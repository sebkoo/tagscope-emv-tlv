package io.github.sebkoo.tagscope.tags

import io.github.sebkoo.tagscope.tags.TagFormat.ALPHANUMERIC
import io.github.sebkoo.tagscope.tags.TagFormat.ALPHANUMERIC_SPECIAL
import io.github.sebkoo.tagscope.tags.TagFormat.BINARY
import io.github.sebkoo.tagscope.tags.TagFormat.COMPRESSED_NUMERIC
import io.github.sebkoo.tagscope.tags.TagFormat.NUMERIC
import io.github.sebkoo.tagscope.tags.TagFormat.VAR
import io.github.sebkoo.tagscope.tlv.TagClass
import io.github.sebkoo.tagscope.tlv.TagClass.APPLICATION
import io.github.sebkoo.tagscope.tlv.TagClass.CONTEXT_SPECIFIC
import io.github.sebkoo.tagscope.tlv.TlvParser
import io.github.sebkoo.tagscope.tlv.TlvReader
import io.github.sebkoo.tagscope.tlv.TlvTag
import io.github.sebkoo.tagscope.tlv.expectSuccess
import io.github.sebkoo.tagscope.tlv.hex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * The dictionary is data, so the suite is data too: [EXPECTED] is written out by hand from EMV
 * Book 3, Annex A, and the production table has to agree with it row for row. Neither is derived
 * from the other, which is the only way a table of facts can be tested at all.
 *
 * Definitions only. Nothing here is card data.
 */
class TagDictionaryTest {
    @TestFactory
    fun `every tag resolves to what the specification says about it`(): List<DynamicTest> =
        EXPECTED.map { expected ->
            dynamicTest(expected.hex) {
                val info = known(expected.hex)

                assertEquals(expected.name, info.name, "name")
                assertEquals(expected.tagClass, info.tagClass, "class")
                assertEquals(expected.constructed, info.isConstructed, "constructed")
                assertEquals(expected.format, info.format, "format")
                assertEquals(expected.min, info.minLength, "minLength")
                assertEquals(expected.max, info.maxLength, "maxLength")
                assertEquals(expected.sensitive, info.isSensitive, "sensitive")
                assertEquals(EXPECTED_NOTES[expected.hex].orEmpty(), info.note, "note")
            }
        }

    @Test
    fun `the dictionary holds exactly the tags this suite lists`() {
        val actual = TagDictionary.entries.map { it.tag.hex }

        // Sets, so an entry added to one table and not the other is named rather than counted.
        assertEquals(EXPECTED.map { it.hex }.toSet(), actual.toSet())
        assertEquals(EXPECTED.size, TagDictionary.entries.size)
        // A note left behind for a tag that no longer exists would otherwise never be looked at.
        assertEquals(emptySet<String>(), EXPECTED_NOTES.keys - actual.toSet())
    }

    @Test
    fun `no tag is listed twice`() {
        val tags = TagDictionary.entries.map { it.tag }

        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `no two entries share a name`() {
        val names = TagDictionary.entries.map { it.name }

        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun `entries are in tag order`() {
        // Octet order, which is what sorting the hex gives and what Annex A2 itself is in: 5F34
        // comes before 6F because 5F is below 6F, even though 0x5F34 is the larger number.
        val order = TagDictionary.entries.map { it.tag.hex }

        assertEquals(order.sorted(), order)
    }

    @Test
    fun `a tag the dictionary does not hold is unknown, never guessed`() {
        // Proprietary and issuer-specific objects are ordinary in real card data. Each second
        // octet here is below 0x80, since bit 8 set would promise a third octet and these have to
        // be whole tags before they can be unknown ones.
        for (text in listOf("9F0C", "DF01", "5F50", "C1")) {
            val tag = tagOf(text)

            assertEquals(TagLookup.Unknown(tag), TagDictionary.lookup(tag))
        }
    }

    @Test
    fun `a tag with the right value but the wrong octet count is unknown`() {
        // TlvReader cannot produce this, but the type can hold it, and 005A is not 5A.
        val twoOctet = TlvTag(value = 0x5A, octetLength = 2)

        assertEquals(TagLookup.Unknown(twoOctet), TagDictionary.lookup(twoOctet))
        assertTrue(TagDictionary.lookup(tagOf("5A")) is TagLookup.Known)
    }

    @Test
    fun `the PAN and Track 2 are the only sensitive entries`() {
        val sensitive = TagDictionary.entries.filter { it.isSensitive }.map { it.tag.hex }

        assertEquals(listOf("57", "5A"), sensitive.sorted())
    }

    @Test
    fun `var means variable length rather than constructed, so two primitives carry it as well`() {
        val varFormatted = TagDictionary.entries.filter { it.format == VAR }

        // Annex A1 prints var. for all six: the templates 6F, 70, 77 and A5, and the primitives
        // 80, a run of values carrying no tags, and 94, a run of four-octet entries. Book 3 §4.3
        // defines var. as variable length holding any bit combination, so a primitive carrying it
        // is ordinary rather than an anomaly. The DOLs are variable-length runs too, but Annex A1
        // prints b for them, so they stay b here.
        assertEquals(listOf("6F", "70", "77", "80", "94", "A5"), varFormatted.map { it.tag.hex }.sorted())
        // The converse does hold, and is worth pinning: nothing constructed is formatted anything
        // but var. Read off the tag, never off this column.
        for (info in TagDictionary.entries.filter { it.isConstructed }) {
            assertEquals(VAR, info.format, info.tag.hex)
        }
    }

    @Test
    fun `every length bound is ordered and never negative`() {
        for (info in TagDictionary.entries) {
            assertTrue(info.minLength >= 0, "${info.tag.hex} minLength ${info.minLength}")
            assertTrue(info.minLength <= info.maxLength, "${info.tag.hex} bounds are inverted")
        }
    }

    @Test
    fun `the advisory bounds never contradict the parser`() {
        // 6F 00 is an empty template: well-formed BER-TLV, and the parser accepts it. The bounds
        // are reference data, not a gate, so nothing in the dictionary may call this too short.
        val empty = TlvParser.parse(hex("6F00")).expectSuccess().single()
        val info = known("6F")

        assertEquals(0, empty.length.value)
        assertTrue(empty.length.value >= info.minLength, "6F minLength would reject an empty template")
    }

    @Test
    fun `a note is either absent or worth reading`() {
        for (info in TagDictionary.entries.filter { it.note.isNotEmpty() }) {
            assertTrue(info.note.isNotBlank(), "${info.tag.hex} has a blank note")
            assertTrue(info.note.length <= MAX_NOTE_LENGTH, "${info.tag.hex} has a note of an essay")
        }
    }

    @Test
    fun `the entry list cannot be modified`() {
        val castBack = TagDictionary.entries as MutableList<TagInfo>
        val first = TagDictionary.entries.first()

        // set(), not add() or clear(): the list listOf hands back already refuses to grow or
        // shrink, and would pass this test with the unmodifiable wrapper deleted. Replacing an
        // element in place is the hole the wrapper is there to close.
        assertThrows<UnsupportedOperationException> { castBack[0] = castBack[1] }
        assertThrows<UnsupportedOperationException> { castBack.clear() }
        assertEquals(EXPECTED.size, TagDictionary.entries.size)
        assertEquals(first, TagDictionary.entries.first())
    }

    /** The entry for [text], failing the test rather than returning something absent. */
    private fun known(text: String): TagInfo =
        when (val lookup = TagDictionary.lookup(tagOf(text))) {
            is TagLookup.Known -> lookup.info
            is TagLookup.Unknown -> fail("expected $text to be in the dictionary")
        }
}

/**
 * The tag those octets denote, read by [TlvReader] rather than assembled here.
 *
 * That is deliberate: it keeps the expected key independent of however the dictionary builds its
 * own, and it also pins that every key in the table is a tag the reader can actually produce.
 */
private fun tagOf(text: String): TlvTag = TlvReader.readTag(hex(text), 0).expectSuccess()

/** No note needs more room than this; a longer one belongs in KDoc. */
private const val MAX_NOTE_LENGTH: Int = 40

/**
 * The note expected on each entry that carries one, so the column a reader is most likely to trust
 * is checked and not merely measured. An entry absent from this map is expected to have no note.
 */
private val EXPECTED_NOTES: Map<String, String> =
    mapOf(
        "4F" to "the card's AID",
        "50" to "letters, digits and space",
        "57" to "a D nibble follows the PAN",
        "5A" to "F-padded",
        "5F24" to "YYMMDD",
        "5F25" to "YYMMDD",
        "5F28" to "ISO 3166 numeric",
        "5F2A" to "ISO 4217 numeric",
        "5F2D" to "ISO 639 codes, most preferred first",
        "6F" to "returned by SELECT",
        "77" to "data objects with their tags",
        "80" to "values only, no tags or lengths",
        "82" to "bit field",
        "87" to "bit field",
        "88" to "1 to 30, three high bits zero",
        "8C" to "a DOL",
        "8D" to "a DOL",
        "8E" to "amounts, then CV rules",
        "94" to "four-octet entries",
        "95" to "bit field",
        "9A" to "YYMMDD",
        "9F03" to "a cashback amount",
        "9F07" to "bit field",
        "9F0D" to "bit field, shaped like the TVR",
        "9F0E" to "bit field, shaped like the TVR",
        "9F0F" to "bit field, shaped like the TVR",
        "9F10" to "opaque here, never interpreted",
        "9F1A" to "ISO 3166 numeric",
        "9F26" to "opaque here, never interpreted",
        "9F27" to "bit field",
        "9F34" to "method, condition, result",
        "9F38" to "a DOL",
        "9F42" to "ISO 4217 numeric",
    )

/** One expected row, written from the specification rather than read from the dictionary. */
private class Expected(
    val hex: String,
    val name: String,
    val tagClass: TagClass,
    val constructed: Boolean,
    val format: TagFormat,
    val min: Int,
    val max: Int,
    val sensitive: Boolean = false,
)

private val EXPECTED: List<Expected> =
    listOf(
        Expected("4F", "Application Dedicated File (ADF) Name", APPLICATION, false, BINARY, 5, 16),
        Expected("50", "Application Label", APPLICATION, false, ALPHANUMERIC_SPECIAL, 1, 16),
        Expected("57", "Track 2 Equivalent Data", APPLICATION, false, BINARY, 0, 19, true),
        Expected("5A", "Application Primary Account Number (PAN)", APPLICATION, false, COMPRESSED_NUMERIC, 0, 10, true),
        Expected("5F20", "Cardholder Name", APPLICATION, false, ALPHANUMERIC_SPECIAL, 2, 26),
        Expected("5F24", "Application Expiration Date", APPLICATION, false, NUMERIC, 3, 3),
        Expected("5F25", "Application Effective Date", APPLICATION, false, NUMERIC, 3, 3),
        Expected("5F28", "Issuer Country Code", APPLICATION, false, NUMERIC, 2, 2),
        Expected("5F2A", "Transaction Currency Code", APPLICATION, false, NUMERIC, 2, 2),
        Expected("5F2D", "Language Preference", APPLICATION, false, ALPHANUMERIC, 2, 8),
        Expected("5F30", "Service Code", APPLICATION, false, NUMERIC, 2, 2),
        Expected("5F34", "Application Primary Account Number (PAN) Sequence Number", APPLICATION, false, NUMERIC, 1, 1),
        Expected("6F", "File Control Information (FCI) Template", APPLICATION, true, VAR, 0, 252),
        Expected("70", "READ RECORD Response Message Template", APPLICATION, true, VAR, 0, 252),
        Expected("77", "Response Message Template Format 2", APPLICATION, true, VAR, 0, 252),
        Expected("80", "Response Message Template Format 1", CONTEXT_SPECIFIC, false, VAR, 0, 252),
        Expected("82", "Application Interchange Profile", CONTEXT_SPECIFIC, false, BINARY, 2, 2),
        Expected("84", "Dedicated File (DF) Name", CONTEXT_SPECIFIC, false, BINARY, 5, 16),
        Expected("87", "Application Priority Indicator", CONTEXT_SPECIFIC, false, BINARY, 1, 1),
        Expected("88", "Short File Identifier (SFI)", CONTEXT_SPECIFIC, false, BINARY, 1, 1),
        Expected("8C", "Card Risk Management Data Object List 1 (CDOL1)", CONTEXT_SPECIFIC, false, BINARY, 0, 252),
        Expected("8D", "Card Risk Management Data Object List 2 (CDOL2)", CONTEXT_SPECIFIC, false, BINARY, 0, 252),
        Expected("8E", "Cardholder Verification Method (CVM) List", CONTEXT_SPECIFIC, false, BINARY, 10, 252),
        Expected("94", "Application File Locator (AFL)", CONTEXT_SPECIFIC, false, VAR, 0, 252),
        Expected("95", "Terminal Verification Results", CONTEXT_SPECIFIC, false, BINARY, 5, 5),
        Expected("9A", "Transaction Date", CONTEXT_SPECIFIC, false, NUMERIC, 3, 3),
        Expected("9C", "Transaction Type", CONTEXT_SPECIFIC, false, NUMERIC, 1, 1),
        Expected("9F02", "Amount, Authorised (Numeric)", CONTEXT_SPECIFIC, false, NUMERIC, 6, 6),
        Expected("9F03", "Amount, Other (Numeric)", CONTEXT_SPECIFIC, false, NUMERIC, 6, 6),
        Expected("9F07", "Application Usage Control", CONTEXT_SPECIFIC, false, BINARY, 2, 2),
        Expected("9F0D", "Issuer Action Code - Default", CONTEXT_SPECIFIC, false, BINARY, 5, 5),
        Expected("9F0E", "Issuer Action Code - Denial", CONTEXT_SPECIFIC, false, BINARY, 5, 5),
        Expected("9F0F", "Issuer Action Code - Online", CONTEXT_SPECIFIC, false, BINARY, 5, 5),
        Expected("9F10", "Issuer Application Data", CONTEXT_SPECIFIC, false, BINARY, 0, 32),
        Expected("9F1A", "Terminal Country Code", CONTEXT_SPECIFIC, false, NUMERIC, 2, 2),
        Expected("9F26", "Application Cryptogram", CONTEXT_SPECIFIC, false, BINARY, 8, 8),
        Expected("9F27", "Cryptogram Information Data", CONTEXT_SPECIFIC, false, BINARY, 1, 1),
        Expected("9F34", "Cardholder Verification Method (CVM) Results", CONTEXT_SPECIFIC, false, BINARY, 3, 3),
        Expected("9F36", "Application Transaction Counter (ATC)", CONTEXT_SPECIFIC, false, BINARY, 2, 2),
        Expected("9F37", "Unpredictable Number", CONTEXT_SPECIFIC, false, BINARY, 4, 4),
        Expected("9F38", "Processing Options Data Object List (PDOL)", CONTEXT_SPECIFIC, false, BINARY, 0, 252),
        Expected("9F42", "Application Currency Code", CONTEXT_SPECIFIC, false, NUMERIC, 2, 2),
        Expected("A5", "File Control Information (FCI) Proprietary Template", CONTEXT_SPECIFIC, true, VAR, 0, 252),
    )
