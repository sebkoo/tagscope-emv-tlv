package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.BitField
import io.github.sebkoo.tagscope.decode.DecodedValue.BitField.CryptogramType
import io.github.sebkoo.tagscope.decode.DecodedValue.BitField.EnumSelection
import io.github.sebkoo.tagscope.decode.DecodedValue.BitField.SetFlag
import io.github.sebkoo.tagscope.decode.DecodedValue.Sensitive
import io.github.sebkoo.tagscope.tags.TagDictionary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

/**
 * The bit-field tags: the flag tags — the Application Interchange Profile (`82`), the Application
 * Usage Control (`9F07`), the Terminal Verification Results (`95`), and the three Issuer Action
 * Codes (`9F0D`/`9F0E`/`9F0F`) that share the TVR's layout — and the enum-bearing tags, the
 * Cryptogram Information Data (`9F27`) and the CVM Results (`9F34`).
 *
 * Every expected meaning is transcribed from EMV Book 3 v4.4 (October 2022): Annex C Table 41 (AIP),
 * Table 42 (AUC), Table 46 (TVR), Table 43 (CVM codes), Table 44 (CVM conditions), and Table 15
 * (CID); the CVM Results byte-3 result codes are from EMV Book 4 v4.4, Annex A4. The example bytes
 * are hand-written to set named bits; none of it is card data.
 */
class BitFieldDecodeTest {
    private fun flagsOf(text: String): List<SetFlag> = (decode(text).expectValue() as BitField).flags

    private fun selectionsOf(text: String): List<EnumSelection> = (decode(text).expectValue() as BitField).selections

    @Test
    fun `AIP names the byte-1 capability bits that are set`() {
        // 0x78 = b7 b6 b5 b4 of byte 1.
        assertEquals(
            listOf(
                SetFlag(0, 7, "SDA supported"),
                SetFlag(0, 6, "DDA supported"),
                SetFlag(0, 5, "Cardholder verification is supported"),
                SetFlag(0, 4, "Terminal risk management is to be performed"),
            ),
            flagsOf("82027800"),
        )
    }

    @Test
    fun `AIP reads XDA and CDA, the outermost bits of byte 1`() {
        // 0x81 = b8 (XDA) and b1 (CDA), the pair that brackets byte 1.
        assertEquals(
            listOf(SetFlag(0, 8, "XDA supported"), SetFlag(0, 1, "CDA supported")),
            flagsOf("82028100"),
        )
    }

    @Test
    fun `AUC spans both octets, capabilities in byte 1 and cashback in byte 2`() {
        assertEquals(
            listOf(
                SetFlag(0, 8, "Valid for domestic cash transactions"),
                SetFlag(1, 8, "Domestic cashback allowed"),
            ),
            flagsOf("9F07028080"),
        )
    }

    @Test
    fun `TVR names bits across the five bytes, most significant first`() {
        // Byte 1 0x42 = b7 (SDA failed) and b2 (SDA selected); byte 5 0x40 = b7 (Issuer auth failed).
        assertEquals(
            listOf(
                SetFlag(0, 7, "SDA failed"),
                SetFlag(0, 2, "SDA selected"),
                SetFlag(4, 7, "Issuer authentication failed"),
            ),
            flagsOf("95054200000040"),
        )
    }

    @Test
    fun `a bit field with no bits set names nothing`() {
        assertEquals(emptyList<SetFlag>(), flagsOf("82020000"))
        assertEquals(emptyList<SetFlag>(), flagsOf("95050000000000"))
    }

    @Test
    fun `the Issuer Action Codes reuse the TVR table, bit for bit`() {
        // The same five value octets through the TVR and each IAC give the identical flags: an IAC
        // is a TVR-shaped mask, so they share one table and cannot drift apart.
        val value = "4200000040"
        val tvr = flagsOf("9505$value")
        assertEquals(tvr, flagsOf("9F0D05$value"), "IAC - Default must match the TVR")
        assertEquals(tvr, flagsOf("9F0E05$value"), "IAC - Denial must match the TVR")
        assertEquals(tvr, flagsOf("9F0F05$value"), "IAC - Online must match the TVR")
    }

    @Test
    fun `a set plain-RFU bit is surfaced as RFU rather than dropped`() {
        // AUC byte 2 b1 is plain RFU; TVR byte 2 b3 is plain RFU. A set reserved bit is exactly the
        // anomaly the tool exists to show, so it is reported, not silently lost.
        assertEquals(listOf(SetFlag(1, 1, "RFU")), flagsOf("9F07020001"))
        assertEquals(listOf(SetFlag(1, 3, "RFU")), flagsOf("95050004000000"))
    }

    @Test
    fun `a bit reserved for contactless keeps that exact wording, not a plain RFU`() {
        // AIP byte 1 b2 is "Reserved for use by the EMV Contactless Specifications", which Book 3
        // states apart from a plain RFU; the decoder keeps the distinction.
        assertEquals(
            listOf(SetFlag(0, 2, "Reserved for use by the EMV Contactless Specifications")),
            flagsOf("82020200"),
        )
    }

    @Test
    fun `named bits and a synthesised RFU sort together into wire order`() {
        // TVR Byte 2 (index 1) 0x24 = b6 (Application not yet effective, named) and b3 (plain RFU);
        // Byte 3 (index 2) 0x40 = b7 (Unrecognised CVM). All come out ordered by octet, then the
        // most significant bit first, so a named bit and a synthesised RFU interleave by position.
        assertEquals(
            listOf(
                SetFlag(1, 6, "Application not yet effective"),
                SetFlag(1, 3, "RFU"),
                SetFlag(2, 7, "Unrecognised CVM"),
            ),
            flagsOf("95050024400000"),
        )
    }

    @Test
    fun `AIP names every bit of both octets`() {
        // All sixteen bits set: byte 1 is fully named, byte 2 is three contactless-reserved bits,
        // four plain RFU, and one more contactless-reserved bit. Pins every AIP position at once.
        assertEquals(
            listOf(
                SetFlag(0, 8, "XDA supported"),
                SetFlag(0, 7, "SDA supported"),
                SetFlag(0, 6, "DDA supported"),
                SetFlag(0, 5, "Cardholder verification is supported"),
                SetFlag(0, 4, "Terminal risk management is to be performed"),
                SetFlag(0, 3, "Issuer authentication is supported"),
                SetFlag(0, 2, "Reserved for use by the EMV Contactless Specifications"),
                SetFlag(0, 1, "CDA supported"),
                SetFlag(1, 8, "Reserved for use by the EMV Contactless Specifications"),
                SetFlag(1, 7, "Reserved for use by the EMV Contactless Specifications"),
                SetFlag(1, 6, "Reserved for use by the EMV Contactless Specifications"),
                SetFlag(1, 5, "RFU"),
                SetFlag(1, 4, "RFU"),
                SetFlag(1, 3, "RFU"),
                SetFlag(1, 2, "RFU"),
                SetFlag(1, 1, "Reserved for use by the EMV Contactless Specifications"),
            ),
            flagsOf("8202FFFF"),
        )
    }

    @Test
    fun `AUC names every bit of both octets`() {
        assertEquals(
            listOf(
                SetFlag(0, 8, "Valid for domestic cash transactions"),
                SetFlag(0, 7, "Valid for international cash transactions"),
                SetFlag(0, 6, "Valid for domestic goods"),
                SetFlag(0, 5, "Valid for international goods"),
                SetFlag(0, 4, "Valid for domestic services"),
                SetFlag(0, 3, "Valid for international services"),
                SetFlag(0, 2, "Valid at ATMs"),
                SetFlag(0, 1, "Valid at terminals other than ATMs"),
                SetFlag(1, 8, "Domestic cashback allowed"),
                SetFlag(1, 7, "International cashback allowed"),
                SetFlag(1, 6, "RFU"),
                SetFlag(1, 5, "RFU"),
                SetFlag(1, 4, "RFU"),
                SetFlag(1, 3, "RFU"),
                SetFlag(1, 2, "RFU"),
                SetFlag(1, 1, "RFU"),
            ),
            flagsOf("9F0702FFFF"),
        )
    }

    @Test
    fun `TVR names every bit of all five octets`() {
        // All forty bits set: pins every TVR position and meaning, including byte 2 b3's plain RFU
        // and byte 5's two contactless-reserved bits. The IACs share this table, so pinning it here
        // pins them too.
        assertEquals(
            listOf(
                SetFlag(0, 8, "Offline data authentication was not performed"),
                SetFlag(0, 7, "SDA failed"),
                SetFlag(0, 6, "ICC data missing"),
                SetFlag(0, 5, "Card appears on terminal exception file"),
                SetFlag(0, 4, "DDA failed"),
                SetFlag(0, 3, "CDA failed"),
                SetFlag(0, 2, "SDA selected"),
                SetFlag(0, 1, "XDA selected"),
                SetFlag(1, 8, "ICC and terminal have different application versions"),
                SetFlag(1, 7, "Expired application"),
                SetFlag(1, 6, "Application not yet effective"),
                SetFlag(1, 5, "Requested service not allowed for card product"),
                SetFlag(1, 4, "New card"),
                SetFlag(1, 3, "RFU"),
                SetFlag(1, 2, "Biometric performed and successful"),
                SetFlag(1, 1, "Biometric template format not supported"),
                SetFlag(2, 8, "Cardholder verification was not successful"),
                SetFlag(2, 7, "Unrecognised CVM"),
                SetFlag(2, 6, "PIN Try Limit exceeded"),
                SetFlag(2, 5, "PIN entry required and PIN pad not present or not working"),
                SetFlag(2, 4, "PIN entry required, PIN pad present, but PIN was not entered"),
                SetFlag(2, 3, "Online CVM captured"),
                SetFlag(2, 2, "Biometric required but Biometric capture device not working"),
                SetFlag(
                    2,
                    1,
                    "Biometric required, Biometric capture device present, but Biometric Subtype entry was bypassed",
                ),
                SetFlag(3, 8, "Transaction exceeds floor limit"),
                SetFlag(3, 7, "Lower consecutive offline limit exceeded"),
                SetFlag(3, 6, "Upper consecutive offline limit exceeded"),
                SetFlag(3, 5, "Transaction selected randomly for online processing"),
                SetFlag(3, 4, "Merchant forced transaction online"),
                SetFlag(3, 3, "Biometric Try Limit exceeded"),
                SetFlag(3, 2, "A selected Biometric Type not supported"),
                SetFlag(3, 1, "XDA signature verification failed"),
                SetFlag(4, 8, "Default TDOL used"),
                SetFlag(4, 7, "Issuer authentication failed"),
                SetFlag(4, 6, "Script processing failed before final GENERATE AC"),
                SetFlag(4, 5, "Script processing failed after final GENERATE AC"),
                SetFlag(4, 4, "Reserved for use by the EMV Contactless Specifications"),
                SetFlag(4, 3, "CA ECC key missing"),
                SetFlag(4, 2, "ECC key recovery failed"),
                SetFlag(4, 1, "Reserved for use by the EMV Contactless Specifications"),
            ),
            flagsOf("9505FFFFFFFFFF"),
        )
    }

    @Test
    fun `a bit field is not sensitive and comes back bare`() {
        assertFalse(decode("82020000").expectValue() is Sensitive, "a bit field is not cardholder data")
    }

    private fun cryptogramTypeOf(text: String): EnumSelection =
        selectionsOf(text).first { it.label == BitField.CRYPTOGRAM_TYPE_LABEL }

    @Test
    fun `CID reads the cryptogram type from the top two bits`() {
        assertEquals(EnumSelection(0, "Cryptogram type", 0, "AAC"), cryptogramTypeOf("9F270100"))
        assertEquals(EnumSelection(0, "Cryptogram type", 1, "TC"), cryptogramTypeOf("9F270140"))
        assertEquals(EnumSelection(0, "Cryptogram type", 2, "ARQC"), cryptogramTypeOf("9F270180"))
        // b8 b7 = 11 is RFU in EMV 4.4, not AAR.
        assertEquals(EnumSelection(0, "Cryptogram type", 3, "RFU"), cryptogramTypeOf("9F2701C0"))
    }

    @Test
    fun `CID reports the advice flag, cryptogram type and reason code together`() {
        // 0x8B = ARQC (b8 b7 = 10), advice required (b4), reason 011 = issuer authentication failed.
        assertEquals(listOf(SetFlag(0, 4, "Advice required")), flagsOf("9F27018B"))
        assertEquals(
            listOf(
                EnumSelection(0, "Cryptogram type", 2, "ARQC"),
                EnumSelection(0, "Reason/advice code", 3, "Issuer authentication failed"),
            ),
            selectionsOf("9F27018B"),
        )
    }

    @Test
    fun `CID names the payment-system-specific bits`() {
        // 0x30 = b6 b5 set; the cryptogram type and reason code both read zero. Book 3 Table 15
        // words this row "Payment System-specific cryptogram" verbatim.
        assertEquals(
            listOf(
                SetFlag(0, 6, "Payment System-specific cryptogram"),
                SetFlag(0, 5, "Payment System-specific cryptogram"),
            ),
            flagsOf("9F270130"),
        )
    }

    @Test
    fun `the CID cryptogram type maps onto the CryptogramType enum`() {
        val selection = cryptogramTypeOf("9F270180")
        assertEquals(CryptogramType.ARQC.name, selection.meaning)
        assertEquals(CryptogramType.ARQC, CryptogramType.entries[selection.value])
    }

    private fun reasonOf(text: String): EnumSelection = selectionsOf(text).first { it.label == "Reason/advice code" }

    @Test
    fun `CID names every defined reason advice code, and RFU beyond them`() {
        assertEquals(EnumSelection(0, "Reason/advice code", 0, "No information given"), reasonOf("9F270100"))
        assertEquals(EnumSelection(0, "Reason/advice code", 1, "Service not allowed"), reasonOf("9F270101"))
        assertEquals(EnumSelection(0, "Reason/advice code", 2, "PIN Try Limit exceeded"), reasonOf("9F270102"))
        assertEquals(EnumSelection(0, "Reason/advice code", 3, "Issuer authentication failed"), reasonOf("9F270103"))
        // b3 b2 b1 = 111; only 000..011 are defined, so the rest are RFU.
        assertEquals(EnumSelection(0, "Reason/advice code", 7, "RFU"), reasonOf("9F270107"))
    }

    @Test
    fun `CVM Results decodes the method, condition and result lanes`() {
        // 1F 00 02: No CVM required, condition Always, result Successful.
        assertEquals(
            listOf(
                EnumSelection(0, "CVM performed", 0x1F, "No CVM required"),
                EnumSelection(1, "CVM condition", 0x00, "Always"),
                EnumSelection(2, "CVM result", 0x02, "Successful"),
            ),
            selectionsOf("9F34031F0002"),
        )
        assertEquals(emptyList<SetFlag>(), flagsOf("9F34031F0002"))
    }

    @Test
    fun `CVM Results reports byte 1 bit 7 as the apply-succeeding-rule flag`() {
        // 44 03 01: b7 set (apply succeeding rule), method 04 (enciphered PIN by ICC), condition 03,
        // result Failed.
        assertEquals(
            listOf(SetFlag(0, 7, "Apply succeeding CV Rule if this CVM is unsuccessful")),
            flagsOf("9F3403440301"),
        )
        assertEquals(
            listOf(
                EnumSelection(0, "CVM performed", 0x04, "Enciphered PIN verification performed by ICC"),
                EnumSelection(1, "CVM condition", 0x03, "If terminal supports the CVM"),
                EnumSelection(2, "CVM result", 0x01, "Failed"),
            ),
            selectionsOf("9F3403440301"),
        )
    }

    @Test
    fun `CVM Results byte 1 of 3F is Book 4's No CVM performed`() {
        // 3F 00 00: byte 1 is Book 4's "No CVM performed"; byte 2 reads Always; byte 3 Unknown.
        assertEquals(
            listOf(
                EnumSelection(0, "CVM performed", 0x3F, "No CVM performed"),
                EnumSelection(1, "CVM condition", 0x00, "Always"),
                EnumSelection(2, "CVM result", 0x00, "Unknown"),
            ),
            selectionsOf("9F34033F0000"),
        )
    }

    @Test
    fun `a CVM result the spec does not define is RFU`() {
        assertEquals(
            EnumSelection(2, "CVM result", 0x03, "RFU"),
            selectionsOf("9F3403000003").first { it.label == "CVM result" },
        )
    }

    @Test
    fun `a CVM byte 1 reserved bit 8 is surfaced as RFU`() {
        // 80 00 00: b8 of byte 1 is RFU; the method reads 0 (Fail CVM processing), result Unknown.
        assertEquals(listOf(SetFlag(0, 8, "RFU")), flagsOf("9F3403800000"))
        assertEquals(
            listOf(
                EnumSelection(0, "CVM performed", 0x00, "Fail CVM processing"),
                EnumSelection(1, "CVM condition", 0x00, "Always"),
                EnumSelection(2, "CVM result", 0x00, "Unknown"),
            ),
            selectionsOf("9F3403800000"),
        )
    }

    private fun methodOf(text: String): String = selectionsOf(text).first { it.label == "CVM performed" }.meaning

    @Test
    fun `CVM method reserved ranges keep their distinct labels`() {
        // One representative from each reserved range of Table 43 (byte 1, mask 0x3F), so a swap
        // between the three distinct "reserved" wordings cannot pass unseen.
        assertEquals("RFU (reserved for future use by this specification)", methodOf("9F3403150000"))
        assertEquals("Reserved for use by the individual payment systems", methodOf("9F3403250000"))
        assertEquals("Reserved for use by the issuer", methodOf("9F3403350000"))
    }

    @Test
    fun `a bit field of the wrong length has nothing to read against the table`() {
        assertEquals(
            DecodeError.UnexpectedValueLength(node("820100").tag, offset = 0, expectedOctets = 2, actualOctets = 1),
            decode("820100").expectError(),
        )
        assertEquals(
            DecodeError.UnexpectedValueLength(node("9506000000000000").tag, 0, 5, 6),
            decode("9506000000000000").expectError(),
        )
        assertEquals(
            DecodeError.UnexpectedValueLength(node("9F0703000000").tag, 0, 2, 3),
            decode("9F0703000000").expectError(),
        )
        assertEquals(
            DecodeError.UnexpectedValueLength(node("9F27020000").tag, 0, 1, 2),
            decode("9F27020000").expectError(),
        )
        assertEquals(
            DecodeError.UnexpectedValueLength(node("9F34020000").tag, 0, 3, 2),
            decode("9F34020000").expectError(),
        )
    }

    @Test
    fun `a wrong-length IAC fails the same way as the TVR it is shaped like`() {
        assertEquals(
            DecodeError.UnexpectedValueLength(node("9F0D0400000000").tag, 0, 5, 4),
            decode("9F0D0400000000").expectError(),
        )
    }

    @Test
    fun `bit fields compare by their octets and their meaning together`() {
        // Two decodes of one input are equal; different octets are not. The load-bearing case is
        // the last: 82 and 9F07 both take 0x40 0x00, but decode to different flags, so comparing the
        // octets alone would wrongly call them equal.
        assertEquals(decode("95054000000000").expectValue(), decode("95054000000000").expectValue())
        assertEquals(
            decode("95054000000000").expectValue().hashCode(),
            decode("95054000000000").expectValue().hashCode(),
        )
        assertNotEquals(decode("95054000000000").expectValue(), decode("95058000000000").expectValue())
        assertNotEquals(decode("82024000").expectValue(), decode("9F07024000").expectValue())
    }

    @Test
    fun `a bit field prints its meanings, since it carries no card data`() {
        assertEquals(
            "BitField(Offline data authentication was not performed)",
            decode("95058000000000").expectValue().toString(),
        )
    }

    @Test
    fun `the octets are copied out, so a caller cannot reach into a decoded bit field`() {
        val field = decode("95054000000000").expectValue() as BitField
        val first = field.bytes()

        first[0] = 0x7F

        assertEquals(0x40, field.bytes()[0].toInt())
    }

    @Test
    fun `every bit-field tag is non-sensitive and agrees with the dictionary on width`() {
        // Two invariants the error design leans on. First: none of these tags is cardholder data,
        // so UnexpectedValueLength may carry the lengths. Second: the spec's width is the fixed
        // width the dictionary states, so the length check and the dictionary cannot disagree.
        for (hex in listOf("82", "9F07", "95", "9F0D", "9F0E", "9F0F", "9F27", "9F34")) {
            val info = TagDictionary.entries.first { it.tag.hex == hex }
            assertFalse(info.isSensitive, "$hex must be non-sensitive")

            val spec = BitFieldTable.specFor(info.tag) ?: fail("$hex should have a bit-field spec")
            assertEquals(info.minLength, spec.octetLength, "$hex width disagrees with the dictionary minimum")
            assertEquals(info.maxLength, spec.octetLength, "$hex width disagrees with the dictionary maximum")
            assertTrue(info.minLength == info.maxLength, "$hex is not a fixed width in the dictionary")
        }
    }
}
