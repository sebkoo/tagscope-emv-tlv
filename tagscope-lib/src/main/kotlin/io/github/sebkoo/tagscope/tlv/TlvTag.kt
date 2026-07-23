package io.github.sebkoo.tagscope.tlv

/**
 * The identifier field of a BER-TLV data object: its tag.
 *
 * The identifier octets are held packed big-endian in [value], so tag `9F26` is `0x9F26` and
 * tag `5A` is `0x5A`. [octetLength] records how many octets were read rather than inferring it
 * from [value], because a leading `0x00` octet would otherwise be lost, and [toBytes]
 * reproduces the octets exactly as they appeared.
 *
 * EMV Book 3, Annex B1; ISO/IEC 7816-4 §5.2.2.1.
 *
 * @property value the identifier octets packed big-endian, most significant octet first.
 * @property octetLength how many identifier octets the tag occupies, `1..`[MAX_IDENTIFIER_OCTETS].
 */
public data class TlvTag(
    public val value: Long,
    public val octetLength: Int,
) {
    init {
        // Preconditions on the value object, not parse errors. Malformed input never reaches
        // here: TlvReader reports it by returning a TlvError instead of constructing a tag.
        require(octetLength in 1..MAX_IDENTIFIER_OCTETS) {
            "octetLength must be 1..$MAX_IDENTIFIER_OCTETS, was $octetLength"
        }
        // Without this, equality would compare octets that hex, toBytes and number cannot see.
        require(value >= 0 && value < (1L shl (octetLength * Byte.SIZE_BITS))) {
            "value does not fit in $octetLength octets: $value"
        }
    }

    /** The tag class, from bits 8-7 of the first identifier octet. */
    public val tagClass: TagClass
        get() =
            when ((firstOctet and BerBits.CLASS_MASK) ushr BerBits.CLASS_SHIFT) {
                0b00 -> TagClass.UNIVERSAL
                0b01 -> TagClass.APPLICATION
                0b10 -> TagClass.CONTEXT_SPECIFIC
                else -> TagClass.PRIVATE
            }

    /**
     * True when the value octets are themselves a sequence of TLV objects, from bit 6 of the
     * first identifier octet. Note that `80` is primitive despite carrying structured data.
     */
    public val isConstructed: Boolean
        get() = (firstOctet and BerBits.CONSTRUCTED) != 0

    /**
     * The tag number. For a one-octet tag that is bits 5-1 of the identifier octet; for a
     * longer tag it is bits 7-1 of each subsequent octet, concatenated most significant first.
     */
    public val number: Long
        get() {
            if (octetLength == 1) {
                return (firstOctet and BerBits.TAG_NUMBER_MASK).toLong()
            }
            var result = 0L
            for (index in 1 until octetLength) {
                result = (result shl SUBSEQUENT_NUMBER_BITS) or
                    (octetAt(index) and BerBits.SUBSEQUENT_NUMBER_MASK).toLong()
            }
            return result
        }

    /** The identifier octets as uppercase hex, for example `9F26`. */
    public val hex: String
        get() =
            buildString(octetLength * 2) {
                for (index in 0 until octetLength) {
                    val octet = octetAt(index)
                    append(HEX_DIGITS[octet ushr 4])
                    append(HEX_DIGITS[octet and 0x0F])
                }
            }

    /** The identifier octets, in the order they appear on the wire. */
    public fun toBytes(): ByteArray = ByteArray(octetLength) { index -> octetAt(index).toByte() }

    override fun toString(): String = "TlvTag($hex)"

    /** The first identifier octet, carrying the class, the constructed bit and the number. */
    private val firstOctet: Int
        get() = octetAt(0)

    private fun octetAt(index: Int): Int =
        ((value ushr ((octetLength - 1 - index) * Byte.SIZE_BITS)) and BerBits.OCTET_MASK.toLong()).toInt()

    public companion object {
        /**
         * The widest tag this library reads. EMV never uses more than two identifier octets
         * (Book 3, Annex B1); four is a deliberately generous ceiling that keeps the packed
         * [value] inside a positive Long and stops a run of continuation octets from building
         * an unbounded tag.
         */
        public const val MAX_IDENTIFIER_OCTETS: Int = 4

        /** Each subsequent identifier octet contributes seven bits to the tag number. */
        private const val SUBSEQUENT_NUMBER_BITS: Int = 7

        private const val HEX_DIGITS: String = "0123456789ABCDEF"
    }
}
