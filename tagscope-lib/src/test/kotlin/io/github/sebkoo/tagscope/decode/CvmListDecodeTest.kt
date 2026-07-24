package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.CvmList
import io.github.sebkoo.tagscope.decode.DecodedValue.CvmList.CvmRule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Cardholder Verification Method List decoder: `8E` read into its two four-octet binary amounts
 * (X, Y) and its run of two-octet CV Rules. Each rule is a CVM Code octet and a Condition octet; the
 * code's low six bits are the method, its `b7` the apply-next flag, its `b8` RFU.
 *
 * Inputs are whole `8E` data objects parsed by `TlvParser`, so the offsets a failure reports are
 * indices into a buffer the parser accepted. A CVM List carries no card data, and this only reads
 * the list the card states — it performs no cardholder verification. Book 3 v4.4, §10.5 and Annex C3.
 */
class CvmListDecodeTest {
    @Test
    fun `the Vector 3 CVM List decodes to its amounts and six CV rules`() {
        // 8E 14 | X=0 Y=0 · 4201 4403 4103 4203 1E03 1F03 (the published Vector 3 CVM List).
        val value = decode("8E14000000000000000042014403410342031E031F03").expectValue()
        assertTrue(value is CvmList, "the CVM List decodes to a CvmList, got $value")
        val cvm = value as CvmList
        assertEquals(0L, cvm.amountX, "amount X")
        assertEquals(0L, cvm.amountY, "amount Y")
        assertEquals(
            listOf(
                CvmRule(methodCode = 0x02, applyNextIfFailed = true, conditionCode = 0x01),
                CvmRule(methodCode = 0x04, applyNextIfFailed = true, conditionCode = 0x03),
                CvmRule(methodCode = 0x01, applyNextIfFailed = true, conditionCode = 0x03),
                CvmRule(methodCode = 0x02, applyNextIfFailed = true, conditionCode = 0x03),
                CvmRule(methodCode = 0x1E, applyNextIfFailed = false, conditionCode = 0x03),
                CvmRule(methodCode = 0x1F, applyNextIfFailed = false, conditionCode = 0x03),
            ),
            cvm.rules,
            "the six CV rules, in wire order",
        )
    }

    @Test
    fun `an amounts-only list is well-formed and has no rules`() {
        // 8E 08 | X=0 Y=0, no rules. A CVM List with the two amounts and nothing after them is a
        // complete list, not a malformed one.
        val cvm = decode("8E080000000000000000").expectValue() as CvmList
        assertEquals(0L, cvm.amountX)
        assertEquals(0L, cvm.amountY)
        assertTrue(cvm.rules.isEmpty(), "an amounts-only CVM List has no rules")
    }

    @Test
    fun `the amounts are four-octet binary integers, not BCD`() {
        // 8E 08 | X = 00 00 00 01, Y = FF FF FF FF. Big-endian unsigned: 1 and 4294967295, the four
        // full octets that overflow a signed Int and are why the amounts are Longs.
        val cvm = decode("8E0800000001FFFFFFFF").expectValue() as CvmList
        assertEquals(1L, cvm.amountX, "amount X is big-endian binary")
        assertEquals(4_294_967_295L, cvm.amountY, "amount Y is a full four octets, unsigned")
    }

    @Test
    fun `the code's b8 is dropped as RFU and its b7 sets apply-next independent of the method`() {
        // 8E 0C | X=0 Y=0 · 82 00 · C2 00. Both codes carry method 0x02 with b8 (0x80) set, which is
        // RFU and taken off; the first clears b7 (0x40) and the second sets it, so the flag turns on
        // apply-next without touching the method.
        val cvm = decode("8E0C00000000000000008200C200").expectValue() as CvmList
        assertEquals(
            listOf(
                CvmRule(methodCode = 0x02, applyNextIfFailed = false, conditionCode = 0x00),
                CvmRule(methodCode = 0x02, applyNextIfFailed = true, conditionCode = 0x00),
            ),
            cvm.rules,
            "b8 dropped, b7 sets apply-next, method masked to b6..b1",
        )
    }

    @Test
    fun `a value too short for the two amounts is malformed at the object`() {
        // 8E 04 | 00 00 00 00 — four octets cannot hold the two four-octet amounts.
        val error = decode("8E0400000000").expectError()
        assertTrue(error is DecodeError.MalformedCvmList, "too short is a malformed CVM List, got $error")
        assertEquals("8E", error.tag.hex, "the error names the CVM List object")
        // Buffer: 8E 04 00 00 00 00. The object's first identifier octet is index 0.
        assertEquals(0, error.offset, "an undersized list points at the object's own octet")
    }

    @Test
    fun `an odd octet left after the amounts is malformed at the stray octet`() {
        // 8E 09 | X=0 Y=0 · 42 — one octet after the amounts cannot complete a two-octet rule.
        val error = decode("8E09000000000000000042").expectError()
        assertTrue(error is DecodeError.MalformedCvmList, "a lone trailing octet is malformed, got $error")
        // Buffer: 8E 09 [8 amount octets] 42. The stray 42 sits at buffer index 10.
        assertEquals(10, error.offset, "the offset is the stray octet in the parsed buffer")
    }

    @Test
    fun `a CVM List is not cardholder data and is never wrapped sensitive`() {
        assertTrue(decode("8E080000000000000000").expectValue() is CvmList)
    }
}
