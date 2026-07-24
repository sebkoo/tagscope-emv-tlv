package io.github.sebkoo.tagscope.cli

import io.github.sebkoo.tagscope.lint.Severity
import io.github.sebkoo.tagscope.lint.TlvLinter
import io.github.sebkoo.tagscope.tlv.TlvParser
import io.github.sebkoo.tagscope.tlv.TlvResult

/** What the CLI produced without touching the process: what to print, and the exit code. */
internal data class CliOutcome(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
)

/** The exit codes the CLI returns. Success is 0; a parse error and a usage error are told apart. */
internal object ExitCode {
    const val SUCCESS: Int = 0
    const val PARSE_ERROR: Int = 1
    const val USAGE_ERROR: Int = 2

    /**
     * `lint` decoded the input but raised at least one ERROR-level finding. Shares the value of
     * [PARSE_ERROR] — both mean "the data has a problem" — so a script gating on a non-zero exit
     * treats a failed lint like a failed parse, which is what a certification check wants.
     */
    const val LINT_ERROR: Int = 1
}

/**
 * Runs the CLI as a pure function of its arguments and its input: no process exit, no global
 * streams. Every path — tree, JSON, masking, errors, help, version — is therefore testable in
 * process, and [main] is the only thing that reads standard input, writes the console, and sets the
 * exit code.
 *
 * @param readStdin called only when there is no positional hex argument; returns the piped input.
 */
internal fun runCli(
    args: Array<String>,
    readStdin: () -> String,
): CliOutcome =
    when (val command = parseArgs(args)) {
        is CliCommand.Help -> CliOutcome(helpText(), "", ExitCode.SUCCESS)
        is CliCommand.Version -> CliOutcome("tagscope ${tagscopeVersion()}", "", ExitCode.SUCCESS)
        is CliCommand.Invalid -> usage(command.message)
        is CliCommand.Decode -> decode(command, readStdin)
        is CliCommand.Lint -> lint(command, readStdin)
    }

private fun decode(
    command: CliCommand.Decode,
    readStdin: () -> String,
): CliOutcome {
    val input = command.hex ?: readStdin()
    return when (val hex = parseHexInput(input)) {
        is HexResult.Invalid -> usage(hex.message)
        is HexResult.Ok -> render(hex.bytes, command.json, command.reveal)
    }
}

private fun render(
    bytes: ByteArray,
    json: Boolean,
    reveal: Boolean,
): CliOutcome =
    when (val parsed = TlvParser.parse(bytes)) {
        is TlvResult.Failure -> CliOutcome("", parseErrorLine(parsed.error), ExitCode.PARSE_ERROR)
        is TlvResult.Success -> {
            val described = describe(parsed.value, reveal)
            val output = if (json) renderJson(described) else renderTree(described)
            CliOutcome(output, "", ExitCode.SUCCESS)
        }
    }

/**
 * Decodes the input and reports consistency findings. A structural parse failure is still a parse
 * error; a clean parse is linted, and an ERROR-level finding drives a non-zero exit so the command
 * can gate a script or CI step the way a certification check does. The report itself is on stdout —
 * it names tags and describes defects, never a value.
 */
private fun lint(
    command: CliCommand.Lint,
    readStdin: () -> String,
): CliOutcome {
    val input = command.hex ?: readStdin()
    return when (val hex = parseHexInput(input)) {
        is HexResult.Invalid -> usage(hex.message)
        is HexResult.Ok ->
            when (val parsed = TlvParser.parse(hex.bytes)) {
                is TlvResult.Failure -> CliOutcome("", parseErrorLine(parsed.error), ExitCode.PARSE_ERROR)
                is TlvResult.Success -> {
                    val findings = TlvLinter.DEFAULT.lint(parsed.value)
                    val exit =
                        if (findings.any { it.severity == Severity.ERROR }) ExitCode.LINT_ERROR else ExitCode.SUCCESS
                    CliOutcome(renderFindings(findings), "", exit)
                }
            }
    }
}

private fun usage(message: String): CliOutcome =
    CliOutcome("", "tagscope: $message\nTry 'tagscope --help' for usage.", ExitCode.USAGE_ERROR)
