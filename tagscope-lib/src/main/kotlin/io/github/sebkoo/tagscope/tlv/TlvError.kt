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
}
