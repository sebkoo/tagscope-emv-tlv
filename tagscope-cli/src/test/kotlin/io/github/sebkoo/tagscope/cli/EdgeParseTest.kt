package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The well-formed edges: input that is valid BER-TLV yet holds nothing to show, a primitive whose
 * value is structured but must not be recursed into, and the full set of golden vectors each landing
 * on a clean success.
 */
class EdgeParseTest {
    @Test
    fun `an all-filler input is an empty but successful decode as a tree`() {
        val outcome = runCli(arrayOf("00")) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a lone 0x00 filler octet is well-formed")
        assertEquals("", outcome.stdout, "there is nothing to render, so the tree is empty")
    }

    @Test
    fun `an all-filler input is an empty JSON array`() {
        val outcome = runCli(arrayOf("--json", "00")) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a lone 0x00 filler octet is well-formed")
        assertEquals("[]", outcome.stdout, "an empty forest is the empty JSON array")
    }

    @Test
    fun `a primitive 80 is a leaf and is not recursed into`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.GPO_FMT1)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a primitive 80 decodes")
        assertTrue(outcome.stdout.contains("\"tag\": \"80\""), "the 80 node is present")
        assertFalse(outcome.stdout.contains("\"children\""), "a primitive carries no children array")
    }

    @Test
    fun `every golden vector decodes cleanly to a non-empty tree`() {
        for ((name, hex) in CliTestVectors.ALL) {
            val outcome = runCli(arrayOf(hex)) { "" }

            assertEquals(ExitCode.SUCCESS, outcome.exitCode, "$name decodes without error")
            assertFalse(outcome.stdout.isEmpty(), "$name renders at least one node")
        }
    }
}
