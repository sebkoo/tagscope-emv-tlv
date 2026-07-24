package io.github.sebkoo.tagscope.decode

/**
 * The EMV names for a CVM method code and a CVM condition code — the wording Book 3 Annex C3 gives
 * each code, resolved from the code itself.
 *
 * The single source of truth for these two tables. Two tags carry the same pair of codes and read
 * their meanings here: the CVM Results bit field (`9F34`) resolves its byte-1 method and byte-2
 * condition through [METHODS] and [CONDITIONS] as it decodes, and the CVM List (`8E`), whose CV
 * Rules are those same two codes, resolves its names here at render time. Sharing the tables is why
 * the two tags cannot drift.
 *
 * A [DecodedValue.CvmList.CvmRule] stores only the codes, the way a [DecodedValue.Dol.Entry] stores
 * only a tag and a length: a name is a reading of a code, and reading belongs to whoever displays
 * the value — for this project, the CLI. So the lookups are public, the tables they read internal.
 *
 * A code no table names comes back as an `"RFU/unknown (0x..)"` string rather than throwing — a
 * decode that yielded a code outside the mapped range is still a value to show, and this library
 * turns no input into an exception. The tables cover their whole domains as it stands — a method
 * code is the six bits `0x00..0x3F`, a condition code the octet `0x00..0xFF`, both mapped end to
 * end — so the fallback guards only a caller that passes a code from outside those ranges.
 *
 * EMV Book 3 v4.4, Annex C3: Table 43 (CVM Codes) and Table 44 (CVM Condition Codes). `0x3F` is
 * Book 4 v4.4, Annex A4's "No CVM performed", which overrides Table 43's "not available".
 */
public object CvmCodes {
    /**
     * The name for a CVM method code: the low six bits (`b6..b1`) of a CVM Code byte, with the
     * apply-next bit (`0x40`) and the RFU bit (`0x80`) already taken off. Never null — an unmapped
     * code is reported as RFU/unknown rather than throwing.
     */
    public fun method(code: Int): String = METHODS[code] ?: unknown(code)

    /** The name for a CVM condition code — the whole condition octet. Never null; see [method]. */
    public fun condition(code: Int): String = CONDITIONS[code] ?: unknown(code)

    /**
     * CVM Codes keyed by `b6..b1`. Book 3 v4.4, Annex C3, Table 43; `0x3F` is Book 4 v4.4, Annex A4's
     * "No CVM performed", which overrides Table 43's "not available".
     */
    internal val METHODS: Map<Int, String> =
        mapOf(
            0x00 to "Fail CVM processing",
            0x01 to "Plaintext PIN verification performed by ICC",
            0x02 to "Enciphered PIN verified online",
            0x03 to "Plaintext PIN verification performed by ICC and signature",
            0x04 to "Enciphered PIN verification performed by ICC",
            0x05 to "Enciphered PIN verification performed by ICC and signature",
            0x06 to "Facial biometric verified offline (by ICC)",
            0x07 to "Facial biometric verified online",
            0x08 to "Finger biometric verified offline (by ICC)",
            0x09 to "Finger biometric verified online",
            0x0A to "Palm biometric verified offline (by ICC)",
            0x0B to "Palm biometric verified online",
            0x0C to "Iris biometric verified offline (by ICC)",
            0x0D to "Iris biometric verified online",
            0x0E to "Voice biometric verified offline (by ICC)",
            0x0F to "Voice biometric verified online",
            0x1E to "Signature",
            0x1F to "No CVM required",
            0x3F to "No CVM performed",
        ) +
            (0x10..0x1D).associateWith { "RFU (reserved for future use by this specification)" } +
            (0x20..0x2F).associateWith { "Reserved for use by the individual payment systems" } +
            (0x30..0x3E).associateWith { "Reserved for use by the issuer" }

    /** CVM Condition Codes. Book 3 v4.4, Annex C3, Table 44. */
    internal val CONDITIONS: Map<Int, String> =
        mapOf(
            0x00 to "Always",
            0x01 to "If unattended cash",
            0x02 to "If not unattended cash and not manual cash and not purchase with cashback",
            0x03 to "If terminal supports the CVM",
            0x04 to "If manual cash",
            0x05 to "If purchase with cashback",
            0x06 to "If transaction is in the application currency and is under X value",
            0x07 to "If transaction is in the application currency and is over X value",
            0x08 to "If transaction is in the application currency and is under Y value",
            0x09 to "If transaction is in the application currency and is over Y value",
        ) +
            (0x0A..0x7F).associateWith { "RFU" } +
            (0x80..0xFF).associateWith { "Reserved for use by individual payment systems" }

    /** A code no table names, printed with its hex so an analyst can find it in the raw octets. */
    private fun unknown(code: Int): String {
        val hex = code.toString(radix = 16).uppercase().padStart(2, '0')
        return "RFU/unknown (0x$hex)"
    }
}
