package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.tlv.TlvTag
import java.util.Collections

/**
 * What a data object's value octets say, read according to the format EMV states for its tag.
 *
 * A value, never a rendering. Nothing here is a formatted string and no variant decides how it
 * should look: [Digits] keeps the leading zeroes the card wrote, [Date] keeps a two-digit year
 * because that is what the card wrote, and [Amount] places no decimal point. Presentation belongs
 * to the caller, which for this project is the CLI, and a decoder that returned display text would
 * have made that decision for it.
 *
 * The variants divide by what the octets are, not by which tag they came from: `5F2A` and `9C` are
 * both [Digits] though one is a currency and the other a transaction type. What a value *means*
 * beyond its format — that `0826` is the pound sterling — is a further step this library does not
 * take.
 *
 * EMV Book 3 §4.3, Data Element Format Conventions.
 */
public sealed interface DecodedValue {
    /**
     * Decimal digits, one per nibble.
     *
     * Leading zeroes are kept. In `n` they are not decoration but the padding §4.3 specifies, and
     * dropping them would turn the currency code `0826` into `826`, which is a different code. In
     * `cn` the trailing `F` padding is gone instead, because that is padding rather than a digit.
     *
     * A string and not a number, because these digits are an identifier at least as often as they
     * are a quantity: an account number with a leading zero is not the integer it would parse to.
     * The one place EMV really does mean a quantity has its own variant, [Amount].
     */
    public data class Digits(
        public val digits: String,
    ) : DecodedValue

    /**
     * Characters, one per octet.
     *
     * [padding] records any trailing filler that was taken off, so a padded value can be read
     * without the padding and still be known to have been padded. See [TextPadding].
     */
    public data class Text(
        public val text: String,
        public val padding: TextPadding = TextPadding.None,
    ) : DecodedValue

    /**
     * A date as the card wrote it: two digits of year, and no century.
     *
     * EMV codes these as `YYMMDD` and states no century, so neither does this. Windowing `26` into
     * 2026 would be a policy — a sound one for an expiration date and the wrong one for the
     * effective date of a card issued in the nineties — and policy belongs to whoever is reading
     * the card, not to a decoder. That is also why no `java.time` value is produced: a `LocalDate`
     * cannot be built without inventing the two digits EMV left out.
     *
     * [month] is `1..12` and [day] is within that month's length in a year ending [yearOfCentury];
     * see `ValueDecoder` for how much of February the two digits settle on their own.
     *
     * @property yearOfCentury the two year digits, `0..99`, exactly as they were written.
     */
    public data class Date(
        public val yearOfCentury: Int,
        public val month: Int,
        public val day: Int,
    ) : DecodedValue

    /**
     * An amount, as a whole number of minor units.
     *
     * No decimal point is placed, and none can be: where it goes is the currency's exponent, which
     * lives in another data object entirely (`5F2A` or `9F42`, and the exponent `5F36`/`9F44`
     * beside it). Pairing an amount with a currency is a reading of the whole tree, so it belongs
     * above a decoder that is handed one data object at a time. `12345` is twelve thousand three
     * hundred and forty-five minor units, and this library declines to guess whether that is
     * £123.45 or ¥12345.
     */
    public data class Amount(
        public val minorUnits: Long,
    ) : DecodedValue

    /**
     * Track 2 Equivalent Data (tag `57`), decoded into its fields.
     *
     * The magnetic-stripe track as the chip carries it: the [pan], then the card's [expiry] and
     * [serviceCode], then whatever [discretionaryData] the issuer put after them. On the wire
     * these are packed BCD, the PAN split from the rest by a `D` nibble and the whole thing padded
     * with `F` to a full octet; none of that survives here, only the fields. `ValueDecoder` reads
     * the structure; interpreting it — what the service-code digits mean, which century the year
     * belongs to — is left to the caller.
     *
     * Cardholder data, all of it, which is why tag `57` is wrapped in [Sensitive]: the value, and
     * [pan] above all, is reached only through [Sensitive.reveal], the deliberate unwrap that reads
     * at the call site as the decision it is. [toString] is redacted here as well, so a revealed
     * value that finds its way into a log or an exception message still does not print the PAN —
     * the same guard [RawBinary] and [Sensitive] keep.
     *
     * Not a data class, for the reason [RawBinary] is not one: a generated `toString` would print
     * every field, and a generated `copy`/`componentN` would hand the PAN out without anyone
     * naming it.
     *
     * @property pan the Application PAN, the digits before the separator.
     * @property expiry the expiration date the track carries — see [Expiry].
     * @property serviceCode the three service-code digits, kept as digits and not interpreted.
     * @property discretionaryData the issuer's discretionary digits after the service code, empty
     *   when there are none. The `F` padding is not part of it.
     */
    public class Track2(
        public val pan: String,
        public val expiry: Expiry,
        public val serviceCode: String,
        public val discretionaryData: String,
    ) : DecodedValue {
        /**
         * A Track 2 expiry: two digits of year and a month, and nothing more.
         *
         * No century, for the reason [Date] gives — windowing `26` into a full year is the
         * reader's policy — and no day, because Track 2 states none. [month] is `1..12`;
         * [yearOfCentury] is the two digits exactly as written, `0..99`.
         *
         * Its `toString` prints these two numbers, where the parent [Track2] redacts its own. That
         * is deliberate and not an oversight: the redaction guards the accidental log of the whole
         * value, PAN included, whereas a field reached only past [Sensitive.reveal] is an ordinary
         * value — as [Track2.pan] is, a bare `String` once revealed — and prints like one. An
         * expiry with no PAN beside it is also what the dictionary marks non-sensitive as `5F24`.
         */
        public data class Expiry(
            public val yearOfCentury: Int,
            public val month: Int,
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Track2) return false
            return pan == other.pan &&
                expiry == other.expiry &&
                serviceCode == other.serviceCode &&
                discretionaryData == other.discretionaryData
        }

        override fun hashCode(): Int {
            var result = pan.hashCode()
            result = HASH_FACTOR * result + expiry.hashCode()
            result = HASH_FACTOR * result + serviceCode.hashCode()
            result = HASH_FACTOR * result + discretionaryData.hashCode()
            return result
        }

        /** Redacted: this is cardholder data, and a `toString` is exactly how it reaches a log. */
        override fun toString(): String = "Track2(redacted)"

        private companion object {
            private const val HASH_FACTOR: Int = 31
        }
    }

    /**
     * The value octets, undecoded.
     *
     * What `b` gets, and what a primitive `var.` gets as well, since §4.3 defines `var.` as any
     * bit combination and that is what `b` is too. A cryptogram (`9F26`) and the Issuer
     * Application Data (`9F10`) arrive here and go no further: they are opaque to this library by
     * design, not merely undecoded yet.
     *
     * Not a data class, for the reason `TlvNode` is not one: a data class would compare the array
     * by identity. The octets are copied in and copied out.
     */
    public class RawBinary(
        bytes: ByteArray,
    ) : DecodedValue {
        private val octets: ByteArray = bytes.copyOf()

        /** The value octets, exactly as they appear on the wire. A fresh copy on every call. */
        public fun bytes(): ByteArray = octets.copyOf()

        /** How many octets there are, without handing them out. */
        public val size: Int
            get() = octets.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawBinary) return false
            return octets.contentEquals(other.octets)
        }

        override fun hashCode(): Int = octets.contentHashCode()

        /** The count and not the content: a `toString` is exactly how bytes reach a log. */
        override fun toString(): String = "RawBinary($size octets)"
    }

    /**
     * A bit field: value octets read against the meaning EMV fixes for each bit position.
     *
     * The Application Interchange Profile (`82`), the Terminal Verification Results (`95`) and the
     * Issuer Action Codes (`9F0D`/`9F0E`/`9F0F`, which share the TVR's layout), and the Application
     * Usage Control (`9F07`) are rows of single-bit flags. The Cryptogram Information Data (`9F27`)
     * and the CVM Results (`9F34`) mix flags with small enums — a group of bits read together, like
     * the CID's cryptogram type. So two lists: [flags] for the bits that were set, and [selections]
     * for the multi-bit fields and the value each chose.
     *
     * [flags] carries each set bit with the meaning Book 3 states for its position; a set bit no
     * meaning names — reserved for a future version, or not yet in the table — is surfaced as
     * `"RFU"` rather than dropped, since a bit set where the spec reserves one is the anomaly an
     * inspection tool is looking for. [selections] carries each enum field's chosen [EnumSelection],
     * whose meaning is the name EMV gives that value, or `"RFU"` for a value the spec does not
     * define.
     *
     * None of these tags is cardholder data, so — unlike [RawBinary] and [Track2] — [toString]
     * prints the meanings, which are the point of decoding a bit field at all. Not a data class for
     * the reason [RawBinary] is not: the value is a `ByteArray`, which a data class would compare by
     * identity. The octets are copied in and copied out, and [equals] compares both the octets and
     * the interpretation, because `82` and `9F07` are each two octets and identical bytes under
     * different tags decode to different flags.
     *
     * EMV Book 3 v4.4, Annex C (C1 AIP, C2 AUC, C5 TVR) and Table 15 (CID); CVM Results is Book 4
     * v4.4, Annex A4, with its method and condition codes from Book 3 Annex C3.
     */
    public class BitField(
        bytes: ByteArray,
        flags: List<SetFlag>,
        selections: List<EnumSelection>,
    ) : DecodedValue {
        private val octets: ByteArray = bytes.copyOf()

        /** The bits that were set, each with the meaning EMV gives its position, in wire order. */
        public val flags: List<SetFlag> = Collections.unmodifiableList(flags.toList())

        /**
         * The multi-bit fields and the value each selected: a CID's cryptogram type and
         * reason/advice code, a CVM Results method, condition and result. Empty for a tag that is
         * pure flags, and in the order the spec lists the fields.
         */
        public val selections: List<EnumSelection> = Collections.unmodifiableList(selections.toList())

        /** The value octets, exactly as they appear on the wire. A fresh copy on every call. */
        public fun bytes(): ByteArray = octets.copyOf()

        /** How many octets there are, without handing them out. */
        public val size: Int
            get() = octets.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is BitField) return false
            return octets.contentEquals(other.octets) &&
                flags == other.flags &&
                selections == other.selections
        }

        override fun hashCode(): Int {
            var result = octets.contentHashCode()
            result = HASH_FACTOR * result + flags.hashCode()
            result = HASH_FACTOR * result + selections.hashCode()
            return result
        }

        /** The meanings, not a count: this is not cardholder data, and the meanings are the point. */
        override fun toString(): String {
            val parts = selections.map { "${it.label}=${it.meaning}" } + flags.map { it.meaning }
            return "BitField(${parts.joinToString()})"
        }

        /**
         * One set bit and what EMV says it means.
         *
         * @property byteIndex which octet of the value, from zero — Book 3's "Byte 1" is index 0.
         * @property bit which bit of that octet, EMV's `b1`..`b8`, where `b8` is the most
         *   significant, `0x80`.
         * @property meaning the Book 3 wording for this position, or `"RFU"` for a set bit no rule
         *   names.
         */
        public data class SetFlag(
            public val byteIndex: Int,
            public val bit: Int,
            public val meaning: String,
        )

        /**
         * One multi-bit field and the value it selected.
         *
         * Where a [SetFlag] is a single bit that is on, a selection is a group of bits read together
         * as a small enum: the CID cryptogram type (`b8 b7`), its reason/advice code (`b3 b2 b1`),
         * or a CVM Results method, condition or result.
         *
         * @property byteIndex which octet of the value, from zero.
         * @property label what the field is, e.g. `"Cryptogram type"`.
         * @property value the selected bits, shifted down so the field reads from zero.
         * @property meaning what EMV calls that value, or `"RFU"` for one the spec does not define.
         */
        public data class EnumSelection(
            public val byteIndex: Int,
            public val label: String,
            public val value: Int,
            public val meaning: String,
        )

        /**
         * The cryptogram a card returned, from the top two bits of the CID (`9F27`).
         *
         * The canonical value set behind the [EnumSelection] labelled [CRYPTOGRAM_TYPE_LABEL]: its
         * constant names are that selection's meanings and its ordinals are that selection's
         * [EnumSelection.value], so a caller recovers the typed value with
         * `CryptogramType.entries[selection.value]`. No `AAR`: EMV 4.4 codes `b8 b7` of `11` as RFU.
         */
        public enum class CryptogramType {
            AAC,
            TC,
            ARQC,
            RFU,
        }

        public companion object {
            /** The [EnumSelection.label] the CID cryptogram type is carried under; see [CryptogramType]. */
            public const val CRYPTOGRAM_TYPE_LABEL: String = "Cryptogram type"

            private const val HASH_FACTOR: Int = 31
        }
    }

    /**
     * A Data Object List: the (tag, length) entries a terminal must fill in.
     *
     * The PDOL (`9F38`), CDOL1 (`8C`) and CDOL2 (`8D`) are lists of requests, not of values — each
     * entry names a data element by [Entry.tag] and states how many octets, [Entry.length], the
     * terminal must supply for it when it builds the GET PROCESSING OPTIONS or GENERATE AC command.
     * There are no values here to decode: a DOL is the question, and the terminal's command is the
     * answer.
     *
     * The entries are in wire order. A tag's name is not stored — it is looked up from the
     * dictionary when the list is rendered, the same as any other tag — so this carries only what
     * the octets state. None of it is cardholder data.
     *
     * Not a data class, for the reason [BitField] is not: the list is copied in and wrapped so a
     * caller cannot reach back into it, and [equals] compares the entries in order.
     *
     * EMV Book 3 §5.4, Rules for Using a Data Object List (DOL).
     */
    public class Dol(
        entries: List<Entry>,
    ) : DecodedValue {
        /** The (tag, length) requests, in wire order. */
        public val entries: List<Entry> = Collections.unmodifiableList(entries.toList())

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Dol) return false
            return entries == other.entries
        }

        override fun hashCode(): Int = entries.hashCode()

        /** The tags and lengths, which are the point of decoding a DOL and carry nothing sensitive. */
        override fun toString(): String =
            entries.joinToString(prefix = "Dol(", postfix = ")") { "${it.tag.hex}:${it.length}" }

        /**
         * One DOL entry: a request for [length] octets of the data element named by [tag].
         *
         * A request, with no value of its own — the value is what the terminal fills in later. The
         * [tag] is a full BER-TLV tag, so it may be multi-byte; [length] is the octet count the DOL
         * states, read as a BER-TLV length so the long form is admitted though a DOL rarely needs it.
         */
        public data class Entry(
            public val tag: TlvTag,
            public val length: Int,
        )
    }

    /**
     * This data object's value is other data objects, so there is no scalar here to decode.
     *
     * Decided by bit 6 of the first identifier octet and nothing else, the same rule `TlvParser`
     * recurses on — never by the Format column, which says `var.` for the templates and equally
     * for the primitives `80` and `94`. The value is not lost: it is the node's children, and
     * `TlvNode.valueBytes` still holds the octets they were parsed from.
     */
    public data object Constructed : DecodedValue

    /**
     * Cardholder data, which is not to be displayed unless displaying it was asked for.
     *
     * The wrapped value is **private**. There is no public `val`, no `component1`, and no `copy`,
     * so it cannot be destructured out, printed by an auto-generated `toString`, or reached at all
     * except by naming [reveal] — which greps, and which reads at the call site as the decision it
     * is. A `when` over [DecodedValue] that forgets this variant fails to compile rather than
     * printing a PAN, and that is the whole point of it being a variant and not a flag beside one.
     *
     * [toString] is redacted however the value is handled, because the leak this guards against is
     * not a considered `println` but an exception message, a log line, or a debugger.
     *
     * Applied to every entry the dictionary marks `TagInfo.isSensitive`, at one place in
     * `ValueDecoder`, so what is masked is a property of the tag rather than of the code path that
     * happened to decode it. Today that is `5A` and `57`.
     */
    public class Sensitive(
        value: DecodedValue,
    ) : DecodedValue {
        init {
            // Nothing needs a doubly-wrapped value, and one would let reveal() return something
            // still sensitive to a caller that had already decided it was allowed to look.
            require(value !is Sensitive) { "a sensitive value is not wrapped twice" }
        }

        private val payload: DecodedValue = value

        /** The value this holds. Say it deliberately; masking it is the default for a reason. */
        public fun reveal(): DecodedValue = payload

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Sensitive) return false
            return payload == other.payload
        }

        override fun hashCode(): Int = payload.hashCode()

        override fun toString(): String = "Sensitive(redacted)"
    }
}

/**
 * Trailing filler taken off a text value before decoding it.
 *
 * EMV §4.3 admits no space to `an` and no null to either `an` or `ans`, yet cards pad with both.
 * Rejecting a padded value outright would leave an analyst holding an error where a language
 * preference should be, and silently trimming it would hide that the card is off-spec. So the
 * padding is trimmed *and* reported, and this is what reports it.
 *
 * Absence is [None] rather than a null, as it is everywhere else in this library.
 */
public sealed interface TextPadding {
    /** Nothing was taken off. */
    public data object None : TextPadding

    /**
     * A run of filler octets at the end of the value was taken off.
     *
     * Not a data class, and the octets are copied and wrapped, for the reason `TlvNode.children`
     * is: Kotlin's read-only `List` and `MutableList` are one type on the JVM. Without the copy a
     * caller could empty the list they passed to the constructor and leave this holding no octets
     * at all — the one state [init] exists to forbid, and reachable in plain Kotlin without a cast
     * — and without the wrapper a cast could do the same afterwards.
     *
     * @property offset the index of the first stripped octet, in the buffer that was parsed.
     */
    public class Stripped(
        public val offset: Int,
        octets: List<Int>,
    ) : TextPadding {
        /** The stripped octets, in the order they appear, each unsigned, `0..255`. */
        public val octets: List<Int> = Collections.unmodifiableList(octets.toList())

        init {
            require(this.octets.isNotEmpty()) { "stripped padding of no octets is None" }
        }

        /** How many octets were taken off. */
        public val octetCount: Int
            get() = octets.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Stripped) return false
            return offset == other.offset && octets == other.octets
        }

        override fun hashCode(): Int = HASH_FACTOR * offset + octets.hashCode()

        /**
         * Prints the octets, unlike its neighbours: a stripped run is filler by construction, so
         * there is nothing off the card in it, and a test that compares two of these is unreadable
         * if they both print as a count.
         */
        override fun toString(): String = "Stripped(offset=$offset, octets=$octets)"

        private companion object {
            private const val HASH_FACTOR: Int = 31
        }
    }
}
