package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.Dol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Data Object List decoder: `9F38` PDOL, `8C` CDOL1, `8D` CDOL2 read into their (tag, length)
 * entries. A DOL is a run of tag-and-length pairs with no values, so the decoder reuses the same
 * tag and length readers `TlvParser` uses and consumes no value octets between entries.
 *
 * Inputs are whole `9F38`/`8C`/`8D` data objects parsed by `TlvParser`, so the offsets a failure
 * reports are indices into a buffer the parser accepted, and every DOL body tested is the value of
 * an object the parser really produced. None of it is card data — a DOL carries none.
 */
class DolDecodeTest {
    @Test
    fun `the Visa PDOL decodes to its four tag-and-length entries`() {
        // 9F38 0C | 9F33 03 · 9F1A 02 · 9F35 01 · 9F40 05  (the published Vector 2 PDOL).
        assertEquals(
            listOf("9F33" to 3, "9F1A" to 2, "9F35" to 1, "9F40" to 5),
            entriesOf("9F380C9F33039F1A029F35019F4005"),
        )
    }

    @Test
    fun `a CDOL1 with single- and multi-byte tags decodes in wire order`() {
        // 8C mixes one-octet tags (95, 9A, 9C) with two-octet tags (9F02, 9F37 …); the reader tells
        // them apart by the 1F escape, exactly as it does for a real data object.
        assertEquals(
            listOf(
                "9F02" to 6,
                "9F03" to 6,
                "9F1A" to 2,
                "95" to 5,
                "5F2A" to 2,
                "9A" to 3,
                "9C" to 1,
                "9F37" to 4,
                "9F35" to 1,
                "9F45" to 2,
                "9F4C" to 8,
                "9F34" to 3,
            ),
            entriesOf("8C219F02069F03069F1A0295055F2A029A039C019F37049F35019F45029F4C089F3403"),
        )
    }

    @Test
    fun `an empty DOL is well-formed and has no entries`() {
        // EMV states no minimum for a DOL; 8C 00 is an empty list, not a malformed one.
        val value = decode("8C00").expectValue()
        assertTrue(value is Dol, "an empty DOL still decodes to a Dol")
        assertTrue((value as Dol).entries.isEmpty(), "an empty DOL has no entries")
    }

    @Test
    fun `a DOL entry length is a full BER length, so the long form is read`() {
        // 9F38 04 | 9F40 81 80 — the length 81 80 is the long form for 128. A DOL entry states how
        // many octets the terminal must supply, coded per EMV Book 3 Annex B like any BER length, so
        // an entry can ask for more than 127 octets even though one rarely does.
        assertEquals(listOf("9F40" to 128), entriesOf("9F38049F408180"))
    }

    @Test
    fun `a truncated tag at the end of a DOL is reported structurally`() {
        // 9F38 01 | 9F — a lone 9F promises a continuation octet the value does not have.
        val error = decode("9F38019F").expectError()
        assertTrue(error is DecodeError.MalformedDol, "a truncated tag is a malformed DOL, got $error")
    }

    @Test
    fun `a tag with no room left for its length is reported structurally`() {
        // 9F38 02 | 9F33 — the tag consumes the whole value, leaving no length octet behind it.
        val error = decode("9F38029F33").expectError()
        assertTrue(error is DecodeError.MalformedDol, "a missing length is a malformed DOL, got $error")
    }

    @Test
    fun `a malformed DOL points at the failing entry's first octet in the parsed buffer`() {
        // Buffer: 9F 38 02 9F 33. The value begins at index 3, and the bad entry begins there, so the
        // reported offset is 3 — an index into the parsed buffer, the same coordinate every error uses.
        val error = decode("9F38029F33").expectError()
        assertEquals("9F38", error.tag.hex, "the error names the DOL object")
        assertEquals(3, error.offset, "the offset is the failing entry's first octet in the buffer")
    }

    @Test
    fun `a DOL is not cardholder data and is never wrapped sensitive`() {
        assertTrue(decode("9F380C9F33039F1A029F35019F4005").expectValue() is Dol)
    }

    private fun entriesOf(text: String): List<Pair<String, Int>> =
        (decode(text).expectValue() as Dol).entries.map { it.tag.hex to it.length }
}
