package io.github.sebkoo.tagscope.encode

import io.github.sebkoo.tagscope.decode.DecodedValue
import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagFormat
import io.github.sebkoo.tagscope.tags.TagLookup
import io.github.sebkoo.tagscope.tlv.TlvTag
import java.io.ByteArrayOutputStream

/**
 * The command data a DOL asks for: each entry's value fitted to the length that entry states,
 * concatenated in DOL order, with no tags and no lengths.
 *
 * A Data Object List is the card's question — a run of (tag, length) requests — and this builds the
 * answer. The answer carries values only: they go back to back into the data field of GET PROCESSING
 * OPTIONS or GENERATE AC, so nothing in the output says where one element ends and the next begins.
 * The card recovers the boundaries by reading its own DOL again, which is why the result is exactly
 * `sum(entry.length)` octets and why every branch below pads or truncates rather than ever skipping
 * an entry. That length invariant is the whole correctness content of §5.4: get one field's width
 * wrong and every field after it is misread.
 *
 * The write-side dual of decoding a DOL, as `TlvEncoder` is of parsing one.
 *
 * ## The rules
 *
 * Per entry, in DOL order:
 *
 * - **No value supplied.** The tag is absent from `data`: the entry contributes its length in `00`
 *   octets. §5.4 states this for a tag the terminal does not know, which is the same situation — a
 *   terminal with no value for an element has nothing else to put there.
 * - **Exactly the right length.** The octets go in as they are, and the dictionary is not consulted.
 * - **Too short, pad.** `n` gains leading `00`; `cn` gains trailing `FF`; every other format gains
 *   trailing `00`.
 * - **Too long, truncate.** `n` loses its leading octets, keeping the rightmost; every other format,
 *   `cn` included, loses its trailing octets, keeping the leftmost.
 *
 * The asymmetry is not a transcription slip. §5.4 names `n` and `cn` in its padding clause but names
 * `n` *alone* in its truncation clause, sending everything else — `cn` among them — to one
 * "otherwise" arm. It agrees with the justification rules in §4.3, which [TagFormat] already
 * transcribes: `n` is right-justified and zero-filled on the left, `cn` and the rest are
 * left-justified. So both directions of both rules add or remove *fill* and never touch a digit
 * while fill remains. A `cn` that truncated leftmost would strip significant digits and leave the
 * `F` padding standing.
 *
 * ## What the dictionary decides, and what it does not
 *
 * The dictionary is consulted for one thing — the format that picks a pad or truncate rule — and so
 * it is consulted only when a length has to change at all. A tag the dictionary does not name is
 * padded and truncated by the "other formats" rules rather than being zero-filled, which is a
 * deliberate reading of §5.4. "Unknown to the terminal" there means the terminal holds no value for
 * the element; this dictionary is a curated subset of EMV, so a tag missing from it is routinely one
 * the caller knows perfectly well — a scheme tag, a proprietary one, a newer one. Zero-filling a
 * value the caller supplied would discard it, and the caller would have no way to ask for it to be
 * kept, whereas a caller who *wants* the zero-fill need only leave the tag out of `data`. The
 * asymmetry settles it: absence, not ignorance, is what fills an entry with zeroes.
 *
 * ## Card data
 *
 * **What [build] returns is card data, and nothing here masks it.** A DOL may name any tag, and
 * values arrive as raw [ByteArray] from the caller rather than through `DecodedValue.Sensitive` — so
 * the guard that covers the decode path does not reach this one, and nothing reaching [build] may be
 * assumed to have been redacted. In one respect the result is worse than a tag tree: it is unlabelled
 * octets, so a PAN inside it cannot be found again by tag afterwards. Treat it as cardholder data —
 * do not log it, do not print it without the explicit opt-in `--reveal` provides elsewhere, and do
 * not commit it as a fixture. Where `LintFinding` promises it never carries a value, this promises
 * the opposite, and says so plainly. Nothing in this package renders octets to text, deliberately:
 * the moment such a helper exists it becomes the unguarded print path.
 *
 * What [build] itself does is narrow, stated so it is not mistaken for more: it copies octets the
 * caller already held, interprets no value, keeps no state, and writes nothing anywhere.
 *
 * ## What this is not
 *
 * This assembles octets; it is not a step of a transaction. It chooses no tags, sources no values,
 * decides nothing, performs no cryptography, and sends no APDU — the caller does all of that. It
 * builds the command *data field* only, never the APDU around it: no CLA/INS header, no `Lc`, no
 * `Le`.
 *
 * EMV Book 3 v4.4, §5.4, Rules for Using a Data Object List (DOL).
 */
public object DolEncoder {
    /**
     * The most octets [build] will assemble: a bound on the DOL, not a rule of §5.4.
     *
     * A command data field is carried in an APDU whose short-form `Lc` is a single octet, so 255 is
     * what a command can hold — some five times the largest DOL a card really sends, and no genuine
     * input comes near it. A DOL entry's length, by contrast, is a BER-TLV length and reaches
     * [Int.MAX_VALUE]; see [EncodeError.CommandDataTooLong] for why that gap is refused rather than
     * attempted.
     *
     * ISO/IEC 7816-4 §5.1, for the short-form `Lc` this is taken from.
     */
    public const val MAX_COMMAND_DATA_OCTETS: Int = 255

    /** Pad octet for `n` and for every format §5.4 does not name; also the fill for an absent value. */
    private const val ZERO_FILL: Byte = 0x00

    /** Pad octet for `cn`: §4.3 pads a compressed numeric field with trailing `F` nibbles. */
    private const val COMPRESSED_NUMERIC_FILL: Byte = 0xFF.toByte()

    /**
     * The octets [dol] asks for, drawn from [data], in DOL order.
     *
     * [data] is a pool the entries draw from, not a queue: its value is read afresh for each entry
     * that names it, so a DOL naming a tag twice — which the `dol-entries` lint rule reports —
     * fills both entries from the same octets, each fitted to its own length, which the two entries
     * need not share. An entry of length nought contributes nothing. An entry whose tag is not in
     * [data] contributes its length in `00`. A tag in [data] that no entry names is ignored, in
     * silence: [data] may be a whole terminal's parameters, and the DOL selects from it.
     *
     * A value present but *empty* is padded like any other, not treated as absent — so an empty `cn`
     * value becomes a field of all `FF`, which is the correct representation of no digits, where an
     * absent one would have become zeroes. The map distinguishes the two cases and so does this.
     *
     * On success the result is exactly the sum of the entries' lengths, and empty for a DOL with no
     * entries. Lookup is by exact [TlvTag] equality, identifier octets and all, so a tag written with
     * a leading `00` is a different key from the same tag without one; the parser never produces the
     * former, and normalising here would contradict why [TlvTag] records its width. Neither [data]
     * nor any array in it is modified or retained, and the result aliases nothing the caller passed.
     *
     * Failure is returned, not thrown, the same as everywhere else in this library: see
     * [EncodeError] for the two things a well-formed DOL can still ask for that no command could
     * carry. Both are checked before a single octet is allocated.
     */
    public fun build(
        dol: DecodedValue.Dol,
        data: Map<TlvTag, ByteArray>,
    ): EncodeResult {
        val problem = firstProblem(dol.entries)
        if (problem != null) {
            return EncodeResult.Failure(problem)
        }
        // Sized exactly, which the check above makes safe: the total is now known to be non-negative
        // and at most MAX_COMMAND_DATA_OCTETS, so the overflow that stops TlvEncoder hinting a
        // capacity cannot arise here.
        val out = ByteArrayOutputStream(dol.entries.sumOf { it.length })
        for (entry in dol.entries) {
            val value = data[entry.tag]
            val octets =
                when {
                    value == null -> zeroes(entry.length)
                    // Handled here as well as inside fitToLength so the dictionary is consulted only
                    // when its answer could change the output. writeBytes copies, so handing the
                    // caller's own array straight through cannot let the result alias it.
                    value.size == entry.length -> value
                    else -> fitToLength(value, entry.length, formatOf(entry.tag))
                }
            out.writeBytes(octets)
        }
        return EncodeResult.Success(out.toByteArray())
    }

    /**
     * [value] as exactly [length] octets, padded or truncated by the rule [format] selects.
     *
     * Internal rather than private because this table is the whole of §5.4 and a test should reach it
     * directly. Driven only through [build] it would be reachable just for the formats the dictionary
     * happens to assign, and the dictionary's only two `cn` tags are `5A` (PAN) and `9F20` (Track 2
     * Discretionary Data), both marked sensitive — so testing which octet fills a gap would need
     * PAN-shaped and track-shaped fixtures, in a repository that holds neither, to exercise a rule
     * that has nothing to do with either. Taking a [TagFormat] rather than a [TlvTag] keeps the rules
     * independent of what the dictionary happens to contain and lets a test enumerate every format.
     *
     * A `null` [format] is a tag the dictionary does not name, and lands in the "otherwise" arm along
     * with every format §5.4 does not single out. The `else` branches below *are* that arm and are
     * load-bearing rather than an unfinished `when`: a format added to [TagFormat] later joins them,
     * which is what §5.4 asks for.
     *
     * EMV Book 3 v4.4, §5.4, Rules for Using a Data Object List (DOL).
     */
    internal fun fitToLength(
        value: ByteArray,
        length: Int,
        format: TagFormat?,
    ): ByteArray =
        when {
            // copyOf, not value: an array returned from here must not alias the caller's, which may
            // be mutated afterwards or held in a map reused across builds.
            value.size == length -> value.copyOf()
            value.size < length -> pad(value, length, format)
            else -> truncate(value, length, format)
        }

    /**
     * [value] widened to [length] octets: leading `00` for `n`, trailing `FF` for `cn`, trailing `00`
     * for anything else.
     *
     * Two independent decisions, one line each — which octet fills, and which end the value sits at.
     * `n` is the only right-justified format, so it is the only one whose octets go at the end and
     * the only one whose fill is leading. An empty [value] pads like any other, which is how a `cn`
     * element the caller holds no digits for becomes a field of all `FF`.
     */
    private fun pad(
        value: ByteArray,
        length: Int,
        format: TagFormat?,
    ): ByteArray {
        val fill = if (format == TagFormat.COMPRESSED_NUMERIC) COMPRESSED_NUMERIC_FILL else ZERO_FILL
        val padded = ByteArray(length) { fill }
        val destinationOffset = if (format == TagFormat.NUMERIC) length - value.size else 0
        value.copyInto(padded, destinationOffset = destinationOffset)
        return padded
    }

    /**
     * [value] narrowed to [length] octets: the rightmost for `n`, the leftmost for everything else,
     * `cn` included.
     *
     * §5.4 names `n` alone in its truncation clause, which agrees with the justifications: dropping
     * the leading octets of a right-justified `n` drops its leading zeroes, and dropping the trailing
     * octets of a left-justified `cn` drops its `F` pad. Truncating a value longer than its own fill
     * loses digits, and that is the caller's to avoid — the entry's length is the card's demand, and
     * the field cannot be widened to suit.
     */
    private fun truncate(
        value: ByteArray,
        length: Int,
        format: TagFormat?,
    ): ByteArray =
        if (format == TagFormat.NUMERIC) {
            value.copyOfRange(value.size - length, value.size)
        } else {
            value.copyOfRange(0, length)
        }

    /** [length] octets of `00`: what an entry contributes when [build] was given no value for it. */
    private fun zeroes(length: Int): ByteArray = ByteArray(length) { ZERO_FILL }

    /**
     * The format the dictionary gives [tag], or `null` when this build does not name the tag.
     *
     * `null` is the "otherwise" arm of §5.4 and not an error; see the class KDoc on why a tag the
     * dictionary does not name is still padded and truncated rather than zero-filled.
     */
    private fun formatOf(tag: TlvTag): TagFormat? = (TagDictionary.lookup(tag) as? TagLookup.Known)?.info?.format

    /**
     * The first thing about [entries] that makes them unanswerable, or `null` when they are fine.
     *
     * Both conditions are properties of the entries rather than rules of §5.4, and both are settled
     * before a single octet is allocated. The running total is accumulated in a [Long] because that
     * is the whole point of the check: two entries of `0x40000000` sum to [Int.MIN_VALUE] in an
     * `Int`, so a guard written against an oversized allocation would itself pass and the allocation
     * proceed. The total is tested inside the loop rather than after it, so the entry named is the
     * one that crossed the bound rather than merely the last one asked for.
     */
    private fun firstProblem(entries: List<DecodedValue.Dol.Entry>): EncodeError? {
        var total = 0L
        for (entry in entries) {
            if (entry.length < 0) {
                return EncodeError.NegativeEntryLength(entry.tag, entry.length)
            }
            total += entry.length
            if (total > MAX_COMMAND_DATA_OCTETS) {
                return EncodeError.CommandDataTooLong(entry.tag, total, MAX_COMMAND_DATA_OCTETS)
            }
        }
        return null
    }
}
