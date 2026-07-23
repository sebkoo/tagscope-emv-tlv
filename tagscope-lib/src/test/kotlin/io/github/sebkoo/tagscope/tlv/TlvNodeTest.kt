package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

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
}
