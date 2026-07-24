package io.github.sebkoo.tagscope.lint

import io.github.sebkoo.tagscope.tlv.TlvNode
import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * How much a [LintFinding] matters.
 *
 * [ERROR] is a violation that makes the data object invalid for its role — a mandatory tag absent
 * from a template — the kind of thing that fails a certification check, and the reason `tagscope
 * lint` returns a non-zero exit. [WARNING] is a well-formed object that is off-spec or suspicious:
 * a reserved bit set, a CVM List with a reserved method, a DOL that repeats a tag. [INFO] is a
 * neutral observation an analyst may want during cert triage, such as a tag the dictionary does not
 * name — not a defect on its own.
 *
 * Ordered most severe first, so findings sort by `ordinal` into the order a report reads best.
 */
public enum class Severity {
    ERROR,
    WARNING,
    INFO,
}

/**
 * One thing a [Rule] observed about a decoded tag tree.
 *
 * A finding names *where* ([tagPath]), *what rule* saw it ([ruleId]), *how much it matters*
 * ([severity]) and *what it is* ([message]) — never the value that carries a defect. The linter
 * exists to inspect EMV data that includes cardholder data, so a finding is built to be safe to
 * print: [message] describes structure ("CVM List length 0x13 is odd", "FCI template is missing
 * tag 84") and never embeds a value octet, and [tagPath] is a path of tags, which are not
 * themselves sensitive. A finding therefore cannot become the leak the single-masking-site design
 * guards against elsewhere.
 *
 * @property severity how much this finding matters; see [Severity].
 * @property ruleId the stable, kebab-case id of the [Rule] that raised it, e.g. `"fci-mandatory"`.
 *   Stable so a script can grep or suppress by rule.
 * @property message a one-line description of the finding, in structural terms only — never a value.
 * @property tagPath the tags from the tree root to the object the finding is about, outermost
 *   first, e.g. `6F › A5 › BF0C › 61`. Empty only for a finding about the tree as a whole.
 */
public data class LintFinding(
    public val severity: Severity,
    public val ruleId: String,
    public val message: String,
    public val tagPath: List<TlvTag>,
)

/**
 * One consistency check over a decoded BER-TLV tree.
 *
 * A rule is a pure function of the parsed tree: it is handed the forest [TlvParser] produced, does
 * its own dictionary lookups and value decoding as it needs them, and returns the findings it saw,
 * in tree order. It performs no I/O, holds no state between calls, and never reveals a sensitive
 * value — a rule reports *that* a tag is wrong, not what it holds. [TlvLinter] runs an ordered list
 * of rules and concatenates their findings.
 *
 * A `fun interface`, so a one-off check can be written as a lambda; the built-in rules are objects
 * in [DefaultRules].
 */
public fun interface Rule {
    /** The findings this rule sees in [tree], in tree order. Empty when the rule is satisfied. */
    public fun check(tree: List<TlvNode>): List<LintFinding>
}
