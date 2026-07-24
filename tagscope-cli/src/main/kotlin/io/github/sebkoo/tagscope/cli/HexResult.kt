package io.github.sebkoo.tagscope.cli

/**
 * The outcome of reading a user's hex string: the bytes, or why it was not hex.
 *
 * Absence of a clean parse is modelled here rather than thrown, matching the library's habit of
 * returning failures for expected-bad input.
 */
internal sealed interface HexResult {
    data class Ok(
        val bytes: ByteArray,
    ) : HexResult

    data class Invalid(
        val message: String,
    ) : HexResult
}

/**
 * Turns the hex text a user typed, or piped, into the bytes the library parses.
 *
 * The library takes bytes a caller already holds; getting from a command-line string to those
 * bytes is the CLI's job, and so is telling the user cleanly when the string is not hex. (The
 * library's own hex helper is test-only and internal to another module, so this is written here
 * rather than borrowed.)
 *
 * Whitespace of every kind is insignificant, so a trace pasted across several lines, or grouped in
 * octet pairs, decodes the same as one unbroken run. An error names a count or the single offending
 * character, never the whole input: the input is card data, and an error message is exactly the
 * sort of place it must not land.
 */
internal fun parseHexInput(text: String): HexResult {
    val compact =
        buildString(text.length) {
            for (ch in text) {
                if (!ch.isWhitespace()) append(ch)
            }
        }

    if (compact.isEmpty()) {
        return HexResult.Invalid("no input: expected a hex string as an argument or on standard input")
    }
    if (compact.length % 2 != 0) {
        return HexResult.Invalid("odd number of hex digits (${compact.length}); each byte needs two")
    }
    for ((position, ch) in compact.withIndex()) {
        if (Character.digit(ch, HEX_RADIX) < 0) {
            return HexResult.Invalid("not a hex digit: '$ch' at position $position")
        }
    }

    val bytes =
        ByteArray(compact.length / 2) { index ->
            val high = Character.digit(compact[index * 2], HEX_RADIX)
            val low = Character.digit(compact[index * 2 + 1], HEX_RADIX)
            ((high shl NIBBLE_BITS) or low).toByte()
        }
    return HexResult.Ok(bytes)
}

private const val HEX_RADIX: Int = 16
private const val NIBBLE_BITS: Int = 4
