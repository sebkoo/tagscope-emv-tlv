package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Turning the user's hex text into bytes: case-insensitive, whitespace-insensitive, and — when the
 * text is not hex — an error that names a count or a single character, never the whole input, which
 * could be card data.
 */
class HexValidationTest {
    @Test
    fun `lowercase and uppercase hex decode the same`() {
        val upper = runCli(arrayOf("--json", "77 0A 82 02 1C 00 94 04 08 01 01 00")) { "" }
        val lower = runCli(arrayOf("--json", "77 0a 82 02 1c 00 94 04 08 01 01 00")) { "" }

        assertEquals(ExitCode.SUCCESS, lower.exitCode, "lowercase hex decodes")
        assertEquals(upper.stdout, lower.stdout, "case does not change the decode")
    }

    @Test
    fun `whitespace embedded anywhere in the hex is stripped`() {
        val outcome = runCli(arrayOf("  80 06\t1C 00 08 01 01 00  ")) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "surrounding and embedded whitespace is insignificant")
        assertTrue(outcome.stdout.contains("80"), "the tag was still parsed")
    }

    @Test
    fun `an odd number of hex digits is a usage error naming the count, not the input`() {
        val outcome = runCli(arrayOf("6F158")) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "an odd-length string cannot be bytes")
        assertTrue(outcome.stderr.contains("odd number of hex digits (5)"), "the error names the digit count")
        assertFalse(outcome.stderr.contains("6F158"), "the error must not echo the whole input")
    }

    @Test
    fun `a non-hex character is a usage error naming the character and its position`() {
        val outcome = runCli(arrayOf("6F15XY")) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "a non-hex character cannot be a nibble")
        assertTrue(outcome.stderr.contains("not a hex digit: 'X' at position 4"), "the error names the offending char")
        assertFalse(outcome.stderr.contains("6F15XY"), "the error must not echo the whole input")
    }
}
