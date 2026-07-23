package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TagReaderTest {
    @Test
    fun `reads a one-octet primitive application tag`() {
        val tag = TlvReader.readTag(hex("5A")).expectSuccess()

        assertEquals(0x5AL, tag.value)
        assertEquals(1, tag.octetLength)
        assertEquals(TagClass.APPLICATION, tag.tagClass)
        assertFalse(tag.isConstructed)
        assertEquals(0x1AL, tag.number)
        assertEquals("5A", tag.hex)
    }

    @Test
    fun `reads a one-octet constructed template tag`() {
        val tag = TlvReader.readTag(hex("6F")).expectSuccess()

        assertEquals(0x6FL, tag.value)
        assertEquals(1, tag.octetLength)
        assertEquals(TagClass.APPLICATION, tag.tagClass)
        assertTrue(tag.isConstructed)
        assertEquals(0x0FL, tag.number)
        assertEquals("6F", tag.hex)
    }

    @Test
    fun `reads 80 as primitive even though it carries structured data`() {
        val tag = TlvReader.readTag(hex("80")).expectSuccess()

        assertEquals(0x80L, tag.value)
        assertEquals(1, tag.octetLength)
        assertEquals(TagClass.CONTEXT_SPECIFIC, tag.tagClass)
        assertFalse(tag.isConstructed)
        assertEquals(0L, tag.number)
    }

    @Test
    fun `reads a one-octet constructed context-specific tag`() {
        val tag = TlvReader.readTag(hex("A5")).expectSuccess()

        assertEquals(TagClass.CONTEXT_SPECIFIC, tag.tagClass)
        assertTrue(tag.isConstructed)
        assertEquals(0x05L, tag.number)
        assertEquals("A5", tag.hex)
    }

    @Test
    fun `reads a zero octet as a one-octet universal tag`() {
        val tag = TlvReader.readTag(hex("00")).expectSuccess()

        assertEquals(0x00L, tag.value)
        assertEquals(1, tag.octetLength)
        assertEquals(TagClass.UNIVERSAL, tag.tagClass)
        assertFalse(tag.isConstructed)
        assertEquals(0L, tag.number)
        assertEquals("00", tag.hex)
        assertArrayEquals(hex("00"), tag.toBytes())
    }

    @Test
    fun `reads a two-octet private-class tag`() {
        val tag = TlvReader.readTag(hex("DF01")).expectSuccess()

        assertEquals(0xDF01L, tag.value)
        assertEquals(2, tag.octetLength)
        assertEquals(TagClass.PRIVATE, tag.tagClass)
        assertFalse(tag.isConstructed)
        assertEquals(1L, tag.number)
        assertEquals("DF01", tag.hex)
    }

    @Test
    fun `reads a two-octet tag as one tag`() {
        val tag = TlvReader.readTag(hex("9F26")).expectSuccess()

        assertEquals(0x9F26L, tag.value)
        assertEquals(2, tag.octetLength)
        assertEquals(TagClass.CONTEXT_SPECIFIC, tag.tagClass)
        assertFalse(tag.isConstructed)
        assertEquals(38L, tag.number)
        assertEquals("9F26", tag.hex)
        assertArrayEquals(hex("9F26"), tag.toBytes())
    }

    @Test
    fun `reads a two-octet application tag as one tag`() {
        val tag = TlvReader.readTag(hex("5F2A")).expectSuccess()

        assertEquals(0x5F2AL, tag.value)
        assertEquals(2, tag.octetLength)
        assertEquals(TagClass.APPLICATION, tag.tagClass)
        assertFalse(tag.isConstructed)
        assertEquals(42L, tag.number)
        assertEquals("5F2A", tag.hex)
    }

    @Test
    fun `reads a two-octet constructed tag as one tag`() {
        val tag = TlvReader.readTag(hex("BF0C")).expectSuccess()

        assertEquals(0xBF0CL, tag.value)
        assertEquals(2, tag.octetLength)
        assertEquals(TagClass.CONTEXT_SPECIFIC, tag.tagClass)
        assertTrue(tag.isConstructed)
        assertEquals(12L, tag.number)
        assertEquals("BF0C", tag.hex)
    }

    @Test
    fun `stops at the end of the tag and does not read the length octet`() {
        val tag = TlvReader.readTag(hex("5F34019F")).expectSuccess()

        assertEquals(0x5F34L, tag.value)
        assertEquals(2, tag.octetLength)
        assertEquals(52L, tag.number)
    }

    @Test
    fun `reads a tag at a non-zero offset`() {
        val tag = TlvReader.readTag(hex("70059F2602"), offset = 2).expectSuccess()

        assertEquals(0x9F26L, tag.value)
        assertEquals(2, tag.octetLength)
    }

    @Test
    fun `concatenates the number of a three-octet tag seven bits at a time`() {
        val tag = TlvReader.readTag(hex("9F8105")).expectSuccess()

        assertEquals(0x9F8105L, tag.value)
        assertEquals(3, tag.octetLength)
        assertEquals(133L, tag.number)
        assertEquals("9F8105", tag.hex)
        assertArrayEquals(hex("9F8105"), tag.toBytes())
    }

    @Test
    fun `reads a tag of the maximum supported width`() {
        val tag = TlvReader.readTag(hex("9F818101")).expectSuccess()

        assertEquals(0x9F818101L, tag.value)
        assertEquals(TlvTag.MAX_IDENTIFIER_OCTETS, tag.octetLength)
        assertArrayEquals(hex("9F818101"), tag.toBytes())
    }

    @Test
    fun `rejects empty input`() {
        val error = TlvReader.readTag(hex("")).expectFailure()

        assertEquals(TlvError.UnexpectedEndOfData(offset = 0, size = 0), error)
    }

    @Test
    fun `rejects an offset at the end of the buffer`() {
        val error = TlvReader.readTag(hex("9F26"), offset = 2).expectFailure()

        assertEquals(TlvError.UnexpectedEndOfData(offset = 2, size = 2), error)
    }

    @Test
    fun `rejects an offset outside the buffer`() {
        assertEquals(
            TlvError.UnexpectedEndOfData(offset = 7, size = 2),
            TlvReader.readTag(hex("9F26"), offset = 7).expectFailure(),
        )
        assertEquals(
            TlvError.UnexpectedEndOfData(offset = -1, size = 2),
            TlvReader.readTag(hex("9F26"), offset = -1).expectFailure(),
        )
    }

    @Test
    fun `rejects a multi-octet tag with no subsequent octet`() {
        val error = TlvReader.readTag(hex("9F")).expectFailure()

        assertEquals(TlvError.TruncatedTag(offset = 1), error)
    }

    @Test
    fun `rejects a multi-octet tag truncated after a continuation octet`() {
        val error = TlvReader.readTag(hex("9F81")).expectFailure()

        assertEquals(TlvError.TruncatedTag(offset = 2), error)
    }

    @Test
    fun `reports the truncation offset relative to the whole buffer`() {
        val error = TlvReader.readTag(hex("7002825F"), offset = 3).expectFailure()

        assertEquals(TlvError.TruncatedTag(offset = 4), error)
    }

    @Test
    fun `rejects a tag wider than the maximum supported width`() {
        assertEquals(
            TlvError.TagTooLong(offset = 4, maxOctets = TlvTag.MAX_IDENTIFIER_OCTETS),
            TlvReader.readTag(hex("9F81818101")).expectFailure(),
        )
        assertEquals(
            TlvError.TagTooLong(offset = 6, maxOctets = TlvTag.MAX_IDENTIFIER_OCTETS),
            TlvReader.readTag(hex("70039F81818101"), offset = 2).expectFailure(),
        )
    }

    @Test
    fun `reports truncation when a maximum-width tag also runs out of octets`() {
        val error = TlvReader.readTag(hex("9F818181")).expectFailure()

        assertEquals(TlvError.TruncatedTag(offset = 4), error)
    }
}
