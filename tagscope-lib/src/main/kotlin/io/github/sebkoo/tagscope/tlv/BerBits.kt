package io.github.sebkoo.tagscope.tlv

/**
 * Bit masks and sentinel octets for the identifier and length fields of a BER-TLV data object.
 *
 * ISO/IEC 7816-4 §5.2.2; EMV Book 3, Annex B1.
 */
internal object BerBits {
    /** Identifier octet 1, bits 8-7: the tag class. */
    const val CLASS_MASK: Int = 0xC0

    /** How far to shift the masked class bits down to get their numeric value. */
    const val CLASS_SHIFT: Int = 6

    /** Identifier octet 1, bit 6: set when the value octets are themselves TLV objects. */
    const val CONSTRUCTED: Int = 0x20

    /**
     * Identifier octet 1, bits 5-1: the tag number. All five set is the escape that says the
     * number continues in subsequent identifier octets.
     */
    const val TAG_NUMBER_MASK: Int = 0x1F

    /** Subsequent identifier octet, bit 8: another identifier octet follows. */
    const val MORE_IDENTIFIER_OCTETS: Int = 0x80

    /** Subsequent identifier octet, bits 7-1: seven more bits of the tag number. */
    const val SUBSEQUENT_NUMBER_MASK: Int = 0x7F

    /** The largest first length octet that is itself the length, i.e. the short form. */
    const val SHORT_FORM_MAX: Int = 0x7F

    /** First length octet `0x80`: the indefinite form, which EMV forbids. */
    const val INDEFINITE_LENGTH_OCTET: Int = 0x80

    /** First length octet `0xFF`: reserved by ISO/IEC 7816-4, never a valid length. */
    const val RESERVED_LENGTH_OCTET: Int = 0xFF

    /** Long-form first length octet, bits 7-1: the count of subsequent length octets. */
    const val LONG_FORM_COUNT_MASK: Int = 0x7F

    /** Mask for one octet held in a wider integer. */
    const val OCTET_MASK: Int = 0xFF
}
