package io.github.sebkoo.tagscope.tlv

/**
 * The outcome of reading BER-TLV data: either the decoded value, or the [TlvError] describing
 * what was wrong and where.
 *
 * Malformed input is expected input, so it is returned rather than thrown, and absence is
 * modelled here rather than as `null`.
 */
public sealed interface TlvResult<out T> {
    /** The read succeeded and produced [value]. */
    public data class Success<out T>(
        public val value: T,
    ) : TlvResult<T>

    /** The read failed with [error]. */
    public data class Failure(
        public val error: TlvError,
    ) : TlvResult<Nothing>
}
