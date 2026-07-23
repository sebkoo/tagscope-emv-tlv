package io.github.sebkoo.tagscope.tags

import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * The outcome of asking [TagDictionary] about a tag: either what EMV says about it, or a plain
 * statement that this dictionary does not know it.
 *
 * Absence is modelled here rather than as `null`, as it is for a parse failure. An unknown tag is
 * an ordinary answer — proprietary and issuer-specific objects are everywhere in real card data —
 * and [Unknown] carries the tag that was asked for, so a caller can say `DF01 (unknown)` without
 * plumbing it through a second time. No name is ever guessed.
 */
public sealed interface TagLookup {
    /** The dictionary knows this tag, and [info] is what EMV says about it. */
    public data class Known(
        public val info: TagInfo,
    ) : TagLookup

    /** The dictionary has no entry for [tag]. */
    public data class Unknown(
        public val tag: TlvTag,
    ) : TagLookup
}
