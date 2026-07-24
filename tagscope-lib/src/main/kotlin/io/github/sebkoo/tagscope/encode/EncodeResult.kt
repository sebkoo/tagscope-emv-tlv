package io.github.sebkoo.tagscope.encode

/**
 * The outcome of building command data from a DOL: either the octets, or the [EncodeError] saying
 * which entry could not be answered and why.
 *
 * The same shape as `TlvResult` and `DecodeResult`, and separate from both for the same reason they
 * are separate from each other: the failure it carries is a different kind. See [EncodeError].
 */
public sealed interface EncodeResult {
    /**
     * The command data was built, and is [bytes].
     *
     * Not a data class, for the reason `DecodedValue.RawBinary` is not one: a data class would
     * compare the array by identity. The octets are copied in and copied out, so the result aliases
     * neither the caller's input nor anything a later call could reach.
     *
     * **These octets are card data.** See `DolEncoder` for what that means and what it does not.
     */
    public class Success(
        bytes: ByteArray,
    ) : EncodeResult {
        private val octets: ByteArray = bytes.copyOf()

        /** The command data field's octets, in DOL order. A fresh copy on every call. */
        public fun bytes(): ByteArray = octets.copyOf()

        /** How many octets there are, without handing them out. Always the sum of the DOL's lengths. */
        public val size: Int
            get() = octets.size

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return octets.contentEquals(other.octets)
        }

        override fun hashCode(): Int = octets.contentHashCode()

        /**
         * The count and not the content.
         *
         * A `toString` is exactly how bytes reach a log, and these bytes are the ones this library
         * most needs to keep out of one: unlabelled command data that may carry a PAN with no tag
         * left on it to redact by.
         */
        override fun toString(): String = "EncodeResult.Success($size octets)"
    }

    /** The command data was not built, because of [error]. */
    public data class Failure(
        public val error: EncodeError,
    ) : EncodeResult
}
