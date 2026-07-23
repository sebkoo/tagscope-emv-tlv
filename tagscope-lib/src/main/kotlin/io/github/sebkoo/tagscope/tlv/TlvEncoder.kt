package io.github.sebkoo.tagscope.tlv

import java.io.ByteArrayOutputStream

/**
 * Serializes a tree of [TlvNode] back to BER-TLV octets: the inverse of [TlvParser.parse].
 *
 * Where the parser walks octets into a tree by recursive descent, this walks the tree back into
 * octets the same way. Each object emits its identifier octets verbatim (from [TlvTag.toBytes],
 * so a multi-byte tag such as `9F26` and any leading `0x00` an identifier carried are reproduced
 * exactly), then its length, then its value: the value octets for a primitive, or the
 * recursively-encoded [children][TlvNode.children] for a constructed object. A constructed object
 * is rebuilt from its children, not from its cached value octets — so this serializes a tree
 * assembled by hand, not only one the parser cached, which is what makes it a true inverse.
 *
 * ## What round-trips
 *
 * The parser skips `0x00` filler at every object boundary (ISO/IEC 7816-4 §5.2.2.1; EMV Book 3,
 * Annex B1), so a re-encode cannot put it back. That single fact bounds every guarantee here.
 *
 * - **Byte round-trip.** `encode(parse(x)) == x` for every `x` the parser accepts that carries no
 *   `0x00` filler. No minimality condition: a non-minimal long-form length such as `81 05` for the
 *   value 5 is reproduced octet-for-octet, because the length *form* is preserved (see below).
 * - **Tree round-trip.** `parse(encode(parse(x))) == parse(x)` for filler-free `x`. It does not
 *   hold when filler *precedes* an object — the re-encode drops it, shifting every later
 *   [offset][TlvNode.offset], which [TlvNode.equals] compares — or sits *inside* a constructed
 *   value, where dropping it shrinks that node's recomputed length and cached value octets.
 *   Trailing top-level filler and an all-filler payload still round-trip, because neither shifts an
 *   offset nor inflates a length.
 * - **Idempotence, which always holds.** `encode(parse(·))` is a normal form: encoding, reparsing
 *   and re-encoding yields the same octets for *any* input the parser accepts. Its output is filler
 *   -free with packed offsets and the length forms the tree recorded.
 *
 * A hand-built tag whose first octet is `0x00` is the write-side dual of the parser skipping `00`:
 * the parser never produces one, and one encoded here vanishes on the next parse. It is the
 * caller's to avoid; the encoder emits what the tree holds.
 *
 * ## Length form: preserved, not normalized
 *
 * The length is emitted in the number of octets the node recorded in [TlvLength.octetLength]
 * whenever the value fits there, so what the parser read is what this writes — the reason the
 * parser bothered to record the width at all. Only when a hand-built node declares a width too
 * narrow for its value does the encoder widen to the minimal form that fits; this never happens for
 * a tree the parser produced, where the recorded width always holds the value. The encoder does not
 * normalize a non-minimal length down to the minimal form: preserving what was on the wire is the
 * point of an inspection tool.
 *
 * ## Totality
 *
 * [encode] returns octets and cannot fail. For any tree the parser produced the recorded length
 * width always holds the (equal or smaller) re-encoded value, and nesting is bounded by
 * [TlvParser.MAX_DEPTH]. The widen-if-needed rule keeps a hand-built node with a too-narrow length
 * total as well. Like [TlvNode.walk], [TlvNode.equals] and [TlvNode.hashCode], the recursion is
 * unguarded, so a tree nested far deeper than the parser would ever build can exhaust the stack;
 * that is a precondition on the caller, not a failure this returns.
 *
 * EMV Book 3, Annex B; ISO/IEC 7816-4 §5.2.2.
 */
public object TlvEncoder {
    /**
     * Encodes one data object and everything nested inside it.
     *
     * A primitive object emits its value octets; a constructed one emits its recursively-encoded
     * children, and its declared length is the size of those children — which drops any `0x00`
     * filler the parser skipped between them, so a constructed value that held filler does not
     * re-encode to the octets it was parsed from.
     */
    public fun encode(node: TlvNode): ByteArray {
        // Constructed objects are rebuilt from their children, never from the cached value octets:
        // node.tag.isConstructed is the sole discriminant, exactly as in the parser, so an object
        // whose value merely looks like TLV (80) stays opaque and one whose value is all filler
        // (6F 03 000000) collapses to an empty template rather than echoing the filler.
        val body = if (node.tag.isConstructed) encode(node.children) else node.valueBytes()
        // No capacity hint: the identifier + length + value sum could overflow Int to a negative
        // for a value near the array-size limit, and ByteArrayOutputStream rejects a negative size.
        // The buffer grows on its own, and EMV payloads are far too small for the growth to matter.
        val out = ByteArrayOutputStream()
        out.writeBytes(node.tag.toBytes())
        out.writeBytes(encodeLength(body.size, node.length.octetLength))
        out.writeBytes(body)
        return out.toByteArray()
    }

    /** Encodes a sequence of data objects, back to back and in order. Empty in, empty out. */
    public fun encode(nodes: List<TlvNode>): ByteArray {
        val out = ByteArrayOutputStream()
        for (node in nodes) {
            out.writeBytes(encode(node))
        }
        return out.toByteArray()
    }

    /**
     * Encodes the length field for a value of [size] octets, in the width the node recorded.
     *
     * [preferredOctetLength] is the node's [TlvLength.octetLength]. It is honoured when the value
     * fits, so the field is byte-identical to the one the parser read, non-minimal long forms and
     * all. It is widened only when the recorded width is too narrow for the value, which a
     * parser-built node never is: only a hand-built length can declare fewer octets than its value
     * needs, and widening it is what keeps [encode] total.
     */
    private fun encodeLength(
        size: Int,
        preferredOctetLength: Int,
    ): ByteArray {
        val octetLength =
            if (fitsInLengthField(size, preferredOctetLength)) {
                preferredOctetLength
            } else {
                minimalOctetLength(size)
            }
        return renderLength(size, octetLength)
    }

    /**
     * True when [size] can be declared in a length field of [octetLength] octets.
     *
     * The short form (one octet) holds `0..127`, not `0..255`: a first length octet of `0x80` or
     * more is the long-form indicator, so `0xC8` is not the length 200 but a claim of 72 further
     * length octets. The long form with `octetLength - 1` subsequent octets holds
     * `0 .. 256^(octetLength - 1) - 1`, computed in a `Long` so a four-octet width does not
     * overflow the shift.
     */
    private fun fitsInLengthField(
        size: Int,
        octetLength: Int,
    ): Boolean =
        if (octetLength == 1) {
            size <= BerBits.SHORT_FORM_MAX
        } else {
            size.toLong() < (1L shl (Byte.SIZE_BITS * (octetLength - 1)))
        }

    /** The fewest octets a length field can declare [size] in: one for the short form, else `1 + n`. */
    private fun minimalOctetLength(size: Int): Int {
        if (size <= BerBits.SHORT_FORM_MAX) {
            return 1
        }
        val significantBits = Int.SIZE_BITS - Integer.numberOfLeadingZeros(size)
        val subsequentOctets = (significantBits + Byte.SIZE_BITS - 1) / Byte.SIZE_BITS
        return 1 + subsequentOctets
    }

    /**
     * Renders [size] as a length field of exactly [octetLength] octets.
     *
     * A one-octet field is the short form and carries [size] directly; [size] is `0..127` here,
     * guaranteed by [fitsInLengthField] and [minimalOctetLength], the only callers. A wider field
     * is the long form: a first octet of `0x80` with the count of subsequent octets in its low
     * seven bits, then [size] big-endian in those octets.
     */
    private fun renderLength(
        size: Int,
        octetLength: Int,
    ): ByteArray {
        if (octetLength == 1) {
            return byteArrayOf(size.toByte())
        }
        val subsequentOctets = octetLength - 1
        val field = ByteArray(octetLength)
        // 0x80 is the long-form indicator; its low seven bits are the subsequent-octet count. With
        // at least one subsequent octet it is never the bare 0x80 the parser reads as indefinite.
        field[0] = (LONG_FORM_INDICATOR or subsequentOctets).toByte()
        for (index in 0 until subsequentOctets) {
            field[octetLength - 1 - index] = (size ushr (Byte.SIZE_BITS * index)).toByte()
        }
        return field
    }

    /** First length octet with this bit set is the long form; its low seven bits count the rest. */
    private const val LONG_FORM_INDICATOR: Int = 0x80
}
