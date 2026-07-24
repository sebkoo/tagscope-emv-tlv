package io.github.sebkoo.tagscope.decode

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [DecodedValue.BitField.SetFlag.isRfu] is the checked answer to "is this a plain RFU bit?", so a
 * consistency checker reads it instead of testing the meaning string itself. It is true for exactly
 * the [DecodedValue.BitField.SetFlag.RFU_MEANING] `ValueDecoder` synthesises for an unnamed set bit,
 * and false for a position reserved with more specific wording — which the decoder lists by name —
 * or any named meaning.
 */
class SetFlagIsRfuTest {
    @Test
    fun `a plain RFU flag reports isRfu`() {
        assertTrue(flag("RFU").isRfu)
        assertTrue(flag(DecodedValue.BitField.SetFlag.RFU_MEANING).isRfu, "RFU_MEANING is the marker")
    }

    @Test
    fun `a specifically-reserved or named flag does not report isRfu`() {
        assertFalse(flag("Reserved for use by the EMV Contactless Specifications").isRfu)
        assertFalse(flag("SDA supported").isRfu)
        assertFalse(flag("Issuer authentication failed").isRfu)
    }

    private fun flag(meaning: String): DecodedValue.BitField.SetFlag =
        DecodedValue.BitField.SetFlag(byteIndex = 0, bit = 1, meaning = meaning)
}
