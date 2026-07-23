package io.github.sebkoo.tagscope.tlv

/**
 * Reads the tag and length fields of a BER-TLV data object out of a byte array.
 *
 * Both readers are pure: they take a buffer and an offset, read nothing else, hold no state,
 * and return either the decoded field or the [TlvError] saying what was wrong and where.
 * Neither throws on malformed input, and neither looks at the value octets — checking that a
 * declared length is actually present belongs to the parser that walks the buffer.
 *
 * EMV Book 3, Annex B; ISO/IEC 7816-4 §5.2.2.
 */
public object TlvReader {
    /**
     * Reads the identifier octets of one data object starting at [offset] in [source].
     *
     * A tag is one octet unless bits 5-1 of that octet are all set, which is the escape saying
     * the tag number continues: each octet after it sets bit 8 while another follows, and the
     * octet that leaves bit 8 clear is the last. That is how `9F26` and `5F2A` are each one
     * tag rather than two.
     *
     * A tag that both reaches [TlvTag.MAX_IDENTIFIER_OCTETS] and runs out of octets is
     * reported as [TlvError.TruncatedTag], because the octets on hand never actually exceeded
     * the ceiling — only a further octet would have.
     */
    public fun readTag(
        source: ByteArray,
        offset: Int = 0,
    ): TlvResult<TlvTag> {
        if (offset !in source.indices) {
            return TlvResult.Failure(TlvError.UnexpectedEndOfData(offset, source.size))
        }

        val firstOctet = source[offset].toInt() and BerBits.OCTET_MASK
        var value = firstOctet.toLong()
        if ((firstOctet and BerBits.TAG_NUMBER_MASK) != BerBits.TAG_NUMBER_MASK) {
            return TlvResult.Success(TlvTag(value, octetLength = 1))
        }

        var octetLength = 1
        var index = offset + 1
        while (true) {
            if (index !in source.indices) {
                // The previous octet promised another one and the buffer ended instead.
                return TlvResult.Failure(TlvError.TruncatedTag(index))
            }
            if (octetLength == TlvTag.MAX_IDENTIFIER_OCTETS) {
                return TlvResult.Failure(
                    TlvError.TagTooLong(index, TlvTag.MAX_IDENTIFIER_OCTETS),
                )
            }
            val octet = source[index].toInt() and BerBits.OCTET_MASK
            value = (value shl Byte.SIZE_BITS) or octet.toLong()
            octetLength++
            index++
            if ((octet and BerBits.MORE_IDENTIFIER_OCTETS) == 0) {
                return TlvResult.Success(TlvTag(value, octetLength))
            }
        }
    }

    /**
     * Reads the length octets of one data object starting at [offset] in [source].
     *
     * A first octet below `0x80` is itself the length, the short form. Otherwise its low seven
     * bits count the big-endian octets that carry the length, so `0x81` is followed by one,
     * `0x82` by two and `0x83` by three. `0x80` announces the indefinite form and `0xFF` is
     * reserved; neither is a length, and both are rejected.
     */
    public fun readLength(
        source: ByteArray,
        offset: Int = 0,
    ): TlvResult<TlvLength> {
        if (offset !in source.indices) {
            return TlvResult.Failure(TlvError.UnexpectedEndOfData(offset, source.size))
        }

        val firstOctet = source[offset].toInt() and BerBits.OCTET_MASK
        if (firstOctet <= BerBits.SHORT_FORM_MAX) {
            return TlvResult.Success(TlvLength(firstOctet, octetLength = 1))
        }
        if (firstOctet == BerBits.INDEFINITE_LENGTH_OCTET) {
            return TlvResult.Failure(TlvError.IndefiniteLength(offset))
        }
        if (firstOctet == BerBits.RESERVED_LENGTH_OCTET) {
            return TlvResult.Failure(TlvError.ReservedLengthOctet(offset))
        }

        val declaredOctets = firstOctet and BerBits.LONG_FORM_COUNT_MASK
        if (declaredOctets > TlvLength.MAX_LONG_FORM_OCTETS) {
            return TlvResult.Failure(TlvError.LengthOutOfRange(offset, declaredOctets))
        }
        val firstLengthOctet = offset + 1
        val availableOctets = source.size - firstLengthOctet
        if (declaredOctets > availableOctets) {
            return TlvResult.Failure(
                TlvError.TruncatedLength(source.size, declaredOctets, availableOctets),
            )
        }

        // Accumulate in a Long so a four-octet length that overflows Int is detected rather
        // than silently wrapping to a negative value.
        var value = 0L
        for (index in 0 until declaredOctets) {
            val octet = source[firstLengthOctet + index].toInt() and BerBits.OCTET_MASK
            value = (value shl Byte.SIZE_BITS) or octet.toLong()
        }
        if (value > Int.MAX_VALUE) {
            return TlvResult.Failure(TlvError.LengthOutOfRange(offset, declaredOctets))
        }
        return TlvResult.Success(TlvLength(value.toInt(), octetLength = 1 + declaredOctets))
    }
}
