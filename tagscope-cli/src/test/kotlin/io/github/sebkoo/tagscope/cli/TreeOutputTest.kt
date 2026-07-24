package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The text tree — the CLI's headline output. One small deterministic vector is pinned exactly, so a
 * change to column alignment, indentation or a tag name is caught; the richer READ RECORD is checked
 * for its security-critical and bit-field behaviour rather than character for character.
 */
class TreeOutputTest {
    // Captured from the renderer for the PSE FCI vector: tag, name, length and value columns aligned
    // to the widest entry, children indented two spaces per level, no trailing newline.
    private val pseFciTree =
        listOf(
            "6F      File Control Information (FCI) Template              [21]",
            "  84    Dedicated File (DF) Name                             [14]  315041592E5359532E4444463031",
            "  A5    File Control Information (FCI) Proprietary Template  [3]",
            "    88  Short File Identifier (SFI)                          [1]   01",
        ).joinToString("\n")

    @Test
    fun `the PSE FCI vector renders as the exact aligned tree`() {
        val outcome = runCli(arrayOf(CliTestVectors.PSE_FCI)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the vector decodes")
        assertEquals(pseFciTree, outcome.stdout, "the tree matches the pinned layout exactly")
    }

    @Test
    fun `the READ RECORD tree masks the PAN and shows a marker in its place`() {
        val outcome = runCli(arrayOf(CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the record decodes")
        assertFalse(outcome.stdout.contains(CliTestVectors.PAN), "the default tree must not print the PAN")
        assertTrue(outcome.stdout.contains("masked"), "the PAN node shows a masked marker")
    }

    @Test
    fun `--reveal shows the PAN digits in the tree`() {
        val outcome = runCli(arrayOf("--reveal", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the record decodes")
        assertTrue(outcome.stdout.contains(CliTestVectors.PAN), "--reveal shows the PAN digits")
    }

    @Test
    fun `a decoded bit field prints its meanings one per indented line`() {
        val outcome = runCli(arrayOf(CliTestVectors.READ_RECORD)) { "" }

        // 9F07 (Application Usage Control) is a bit field; its set bits print as `      - <meaning>`,
        // indented a further two spaces for each level of nesting beneath the top of the tree.
        assertTrue(outcome.stdout.contains("      - "), "bit-field meanings print one per indented line")
    }
}
