package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How a Data Object List renders. The Visa PDOL (`9F38`) is a run of (tag, length) requests, and it
 * prints as entry sub-lines under the DOL rather than as a hex value — the tag, its dictionary name
 * (resolved here at render time, `Unknown` for a tag the dictionary does not carry yet), and the
 * octet count the terminal must supply, singular or plural. Vector 2's PDOL references the terminal
 * tags 9F33/9F35/9F40, which the dictionary now names.
 */
class DolOutputTest {
    private val pdolTree =
        listOf(
            "    9F38  Processing Options Data Object List (PDOL)           [12]",
            "          - 9F33  Terminal Capabilities  (3 bytes)",
            "          - 9F1A  Terminal Country Code  (2 bytes)",
            "          - 9F35  Terminal Type  (1 byte)",
            "          - 9F40  Additional Terminal Capabilities  (5 bytes)",
        ).joinToString("\n")

    @Test
    fun `a PDOL renders its entries as indented sub-lines with no hex value`() {
        val outcome = runCli(arrayOf(CliTestVectors.VISA_FCI_PDOL)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the vector decodes")
        assertTrue(outcome.stdout.contains(pdolTree), "the PDOL entries render one per line:\n${outcome.stdout}")
        // The DOL is a list of requests, not a value: the raw PDOL octets do not appear as a value.
        assertFalse(
            outcome.stdout.contains("9F33039F1A029F35019F4005"),
            "a DOL shows its entries, not its raw octets",
        )
    }

    @Test
    fun `a PDOL renders an entries array in JSON, and no value or hex`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.VISA_FCI_PDOL)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the vector decodes")
        val entries =
            """"entries": [{"tag": "9F33", "name": "Terminal Capabilities", "length": 3}, """ +
                """{"tag": "9F1A", "name": "Terminal Country Code", "length": 2}, """ +
                """{"tag": "9F35", "name": "Terminal Type", "length": 1}, """ +
                """{"tag": "9F40", "name": "Additional Terminal Capabilities", "length": 5}]"""
        assertTrue(outcome.stdout.contains(entries), "the PDOL emits a JSON entries array:\n${outcome.stdout}")
        assertFalse(
            outcome.stdout.contains("9F33039F1A029F35019F4005"),
            "a DOL node emits its entries, not a value or a hex field",
        )
    }
}
