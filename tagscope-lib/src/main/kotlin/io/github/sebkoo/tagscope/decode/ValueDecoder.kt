package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.tags.TagFormat
import io.github.sebkoo.tagscope.tags.TagInfo
import io.github.sebkoo.tagscope.tlv.TlvNode
import io.github.sebkoo.tagscope.tlv.TlvReader
import io.github.sebkoo.tagscope.tlv.TlvResult
import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * Reads one data object's value octets according to the format EMV states for its tag.
 *
 * Scalars, Track 2 (`57`), and the bit fields (AIP, TVR and the Issuer Action Codes, AUC, CID and
 * CVM Results). Every other `b` and primitive `var.` is handed back as octets, and reading the
 * structure inside `94` is a later step; a cryptogram is handed back as octets permanently, by the
 * scope this project keeps.
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
            TagFormat.BINARY -> binary(node, value)
            // §4.3 defines var. as any bit combination, which is what b is with no tag-specific
            // reading, so a primitive var. (80, 94) is handed back as octets exactly as an opaque
            // b tag is. A caller wanting the structure inside 80 or 94 has the octets; reading it
            // is a later commit's job.
            TagFormat.VAR -> DecodeResult.Success(DecodedValue.RawBinary(value))
        }
    }

    /**
     * `b`, which is octets unless the tag says the octets have a structure worth reading: Track 2
     * (`57`), the DF Name (`84`) when it is textual, a Data Object List (`9F38`/`8C`/`8D`), or one
     * of the bit-field tags [BitFieldTable] carries. A cryptogram (`9F26`) and the Issuer
     * Application Data (`9F10`) match none and go no further, opaque to this library by design
     * rather than merely undecoded.
     */
    private fun binary(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult =
        when (node.tag) {
            TRACK2_TAG -> track2(node, value)
            DF_NAME_TAG -> dfName(value)
            CVM_LIST_TAG -> cvmList(node, value)
            in DOL_TAGS -> dol(node, value)
            else ->
                when (val spec = BitFieldTable.specFor(node.tag)) {
                    null -> DecodeResult.Success(DecodedValue.RawBinary(value))
                    else -> bitField(node, value, spec)
                }
        }

    /**
     * The Cardholder Verification Method List (`8E`): two four-octet binary amounts, then a run of
     * two-octet CV Rules. The amounts are Amount X then Amount Y, the thresholds the "under/over X/Y"
     * conditions test against, each an unsigned big-endian integer — read into a `Long` because a
     * full four octets, `0xFFFFFFFF`, overflows a signed `Int`. Each CV Rule that follows is a CVM
     * Code octet and a Condition octet: the code's low six bits are the method, its `b7` (`0x40`) the
     * apply-next flag, its `b8` RFU; the condition octet is taken whole. An amounts-only list — eight
     * octets and no rules — is well-formed and has no rules.
     *
     * Two malformations, returned and never thrown, the same as every other decode: fewer than eight
     * octets cannot hold the two amounts, and an odd octet left after them cannot complete a rule.
     * Both are [DecodeError.MalformedCvmList]; the amounts are read only once the length is known to
     * admit them and the rules to pair up. This performs no cardholder verification — it reads the
     * list the card states, nothing more. EMV Book 3 v4.4, §10.5 and Annex C3.
     */
    private fun cvmList(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult {
        if (value.size < CVM_LIST_AMOUNTS_OCTETS) {
            return DecodeResult.Failure(DecodeError.MalformedCvmList(node.tag, node.offset))
        }
        if ((value.size - CVM_LIST_AMOUNTS_OCTETS) % CVM_RULE_OCTETS != 0) {
            return DecodeResult.Failure(
                DecodeError.MalformedCvmList(node.tag, node.valueOffset + value.size - 1),
            )
        }
        val amountX = uInt32(value, 0)
        val amountY = uInt32(value, AMOUNT_FIELD_OCTETS)
        val rules = mutableListOf<DecodedValue.CvmList.CvmRule>()
        var offset = CVM_LIST_AMOUNTS_OCTETS
        while (offset < value.size) {
            val code = value[offset].toInt() and UNSIGNED_OCTET
            val condition = value[offset + 1].toInt() and UNSIGNED_OCTET
            rules +=
                DecodedValue.CvmList.CvmRule(
                    methodCode = code and CVM_METHOD_MASK,
                    applyNextIfFailed = code and CVM_APPLY_NEXT_BIT != 0,
                    conditionCode = condition,
                )
            offset += CVM_RULE_OCTETS
        }
        return DecodeResult.Success(DecodedValue.CvmList(amountX, amountY, rules))
    }

    /** A four-octet unsigned big-endian integer at [at], widened to a `Long` so `0xFFFFFFFF` fits. */
    private fun uInt32(
        value: ByteArray,
        at: Int,
    ): Long {
        var result = 0L
        for (index in 0 until AMOUNT_FIELD_OCTETS) {
            result = (result shl BITS_PER_OCTET) or (value[at + index].toLong() and UNSIGNED_OCTET.toLong())
        }
        return result
    }

    /**
     * A Data Object List (`9F38` PDOL, `8C` CDOL1, `8D` CDOL2): a run of (tag, length) entries with
     * no values, naming the data elements a terminal must supply for GET PROCESSING OPTIONS or
     * GENERATE AC. Each entry is a BER-TLV tag followed by a BER-TLV length, read with the very
     * readers `TlvParser` uses — so a multi-byte tag and the long-form length are handled the same
     * way here as anywhere else, though a DOL rarely needs either. No value octets follow a length:
     * the length is a *request* for octets the terminal will supply, not a count present in the DOL.
     *
     * An empty value is an empty DOL, which is well-formed. A tag or length field that runs off the
     * end, or a malformed tag, is [DecodeError.MalformedDol] pointing at the entry that failed —
     * returned, never thrown, the same as every other decode.
     */
    private fun dol(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult {
        val entries = mutableListOf<DecodedValue.Dol.Entry>()
        var offset = 0
        while (offset < value.size) {
            val tag =
                when (val read = TlvReader.readTag(value, offset)) {
                    is TlvResult.Failure -> return malformedDol(node, offset)
                    is TlvResult.Success -> read.value
                }
            val lengthOffset = offset + tag.octetLength
            val length =
                when (val read = TlvReader.readLength(value, lengthOffset)) {
                    is TlvResult.Failure -> return malformedDol(node, offset)
                    is TlvResult.Success -> read.value
                }
            entries += DecodedValue.Dol.Entry(tag, length.value)
            offset = lengthOffset + length.octetLength
        }
        return DecodeResult.Success(DecodedValue.Dol(entries))
    }

    /** A DOL entry that could not be read, named by its first octet in the parsed buffer. */
    private fun malformedDol(
        node: TlvNode,
        entryOffset: Int,
    ): DecodeResult = DecodeResult.Failure(DecodeError.MalformedDol(node.tag, node.valueOffset + entryOffset))

    /**
     * DF Name (`84`): the name of the selected file. Annex A formats it `b`, but the value is a
     * registered identifier that is textual for a directory — the PSE's is the ASCII
     * `1PAY.SYS.DDF01` — and binary for an ADF, where it is the AID (`A0000000031010`). So it is
     * read as text when every octet is printable, since a name is meant to be read, and handed back
     * as octets otherwise, exactly as any opaque `b` is.
     *
     * No filler is stripped and none is tolerated: a DF Name states no padding, so a trailing `00`
     * or space belongs to the name or is the anomaly worth seeing as hex, not something to trim. An
     * empty value is octets, not an empty string.
     */
    private fun dfName(value: ByteArray): DecodeResult =
        if (value.isNotEmpty() && value.all { isCommonCharacter(it.toInt() and UNSIGNED_OCTET) }) {
            // Every octet is printable ASCII, so this decoding cannot substitute or replace anything.
            DecodeResult.Success(DecodedValue.Text(String(value, Charsets.US_ASCII)))
        } else {
            DecodeResult.Success(DecodedValue.RawBinary(value))
        }

    /**
     * A row of flags per octet, read against the meanings EMV fixes for each bit position.
     *
     * The length is checked first: a bit field is a fixed width, and a value of any other length
     * has nothing to read against the table — the same fixed-shape failure a mis-sized date or
     * amount is. It is [DecodeError.UnexpectedValueLength], carrying the object's own offset, and it
     * may carry the lengths because no bit-field tag is cardholder data.
     *
     * Then each named bit that is set becomes a [DecodedValue.BitField.SetFlag], each enum field
     * becomes a [DecodedValue.BitField.EnumSelection] carrying the value it chose, and any set bit
     * no rule named is surfaced as `"RFU"` rather than dropped — a bit set where the spec reserves
     * one is the anomaly the tool exists to show. The flags come out in wire order, the most
     * significant bit of the lowest octet first, so they read as the spec's tables do; the
     * selections come out in the order the spec lists the fields.
     */
    private fun bitField(
        node: TlvNode,
        value: ByteArray,
        spec: BitFieldSpec,
    ): DecodeResult {
        if (value.size != spec.octetLength) {
            return DecodeResult.Failure(
                DecodeError.UnexpectedValueLength(node.tag, node.offset, spec.octetLength, value.size),
            )
        }
        val flags = mutableListOf<DecodedValue.BitField.SetFlag>()
        val selections = mutableListOf<DecodedValue.BitField.EnumSelection>()
        val named = IntArray(value.size)
        for (rule in spec.bits) {
            val mask = 1 shl (rule.bit - 1)
            named[rule.byteIndex] = named[rule.byteIndex] or mask
            if (value[rule.byteIndex].toInt() and mask != 0) {
                flags += DecodedValue.BitField.SetFlag(rule.byteIndex, rule.bit, rule.meaning)
            }
        }
        for (rule in spec.enums) {
            named[rule.byteIndex] = named[rule.byteIndex] or rule.mask
            val octet = value[rule.byteIndex].toInt() and UNSIGNED_OCTET
            val selected = (octet and rule.mask) ushr Integer.numberOfTrailingZeros(rule.mask)
            selections +=
                DecodedValue.BitField.EnumSelection(
                    rule.byteIndex,
                    rule.label,
                    selected,
                    rule.meanings[selected] ?: RFU,
                )
        }
        for (index in value.indices) {
            val unnamed = value[index].toInt() and UNSIGNED_OCTET and named[index].inv()
            for (bit in 1..BITS_PER_OCTET) {
                if (unnamed and (1 shl (bit - 1)) != 0) {
                    flags += DecodedValue.BitField.SetFlag(index, bit, RFU)
                }
            }
        }
        flags.sortWith(compareBy<DecodedValue.BitField.SetFlag> { it.byteIndex }.thenByDescending { it.bit })
        return DecodeResult.Success(DecodedValue.BitField(value, flags, selections))
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
     * Track 2 Equivalent Data (`57`): the PAN, the fields after it, and the `D` nibble between.
     *
     * The value is packed BCD, one digit per nibble, laid out as PAN, a `D` separator, expiry
     * (`YYMM`), service code (three digits), discretionary data, then `F` padding to a whole octet.
     * This walks it nibble by nibble, high before low, splitting on the single `D` and dropping the
     * trailing `F` run.
     *
     * The month is checked `1..12`; nothing else about the expiry is, since the year has no century
     * to test against — the same reading the scalar dates take. The PAN is bounded at nineteen
     * digits and the fields after the separator must be at least seven, enough to hold the expiry
     * and service code; the discretionary data is whatever digits are left.
     *
     * Every failure names an octet or a count and never a digit, because this is the one object
     * that carries the PAN outright: an error that echoed a value octet would be two digits of a
     * PAN in whatever log it reached. See [DecodeError].
     */
    private fun track2(
        node: TlvNode,
        value: ByteArray,
    ): DecodeResult {
        val pan = StringBuilder()
        val rest = StringBuilder()
        var sawSeparator = false
        var padding = false
        for (index in value.indices) {
            val octet = value[index].toInt() and UNSIGNED_OCTET
            for (shift in NIBBLE_SHIFTS) {
                val nibble = (octet ushr shift) and LOW_NIBBLE
                when {
                    // A non-F nibble once padding has begun — a digit, or a stray D — is not
                    // padding, so the F before it was not padding either.
                    padding && nibble != PADDING_NIBBLE ->
                        return DecodeResult.Failure(
                            DecodeError.MisplacedPadding(node.tag, node.valueOffset + index),
                        )
                    nibble == PADDING_NIBBLE -> padding = true
                    nibble == SEPARATOR_NIBBLE ->
                        if (sawSeparator) {
                            return DecodeResult.Failure(
                                DecodeError.Track2MultipleSeparators(node.tag, node.valueOffset + index),
                            )
                        } else {
                            sawSeparator = true
                        }
                    nibble > LARGEST_DIGIT ->
                        return DecodeResult.Failure(
                            DecodeError.NotBcd(node.tag, node.valueOffset + index),
                        )
                    sawSeparator -> rest.append('0' + nibble)
                    else -> pan.append('0' + nibble)
                }
            }
        }

        if (!sawSeparator) {
            return DecodeResult.Failure(DecodeError.Track2NoSeparator(node.tag, node.offset))
        }
        if (pan.length > MAX_PAN_DIGITS) {
            return DecodeResult.Failure(
                DecodeError.Track2PanTooLong(node.tag, node.offset, pan.length, MAX_PAN_DIGITS),
            )
        }
        if (rest.length < TRACK2_FIXED_FIELD_DIGITS) {
            return DecodeResult.Failure(
                DecodeError.Track2MissingFields(node.tag, node.offset, rest.length, TRACK2_FIXED_FIELD_DIGITS),
            )
        }

        val month = rest.substring(EXPIRY_MONTH_START, SERVICE_CODE_START).toInt()
        if (month !in 1..MONTHS_IN_YEAR) {
            // The month's first nibble sits at PAN digits + one separator + two year digits into
            // the value; its octet may also hold the year digit before it, since the fields are
            // nibble-granular. Offset only, never the month itself.
            val monthNibble = pan.length + 1 + EXPIRY_MONTH_START
            return DecodeResult.Failure(
                DecodeError.Track2MonthOutOfRange(node.tag, node.valueOffset + monthNibble / NIBBLES_PER_OCTET),
            )
        }
        return DecodeResult.Success(
            DecodedValue.Track2(
                pan = pan.toString(),
                expiry =
                    DecodedValue.Track2.Expiry(
                        yearOfCentury = rest.substring(0, EXPIRY_MONTH_START).toInt(),
                        month = month,
                    ),
                serviceCode = rest.substring(SERVICE_CODE_START, DISCRETIONARY_START),
                discretionaryData = rest.substring(DISCRETIONARY_START),
            ),
        )
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

    /** Track 2 Equivalent Data, the one `b` object this library reads the structure of. */
    private val TRACK2_TAG: TlvTag = TlvTag(value = 0x57, octetLength = 1)

    /** DF Name (`84`): `b` in Annex A, but textual for a PSE directory and binary for an AID. */
    private val DF_NAME_TAG: TlvTag = TlvTag(value = 0x84, octetLength = 1)

    /** Cardholder Verification Method List (`8E`), read into its amounts and CV Rules. */
    private val CVM_LIST_TAG: TlvTag = TlvTag(value = 0x8E, octetLength = 1)

    /** The Data Object Lists this library reads into (tag, length) entries: PDOL, CDOL1, CDOL2. */
    private val DOL_TAGS: Set<TlvTag> =
        setOf(
            TlvTag(value = 0x9F38, octetLength = 2),
            TlvTag(value = 0x8C, octetLength = 1),
            TlvTag(value = 0x8D, octetLength = 1),
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

    /** A CVM List's two four-octet amounts, before any CV Rules. */
    private const val CVM_LIST_AMOUNTS_OCTETS: Int = 8

    /** One CVM List amount field: a four-octet binary integer. */
    private const val AMOUNT_FIELD_OCTETS: Int = 4

    /** One CV Rule: a CVM Code octet and a Condition octet. */
    private const val CVM_RULE_OCTETS: Int = 2

    /** The CVM Code's method, bits `b6..b1`. */
    private const val CVM_METHOD_MASK: Int = 0x3F

    /** The CVM Code's `b7`: apply the succeeding CV Rule if this CVM is unsuccessful. */
    private const val CVM_APPLY_NEXT_BIT: Int = 0x40

    /** The `D` nibble that separates the PAN from the fields after it in Track 2. */
    private const val SEPARATOR_NIBBLE: Int = 0x0D

    /** ISO/IEC 7813 caps the Track 2 PAN at nineteen digits. */
    private const val MAX_PAN_DIGITS: Int = 19

    /** The digits that must follow the Track 2 separator: four of expiry (`YYMM`), three of service. */
    private const val TRACK2_FIXED_FIELD_DIGITS: Int = 7

    /** Where each field starts in the run of digits after the Track 2 separator, in digits. */
    private const val EXPIRY_MONTH_START: Int = 2
    private const val SERVICE_CODE_START: Int = 4
    private const val DISCRETIONARY_START: Int = 7

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

    /** Bits to a bit-field octet, and the meaning given a set bit the tables do not name. */
    private const val BITS_PER_OCTET: Int = 8
    private const val RFU: String = "RFU"

    /** Widens a JVM byte, which is signed, to the octet it actually is. */
    private const val UNSIGNED_OCTET: Int = 0xFF

    private const val SPACE: Int = 0x20
    private const val NULL: Int = 0x00
    private const val FIRST_PRINTABLE: Int = 0x20
    private const val LAST_PRINTABLE: Int = 0x7E
}
