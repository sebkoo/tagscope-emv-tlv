package io.github.sebkoo.tagscope.vectors

import io.github.sebkoo.tagscope.tlv.hex

/*
 * Golden-vector fixtures: six byte-verified EMV data objects, each asserted on three layers —
 * structure, decoded values and round-trip — by GoldenVectorTest.
 *
 * The raw octets live in `src/test/resources/vectors/` as `.hex` files (test-only, so the JAR
 * carries none of them, the synthetic PAN included); the expected trees and decoded values are the
 * typed [VECTORS] table, written by hand from the bytes and checked by the compiler against the
 * real decoder API — the same reason `TagDictionary` is a Kotlin table and not a resource. The
 * bytes are the oracle: when a layer disagrees, the expectation here is wrong, never the fixture.
 *
 * Every vector is a published EMV / OpenSCDP specification sample. None of it is live cardholder
 * data; the one PAN present (Vector 3) is a synthetic spec value, masked by default and read in
 * full only by the single test that names `reveal()`.
 */

/** Anchors [loadVectorBytes] to this module's classloader for `getResourceAsStream`. */
private class VectorResourceAnchor

/**
 * Reads `vectors/[fileName]` from the test classpath into octets, dropping `#` comments and every
 * whitespace character before handing the compact hex to `hex`. The first resource reader in the
 * project — `TlvParser` and the decoders take bytes a caller supplies and never touch the classpath.
 */
internal fun loadVectorBytes(fileName: String): ByteArray {
    val path = "/vectors/$fileName"
    val stream =
        requireNotNull(VectorResourceAnchor::class.java.getResourceAsStream(path)) {
            "missing golden-vector resource: $path"
        }
    val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
    val compact =
        text
            .lineSequence()
            .map { it.substringBefore('#') }
            .joinToString(separator = "")
            .filterNot { it.isWhitespace() }
    return hex(compact)
}

/** One golden vector: a name, its fixture file, provenance, expected nesting depth and tree. */
internal class GoldenVector(
    val name: String,
    val hexFile: String,
    val provenance: String,
    val depth: Int,
    val tree: ExpectedNode,
)

/**
 * One expected node in a vector's tree.
 *
 * [constructed] is stated by choosing [tlv] over [leaf], so it is an independent claim rather than
 * a re-reading of the tag's bit 6. [value], when present, is a leaf's expected value octets as
 * compact hex; a constructed node leaves it null and is pinned by its [children] instead, and an
 * opaque leaf may leave it null when the round-trip layer already guarantees its octets.
 */
internal class ExpectedNode(
    val tag: String,
    val length: Int,
    val constructed: Boolean,
    val value: String?,
    val children: List<ExpectedNode>,
    val decoded: ExpectedDecode,
)

/** A constructed node: bit 6 set, children recurse, value decodes to [ExpectedDecode.Constructed]. */
internal fun tlv(
    tag: String,
    length: Int,
    vararg children: ExpectedNode,
): ExpectedNode =
    ExpectedNode(
        tag = tag,
        length = length,
        constructed = true,
        value = null,
        children = children.toList(),
        decoded = ExpectedDecode.Constructed,
    )

/** A primitive node: bit 6 clear, no children, [value] its opaque octets (null to skip the byte check). */
internal fun leaf(
    tag: String,
    length: Int,
    decoded: ExpectedDecode,
    value: String? = null,
): ExpectedNode =
    ExpectedNode(
        tag = tag,
        length = length,
        constructed = false,
        value = value,
        children = emptyList(),
        decoded = decoded,
    )

/** What a node's value octets are expected to decode to, mirroring `DecodedValue`. */
internal sealed interface ExpectedDecode {
    /** The value is other data objects; `DecodedValue.Constructed`. */
    data object Constructed : ExpectedDecode

    /** `DecodedValue.RawBinary` whose octets equal [hex]. */
    data class Raw(
        val hex: String,
    ) : ExpectedDecode

    /** `DecodedValue.RawBinary` of [size] octets, opaque — equal to the node's own value bytes. */
    data class RawOpaque(
        val size: Int,
    ) : ExpectedDecode

    /** `DecodedValue.Text([text])`, with no padding stripped. */
    data class Text(
        val text: String,
    ) : ExpectedDecode

    /** `DecodedValue.Digits([digits])`. */
    data class Digits(
        val digits: String,
    ) : ExpectedDecode

    /** `DecodedValue.Date([yy], [mm], [dd])` — two-digit year, no century. */
    data class Date(
        val yy: Int,
        val mm: Int,
        val dd: Int,
    ) : ExpectedDecode

    /** `DecodedValue.BitField` with exactly these set [flags] and enum [selections]. */
    data class Bits(
        val flags: List<Flag> = emptyList(),
        val selections: List<Selection> = emptyList(),
    ) : ExpectedDecode

    /** `DecodedValue.Dol` with exactly these (tag, length) entries, in wire order. */
    data class Dol(
        val entries: List<Entry>,
    ) : ExpectedDecode {
        /** One expected DOL entry: the tag as hex and the octet count it requests. */
        data class Entry(
            val tag: String,
            val length: Int,
        )
    }

    /** `DecodedValue.CvmList` with these two amounts and exactly these CV Rules, in wire order. */
    data class CvmList(
        val amountX: Long,
        val amountY: Long,
        val rules: List<Rule>,
    ) : ExpectedDecode {
        /** One expected CV Rule: the method code (`b6..b1`), the apply-next flag, the condition code. */
        data class Rule(
            val methodCode: Int,
            val applyNextIfFailed: Boolean,
            val conditionCode: Int,
        )
    }

    /** `DecodedValue.Sensitive`; the default decode redacts and leaks nothing. */
    data object Sensitive : ExpectedDecode

    /** The tag is absent from the dictionary; `TagDictionary.lookup` is `Unknown`, so no decode runs. */
    data object Unknown : ExpectedDecode

    /** One expected set bit: octet [byteIndex] (from 0), EMV bit [bit] (b8 = MSB), its [meaning]. */
    data class Flag(
        val byteIndex: Int,
        val bit: Int,
        val meaning: String,
    )

    /** One expected enum field: octet [byteIndex], its [label], the selected [value], its [meaning]. */
    data class Selection(
        val byteIndex: Int,
        val label: String,
        val value: Int,
        val meaning: String,
    )
}

/** `DecodedValue.RawBinary` expected to equal [hex]. */
internal fun raw(hex: String): ExpectedDecode = ExpectedDecode.Raw(hex)

/** Opaque `DecodedValue.RawBinary` of [size] octets, never interpreted. */
internal fun rawOpaque(size: Int): ExpectedDecode = ExpectedDecode.RawOpaque(size)

/** `DecodedValue.Text([text])`. */
internal fun text(text: String): ExpectedDecode = ExpectedDecode.Text(text)

/** `DecodedValue.Dol` with these (tag hex, length) entries, in wire order. */
internal fun dol(vararg entries: Pair<String, Int>): ExpectedDecode =
    ExpectedDecode.Dol(entries.map { (tag, length) -> ExpectedDecode.Dol.Entry(tag, length) })

/** `DecodedValue.CvmList` with amounts [amountX]/[amountY] and these CV [rules], in wire order. */
internal fun cvmList(
    amountX: Long,
    amountY: Long,
    vararg rules: ExpectedDecode.CvmList.Rule,
): ExpectedDecode = ExpectedDecode.CvmList(amountX, amountY, rules.toList())

/** One expected CV Rule: method code (`b6..b1`), apply-next flag, condition code. */
internal fun rule(
    methodCode: Int,
    applyNextIfFailed: Boolean,
    conditionCode: Int,
): ExpectedDecode.CvmList.Rule = ExpectedDecode.CvmList.Rule(methodCode, applyNextIfFailed, conditionCode)

/** `DecodedValue.Digits([digits])`. */
internal fun digits(digits: String): ExpectedDecode = ExpectedDecode.Digits(digits)

/** `DecodedValue.Date([yy], [mm], [dd])`. */
internal fun date(
    yy: Int,
    mm: Int,
    dd: Int,
): ExpectedDecode = ExpectedDecode.Date(yy, mm, dd)

/** `DecodedValue.BitField` with these set [flags] and enum [selections]. */
internal fun bits(
    flags: List<ExpectedDecode.Flag> = emptyList(),
    selections: List<ExpectedDecode.Selection> = emptyList(),
): ExpectedDecode = ExpectedDecode.Bits(flags, selections)

/** One expected set bit. */
internal fun flag(
    byteIndex: Int,
    bit: Int,
    meaning: String,
): ExpectedDecode.Flag = ExpectedDecode.Flag(byteIndex, bit, meaning)

/** One expected enum selection. */
internal fun sel(
    byteIndex: Int,
    label: String,
    value: Int,
    meaning: String,
): ExpectedDecode.Selection = ExpectedDecode.Selection(byteIndex, label, value, meaning)
