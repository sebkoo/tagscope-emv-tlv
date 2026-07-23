package io.github.sebkoo.tagscope.tags

import io.github.sebkoo.tagscope.tlv.TagClass
import io.github.sebkoo.tagscope.tlv.TlvTag
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * TagInfo is a value object, so what is worth pinning is what it refuses to be built as, and that
 * the class and the constructed bit come off the tag rather than being carried beside it.
 */
class TagInfoTest {
    @Test
    fun `the class and the constructed bit are read from the tag itself`() {
        val template = info(tag = TlvTag(0x6F, 1), format = TagFormat.VAR)
        val element = info(tag = TlvTag(0x9F26, 2), format = TagFormat.BINARY)

        assertEquals(TagClass.APPLICATION, template.tagClass)
        assertTrue(template.isConstructed)
        assertEquals(TagClass.CONTEXT_SPECIFIC, element.tagClass)
        assertFalse(element.isConstructed)
    }

    @Test
    fun `80 is primitive, even though its value carries structured data`() {
        val formatOne = info(tag = TlvTag(0x80, 1), format = TagFormat.VAR)

        assertFalse(formatOne.isConstructed)
    }

    @Test
    fun `a nought minimum is allowed, since a variable-length field has no stated minimum`() {
        val varying = info(minLength = 0, maxLength = 252)

        assertEquals(0, varying.minLength)
    }

    @Test
    fun `a blank name is rejected`() {
        assertThrows<IllegalArgumentException> { info(name = "  ") }
    }

    @Test
    fun `a negative minimum is rejected`() {
        assertThrows<IllegalArgumentException> { info(minLength = -1, maxLength = 4) }
    }

    @Test
    fun `bounds the wrong way round are rejected`() {
        assertThrows<IllegalArgumentException> { info(minLength = 5, maxLength = 4) }
    }

    @Test
    fun `an entry has no note until it is given one`() {
        assertEquals("", info().note)
        assertFalse(info().isSensitive)
    }

    private fun info(
        tag: TlvTag = TlvTag(0x9F36, 2),
        name: String = "Application Transaction Counter (ATC)",
        format: TagFormat = TagFormat.BINARY,
        minLength: Int = 2,
        maxLength: Int = 2,
    ): TagInfo =
        TagInfo(
            tag = tag,
            name = name,
            format = format,
            minLength = minLength,
            maxLength = maxLength,
        )
}
