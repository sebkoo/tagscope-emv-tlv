package io.github.sebkoo.tagscope.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [CvmCodes.classifyMethod] and the [CvmCodes.METHODS] name table are two readings of one Annex C3
 * ranging, so they must agree: a code the classifier calls [CvmMethodClass.DEFINED] must be one the
 * name table gives a concrete method, and a code it calls reserved must be one the table names
 * reserved. The drift test walks the whole six-bit domain and checks the two never disagree, so a
 * later edit to one table that forgets the other fails here rather than silently letting a
 * consistency check pass a reserved method or flag a real one.
 */
class CvmMethodClassTest {
    @Test
    fun `classification agrees with the method-name table across the whole six-bit domain`() {
        for (code in 0x00..0x3F) {
            val name = CvmCodes.METHODS.getValue(code)
            when (CvmCodes.classifyMethod(code)) {
                CvmMethodClass.DEFINED -> {
                    assertFalse(name.startsWith("RFU")) { "0x${hex(code)} is DEFINED but named '$name'" }
                    assertFalse(name.startsWith("Reserved")) { "0x${hex(code)} is DEFINED but named '$name'" }
                }
                CvmMethodClass.RFU ->
                    assertTrue(name.startsWith("RFU")) { "0x${hex(code)} is RFU but named '$name'" }
                CvmMethodClass.PAYMENT_SYSTEM ->
                    assertEquals("Reserved for use by the individual payment systems", name, "0x${hex(code)}")
                CvmMethodClass.ISSUER ->
                    assertEquals("Reserved for use by the issuer", name, "0x${hex(code)}")
            }
        }
    }

    @Test
    fun `the range boundaries and the biometric and no-CVM overrides land where Annex C3 puts them`() {
        // Defined: fail, the PIN methods, and — per Book 3 v4.4 — the biometric CVMs 0x06..0x0F.
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x00))
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x05))
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x08), "Finger biometric, Book 3 v4.4")
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x0F))
        // Reserved for future use by the specification.
        assertEquals(CvmMethodClass.RFU, CvmCodes.classifyMethod(0x10))
        assertEquals(CvmMethodClass.RFU, CvmCodes.classifyMethod(0x1D))
        // Signature and No CVM required are defined again.
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x1E))
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x1F))
        // Payment-system and issuer ranges.
        assertEquals(CvmMethodClass.PAYMENT_SYSTEM, CvmCodes.classifyMethod(0x20))
        assertEquals(CvmMethodClass.PAYMENT_SYSTEM, CvmCodes.classifyMethod(0x2F))
        assertEquals(CvmMethodClass.ISSUER, CvmCodes.classifyMethod(0x30))
        assertEquals(CvmMethodClass.ISSUER, CvmCodes.classifyMethod(0x3E))
        // 0x3F is Book 4 v4.4 Annex A4's "No CVM performed", which overrides Table 43's "not available".
        assertEquals(CvmMethodClass.DEFINED, CvmCodes.classifyMethod(0x3F))
    }

    @Test
    fun `a code outside the six-bit method domain is treated as RFU`() {
        assertEquals(CvmMethodClass.RFU, CvmCodes.classifyMethod(0x40))
        assertEquals(CvmMethodClass.RFU, CvmCodes.classifyMethod(0xFF))
    }

    private fun hex(code: Int): String = code.toString(radix = 16).uppercase().padStart(2, '0')
}
