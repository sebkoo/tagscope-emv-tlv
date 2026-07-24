package io.github.sebkoo.tagscope.cli

import io.github.sebkoo.tagscope.decode.CvmCodes
import io.github.sebkoo.tagscope.decode.DecodeError
import io.github.sebkoo.tagscope.decode.DecodeResult
import io.github.sebkoo.tagscope.decode.DecodedValue
import io.github.sebkoo.tagscope.decode.ValueDecoder
import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagLookup
import io.github.sebkoo.tagscope.tlv.TagClass
import io.github.sebkoo.tagscope.tlv.TlvNode

/**
 * A node reduced to exactly what a renderer may print — and nothing it may not.
 *
 * This type, and the [describe] that builds it, are the one place cardholder data is handled.
 * [describe] is the only code in the CLI that calls [TlvNode.valueBytes] or
 * [DecodedValue.Sensitive.reveal]; by the time a [RenderNode] exists, masking has already been
 * applied, so neither the tree renderer nor the JSON writer can print an unmasked PAN even by
 * mistake. A leak would have to be introduced here, in front of the single masking decision, rather
 * than anywhere downstream a value happens to be formatted.
 *
 * @property sensitive the tag is cardholder data (the dictionary marks it so — `5A`, `57`).
 * @property masked sensitive *and* not being revealed, so [value] and [valueHex] were withheld.
 * @property value the human rendering of the value, or `null` for a constructed node or a masked one.
 * @property valueHex the raw value octets as hex, or `null` when withheld (masked) or constructed.
 * @property meanings decoded bit-field meanings, one per entry; empty for everything else.
 * @property dolEntries a Data Object List's (tag, name, length) requests, or `null` for a non-DOL.
 * @property cvm a CVM List's amounts and CV Rules, or `null` for a non-CVM-List.
 * @property decodeNote why a non-sensitive value would not decode, or `null`.
 */
internal class RenderNode(
    val tagHex: String,
    val name: String,
    val tagClass: TagClass,
    val constructed: Boolean,
    val length: Int,
    val sensitive: Boolean,
    val masked: Boolean,
    val value: String?,
    val valueHex: String?,
    val meanings: List<String>,
    val dolEntries: List<DolEntryView>?,
    val cvm: CvmListView?,
    val decodeNote: String?,
    val children: List<RenderNode>,
)

/**
 * A Data Object List entry ready to render: the tag as hex, the dictionary name (looked up here at
 * render time, never stored in the decoded value), and how many octets the terminal must supply.
 */
internal class DolEntryView(
    val tagHex: String,
    val name: String,
    val length: Int,
)

/**
 * A Cardholder Verification Method List ready to render: its two amount thresholds and its CV Rules,
 * the method and condition names resolved here at render time via `CvmCodes` — never stored on the
 * decoded value, the same way a DOL entry's name is looked up here.
 */
internal class CvmListView(
    val amountX: Long,
    val amountY: Long,
    val rules: List<CvmRuleView>,
)

/**
 * One CV Rule ready to render: the resolved method and condition names, the codes they came from,
 * and whether an unsuccessful CVM applies the next rule (true) or fails cardholder verification.
 */
internal class CvmRuleView(
    val method: String,
    val methodCode: Int,
    val applyNextIfFailed: Boolean,
    val condition: String,
    val conditionCode: Int,
)

/** Shown in a value column in place of a masked value, in the text tree. */
internal const val MASK_TREE: String = "••• masked (use --reveal)"

/** Shown as a masked value in JSON, where a plain ASCII token reads better for a consumer. */
internal const val MASK_JSON: String = "****"

/** The name given to a tag the dictionary does not carry. */
internal const val UNKNOWN_TAG_NAME: String = "Unknown"

/** Reduces a parsed forest to render nodes, applying masking once, here. */
internal fun describe(
    nodes: List<TlvNode>,
    reveal: Boolean,
): List<RenderNode> = nodes.map { describeNode(it, reveal) }

private fun describeNode(
    node: TlvNode,
    reveal: Boolean,
): RenderNode {
    val info = (TagDictionary.lookup(node.tag) as? TagLookup.Known)?.info
    val name = info?.name ?: UNKNOWN_TAG_NAME
    val sensitive = info?.isSensitive == true

    // A constructed node carries its children, not a scalar. Bit 6 of the tag decides this, exactly
    // as the parser decided whether to recurse — never the dictionary's format column.
    if (node.tag.isConstructed) {
        val children = node.children.map { describeNode(it, reveal) }
        return leafless(node, name, sensitive, constructed = true, children = children)
    }

    // A sensitive primitive that is not being revealed: withhold everything derived from its octets.
    // Keyed on the dictionary's isSensitive, not on the decode result — a `5A` whose octets fail to
    // decode is still a PAN, and keying on DecodedValue.Sensitive would let that one slip through as
    // its raw hex.
    if (sensitive && !reveal) {
        return leafless(node, name, sensitive = true, constructed = false, masked = true)
    }

    val rawHex = hexOf(node.valueBytes())

    // An unknown tag has no dictionary entry to decode against, so show the octets and no more.
    if (info == null) {
        return primitive(node, name, sensitive = false, value = rawHex, valueHex = rawHex)
    }

    return when (val result = ValueDecoder.decode(node, info)) {
        is DecodeResult.Success -> {
            // A sensitive tag reached here only with reveal on; unwrap it deliberately. Bound to a
            // local first: a cross-module property cannot be smart-cast after the `is` check.
            val raw = result.value
            val decoded = if (raw is DecodedValue.Sensitive) raw.reveal() else raw
            renderPrimitive(node, name, sensitive, decoded, rawHex)
        }
        // A well-formed object whose octets do not match its format: show the octets and say why.
        // The reason carries no digits (the library keeps sensitive DecodeErrors data-free), so it
        // is safe to print even here.
        is DecodeResult.Failure ->
            primitive(node, name, sensitive, value = rawHex, valueHex = rawHex, decodeNote = decodeNote(result.error))
    }
}

private fun renderPrimitive(
    node: TlvNode,
    name: String,
    sensitive: Boolean,
    decoded: DecodedValue,
    rawHex: String,
): RenderNode {
    var meanings: List<String> = emptyList()
    var dolEntries: List<DolEntryView>? = null
    var cvm: CvmListView? = null
    val value: String? =
        when (decoded) {
            is DecodedValue.Digits -> decoded.digits
            is DecodedValue.Text -> decoded.text
            is DecodedValue.Date -> "${pad2(decoded.yearOfCentury)}-${pad2(decoded.month)}-${pad2(decoded.day)}"
            is DecodedValue.Amount -> decoded.minorUnits.toString()
            is DecodedValue.RawBinary -> rawHex
            is DecodedValue.BitField -> {
                meanings = decoded.selections.map { "${it.label}: ${it.meaning}" } + decoded.flags.map { it.meaning }
                rawHex
            }
            is DecodedValue.Dol -> {
                // A DOL is a list of requests, not a value: its entries render as sub-lines, and the
                // value column is left empty. Names are looked up here, at render time, not stored.
                dolEntries = decoded.entries.map(::dolEntryView)
                null
            }
            is DecodedValue.Track2 -> track2(decoded)
            is DecodedValue.CvmList -> {
                // A CVM List renders as its amounts and CV Rules, like a DOL renders as its entries:
                // sub-lines beneath the node, the value column left empty. The method and condition
                // names are resolved here, at render time, from the codes — never stored on the value.
                cvm = cvmListView(decoded)
                null
            }
            // Unreachable for a primitive: Constructed is handled above and Sensitive was unwrapped.
            // Fall back to something that reveals nothing, so an invariant slip cannot leak.
            is DecodedValue.Constructed -> rawHex
            is DecodedValue.Sensitive -> MASK_TREE
        }
    return primitive(
        node,
        name,
        sensitive,
        value = value,
        // A DOL and a CVM List carry no scalar value to show: they emit their sub-lines in place of a
        // hex value, so the value column and the hex field are both left empty.
        valueHex = if (dolEntries != null || cvm != null) null else rawHex,
        meanings = meanings,
        dolEntries = dolEntries,
        cvm = cvm,
    )
}

private fun dolEntryView(entry: DecodedValue.Dol.Entry): DolEntryView {
    val info = (TagDictionary.lookup(entry.tag) as? TagLookup.Known)?.info
    return DolEntryView(entry.tag.hex, info?.name ?: UNKNOWN_TAG_NAME, entry.length)
}

private fun cvmListView(value: DecodedValue.CvmList): CvmListView =
    CvmListView(
        amountX = value.amountX,
        amountY = value.amountY,
        rules =
            value.rules.map { rule ->
                CvmRuleView(
                    method = CvmCodes.method(rule.methodCode),
                    methodCode = rule.methodCode,
                    applyNextIfFailed = rule.applyNextIfFailed,
                    condition = CvmCodes.condition(rule.conditionCode),
                    conditionCode = rule.conditionCode,
                )
            },
    )

private fun track2(value: DecodedValue.Track2): String =
    buildString {
        append("PAN=").append(value.pan)
        append(" exp=").append(pad2(value.expiry.yearOfCentury)).append('-').append(pad2(value.expiry.month))
        append(" svc=").append(value.serviceCode)
        if (value.discretionaryData.isNotEmpty()) {
            append(" disc=").append(value.discretionaryData)
        }
    }

private fun decodeNote(error: DecodeError): String =
    when (error) {
        is DecodeError.NotBcd -> "not BCD"
        is DecodeError.MisplacedPadding -> "misplaced padding"
        is DecodeError.UnexpectedCharacter -> "unexpected character"
        is DecodeError.UnexpectedValueLength ->
            "unexpected length: ${error.actualOctets} octets, expected ${error.expectedOctets}"
        is DecodeError.MonthOutOfRange -> "month out of range"
        is DecodeError.DayOutOfRange -> "day out of range"
        is DecodeError.Track2NoSeparator -> "no Track 2 separator"
        is DecodeError.Track2MultipleSeparators -> "multiple Track 2 separators"
        is DecodeError.Track2PanTooLong -> "Track 2 PAN too long"
        is DecodeError.Track2MissingFields -> "Track 2 missing fields"
        is DecodeError.Track2MonthOutOfRange -> "Track 2 month out of range"
        is DecodeError.MalformedDol -> "malformed DOL entry"
        is DecodeError.MalformedCvmList -> "malformed CVM List"
    }

private fun leafless(
    node: TlvNode,
    name: String,
    sensitive: Boolean,
    constructed: Boolean,
    masked: Boolean = false,
    children: List<RenderNode> = emptyList(),
): RenderNode =
    RenderNode(
        tagHex = node.tag.hex,
        name = name,
        tagClass = node.tag.tagClass,
        constructed = constructed,
        length = node.length.value,
        sensitive = sensitive,
        masked = masked,
        value = null,
        valueHex = null,
        meanings = emptyList(),
        dolEntries = null,
        cvm = null,
        decodeNote = null,
        children = children,
    )

private fun primitive(
    node: TlvNode,
    name: String,
    sensitive: Boolean,
    value: String?,
    valueHex: String?,
    meanings: List<String> = emptyList(),
    dolEntries: List<DolEntryView>? = null,
    cvm: CvmListView? = null,
    decodeNote: String? = null,
): RenderNode =
    RenderNode(
        tagHex = node.tag.hex,
        name = name,
        tagClass = node.tag.tagClass,
        constructed = false,
        length = node.length.value,
        sensitive = sensitive,
        masked = false,
        value = value,
        valueHex = valueHex,
        meanings = meanings,
        dolEntries = dolEntries,
        cvm = cvm,
        decodeNote = decodeNote,
        children = emptyList(),
    )

private fun pad2(value: Int): String = if (value in 0..9) "0$value" else value.toString()

private fun hexOf(bytes: ByteArray): String =
    buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

private const val HEX_DIGITS: String = "0123456789ABCDEF"
