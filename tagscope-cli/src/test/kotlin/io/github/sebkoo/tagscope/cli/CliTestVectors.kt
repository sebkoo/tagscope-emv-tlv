package io.github.sebkoo.tagscope.cli

/**
 * The hex the CLI smoke tests decode, shared across the focused test classes.
 *
 * These are the six golden vectors from the library's `src/test/resources/vectors` fixtures, plus a
 * couple of deliberately malformed inputs. They are inlined here — grouped a row per line, verbatim
 * from the fixtures so they stay auditable byte-for-byte — because the library's test resources sit
 * in another module and are not on this module's classpath. The CLI strips whitespace from its hex
 * input, so the grouping is free.
 *
 * The vector-3 PAN and its masking are pinned by [MaskingTest]; these constants let the other
 * classes reach the same fixtures without duplicating them.
 */
internal object CliTestVectors {
    /** Vector 1 — PSE FCI. Small and fully deterministic: the exact-string golden oracle. */
    const val PSE_FCI: String = "6F 15 84 0E 31 50 41 59 2E 53 59 53 2E 44 44 46 30 31 A5 03 88 01 01"

    /** Vector 2 — Visa AID FCI with a PDOL (9F38). */
    const val VISA_FCI_PDOL: String =
        "6F 28 84 07 A0 00 00 00 03 10 10 A5 1D 50 04 56 49 53 41 87 01 01 9F 38 0C " +
            "9F 33 03 9F 1A 02 9F 35 01 9F 40 05 5F 2D 02 64 65"

    /** Vector 3 — READ RECORD, long-form length. Carries the masked PAN (5A) and TVR-shaped IACs. */
    const val READ_RECORD: String =
        "70 81 8C 9F 42 02 06 43 5F 25 03 16 03 01 5F 24 03 20 11 30 5A 08 55 70 29 56 26 67 80 85 " +
            "5F 34 01 02 9F 07 02 FF 00 8C 21 9F 02 06 9F 03 06 9F 1A 02 95 05 5F 2A 02 9A 03 9C 01 " +
            "9F 37 04 9F 35 01 9F 45 02 9F 4C 08 9F 34 03 8D 0C 91 0A 8A 02 95 05 9F 37 04 9F 4C 08 " +
            "8E 14 00 00 00 00 00 00 00 00 42 01 44 03 41 03 42 03 1E 03 1F 03 9F 0D 05 BC 50 BC 88 00 " +
            "9F 0E 05 00 00 08 00 00 9F 0F 05 BC 70 BC 98 00 5F 28 02 06 43 9F 4A 01 82"

    /** Vector 4 — GENERATE AC, Format 2 (constructed 77). CID = 00 (AAC). */
    const val GENERATE_AC_FMT2: String =
        "77 29 9F 27 01 00 9F 36 02 00 41 9F 26 08 C7 4D 18 B0 82 48 FE FC 9F 10 12 " +
            "01 10 20 10 09 24 84 00 00 00 00 00 00 00 00 00 29 FF"

    /** Vector 5 — GPO Format 1: a primitive 80 carrying six opaque octets, no recursion. */
    const val GPO_FMT1: String = "80 06 1C 00 08 01 01 00"

    /** Vector 6 — GPO Format 2: a constructed 77 with 82 AIP and 94 AFL. */
    const val GPO_FMT2: String = "77 0A 82 02 1C 00 94 04 08 01 01 00"

    /** The six golden vectors, each expected to decode with exit 0. */
    val ALL: List<Pair<String, String>> =
        listOf(
            "PSE FCI" to PSE_FCI,
            "Visa FCI + PDOL" to VISA_FCI_PDOL,
            "READ RECORD" to READ_RECORD,
            "GENERATE AC (Format 2)" to GENERATE_AC_FMT2,
            "GPO (Format 1)" to GPO_FMT1,
            "GPO (Format 2)" to GPO_FMT2,
        )

    /** The PAN digits carried by [READ_RECORD] (5A), masked unless `--reveal`. */
    const val PAN: String = "5570295626678085"

    /** Malformed: a length octet of 0x80 is an indefinite length, which EMV forbids. */
    const val INDEFINITE_LENGTH: String = "6F 80"

    /** Malformed: 9F42 declares a 4-octet value but only 2 octets follow. */
    const val TRUNCATED_VALUE: String = "9F 42 04 06 43"
}
