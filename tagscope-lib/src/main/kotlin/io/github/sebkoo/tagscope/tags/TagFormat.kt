package io.github.sebkoo.tagscope.tags

/**
 * How the value octets of a data object are coded, as the Format column of the EMV Data Elements
 * Dictionary states it.
 *
 * A format says how to read the octets, not what they mean. Nothing here decodes anything, and for
 * [BINARY] there is often nothing to decode at all: a cryptogram is opaque to this library by
 * design.
 *
 * EMV Book 3, Annex A for the column itself; Book 3 §4.3, Data Element Format Conventions, for
 * what each letter means.
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

    /** `an` — alphanumeric. One character per octet, letters and digits; §4.3 admits no space. */
    ALPHANUMERIC("an"),

    /**
     * `ans` — alphanumeric special. One character per octet, from the Common Character Set that
     * Book 4, Annex B tabulates, which admits space and punctuation as well as letters and digits.
     */
    ALPHANUMERIC_SPECIAL("ans"),

    /** `b` — binary. The octets are a number, a bit field, or data this library leaves opaque. */
    BINARY("b"),

    /**
     * `var.` — variable length, and any bit combination.
     *
     * That is the whole of it. Book 3 §4.3: "Variable data elements are variable length and may
     * contain any bit combination. Additional information on the formats of specific variable
     * data elements is available elsewhere."
     *
     * So this is a statement about length, and it says nothing about structure. In particular it
     * does not mean constructed. Annex A1 prints `var.` for the templates `6F`, `70`, `77` and
     * `A5`, and equally for two primitives: `80`, whose value is a run of values carrying no tags
     * or lengths of their own, and `94`, a run of four-octet entries. Whether a value holds other
     * data objects is bit 6 of the first identifier octet and nothing else, so nothing may read
     * constructed-ness off this column — see `TlvTag.isConstructed`.
     *
     * The specification is not uniform about which variable-length objects get `var.`, and
     * neither is this table, deliberately: the DOLs `8C`, `8D` and `9F38` are variable-length
     * runs of tag-and-length pairs and would fit the description, but Annex A1 prints `b` for
     * them, so this dictionary says `b` too. The Format column is copied, not reasoned about.
     */
    VAR("var."),
}
