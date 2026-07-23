package io.github.sebkoo.tagscope.decode

import io.github.sebkoo.tagscope.tags.TagDictionary
import io.github.sebkoo.tagscope.tags.TagInfo
import io.github.sebkoo.tagscope.tags.TagLookup
import io.github.sebkoo.tagscope.tlv.TlvNode
import io.github.sebkoo.tagscope.tlv.TlvParser
import io.github.sebkoo.tagscope.tlv.expectSuccess
import io.github.sebkoo.tagscope.tlv.hex
import org.junit.jupiter.api.Assertions.fail

/**
 * Inputs are written as whole data objects — tag, length and value — and parsed by `TlvParser`
 * rather than assembled here, for two reasons. The offsets a decode failure reports are indices
 * into the parsed buffer, so a test that built a node by hand would be checking arithmetic against
 * itself; and it pins that every case is a data object the parser actually accepts, which keeps a
 * decoder test from asserting about something that could never reach the decoder.
 *
 * Every value below is hand-written or taken from a published specification example. None of it is
 * card data.
 */
internal fun node(text: String): TlvNode = TlvParser.parse(hex(text)).expectSuccess().single()

/** Decodes the single data object [text] encodes, against what the dictionary says about its tag. */
internal fun decode(text: String): DecodeResult {
    val node = node(text)
    return ValueDecoder.decode(node, infoFor(node))
}

/** What the dictionary says about [node]'s tag, failing the test if it says nothing. */
internal fun infoFor(node: TlvNode): TagInfo =
    when (val lookup = TagDictionary.lookup(node.tag)) {
        is TagLookup.Known -> lookup.info
        is TagLookup.Unknown -> fail("expected ${node.tag.hex} to be in the dictionary")
    }

/** Unwraps a successful decode, failing the test with the error if it did not succeed. */
internal fun DecodeResult.expectValue(): DecodedValue =
    when (this) {
        is DecodeResult.Success -> value
        is DecodeResult.Failure -> fail("expected a successful decode, got $error")
    }

/** Unwraps a failed decode, failing the test with the value if it unexpectedly succeeded. */
internal fun DecodeResult.expectError(): DecodeError =
    when (this) {
        is DecodeResult.Success -> fail("expected a failed decode, got $value")
        is DecodeResult.Failure -> error
    }

/**
 * The value inside a [DecodedValue.Sensitive], failing the test if it was not wrapped at all.
 *
 * Spelled out at every call site on purpose: a test that reaches a PAN should read as one.
 */
internal fun DecodedValue.revealed(): DecodedValue =
    when (this) {
        is DecodedValue.Sensitive -> reveal()
        else -> fail("expected a sensitive value, got $this")
    }
