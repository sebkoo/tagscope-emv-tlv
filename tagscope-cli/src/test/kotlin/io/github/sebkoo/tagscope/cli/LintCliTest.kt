package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `lint` subcommand end to end: it routes off a leading `lint`, reports findings on stdout,
 * exits non-zero on an ERROR so a script can gate on it, reads stdin like decode, and never prints a
 * value — the same card-data guarantees the decode path keeps, on a command whose whole job is to
 * describe defects in data that includes cardholder data.
 */
class LintCliTest {
    @Test
    fun `a clean tree lints with no findings and exit zero`() {
        val outcome = runCli(arrayOf("lint", CliTestVectors.GPO_FMT2)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a clean vector passes lint")
        assertEquals("no findings", outcome.stdout, "a clean tree reports no findings")
    }

    @Test
    fun `an FCI missing a mandatory tag is an ERROR and exits non-zero`() {
        val outcome = runCli(arrayOf("lint", CliTestVectors.FCI_MISSING_DF_NAME)) { "" }

        assertEquals(ExitCode.LINT_ERROR, outcome.exitCode, "an ERROR finding drives a non-zero exit")
        assertTrue(outcome.stdout.contains("ERROR"), "the report marks the severity")
        assertTrue(outcome.stdout.contains("fci-mandatory"), "the report names the rule")
        assertTrue(outcome.stdout.contains("DF Name (84)"), "the report says what is missing")
    }

    @Test
    fun `a warning-only tree still exits zero`() {
        val outcome = runCli(arrayOf("lint", CliTestVectors.AIP_RFU_BIT)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a WARNING does not gate a run; only an ERROR does")
        assertTrue(outcome.stdout.contains("WARNING"), "the warning is reported")
        assertTrue(outcome.stdout.contains("rfu-bits"), "the report names the rule")
    }

    @Test
    fun `lint reads hex from standard input when no positional is given`() {
        val outcome = runCli(arrayOf("lint")) { CliTestVectors.FCI_MISSING_DF_NAME }

        assertEquals(ExitCode.LINT_ERROR, outcome.exitCode, "the piped input is linted")
        assertTrue(outcome.stdout.contains("fci-mandatory"), "the finding is on the piped input")
    }

    @Test
    fun `lint of the PAN-bearing record never prints the PAN`() {
        val outcome = runCli(arrayOf("lint", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "vector 3 lints clean")
        assertFalse(outcome.stdout.contains(CliTestVectors.PAN), "no finding prints a value, masked or not")
    }

    @Test
    fun `lint rejects a decode-only option rather than ignoring it`() {
        val outcome = runCli(arrayOf("lint", "--reveal", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "--reveal is not a lint option")
        assertTrue(outcome.stderr.contains("unknown option: --reveal"), "the rejected option is named")
    }

    @Test
    fun `a structural parse failure under lint is still a parse error`() {
        val outcome = runCli(arrayOf("lint", CliTestVectors.INDEFINITE_LENGTH)) { "" }

        assertEquals(ExitCode.PARSE_ERROR, outcome.exitCode, "malformed BER-TLV fails before any rule runs")
        assertTrue(outcome.stderr.contains("parse error"), "the parse error is reported")
    }
}
