package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TlvParserTest {
    @Test
    fun `parses a single primitive data object`() {
        val nodes = TlvParser.parse(hex("9F36020001")).expectSuccess()

        assertEquals(1, nodes.size)
        val node = nodes.single()
        assertEquals("9F36", node.tag.hex)
        assertFalse(node.tag.isConstructed)
        assertEquals(2, node.length.value)
        assertEquals(0, node.offset)
        assertTrue(node.children.isEmpty())
        assertArrayEquals(hex("0001"), node.valueBytes())
    }

    @Test
    fun `parses several data objects in one payload`() {
        val nodes = TlvParser.parse(hex("9F3602000195050000000000")).expectSuccess()

        assertEquals(2, nodes.size)
        assertEquals("9F36", nodes[0].tag.hex)
        assertEquals(0, nodes[0].offset)
        assertEquals("95", nodes[1].tag.hex)
        assertEquals(5, nodes[1].offset)
        assertArrayEquals(hex("0000000000"), nodes[1].valueBytes())
    }

    @Test
    fun `parses an empty payload as an empty sequence`() {
        assertEquals(emptyList<TlvNode>(), TlvParser.parse(hex("")).expectSuccess())
    }

    @Test
    fun `parses the PSE FCI template into a nested tree`() {
        // 6F { 84 "1PAY.SYS.DDF01", A5 { 88 01 } }
        val nodes =
            TlvParser.parse(hex("6F15840E315041592E5359532E4444463031A503880101")).expectSuccess()

        val fci = nodes.single()
        assertEquals("6F", fci.tag.hex)
        assertTrue(fci.tag.isConstructed)
        assertEquals(21, fci.length.value)
        assertEquals(0, fci.offset)
        assertEquals(2, fci.children.size)
        // A constructed node keeps its own value octets as well as its children.
        assertArrayEquals(
            hex("840E315041592E5359532E4444463031A503880101"),
            fci.valueBytes(),
        )

        val dfName = fci.children[0]
        assertEquals("84", dfName.tag.hex)
        assertEquals(2, dfName.offset)
        assertTrue(dfName.children.isEmpty())
        assertArrayEquals(hex("315041592E5359532E4444463031"), dfName.valueBytes())

        val proprietary = fci.children[1]
        assertEquals("A5", proprietary.tag.hex)
        assertTrue(proprietary.tag.isConstructed)
        assertEquals(18, proprietary.offset)

        val sfi = proprietary.children.single()
        assertEquals("88", sfi.tag.hex)
        assertEquals(20, sfi.offset)
        assertTrue(sfi.children.isEmpty())
        assertArrayEquals(hex("01"), sfi.valueBytes())
    }

    @Test
    fun `recurses into a constructed template`() {
        val nodes = TlvParser.parse(hex("770A82021C00940408010100")).expectSuccess()

        val template = nodes.single()
        assertEquals("77", template.tag.hex)
        assertEquals(2, template.children.size)

        assertEquals("82", template.children[0].tag.hex)
        assertEquals(2, template.children[0].offset)
        assertArrayEquals(hex("1C00"), template.children[0].valueBytes())

        assertEquals("94", template.children[1].tag.hex)
        assertEquals(6, template.children[1].offset)
        assertArrayEquals(hex("08010100"), template.children[1].valueBytes())
    }

    @Test
    fun `treats 80 as a primitive leaf and does not recurse into it`() {
        // These six octets would parse as TLV (1C 00, 08 01 01, then 00) if 80 were recursed
        // into. Bit 6 of 80 is clear, so it is primitive and its value is opaque.
        val nodes = TlvParser.parse(hex("80061C0008010100")).expectSuccess()

        val node = nodes.single()
        assertEquals("80", node.tag.hex)
        assertFalse(node.tag.isConstructed)
        assertTrue(node.children.isEmpty())
        assertEquals(6, node.length.value)
        assertArrayEquals(hex("1C0008010100"), node.valueBytes())
    }

    @Test
    fun `parses a zero-length value as a leaf and a zero-length template as childless`() {
        val nodes = TlvParser.parse(hex("5A006F00")).expectSuccess()

        assertEquals(2, nodes.size)
        assertEquals("5A", nodes[0].tag.hex)
        assertEquals(0, nodes[0].length.value)
        assertEquals(0, nodes[0].valueBytes().size)
        assertTrue(nodes[0].children.isEmpty())

        assertEquals("6F", nodes[1].tag.hex)
        assertTrue(nodes[1].tag.isConstructed)
        assertEquals(2, nodes[1].offset)
        assertTrue(nodes[1].children.isEmpty())
    }

    @Test
    fun `skips 00 filler before, between and after data objects`() {
        val nodes = TlvParser.parse(hex("0082021C00000094040801010000")).expectSuccess()

        assertEquals(2, nodes.size)
        assertEquals("82", nodes[0].tag.hex)
        assertEquals(1, nodes[0].offset)
        assertArrayEquals(hex("1C00"), nodes[0].valueBytes())
        assertEquals("94", nodes[1].tag.hex)
        assertEquals(7, nodes[1].offset)
        assertArrayEquals(hex("08010100"), nodes[1].valueBytes())
    }

    @Test
    fun `skips 00 filler between siblings inside a template`() {
        val nodes = TlvParser.parse(hex("6F058400008800")).expectSuccess()

        val template = nodes.single()
        assertEquals(2, template.children.size)
        assertEquals("84", template.children[0].tag.hex)
        assertEquals(2, template.children[0].offset)
        assertEquals("88", template.children[1].tag.hex)
        assertEquals(5, template.children[1].offset)
    }

    @Test
    fun `parses a payload of nothing but 00 filler as an empty sequence`() {
        assertEquals(emptyList<TlvNode>(), TlvParser.parse(hex("000000")).expectSuccess())
    }

    @Test
    fun `reads a two-octet tag nested inside a template`() {
        val nodes = TlvParser.parse(hex("70059F36020001")).expectSuccess()

        val child = nodes.single().children.single()
        assertEquals("9F36", child.tag.hex)
        assertEquals(2, child.tag.octetLength)
        assertEquals(2, child.offset)
        assertArrayEquals(hex("0001"), child.valueBytes())
    }

    @Test
    fun `reads a long-form length before the value rather than assuming one octet`() {
        // 70 81 05: the length field is two octets, so the child begins at offset 3, not 2.
        val nodes = TlvParser.parse(hex("7081059F36020001")).expectSuccess()

        val template = nodes.single()
        assertEquals(5, template.length.value)
        assertEquals(2, template.length.octetLength)

        val child = template.children.single()
        assertEquals("9F36", child.tag.hex)
        assertEquals(3, child.offset)
        assertArrayEquals(hex("0001"), child.valueBytes())
    }

    @Test
    fun `parses a template whose value is too long for a short-form length`() {
        // 26 copies of 9F36 02 0001 is 130 value octets, past the short form's 127.
        val body = List(26) { hex("9F36020001") }.reduce(ByteArray::plus)

        val template = TlvParser.parse(hex("708182") + body).expectSuccess().single()

        assertEquals(130, template.length.value)
        assertEquals(26, template.children.size)
        assertEquals(3, template.children.first().offset)
        assertEquals(128, template.children.last().offset)
    }

    @Test
    fun `parses nesting exactly as deep as the guard allows`() {
        val nodes = TlvParser.parse(nested(TlvParser.MAX_DEPTH - 1)).expectSuccess()
        val deepest = nodes.walk().last()

        assertEquals(TlvParser.MAX_DEPTH, nodes.walk().count())
        assertEquals("88", deepest.tag.hex)
    }

    @Test
    fun `the returned sequence refuses mutation, as a node's children do`() {
        // The primary public API. Without the wrapper, parse hands back a live mutableListOf, and
        // List and MutableList are one JVM type, so a cast reaches it. Two nodes on purpose:
        // toList() returns an immutable list of its own for nought or one element, so a shorter
        // payload would refuse mutation even with the wrapper gone.
        val nodes = TlvParser.parse(hex("9F3602000195050000000000")).expectSuccess()

        val castBack = nodes as MutableList<TlvNode>

        assertThrows<UnsupportedOperationException> { castBack.add(nodes[0]) }
        assertThrows<UnsupportedOperationException> { castBack.clear() }
        assertEquals(2, nodes.size)
    }

    @Test
    fun `walks a tree depth-first, parent before child`() {
        val nodes =
            TlvParser.parse(hex("6F15840E315041592E5359532E4444463031A503880101")).expectSuccess()

        assertEquals(
            listOf("6F", "84", "A5", "88"),
            nodes.walk().map { it.tag.hex }.toList(),
        )
    }
}
