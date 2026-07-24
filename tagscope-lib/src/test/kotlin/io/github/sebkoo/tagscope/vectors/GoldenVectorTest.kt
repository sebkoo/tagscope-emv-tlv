package io.github.sebkoo.tagscope.vectors

import io.github.sebkoo.tagscope.decode.DecodedValue
import io.github.sebkoo.tagscope.decode.ValueDecoder
import io.github.sebkoo.tagscope.decode.expectValue
import io.github.sebkoo.tagscope.decode.infoFor
import io.github.sebkoo.tagscope.decode.revealed
import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagLookup
import io.github.sebkoo.tagscope.tlv.TlvEncoder
import io.github.sebkoo.tagscope.tlv.TlvNode
import io.github.sebkoo.tagscope.tlv.TlvParser
import io.github.sebkoo.tagscope.tlv.expectSuccess
import io.github.sebkoo.tagscope.tlv.hex
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * Golden vectors: six published EMV data objects, each asserted on three layers. The fixture bytes
 * are the oracle (CLAUDE.md) — a failing layer means the code or the [VECTORS] expectation is
 * wrong, and the `.hex` file is never edited to make a test pass.
 *
 * (A) structure — tag, length, primitive-vs-constructed and nesting, re-derived from the bytes.
 * (B) decoded values — via the real dictionary and `ValueDecoder`, asserting the exact
 *     `DecodedValue` each tag returns.
 * (C) round-trip — `encode(parse(bytes)) == bytes`, byte for byte.
 *
 * None of the fixtures is live cardholder data. The single PAN (Vector 3) is masked by default and
 * read in full only by [`Vector 3 masks the PAN by default and reveals it only on request`].
 */
class GoldenVectorTest {
    @TestFactory
    fun `each golden vector holds its structure, decoded values and round-trip`(): List<DynamicNode> =
        VECTORS.map { vector ->
            dynamicContainer(
                vector.name,
                listOf(
                    dynamicTest("structure") { assertStructureLayer(vector) },
                    dynamicTest("decoded values") { assertDecodedLayer(vector) },
                    dynamicTest("round-trip") { assertRoundTrip(vector) },
                ),
            )
        }

    // (A) STRUCTURE

    private fun assertStructureLayer(vector: GoldenVector) {
        val roots = TlvParser.parse(loadVectorBytes(vector.hexFile)).expectSuccess()
        assertEquals(1, roots.size, "${vector.name}: exactly one top-level data object")
        val root = roots.single()
        assertStructure(vector.tree, root)
        assertEquals(vector.depth, depthOf(root), "${vector.name}: nesting depth")
    }

    private fun assertStructure(
        expected: ExpectedNode,
        actual: TlvNode,
    ) {
        val where = expected.tag
        assertEquals(expected.tag, actual.tag.hex, "tag")
        assertEquals(expected.constructed, actual.tag.isConstructed, "$where constructed")
        assertEquals(expected.length, actual.length.value, "$where length")
        assertEquals(expected.children.size, actual.children.size, "$where child count")
        if (!expected.constructed) {
            assertTrue(actual.children.isEmpty(), "$where is primitive and must not recurse")
        }
        expected.value?.let { assertArrayEquals(hex(it), actual.valueBytes(), "$where value octets") }
        expected.children.zip(actual.children).forEach { (child, node) -> assertStructure(child, node) }
    }

    private fun depthOf(node: TlvNode): Int = 1 + (node.children.maxOfOrNull { depthOf(it) } ?: 0)

    // (B) DECODED VALUES

    private fun assertDecodedLayer(vector: GoldenVector) {
        val root = TlvParser.parse(loadVectorBytes(vector.hexFile)).expectSuccess().single()
        assertDecoded(vector.tree, root)
    }

    private fun assertDecoded(
        expected: ExpectedNode,
        actual: TlvNode,
    ) {
        assertNodeDecodesTo(expected.decoded, actual)
        expected.children.zip(actual.children).forEach { (child, node) -> assertDecoded(child, node) }
    }

    private fun assertNodeDecodesTo(
        expected: ExpectedDecode,
        node: TlvNode,
    ) {
        val where = node.tag.hex
        if (expected is ExpectedDecode.Unknown) {
            assertTrue(
                TagDictionary.lookup(node.tag) is TagLookup.Unknown,
                "$where should be unknown to the dictionary",
            )
            return
        }
        val value = ValueDecoder.decode(node, infoFor(node)).expectValue()
        when (expected) {
            ExpectedDecode.Constructed -> assertEquals(DecodedValue.Constructed, value, where)
            is ExpectedDecode.Raw ->
                assertArrayEquals(hex(expected.hex), rawOctets(value, where), "$where raw octets")
            is ExpectedDecode.RawOpaque -> {
                val raw = value as? DecodedValue.RawBinary ?: fail("$where expected RawBinary, got $value")
                assertEquals(expected.size, raw.size, "$where opaque size")
                assertArrayEquals(node.valueBytes(), raw.bytes(), "$where opaque passthrough")
            }
            is ExpectedDecode.Text -> assertEquals(DecodedValue.Text(expected.text), value, "$where text")
            is ExpectedDecode.Digits -> assertEquals(DecodedValue.Digits(expected.digits), value, "$where digits")
            is ExpectedDecode.Date ->
                assertEquals(DecodedValue.Date(expected.yy, expected.mm, expected.dd), value, "$where date")
            is ExpectedDecode.Bits -> assertBitField(expected, node, value, where)
            is ExpectedDecode.Dol -> {
                val dol = value as? DecodedValue.Dol ?: fail("$where expected Dol, got $value")
                assertEquals(
                    expected.entries.map { it.tag to it.length },
                    dol.entries.map { it.tag.hex to it.length },
                    "$where DOL entries",
                )
            }
            ExpectedDecode.Sensitive -> {
                assertTrue(value is DecodedValue.Sensitive, "$where expected Sensitive, got $value")
                assertEquals("Sensitive(redacted)", value.toString(), "$where must not print its value")
            }
            ExpectedDecode.Unknown -> Unit // handled above
        }
    }

    private fun rawOctets(
        value: DecodedValue,
        where: String,
    ): ByteArray = (value as? DecodedValue.RawBinary)?.bytes() ?: fail("$where expected RawBinary, got $value")

    private fun assertBitField(
        expected: ExpectedDecode.Bits,
        node: TlvNode,
        value: DecodedValue,
        where: String,
    ) {
        val bitField = value as? DecodedValue.BitField ?: fail("$where expected BitField, got $value")
        assertArrayEquals(node.valueBytes(), bitField.bytes(), "$where bit-field octets")
        assertEquals(
            expected.flags.map { DecodedValue.BitField.SetFlag(it.byteIndex, it.bit, it.meaning) },
            bitField.flags,
            "$where set flags",
        )
        assertEquals(
            expected.selections.map {
                DecodedValue.BitField.EnumSelection(it.byteIndex, it.label, it.value, it.meaning)
            },
            bitField.selections,
            "$where enum selections",
        )
    }

    // (C) ROUND-TRIP

    private fun assertRoundTrip(vector: GoldenVector) {
        val bytes = loadVectorBytes(vector.hexFile)
        val roots = TlvParser.parse(bytes).expectSuccess()
        assertArrayEquals(bytes, TlvEncoder.encode(roots), "${vector.name}: encode(parse(bytes)) == bytes")
    }

    // Cross-vector properties

    @Test
    fun `Vector 3 masks the PAN by default and reveals it only on request`() {
        val root = TlvParser.parse(loadVectorBytes("03-read-record.hex")).expectSuccess().single()
        val pan = root.children.single { it.tag.hex == "5A" }
        val decoded = ValueDecoder.decode(pan, infoFor(pan)).expectValue()

        // Masked by default: nothing that reaches a log or an exception message carries the digits.
        assertEquals("Sensitive(redacted)", decoded.toString(), "the default decode must not print the PAN")
        // The full PAN is reachable only by naming reveal(), which reads as the decision it is.
        assertEquals(DecodedValue.Digits("5570295626678085"), decoded.revealed())
    }

    @Test
    fun `GPO Format 1 hides in an opaque 80 the same AIP and AFL that Format 2 exposes`() {
        val format1 = TlvParser.parse(loadVectorBytes("05-gpo-fmt1.hex")).expectSuccess().single()
        val format2 = TlvParser.parse(loadVectorBytes("06-gpo-fmt2.hex")).expectSuccess().single()

        // 80: bit 6 clear, so the parser does not recurse and the six octets stay opaque.
        assertFalse(format1.tag.isConstructed, "80 is primitive")
        assertTrue(format1.children.isEmpty(), "80 must not recurse")
        val payload = format1.valueBytes()
        assertEquals(6, payload.size)

        // 77: bit 6 set, so the identical payload is exposed as 82 AIP then 94 AFL.
        val aip = format2.children.single { it.tag.hex == "82" }
        val afl = format2.children.single { it.tag.hex == "94" }
        assertArrayEquals(payload.copyOfRange(0, 2), aip.valueBytes(), "AIP is the first two octets of 80")
        assertArrayEquals(payload.copyOfRange(2, 6), afl.valueBytes(), "AFL is the remaining octets of 80")
    }
}
