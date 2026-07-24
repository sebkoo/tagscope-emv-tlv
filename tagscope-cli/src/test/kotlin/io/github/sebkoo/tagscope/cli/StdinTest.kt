package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where the hex comes from: standard input when no positional is given, the positional otherwise.
 * A positional wins outright — stdin is not even read — and an empty or blank stream is a usage
 * error rather than a silent success.
 */
class StdinTest {
    @Test
    fun `hex is read from standard input when no positional is given`() {
        val outcome = runCli(arrayOf("--json")) { CliTestVectors.GPO_FMT2 }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "piped hex decodes")
        assertTrue(outcome.stdout.contains("\"tag\": \"77\""), "the piped input was parsed")
    }

    @Test
    fun `whitespace and newlines in the piped input are ignored`() {
        val outcome = runCli(arrayOf("--json")) { "77 0A\n82 02 1C 00\n94 04 08 01 01 00\n" }
        val compact = runCli(arrayOf("--json")) { "770A82021C00940408010100" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a trace split across lines decodes")
        assertEquals(compact.stdout, outcome.stdout, "grouping and newlines make no difference")
    }

    @Test
    fun `a positional takes precedence and standard input is never read`() {
        val outcome =
            runCli(arrayOf(CliTestVectors.GPO_FMT2)) {
                throw AssertionError("standard input must not be read when a positional is present")
            }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the positional decodes")
        assertTrue(outcome.stdout.contains("Application Interchange Profile"), "the positional was the source")
    }

    @Test
    fun `empty standard input with no positional is a usage error`() {
        val outcome = runCli(arrayOf<String>()) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "no input at all is a usage error")
        assertTrue(outcome.stderr.contains("no input"), "the error says there was no input")
        assertTrue(outcome.stdout.isEmpty(), "a usage error writes nothing to standard output")
    }

    @Test
    fun `whitespace-only standard input with no positional is a usage error`() {
        val outcome = runCli(arrayOf<String>()) { "   \n\t  \n" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "blank input decodes to nothing, a usage error")
        assertFalse(outcome.stderr.isEmpty(), "the usage error is reported on standard error")
    }
}
