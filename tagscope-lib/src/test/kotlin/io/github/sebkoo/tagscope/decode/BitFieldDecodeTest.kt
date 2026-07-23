package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.BitField
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
 * The bit-field flag tags: the Application Interchange Profile (`82`), the Application Usage Control
 * (`9F07`), the Terminal Verification Results (`95`), and the three Issuer Action Codes
 * (`9F0D`/`9F0E`/`9F0F`) that share the TVR's layout.
 *
 * Every expected meaning is transcribed from EMV Book 3 v4.4 (October 2022), Annex C: Table 41
 * (AIP, C1), Table 42 (AUC, C2) and Table 46 (TVR, C5). The example bytes are hand-written to set
 * named bits; none of it is card data.
 */
class BitFieldDecodeTest {
    private fun flagsOf(text: String): List<SetFlag> = (decode(text).expectValue() as BitField).flags

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
    fun `a bit field is not sensitive and comes back bare`() {
        assertFalse(decode("82020000").expectValue() is Sensitive, "a bit field is not cardholder data")
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
        for (hex in listOf("82", "9F07", "95", "9F0D", "9F0E", "9F0F")) {
            val info = TagDictionary.entries.first { it.tag.hex == hex }
            assertFalse(info.isSensitive, "$hex must be non-sensitive")

            val spec = BitFieldTable.specFor(info.tag) ?: fail("$hex should have a bit-field spec")
            assertEquals(info.minLength, spec.octetLength, "$hex width disagrees with the dictionary minimum")
            assertEquals(info.maxLength, spec.octetLength, "$hex width disagrees with the dictionary maximum")
            assertTrue(info.minLength == info.maxLength, "$hex is not a fixed width in the dictionary")
        }
    }
}
