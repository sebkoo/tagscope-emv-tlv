package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.decode.DecodedValue.Date
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The three `YYMMDD` tags: `5F24`, `5F25` and `9A`.
 *
 * The year is whatever two digits the card wrote, so there is no case here asserting a century —
 * that is the point. What is asserted is the calendar: a day is judged against the length of the
 * month it claims to be in, and the only thing the missing century costs is that 29 February has
 * to be allowed through.
 */
class DateDecodeTest {
    @Test
    fun `a date is the digits the card wrote, with no century supplied`() {
        // 9A: one identifier octet and one length octet, so the value starts at offset 2.
        assertEquals(Date(yearOfCentury = 26, month = 7, day = 23), decode("9A03260723").expectValue())
        assertEquals(Date(yearOfCentury = 99, month = 12, day = 31), decode("5F2403991231").expectValue())
        assertEquals(Date(yearOfCentury = 0, month = 1, day = 1), decode("5F2503000101").expectValue())
    }

    @Test
    fun `a month outside one to twelve is rejected, and the month octet is named`() {
        val thirteenth = node("9A03261301")
        val zeroth = node("9A03260015")

        assertEquals(
            DecodeError.MonthOutOfRange(thirteenth.tag, offset = 3, month = 13),
            ValueDecoder.decode(thirteenth, infoFor(thirteenth)).expectError(),
        )
        assertEquals(
            DecodeError.MonthOutOfRange(zeroth.tag, offset = 3, month = 0),
            ValueDecoder.decode(zeroth, infoFor(zeroth)).expectError(),
        )
    }

    @Test
    fun `a day outside the month is rejected, and the day octet is named`() {
        val thirtySecond = node("9A03260732")
        val zeroth = node("9A03260700")

        assertEquals(
            DecodeError.DayOutOfRange(thirtySecond.tag, offset = 4, day = 32, maxDay = 31),
            ValueDecoder.decode(thirtySecond, infoFor(thirtySecond)).expectError(),
        )
        assertEquals(
            DecodeError.DayOutOfRange(zeroth.tag, offset = 4, day = 0, maxDay = 31),
            ValueDecoder.decode(zeroth, infoFor(zeroth)).expectError(),
        )
    }

    @Test
    fun `a day is judged against its own month, so there is no 31st of June`() {
        // Impossible in every century, so nothing about the missing one excuses these.
        val june = node("9A03260631")
        val april = node("9A03260431")

        assertEquals(
            DecodeError.DayOutOfRange(june.tag, offset = 4, day = 31, maxDay = 30),
            ValueDecoder.decode(june, infoFor(june)).expectError(),
        )
        assertEquals(
            DecodeError.DayOutOfRange(april.tag, offset = 4, day = 31, maxDay = 30),
            ValueDecoder.decode(april, infoFor(april)).expectError(),
        )
    }

    @Test
    fun `the 29th of February is allowed in a year ending some century could make a leap year`() {
        // A full year is 100 x century + these two digits, and 100 divides by four, so the year
        // divides by four exactly when the two digits do. An ending of 24 divides by four, so
        // every century's 24 is a leap year and the 29th is real in all of them.
        assertEquals(Date(yearOfCentury = 24, month = 2, day = 29), decode("9A03240229").expectValue())
    }

    @Test
    fun `the 29th of February is refused in a year ending no century could make a leap year`() {
        // 26 does not divide by four, so no year ending 26 does either — not 1926, not 2026, not
        // any of them. This is the 31st of June again, and is refused on the same ground.
        val neverLeap = node("9A03260229")
        val expirationDate = node("5F2403260229")

        assertEquals(
            DecodeError.DayOutOfRange(neverLeap.tag, offset = 4, day = 29, maxDay = 28),
            ValueDecoder.decode(neverLeap, infoFor(neverLeap)).expectError(),
        )
        // Not a rule about 9A: the same reading applies wherever EMV states YYMMDD.
        assertEquals(
            DecodeError.DayOutOfRange(expirationDate.tag, offset = 5, day = 29, maxDay = 28),
            ValueDecoder.decode(expirationDate, infoFor(expirationDate)).expectError(),
        )
    }

    @Test
    fun `a year ending 00 keeps its 29th, the one ending the century really does decide`() {
        // 1900 was not a leap year and 2000 was, so these two digits settle nothing here and the
        // day cannot be refuted. This is the whole of what the missing century costs.
        assertEquals(Date(yearOfCentury = 0, month = 2, day = 29), decode("9A03000229").expectValue())
    }

    @Test
    fun `the 30th and 31st of February are refused, since no century makes them a date`() {
        val thirtieth = node("9A03240230")
        val thirtyFirst = node("9A03240231")

        // A leap-capable year, so 29 is the most February can hold and these are still refused.
        assertEquals(
            DecodeError.DayOutOfRange(thirtieth.tag, offset = 4, day = 30, maxDay = 29),
            ValueDecoder.decode(thirtieth, infoFor(thirtieth)).expectError(),
        )
        assertEquals(
            DecodeError.DayOutOfRange(thirtyFirst.tag, offset = 4, day = 31, maxDay = 29),
            ValueDecoder.decode(thirtyFirst, infoFor(thirtyFirst)).expectError(),
        )
    }

    @Test
    fun `the bound reported for February is the year's own, never a flat 29`() {
        // The number travels to the analyst, so it has to be true of the year in hand: telling
        // them February of a year ending 26 holds 29 days would be a wrong fact, not a rounding.
        val neverLeap = node("9A03260230")

        val error = ValueDecoder.decode(neverLeap, infoFor(neverLeap)).expectError()

        assertEquals(DecodeError.DayOutOfRange(neverLeap.tag, offset = 4, day = 30, maxDay = 28), error)
    }

    @Test
    fun `the month is judged first, since it is what the day is judged against`() {
        val both = node("9A03261332")

        val error = ValueDecoder.decode(both, infoFor(both)).expectError()

        assertEquals(DecodeError.MonthOutOfRange(both.tag, offset = 3, month = 13), error)
    }

    @Test
    fun `a date that is not three octets has nothing to decode`() {
        val short = node("9A022607")

        val error = ValueDecoder.decode(short, infoFor(short)).expectError()

        assertEquals(
            DecodeError.UnexpectedValueLength(short.tag, offset = 0, expectedOctets = 3, actualOctets = 2),
            error,
        )
    }

    @Test
    fun `the shape is judged before the digits, so a short bad date is reported as short`() {
        val shortAndBad = node("9A022A07")

        val error = ValueDecoder.decode(shortAndBad, infoFor(shortAndBad)).expectError()

        // Not NotBcd: six digits do not fit in two octets, so there was nothing to read.
        assertEquals(
            DecodeError.UnexpectedValueLength(shortAndBad.tag, offset = 0, expectedOctets = 3, actualOctets = 2),
            error,
        )
    }

    @Test
    fun `a date is still BCD, so a hex nibble fails it before any calendar is consulted`() {
        val node = node("9A032A0723")

        val error = ValueDecoder.decode(node, infoFor(node)).expectError()

        assertEquals(DecodeError.NotBcd(node.tag, offset = 2), error)
    }
}
