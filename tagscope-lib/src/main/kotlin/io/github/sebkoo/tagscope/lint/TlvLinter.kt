package io.github.sebkoo.tagscope.lint

import io.github.sebkoo.tagscope.tlv.TlvNode

/**
 * The consistency rules Tagscope ships, in report order.
 *
 * The order is the order findings appear when every rule fires: the mandatory-tag ERROR first, then
 * the well-formedness WARNINGs (bit fields, DOLs, CVM Lists), then the unknown-tag INFO last. A
 * caller wanting a different set, or a single rule, builds its own [TlvLinter]; [ALL] is the default.
 */
public object DefaultRules {
    /** Every built-in rule, in the order [TlvLinter.DEFAULT] runs them. */
    public val ALL: List<Rule> =
        listOf(
            FciMandatoryTags,
            RfuBitsSet,
            DolEntries,
            CvmListWellFormed,
            UnknownTag,
        )
}

/**
 * Runs a fixed list of [Rule]s over a decoded BER-TLV tree and gathers their findings.
 *
 * A consistency checker layered on the parser: where `ValueDecoder` says what one object's octets
 * mean, a linter says whether the tree of objects is self-consistent and complete for its role —
 * the question a certification check asks. It is pure, the same discipline the parser and decoder
 * keep: it takes the parsed tree and returns findings, with no I/O and no state between calls, so a
 * caller (the CLI, a test, a CI step) drives the parse and the reporting.
 *
 * Construct one with a chosen rule list, or use [DEFAULT] for the rules Tagscope ships. Findings
 * come back in rule order — every finding of the first rule, then the next — which [DefaultRules]
 * orders to read most-severe first. A finding never carries a sensitive value; see [LintFinding].
 */
public class TlvLinter(
    private val rules: List<Rule>,
) {
    /** The findings every rule sees in [tree], concatenated in rule order. */
    public fun lint(tree: List<TlvNode>): List<LintFinding> = rules.flatMap { it.check(tree) }

    public companion object {
        /** A linter running every rule in [DefaultRules.ALL]. */
        public val DEFAULT: TlvLinter = TlvLinter(DefaultRules.ALL)
    }
}
