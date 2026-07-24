package io.github.sebkoo.tagscope.cli

/**
 * What the command line asked for: an action and how to render it.
 *
 * Deliberately tiny. There are four flags and one positional argument, so a hand-rolled parse is
 * smaller, and has fewer moving parts, than any dependency would add, and it keeps the whole repo
 * free of runtime dependencies — a selling point rather than an accident. A subcommand layer
 * (`decode` / `encode` / …) is a later addition: today decode is the only action, and the default
 * one, so a bare `tagscope <hex>` decodes.
 */
internal sealed interface CliCommand {
    /** Print usage and exit successfully. */
    data object Help : CliCommand

    /** Print the version and exit successfully. */
    data object Version : CliCommand

    /**
     * Decode input into a tree, or JSON when [json] is set. Sensitive values are masked unless
     * [reveal]. [hex] is the positional argument, or `null` to read standard input instead.
     */
    data class Decode(
        val hex: String?,
        val json: Boolean,
        val reveal: Boolean,
    ) : CliCommand

    /** The command line did not parse; [message] says why. */
    data class Invalid(
        val message: String,
    ) : CliCommand
}

/**
 * Parses the argument vector. `--help`/`-h` and `--version` win over everything and return at once.
 * A `--` marks the end of options, so a positional that begins with a dash can still be given after
 * it. An unknown option, or a second positional, is an [CliCommand.Invalid] rather than a silent
 * guess.
 */
internal fun parseArgs(args: Array<String>): CliCommand {
    var json = false
    var reveal = false
    var positional: String? = null
    var endOfOptions = false

    for (arg in args) {
        val looksLikeOption = !endOfOptions && arg.startsWith("-") && arg != "-"
        when {
            !endOfOptions && arg == "--" -> endOfOptions = true
            looksLikeOption && (arg == "--help" || arg == "-h") -> return CliCommand.Help
            looksLikeOption && arg == "--version" -> return CliCommand.Version
            looksLikeOption && arg == "--json" -> json = true
            looksLikeOption && arg == "--reveal" -> reveal = true
            looksLikeOption -> return CliCommand.Invalid(unknownOption(arg))
            // The extra token is echoed *only* as guidance, never verbatim: a bare hex string is
            // card data, and a second positional (`tagscope 00 <pan>`) would put a PAN in an error
            // message. This mirrors the rule HexResult keeps — never echo the whole input.
            positional != null ->
                return CliCommand.Invalid("unexpected extra argument; give one hex string (quoted) or pipe it on stdin")
            else -> positional = arg
        }
    }

    return CliCommand.Decode(hex = positional, json = json, reveal = reveal)
}

/**
 * Names an unknown option, but only when it is plainly option-shaped — letters and dashes. A token
 * a user prefixed with a dash (`-5570...`) could be card data, so it is described, not echoed: the
 * "never print a PAN" rule holds even for a value that arrives dressed as an option.
 */
private fun unknownOption(arg: String): String =
    if (arg.matches(OPTION_NAME)) "unknown option: $arg" else "unknown option"

private val OPTION_NAME = Regex("^--?[A-Za-z][A-Za-z0-9-]*$")
