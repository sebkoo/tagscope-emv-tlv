package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LengthReaderTest {
    @Test
    fun `reads a zero short-form length`() {
        val length = TlvReader.readLength(hex("00")).expectSuccess()

        assertEquals(0, length.value)
        assertEquals(1, length.octetLength)
    }

    @Test
    fun `reads a short-form length`() {
        val length = TlvReader.readLength(hex("05")).expectSuccess()

        assertEquals(5, length.value)
        assertEquals(1, length.octetLength)
    }

    @Test
    fun `reads the largest short-form length`() {
        val length = TlvReader.readLength(hex("7F")).expectSuccess()

        assertEquals(127, length.value)
        assertEquals(1, length.octetLength)
    }

    @Test
    fun `reads a one-octet long-form length`() {
        val length = TlvReader.readLength(hex("8180")).expectSuccess()

        assertEquals(128, length.value)
        assertEquals(2, length.octetLength)
    }

    @Test
    fun `treats long-form length octets as unsigned`() {
        val length = TlvReader.readLength(hex("81FF")).expectSuccess()

        assertEquals(255, length.value)
        assertEquals(2, length.octetLength)
    }

    @Test
    fun `reads a two-octet long-form length big-endian`() {
        val length = TlvReader.readLength(hex("820100")).expectSuccess()

        assertEquals(256, length.value)
        assertEquals(3, length.octetLength)
    }

    @Test
    fun `reads the largest two-octet long-form length`() {
        val length = TlvReader.readLength(hex("82FFFF")).expectSuccess()

        assertEquals(65_535, length.value)
        assertEquals(3, length.octetLength)
    }

    @Test
    fun `reads a three-octet long-form length`() {
        val length = TlvReader.readLength(hex("83010000")).expectSuccess()

        assertEquals(65_536, length.value)
        assertEquals(4, length.octetLength)
    }

    @Test
    fun `accepts a long form that is longer than it needs to be`() {
        val length = TlvReader.readLength(hex("82007F")).expectSuccess()

        assertEquals(127, length.value)
        assertEquals(3, length.octetLength)
    }

    @Test
    fun `reads the largest length that can address a byte array`() {
        val length = TlvReader.readLength(hex("847FFFFFFF")).expectSuccess()

        assertEquals(Int.MAX_VALUE, length.value)
        assertEquals(5, length.octetLength)
    }

    @Test
    fun `reads a length at a non-zero offset`() {
        val length = TlvReader.readLength(hex("9F2681FF"), offset = 2).expectSuccess()

        assertEquals(255, length.value)
        assertEquals(2, length.octetLength)
    }

    @Test
    fun `does not look at the value octets it declares`() {
        val length = TlvReader.readLength(hex("05AABB")).expectSuccess()

        assertEquals(5, length.value)
        assertEquals(1, length.octetLength)
    }

    @Test
    fun `rejects empty input`() {
        val error = TlvReader.readLength(hex("")).expectFailure()

        assertEquals(TlvError.UnexpectedEndOfData(offset = 0, size = 0), error)
    }

    @Test
    fun `rejects an offset at the end of the buffer`() {
        val error = TlvReader.readLength(hex("9F26"), offset = 2).expectFailure()

        assertEquals(TlvError.UnexpectedEndOfData(offset = 2, size = 2), error)
    }

    @Test
    fun `rejects an offset outside the buffer`() {
        assertEquals(
            TlvError.UnexpectedEndOfData(offset = 9, size = 2),
            TlvReader.readLength(hex("9F26"), offset = 9).expectFailure(),
        )
        assertEquals(
            TlvError.UnexpectedEndOfData(offset = -1, size = 2),
            TlvReader.readLength(hex("9F26"), offset = -1).expectFailure(),
        )
    }

    @Test
    fun `rejects the indefinite form`() {
        val error = TlvReader.readLength(hex("80")).expectFailure()

        assertEquals(TlvError.IndefiniteLength(offset = 0), error)
    }

    @Test
    fun `rejects the indefinite form at a non-zero offset`() {
        val error = TlvReader.readLength(hex("9F2680"), offset = 2).expectFailure()

        assertEquals(TlvError.IndefiniteLength(offset = 2), error)
    }

    @Test
    fun `rejects the reserved first length octet`() {
        assertEquals(
            TlvError.ReservedLengthOctet(offset = 0),
            TlvReader.readLength(hex("FF")).expectFailure(),
        )
        assertEquals(
            TlvError.ReservedLengthOctet(offset = 2),
            TlvReader.readLength(hex("9F26FF"), offset = 2).expectFailure(),
        )
    }

    @Test
    fun `rejects a long form with no length octets after it`() {
        val error = TlvReader.readLength(hex("81")).expectFailure()

        assertEquals(
            TlvError.TruncatedLength(offset = 1, declaredOctets = 1, availableOctets = 0),
            error,
        )
    }

    @Test
    fun `rejects a long form whose length octets run past the buffer`() {
        val error = TlvReader.readLength(hex("8201")).expectFailure()

        assertEquals(
            TlvError.TruncatedLength(offset = 2, declaredOctets = 2, availableOctets = 1),
            error,
        )
    }

    @Test
    fun `counts only the octets after the offset when reporting truncation`() {
        val error = TlvReader.readLength(hex("9F268301"), offset = 2).expectFailure()

        assertEquals(
            TlvError.TruncatedLength(offset = 4, declaredOctets = 3, availableOctets = 1),
            error,
        )
    }

    @Test
    fun `rejects a long form declaring more octets than are supported`() {
        assertEquals(
            TlvError.LengthOutOfRange(offset = 0, declaredOctets = 5),
            TlvReader.readLength(hex("850000000005")).expectFailure(),
        )
        assertEquals(
            TlvError.LengthOutOfRange(offset = 2, declaredOctets = 5),
            TlvReader.readLength(hex("9F2685"), offset = 2).expectFailure(),
        )
    }

    @Test
    fun `rejects a length that cannot address a byte array`() {
        assertEquals(
            TlvError.LengthOutOfRange(offset = 0, declaredOctets = 4),
            TlvReader.readLength(hex("8480000000")).expectFailure(),
        )
        assertEquals(
            TlvError.LengthOutOfRange(offset = 2, declaredOctets = 4),
            TlvReader.readLength(hex("9F268480000000"), offset = 2).expectFailure(),
        )
    }
}
