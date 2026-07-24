package io.github.sebkoo.tagscope.lint

import io.github.sebkoo.tagscope.decode.CvmCodes
import io.github.sebkoo.tagscope.decode.CvmMethodClass
import io.github.sebkoo.tagscope.decode.DecodeResult
import io.github.sebkoo.tagscope.decode.DecodedValue
import io.github.sebkoo.tagscope.decode.ValueDecoder
import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagLookup
import io.github.sebkoo.tagscope.tlv.TlvNode
import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * Every node in [nodes] paired with the tags from the root down to it, outermost first.
 *
 * `TlvNode.walk` gives the nodes depth-first but not their ancestry, and a finding wants the path.
 * This carries the path down as it recurses, so a rule can label a finding `6F › A5 › 88` without a
 * parent pointer on the node. Depth-first, self before children, the order a report reads in.
 */
internal fun walkWithPath(
    nodes: List<TlvNode>,
    prefix: List<TlvTag> = emptyList(),
): Sequence<Pair<TlvNode, List<TlvTag>>> =
    sequence {
        for (node in nodes) {
            val path = prefix + node.tag
            yield(node to path)
            yieldAll(walkWithPath(node.children, path))
        }
    }

/**
 * What [node]'s value decodes to, or `null` when the dictionary does not name the tag or the value
 * does not decode. A rule that cares only about a well-formed, known object uses this; the value may
 * be [DecodedValue.Sensitive] for a sensitive tag, which no rule here unwraps.
 */
internal fun decodedValue(node: TlvNode): DecodedValue? {
    val info = (TagDictionary.lookup(node.tag) as? TagLookup.Known)?.info ?: return null
    return (ValueDecoder.decode(node, info) as? DecodeResult.Success)?.value
}

/** The dividing name in a byte-field finding: Book 3 numbers bytes from one, this counts from zero. */
private fun byteLabel(byteIndex: Int): Int = byteIndex + 1

/**
 * A set Reserved-for-Future-Use bit in any bit-field object — AIP (`82`), TVR (`95`), the Issuer
 * Action Codes (`9F0D`/`9F0E`/`9F0F`, TVR-shaped), AUC (`9F07`) — is a **WARNING**.
 *
 * A set RFU bit is legal BER-TLV but off-spec: the position is reserved, so a card or terminal that
 * sets it is either using a newer spec version than the reader knows or has written a stray bit, and
 * either is worth an analyst's eye. The check reads [DecodedValue.BitField.SetFlag.isRfu] rather than
 * the meaning string, so it flags exactly the positions `ValueDecoder` synthesised as plain RFU and
 * leaves a position reserved with specific wording — "Reserved for use by the EMV Contactless
 * Specifications" — alone, that being a different kind of reserved. It is generic over every
 * bit-field tag the decoder knows, so a future one is covered without a change here. The TSI (`9B`)
 * is not decoded as a bit field yet, so it is out of this rule's reach until it is.
 *
 * EMV Book 3 v4.4, Annex C (C1 AIP, C2 AUC, C5 TVR) — the bit tables that mark each reserved position.
 */
internal object RfuBitsSet : Rule {
    internal const val ID: String = "rfu-bits"

    override fun check(tree: List<TlvNode>): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        for ((node, path) in walkWithPath(tree)) {
            val bitField = decodedValue(node) as? DecodedValue.BitField ?: continue
            for (flag in bitField.flags) {
                if (flag.isRfu) {
                    findings +=
                        LintFinding(
                            Severity.WARNING,
                            ID,
                            "RFU bit set in ${node.tag.hex}: byte ${byteLabel(flag.byteIndex)} bit " +
                                "b${flag.bit} is reserved for future use but is set",
                            path,
                        )
                }
            }
        }
        return findings
    }
}

/**
 * Anomalies in a Data Object List — PDOL (`9F38`), CDOL1 (`8C`), CDOL2 (`8D`).
 *
 * A DOL is a run of (tag, length) requests. Three things a cert analyst wants flagged: a **duplicate
 * tag** (the terminal would supply the same element twice — **WARNING**), a **zero-length entry**
 * (a request for no octets, almost always a mistake — **WARNING**), and an **unknown tag** (one the
 * dictionary does not name, worth noting in triage though not itself wrong — **INFO**). A DOL that
 * does not decode at all is left to the value decoder's own error; this rule reads the entries the
 * decoder already parsed.
 *
 * EMV Book 3 v4.4, §5.4, Rules for Using a Data Object List (DOL).
 */
internal object DolEntries : Rule {
    internal const val ID: String = "dol-entries"

    override fun check(tree: List<TlvNode>): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        for ((node, path) in walkWithPath(tree)) {
            val dol = decodedValue(node) as? DecodedValue.Dol ?: continue
            val seen = mutableSetOf<TlvTag>()
            for (entry in dol.entries) {
                val entryPath = path + entry.tag
                if (entry.length == 0) {
                    findings +=
                        LintFinding(
                            Severity.WARNING,
                            ID,
                            "DOL ${node.tag.hex} entry ${entry.tag.hex} requests zero octets",
                            entryPath,
                        )
                }
                if (!seen.add(entry.tag)) {
                    findings +=
                        LintFinding(
                            Severity.WARNING,
                            ID,
                            "DOL ${node.tag.hex} names tag ${entry.tag.hex} more than once",
                            entryPath,
                        )
                }
                if (TagDictionary.lookup(entry.tag) is TagLookup.Unknown) {
                    findings +=
                        LintFinding(
                            Severity.INFO,
                            ID,
                            "DOL ${node.tag.hex} names tag ${entry.tag.hex}, which the dictionary does not describe",
                            entryPath,
                        )
                }
            }
        }
        return findings
    }
}

/**
 * The CVM List (`8E`) is well-formed and names recognised methods.
 *
 * Two structural checks on the raw octets, because a malformed list does not decode into rules to
 * inspect: a list must be at least the eight octets its two amount thresholds take (**WARNING** if
 * shorter), and the CV Rules after them are two octets each, so an odd octet left over cannot
 * complete a rule (**WARNING**). Then, for a list that is structurally sound, each CV Rule's method
 * is classified by code range through [CvmCodes.classifyMethod]: a method outside the concretely
 * defined set — reserved for future use, for the payment systems, or for the issuer — is a
 * **WARNING**, since a terminal cannot be relied on to recognise it. This reads the list as data;
 * it performs no cardholder verification and processes no PIN.
 *
 * EMV Book 3 v4.4, §10.5 and Annex C3 (Table 43, CVM Codes).
 */
internal object CvmListWellFormed : Rule {
    internal const val ID: String = "cvm-well-formed"

    /** A CVM List's two four-octet amount thresholds, before any CV Rules. Book 3 §10.5. */
    private const val AMOUNTS_OCTETS: Int = 8

    /** One CV Rule: a CVM Code octet and a Condition octet. */
    private const val RULE_OCTETS: Int = 2

    private val CVM_LIST_TAG: TlvTag = TlvTag(value = 0x8E, octetLength = 1)

    override fun check(tree: List<TlvNode>): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        for ((node, path) in walkWithPath(tree)) {
            if (node.tag != CVM_LIST_TAG) continue
            val size = node.valueBytes().size
            when {
                size < AMOUNTS_OCTETS ->
                    findings +=
                        LintFinding(
                            Severity.WARNING,
                            ID,
                            "CVM List (8E) is $size octets, too short to hold its two amount thresholds",
                            path,
                        )
                (size - AMOUNTS_OCTETS) % RULE_OCTETS != 0 ->
                    findings +=
                        LintFinding(
                            Severity.WARNING,
                            ID,
                            "CVM List (8E) has a trailing octet that cannot complete a two-octet CV Rule",
                            path,
                        )
                else -> findings += methodFindings(node, path)
            }
        }
        return findings
    }

    /** A finding for each CV Rule whose method is not a concretely defined CVM. */
    private fun methodFindings(
        node: TlvNode,
        path: List<TlvTag>,
    ): List<LintFinding> {
        val cvmList = decodedValue(node) as? DecodedValue.CvmList ?: return emptyList()
        return cvmList.rules.mapNotNull { rule ->
            val reserved =
                when (CvmCodes.classifyMethod(rule.methodCode)) {
                    CvmMethodClass.DEFINED -> null
                    CvmMethodClass.RFU -> "reserved for future use"
                    CvmMethodClass.PAYMENT_SYSTEM -> "reserved for the payment systems"
                    CvmMethodClass.ISSUER -> "reserved for the issuer"
                }
            reserved?.let {
                val code =
                    rule.methodCode
                        .toString(radix = 16)
                        .uppercase()
                        .padStart(2, '0')
                LintFinding(
                    Severity.WARNING,
                    ID,
                    "CVM List (8E) names method 0x$code, which is $it, not a defined CVM",
                    path,
                )
            }
        }
    }
}

/**
 * An FCI carries the tags its role requires.
 *
 * Every FCI template (`6F`) must carry the DF Name (`84`) and the FCI Proprietary Template (`A5`);
 * either absent is an **ERROR**, the kind of missing mandatory element that fails a SELECT. For the
 * PPSE — the FCI whose DF Name is `2PAY.SYS.DDF01` — each directory entry (`61`) under
 * `A5 › BF0C` should carry an ADF Name (`4F`) naming the application it points at; a `61` without
 * one is a **WARNING**, a directory entry that points nowhere. `BF0C` and `61` are navigated by
 * their wire tags, so the rule needs no dictionary entry for them.
 *
 * EMV Book 1 v4.4, §11.3.4 (SELECT response, FCI) and §12.3.4 (PPSE directory).
 */
internal object FciMandatoryTags : Rule {
    internal const val ID: String = "fci-mandatory"

    private val FCI_TEMPLATE: TlvTag = TlvTag(value = 0x6F, octetLength = 1)
    private val DF_NAME: TlvTag = TlvTag(value = 0x84, octetLength = 1)
    private val FCI_PROPRIETARY: TlvTag = TlvTag(value = 0xA5, octetLength = 1)
    private val FCI_ISSUER_DISCRETIONARY: TlvTag = TlvTag(value = 0xBF0C, octetLength = 2)
    private val DIRECTORY_ENTRY: TlvTag = TlvTag(value = 0x61, octetLength = 1)
    private val ADF_NAME: TlvTag = TlvTag(value = 0x4F, octetLength = 1)

    /** The DF Name that marks a Proximity Payment System Environment FCI. Book 1 §12.3.2. */
    private val PPSE_NAME: ByteArray = "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII)

    override fun check(tree: List<TlvNode>): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        for ((node, path) in walkWithPath(tree)) {
            if (node.tag != FCI_TEMPLATE) continue
            val dfName = node.children.firstOrNull { it.tag == DF_NAME }
            val proprietary = node.children.firstOrNull { it.tag == FCI_PROPRIETARY }
            if (dfName == null) {
                findings += mandatory(node.tag, "DF Name (84)", path)
            }
            if (proprietary == null) {
                findings += mandatory(node.tag, "FCI Proprietary Template (A5)", path)
            }
            if (dfName != null && proprietary != null && dfName.valueBytes().contentEquals(PPSE_NAME)) {
                findings += ppseDirectory(proprietary, path)
            }
        }
        return findings
    }

    private fun mandatory(
        tag: TlvTag,
        missing: String,
        path: List<TlvTag>,
    ): LintFinding =
        LintFinding(
            Severity.ERROR,
            ID,
            "FCI template (${tag.hex}) is missing mandatory $missing",
            path,
        )

    /** For a PPSE, each `61` directory entry under `A5 › BF0C` should name an ADF (`4F`). */
    private fun ppseDirectory(
        proprietary: TlvNode,
        proprietaryParentPath: List<TlvTag>,
    ): List<LintFinding> {
        val discretionary =
            proprietary.children.firstOrNull { it.tag == FCI_ISSUER_DISCRETIONARY } ?: return emptyList()
        val basePath = proprietaryParentPath + proprietary.tag + discretionary.tag
        return discretionary.children
            .filter { it.tag == DIRECTORY_ENTRY }
            .filter { entry -> entry.children.none { it.tag == ADF_NAME } }
            .map { entry ->
                LintFinding(
                    Severity.WARNING,
                    ID,
                    "PPSE directory entry (61) carries no ADF Name (4F) to select an application",
                    basePath + entry.tag,
                )
            }
    }
}

/**
 * Any tag the dictionary does not describe is an **INFO** — a triage aid, not a defect.
 *
 * A tag Tagscope has no entry for is well-formed BER-TLV; it just is not one this build names. In a
 * certification triage that is worth surfacing — a proprietary tag, a newer element, or a misread —
 * so it is reported, at the lowest severity, and never gates a run.
 *
 * EMV Book 3 v4.4, Annex A (Data Elements Dictionary) — the reference the tag dictionary transcribes.
 */
internal object UnknownTag : Rule {
    internal const val ID: String = "unknown-tag"

    override fun check(tree: List<TlvNode>): List<LintFinding> {
        val findings = mutableListOf<LintFinding>()
        for ((node, path) in walkWithPath(tree)) {
            if (TagDictionary.lookup(node.tag) is TagLookup.Unknown) {
                findings +=
                    LintFinding(
                        Severity.INFO,
                        ID,
                        "tag ${node.tag.hex} is not in the dictionary",
                        path,
                    )
            }
        }
        return findings
    }
}
