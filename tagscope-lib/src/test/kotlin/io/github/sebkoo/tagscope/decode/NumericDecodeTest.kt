package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.Amount
import io.github.sebkoo.tagscope.decode.DecodedValue.Digits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `n` and `cn` formats, and the two tags EMV states as `n 12` amounts.
 *
 * Two of the fixtures are the specification's own worked examples, quoted in Book 3 §4.3: the PAN
 * `12 34 56 78 90 12 3F FF` for 1234567890123, and `00 00 00 01 23 45` for an amount of 12345.
 * The rest are hand-written. Nothing here is card data.
 */
class NumericDecodeTest {
    @Test
    fun `n keeps the leading zeroes it was padded with`() {
        // 5F2A is a currency code. Dropping the zero would make 0826 into 826, a different code.
        assertEquals(Digits("0826"), decode("5F2A020826").expectValue())
    }

    @Test
    fun `n decodes two digits per octet, however few octets there are`() {
        assertEquals(Digits("00"), decode("9C0100").expectValue())
        assertEquals(Digits("01"), decode("5F3401" + "01").expectValue())
        assertEquals(Digits("0840"), decode("9F1A020840").expectValue())
    }

    @Test
    fun `n decodes an empty value to no digits, since the dictionary's bounds are not a gate`() {
        // 5F2A is n 3 and the dictionary says two octets, but a length of nought is well-formed
        // BER-TLV and the parser accepts it, so the decoder may not reject it either.
        assertEquals(Digits(""), decode("5F2A00").expectValue())
    }

    @Test
    fun `n rejects a nibble that is not a decimal digit, and names the octet holding it`() {
        // 5F2A: two identifier octets, one length octet, so the value starts at offset 3.
        val node = node("5F2A02082F")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.NotBcd(node.tag, offset = 4), error)
    }

    @Test
    fun `n has no padding nibble, so an F is a bad digit wherever it appears`() {
        val leading = node("5F2A020F26")

        val error = ValueDecoder.decode(leading, infoFor(leading)).expectError()

        assertEquals(DecodeError.NotBcd(leading.tag, offset = 3), error)
    }

    @Test
    fun `cn drops the trailing F padding and keeps the odd digit count that leaves`() {
        // Book 3 §4.3's own example: 1234567890123 stored in eight octets, thirteen digits.
        val pan = decode("5A081234567890123FFF").expectValue()

        assertEquals(Digits("1234567890123"), pan.revealed())
    }

    @Test
    fun `cn that fills its octets exactly has no padding to drop`() {
        // Sixteen digits, so every nibble is a digit and there is no F to strip. Fabricated, and
        // deliberately not a number any card could carry: 1111 is an unassigned issuer identifier
        // and the digits fail the Luhn check that a real account number satisfies.
        val pan = decode("5A081111222233334440").expectValue()

        assertEquals(Digits("1111222233334440"), pan.revealed())
    }

    @Test
    fun `cn of nothing but padding decodes to no digits`() {
        assertEquals(Digits(""), decode("5A01FF").expectValue().revealed())
    }

    @Test
    fun `cn rejects a digit that follows the padding, which is not trailing padding at all`() {
        // 5A is one identifier octet and one length octet, so the value starts at offset 2.
        val node = node("5A0212F3")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.MisplacedPadding(node.tag, offset = 3), error)
    }

    @Test
    fun `cn rejects A to E, which are neither digits nor padding`() {
        val node = node("5A021A23")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        // Equality against the whole error, which is also how this pins that no value octet
        // travels in it: the PAN is sensitive and NotBcd has nowhere to put one.
        assertEquals(DecodeError.NotBcd(node.tag, offset = 2), error)
    }

    @Test
    fun `an amount is a whole number of minor units, with no decimal point placed`() {
        // Book 3 §4.3's own example: 12345 in an n 12 field.
        assertEquals(Amount(12345), decode("9F0206000000012345").expectValue())
        assertEquals(Amount(0), decode("9F0306000000000000").expectValue())
    }

    @Test
    fun `an amount fills all twelve digits without overflowing`() {
        assertEquals(Amount(999_999_999_999), decode("9F0206999999999999").expectValue())
    }

    @Test
    fun `an amount that is not six octets has nothing to decode`() {
        val node = node("9F02050000000123")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        // The offset is the object's own first octet: the whole object is wrong, not one place
        // in it.
        assertEquals(
            DecodeError.UnexpectedValueLength(node.tag, offset = 0, expectedOctets = 6, actualOctets = 5),
            error,
        )
    }

    @Test
    fun `an amount is still BCD, so a stray nibble fails it`() {
        val node = node("9F020600000001234F")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.NotBcd(node.tag, offset = 8), error)
    }
}
