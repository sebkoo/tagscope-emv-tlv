package io.github.sebkoo.tagscope.lint

import io.github.sebkoo.tagscope.tlv.TlvParser
import io.github.sebkoo.tagscope.tlv.TlvResult
import io.github.sebkoo.tagscope.vectors.loadVectorBytes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The consistency checker, driven by the same hand-verified fixtures as the decoder.
 *
 * Two halves, and the second is the point. The clean half runs the linter over the six golden
 * vectors and a well-formed PPSE, and asserts it raises nothing more severe than INFO — the
 * false-positive gate: a rule that fired on good data would be worse than useless. The broken half
 * runs it over vectors hand-crafted to trip each rule and asserts the exact error and warning
 * findings, byte-verified against the rule's cited EMV clause. INFO is incidental — an unknown tag
 * a broken vector happens to carry — so only ERROR and WARNING are pinned.
 *
 * The bytes are the oracle: every broken vector is well-formed BER-TLV that parses, and the
 * findings assert what the *rules* make of it, never a value it carries.
 */
class LintTest {
    @TestFactory
    fun `clean vectors raise no error or warning`(): List<DynamicNode> =
        CLEAN_VECTORS.map { file ->
            dynamicTest(file) {
                val severe = lint(file).filter { it.severity != Severity.INFO }
                assertTrue(severe.isEmpty()) { "clean vector $file should raise no error or warning, got: $severe" }
            }
        }

    @TestFactory
    fun `each broken vector raises exactly its error and warning findings`(): List<DynamicNode> =
        BROKEN_CASES.map { case ->
            dynamicTest(case.name) {
                val severe = lint(case.hexFile).filter { it.severity != Severity.INFO }
                assertEquals(
                    case.expected.size,
                    severe.size,
                ) { "unexpected findings for ${case.name}: $severe" }
                case.expected.forEachIndexed { index, expected ->
                    val actual = severe[index]
                    assertEquals(expected.severity, actual.severity) { "severity of finding #$index in ${case.name}" }
                    assertEquals(expected.ruleId, actual.ruleId) { "ruleId of finding #$index in ${case.name}" }
                    assertTrue(actual.message.contains(expected.messageContains)) {
                        "finding #$index message '${actual.message}' should contain '${expected.messageContains}'"
                    }
                }
            }
        }

    @Test
    fun `no finding on the PAN-bearing record carries the PAN`() {
        // Vector 3 (READ RECORD) carries a masked PAN (5A). It lints clean, but the guarantee is
        // structural: a finding names tags, never a value, so even a future rule cannot leak one.
        val findings = lint("03-read-record.hex")
        for (finding in findings) {
            assertTrue(!finding.message.contains(READ_RECORD_PAN)) {
                "a finding must never echo a sensitive value: ${finding.message}"
            }
        }
    }

    @Test
    fun `a linter built with one rule runs only that rule`() {
        val onlyUnknown = TlvLinter(listOf(UnknownTag))
        val parsed = TlvParser.parse(loadVectorBytes("lint/12-ppse-entry-missing-adf.hex"))
        val tree = (parsed as TlvResult.Success).value

        val findings = onlyUnknown.lint(tree)
        assertTrue(findings.isNotEmpty(), "the PPSE carries unknown tags BF0C and 61")
        assertTrue(findings.all { it.ruleId == UnknownTag.ID }, "only the one rule ran")
    }

    private fun lint(hexFile: String): List<LintFinding> =
        when (val parsed = TlvParser.parse(loadVectorBytes(hexFile))) {
            is TlvResult.Failure -> error("vector $hexFile did not parse: ${parsed.error}")
            is TlvResult.Success -> TlvLinter.DEFAULT.lint(parsed.value)
        }

    private companion object {
        /** The six golden decode vectors, plus a well-formed PPSE — all expected to lint clean. */
        private val CLEAN_VECTORS: List<String> =
            listOf(
                "01-pse-fci.hex",
                "02-visa-fci-pdol.hex",
                "03-read-record.hex",
                "04-generate-ac-fmt2.hex",
                "05-gpo-fmt1.hex",
                "06-gpo-fmt2.hex",
                "lint/13-ppse-fci.hex",
            )

        private val BROKEN_CASES: List<BrokenCase> =
            listOf(
                BrokenCase(
                    "FCI missing DF Name (84)",
                    "lint/07-fci-missing-df-name.hex",
                    listOf(Expected(Severity.ERROR, FciMandatoryTags.ID, "missing mandatory DF Name (84)")),
                ),
                BrokenCase(
                    "CVM List with an odd trailing octet",
                    "lint/08-cvm-odd-length.hex",
                    listOf(Expected(Severity.WARNING, CvmListWellFormed.ID, "trailing octet")),
                ),
                BrokenCase(
                    "CVM List naming a payment-system-reserved method",
                    "lint/09-cvm-reserved-method.hex",
                    listOf(Expected(Severity.WARNING, CvmListWellFormed.ID, "0x20")),
                ),
                BrokenCase(
                    "DOL with a duplicate tag and a zero-length entry",
                    "lint/10-dol-duplicate-zero-length.hex",
                    listOf(
                        Expected(Severity.WARNING, DolEntries.ID, "requests zero octets"),
                        Expected(Severity.WARNING, DolEntries.ID, "more than once"),
                    ),
                ),
                BrokenCase(
                    "AIP with an RFU bit set",
                    "lint/11-aip-rfu-bit.hex",
                    listOf(Expected(Severity.WARNING, RfuBitsSet.ID, "byte 2 bit b2")),
                ),
                BrokenCase(
                    "PPSE directory entry missing an ADF Name (4F)",
                    "lint/12-ppse-entry-missing-adf.hex",
                    listOf(Expected(Severity.WARNING, FciMandatoryTags.ID, "PPSE directory entry (61)")),
                ),
            )

        /** The PAN vector 3 carries; a finding must never contain it. Pinned by the decode masking tests. */
        private const val READ_RECORD_PAN: String = "5570295626678085"
    }
}

/** One negative-test case: a fixture and the exact error/warning findings it should raise, in order. */
internal class BrokenCase(
    val name: String,
    val hexFile: String,
    val expected: List<Expected>,
)

/** One expected finding: its severity, the rule that should raise it, and a phrase its message holds. */
internal data class Expected(
    val severity: Severity,
    val ruleId: String,
    val messageContains: String,
)
