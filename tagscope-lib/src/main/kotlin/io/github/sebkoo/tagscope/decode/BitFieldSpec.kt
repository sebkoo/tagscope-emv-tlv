package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * The bit meanings EMV states for each fixed-length bit-field tag, as compile-time data.
 *
 * The bit-field analogue of `TagDictionary`: an authoritative EMV table, encoded as Kotlin rather
 * than a resource so the compiler checks it and the library keeps its zero runtime dependencies.
 * Internal, because a caller receives the decoded `DecodedValue.BitField` — the set flags already
 * resolved to their meanings — and never this layout, the same way `BerBits` stays internal to the
 * parser.
 *
 * A spec lists only the bit positions that carry a meaning worth naming. A plain "RFU" position is
 * left out and synthesised by `ValueDecoder` when it is actually set, so nothing set is dropped and
 * the tables stay to the meanings EMV actually spells. A position Book 3 reserves with wording more
 * specific than "RFU" — "Reserved for use by the EMV Contactless Specifications" — is listed, so a
 * set reserved bit reports which kind of reserved it is.
 *
 * Every meaning is transcribed from EMV Book 3 v4.4 (October 2022), Annex C, at the table cited
 * beside each spec in [BitFieldTable].
 *
 * @property octetLength how many octets the field is; a value of any other length has nothing to
 *   read against this table and fails with `DecodeError.UnexpectedValueLength`.
 * @property bits the named bit positions, in any order.
 */
internal class BitFieldSpec(
    val octetLength: Int,
    val bits: List<BitRule>,
) {
    init {
        // Static-data preconditions, the discipline TagInfo and TagDictionary keep in their own
        // init blocks: a mistake in the table is caught when the object loads, long before a value
        // is decoded against it.
        val seen = HashSet<Int>(bits.size)
        for (rule in bits) {
            require(rule.byteIndex in 0 until octetLength) {
                "a bit rule names byte ${rule.byteIndex}, outside 0 until $octetLength"
            }
            require(rule.bit in 1..BITS_PER_OCTET) {
                "a bit rule names bit ${rule.bit}, outside 1..$BITS_PER_OCTET"
            }
            require(seen.add(rule.byteIndex * BITS_PER_OCTET + rule.bit)) {
                "bit ${rule.bit} of byte ${rule.byteIndex} is named twice"
            }
        }
    }

    private companion object {
        private const val BITS_PER_OCTET: Int = 8
    }
}

/**
 * One bit position and what a set bit there means.
 *
 * @property byteIndex which octet of the value, from zero — Book 3's "Byte 1" is index 0.
 * @property bit EMV's `b1`..`b8`, where `b8` is the most significant, `0x80`.
 * @property meaning the Book 3 wording for this position, verbatim.
 */
internal data class BitRule(
    val byteIndex: Int,
    val bit: Int,
    val meaning: String,
)

/**
 * Which fixed-length bit-field tags this library decodes, and the bit table for each.
 *
 * The Issuer Action Codes `9F0D`/`9F0E`/`9F0F` share the one [TVR] spec instance: Book 3 §10.7
 * states an IAC's "size and format ... identical to the TVR", so an IAC is a TVR-shaped mask.
 * Duplicating the forty bit meanings three times over would only invite the copies to drift.
 */
internal object BitFieldTable {
    /** The spec for [tag], or null when the tag is not a bit field this library decodes. */
    fun specFor(tag: TlvTag): BitFieldSpec? = SPECS[tag]

    /** Application Interchange Profile (`82`). Book 3 v4.4, Annex C1, Table 41. */
    private val AIP: BitFieldSpec =
        BitFieldSpec(
            octetLength = 2,
            bits =
                listOf(
                    // Byte 1 (leftmost)
                    BitRule(0, 8, "XDA supported"),
                    BitRule(0, 7, "SDA supported"),
                    BitRule(0, 6, "DDA supported"),
                    BitRule(0, 5, "Cardholder verification is supported"),
                    BitRule(0, 4, "Terminal risk management is to be performed"),
                    BitRule(0, 3, "Issuer authentication is supported"),
                    BitRule(0, 2, "Reserved for use by the EMV Contactless Specifications"),
                    BitRule(0, 1, "CDA supported"),
                    // Byte 2 (rightmost); b5..b2 are plain RFU and are synthesised when set.
                    BitRule(1, 8, "Reserved for use by the EMV Contactless Specifications"),
                    BitRule(1, 7, "Reserved for use by the EMV Contactless Specifications"),
                    BitRule(1, 6, "Reserved for use by the EMV Contactless Specifications"),
                    BitRule(1, 1, "Reserved for use by the EMV Contactless Specifications"),
                ),
        )

    /** Application Usage Control (`9F07`). Book 3 v4.4, Annex C2, Table 42. */
    private val AUC: BitFieldSpec =
        BitFieldSpec(
            octetLength = 2,
            bits =
                listOf(
                    // Byte 1 (leftmost)
                    BitRule(0, 8, "Valid for domestic cash transactions"),
                    BitRule(0, 7, "Valid for international cash transactions"),
                    BitRule(0, 6, "Valid for domestic goods"),
                    BitRule(0, 5, "Valid for international goods"),
                    BitRule(0, 4, "Valid for domestic services"),
                    BitRule(0, 3, "Valid for international services"),
                    BitRule(0, 2, "Valid at ATMs"),
                    BitRule(0, 1, "Valid at terminals other than ATMs"),
                    // Byte 2 (rightmost); b6..b1 are plain RFU and are synthesised when set.
                    BitRule(1, 8, "Domestic cashback allowed"),
                    BitRule(1, 7, "International cashback allowed"),
                ),
        )

    /**
     * Terminal Verification Results (`95`), and the Issuer Action Codes that share its layout.
     * Book 3 v4.4, Annex C5, Table 46.
     */
    private val TVR: BitFieldSpec =
        BitFieldSpec(
            octetLength = 5,
            bits =
                listOf(
                    // Byte 1
                    BitRule(0, 8, "Offline data authentication was not performed"),
                    BitRule(0, 7, "SDA failed"),
                    BitRule(0, 6, "ICC data missing"),
                    BitRule(0, 5, "Card appears on terminal exception file"),
                    BitRule(0, 4, "DDA failed"),
                    BitRule(0, 3, "CDA failed"),
                    BitRule(0, 2, "SDA selected"),
                    BitRule(0, 1, "XDA selected"),
                    // Byte 2; b3 is plain RFU and is synthesised when set.
                    BitRule(1, 8, "ICC and terminal have different application versions"),
                    BitRule(1, 7, "Expired application"),
                    BitRule(1, 6, "Application not yet effective"),
                    BitRule(1, 5, "Requested service not allowed for card product"),
                    BitRule(1, 4, "New card"),
                    BitRule(1, 2, "Biometric performed and successful"),
                    BitRule(1, 1, "Biometric template format not supported"),
                    // Byte 3
                    BitRule(2, 8, "Cardholder verification was not successful"),
                    BitRule(2, 7, "Unrecognised CVM"),
                    BitRule(2, 6, "PIN Try Limit exceeded"),
                    BitRule(2, 5, "PIN entry required and PIN pad not present or not working"),
                    BitRule(2, 4, "PIN entry required, PIN pad present, but PIN was not entered"),
                    BitRule(2, 3, "Online CVM captured"),
                    BitRule(2, 2, "Biometric required but Biometric capture device not working"),
                    BitRule(
                        2,
                        1,
                        "Biometric required, Biometric capture device present, but Biometric " +
                            "Subtype entry was bypassed",
                    ),
                    // Byte 4
                    BitRule(3, 8, "Transaction exceeds floor limit"),
                    BitRule(3, 7, "Lower consecutive offline limit exceeded"),
                    BitRule(3, 6, "Upper consecutive offline limit exceeded"),
                    BitRule(3, 5, "Transaction selected randomly for online processing"),
                    BitRule(3, 4, "Merchant forced transaction online"),
                    BitRule(3, 3, "Biometric Try Limit exceeded"),
                    BitRule(3, 2, "A selected Biometric Type not supported"),
                    BitRule(3, 1, "XDA signature verification failed"),
                    // Byte 5 (rightmost); b4 and b1 are reserved for contactless, not plain RFU.
                    BitRule(4, 8, "Default TDOL used"),
                    BitRule(4, 7, "Issuer authentication failed"),
                    BitRule(4, 6, "Script processing failed before final GENERATE AC"),
                    BitRule(4, 5, "Script processing failed after final GENERATE AC"),
                    BitRule(4, 4, "Reserved for use by the EMV Contactless Specifications"),
                    BitRule(4, 3, "CA ECC key missing"),
                    BitRule(4, 2, "ECC key recovery failed"),
                    BitRule(4, 1, "Reserved for use by the EMV Contactless Specifications"),
                ),
        )

    private val SPECS: Map<TlvTag, BitFieldSpec> =
        mapOf(
            TlvTag(value = 0x82, octetLength = 1) to AIP,
            TlvTag(value = 0x9F07, octetLength = 2) to AUC,
            TlvTag(value = 0x95, octetLength = 1) to TVR,
            TlvTag(value = 0x9F0D, octetLength = 2) to TVR,
            TlvTag(value = 0x9F0E, octetLength = 2) to TVR,
            TlvTag(value = 0x9F0F, octetLength = 2) to TVR,
        )
}
