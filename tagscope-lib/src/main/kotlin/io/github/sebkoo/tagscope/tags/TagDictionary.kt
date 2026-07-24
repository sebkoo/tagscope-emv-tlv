package io.github.sebkoo.tagscope.tags

import io.github.sebkoo.tagscope.tags.TagFormat.ALPHANUMERIC
import io.github.sebkoo.tagscope.tags.TagFormat.ALPHANUMERIC_SPECIAL
import io.github.sebkoo.tagscope.tags.TagFormat.BINARY
import io.github.sebkoo.tagscope.tags.TagFormat.COMPRESSED_NUMERIC
import io.github.sebkoo.tagscope.tags.TagFormat.NUMERIC
import io.github.sebkoo.tagscope.tags.TagFormat.VAR
import io.github.sebkoo.tagscope.tlv.TlvTag
import java.util.Collections

/**
 * What EMV calls each of the data objects Tagscope knows about.
 *
 * A standalone lookup. It holds no parser state and the parser holds no dictionary: `TlvParser`
 * reads structure and never asks what a tag is called, and whatever later renders or decodes a
 * tree asks here, by tag. The dependency runs one way only.
 *
 * The table is Kotlin rather than a JSON or CSV resource on purpose. The data is fixed at build
 * time, so a resource file would buy nothing and cost either a JSON dependency, which this
 * library does not have and does not want, or a hand-rolled reader with its own malformed-input
 * tests — a parser for something that never changes. In source it is checked by the compiler and
 * read with no I/O at all.
 *
 * Definitions only: no card data and no example values.
 *
 * Sources, cited rather than copied. EMV Book 3, Annex A, the Data Elements Dictionary — A1 by
 * name, A2 by tag — for the names, formats and lengths. EMV Book 1 for the objects belonging to
 * application selection: `4F`, `50`, `84`, `6F`, `A5` and `70`. The public emvlab.org and EFTlab
 * tag lists as a second opinion on the format column.
 */
public object TagDictionary {
    private val table: List<TagInfo> =
        listOf(
            entry("4F", "Application Dedicated File (ADF) Name", BINARY, 5, 16, "the card's AID"),
            entry("50", "Application Label", ALPHANUMERIC_SPECIAL, 1, 16, "letters, digits and space"),
            entry("56", "Track 1 Data", BINARY, 0, 76, "sensitive, so kept opaque like 57", SENSITIVE),
            entry("57", "Track 2 Equivalent Data", BINARY, 0, 19, "a D nibble follows the PAN", SENSITIVE),
            entry("5A", "Application Primary Account Number (PAN)", COMPRESSED_NUMERIC, 0, 10, "F-padded", SENSITIVE),
            entry("5F20", "Cardholder Name", BINARY, 2, 26, "PII, kept opaque so masking is total", SENSITIVE),
            entry("5F24", "Application Expiration Date", NUMERIC, 3, 3, "YYMMDD"),
            entry("5F25", "Application Effective Date", NUMERIC, 3, 3, "YYMMDD"),
            entry("5F28", "Issuer Country Code", NUMERIC, 2, 2, "ISO 3166 numeric"),
            entry("5F2A", "Transaction Currency Code", NUMERIC, 2, 2, "ISO 4217 numeric"),
            entry("5F2D", "Language Preference", ALPHANUMERIC, 2, 8, "ISO 639 codes, most preferred first"),
            entry("5F30", "Service Code", NUMERIC, 2, 2),
            entry("5F34", "Application Primary Account Number (PAN) Sequence Number", NUMERIC, 1, 1),
            entry("6F", "File Control Information (FCI) Template", VAR, 0, 252, "returned by SELECT"),
            entry("70", "READ RECORD Response Message Template", VAR, 0, 252),
            entry("77", "Response Message Template Format 2", VAR, 0, 252, "data objects with their tags"),
            entry("80", "Response Message Template Format 1", VAR, 0, 252, "values only, no tags or lengths"),
            entry("82", "Application Interchange Profile", BINARY, 2, 2, "bit field"),
            entry("84", "Dedicated File (DF) Name", BINARY, 5, 16),
            entry("87", "Application Priority Indicator", BINARY, 1, 1, "bit field"),
            entry("88", "Short File Identifier (SFI)", BINARY, 1, 1, "1 to 30, three high bits zero"),
            entry("8C", "Card Risk Management Data Object List 1 (CDOL1)", BINARY, 0, 252, "a DOL"),
            entry("8D", "Card Risk Management Data Object List 2 (CDOL2)", BINARY, 0, 252, "a DOL"),
            entry("8E", "Cardholder Verification Method (CVM) List", BINARY, 10, 252, "amounts, then CV rules"),
            entry("94", "Application File Locator (AFL)", VAR, 0, 252, "four-octet entries"),
            entry("95", "Terminal Verification Results", BINARY, 5, 5, "bit field"),
            entry("99", "Transaction Personal Identification Number (PIN) Data", BINARY, 0, 252, sensitive = SENSITIVE),
            entry("9A", "Transaction Date", NUMERIC, 3, 3, "YYMMDD"),
            entry("9C", "Transaction Type", NUMERIC, 1, 1),
            entry("9F02", "Amount, Authorised (Numeric)", NUMERIC, 6, 6),
            entry("9F03", "Amount, Other (Numeric)", NUMERIC, 6, 6, "a cashback amount"),
            entry("9F07", "Application Usage Control", BINARY, 2, 2, "bit field"),
            entry("9F0D", "Issuer Action Code - Default", BINARY, 5, 5, "bit field, shaped like the TVR"),
            entry("9F0E", "Issuer Action Code - Denial", BINARY, 5, 5, "bit field, shaped like the TVR"),
            entry("9F0F", "Issuer Action Code - Online", BINARY, 5, 5, "bit field, shaped like the TVR"),
            entry("9F10", "Issuer Application Data", BINARY, 0, 32, "opaque here, never interpreted"),
            entry("9F1A", "Terminal Country Code", NUMERIC, 2, 2, "ISO 3166 numeric"),
            entry("9F1F", "Track 1 Discretionary Data", BINARY, 0, 252, sensitive = SENSITIVE),
            entry("9F20", "Track 2 Discretionary Data", COMPRESSED_NUMERIC, 0, 252, sensitive = SENSITIVE),
            entry("9F26", "Application Cryptogram", BINARY, 8, 8, "opaque here, never interpreted"),
            entry("9F27", "Cryptogram Information Data", BINARY, 1, 1, "bit field"),
            entry("9F33", "Terminal Capabilities", BINARY, 3, 3, "named only, not decoded here"),
            entry("9F34", "Cardholder Verification Method (CVM) Results", BINARY, 3, 3, "method, condition, result"),
            entry("9F35", "Terminal Type", NUMERIC, 1, 1),
            entry("9F36", "Application Transaction Counter (ATC)", BINARY, 2, 2),
            entry("9F37", "Unpredictable Number", BINARY, 4, 4),
            entry("9F38", "Processing Options Data Object List (PDOL)", BINARY, 0, 252, "a DOL"),
            entry("9F40", "Additional Terminal Capabilities", BINARY, 5, 5, "named only, not decoded here"),
            entry("9F42", "Application Currency Code", NUMERIC, 2, 2, "ISO 4217 numeric"),
            entry("9F4A", "Static Data Authentication Tag List", BINARY, 0, 252, "named only, not decoded here"),
            entry("A5", "File Control Information (FCI) Proprietary Template", VAR, 0, 252),
        )

    /**
     * Every data object this dictionary knows, in tag order.
     *
     * Unmodifiable, and wrapped rather than merely built read-only, because Kotlin's `List` and
     * `MutableList` are one type on the JVM and the list `listOf` hands back still accepts `set`.
     */
    public val entries: List<TagInfo> = Collections.unmodifiableList(table)

    private val byTag: Map<TlvTag, TagInfo> = table.associateBy { it.tag }

    init {
        // associateBy keeps the last of a repeated tag and says nothing about the one it dropped.
        require(byTag.size == table.size) { "the dictionary lists a tag more than once" }
    }

    /**
     * What EMV says about [tag], or [TagLookup.Unknown] when this dictionary has no entry for it.
     *
     * The whole tag is the key, its octet count included, so a two-octet `005A` is not the
     * one-octet `5A`. `TlvReader` cannot produce the former — a tag runs into a second octet only
     * when bits 5-1 of the first are all set — but the type can hold it, and answering with the
     * PAN's entry for octets that are not the PAN's tag would be a guess.
     */
    public fun lookup(tag: TlvTag): TagLookup {
        val info = byTag[tag] ?: return TagLookup.Unknown(tag)
        return TagLookup.Known(info)
    }

    /**
     * One row of the table. [max] repeats [min] for a fixed-length object, so every row reads the
     * same way round and no length is quietly defaulted.
     */
    private fun entry(
        tag: String,
        name: String,
        format: TagFormat,
        min: Int,
        max: Int,
        note: String = "",
        sensitive: Boolean = false,
    ): TagInfo =
        TagInfo(
            tag = tagOf(tag),
            name = name,
            format = format,
            minLength = min,
            maxLength = max,
            note = note,
            isSensitive = sensitive,
        )

    /**
     * The tag written the way the specification prints it, `9F26` rather than a packed number and
     * an octet count typed out by hand.
     *
     * Uppercase is required rather than merely expected, so that a row reads the same as
     * [TlvTag.hex] renders it. Nothing downstream would break on a lowercase row — the tag is a
     * number by then — which is exactly why the check lives here and not in a test.
     */
    private fun tagOf(hex: String): TlvTag {
        require(hex.isNotEmpty() && hex.length % 2 == 0) {
            "a tag is written as whole octets, \"$hex\" is not"
        }
        require(hex.all { it in UPPERCASE_HEX_DIGITS }) {
            "a tag is written in uppercase hex, \"$hex\" is not"
        }
        return TlvTag(value = hex.toLong(radix = 16), octetLength = hex.length / 2)
    }

    /** Reads better at the end of a row than a bare `true`. See [TagInfo.isSensitive]. */
    private const val SENSITIVE: Boolean = true

    private const val UPPERCASE_HEX_DIGITS: String = "0123456789ABCDEF"
}
