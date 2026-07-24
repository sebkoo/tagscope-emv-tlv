package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How a Cardholder Verification Method List (`8E`) renders. It prints as an amounts header line and
 * one CV Rule per line beneath the node — the method and condition names resolved at render time from
 * their codes, and whether an unsuccessful CVM applies the next rule or fails — the same indented form
 * the DOL entries and bit-field meanings take. Like a DOL, it emits no hex value: its sub-lines are
 * the decode. The Vector-3 CVM List is the oracle, derived byte-for-byte in the library's golden
 * vectors.
 */
class CvmOutputTest {
    private val cvmTree =
        listOf(
            "  8E    Cardholder Verification Method (CVM) List                 [20]",
            "        amounts: X=0  Y=0",
            "        - Enciphered PIN verified online — If unattended cash (else apply next)",
            "        - Enciphered PIN verification performed by ICC — If terminal supports the CVM (else apply next)",
            "        - Plaintext PIN verification performed by ICC — If terminal supports the CVM (else apply next)",
            "        - Enciphered PIN verified online — If terminal supports the CVM (else apply next)",
            "        - Signature — If terminal supports the CVM (else fail)",
            "        - No CVM required — If terminal supports the CVM (else fail)",
        ).joinToString("\n")

    /** The raw 8E value octets, which must never surface as a value: the sub-lines replace them. */
    private val rawOctets = "000000000000000042014403410342031E031F03"

    @Test
    fun `a CVM List renders its amounts header and CV rule sub-lines`() {
        val outcome = runCli(arrayOf(CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the vector decodes")
        assertTrue(outcome.stdout.contains(cvmTree), "the CVM List renders its amounts and rules:\n${outcome.stdout}")
        assertFalse(outcome.stdout.contains(rawOctets), "a CVM List shows its rules, not its raw octets")
    }

    @Test
    fun `a CVM List renders its amounts and a rules array in JSON, and no value or hex`() {
        val outcome = runCli(arrayOf("--json", CliTestVectors.READ_RECORD)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "the vector decodes")
        assertTrue(outcome.stdout.contains(""""amountX": 0"""), "the CVM List emits amount X")
        assertTrue(outcome.stdout.contains(""""amountY": 0"""), "the CVM List emits amount Y")
        // The first rule (apply-next set) and the last (apply-next clear), pinned as JSON objects.
        val firstRule =
            """{"method": "Enciphered PIN verified online", "methodCode": 2, "applyNextIfFailed": true, """ +
                """"condition": "If unattended cash", "conditionCode": 1}"""
        val lastRule =
            """{"method": "No CVM required", "methodCode": 31, "applyNextIfFailed": false, """ +
                """"condition": "If terminal supports the CVM", "conditionCode": 3}"""
        assertTrue(outcome.stdout.contains(firstRule), "the first CV rule renders with its codes and names")
        assertTrue(outcome.stdout.contains(lastRule), "the last CV rule renders with apply-next clear")
        assertFalse(outcome.stdout.contains(rawOctets), "a CVM List node emits its rules, not a value or a hex field")
    }
}
