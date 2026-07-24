package io.github.sebkoo.tagscope.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * The public CVM code tables: a method or condition code resolved to the wording EMV Book 3 Annex C3
 * gives it, shared by the CVM List (`8E`) and the CVM Results bit field (`9F34`). The tables cover
 * their whole domains — a method code is `0x00..0x3F`, a condition code `0x00..0xFF` — so a code from
 * a real decode always names; the fallback guards only a code from outside those ranges, and never
 * throws.
 */
class CvmCodesTest {
    @Test
    fun `known method codes resolve to their Annex C3 names`() {
        assertEquals("Fail CVM processing", CvmCodes.method(0x00))
        assertEquals("Plaintext PIN verification performed by ICC", CvmCodes.method(0x01))
        assertEquals("Enciphered PIN verified online", CvmCodes.method(0x02))
        assertEquals("Enciphered PIN verification performed by ICC", CvmCodes.method(0x04))
        assertEquals("Signature", CvmCodes.method(0x1E))
        assertEquals("No CVM required", CvmCodes.method(0x1F))
        assertEquals("No CVM performed", CvmCodes.method(0x3F))
    }

    @Test
    fun `known condition codes resolve to their Annex C3 names`() {
        assertEquals("Always", CvmCodes.condition(0x00))
        assertEquals("If unattended cash", CvmCodes.condition(0x01))
        assertEquals("If terminal supports the CVM", CvmCodes.condition(0x03))
    }

    @Test
    fun `the reserved ranges resolve to their range wording`() {
        assertEquals("RFU (reserved for future use by this specification)", CvmCodes.method(0x15))
        assertEquals("Reserved for use by the individual payment systems", CvmCodes.method(0x25))
        assertEquals("Reserved for use by the issuer", CvmCodes.method(0x35))
        assertEquals("RFU", CvmCodes.condition(0x50))
        assertEquals("Reserved for use by individual payment systems", CvmCodes.condition(0xA0))
    }

    @Test
    fun `every method code a decode can produce resolves without the fallback`() {
        // A CvmRule's methodCode is the six low bits, so 0x00..0x3F is the whole domain. All of it is
        // named, so the fallback never fires for a real decode.
        for (code in 0x00..0x3F) {
            assertFalse(CvmCodes.method(code).startsWith("RFU/unknown"), "method 0x${code.toString(16)} is named")
        }
    }

    @Test
    fun `every condition code resolves without the fallback`() {
        // A condition code is a whole octet, so 0x00..0xFF is the whole domain and all of it is named.
        for (code in 0x00..0xFF) {
            assertFalse(CvmCodes.condition(code).startsWith("RFU/unknown"), "condition 0x${code.toString(16)} is named")
        }
    }

    @Test
    fun `a code outside the mapped range falls back rather than throwing`() {
        // 0x40 is past the six-bit method domain; the fallback prints the hex so it can be found in
        // the raw octets. This guards the public API — a real CvmRule never carries such a code.
        assertEquals("RFU/unknown (0x40)", CvmCodes.method(0x40))
    }
}
