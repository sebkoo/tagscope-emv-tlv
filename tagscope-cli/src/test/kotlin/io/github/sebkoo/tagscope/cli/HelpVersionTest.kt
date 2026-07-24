package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two informational actions. Each wins over everything else on the command line, exits zero, and
 * reads no input — proven by a standard-input source that throws if it is ever consulted.
 */
class HelpVersionTest {
    private val stdinMustNotBeRead: () -> String = { throw AssertionError("help and version read no input") }

    @Test
    fun `--version prints the tool name and a version`() {
        val outcome = runCli(arrayOf("--version"), stdinMustNotBeRead)

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "--version exits successfully")
        assertTrue(outcome.stdout.startsWith("tagscope "), "the version line names the tool")
    }

    @Test
    fun `--help prints usage and exits successfully without reading input`() {
        val outcome = runCli(arrayOf("--help"), stdinMustNotBeRead)

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "--help exits successfully")
        assertTrue(outcome.stdout.contains("Usage:"), "the help text opens with a usage line")
    }

    @Test
    fun `the short -h prints the same usage as --help`() {
        val long = runCli(arrayOf("--help"), stdinMustNotBeRead)
        val short = runCli(arrayOf("-h"), stdinMustNotBeRead)

        assertEquals(ExitCode.SUCCESS, short.exitCode, "-h exits successfully")
        assertEquals(long.stdout, short.stdout, "-h and --help print the same text")
    }

    @Test
    fun `help wins even when other arguments are present`() {
        val outcome = runCli(arrayOf(CliTestVectors.READ_RECORD, "--help"), stdinMustNotBeRead)

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "--help short-circuits the decode")
        assertTrue(outcome.stdout.contains("Usage:"), "help is printed instead of a decode")
    }
}
