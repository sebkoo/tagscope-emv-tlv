package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.tags.TagFormat
import io.github.sebkoo.tagscope.tags.TagInfo
import io.github.sebkoo.tagscope.tlv.TlvNode
import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * Reads one data object's value octets according to the format EMV states for its tag.
 *
 * Scalars only. `b` and a primitive `var.` are handed back as octets, and reading the structure
 * inside `57`, `94` or a bit field is a later step; a cryptogram is handed back as octets
 * permanently, by the scope this project keeps.
 *
 * The rules it applies:
 *
 * - **The wire decides whether there is a scalar at all.** A constructed object decodes to
 *   [DecodedValue.Constructed] before the Format column is looked at, because bit 6 of the first
 *   identifier octet is what says the value is other data objects — the same rule `TlvParser`
 *   recurses on. §4.3's `var.` is about length and would answer the wrong question.
 * - **The format decides the rest**, and where a format is not enough, the tag: EMV states
 *   `YYMMDD` and `n 12` in Annex A's Description column rather than in its Format column, so
 *   [DATE_TAGS] and [AMOUNT_TAGS] name the objects that carry them.
 * - **The dictionary's length bounds are never consulted.** They are advisory (see [TagInfo]), so
 *   a value shorter or longer than expected still decodes. The only lengths checked are the two
 *   the decoding rule itself fixes: six digits of date need three octets and twelve of amount need
 *   six, and neither is a bound that could be relaxed without changing what is being read.
 * - **Cardholder data is wrapped once, here**, at the single point every path returns through, so
 *   what gets masked is a property of the tag rather than of the branch that decoded it.
 * - **Failures are returned, never thrown**, and carry an offset into the buffer that was parsed.
 *
 * Pure: no I/O, no shared state, and the value octets are never mutated.
 *
 * EMV Book 3 §4.3, Data Element Format Conventions; Annex A for which tag holds what.
 */
public object ValueDecoder {
    /**
     * What [node]'s value octets say, given what EMV says about its tag.
     *
     * [info] must describe [node]'s own tag. Handing over another entry is a mistake in the
     * caller rather than something wrong with the card, so it throws where malformed input would
     * be returned — the same division `TlvNode` and `TagInfo` already draw.
     */
    public fun decode(
        node: TlvNode,
        info: TagInfo,
    ): DecodeResult {
        require(node.tag == info.tag) {
            "info describes ${info.tag.hex}, but the node is ${node.tag.hex}"
        }
        return when (val decoded = decodeValue(node, info)) {
            is DecodeResult.Failure -> decoded
            is DecodeResult.Success ->
                if (info.isSensitive) {
                    DecodeResult.Success(DecodedValue.Sensitive(decoded.value))
                } else {
                    decoded
                }
        }
    }

    /** Everything except the masking, which [decode] applies to whatever comes back from here. */
    private fun decodeValue(
        node: TlvNode,
        info: TagInfo,
    ): DecodeResult {
        if (node.tag.isConstructed) {
            return DecodeResult.Success(DecodedValue.Constructed)
        }
        val value = node.valueBytes()
        return when (info.format) {
            TagFormat.NUMERIC -> numeric(node, value)
            TagFormat.COMPRESSED_NUMERIC -> digits(node, value, padsWithF = true)
            TagFormat.ALPHANUMERIC -> text(node, value, ::isAlphanumeric, ::isSpaceOrNull)
            TagFormat.ALPHANUMERIC_SPECIAL -> text(node, value, ::isCommonCharacter, ::isNull)
            // §4.3 defines var. as any bit combination, which is what b is, so the two answer the
            // same way. Nothing is lost: a caller wanting the structure inside 80 or 94 has the
            // octets, and reading it is a later commit's job.
            TagFormat.BINARY, TagFormat.VAR -> DecodeResult.Success(DecodedValue.RawBinary(value))
        }
    }

    /** `n`, which is digits unless the tag says the digits are a date or an amount. */
    private fun numeric(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult =
        when (node.tag) {
            in DATE_TAGS -> date(node, value)
            in AMOUNT_TAGS -> amount(node, value)
            else -> digits(node, value, padsWithF = false)
        }

    private fun digits(
        node: TlvNode,
        value: ByteArray,
        padsWithF: Boolean,
    ): DecodeResult =
        when (val read = readDigits(node, value, padsWithF)) {
            is Digits.Failed -> DecodeResult.Failure(read.error)
            is Digits.Read -> DecodeResult.Success(DecodedValue.Digits(read.digits))
        }

    /**
     * `YYMMDD`, validated as a calendar date as far as it can be without a century.
     *
     * The month is checked first because it is what the day is checked against. A day is judged
     * against that month's own length, so a 31st of June fails: it is impossible in every century,
     * and a flat `01..31` would have let it through while claiming the missing century as the
     * reason. February is judged against the year as well — see [longestMonth], which is where the
     * missing century turns out to cost far less than it appears to.
     */
    private fun date(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult {
        if (value.size != DATE_OCTETS) {
            return DecodeResult.Failure(
                DecodeError.UnexpectedValueLength(node.tag, node.offset, DATE_OCTETS, value.size),
            )
        }
        val digits =
            when (val read = readDigits(node, value, padsWithF = false)) {
                is Digits.Failed -> return DecodeResult.Failure(read.error)
                is Digits.Read -> read.digits
            }

        val month = digits.substring(2, 4).toInt()
        if (month !in 1..MONTHS_IN_YEAR) {
            return DecodeResult.Failure(
                DecodeError.MonthOutOfRange(node.tag, node.valueOffset + MONTH_OCTET, month),
            )
        }
        val yearOfCentury = digits.substring(0, 2).toInt()
        val maxDay = longestMonth(month, yearOfCentury)
        val day = digits.substring(4, 6).toInt()
        if (day !in 1..maxDay) {
            return DecodeResult.Failure(
                DecodeError.DayOutOfRange(node.tag, node.valueOffset + DAY_OCTET, day, maxDay),
            )
        }
        return DecodeResult.Success(
            DecodedValue.Date(
                yearOfCentury = yearOfCentury,
                month = month,
                day = day,
            ),
        )
    }

    /**
     * How long [month] can be in a year ending [yearOfCentury], for a card that did not say which
     * century it meant.
     *
     * Only February turns on the year, and the two digits settle it nearly everywhere. A full year
     * is `100 × century + yearOfCentury`, and 100 divides by four, so the year divides by four
     * exactly when those two digits do. A year ending 26 therefore divides by four in no century
     * at all and is a leap year in none of them, which makes its 29th of February as impossible as
     * a 31st of June — and it is refused on that same ground rather than waved through as
     * unknowable.
     *
     * The exception is an ending of 00, where the century really does decide: 1900 was not a leap
     * year and 2000 was. That one is admitted, because nothing in `YYMMDD` refutes it. So of the
     * hundred endings a card can write, exactly the twenty-five divisible by four keep a 29th, and
     * the missing century costs this library one of them rather than all seventy-five.
     */
    private fun longestMonth(
        month: Int,
        yearOfCentury: Int,
    ): Int =
        if (month == FEBRUARY && yearOfCentury % LEAP_YEAR_CYCLE == 0) {
            LEAP_FEBRUARY
        } else {
            LONGEST_MONTH[month - 1]
        }

    /** `n 12`, as minor units. No decimal point: see [DecodedValue.Amount]. */
    private fun amount(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult {
        if (value.size != AMOUNT_OCTETS) {
            return DecodeResult.Failure(
                DecodeError.UnexpectedValueLength(node.tag, node.offset, AMOUNT_OCTETS, value.size),
            )
        }
        return when (val read = readDigits(node, value, padsWithF = false)) {
            is Digits.Failed -> DecodeResult.Failure(read.error)
            // Twelve digits at most, so the largest amount EMV can state here is four orders of
            // magnitude inside a Long and the conversion cannot overflow.
            is Digits.Read -> DecodeResult.Success(DecodedValue.Amount(read.digits.toLong()))
        }
    }

    /**
     * The nibbles of a value, as decimal digits.
     *
     * Leading zeroes are kept, since in `n` they are what §4.3 pads with and dropping them would
     * change the value. With [padsWithF] the trailing `F` nibbles `cn` pads with are dropped
     * instead, and a digit found after them is [DecodeError.MisplacedPadding]: padding that is not
     * at the end is not padding, and the value is not a compressed numeric one.
     */
    private fun readDigits(
        node: TlvNode,
        value: ByteArray,
        padsWithF: Boolean,
    ): Digits {
        val digits = StringBuilder(value.size * NIBBLES_PER_OCTET)
        var padded = false
        for (index in value.indices) {
            val octet = value[index].toInt() and UNSIGNED_OCTET
            for (shift in NIBBLE_SHIFTS) {
                val nibble = (octet ushr shift) and LOW_NIBBLE
                when {
                    padsWithF && nibble == PADDING_NIBBLE -> padded = true
                    nibble > LARGEST_DIGIT -> return Digits.Failed(
                        DecodeError.NotBcd(node.tag, node.valueOffset + index),
                    )
                    padded ->
                        return Digits.Failed(
                            DecodeError.MisplacedPadding(node.tag, node.valueOffset + index),
                        )
                    else -> digits.append('0' + nibble)
                }
            }
        }
        return Digits.Read(digits.toString())
    }

    /**
     * One character per octet, with any trailing filler taken off first.
     *
     * [permits] is the format's own character set and [isFiller] the run this library tolerates at
     * the end of a value. The trailing run is found before anything is validated, so a tolerated
     * octet stays an anomaly anywhere else: an embedded space in an `an` value is reported, and so
     * is a trailing octet that is neither space nor null. The first offending octet is reported and
     * the rest of the value is not read.
     */
    private fun text(
        node: TlvNode,
        value: ByteArray,
        permits: (Int) -> Boolean,
        isFiller: (Int) -> Boolean,
    ): DecodeResult {
        var end = value.size
        while (end > 0 && isFiller(value[end - 1].toInt() and UNSIGNED_OCTET)) {
            end--
        }
        for (index in 0 until end) {
            val octet = value[index].toInt() and UNSIGNED_OCTET
            if (!permits(octet)) {
                return DecodeResult.Failure(
                    DecodeError.UnexpectedCharacter(node.tag, node.valueOffset + index, octet),
                )
            }
        }
        val padding =
            if (end == value.size) {
                TextPadding.None
            } else {
                TextPadding.Stripped(
                    offset = node.valueOffset + end,
                    octets = (end until value.size).map { value[it].toInt() and UNSIGNED_OCTET },
                )
            }
        // Every octet up to `end` has been checked against a set that is wholly ASCII, so this
        // decoding cannot substitute or replace anything.
        return DecodeResult.Success(DecodedValue.Text(String(value, 0, end, Charsets.US_ASCII), padding))
    }

    /** §4.3: `an` is "alphabetic (a to z and A to Z, upper and lower case) and numeric (0 to 9)". */
    private fun isAlphanumeric(octet: Int): Boolean =
        octet in '0'.code..'9'.code || octet in 'A'.code..'Z'.code || octet in 'a'.code..'z'.code

    /**
     * §4.3 defers `ans` to the Common Character Set tabulated in Book 4, Annex B, which is not
     * reproduced here. Printable ASCII is this library's reading of it: it admits the space and
     * punctuation the table is there to allow, and refuses control characters and anything with
     * the top bit set, which is where a misread length or a truncated record shows up.
     */
    private fun isCommonCharacter(octet: Int): Boolean = octet in FIRST_PRINTABLE..LAST_PRINTABLE

    /**
     * The filler `an` tolerates at the end of a value.
     *
     * §4.3 admits neither: a space is not alphanumeric and a null is not a character at all. Cards
     * pad with both regardless, most visibly in a language preference, and an inspection tool that
     * answered a padded value with an error alone would be withholding the value over something
     * the analyst can see for themselves. So the run is taken off *and* reported — see
     * [TextPadding] — which is a deliberate leniency with a signal attached, not an oversight.
     */
    private fun isSpaceOrNull(octet: Int): Boolean = octet == SPACE || octet == NULL

    /** The filler `ans` tolerates. A trailing space is left alone: §4.3 admits it to `ans`. */
    private fun isNull(octet: Int): Boolean = octet == NULL

    /** Digits read off a value, or why they could not be. Private, so `null` is not the alternative. */
    private sealed interface Digits {
        data class Read(
            val digits: String,
        ) : Digits

        data class Failed(
            val error: DecodeError,
        ) : Digits
    }

    /**
     * The objects EMV states as `YYMMDD`.
     *
     * Named here rather than in the dictionary because Annex A states it in the Description column
     * and not in the Format column, and the dictionary copies the Format column rather than
     * paraphrasing prose. `9F21`, the transaction *time*, is `HHMMSS` and is not one of these — it
     * is also not in the dictionary yet, which is why nothing distinguishes them but this list.
     */
    private val DATE_TAGS: Set<TlvTag> =
        setOf(
            TlvTag(value = 0x5F24, octetLength = 2),
            TlvTag(value = 0x5F25, octetLength = 2),
            TlvTag(value = 0x9A, octetLength = 1),
        )

    /** The objects EMV states as `n 12` amounts, for the same reason as [DATE_TAGS]. */
    private val AMOUNT_TAGS: Set<TlvTag> =
        setOf(
            TlvTag(value = 0x9F02, octetLength = 2),
            TlvTag(value = 0x9F03, octetLength = 2),
        )

    /**
     * Longest each month can be, indexed from January. February is the common 28 here; the leap
     * year's extra day is [LEAP_FEBRUARY], granted by [longestMonth] to the endings that can earn
     * it.
     */
    private val LONGEST_MONTH: IntArray = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private const val FEBRUARY: Int = 2
    private const val LEAP_FEBRUARY: Int = 29

    /** A year divisible by four is a leap year, save at a century the card did not write. */
    private const val LEAP_YEAR_CYCLE: Int = 4

    /** Six digits of `YYMMDD`, two per octet. */
    private const val DATE_OCTETS: Int = 3

    /** Twelve digits of `n 12`, two per octet. */
    private const val AMOUNT_OCTETS: Int = 6

    /** Where the month and day sit in a `YYMMDD` value, in octets from its start. */
    private const val MONTH_OCTET: Int = 1
    private const val DAY_OCTET: Int = 2

    private const val MONTHS_IN_YEAR: Int = 12

    /** High nibble before low, which is the order the digits are written in. */
    private val NIBBLE_SHIFTS: IntArray = intArrayOf(4, 0)

    private const val NIBBLES_PER_OCTET: Int = 2
    private const val LOW_NIBBLE: Int = 0x0F
    private const val LARGEST_DIGIT: Int = 9
    private const val PADDING_NIBBLE: Int = 0x0F

    /** Widens a JVM byte, which is signed, to the octet it actually is. */
    private const val UNSIGNED_OCTET: Int = 0xFF

    private const val SPACE: Int = 0x20
    private const val NULL: Int = 0x00
    private const val FIRST_PRINTABLE: Int = 0x20
    private const val LAST_PRINTABLE: Int = 0x7E
}
