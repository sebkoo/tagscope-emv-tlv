package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The argument vector: which flags are recognised, that they are order-independent, and that an
 * unparsable command line fails cleanly — naming an option only when it is safely option-shaped,
 * never echoing a value that might be card data.
 */
class ArgParsingTest {
    @Test
    fun `a bare positional hex string decodes to the text tree`() {
        val outcome = runCli(arrayOf(CliTestVectors.GPO_FMT2)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a well-formed positional decodes")
        assertTrue(outcome.stdout.contains("Application Interchange Profile"), "the tree names the decoded tags")
    }

    @Test
    fun `--json selects the JSON renderer`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.GPO_FMT2)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the same input decodes under --json")
        assertTrue(outcome.stdout.startsWith("["), "JSON output is an array")
        assertTrue(outcome.stdout.contains("\"tag\": \"77\""), "the JSON carries the tag field")
    }

    @Test
    fun `--reveal is accepted and decodes successfully`() {
        val outcome = runCli(arrayOf("--reveal", CliTestVectors.GPO_FMT2)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "--reveal on non-sensitive data still decodes")
    }

    @Test
    fun `--json and --reveal combine`() {
        val outcome = runCli(arrayOf("--json", "--reveal", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "both flags together decode")
        assertTrue(outcome.stdout.startsWith("["), "the output is JSON")
        assertTrue(outcome.stdout.contains(CliTestVectors.PAN), "--reveal shows the PAN under --json")
    }

    @Test
    fun `a flag after the positional parses the same as before it`() {
        val before = runCli(arrayOf("--json", CliTestVectors.GPO_FMT2)) { "" }
        val after = runCli(arrayOf(CliTestVectors.GPO_FMT2, "--json")) { "" }

        assertEquals(before.exitCode, after.exitCode, "flag order does not change the exit code")
        assertEquals(before.stdout, after.stdout, "flag order does not change the output")
    }

    @Test
    fun `a repeated flag is idempotent`() {
        val once = runCli(arrayOf("--json", CliTestVectors.GPO_FMT2)) { "" }
        val twice = runCli(arrayOf("--json", "--json", CliTestVectors.GPO_FMT2)) { "" }

        assertEquals(ExitCode.SUCCESS, twice.exitCode, "a duplicated flag is accepted, not an error")
        assertEquals(once.stdout, twice.stdout, "a duplicated flag produces the same output as one")
    }

    @Test
    fun `an unknown option-shaped flag is named in the usage error`() {
        val outcome = runCli(arrayOf("--bogus", CliTestVectors.GPO_FMT2)) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "an unknown flag is a usage error")
        assertTrue(outcome.stderr.contains("unknown option: --bogus"), "a plainly option-shaped flag is named")
    }

    @Test
    fun `an unknown flag error never echoes the positional value`() {
        val outcome = runCli(arrayOf("--bogus", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "an unknown flag is a usage error")
        assertFalse(outcome.stderr.contains(CliTestVectors.PAN), "the positional value must not reach the error")
    }

    // The security guard: a value dressed as an option (a PAN typed without a leading space, so the
    // shell hands it over as `-5570...`) is digit-led, does not match the option-name shape, and so is
    // described generically — the digits are withheld — while a genuine letter-led flag is named.
    @Test
    fun `a dash-digit token is rejected without echoing its digits`() {
        val outcome = runCli(arrayOf("-${CliTestVectors.PAN}")) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "a dash-prefixed token is an unknown option")
        assertTrue(outcome.stderr.contains("unknown option"), "the error still says an option was unknown")
        assertFalse(outcome.stderr.contains(CliTestVectors.PAN), "a digit-led token is withheld, not echoed")
    }
}
