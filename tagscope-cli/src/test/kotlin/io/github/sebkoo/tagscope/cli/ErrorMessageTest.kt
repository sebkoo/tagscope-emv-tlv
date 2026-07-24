package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The prose the CLI writes for a structural parse failure: the offset the library reports, the
 * authored reason for the specific variant, and nothing else — no value octets, no stack trace.
 */
class ErrorMessageTest {
    @Test
    fun `an indefinite length prints its authored reason with the offset`() {
        val outcome = runCli(arrayOf(CliTestVectors.INDEFINITE_LENGTH)) { "" }

        assertEquals(
            "parse error at offset 1: indefinite length (0x80) is not allowed in EMV",
            outcome.stderr,
            "the indefinite-length reason is written verbatim, offset and all",
        )
    }

    @Test
    fun `a truncated value reports how many octets it declared and how many were available`() {
        val outcome = runCli(arrayOf(CliTestVectors.TRUNCATED_VALUE)) { "" }

        assertEquals(
            "parse error at offset 5: value is truncated: declares 4 octets, 2 available",
            outcome.stderr,
            "the truncated-value reason names both the declared and available counts",
        )
    }

    @Test
    fun `every parse error opens with the offset`() {
        val outcome = runCli(arrayOf(CliTestVectors.TRUNCATED_VALUE)) { "" }

        assertTrue(outcome.stderr.startsWith("parse error at offset "), "the error leads with the offset")
    }

    @Test
    fun `a parse error is a single line with no stack trace`() {
        val outcome = runCli(arrayOf(CliTestVectors.INDEFINITE_LENGTH)) { "" }

        assertFalse(outcome.stderr.contains("\n"), "a parse error is one line")
        assertFalse(outcome.stderr.contains("\tat "), "a parse error carries no stack trace")
    }
}
