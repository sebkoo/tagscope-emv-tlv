package io.github.sebkoo.tagscope.decode

/**
 * The outcome of decoding a data object's value: either the [DecodedValue], or the [DecodeError]
 * describing what was wrong with the octets and where.
 *
 * Separate from `TlvResult` although it is the same shape, because the failure it carries is a
 * different kind: `TlvResult` fails when a payload cannot be read as data objects, this fails when
 * a data object that read perfectly well does not hold what its tag says it holds. A caller that
 * has one of these has already got past the other.
 */
public sealed interface DecodeResult {
    /** The value decoded, and is [value]. */
    public data class Success(
        public val value: DecodedValue,
    ) : DecodeResult

    /** The value did not decode, because of [error]. */
    public data class Failure(
        public val error: DecodeError,
    ) : DecodeResult
}
