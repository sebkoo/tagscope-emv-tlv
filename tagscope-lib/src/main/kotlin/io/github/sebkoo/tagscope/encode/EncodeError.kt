package io.github.sebkoo.tagscope.encode

import io.github.sebkoo.tagscope.tlv.TlvTag

/**
 * A Data Object List that decoded perfectly well but cannot be answered: it asks for octets no
 * command data field could carry, or states a length no field could have.
 *
 * A third kind of failure, beside `TlvError` and `DecodeError`, and the division is the same one
 * those two draw. `TlvError` is what stops octets being read as data objects; `DecodeError` is what
 * a well-formed data object can still be wrong about; this is what a well-formed *DOL* can still
 * ask for that no terminal could supply. A caller holding one of these has already got past both
 * others — the DOL parsed, and it decoded into entries.
 *
 * Every variant carries the [tag] of the entry the failure is anchored to. There is no byte stream
 * to index into here, so the entry's tag takes the role `offset` plays for the other two: it names
 * where in the DOL to look. A DOL carries no cardholder data, and neither does a failure about one.
 *
 * EMV Book 3 v4.4, §5.4, Rules for Using a Data Object List (DOL).
 */
public sealed class EncodeError {
    /** The DOL entry the failure is anchored to. */
    public abstract val tag: TlvTag

    /**
     * The entries together ask for more octets than a command data field holds.
     *
     * A DOL entry's length is a BER-TLV length, so it reaches [Int.MAX_VALUE]: six octets on the
     * wire, `9F02 84 7FFFFFFF`, ask for a two-gigabyte field, and a DOL of a few dozen octets can
     * stack several such entries. This library reads data an analyst was handed, which may be
     * corrupt or truncated, so an amplification that large is refused rather than attempted.
     *
     * [tag] is the entry at which the running total crossed [limitOctets] — the first entry that
     * could not be honoured, not the last one asked for. [requestedOctets] is a [Long] because the
     * whole point of the check is a total that does not fit an `Int`.
     *
     * @property requestedOctets how many octets the entries had asked for by the time the limit
     *   was passed.
     * @property limitOctets the bound that was exceeded, `DolEncoder.MAX_COMMAND_DATA_OCTETS`.
     */
    public data class CommandDataTooLong(
        override val tag: TlvTag,
        public val requestedOctets: Long,
        public val limitOctets: Int,
    ) : EncodeError()

    /**
     * An entry states a negative length, so there is no field for it to describe.
     *
     * This cannot come off the wire — `TlvReader` never reads a negative length — but
     * `DecodedValue.Dol.Entry` is a plain data class with no constructor check, so a DOL assembled
     * by hand can hold one. Reported rather than allowed to surface as a `NegativeArraySizeException`
     * from inside a fill, which would name neither the entry nor the cause.
     *
     * @property length the negative length the entry stated.
     */
    public data class NegativeEntryLength(
        override val tag: TlvTag,
        public val length: Int,
    ) : EncodeError()
}
