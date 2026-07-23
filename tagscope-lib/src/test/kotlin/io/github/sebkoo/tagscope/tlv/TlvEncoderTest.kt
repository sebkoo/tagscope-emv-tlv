package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * Pins [TlvEncoder] as the inverse of [TlvParser]. Every fixture is a hand-written structural
 * vector, none of it card data; the hex is written so a case reads like the wire it stands for.
 */
class TlvEncoderTest {
    // Filler-free, canonically-laid-out payloads: for these the byte round-trip is exact and the
    // tree round-trip holds. Each is exercised both ways below.
    private val fillerFree =
        listOf(
            "9F36020001", // one primitive
            "9F3602000195050000000000", // two primitives side by side
            "6F15840E315041592E5359532E4444463031A503880101", // PSE FCI, nested
            "770A82021C00940408010100", // constructed 77 { 82, 94 }
            "80061C0008010100", // 80 is primitive: its value looks like TLV but is opaque
            "5A006F00", // empty primitive then empty template
            "70059F36020001", // template holding a two-octet tag
            "7081059F36020001", // same value, length in a non-minimal long form
            "9F36020000", // a 00 inside a value is a value octet, not filler
            "5F2A020840", // two-octet tag 5F2A
            "9F2608A1A2A3A4A5A6A7A8", // two-octet tag 9F26, opaque eight-octet value
            "6F00", // empty constructed template
            "5A00", // empty primitive value
        )

    @Test
    fun `byte round-trip reproduces filler-free octets exactly`() {
        for (fixture in fillerFree) {
            assertByteRoundTrip(hex(fixture), fixture)
        }
        // A template whose value is 130 octets, past the short form: the long-form length is
        // minimal here and reproduced as read.
        assertByteRoundTrip(longFormTemplate(), "long-form template")
        // As deep as the parser follows, all short-form lengths.
        assertByteRoundTrip(nested(TlvParser.MAX_DEPTH - 1), "deeply nested")
    }

    @Test
    fun `tree round-trip yields an equal tree for filler-free octets`() {
        for (fixture in fillerFree) {
            assertTreeRoundTrip(hex(fixture), fixture)
        }
        assertTreeRoundTrip(longFormTemplate(), "long-form template")
        assertTreeRoundTrip(nested(TlvParser.MAX_DEPTH - 1), "deeply nested")
    }

    @Test
    fun `both overloads encode the same object identically`() {
        val nodes = TlvParser.parse(hex("9F36020001")).expectSuccess()

        assertArrayEquals(hex("9F36020001"), TlvEncoder.encode(nodes))
        assertArrayEquals(hex("9F36020001"), TlvEncoder.encode(nodes.single()))
    }

    @Test
    fun `an empty sequence encodes to no octets`() {
        assertArrayEquals(ByteArray(0), TlvEncoder.encode(emptyList()))
        // A payload of pure filler parses to an empty sequence and so encodes to nothing.
        assertArrayEquals(ByteArray(0), TlvEncoder.encode(TlvParser.parse(hex("000000")).expectSuccess()))
    }

    // --- Length form: preserved, not normalized (option a) --------------------------------------

    @Test
    fun `a non-minimal long-form length is preserved on a primitive`() {
        // 9F1A 81 02 0840: the value is two octets, written with a long-form length. Preserving the
        // form reproduces 81 02; normalizing to the minimal form would emit 9F1A 02 0840 instead.
        val encoded = TlvEncoder.encode(TlvParser.parse(hex("9F1A81020840")).expectSuccess())

        assertArrayEquals(hex("9F1A81020840"), encoded)
        assertEquals(0x81.toByte(), encoded[2], "the length field stays in long form")
    }

    @Test
    fun `a non-minimal long-form length is preserved on a template`() {
        // 70 81 05 { 9F36 02 0001 }: normalizing would collapse 81 05 to a short-form 05.
        assertByteRoundTrip(hex("7081059F36020001"), "constructed long form")
    }

    // --- Widen-if-needed: the only case where a recorded width is not honoured -------------------
    //
    // A hand-built node can declare a length width too narrow for its value. Widening keeps encode
    // total and, crucially, never emits a bare short-form octet above 0x7F, which the reader would
    // take for a long-form indicator.

    @Test
    fun `a short-form width too narrow for its value widens rather than emitting a malformed octet`() {
        // 200 does not fit the short form (max 127). It must become 81 C8, never the bare C8 that
        // announces 72 further length octets.
        val encoded = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 200, octetLength = 1)))

        assertArrayEquals(bytes(0x50, 0x81, 0xC8), encoded.copyOf(3))
        assertEquals(1 + 2 + 200, encoded.size)
    }

    @Test
    fun `128 widens to a two-octet length and 127 stays in the short form`() {
        val at128 = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 128, octetLength = 1)))
        assertArrayEquals(bytes(0x50, 0x81, 0x80), at128.copyOf(3))

        val at127 = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 127, octetLength = 1)))
        assertArrayEquals(bytes(0x50, 0x7F), at127.copyOf(2))
        assertEquals(1 + 1 + 127, at127.size)
    }

    @Test
    fun `a long-form width wide enough is kept even when the value would fit fewer octets`() {
        // 5 fits the short form, but a recorded three-octet width is honoured: 82 0005, not 05.
        val encoded = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 5, octetLength = 3)))

        assertArrayEquals(bytes(0x50, 0x82, 0x00, 0x05), encoded.copyOf(4))
        assertEquals(1 + 3 + 5, encoded.size)
    }

    @Test
    fun `the long-form capacity boundary widens at 256 and not at 255`() {
        // Two subsequent octets hold up to 255. 255 fits (81 FF); 256 does not and widens (82 0100).
        val at255 = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 255, octetLength = 2)))
        assertArrayEquals(bytes(0x50, 0x81, 0xFF), at255.copyOf(3))

        val at256 = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 256, octetLength = 2)))
        assertArrayEquals(bytes(0x50, 0x82, 0x01, 0x00), at256.copyOf(4))
    }

    @Test
    fun `the widest long forms render big-endian across three and four subsequent octets`() {
        // Three subsequent octets (82 is two, 83 is three): 0x010000 big-endian is 01 00 00, so the
        // most significant octet lands right after the indicator.
        val threeSub = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 0x010000, octetLength = 4)))
        assertArrayEquals(bytes(0x50, 0x83, 0x01, 0x00, 0x00), threeSub.copyOf(5))

        // Four subsequent octets, the widest field: exercises the 1L shl 32 capacity check and the
        // full big-endian loop. 5 fits, so the recorded width is kept: 84 00000005.
        val fourSub = TlvEncoder.encode(handBuiltPrimitive(TlvLength(value = 5, octetLength = 5)))
        assertArrayEquals(bytes(0x50, 0x84, 0x00, 0x00, 0x00, 0x05), fourSub.copyOf(6))
    }

    // --- The constructed discriminant is the tag, never the child count -------------------------

    @Test
    fun `a constructed value of pure filler encodes to an empty template`() {
        // 6F 03 000000 parses to a childless template whose cached value is three filler octets.
        // Encoding rebuilds it from its children, so the filler is dropped: 6F 00, not 6F 03 000000.
        val encoded = TlvEncoder.encode(TlvParser.parse(hex("6F03000000")).expectSuccess())

        assertArrayEquals(hex("6F00"), encoded)
    }

    @Test
    fun `an opaque 80 is emitted verbatim and never recursed into`() {
        // The value would parse as TLV, but bit 6 of 80 is clear, so it stays a primitive leaf and
        // its value octets are written as they stand.
        val encoded = TlvEncoder.encode(TlvParser.parse(hex("80061C0008010100")).expectSuccess())

        assertArrayEquals(hex("80061C0008010100"), encoded)
    }

    // --- Filler: what a re-encode cannot reproduce, and what it always can -----------------------

    @Test
    fun `filler that precedes or nests inside an object is dropped, normalising the octets`() {
        // 00 before, between and after; 82 and 94 survive, the four filler octets do not.
        val payload = hex("0082021C00000094040801010000")
        val canonical = TlvEncoder.encode(TlvParser.parse(payload).expectSuccess())

        assertArrayEquals(hex("82021C00940408010100"), canonical)
        assertFalse(payload.contentEquals(canonical), "the filler cannot be reproduced")
    }

    @Test
    fun `encode of parse is a fixed point, filler and all`() {
        val payload = hex("0082021C00000094040801010000")
        val once = TlvEncoder.encode(TlvParser.parse(payload).expectSuccess())
        val twice = TlvEncoder.encode(TlvParser.parse(once).expectSuccess())

        assertArrayEquals(once, twice)
    }

    @Test
    fun `trailing top-level filler still tree round-trips`() {
        // The 00 after the object shifts no offset and inflates no length, so the tree is unchanged.
        assertTreeRoundTrip(hex("9F3602000100"), "trailing filler")
    }

    @Test
    fun `leading filler breaks the tree round-trip by shifting offsets`() {
        val payload = hex("009F36020001")
        val tree = TlvParser.parse(payload).expectSuccess()
        val reparsed = TlvParser.parse(TlvEncoder.encode(tree)).expectSuccess()

        // The re-encode drops the leading 00, so the object moves from offset 1 to offset 0.
        assertEquals(1, tree.single().offset)
        assertEquals(0, reparsed.single().offset)
        assertNotEquals(tree, reparsed)
        // The octets it produces are exactly the object without its filler.
        assertArrayEquals(hex("9F36020001"), TlvEncoder.encode(tree))
    }

    private fun assertByteRoundTrip(
        payload: ByteArray,
        label: String,
    ) {
        val nodes = TlvParser.parse(payload).expectSuccess()
        assertArrayEquals(payload, TlvEncoder.encode(nodes), "byte round-trip for $label")
    }

    private fun assertTreeRoundTrip(
        payload: ByteArray,
        label: String,
    ) {
        val tree = TlvParser.parse(payload).expectSuccess()
        val reparsed = TlvParser.parse(TlvEncoder.encode(tree)).expectSuccess()
        assertEquals(tree, reparsed, "tree round-trip for $label")
    }

    /** A primitive `50` (Application Label) carrying [length].value zero octets, built by hand. */
    private fun handBuiltPrimitive(length: TlvLength): TlvNode =
        TlvNode(
            tag = TlvTag(value = 0x50, octetLength = 1),
            length = length,
            value = ByteArray(length.value),
            children = emptyList(),
            offset = 0,
        )

    /** A template `70` whose value is 26 copies of `9F36 02 0001`, i.e. 130 octets. */
    private fun longFormTemplate(): ByteArray = hex("708182") + List(26) { hex("9F36020001") }.reduce(ByteArray::plus)

    private fun bytes(vararg octets: Int): ByteArray = ByteArray(octets.size) { octets[it].toByte() }
}
