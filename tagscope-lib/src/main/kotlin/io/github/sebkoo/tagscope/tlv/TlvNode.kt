package io.github.sebkoo.tagscope.tlv

import java.util.Collections

/**
 * One BER-TLV data object, together with the objects nested inside it.
 *
 * A primitive object is a leaf: its value octets are a terminal value and [children] is empty. A
 * constructed object holds the objects parsed out of its value octets, and keeps its own value
 * octets as well, so the bytes a node was built from are always recoverable.
 *
 * [offset] is the index of the tag's first identifier octet in the buffer that was parsed, not in
 * the parent's value, so every offset in a tree refers to the same coordinate system as the
 * offsets carried by [TlvError].
 *
 * Instances are immutable: the value octets and the child list are both copied on the way in,
 * [children] refuses every mutation, and [valueBytes] hands out a fresh copy on the way out.
 *
 * EMV Book 3, Annex B; ISO/IEC 7816-4 §5.2.2.
 *
 * @property tag the identifier field.
 * @property length the length field, whose value is how many value octets the object declares.
 * @property offset the index of the first identifier octet within the parsed buffer.
 */
public class TlvNode(
    public val tag: TlvTag,
    public val length: TlvLength,
    value: ByteArray,
    children: List<TlvNode>,
    public val offset: Int,
) {
    private val valueOctets: ByteArray = value.copyOf()

    /**
     * The data objects nested inside this one, in the order they appear. Empty for a leaf.
     *
     * Copied on the way in and then wrapped, so neither the list the caller passed to the
     * constructor nor a cast back to `MutableList` can reach a node's children: Kotlin's
     * read-only `List` is the same JVM type as `MutableList`, so without the wrapper the cast
     * succeeds and `add` mutates a node that documents itself as immutable. The wrapper goes on
     * whatever the size, so a leaf and a parent refuse mutation the same way rather than
     * inheriting whichever read-only list `toList` happened to return.
     */
    public val children: List<TlvNode> = Collections.unmodifiableList(children.toList())

    init {
        // Preconditions on the value object, not parse errors. Malformed input never reaches
        // here: TlvParser reports it by returning a TlvError instead of constructing a node.
        require(offset >= 0) { "offset must not be negative, was $offset" }
        require(value.size == length.value) {
            "value is ${value.size} octets but the length field declares ${length.value}"
        }
        require(tag.isConstructed || children.isEmpty()) {
            "a primitive data object has no children, ${tag.hex} was given ${children.size}"
        }
    }

    /**
     * The index of the first value octet within the parsed buffer.
     *
     * [offset] addresses the identifier octets, so this is that plus the identifier and length
     * fields. Derived rather than stored, because a node that carried both could disagree with
     * itself; public, because anything reporting which value octet it choked on has to say so in
     * the coordinate system [offset] and every [TlvError] already use.
     *
     * For an empty value this is one past the object's last octet, which is the same convention a
     * truncation offset follows.
     */
    public val valueOffset: Int
        get() = offset + tag.octetLength + length.octetLength

    /** The value octets, exactly as they appear on the wire. A fresh copy on every call. */
    public fun valueBytes(): ByteArray = valueOctets.copyOf()

    /**
     * This node and every node beneath it, depth-first and parent before child.
     *
     * There is no `find`: callers filter this themselves, which keeps absence an empty result at
     * the call site rather than a `null` or an invented "not found" error in this library.
     */
    public fun walk(): Sequence<TlvNode> =
        sequence {
            yield(this@TlvNode)
            for (child in children) {
                yieldAll(child.walk())
            }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TlvNode) return false
        // Compares the value octets by content. A data class would compare the array by identity
        // instead, which is why this type is not one.
        return offset == other.offset &&
            tag == other.tag &&
            length == other.length &&
            valueOctets.contentEquals(other.valueOctets) &&
            children == other.children
    }

    override fun hashCode(): Int {
        var result = tag.hashCode()
        result = HASH_FACTOR * result + length.hashCode()
        result = HASH_FACTOR * result + valueOctets.contentHashCode()
        result = HASH_FACTOR * result + children.hashCode()
        result = HASH_FACTOR * result + offset
        return result
    }

    override fun toString(): String =
        "TlvNode(${tag.hex}, length=${length.value}, offset=$offset, children=${children.size})"

    private companion object {
        private const val HASH_FACTOR: Int = 31
    }
}

/** Every node in this sequence of data objects and everything beneath them, depth-first. */
public fun Iterable<TlvNode>.walk(): Sequence<TlvNode> = asSequence().flatMap { it.walk() }
