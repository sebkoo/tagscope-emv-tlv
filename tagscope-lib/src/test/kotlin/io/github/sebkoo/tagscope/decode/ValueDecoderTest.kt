package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.Amount
import io.github.sebkoo.tagscope.decode.DecodedValue.Constructed
import io.github.sebkoo.tagscope.decode.DecodedValue.Date
import io.github.sebkoo.tagscope.decode.DecodedValue.Digits
import io.github.sebkoo.tagscope.decode.DecodedValue.RawBinary
import io.github.sebkoo.tagscope.decode.DecodedValue.Sensitive
import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagFormat
import io.github.sebkoo.tagscope.tags.TagInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

/**
 * How the decoder chooses what to do, and what it refuses to let out.
 *
 * The per-format cases live in the sibling suites; what is pinned here is the dispatch itself,
 * the masking of cardholder data, and the two facts the error design leans on — that only the
 * three date tags produce a date, and that no sensitive tag has a format whose errors carry
 * anything off the card.
 */
class ValueDecoderTest {
    @Test
    fun `a constructed object has no scalar value, whatever its children are`() {
        assertEquals(Constructed, decode("6F03880101").expectValue())
        assertEquals(Constructed, decode("A503880101").expectValue())
        assertEquals(Constructed, decode("7003880101").expectValue())
        assertEquals(Constructed, decode("7703880101").expectValue())
    }

    @TestFactory
    fun `every constructed entry in the dictionary decodes to Constructed`(): List<DynamicTest> =
        TagDictionary.entries.filter { it.isConstructed }.map { info ->
            dynamicTest(info.tag.hex) {
                // An empty template: well-formed, and enough to show the format was never consulted.
                assertEquals(Constructed, decode(info.tag.hex + "00").expectValue())
            }
        }

    @Test
    fun `a primitive var is octets, since var says variable length and not structure`() {
        // 80 and 94 are primitive despite carrying structured data. Reading that structure is
        // not this commit's business; handing back the octets faithfully is.
        assertEquals(RawBinary(byteArrayOf(0x08, 0x01, 0x01, 0x00)), decode("940408010100").expectValue())
        assertEquals(RawBinary(byteArrayOf(0x00, 0x01, 0x02, 0x03)), decode("800400010203").expectValue())
    }

    @Test
    fun `a cryptogram is handed back as octets and is not interpreted`() {
        val cryptogram = decode("9F26080123456789ABCDEF").expectValue()
        val issuerData = decode("9F1004AABBCCDD").expectValue()

        assertEquals(RawBinary("0123456789ABCDEF".octets()), cryptogram)
        assertEquals(RawBinary("AABBCCDD".octets()), issuerData)
    }

    @Test
    fun `the PAN comes back wrapped, and the digits are only reachable by asking`() {
        val pan = decode("5A081234567890123FFF").expectValue()

        assertTrue(pan is Sensitive, "the PAN must not come back bare")
        assertEquals(Digits("1234567890123"), (pan as Sensitive).reveal())
    }

    @Test
    fun `track 2 is wrapped as well, though it is only octets here`() {
        val track2 = decode("570411112222").expectValue()

        assertTrue(track2 is Sensitive)
        assertEquals(RawBinary("11112222".octets()), (track2 as Sensitive).reveal())
    }

    @Test
    fun `a wrapped value does not print what it holds`() {
        val wrapped = Sensitive(Digits("1234567890123"))

        assertEquals("Sensitive(redacted)", wrapped.toString())
        assertFalse(wrapped.toString().contains("123"), "the payload leaked through toString")
        // The result type wraps it in turn, so the same holds of whatever a caller logs.
        assertFalse(DecodeResult.Success(wrapped).toString().contains("123"))
    }

    @Test
    fun `octets do not print themselves either`() {
        assertEquals("RawBinary(4 octets)", RawBinary("11112222".octets()).toString())
    }

    @Test
    fun `a wrapped value is not wrapped twice`() {
        assertThrows<IllegalArgumentException> { Sensitive(Sensitive(Digits("1"))) }
    }

    @Test
    fun `wrapped values compare by what they hold`() {
        assertEquals(Sensitive(Digits("12")), Sensitive(Digits("12")))
        assertEquals(Sensitive(Digits("12")).hashCode(), Sensitive(Digits("12")).hashCode())
        assertNotEquals(Sensitive(Digits("12")), Sensitive(Digits("13")))
        assertFalse(Sensitive(Digits("12")).equals(Digits("12")), "a wrapper is not what it wraps")
    }

    @Test
    fun `an ordinary tag is not wrapped`() {
        assertFalse(decode("9F0206000000012345").expectValue() is Sensitive)
        assertFalse(decode("9A03260723").expectValue() is Sensitive)
    }

    @Test
    fun `octets are copied out, so a caller cannot reach into a decoded value`() {
        val binary = decode("9F26080123456789ABCDEF").expectValue() as RawBinary
        val first = binary.bytes()

        first[0] = 0x7F

        assertEquals(0x01, binary.bytes()[0].toInt())
    }

    @Test
    fun `octets compare by content and not by array identity`() {
        assertEquals(RawBinary("AABB".octets()), RawBinary("AABB".octets()))
        assertEquals(RawBinary("AABB".octets()).hashCode(), RawBinary("AABB".octets()).hashCode())
        assertNotEquals(RawBinary("AABB".octets()), RawBinary("AABC".octets()))
    }

    @Test
    fun `decoding a node against another tag's entry is a caller mistake, not a decode failure`() {
        val pan = node("5A081234567890123FFF")
        val elsewhere = TagDictionary.entries.first { it.tag.hex == "9F02" }

        assertThrows<IllegalArgumentException> { ValueDecoder.decode(pan, elsewhere) }
    }

    @TestFactory
    fun `only the three YYMMDD tags decode as dates and only the two amounts as amounts`(): List<DynamicTest> =
        TagDictionary.entries.filter { it.format == TagFormat.NUMERIC }.map { info ->
            dynamicTest(info.tag.hex) {
                // A value of 01 octets is a valid date, a valid amount and valid digits alike, so
                // what comes back is decided by the tag and by nothing else about the input.
                val decoded = decode(info.tag.hex + "%02X".format(info.minLength) + "01".repeat(info.minLength))

                when (info.tag.hex) {
                    "5F24", "5F25", "9A" -> assertEquals(Date(1, 1, 1), decoded.expectValue())
                    "9F02", "9F03" -> assertEquals(Amount(10_101_010_101), decoded.expectValue())
                    else -> assertTrue(decoded.expectValue() is Digits, "${info.tag.hex} is plain digits")
                }
            }
        }

    @Test
    fun `no sensitive entry has a format whose failures would carry its octets`() {
        // What DecodeError relies on: UnexpectedCharacter carries an octet and the date variants
        // carry their numbers, which is only safe while nothing sensitive is text or a date. If
        // this fails, that reasoning has to be redone before the entry is added.
        val formats =
            TagDictionary.entries
                .filter { it.isSensitive }
                .map { it.format }
                .toSet()

        assertEquals(setOf(TagFormat.BINARY, TagFormat.COMPRESSED_NUMERIC), formats)
    }

    @Test
    fun `the advisory length bounds are not consulted, either way`() {
        // 5F2A is two octets in the dictionary. One below and one above both decode: the bounds
        // say what to expect, and the decoder reads what is there.
        assertEquals(Digits("08"), decode("5F2A0108").expectValue())
        assertEquals(Digits("082612"), decode("5F2A03082612").expectValue())
    }

    @Test
    fun `an unknown format combination still comes back as octets rather than nothing`() {
        // A hand-made entry, to show the dispatch has no hole: BINARY is the catch-all and every
        // primitive lands somewhere.
        val node = node("9F3704ABCDEF01")
        val info = TagInfo(node.tag, "Unpredictable Number", TagFormat.BINARY, 4, 4)

        assertEquals(RawBinary("ABCDEF01".octets()), ValueDecoder.decode(node, info).expectValue())
    }
}

/** The octets a hex string denotes, for expectations written the way the input is. */
private fun String.octets(): ByteArray =
    ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte() }
