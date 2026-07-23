package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.Sensitive
import io.github.sebkoo.tagscope.decode.DecodedValue.Track2
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Track 2 Equivalent Data (tag `57`): the PAN, expiry and service code packed into BCD and split
 * on a `D` nibble.
 *
 * Every value here is fabricated. The PAN in the valid fixtures is `1111222233334441` — issuer
 * identifier `1111` is unassigned and the digits fail the Luhn check a real account number
 * satisfies, so it is not a scannable card number. None of it is card data.
 *
 * The malformed cases pin their error by whole-value equality, which is also how they pin that no
 * value octet travels: `57` holds the PAN, so an error that carried a digit would carry two digits
 * of a PAN, and an equality assertion against an error that has nowhere to put one is the guard.
 * See `no error reachable for track 2 carries a value octet` for the explicit sweep.
 */
class Track2DecodeTest {
    @Test
    fun `a valid track 2 splits into pan, expiry, service code and discretionary data`() {
        // 57 0F | 1111222233334441 D 2612 201 00000 F — a 16-digit PAN, an expiry of 26-12, a
        // service code of 201, five discretionary digits and one F pad to fill the last octet.
        val decoded = decode("570F1111222233334441D261220100000F").expectValue()

        assertTrue(decoded is Sensitive, "track 2 holds the PAN and must not come back bare")
        val track2 = decoded.revealed() as Track2
        assertEquals("1111222233334441", track2.pan)
        assertEquals(Track2.Expiry(yearOfCentury = 26, month = 12), track2.expiry)
        assertEquals("201", track2.serviceCode)
        // The trailing F is padding and is dropped: it is not a sixth discretionary digit.
        assertEquals("00000", track2.discretionaryData)
    }

    @Test
    fun `the expiry keeps the two year digits the card wrote, with no century supplied`() {
        // 99-01, the two digits as written; windowing them into a full year is the reader's policy,
        // the same reading the scalar dates take.
        val track2 = decode("570C1111222233334441D9901201").revealTrack2()

        assertEquals(Track2.Expiry(yearOfCentury = 99, month = 1), track2.expiry)
    }

    @Test
    fun `discretionary data is empty when the expiry and service code fill the value exactly`() {
        // Seven digits after the separator: four of expiry, three of service, nothing left over.
        val track2 = decode("570C1111222233334441D2612201").revealTrack2()

        assertEquals("201", track2.serviceCode)
        assertEquals("", track2.discretionaryData)
    }

    @Test
    fun `an odd number of data nibbles is padded with a single F, which is dropped`() {
        // A 15-digit PAN makes the data odd, so one F pads the last octet. It is stripped, and the
        // discretionary data is empty rather than "F".
        val track2 = decode("570C111111111111111D2612201F").revealTrack2()

        assertEquals("111111111111111", track2.pan)
        assertEquals("", track2.discretionaryData)
    }

    @Test
    fun `no separator is rejected, and the whole object is named`() {
        val node = node("57081111222233334441")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.Track2NoSeparator(node.tag, offset = 0), error)
    }

    @Test
    fun `more than one separator is rejected, and the second D is named`() {
        // 1111 D 2612201 D 00 F — a second separator at offset 8 makes the split ambiguous.
        val node = node("57081111D2612201D00F")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.Track2MultipleSeparators(node.tag, offset = 8), error)
    }

    @Test
    fun `a non-BCD nibble outside the padding is rejected, and the octet holding it is named`() {
        // An A in the PAN, at value offset 8. A, B, C and E are neither digits nor the D separator
        // nor the F padding.
        val node = node("570F111122223333A441D261220100000F")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.NotBcd(node.tag, offset = 8), error)
    }

    @Test
    fun `a digit after the F padding is misplaced padding, not a digit`() {
        // 1111 D 2612201 F 0 — an F with a digit behind it was not padding, so the value is not a
        // well-formed track.
        val node = node("57071111D2612201F0")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.MisplacedPadding(node.tag, offset = 8), error)
    }

    @Test
    fun `a D after the F padding is misplaced padding, not a second separator`() {
        // 1111 D 2612201 F D — the trailing D arrives once padding has begun. Padding is judged
        // before the separator, so this is a nibble after the padding and not a second separator;
        // the precedence is load-bearing, and this pins it against a reordering of the two branches.
        val node = node("57071111D2612201FD")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.MisplacedPadding(node.tag, offset = 8), error)
    }

    @Test
    fun `a PAN of exactly nineteen digits is within bounds`() {
        // The other side of the PanTooLong boundary: nineteen digits is the most ISO 7813 allows,
        // so it decodes rather than being rejected. One F pads the odd nibble count.
        val track2 = decode("570E1111222233334445556D2612201F").revealTrack2()

        assertEquals("1111222233334445556", track2.pan)
        assertEquals("201", track2.serviceCode)
    }

    @Test
    fun `a PAN longer than nineteen digits is rejected, by count and not by digit`() {
        // Twenty digits before the separator. The count travels; the digits do not.
        val node = node("570E11112222333344445555D2612201")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(
            DecodeError.Track2PanTooLong(node.tag, offset = 0, actualDigits = 20, maxDigits = 19),
            error,
        )
    }

    @Test
    fun `too few digits after the separator is rejected, by count and not by digit`() {
        // Five digits after the separator, short of the seven the expiry and service code need.
        val node = node("57051111D26122")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(
            DecodeError.Track2MissingFields(node.tag, offset = 0, foundDigits = 5, requiredDigits = 7),
            error,
        )
    }

    @Test
    fun `a month outside one to twelve is rejected, without carrying the month`() {
        // 1111 D 26 13 201 — a thirteenth month. The offset names the octet the month begins in;
        // the value itself never travels, because 57 is cardholder data.
        val thirteenth = node("57061111D2613201")
        val zeroth = node("57061111D2600201")

        assertEquals(
            DecodeError.Track2MonthOutOfRange(thirteenth.tag, offset = 5),
            ValueDecoder.decode(thirteenth, infoFor(thirteenth)).expectError(),
        )
        assertEquals(
            DecodeError.Track2MonthOutOfRange(zeroth.tag, offset = 5),
            ValueDecoder.decode(zeroth, infoFor(zeroth)).expectError(),
        )
    }

    @Test
    fun `no error reachable for track 2 carries a value octet`() {
        // The PCI guard, the analog of ValueDecoderTest's format invariant. Tag 57 holds the PAN,
        // so an error that echoed a value octet would be two digits of a PAN in whatever log it
        // reached. Each fixture drives a distinct failing path; together they are every error
        // track2() can return.
        val errors =
            listOf(
                "57081111222233334441", // Track2NoSeparator
                "57081111D2612201D00F", // Track2MultipleSeparators
                "570F111122223333A441D261220100000F", // NotBcd
                "57071111D2612201F0", // MisplacedPadding
                "570E11112222333344445555D2612201", // Track2PanTooLong
                "57051111D26122", // Track2MissingFields
                "57061111D2613201", // Track2MonthOutOfRange
            ).map { decode(it).expectError() }

        // Every distinct error type is exercised, so the field check below cannot pass vacuously by
        // decoding seven of the same error.
        assertEquals(7, errors.map { it::class }.toSet().size, "expected every reachable 57 error")

        // A field-naming check, not a substring one: a leak is not only PAN digits but any decoded
        // value — a month, a year — and a two-digit month would slip past a substring guard while
        // still being two digits off the card. So every field an error 57 can carry must be the
        // tag, an offset, or a count, and nothing else. Adding a field outside this set is exactly
        // the review a new field must pass; a value octet does not pass it.
        val allowed = setOf("tag", "offset", "actualDigits", "maxDigits", "foundDigits", "requiredDigits")
        for (error in errors) {
            val fields =
                error.javaClass.declaredFields
                    .filterNot { it.isSynthetic }
                    .map { it.name }
                    .toSet()
            assertTrue(
                allowed.containsAll(fields),
                "${error.javaClass.simpleName} carries a field that is not tag, offset or a count: ${fields - allowed}",
            )
        }
    }

    @Test
    fun `a decoded track 2 does not print its fields`() {
        val track2 = Track2("1234567890123456", Track2.Expiry(26, 12), "201", "9999")

        assertEquals("Track2(redacted)", track2.toString())
        assertFalse(track2.toString().contains("123456"), "the PAN leaked through toString")
        // Wrapped in Sensitive in turn, so the same holds of whatever a caller logs.
        assertFalse(DecodeResult.Success(Sensitive(track2)).toString().contains("123456"))
    }

    @Test
    fun `two track 2 values compare by their fields`() {
        val track2 = Track2("1111", Track2.Expiry(26, 12), "201", "00")

        assertEquals(track2, Track2("1111", Track2.Expiry(26, 12), "201", "00"))
        assertEquals(track2.hashCode(), Track2("1111", Track2.Expiry(26, 12), "201", "00").hashCode())
        assertNotEquals(track2, Track2("1112", Track2.Expiry(26, 12), "201", "00"))
        assertNotEquals(track2, Track2("1111", Track2.Expiry(27, 12), "201", "00"))
        assertNotEquals(track2, Track2("1111", Track2.Expiry(26, 12), "202", "00"))
        assertNotEquals(track2, Track2("1111", Track2.Expiry(26, 12), "201", "01"))
    }
}

/** Reveals the sensitive Track 2 a decode produced, failing the test if it was not one. */
private fun DecodeResult.revealTrack2(): Track2 = expectValue().revealed() as Track2
