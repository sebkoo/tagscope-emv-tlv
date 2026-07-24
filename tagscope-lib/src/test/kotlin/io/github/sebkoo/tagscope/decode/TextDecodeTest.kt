package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.Text
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The `an` and `ans` formats, and the trailing filler cards write that §4.3 does not admit.
 *
 * The tolerated run is stripped and reported rather than either rejected or silently swallowed, so
 * every case here pins both halves: the text that came out, and what was taken off to get it.
 *
 * Hand-written values only. `50` is an application label in EMV; the values here are not real cards.
 */
class TextDecodeTest {
    @Test
    fun `an decodes letters and digits, with nothing taken off`() {
        // 5F2D, a language preference: two identifier octets and one length octet, value at 3.
        assertEquals(Text("en"), decode("5F2D02656E").expectValue())
        assertEquals(Text("enfr"), decode("5F2D04656E6672").expectValue())
        assertEquals(Text("e1"), decode("5F2D026531").expectValue())
    }

    @Test
    fun `an tolerates the trailing spaces cards pad with, and says how many it took off`() {
        val padded = decode("5F2D04656E2020").expectValue()

        assertEquals(Text("en", TextPadding.Stripped(offset = 5, octets = listOf(0x20, 0x20))), padded)
    }

    @Test
    fun `an tolerates trailing nulls, and a run that mixes the two`() {
        assertEquals(
            Text("en", TextPadding.Stripped(offset = 5, octets = listOf(0x00, 0x00))),
            decode("5F2D04656E0000").expectValue(),
        )
        assertEquals(
            Text("en", TextPadding.Stripped(offset = 5, octets = listOf(0x20, 0x00))),
            decode("5F2D04656E2000").expectValue(),
        )
    }

    @Test
    fun `a value that is nothing but padding decodes to no text`() {
        val allFiller = decode("5F2D022020").expectValue()

        assertEquals(Text("", TextPadding.Stripped(offset = 3, octets = listOf(0x20, 0x20))), allFiller)
    }

    @Test
    fun `an embedded space is an anomaly, not padding, however the value ends`() {
        val node = node("5F2D0365206E")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.UnexpectedCharacter(node.tag, offset = 4, octet = 0x20), error)
    }

    @Test
    fun `a trailing octet that is neither space nor null is not tolerated`() {
        val node = node("5F2D03656E21")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        // '!' is printable and would pass as ans, but §4.3 gives an letters and digits only.
        assertEquals(DecodeError.UnexpectedCharacter(node.tag, offset = 5, octet = 0x21), error)
    }

    @Test
    fun `padding tolerance stops at the run, so a bad octet before it still fails`() {
        val node = node("5F2D0465216E20")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.UnexpectedCharacter(node.tag, offset = 4, octet = 0x21), error)
    }

    @Test
    fun `ans keeps its trailing spaces, which the Common Character Set admits`() {
        // 50, an application label (ans): value at offset 2. "JANE DOE  ", padded and conformant.
        val name = decode("500A4A414E4520444F452020").expectValue()

        assertEquals(Text("JANE DOE  "), name)
    }

    @Test
    fun `ans admits punctuation as well`() {
        assertEquals(Text("J. DOE"), decode("50064A2E20444F45").expectValue())
    }

    @Test
    fun `ans still has its trailing nulls taken off, since a null is not in the set either`() {
        // 50, an application label: one identifier octet and one length octet, value at 2.
        assertEquals(
            Text("TEST", TextPadding.Stripped(offset = 6, octets = listOf(0x00, 0x00))),
            decode("5006544553540000").expectValue(),
        )
        assertEquals(
            Text("JANE", TextPadding.Stripped(offset = 6, octets = listOf(0x00))),
            decode("50054A414E4500").expectValue(),
        )
    }

    @Test
    fun `ans rejects an embedded null, which is filler nowhere but at the end`() {
        val node = node("5003410042")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.UnexpectedCharacter(node.tag, offset = 3, octet = 0x00), error)
    }

    @Test
    fun `ans rejects what is not printable at all`() {
        val delete = node("5002417F")
        val high = node("50024180")

        assertEquals(
            DecodeError.UnexpectedCharacter(delete.tag, offset = 3, octet = 0x7F),
            ValueDecoder.decode(delete, infoFor(delete)).expectError(),
        )
        // 0x80 is negative as a JVM byte; a decoder that forgot to widen it would read it as a
        // permitted character or as something absurd.
        assertEquals(
            DecodeError.UnexpectedCharacter(high.tag, offset = 3, octet = 0x80),
            ValueDecoder.decode(high, infoFor(high)).expectError(),
        )
    }

    @Test
    fun `an empty text value decodes to no text`() {
        assertEquals(Text(""), decode("5F2D00").expectValue())
        assertEquals(Text(""), decode("5000").expectValue())
    }

    @Test
    fun `the stripped octets cannot be mutated through the list they are handed out in`() {
        val padded = decode("5F2D04656E2020").expectValue() as Text
        val stripped = padded.padding as TextPadding.Stripped

        @Suppress("UNCHECKED_CAST")
        val castBack = stripped.octets as MutableList<Int>

        // The same hole TlvNode.children closes: List and MutableList are one type on the JVM.
        assertThrows<UnsupportedOperationException> { castBack.add(0xFF) }
        assertThrows<UnsupportedOperationException> { castBack.clear() }
        assertEquals(listOf(0x20, 0x20), stripped.octets)
    }

    @Test
    fun `a list emptied after being handed to the constructor does not empty the padding`() {
        val live = mutableListOf(0x20)

        val padding = TextPadding.Stripped(offset = 5, octets = live)
        live.clear()

        // Without the copy this would hold no octets at all, which is the one state its own
        // constructor refuses — and this route needs no cast, just ordinary Kotlin.
        assertEquals(1, padding.octetCount)
        assertEquals(listOf(0x20), padding.octets)
    }

    @Test
    fun `padding of no octets is refused outright, since that is what None is for`() {
        assertThrows<IllegalArgumentException> { TextPadding.Stripped(offset = 5, octets = emptyList()) }
    }

    @Test
    fun `padding compares by what was stripped and from where`() {
        assertEquals(TextPadding.Stripped(5, listOf(0x20)), TextPadding.Stripped(5, listOf(0x20)))
        assertEquals(
            TextPadding.Stripped(5, listOf(0x20)).hashCode(),
            TextPadding.Stripped(5, listOf(0x20)).hashCode(),
        )
        assertNotEquals(TextPadding.Stripped(5, listOf(0x20)), TextPadding.Stripped(6, listOf(0x20)))
        assertNotEquals(TextPadding.Stripped(5, listOf(0x20)), TextPadding.Stripped(5, listOf(0x00)))
    }
}
