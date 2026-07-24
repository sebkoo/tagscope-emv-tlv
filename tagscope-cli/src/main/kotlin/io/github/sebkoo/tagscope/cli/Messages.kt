package io.github.sebkoo.tagscope.cli

import io.github.sebkoo.tagscope.tlv.TlvError

/**
 * A structural parse failure as one line for the user: the offset the library reports, and a reason
 * this CLI writes. The library's `TlvError` carries the facts but no prose — presentation is the
 * caller's, so the wording lives here. No variant carries value octets, so no reason can leak one.
 */
internal fun parseErrorLine(error: TlvError): String = "parse error at offset ${error.offset}: ${reason(error)}"

private fun reason(error: TlvError): String =
    when (error) {
        is TlvError.UnexpectedEndOfData -> "unexpected end of data (buffer is ${error.size} octets)"
        is TlvError.TruncatedTag -> "tag identifier is truncated"
        is TlvError.TagTooLong -> "tag identifier is longer than ${error.maxOctets} octets"
        is TlvError.IndefiniteLength -> "indefinite length (0x80) is not allowed in EMV"
        is TlvError.ReservedLengthOctet -> "reserved length octet 0xFF"
        is TlvError.TruncatedLength ->
            "length field is truncated: declares ${error.declaredOctets} octets, ${error.availableOctets} available"
        is TlvError.LengthOutOfRange -> "length is out of range (${error.declaredOctets} octets)"
        is TlvError.TruncatedValue ->
            "value is truncated: declares ${error.declaredLength} octets, ${error.availableOctets} available"
        is TlvError.ChildOverrunsParent -> "data object overruns its template (which ends at offset ${error.parentEnd})"
        is TlvError.NestingTooDeep -> "nested deeper than ${error.maxDepth} levels"
        is TlvError.UnexpectedFillerOctet -> "unexpected 0xFF filler octet"
    }

/** The `--help` text. Static, so no card data can reach it. */
internal fun helpText(): String =
    """
    Usage:
      tagscope [options] [hex]     Decode a BER-TLV string into a labelled, nested tag tree.
      tagscope lint [hex]          Check a BER-TLV string for EMV consistency defects.

    The hex may be given as the argument, or piped on standard input when no argument is present.
    Whitespace in the input is ignored, so a trace pasted across several lines is read the same as
    one unbroken run.

    Options (decode):
      --json          Emit the decode as JSON instead of the text tree.
      --reveal        Show sensitive values (PAN, Track 2) in full. Off by default: they are masked.
      -h, --help      Show this help and exit.
      --version       Show the version and exit.

    lint reports findings by severity and exits non-zero when any ERROR is found, so it can gate a
    script or CI step. A finding names a tag and describes the defect; it never prints a value, and
    lint takes no --reveal.

    Examples:
      tagscope 6F15840E315041592E5359532E4444463031A503880101
      echo 6F15840E31504159... | tagscope --json
      tagscope lint 6F20840E325041592E5359532E4444463031A50EBF0C0B61094F07A0000000031010

    A PAN and Track 2 Equivalent Data are masked by default; pass --reveal to display them.
    """.trimIndent()
