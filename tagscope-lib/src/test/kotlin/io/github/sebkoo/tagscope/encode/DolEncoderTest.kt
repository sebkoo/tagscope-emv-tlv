package io.github.sebkoo.tagscope.encode

import io.github.sebkoo.tagscope.decode.DecodeResult
import io.github.sebkoo.tagscope.decode.DecodedValue
import io.github.sebkoo.tagscope.decode.ValueDecoder
import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagFormat
import io.github.sebkoo.tagscope.tags.TagLookup
import io.github.sebkoo.tagscope.tlv.TlvParser
import io.github.sebkoo.tagscope.tlv.TlvResult
import io.github.sebkoo.tagscope.tlv.TlvTag
import io.github.sebkoo.tagscope.tlv.hex
import io.github.sebkoo.tagscope.tlv.walk
import io.github.sebkoo.tagscope.vectors.loadVectorBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

/**
 * The terminal side: a DOL in, the command data a terminal would send out.
 *
 * Three layers, because the encoder has three distinct things to get right. The [FIT_CASES] table
 * drives `fitToLength` directly and is the whole of Book 3 §5.4 — which octet fills a gap and which
 * end of the value survives, per format. The [BUILD_VECTORS] table drives `build` over the *real*
 * PDOL and CDOL1 of the existing golden vectors and pins the result byte-for-byte against a fixture.
 * The rest are the properties `build` holds whatever the DOL says: the length invariant, what an
 * absent tag contributes, and the two things a well-formed DOL can still ask for that no command
 * could carry.
 *
 * Every value here is synthetic terminal data. A DOL never requests the PAN or Track 2, so no case
 * in this suite needs card data, and none carries any.
 */
class DolEncoderTest {
    @TestFactory
    fun `each format fits a value to the DOL length by its Book 3 5-4 rule`(): List<DynamicNode> =
        FIT_CASES.map { case ->
            dynamicTest(case.name) {
                val format = case.format?.symbol ?: "an unnamed tag"
                assertArrayEquals(
                    hex(case.expected),
                    DolEncoder.fitToLength(hex(case.value), case.length, case.format),
                ) { "${case.name}: ${case.value} as $format, fitted to ${case.length} octets" }
            }
        }

    /**
     * §5.4 names `n` and `cn` and routes every other format to one arm, so each format in the
     * dictionary must land in exactly one of three. Enumerating [TagFormat] rather than listing the
     * formats by hand means a format added later fails here until someone decides which arm it takes.
     */
    @TestFactory
    fun `every dictionary format pads by exactly one of the three rules`(): List<DynamicNode> =
        TagFormat.entries.map { format ->
            dynamicTest(format.symbol) {
                val expected =
                    when (format) {
                        TagFormat.NUMERIC -> "00AA"
                        TagFormat.COMPRESSED_NUMERIC -> "AAFF"
                        else -> "AA00"
                    }
                assertArrayEquals(hex(expected), DolEncoder.fitToLength(hex("AA"), 2, format)) {
                    "format ${format.symbol} padded to the wrong shape"
                }
            }
        }

    @TestFactory
    fun `each build vector produces its command data byte-for-byte`(): List<DynamicNode> =
        BUILD_VECTORS.map { vector ->
            dynamicTest(vector.name) {
                val built = vector.build().expectBytes()
                assertArrayEquals(loadVectorBytes(vector.expectedFile), built) {
                    "${vector.name} does not match ${vector.expectedFile}"
                }
            }
        }

    /**
     * The command data carries no tags and no lengths, so the card recovers each field's boundary by
     * re-walking its own DOL. Splitting the output the way the card would and checking every slice
     * is the round-trip that matters: decode a DOL, build its answer, and read the answer back.
     *
     * The expectations are the hand-written [BuildVector.perEntry] column, not a second call to the
     * encoder, so a rule implemented backwards cannot satisfy both sides of the assertion.
     */
    @TestFactory
    fun `each build vector re-splits by its DOL lengths into the expected fields`(): List<DynamicNode> =
        BUILD_VECTORS.map { vector ->
            dynamicTest(vector.name) {
                val dol = vector.dol()
                val built = vector.build().expectBytes()
                assertEquals(dol.entries.size, vector.perEntry.size) {
                    "${vector.name}: the expectation table does not cover every DOL entry"
                }
                var offset = 0
                dol.entries.forEachIndexed { index, entry ->
                    val slice = built.copyOfRange(offset, offset + entry.length)
                    assertArrayEquals(hex(vector.perEntry[index]), slice) {
                        "${vector.name}: field $index (${entry.tag.hex}, ${entry.length} octets)"
                    }
                    offset += entry.length
                }
                assertEquals(built.size, offset) { "${vector.name}: the fields do not span the output" }
            }
        }

    @TestFactory
    fun `each build vector is exactly the sum of its DOL lengths`(): List<DynamicNode> =
        BUILD_VECTORS.map { vector ->
            dynamicTest(vector.name) {
                val expected = vector.dol().entries.sumOf { it.length }
                assertEquals(expected, (vector.build() as EncodeResult.Success).size) {
                    "${vector.name}: the command data is not the length the DOL asked for"
                }
            }
        }

    @Test
    fun `a tag absent from the terminal data contributes its length in zeroes`() {
        val dol = dolOf("9F02" to 6, "9F37" to 4)
        assertArrayEquals(hex("00000000000000000000"), DolEncoder.build(dol, emptyMap()).expectBytes())
    }

    @Test
    fun `an entry of length nought contributes nothing`() {
        val dol = dolOf("9F02" to 0, "9F37" to 2)
        val data = mapOf(tag("9F02") to hex("123456"), tag("9F37") to hex("AABB"))
        assertArrayEquals(hex("AABB"), DolEncoder.build(dol, data).expectBytes())
    }

    @Test
    fun `a DOL with no entries builds nothing`() {
        assertArrayEquals(ByteArray(0), DolEncoder.build(DecodedValue.Dol(emptyList()), emptyMap()).expectBytes())
    }

    /**
     * The terminal data is a pool the entries draw from, not a queue, so a DOL naming a tag twice —
     * which the `dol-entries` lint rule reports as a warning — fills both entries from the same
     * value, each fitted to its own length. Here the same three digits are padded to six octets and
     * truncated to two.
     */
    @Test
    fun `a DOL naming a tag twice fills both entries from the one value`() {
        val dol = dolOf("9F02" to 6, "9F02" to 2)
        val data = mapOf(tag("9F02") to hex("123456"))
        assertArrayEquals(hex("0000001234563456"), DolEncoder.build(dol, data).expectBytes())
    }

    @Test
    fun `a tag no entry names is ignored`() {
        val dol = dolOf("9F37" to 2)
        val data = mapOf(tag("9F37") to hex("AABB"), tag("9F02") to hex("123456789012"))
        assertArrayEquals(hex("AABB"), DolEncoder.build(dol, data).expectBytes())
    }

    /**
     * A tag the dictionary does not name is still the caller's data. Absence from `data` is what
     * fills an entry with zeroes; absence from the dictionary only decides which pad rule applies,
     * and `9F4C` is not in this build's dictionary.
     */
    @Test
    fun `a value supplied for a tag the dictionary does not name is used, not zeroed`() {
        val dol = dolOf("9F4C" to 8)
        val data = mapOf(tag("9F4C") to hex("0011223344556677"))
        assertArrayEquals(hex("0011223344556677"), DolEncoder.build(dol, data).expectBytes())
    }

    @Test
    fun `a short value for a tag the dictionary does not name pads trailing zeroes`() {
        val dol = dolOf("9F4C" to 4)
        val data = mapOf(tag("9F4C") to hex("0011"))
        assertArrayEquals(hex("00110000"), DolEncoder.build(dol, data).expectBytes())
    }

    /**
     * The dictionary marks `9F20` sensitive, and that governs *display*, not this. A terminal that
     * masked a field on its way into a command would send the wrong command, so the encoder does not
     * consult [io.github.sebkoo.tagscope.tags.TagInfo.isSensitive] and nothing here may start to —
     * the masking discipline everywhere else in this library is exactly the change that would break
     * it. Pinned rather than left to the KDoc, because it reads like an omission to anyone arriving
     * from the render path.
     *
     * `9F20` is also the one non-PAN `cn` tag in the dictionary, so this reaches the trailing-`FF`
     * rule through the public API. Four digits of issuer filler: shorter than any PAN, and not one.
     */
    @Test
    fun `a sensitive tag's value goes into the command unmasked`() {
        val dol = dolOf("9F20" to 4)
        val data = mapOf(tag("9F20") to hex("1234"))
        assertArrayEquals(hex("1234FFFF"), DolEncoder.build(dol, data).expectBytes())
    }

    /** A key is a whole identifier field, so `9F02` written in three octets is a different tag. */
    @Test
    fun `a tag key is matched on its identifier octets, not just its number`() {
        val dol = dolOf("9F02" to 2)
        val data = mapOf(TlvTag(value = 0x9F02, octetLength = 3) to hex("AABB"))
        assertArrayEquals(hex("0000"), DolEncoder.build(dol, data).expectBytes())
    }

    @Test
    fun `the caller's arrays are neither retained nor modified`() {
        val supplied = hex("1234")
        val dol = dolOf("9F02" to 4)
        val result = DolEncoder.build(dol, mapOf(tag("9F02") to supplied)).expectBytes()
        supplied[0] = 0xEE.toByte()
        assertArrayEquals(hex("00001234"), result) { "the result must not alias the caller's array" }
    }

    @Test
    fun `a DOL asking for more than a command data field holds is refused`() {
        val dol = dolOf("9F02" to 200, "9F37" to 100)
        val failure = DolEncoder.build(dol, emptyMap())
        assertEquals(
            EncodeResult.Failure(
                EncodeError.CommandDataTooLong(tag("9F37"), 300L, DolEncoder.MAX_COMMAND_DATA_OCTETS),
            ),
            failure,
        )
    }

    /**
     * The running total is a `Long` for exactly this shape: a first entry inside the bound followed
     * by one near [Int.MAX_VALUE]. Summed in an `Int` the two wrap to a negative, which is not
     * greater than the bound, so the guard meant to stop the allocation would wave it through and
     * the negative would then reach the output buffer's capacity.
     */
    @Test
    fun `entries whose total overflows an Int are refused rather than wrapping`() {
        val dol = dolOf("9F02" to 200, "9F37" to Int.MAX_VALUE)
        assertEquals(
            EncodeResult.Failure(
                EncodeError.CommandDataTooLong(
                    tag("9F37"),
                    200L + Int.MAX_VALUE,
                    DolEncoder.MAX_COMMAND_DATA_OCTETS,
                ),
            ),
            DolEncoder.build(dol, emptyMap()),
        )
        assertTrue(200 + Int.MAX_VALUE < 0) { "the case is only meaningful while this sum wraps in an Int" }
    }

    @Test
    fun `a negative entry length is refused`() {
        val dol = dolOf("9F02" to -1)
        assertEquals(
            EncodeResult.Failure(EncodeError.NegativeEntryLength(tag("9F02"), -1)),
            DolEncoder.build(dol, emptyMap()),
        )
    }

    /** The failure is settled before anything is allocated, so an absurd length never reaches a fill. */
    @Test
    fun `an entry that alone exceeds the bound names itself`() {
        val dol = dolOf("9F02" to Int.MAX_VALUE)
        assertEquals(
            EncodeResult.Failure(
                EncodeError.CommandDataTooLong(
                    tag("9F02"),
                    Int.MAX_VALUE.toLong(),
                    DolEncoder.MAX_COMMAND_DATA_OCTETS,
                ),
            ),
            DolEncoder.build(dol, emptyMap()),
        )
    }

    private companion object {
        /**
         * One row of Book 3 §5.4's pad-and-truncate table, driven through `fitToLength` so the rule
         * is exercised for a format rather than for whichever tags the dictionary assigns it. The
         * dictionary's only two `cn` tags are `5A` and `9F20`, both sensitive, so reaching the `cn`
         * rules through a tag would mean PAN-shaped and track-shaped fixtures for a rule about which
         * octet fills a gap.
         */
        private class FitCase(
            val name: String,
            val format: TagFormat?,
            val value: String,
            val length: Int,
            val expected: String,
        )

        private val FIT_CASES: List<FitCase> =
            listOf(
                FitCase("n, short: pads leading zeroes", TagFormat.NUMERIC, "1234", 3, "001234"),
                FitCase("n, long: keeps the rightmost octets", TagFormat.NUMERIC, "00001234", 2, "1234"),
                FitCase("n, exact: unchanged", TagFormat.NUMERIC, "001234", 3, "001234"),
                FitCase("cn, short: pads trailing FF", TagFormat.COMPRESSED_NUMERIC, "1234", 4, "1234FFFF"),
                FitCase("cn, long: keeps the leftmost octets", TagFormat.COMPRESSED_NUMERIC, "1234FFFF", 2, "1234"),
                FitCase("cn, empty: becomes all FF", TagFormat.COMPRESSED_NUMERIC, "", 3, "FFFFFF"),
                FitCase("b, short: pads trailing zeroes", TagFormat.BINARY, "AABB", 4, "AABB0000"),
                FitCase("b, long: keeps the leftmost octets", TagFormat.BINARY, "AABBCCDD", 2, "AABB"),
                FitCase("b, empty: becomes all zeroes", TagFormat.BINARY, "", 3, "000000"),
                FitCase("an, short: pads trailing zeroes", TagFormat.ALPHANUMERIC, "4142", 3, "414200"),
                FitCase("ans, short: pads trailing zeroes", TagFormat.ALPHANUMERIC_SPECIAL, "4142", 3, "414200"),
                FitCase("var., short: pads trailing zeroes", TagFormat.VAR, "AABB", 3, "AABB00"),
                FitCase("unnamed tag, short: pads trailing zeroes", null, "AABB", 3, "AABB00"),
                FitCase("unnamed tag, long: keeps the leftmost octets", null, "AABBCC", 2, "AABB"),
                FitCase("truncated to nothing", TagFormat.BINARY, "AABB", 0, ""),
            )

        /**
         * A real DOL, a synthetic terminal-data map, and the command data both produce.
         *
         * The DOL is never hand-transcribed: it is parsed out of the golden vector it lives in and
         * decoded, so the existing fixture stays the oracle for the question and the new one for the
         * answer. [perEntry] states each field separately, which is what lets the re-split test check
         * the boundaries without recomputing them.
         */
        private class BuildVector(
            val name: String,
            val sourceFile: String,
            val dolTag: String,
            val expectedFile: String,
            val supplied: List<Pair<String, String>>,
            val perEntry: List<String>,
        ) {
            fun dol(): DecodedValue.Dol {
                val parsed = TlvParser.parse(loadVectorBytes(sourceFile))
                val tree = (parsed as TlvResult.Success).value
                val node = tree.walk().first { it.tag.hex == dolTag }
                val info = (TagDictionary.lookup(node.tag) as TagLookup.Known).info
                return (ValueDecoder.decode(node, info) as DecodeResult.Success).value as DecodedValue.Dol
            }

            fun build(): EncodeResult =
                DolEncoder.build(dol(), supplied.associate { (tagHex, value) -> tag(tagHex) to hex(value) })
        }

        private val BUILD_VECTORS: List<BuildVector> =
            listOf(
                // 14 - the Visa PDOL of Vector 2, answered. Three elements supplied at exactly the
                // length asked for, and 9F40 withheld so the entry fills with zeroes.
                BuildVector(
                    name = "PDOL -> GET PROCESSING OPTIONS command data",
                    sourceFile = "02-visa-fci-pdol.hex",
                    dolTag = "9F38",
                    expectedFile = "encode/14-pdol-gpo-command.hex",
                    supplied =
                        listOf(
                            "9F33" to "E0F8C8",
                            "9F1A" to "0840",
                            "9F35" to "22",
                        ),
                    perEntry =
                        listOf(
                            "E0F8C8",
                            "0840",
                            "22",
                            "0000000000",
                        ),
                ),
                // 15 - the CDOL1 of Vector 3, answered. One vector for six of the rules at once:
                // 9F02 pads leading (n), 9F34 pads trailing (b), 9F03 is absent, 9F45 is absent AND
                // unnamed, 9F4C is unnamed but supplied, and the rest arrive at the exact length.
                BuildVector(
                    name = "CDOL1 -> GENERATE AC command data",
                    sourceFile = "03-read-record.hex",
                    dolTag = "8C",
                    expectedFile = "encode/15-cdol1-genac-command.hex",
                    supplied =
                        listOf(
                            "9F02" to "123456",
                            "9F1A" to "0840",
                            "95" to "0000000000",
                            "5F2A" to "0840",
                            "9A" to "260724",
                            "9C" to "00",
                            "9F37" to "01A2B3C4",
                            "9F35" to "22",
                            "9F4C" to "0011223344556677",
                            "9F34" to "1E03",
                        ),
                    perEntry =
                        listOf(
                            "000000123456",
                            "000000000000",
                            "0840",
                            "0000000000",
                            "0840",
                            "260724",
                            "00",
                            "01A2B3C4",
                            "22",
                            "0000",
                            "0011223344556677",
                            "1E0300",
                        ),
                ),
            )

        /** A tag from its identifier octets, written the way the specification prints it. */
        private fun tag(hexDigits: String): TlvTag =
            TlvTag(value = hexDigits.toLong(radix = 16), octetLength = hexDigits.length / 2)

        /** A DOL assembled by hand, for the cases no golden vector happens to contain. */
        private fun dolOf(vararg entries: Pair<String, Int>): DecodedValue.Dol =
            DecodedValue.Dol(entries.map { (tagHex, length) -> DecodedValue.Dol.Entry(tag(tagHex), length) })

        private fun EncodeResult.expectBytes(): ByteArray {
            assertTrue(this is EncodeResult.Success) { "expected the command data to build, got $this" }
            return (this as EncodeResult.Success).bytes()
        }
    }
}
