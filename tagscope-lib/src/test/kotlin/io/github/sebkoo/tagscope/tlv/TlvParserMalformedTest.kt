package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TlvParserMalformedTest {
    @Test
    fun `rejects a child whose value runs past its parent`() {
        // 6F declares five value octets; the 84 inside it declares eight, which is still inside
        // the buffer but outside the template.
        val error = TlvParser.parse(hex("6F0584080102030405060708")).expectFailure()

        assertEquals(TlvError.ChildOverrunsParent(offset = 2, parentEnd = 7), error)
    }

    @Test
    fun `rejects a child whose length field itself straddles the end of its parent`() {
        // 6F's value is offsets 2..3, so the 81 at offset 3 is inside the template but the
        // octet it points at, offset 4, is not.
        val error = TlvParser.parse(hex("6F02848105AABBCCDDEE")).expectFailure()

        assertEquals(TlvError.ChildOverrunsParent(offset = 2, parentEnd = 4), error)
    }

    @Test
    fun `does not diagnose a straddling child from octets outside its parent`() {
        // 70 declares two value octets, so its value is exactly the identifier octets 9F 36 and
        // it ends at offset 4. The child's length field would have to start at offset 4, which
        // belongs to the next object. Whatever sits there is not this template's length octet,
        // and the failure must not be decided by it: all three of these payloads have the same
        // structural defect and must report the same error.
        assertEquals(
            TlvError.ChildOverrunsParent(offset = 2, parentEnd = 4),
            TlvParser.parse(hex("70029F3680020001")).expectFailure(),
        )
        assertEquals(
            TlvError.ChildOverrunsParent(offset = 2, parentEnd = 4),
            TlvParser.parse(hex("70029F36FF020001")).expectFailure(),
        )
        assertEquals(
            TlvError.ChildOverrunsParent(offset = 2, parentEnd = 4),
            TlvParser.parse(hex("70029F3685020001")).expectFailure(),
        )
    }

    @Test
    fun `still reports a genuine truncation when the buffer ends inside a child`() {
        // The mirror of the case above: here offset 4 is past the end of the buffer as well as
        // past the template, and the buffer running out is the more specific truth.
        assertEquals(
            TlvError.UnexpectedEndOfData(offset = 4, size = 4),
            TlvParser.parse(hex("70029F36")).expectFailure(),
        )
    }

    @Test
    fun `rejects a top-level value truncated by the end of the buffer`() {
        val error = TlvParser.parse(hex("5A050102")).expectFailure()

        assertEquals(
            TlvError.TruncatedValue(offset = 4, declaredLength = 5, availableOctets = 2),
            error,
        )
    }

    @Test
    fun `rejects a truncated tag at the top level`() {
        assertEquals(TlvError.TruncatedTag(offset = 1), TlvParser.parse(hex("9F")).expectFailure())
    }

    @Test
    fun `surfaces a reader failure from inside a template at its absolute offset`() {
        val error = TlvParser.parse(hex("6F019F")).expectFailure()

        assertEquals(TlvError.TruncatedTag(offset = 3), error)
    }

    @Test
    fun `surfaces the indefinite length form from inside a template`() {
        val error = TlvParser.parse(hex("6F028480")).expectFailure()

        assertEquals(TlvError.IndefiniteLength(offset = 3), error)
    }

    @Test
    fun `surfaces the reserved length octet from inside a template`() {
        val error = TlvParser.parse(hex("6F0284FF")).expectFailure()

        assertEquals(TlvError.ReservedLengthOctet(offset = 3), error)
    }

    @Test
    fun `reports a stray FF where a data object was expected instead of skipping it`() {
        // ISO/IEC 7816-4 allows FF as filler between data objects; EMV Book 3 Annex B1 allows
        // only 00. Tagscope follows EMV, so an FF is reported wherever it appears.
        assertEquals(
            TlvError.UnexpectedFillerOctet(offset = 0),
            TlvParser.parse(hex("FF82021C00")).expectFailure(),
        )
        assertEquals(
            TlvError.UnexpectedFillerOctet(offset = 4),
            TlvParser.parse(hex("82021C00FF")).expectFailure(),
        )
    }

    @Test
    fun `reports a stray FF between siblings inside a template`() {
        val error = TlvParser.parse(hex("6F038400FF")).expectFailure()

        assertEquals(TlvError.UnexpectedFillerOctet(offset = 4), error)
    }

    @Test
    fun `rejects nesting one level deeper than the guard allows`() {
        // Each template costs two octets, so the value of the outermost-but-one begins here.
        val error = TlvParser.parse(nested(TlvParser.MAX_DEPTH)).expectFailure()

        assertEquals(
            TlvError.NestingTooDeep(offset = 2 * TlvParser.MAX_DEPTH, maxDepth = TlvParser.MAX_DEPTH),
            error,
        )
    }

    @Test
    fun `does not trip the depth guard on an empty template at the limit`() {
        // An empty constructed object has nothing nested inside it, so sitting at the deepest
        // allowed level is not itself a reason to reject the payload.
        val nodes =
            TlvParser
                .parse(nested(TlvParser.MAX_DEPTH - 1, innermost = hex("A500")))
                .expectSuccess()

        val deepest = nodes.walk().last()
        assertEquals(TlvParser.MAX_DEPTH, nodes.walk().count())
        assertTrue(deepest.tag.isConstructed)
        assertTrue(deepest.children.isEmpty())
    }
}
