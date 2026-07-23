package io.github.sebkoo.tagscope.tags

/**
 * How the value octets of a data object are coded, as the Format column of the EMV Data Elements
 * Dictionary states it.
 *
 * A format says how to read the octets, not what they mean. Nothing here decodes anything, and for
 * [BINARY] there is often nothing to decode at all: a cryptogram is opaque to this library by
 * design.
 *
 * EMV Book 3, Annex A.
 *
 * @property symbol the abbreviation the specification prints, for example `cn`.
 */
public enum class TagFormat(
    public val symbol: String,
) {
    /** `n` — numeric. One decimal digit per nibble, right-justified and padded with leading zeroes. */
    NUMERIC("n"),

    /** `cn` — compressed numeric. One decimal digit per nibble, left-justified and padded with `F`. */
    COMPRESSED_NUMERIC("cn"),

    /** `an` — alphanumeric. One character per octet, letters and digits. */
    ALPHANUMERIC("an"),

    /** `ans` — alphanumeric special. One character per octet, [ALPHANUMERIC] widened to punctuation. */
    ALPHANUMERIC_SPECIAL("ans"),

    /** `b` — binary. The octets are a number, a bit field, or data this library leaves opaque. */
    BINARY("b"),

    /**
     * `var.` — the value is not one scalar.
     *
     * Every constructed template is this. So is `80`, whose value is a run of values carrying no
     * tags or lengths of their own, and so is `94`, a run of four-octet entries.
     *
     * The specification is not uniform about this and neither is this table, deliberately: the
     * DOLs `8C`, `8D` and `9F38` are runs of tag-and-length pairs and would fit the description,
     * but Annex A prints `b` for them, so this dictionary says `b` too. The Format column is
     * copied, not reasoned about.
     */
    VAR("var."),
}
