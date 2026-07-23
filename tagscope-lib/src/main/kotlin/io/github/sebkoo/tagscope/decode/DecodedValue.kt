package io.github.sebkoo.tagscope.decode

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
