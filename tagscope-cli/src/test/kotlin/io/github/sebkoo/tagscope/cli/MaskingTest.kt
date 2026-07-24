package io.github.sebkoo.tagscope.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * No sensitive tag may appear in any default output; each must appear only when `--reveal` asks for
 * it. The PAN is pinned in depth against golden vector 3; the other sensitive tags — Track 1, the
 * cardholder name, the PIN, and the track-discretionary fields — are pinned as standalone fixtures.
 *
 * Golden vector 3 (the READ RECORD, `03-read-record.hex`) is the oracle. Its bytes are inlined here
 * because the library's test resources sit in another module and are not on this module's classpath;
 * the constant is grouped a row per line to stay auditable against the fixture. The PAN's decimal
 * digits and the hex of its value octets are the same string, `5570295626678085`, so one
 * "does not contain" check guards both the decoded value and the raw-hex leak vectors at once.
 *
 * The broad CLI smoke suite (arg parsing, stdin, exit codes, formatting) is a later commit; this
 * one pins the security-critical behaviour.
 */
class MaskingTest {
    private val pan = "5570295626678085"

    private val vector3 =
        "70818C9F420206435F25031603015F24032011305A085570295626678085" +
            "5F3401029F0702FF008C219F02069F03069F1A0295055F2A029A039C01" +
            "9F37049F35019F45029F4C089F34038D0C910A8A0295059F37049F4C08" +
            "8E14000000000000000042014403410342031E031F039F0D05BC50BC8800" +
            "9F0E0500000800009F0F05BC70BC98005F280206439F4A0182"

    @Test
    fun `the default text tree masks the PAN`() {
        val outcome = runCli(arrayOf(vector3)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a well-formed record decodes successfully")
        assertFalse(outcome.stdout.contains(pan), "the default tree must not print the PAN")
        assertTrue(outcome.stdout.contains("masked"), "the PAN node must show a masked marker")
    }

    @Test
    fun `the default JSON masks the PAN and withholds its hex`() {
        val outcome = runCli(arrayOf("--json", vector3)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a well-formed record decodes successfully")
        assertFalse(outcome.stdout.contains(pan), "the default JSON must not print the PAN as a value or as hex")
        assertTrue(outcome.stdout.contains("\"sensitive\": true"), "the PAN node must be flagged sensitive")
        assertFalse(outcome.stdout.contains("\"hex\": \"5570"), "the PAN node must not carry a hex field")
    }

    @Test
    fun `--reveal shows the PAN in the text tree`() {
        val outcome = runCli(arrayOf("--reveal", vector3)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a well-formed record decodes successfully")
        assertTrue(outcome.stdout.contains(pan), "--reveal must show the PAN in the tree")
    }

    @Test
    fun `--reveal shows the PAN in JSON, value and hex`() {
        val outcome = runCli(arrayOf("--json", "--reveal", vector3)) { "" }

        assertEquals(ExitCode.SUCCESS, outcome.exitCode, "a well-formed record decodes successfully")
        assertTrue(outcome.stdout.contains("\"value\": \"$pan\""), "--reveal must show the PAN as the value")
        assertTrue(outcome.stdout.contains("\"hex\": \"$pan\""), "--reveal must show the PAN's raw hex")
    }

    // The PAN is not the only masked tag. Track 1 (56), the cardholder name (5F20), the PIN (99),
    // and the two track-discretionary fields (9F1F, 9F20) all ride the same single masking site.
    // Each case below is a standalone, obviously FAKE synthetic TLV — no real track, PIN, or name
    // data ever enters the repository — whose raw value hex must be withheld from every default
    // output and shown only under --reveal. valueHex is the raw octets, deterministic whatever the
    // tag's format, so one string guards the decoded-value and raw-hex leak vectors at once.
    private data class SensitiveCase(
        val label: String,
        val tlv: String,
        val valueHex: String,
    )

    private val sensitiveCases =
        listOf(
            SensitiveCase("56 Track 1 Data", "5605FACE71DA7A", "FACE71DA7A"),
            SensitiveCase(
                "5F20 Cardholder Name",
                "5F200F544553542043415244484F4C444552",
                "544553542043415244484F4C444552",
            ),
            SensitiveCase("99 Transaction PIN Data", "9908DEADBEEFDEADBEEF", "DEADBEEFDEADBEEF"),
            SensitiveCase("9F1F Track 1 Discretionary Data", "9F1F04C0DEFACE", "C0DEFACE"),
            SensitiveCase("9F20 Track 2 Discretionary Data", "9F2003123456", "123456"),
        )

    @Test
    fun `every sensitive tag is masked in the default text tree`() {
        for (case in sensitiveCases) {
            val outcome = runCli(arrayOf(case.tlv)) { "" }

            assertEquals(ExitCode.SUCCESS, outcome.exitCode, "${case.label} decodes")
            assertFalse(
                outcome.stdout.contains(case.valueHex),
                "${case.label}: the default tree must not print its value",
            )
            assertTrue(outcome.stdout.contains("masked"), "${case.label}: the node must show a masked marker")
        }
    }

    @Test
    fun `every sensitive tag is withheld from default JSON, value and hex`() {
        for (case in sensitiveCases) {
            val outcome = runCli(arrayOf("--json", case.tlv)) { "" }

            assertEquals(ExitCode.SUCCESS, outcome.exitCode, "${case.label} decodes")
            assertFalse(
                outcome.stdout.contains(case.valueHex),
                "${case.label}: default JSON must not print its value or hex",
            )
            assertTrue(
                outcome.stdout.contains("\"sensitive\": true"),
                "${case.label}: the node must be flagged sensitive",
            )
        }
    }

    @Test
    fun `--reveal shows every sensitive tag's raw hex`() {
        for (case in sensitiveCases) {
            val outcome = runCli(arrayOf("--json", "--reveal", case.tlv)) { "" }

            assertEquals(ExitCode.SUCCESS, outcome.exitCode, "${case.label} decodes")
            assertTrue(
                outcome.stdout.contains("\"hex\": \"${case.valueHex}\""),
                "${case.label}: --reveal must show the raw hex",
            )
        }
    }

    @Test
    fun `a parse error never echoes sensitive bytes`() {
        // A truncated copy of vector 3: its outer 70 declares 140 value octets but the buffer is cut
        // short, so parsing fails — while the PAN octets are still present in the truncated input.
        val truncated = vector3.substring(0, 80)
        val outcome = runCli(arrayOf(truncated)) { "" }

        assertEquals(ExitCode.PARSE_ERROR, outcome.exitCode, "a truncated record is a parse error")
        assertTrue(outcome.stderr.startsWith("parse error at offset"), "the error names the offset")
        assertFalse(outcome.stderr.contains(pan), "a parse error must not echo the PAN")
        assertTrue(outcome.stdout.isEmpty(), "a parse error writes nothing to standard output")
    }

    // Regression: a PAN handed in as a stray second argument, or dressed as an option, once reached
    // stderr verbatim through the argument-parse error. The command line is card data too.
    @Test
    fun `a stray extra argument is not echoed`() {
        val outcome = runCli(arrayOf("00", pan)) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "a second positional is a usage error")
        assertFalse(outcome.stderr.contains(pan), "an extra-argument error must not echo the argument")
    }

    @Test
    fun `a value dressed as an option is not echoed`() {
        val outcome = runCli(arrayOf("-$pan")) { "" }

        assertEquals(ExitCode.USAGE_ERROR, outcome.exitCode, "a dash-prefixed token is an unknown option")
        assertFalse(outcome.stderr.contains(pan), "a dash-prefixed value must not be echoed as an option")
    }
}
