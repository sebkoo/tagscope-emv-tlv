package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * A data object that is structurally well-formed, but whose value octets do not hold what the
 * format EMV states for its tag says they should.
 *
 * The division from `TlvError` is exactly that: `TlvError` is what stops a payload being read as
 * data objects at all, and this is what a perfectly good data object can still be wrong about —
 * a hex `A` nibble in a field of decimal digits, a thirteenth month. Both are expected input for
 * an inspection tool, so both are returned rather than thrown, and a failure to decode one object
 * says nothing about the next.
 *
 * Every variant carries the [tag] whose value failed and the [offset] at which the failure was
 * found, an index into the buffer `TlvParser` was given rather than into the value — the same
 * coordinate system every `TlvError` uses, so the two kinds of failure name an octet the same way.
 *
 * **An error says where, not what.** [NotBcd] and [MisplacedPadding] carry no octet on purpose:
 * the PAN is `cn`, so an octet in one of those would be two digits of a PAN, in whatever log or
 * exception message the error ends up in. [UnexpectedCharacter] does carry its octet, because only
 * `an` and `ans` produce it and no `an` or `ans` tag in this dictionary is marked sensitive; the
 * date variants carry their numbers on the same reasoning. That reasoning is a fact about the
 * dictionary rather than a law, so `ValueDecoderTest` pins it, and if a sensitive tag is ever
 * given a text or date format the test fails and this comment has to be revisited.
 */
public sealed class DecodeError {
    /** The tag whose value failed to decode. */
    public abstract val tag: TlvTag

    /** Index of the octet at which the failure was found, within the buffer that was parsed. */
    public abstract val offset: Int

    /**
     * A nibble that is not a decimal digit turned up where the format requires one: any of `A`
     * to `F` in an `n` value, or `A` to `E` in a `cn` value, where `F` is padding instead.
     *
     * [offset] is the octet holding the offending nibble; which of the two it was is not reported,
     * for the reason given on this class.
     */
    public data class NotBcd(
        override val tag: TlvTag,
        override val offset: Int,
    ) : DecodeError()

    /**
     * A `cn` value has a digit after its padding started. §4.3 pads `cn` with *trailing* `F`s, so
     * an `F` with a digit behind it is not padding and the value is not a compressed numeric one.
     *
     * [offset] is the octet holding the digit that should not be there.
     */
    public data class MisplacedPadding(
        override val tag: TlvTag,
        override val offset: Int,
    ) : DecodeError()

    /**
     * An octet outside the characters the format permits, and not part of the trailing filler run
     * this library tolerates — so an embedded one, a leading one, or a trailing one that is
     * neither a space nor a null. See [TextPadding].
     *
     * The first such octet is reported and the rest of the value is not examined.
     *
     * @property octet the offending octet, unsigned, `0..255`.
     */
    public data class UnexpectedCharacter(
        override val tag: TlvTag,
        override val offset: Int,
        public val octet: Int,
    ) : DecodeError()

    /**
     * A value whose shape is fixed by what it holds is the wrong size for it: a `YYMMDD` date that
     * is not three octets, an `n 12` amount that is not six.
     *
     * This is not the dictionary's advisory `minLength`/`maxLength`, which never reject anything
     * and are not consulted here. It is the decoding rule itself: six digits do not fit in two
     * octets, so there is nothing to decode rather than something out of the ordinary.
     *
     * [offset] is the data object's own first identifier octet, not a value octet, because what is
     * wrong is the object and not one place in it.
     */
    public data class UnexpectedValueLength(
        override val tag: TlvTag,
        override val offset: Int,
        public val expectedOctets: Int,
        public val actualOctets: Int,
    ) : DecodeError()

    /** A date's month digits are not `01` to `12`. [offset] is the month octet. */
    public data class MonthOutOfRange(
        override val tag: TlvTag,
        override val offset: Int,
        public val month: Int,
    ) : DecodeError()

    /**
     * A date's day digits are not `01` to [maxDay], the length of the month it was written in.
     *
     * Checked against the month rather than a flat 31, because a 31st of June is impossible in
     * every century and a bound that let it through would be a laxer check than its own name
     * claims. February is checked against the year's two digits as well, which settle its length
     * for all but one of the hundred endings a card can write — see `ValueDecoder.longestMonth`.
     *
     * [maxDay] is therefore a fact about the month and year in hand rather than a fixed table
     * entry, and travels because an analyst reading "day 30, maximum 28" learns why.
     *
     * [offset] is the day octet. The month is not reported here: it was already found to be valid,
     * and [maxDay] is what the day was actually judged against.
     */
    public data class DayOutOfRange(
        override val tag: TlvTag,
        override val offset: Int,
        public val day: Int,
        public val maxDay: Int,
    ) : DecodeError()
}
