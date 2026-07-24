package io.github.sebkoo.tagscope.cli

import io.github.sebkoo.tagscope.tlv.TagClass
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The JSON writer's escaping contract, exercised directly.
 *
 * No tag name or decoded meaning in the dictionary today holds a character JSON must escape or a
 * non-ASCII character, so no golden vector can reach these paths. Rather than leave [renderJson]'s
 * escaping untested, a [RenderNode] is built by hand with a name that carries a quote, a backslash, a
 * control character and a non-ASCII dash: only `"`, `\` and controls are escaped, and everything else
 * — including the en-dash — passes through as UTF-8.
 */
class JsonEscapingTest {
    private fun jsonFor(name: String): String =
        renderJson(
            listOf(
                RenderNode(
                    tagHex = "9F0D",
                    name = name,
                    tagClass = TagClass.CONTEXT_SPECIFIC,
                    constructed = false,
                    length = 1,
                    sensitive = false,
                    masked = false,
                    value = "00",
                    valueHex = "00",
                    meanings = emptyList(),
                    dolEntries = null,
                    cvm = null,
                    decodeNote = null,
                    children = emptyList(),
                ),
            ),
        )

    @Test
    fun `a quote and a backslash in a name are escaped`() {
        val json = jsonFor("a\"b\\c")

        assertTrue(json.contains("\"name\": \"a\\\"b\\\\c\""), "the quote becomes \\\" and the backslash becomes \\\\")
    }

    @Test
    fun `a control character in a name is escaped, not emitted raw`() {
        val json = jsonFor("line\nbreak")

        assertTrue(
            json.contains("\"name\": \"line\\nbreak\""),
            "a newline is written as the two characters backslash-n",
        )
    }

    @Test
    fun `a non-ASCII character passes through as UTF-8, not a unicode escape`() {
        val json = jsonFor("Issuer Action Code – Default")

        assertTrue(json.contains("Issuer Action Code – Default"), "the en-dash is written as its own character")
        assertFalse(json.contains("\\u2013"), "a printable non-ASCII character is not escaped to \\u")
    }
}
