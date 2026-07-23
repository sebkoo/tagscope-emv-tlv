package io.github.sebkoo.tagscope.tlv

/**
 * Parses a buffer of BER-TLV data into a tree of [TlvNode], by recursive descent.
 *
 * The top level is a sequence rather than a single object, because a payload may carry several
 * data objects side by side — a GET PROCESSING OPTIONS response, for one.
 *
 * The rules it applies:
 *
 * - **Only the tag decides whether to recurse.** Bit 6 of the first identifier octet says the
 *   value octets are themselves data objects, and nothing else is consulted. `80` has that bit
 *   clear, so it stays an opaque leaf even though its value is an AIP followed by an AFL and
 *   would parse as TLV if it were recursed into. Interpreting `80` is a decoding concern, not a
 *   structural one.
 * - **A constructed value is consumed exactly.** Children are parsed until the parent's declared
 *   value end, and a child reaching past it is [TlvError.ChildOverrunsParent] rather than a child
 *   silently borrowed from the next sibling.
 * - **`00` is skipped, `FF` is reported.** See [TlvError.UnexpectedFillerOctet].
 * - **Nesting is bounded** by [MAX_DEPTH].
 * - **Failures are returned, never thrown**, and every offset they carry is an index into the
 *   buffer that was handed to [parse], at any depth.
 *
 * This is structure only. Nothing here knows what a tag is called or what its value means.
 *
 * EMV Book 3, Annex B; ISO/IEC 7816-4 §5.2.2.
 */
public object TlvParser {
    /**
     * The deepest nesting this parser follows, counting the top-level sequence as level one.
     *
     * The bound is not decoration. A level of nesting costs only two octets, so without it a
     * payload of a few hundred kilobytes could nest deeply enough to exhaust the JVM stack, and
     * a parser that turns hostile input into a `StackOverflowError` has stopped returning its
     * failures. EMV templates nest a handful of levels, so 32 is far beyond real data.
     */
    public const val MAX_DEPTH: Int = 32

    /**
     * Parses every data object in [source], in order.
     *
     * A buffer holding no data objects parses to an empty list rather than a failure. Any failure
     * stops the parse and is returned as it stands, discarding whatever was parsed before it.
     */
    public fun parse(source: ByteArray): TlvResult<List<TlvNode>> =
        parseSequence(source, from = 0, end = source.size, depth = 1)

    /**
     * Parses the data objects in `[from, end)` of [source], where [end] is the buffer size at the
     * top level and the parent's declared value end below it.
     *
     * The window is over the original buffer rather than a copy of the parent's value, so every
     * offset a node or an error carries is already absolute and none of them need rebasing. It is
     * also what lets a child that overruns its parent be told apart from a value truncated by the
     * end of the buffer.
     */
    private fun parseSequence(
        source: ByteArray,
        from: Int,
        end: Int,
        depth: Int,
    ): TlvResult<List<TlvNode>> {
        // Tested before the depth guard, so an empty constructed value is never itself too deep:
        // there is nothing nested inside it to reject.
        if (from >= end) {
            return TlvResult.Success(emptyList())
        }
        if (depth > MAX_DEPTH) {
            return TlvResult.Failure(TlvError.NestingTooDeep(from, MAX_DEPTH))
        }

        val nodes = mutableListOf<TlvNode>()
        var index = from
        while (index < end) {
            val octet = source[index].toInt() and BerBits.OCTET_MASK
            if (octet == SKIPPED_FILLER_OCTET) {
                // Must be explicit: readTag would otherwise accept 00 as a valid one-octet
                // universal tag.
                index++
                continue
            }
            if (octet == REPORTED_FILLER_OCTET) {
                return TlvResult.Failure(TlvError.UnexpectedFillerOctet(index))
            }

            // Both readers are given the whole buffer, because an offset must stay absolute, so
            // either can walk off the end of this window into the next object and fail on an
            // octet this one does not own. contain() puts such a failure back where it belongs.
            val tag =
                when (val result = TlvReader.readTag(source, index)) {
                    is TlvResult.Failure ->
                        return TlvResult.Failure(contain(result.error, source, index, end, depth))
                    is TlvResult.Success -> result.value
                }
            val lengthOffset = index + tag.octetLength
            val length =
                when (val result = TlvReader.readLength(source, lengthOffset)) {
                    is TlvResult.Failure ->
                        return TlvResult.Failure(contain(result.error, source, index, end, depth))
                    is TlvResult.Success -> result.value
                }

            val valueStart = lengthOffset + length.octetLength
            // Deliberately a subtraction. Written as `valueStart + length.value > end` it would
            // overflow to a negative number for a length near Int.MAX_VALUE and wave the object
            // through. This form cannot overflow, since both terms are bounded by the buffer.
            // It is also the other half of contain(): where that catches a tag or length field
            // straddling `end` on which a reader failed, this catches one on which the readers
            // happened to succeed, because a straddling field pushes valueStart past `end` and
            // makes the right-hand side negative.
            if (length.value > end - valueStart) {
                return TlvResult.Failure(
                    overrun(index, valueStart, length.value, end, depth),
                )
            }
            val valueEnd = valueStart + length.value

            // 80 is primitive despite carrying structured data, so it lands here and stays an
            // opaque leaf. Only bit 6 of the tag decides, never the shape of the value octets.
            val children =
                if (!tag.isConstructed) {
                    emptyList()
                } else {
                    when (val result = parseSequence(source, valueStart, valueEnd, depth + 1)) {
                        is TlvResult.Failure -> return result
                        is TlvResult.Success -> result.value
                    }
                }

            nodes +=
                TlvNode(
                    tag = tag,
                    length = length,
                    value = source.copyOfRange(valueStart, valueEnd),
                    children = children,
                    offset = index,
                )
            index = valueEnd
        }
        return TlvResult.Success(nodes)
    }

    /**
     * Re-reports a reader failure that was detected on an octet outside the parent's value.
     *
     * Such an octet exists in the buffer but belongs to a different data object, so whatever it
     * encodes says nothing about this one: a `80` there is not this object's indefinite-length
     * octet and an `FF` there is not its reserved one. The real fault is that the object does not
     * fit inside its parent, and that is what gets reported.
     *
     * A failure at or past the end of the buffer is left alone. There the octets ran out rather
     * than belonging to someone else, and the truncation the reader found is the more specific
     * truth — which is also what keeps a top-level parse reporting exactly what the readers say.
     */
    private fun contain(
        error: TlvError,
        source: ByteArray,
        offset: Int,
        end: Int,
        depth: Int,
    ): TlvError =
        if (depth > 1 && error.offset >= end && error.offset < source.size) {
            TlvError.ChildOverrunsParent(offset, parentEnd = end)
        } else {
            error
        }

    /**
     * The failure for a value that does not fit in the window: past the end of the buffer at the
     * top level, past the parent's declared value end anywhere below it.
     */
    private fun overrun(
        offset: Int,
        valueStart: Int,
        declaredLength: Int,
        end: Int,
        depth: Int,
    ): TlvError =
        if (depth > 1) {
            TlvError.ChildOverrunsParent(offset, parentEnd = end)
        } else {
            TlvError.TruncatedValue(
                offset = end,
                declaredLength = declaredLength,
                availableOctets = end - valueStart,
            )
        }

    /**
     * The only octet EMV allows as meaningless filler before, between and after data objects
     * (Book 3, Annex B1), typically left by an erased or modified object.
     */
    private const val SKIPPED_FILLER_OCTET: Int = 0x00

    /**
     * ISO/IEC 7816-4 §5.2.2.1 allows `FF` as filler as well, but EMV does not, so this parser
     * reports it rather than skipping it. That is the deliberate choice for an inspection tool:
     * an `FF` where a tag should begin usually means a short read or an erased record, which is
     * the very thing someone is reading a payload to find. See [TlvError.UnexpectedFillerOctet].
     */
    private const val REPORTED_FILLER_OCTET: Int = 0xFF
}
