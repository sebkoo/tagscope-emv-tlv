package io.github.sebkoo.tagscope.tlv

/**
 * The length field of a BER-TLV data object: how many value octets follow it.
 *
 * EMV Book 3, Annex B2; ISO/IEC 7816-4 §5.2.2.2.
 *
 * @property value the number of value octets the field declares.
 * @property octetLength how many octets the length field itself occupies: 1 in the short form,
 *   `1 + n` in the long form.
 */
public data class TlvLength(
    public val value: Int,
    public val octetLength: Int,
) {
    init {
        // Preconditions on the value object, not parse errors. TlvReader reports malformed
        // input by returning a TlvError instead of constructing a length.
        require(value >= 0) { "value must not be negative, was $value" }
        require(octetLength in 1..(1 + MAX_LONG_FORM_OCTETS)) {
            "octetLength must be 1..${1 + MAX_LONG_FORM_OCTETS}, was $octetLength"
        }
    }

    public companion object {
        /**
         * The most subsequent length octets this library reads. EMV never needs more than
         * three (Book 3, Annex B2), and four is already the widest field whose value could
         * still address a byte array on the JVM, so a wider one is rejected rather than
         * decoded. Four octets can nonetheless declare more than [Int.MAX_VALUE] octets, which
         * `TlvReader` rejects separately once the value is known.
         */
        public const val MAX_LONG_FORM_OCTETS: Int = 4
    }
}
