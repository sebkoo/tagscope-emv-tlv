package io.github.sebkoo.tagscope.tlv

/**
 * A structural failure found while reading BER-TLV data.
 *
 * Every variant carries [offset], the index of the octet at which the failure was detected.
 * For a truncation that is the index of the first octet that was needed but is not present,
 * which is one past the last readable index and so may equal the size of the buffer.
 */
public sealed class TlvError {
    /** Index of the octet at which the failure was detected. */
    public abstract val offset: Int

    /**
     * No octet is readable at [offset]: the buffer is empty or exhausted, or [offset] lies
     * outside it. [size] is the size of the buffer that was read.
     */
    public data class UnexpectedEndOfData(
        override val offset: Int,
        public val size: Int,
    ) : TlvError()

    /**
     * The tag promised another identifier octet and the buffer ended instead: either bits 5-1
     * of the first octet were all set, or a subsequent octet had bit 8 set, and no octet
     * follows.
     */
    public data class TruncatedTag(
        override val offset: Int,
    ) : TlvError()

    /**
     * The tag number continues past [maxOctets] identifier octets, which is wider than this
     * reader accepts. See [TlvTag.MAX_IDENTIFIER_OCTETS].
     */
    public data class TagTooLong(
        override val offset: Int,
        public val maxOctets: Int,
    ) : TlvError()

    /**
     * The first length octet is `0x80`, the indefinite form. EMV requires every data object to
     * carry a definite length, so this is never valid here.
     */
    public data class IndefiniteLength(
        override val offset: Int,
    ) : TlvError()

    /** The first length octet is `0xFF`, which ISO/IEC 7816-4 reserves. */
    public data class ReservedLengthOctet(
        override val offset: Int,
    ) : TlvError()

    /**
     * A long-form length field declares [declaredOctets] subsequent octets, but only
     * [availableOctets] remain in the buffer.
     */
    public data class TruncatedLength(
        override val offset: Int,
        public val declaredOctets: Int,
        public val availableOctets: Int,
    ) : TlvError()

    /**
     * A long-form length that cannot be represented: it declares more subsequent octets than
     * this reader accepts (see [TlvLength.MAX_LONG_FORM_OCTETS]), or the value they encode
     * exceeds [Int.MAX_VALUE] and so could never address a JVM byte array.
     */
    public data class LengthOutOfRange(
        override val offset: Int,
        public val declaredOctets: Int,
    ) : TlvError()

    /**
     * A data object declares [declaredLength] value octets but only [availableOctets] remain in
     * the buffer.
     */
    public data class TruncatedValue(
        override val offset: Int,
        public val declaredLength: Int,
        public val availableOctets: Int,
    ) : TlvError()

    /**
     * The data object beginning at [offset] is not contained in the constructed value holding
     * it, whose own value ends at [parentEnd]. Here [offset] is the first identifier octet of the
     * offending object rather than a missing octet, because the object, not the buffer, is what
     * is wrong.
     *
     * Distinct from [TruncatedValue]: the octets are present in the buffer, they just belong to
     * something else. This covers an object whose value runs past its parent, and equally one
     * whose identifier or length field does, in which case the octets that would have said how
     * long the object is lie outside the parent and are not reported: a length lifted from a
     * neighbouring object is not this object's length.
     */
    public data class ChildOverrunsParent(
        override val offset: Int,
        public val parentEnd: Int,
    ) : TlvError()

    /**
     * Data objects are nested more than [maxDepth] levels deep, which is more than this parser
     * follows. See [TlvParser.MAX_DEPTH]. Here [offset] is where the too-deep sequence begins.
     */
    public data class NestingTooDeep(
        override val offset: Int,
        public val maxDepth: Int,
    ) : TlvError()

    /**
     * An `FF` octet appears at [offset], where a data object was expected.
     *
     * ISO/IEC 7816-4 §5.2.2.1 permits both `00` and `FF` octets without meaning before, between
     * and after data objects; EMV Book 3, Annex B1 permits only `00`. This library parses EMV
     * data, so it skips `00` and reports `FF`. There is no octet field because the octet is
     * always `FF`: a `00` in the same position is filler and is skipped.
     *
     * An `FF` in the length field is a different failure, [ReservedLengthOctet].
     */
    public data class UnexpectedFillerOctet(
        override val offset: Int,
    ) : TlvError()
}
