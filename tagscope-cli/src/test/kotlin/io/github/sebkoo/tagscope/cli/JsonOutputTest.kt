package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The `--json` renderer. We ship no JSON parser, so the writer is validated the only dependency-free
 * way there is: one small vector is pinned character for character (this catches a malformed brace, a
 * dropped comma, a mis-escaped field), and the security-critical fields are asserted on the richer
 * READ RECORD.
 */
class JsonOutputTest {
    private val pseFciJson =
        """
        [
          {
            "tag": "6F",
            "name": "File Control Information (FCI) Template",
            "class": "application",
            "constructed": true,
            "length": 21,
            "children": [
              {
                "tag": "84",
                "name": "Dedicated File (DF) Name",
                "class": "context-specific",
                "constructed": false,
                "length": 14,
                "value": "315041592E5359532E4444463031",
                "hex": "315041592E5359532E4444463031"
              },
              {
                "tag": "A5",
                "name": "File Control Information (FCI) Proprietary Template",
                "class": "context-specific",
                "constructed": true,
                "length": 3,
                "children": [
                  {
                    "tag": "88",
                    "name": "Short File Identifier (SFI)",
                    "class": "context-specific",
                    "constructed": false,
                    "length": 1,
                    "value": "01",
                    "hex": "01"
                  }
                ]
              }
            ]
          }
        ]
        """.trimIndent()

    @Test
    fun `the PSE FCI vector renders as the exact JSON document`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.PSE_FCI)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the vector decodes")
        assertEquals(pseFciJson, outcome.stdout, "the JSON matches the pinned document exactly")
    }

    @Test
    fun `the default JSON flags the PAN sensitive, masks it, and omits its hex`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the record decodes")
        assertFalse(
            outcome.stdout.contains(CliTestVectors.PAN),
            "the default JSON prints the PAN neither as value nor hex",
        )
        assertTrue(outcome.stdout.contains("\"sensitive\": true"), "the PAN node is flagged sensitive")
        assertFalse(outcome.stdout.contains("\"hex\": \"5570"), "the masked PAN node carries no hex field")
    }

    @Test
    fun `--reveal shows the PAN as both value and hex in JSON`() {
        val outcome = runCli(arrayOf("--json", "--reveal", CliTestVectors.READ_RECORD)) { "" }

        assertTrue(outcome.stdout.contains("\"value\": \"${CliTestVectors.PAN}\""), "--reveal shows the PAN value")
        assertTrue(outcome.stdout.contains("\"hex\": \"${CliTestVectors.PAN}\""), "--reveal shows the PAN's raw hex")
    }

    @Test
    fun `a tag name with an inner hyphen passes through JSON verbatim`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.READ_RECORD)) { "" }

        assertTrue(
            outcome.stdout.contains("\"name\": \"Issuer Action Code - Default\""),
            "the dictionary name is emitted exactly, no escaping of an ordinary hyphen",
        )
    }
}
