package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * TlvNode is not a data class, because a generated `equals` would compare the value octets by
 * array identity rather than by content. That makes the hand-written `equals` and `hashCode` the
 * part of this type most worth pinning.
 */
class TlvNodeTest {
    @Test
    fun `trees parsed from equal octets are equal all the way down`() {
        val payload = hex("6F15840E315041592E5359532E4444463031A503880101")

        val first = TlvParser.parse(payload).expectSuccess()
        val second = TlvParser.parse(payload.copyOf()).expectSuccess()

        // Two separate parses, so no value octets are shared between the trees.
        assertEquals(first, second)
        assertEquals(first.single(), second.single())
        assertEquals(first.single().hashCode(), second.single().hashCode())
    }

    @Test
    fun `nodes differing only in their value octets are not equal`() {
        val one = TlvParser.parse(hex("9F36020001")).expectSuccess().single()
        val other = TlvParser.parse(hex("9F36020002")).expectSuccess().single()

        assertEquals(one.tag, other.tag)
        assertEquals(one.length, other.length)
        assertNotEquals(one, other)
    }

    @Test
    fun `nodes differing only in their offset are not equal`() {
        val atZero = TlvParser.parse(hex("880101")).expectSuccess().single()
        val atThree = TlvParser.parse(hex("000000880101")).expectSuccess().single()

        assertEquals(atZero.tag, atThree.tag)
        assertArrayEquals(atZero.valueBytes(), atThree.valueBytes())
        assertNotEquals(atZero, atThree)
    }

    @Test
    fun `nodes differing only in their children are not equal`() {
        // Same tag, same length, same value octets; one recursed into, one not.
        val constructed = TlvParser.parse(hex("A503880101")).expectSuccess().single()

        assertEquals(1, constructed.children.size)
        assertNotEquals(
            constructed,
            TlvNode(
                tag = constructed.tag,
                length = constructed.length,
                value = constructed.valueBytes(),
                children = emptyList(),
                offset = constructed.offset,
            ),
        )
    }

    @Test
    fun `valueBytes hands out a copy, so a caller cannot reach into the node`() {
        val node = TlvParser.parse(hex("9F36020001")).expectSuccess().single()

        node.valueBytes()[0] = 0xFF.toByte()

        assertArrayEquals(hex("0001"), node.valueBytes())
    }

    // Kotlin's read-only List and MutableList are the same JVM type, so a cast is all it takes to
    // reach a node's children. These pin that the cast buys nothing.
    //
    // Every template below holds two children on purpose. `toList()` returns an immutable list of
    // its own for a list of nought or one element, so a one-child template would refuse mutation
    // even without the wrapper and these tests would pass against the defect they exist to catch.

    @Test
    fun `a caller cannot add to a node's children`() {
        val node = TlvParser.parse(hex("A506880101880102")).expectSuccess().single()

        val castBack = node.children as MutableList<TlvNode>

        assertThrows<UnsupportedOperationException> { castBack.add(node) }
        assertEquals(2, node.children.size)
    }

    @Test
    fun `a caller cannot clear a node's children`() {
        val node = TlvParser.parse(hex("A506880101880102")).expectSuccess().single()

        val castBack = node.children as MutableList<TlvNode>

        assertThrows<UnsupportedOperationException> { castBack.clear() }
        assertEquals(2, node.children.size)
        assertArrayEquals(hex("01"), node.children[0].valueBytes())
    }

    @Test
    fun `a leaf refuses mutation the same way a parent does`() {
        val leaf = TlvParser.parse(hex("880101")).expectSuccess().single()

        val castBack = leaf.children as MutableList<TlvNode>

        assertThrows<UnsupportedOperationException> { castBack.add(leaf) }
        assertEquals(emptyList<TlvNode>(), leaf.children)
    }

    @Test
    fun `the list handed to the constructor is copied, so later changes do not reach the node`() {
        val template = TlvParser.parse(hex("A506880101880102")).expectSuccess().single()
        val handedIn = template.children.toMutableList()

        val node =
            TlvNode(
                tag = template.tag,
                length = template.length,
                value = template.valueBytes(),
                children = handedIn,
                offset = template.offset,
            )
        handedIn.clear()

        assertEquals(template, node)
    }

    @Test
    fun `wrapping the children leaves equality and hashing structural`() {
        val node = TlvParser.parse(hex("A506880101880102")).expectSuccess().single()
        val plain = listOf(node.children[0], node.children[1])

        assertEquals(plain, node.children)
        assertEquals(plain.hashCode(), node.children.hashCode())
    }
}
