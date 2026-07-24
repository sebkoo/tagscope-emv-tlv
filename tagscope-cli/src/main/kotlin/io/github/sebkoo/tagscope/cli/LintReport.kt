package io.github.sebkoo.tagscope.cli

import io.github.sebkoo.tagscope.lint.LintFinding
import io.github.sebkoo.tagscope.lint.Severity
import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * Renders lint findings for the console: a one-line summary, then the findings aligned into
 * columns, ordered most severe first. A clean tree prints `no findings`.
 *
 * Nothing here reaches for a value. A [LintFinding] carries a tag path and a structural message and
 * no value octets — by construction, in the library — so this renderer cannot print a PAN or track
 * even if it tried, and it does not need the masking [RenderModel] applies on the decode path. The
 * columns are severity, rule id, the tag path, then the message, the same aligned-column shape
 * [renderTree] uses so the two outputs read alike.
 */
internal fun renderFindings(findings: List<LintFinding>): String {
    if (findings.isEmpty()) {
        return "no findings"
    }

    // Stable sort by severity keeps each rule's findings in the order the linter emitted them.
    val ordered = findings.sortedBy { it.severity.ordinal }
    val rows = ordered.map { Cells(it.severity.name, it.ruleId, pathString(it.tagPath), it.message) }

    val severityWidth = rows.maxOf { it.severity.length }
    val ruleWidth = rows.maxOf { it.rule.length }
    val pathWidth = rows.maxOf { it.path.length }

    val body =
        rows.joinToString(separator = "\n") { row ->
            buildString {
                append(row.severity.padEnd(severityWidth))
                append(GAP).append(row.rule.padEnd(ruleWidth))
                append(GAP).append(row.path.padEnd(pathWidth))
                append(GAP).append(row.message)
            }.trimEnd()
        }

    return summaryLine(ordered) + "\n\n" + body
}

/** `N findings: A errors, B warnings, C info`, dropping any count that is zero. */
private fun summaryLine(findings: List<LintFinding>): String {
    val counts = Severity.entries.associateWith { severity -> findings.count { it.severity == severity } }
    val parts =
        counts
            .filterValues { it > 0 }
            .map { (severity, count) -> "$count ${label(severity, count)}" }
    val total = findings.size
    return "$total ${if (total == 1) "finding" else "findings"}: " + parts.joinToString()
}

private fun label(
    severity: Severity,
    count: Int,
): String =
    when (severity) {
        Severity.ERROR -> if (count == 1) "error" else "errors"
        Severity.WARNING -> if (count == 1) "warning" else "warnings"
        Severity.INFO -> "info"
    }

/** A tag path as `6F › A5 › 88`, or `-` for the rare finding about the tree as a whole. */
private fun pathString(path: List<TlvTag>): String =
    if (path.isEmpty()) "-" else path.joinToString(separator = " › ") { it.hex }

/** The rendered cells of one finding, before padding. */
private data class Cells(
    val severity: String,
    val rule: String,
    val path: String,
    val message: String,
)

private const val GAP: String = "  "
