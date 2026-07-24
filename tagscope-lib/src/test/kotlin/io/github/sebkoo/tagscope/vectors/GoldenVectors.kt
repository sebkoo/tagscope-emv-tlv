package io.github.sebkoo.tagscope.vectors

// Bit-field flag lists, one set bit per line, byte then bit descending (the decoder's order).
// Meanings are transcribed from decode.BitFieldSpec and audited against EMV Book 3 by review.
// Declared before VECTORS because a top-level val is initialised in file order.

/** AIP 1C00 (82): byte 1 bits b5, b4, b3. Book 3 Annex C1, Table 41. */
private val AIP_1C00: List<ExpectedDecode.Flag> =
    listOf(
        flag(0, 5, "Cardholder verification is supported"),
        flag(0, 4, "Terminal risk management is to be performed"),
        flag(0, 3, "Issuer authentication is supported"),
    )

/** AUC FF00 (9F07): byte 1 all eight bits set. Book 3 Annex C2, Table 42. */
private val AUC_FF00: List<ExpectedDecode.Flag> =
    listOf(
        flag(0, 8, "Valid for domestic cash transactions"),
        flag(0, 7, "Valid for international cash transactions"),
        flag(0, 6, "Valid for domestic goods"),
        flag(0, 5, "Valid for international goods"),
        flag(0, 4, "Valid for domestic services"),
        flag(0, 3, "Valid for international services"),
        flag(0, 2, "Valid at ATMs"),
        flag(0, 1, "Valid at terminals other than ATMs"),
    )

/** IAC-Default BC50BC8800 (9F0D), TVR-shaped. Book 3 Annex C5, Table 46. */
private val IAC_DEFAULT_BC50BC8800: List<ExpectedDecode.Flag> =
    listOf(
        flag(0, 8, "Offline data authentication was not performed"),
        flag(0, 6, "ICC data missing"),
        flag(0, 5, "Card appears on terminal exception file"),
        flag(0, 4, "DDA failed"),
        flag(0, 3, "CDA failed"),
        flag(1, 7, "Expired application"),
        flag(1, 5, "Requested service not allowed for card product"),
        flag(2, 8, "Cardholder verification was not successful"),
        flag(2, 6, "PIN Try Limit exceeded"),
        flag(2, 5, "PIN entry required and PIN pad not present or not working"),
        flag(2, 4, "PIN entry required, PIN pad present, but PIN was not entered"),
        flag(2, 3, "Online CVM captured"),
        flag(3, 8, "Transaction exceeds floor limit"),
        flag(3, 4, "Merchant forced transaction online"),
    )

/** IAC-Denial 0000080000 (9F0E), TVR-shaped: byte 3 bit b4 alone. */
private val IAC_DENIAL_0000080000: List<ExpectedDecode.Flag> =
    listOf(
        flag(2, 4, "PIN entry required, PIN pad present, but PIN was not entered"),
    )

/** IAC-Online BC70BC9800 (9F0F), TVR-shaped. Book 3 Annex C5, Table 46. */
private val IAC_ONLINE_BC70BC9800: List<ExpectedDecode.Flag> =
    listOf(
        flag(0, 8, "Offline data authentication was not performed"),
        flag(0, 6, "ICC data missing"),
        flag(0, 5, "Card appears on terminal exception file"),
        flag(0, 4, "DDA failed"),
        flag(0, 3, "CDA failed"),
        flag(1, 7, "Expired application"),
        flag(1, 6, "Application not yet effective"),
        flag(1, 5, "Requested service not allowed for card product"),
        flag(2, 8, "Cardholder verification was not successful"),
        flag(2, 6, "PIN Try Limit exceeded"),
        flag(2, 5, "PIN entry required and PIN pad not present or not working"),
        flag(2, 4, "PIN entry required, PIN pad present, but PIN was not entered"),
        flag(2, 3, "Online CVM captured"),
        flag(3, 8, "Transaction exceeds floor limit"),
        flag(3, 5, "Transaction selected randomly for online processing"),
        flag(3, 4, "Merchant forced transaction online"),
    )

/** CID 00 (9F27): no flags set; both enums select their zero value. Book 3 Table 15. */
private val CID_00_AAC: List<ExpectedDecode.Selection> =
    listOf(
        sel(0, "Cryptogram type", 0, "AAC"),
        sel(0, "Reason/advice code", 0, "No information given"),
    )

/**
 * The six golden vectors, as data. Adding a vector is a `.hex` file plus one row here.
 *
 * Structure, decoded values and depth are written from the raw octets by hand — never generated
 * from the parser, or the suite would be checking the code against itself. The bit-field meanings
 * are transcribed from `decode.BitFieldSpec`, so a vector pins whatever that table emits; the
 * independent spec review cross-checks those meanings against EMV Book 3, which the bytes alone
 * cannot.
 *
 * A published EMV / OpenSCDP provenance is recorded on every row and in every fixture file. None of
 * it is live cardholder data.
 */
internal val VECTORS: List<GoldenVector> =
    listOf(
        // 1 - PSE FCI. Nested constructed templates; the DF Name is printable ASCII, so tag 84
        // decodes to Text ("1PAY.SYS.DDF01") though Annex A formats it BINARY. The value octets are
        // unchanged — only the reading is text — so the structural hex below stays the same.
        GoldenVector(
            name = "PSE FCI",
            hexFile = "01-pse-fci.hex",
            provenance = "Published EMV / OpenSCDP PSE (1PAY.SYS.DDF01) SELECT sample",
            depth = 3,
            tree =
                tlv(
                    "6F",
                    0x15,
                    leaf("84", 0x0E, text("1PAY.SYS.DDF01"), value = "315041592E5359532E4444463031"),
                    tlv(
                        "A5",
                        0x03,
                        leaf("88", 0x01, raw("01"), value = "01"),
                    ),
                ),
        ),
        // 2 - Visa AID FCI with PDOL. 84 AID is hex; 9F38 decodes to its four PDOL (tag, length)
        // entries — (9F33,3)(9F1A,2)(9F35,1)(9F40,5). 87 is RawBinary despite its "bit field" note.
        GoldenVector(
            name = "Visa AID FCI + PDOL",
            hexFile = "02-visa-fci-pdol.hex",
            provenance = "Published EMV / OpenSCDP Visa (A0000000031010) SELECT sample",
            depth = 3,
            tree =
                tlv(
                    "6F",
                    0x28,
                    leaf("84", 0x07, raw("A0000000031010"), value = "A0000000031010"),
                    tlv(
                        "A5",
                        0x1D,
                        leaf("50", 0x04, text("VISA"), value = "56495341"),
                        leaf("87", 0x01, raw("01"), value = "01"),
                        leaf(
                            "9F38",
                            0x0C,
                            dol("9F33" to 3, "9F1A" to 2, "9F35" to 1, "9F40" to 5),
                            value = "9F33039F1A029F35019F4005",
                        ),
                        leaf("5F2D", 0x02, text("de"), value = "6465"),
                    ),
                ),
        ),
        // 3 - READ RECORD, long-form length 81 8C. PAN masked by default; dates keep a two-digit
        // year; the DOLs 8C/8D decode to their (tag, length) entries and the CVM List 8E to its
        // amounts and CV Rules; the IACs are TVR-shaped bit fields; 9F4A Unknown.
        GoldenVector(
            name = "READ RECORD",
            hexFile = "03-read-record.hex",
            provenance = "Published EMV / OpenSCDP READ RECORD sample",
            depth = 2,
            tree =
                tlv(
                    "70",
                    0x8C,
                    leaf("9F42", 0x02, digits("0643"), value = "0643"),
                    leaf("5F25", 0x03, date(16, 3, 1), value = "160301"),
                    leaf("5F24", 0x03, date(20, 11, 30), value = "201130"),
                    // PAN octets left unpinned here on purpose: the reveal test is the one place the
                    // digits appear, and the round-trip layer already guarantees the bytes.
                    leaf("5A", 0x08, ExpectedDecode.Sensitive),
                    leaf("5F34", 0x01, digits("02"), value = "02"),
                    leaf("9F07", 0x02, bits(flags = AUC_FF00), value = "FF00"),
                    leaf(
                        "8C",
                        0x21,
                        dol(
                            "9F02" to 6,
                            "9F03" to 6,
                            "9F1A" to 2,
                            "95" to 5,
                            "5F2A" to 2,
                            "9A" to 3,
                            "9C" to 1,
                            "9F37" to 4,
                            "9F35" to 1,
                            "9F45" to 2,
                            "9F4C" to 8,
                            "9F34" to 3,
                        ),
                        value = "9F02069F03069F1A0295055F2A029A039C019F37049F35019F45029F4C089F3403",
                    ),
                    leaf(
                        "8D",
                        0x0C,
                        dol("91" to 10, "8A" to 2, "95" to 5, "9F37" to 4, "9F4C" to 8),
                        value = "910A8A0295059F37049F4C08",
                    ),
                    // 8E CVM List: amounts X=0, Y=0, then six CV Rules. Derived byte-for-byte from
                    // 00000000 00000000 4201 4403 4103 4203 1E03 1F03 — apply-next (0x40) set on the
                    // first four codes, clear on the last two; methods 02/04/01/02/1E/1F; conditions
                    // 01 then 03 five times. Book 3 Annex C3, Tables 43 and 44.
                    leaf(
                        "8E",
                        0x14,
                        cvmList(
                            amountX = 0,
                            amountY = 0,
                            rule(0x02, applyNextIfFailed = true, conditionCode = 0x01),
                            rule(0x04, applyNextIfFailed = true, conditionCode = 0x03),
                            rule(0x01, applyNextIfFailed = true, conditionCode = 0x03),
                            rule(0x02, applyNextIfFailed = true, conditionCode = 0x03),
                            rule(0x1E, applyNextIfFailed = false, conditionCode = 0x03),
                            rule(0x1F, applyNextIfFailed = false, conditionCode = 0x03),
                        ),
                        value = "000000000000000042014403410342031E031F03",
                    ),
                    leaf("9F0D", 0x05, bits(flags = IAC_DEFAULT_BC50BC8800), value = "BC50BC8800"),
                    leaf("9F0E", 0x05, bits(flags = IAC_DENIAL_0000080000), value = "0000080000"),
                    leaf("9F0F", 0x05, bits(flags = IAC_ONLINE_BC70BC9800), value = "BC70BC9800"),
                    leaf("5F28", 0x02, digits("0643"), value = "0643"),
                    leaf("9F4A", 0x01, ExpectedDecode.Unknown, value = "82"),
                ),
        ),
        // 4 - GENERATE AC Format 2. CID 00 is AAC (decline); ATC is RawBinary, not Digits; the
        // cryptogram and IAD are opaque and must never be interpreted.
        GoldenVector(
            name = "GENERATE AC (Format 2)",
            hexFile = "04-generate-ac-fmt2.hex",
            provenance = "Published EMV / OpenSCDP GENERATE AC (Format 2) sample",
            depth = 2,
            tree =
                tlv(
                    "77",
                    0x29,
                    leaf("9F27", 0x01, bits(selections = CID_00_AAC), value = "00"),
                    leaf("9F36", 0x02, raw("0041"), value = "0041"),
                    leaf("9F26", 0x08, rawOpaque(0x08)),
                    leaf("9F10", 0x12, rawOpaque(0x12)),
                ),
        ),
        // 5 - GPO Format 1. 80 is primitive: no recursion, six opaque octets. The AIP bits are
        // pinned via Vector 6; GoldenVectorTest cross-checks that the payloads are byte-identical.
        GoldenVector(
            name = "GPO (Format 1, primitive 80)",
            hexFile = "05-gpo-fmt1.hex",
            provenance = "Published EMV / OpenSCDP GET PROCESSING OPTIONS (Format 1) sample",
            depth = 1,
            tree = leaf("80", 0x06, rawOpaque(0x06), value = "1C0008010100"),
        ),
        // 6 - GPO Format 2. 77 is constructed: the same payload as Vector 5, now self-describing.
        // 82 AIP decodes to a bit field; 94 AFL stays opaque.
        GoldenVector(
            name = "GPO (Format 2, constructed 77)",
            hexFile = "06-gpo-fmt2.hex",
            provenance = "Published EMV / OpenSCDP GET PROCESSING OPTIONS (Format 2) sample",
            depth = 2,
            tree =
                tlv(
                    "77",
                    0x0A,
                    leaf("82", 0x02, bits(flags = AIP_1C00), value = "1C00"),
                    leaf("94", 0x04, raw("08010100"), value = "08010100"),
                ),
        ),
    )
