package io.github.sebkoo.tagscope.tags

import io.github.sebkoo.tagscope.tlv.TagClass
import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * What EMV says about one data object: what it is called, how its value is coded, and how long
 * that value normally is.
 *
 * This is reference data about a tag. It is not anything read off the wire, and it carries no
 * value octets — see `TlvNode` for those.
 *
 * [minLength] and [maxLength] are **advisory**. They say what the specification states the field
 * normally holds; they are not a validation gate, nothing in this library rejects a data object
 * for falling outside them, and nothing should start to. Two reasons. The specification gives no
 * minimum at all for a variable-length field, so the lower bound here is the structural one,
 * nought: `6F 00`, an empty template, is well-formed BER-TLV and `TlvParser` accepts it. And the
 * upper bound on a template is what a short APDU response can carry rather than anything the
 * encoding forbids, since a long-form length can declare more. `TlvParser` decides what is
 * well-formed; this type only says what to expect.
 *
 * EMV Book 3, Annex A.
 *
 * @property tag the identifier the specification assigns, and the key this object is found under.
 * @property name the name the specification gives, verbatim.
 * @property format how to read the value octets.
 * @property minLength the fewest value octets to expect, or `0` where no minimum is stated.
 * @property maxLength the most value octets to expect.
 * @property note a short remark where the entry alone would mislead. Empty when there is none.
 * @property isSensitive true for cardholder data that is masked in output unless revealing it is
 *   asked for explicitly.
 */
public data class TagInfo(
    public val tag: TlvTag,
    public val name: String,
    public val format: TagFormat,
    public val minLength: Int,
    public val maxLength: Int,
    public val note: String = "",
    public val isSensitive: Boolean = false,
) {
    init {
        // Preconditions on the value object. The dictionary is static data, so a failure here is
        // a mistake in the table, which the test suite catches long before anything is parsed.
        require(name.isNotBlank()) { "a tag needs a name, ${tag.hex} was given a blank one" }
        require(minLength >= 0) { "${tag.hex} has a negative minLength, $minLength" }
        require(minLength <= maxLength) {
            "${tag.hex} has minLength $minLength above maxLength $maxLength"
        }
    }

    /**
     * The tag class, read from the identifier octets rather than stored beside them, so the
     * dictionary cannot contradict the wire.
     */
    public val tagClass: TagClass
        get() = tag.tagClass

    /**
     * True when the value octets are themselves data objects. Read from bit 6 of the first
     * identifier octet, for the same reason as [tagClass]. Note that `80` is primitive despite
     * carrying structured data.
     */
    public val isConstructed: Boolean
        get() = tag.isConstructed
}
