package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The three exit codes, and which stream each outcome uses: success on standard output, every kind
 * of failure on standard error. A parse failure (1) is told apart from a usage failure (2), and the
 * two never cross streams.
 */
class ExitCodeRoutingTest {
    @Test
    fun `a well-formed decode exits zero on standard output`() {
        val outcome = runCli(arrayOf(CliTestVectors.PSE_FCI)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a good decode is success")
        assertFalse(outcome.stdout.isEmpty(), "success writes to standard output")
        assertTrue(outcome.stderr.isEmpty(), "success writes nothing to standard error")
    }

    @Test
    fun `a truncated value is a parse error on standard error`() {
        val outcome = runCli(arrayOf(CliTestVectors.TRUNCATED_VALUE)) { "" }

        assertEquals(ExitCode.PARSE_ERROR, outcome.exitCode, "a value that overruns the buffer is a parse error")
        assertFalse(outcome.stderr.isEmpty(), "a parse error writes to standard error")
        assertTrue(outcome.stdout.isEmpty(), "a parse error writes nothing to standard output")
    }

    @Test
    fun `an indefinite length is a parse error on standard error`() {
        val outcome = runCli(arrayOf(CliTestVectors.INDEFINITE_LENGTH)) { "" }

        assertEquals(ExitCode.PARSE_ERROR, outcome.exitCode, "an indefinite length is rejected by the parser")
        assertFalse(outcome.stderr.isEmpty(), "a parse error writes to standard error")
        assertTrue(outcome.stdout.isEmpty(), "a parse error writes nothing to standard output")
    }

    @Test
    fun `a usage problem is exit two on standard error`() {
        val outcome = runCli(arrayOf("zz")) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "bad input is a usage error, not a parse error")
        assertFalse(outcome.stderr.isEmpty(), "a usage error writes to standard error")
        assertTrue(outcome.stdout.isEmpty(), "a usage error writes nothing to standard output")
    }
}
