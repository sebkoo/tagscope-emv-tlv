package io.github.sebkoo.tagscope.tlv

/**
 * The class of a BER-TLV tag, encoded in bits 8-7 of its first identifier octet.
 *
 * EMV uses [APPLICATION] for the templates it defines itself, such as `6F` and `70`, and
 * [CONTEXT_SPECIFIC] for the data elements inside them, such as `82` and `9F26`.
 *
 * ISO/IEC 7816-4 §5.2.2.1.
 */
public enum class TagClass {
    /** Bits 8-7 = `00`. */
    UNIVERSAL,

    /** Bits 8-7 = `01`. */
    APPLICATION,

    /** Bits 8-7 = `10`. */
    CONTEXT_SPECIFIC,

    /** Bits 8-7 = `11`. */
    PRIVATE,
}
