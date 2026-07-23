package io.github.sebkoo.tagscope.tlv

import org.junit.jupiter.api.Assertions.fail

/**
 * Parses a compact hex string such as `9F2605` into the octets it denotes.
 *
 * Test inputs are written this way so a case reads like the specification it comes from. Every
 * input in this suite is a hand-written structural fixture; none of it is card data.
 */
internal fun hex(text: String): ByteArray {
    require(text.length % 2 == 0) { "a hex string needs an even number of digits, was \"$text\"" }
    return ByteArray(text.length / 2) { index ->
        text.substring(index * 2, index * 2 + 2).toInt(radix = 16).toByte()
    }
}

/** Unwraps a successful read, failing the test with the error if the read did not succeed. */
internal fun <T> TlvResult<T>.expectSuccess(): T =
    when (this) {
        is TlvResult.Success -> value
        is TlvResult.Failure -> fail("expected a successful read, got $error")
    }

/** Unwraps a failed read, failing the test with the value if the read unexpectedly succeeded. */
internal fun TlvResult<*>.expectFailure(): TlvError =
    when (this) {
        is TlvResult.Success -> fail("expected a failed read, got $value")
        is TlvResult.Failure -> error
    }
