<h1 align="center">Tagscope</h1>

<p align="center">
  <strong>Turn the secret language of a chip card into plain, labelled English — one command.</strong><br>
  A Kotlin EMV <a href="https://en.wikipedia.org/wiki/X.690#BER_encoding">BER-TLV</a> parser, decoder &amp; tag-inspector CLI.
</p>

<p align="center">
  <a href="https://github.com/sebkoo/tagscope-emv-tlv/actions"><img alt="Build" src="https://github.com/sebkoo/tagscope-emv-tlv/actions/workflows/ci.yml/badge.svg"></a>
  <a href="https://central.sonatype.com/artifact/io.github.sebkoo/tagscope"><img alt="Maven Central" src="https://img.shields.io/maven-central/v/io.github.sebkoo/tagscope?label=Maven%20Central"></a>
  <img alt="Tests" src="https://img.shields.io/badge/tests-460%2B%20passing-brightgreen">
  <img alt="Runtime dependencies" src="https://img.shields.io/badge/runtime%20dependencies-0-success">
  <img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-blue">
  <br>
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white">
  <img alt="JVM" src="https://img.shields.io/badge/JVM-21-orange?logo=openjdk&logoColor=white">
  <img alt="EMVCo Book 3" src="https://img.shields.io/badge/verified%20vs-EMVCo%20Book%203%20v4.4-000000">
  <img alt="ISO/IEC 7816-4" src="https://img.shields.io/badge/ISO%2FIEC-7816--4-555555">
  <img alt="PAN-safe" src="https://img.shields.io/badge/PAN-masked%20by%20default-informational">
  <img alt="code style: ktlint" src="https://img.shields.io/badge/code%20style-ktlint-FF4081">
  <img alt="PRs welcome" src="https://img.shields.io/badge/PRs-welcome-brightgreen">
</p>

<p align="center">
  <img alt="Tagscope CLI demo: decoding EMV bytes into a labelled tag tree, masking a PAN, and failing a lint check" src="docs/demo.gif" width="880">
</p>

<p align="center">
  <em>Real output, every frame — the tree, the PAN masked by default, and a lint error that exits&nbsp;1.<br>
  Scripted with <a href="https://github.com/charmbracelet/vhs">VHS</a>; regenerate it with <code>vhs docs/demo.tape</code>.</em>
</p>

---

## What is this? (read this even if you've never seen code)

When you tap or insert a bank card, the little gold chip and the machine talk to each other
in a **packed secret code** — a stream of bytes like this:

```
6F15840E315041592E5359532E4444463031A503880101
```

That is not gibberish. It is a tidy little language called **EMV TLV**, and every card in your
wallet speaks it. **Tagscope is the decoder ring.** Feed it those bytes and it hands you back
something a human can actually read:

```
raw card bytes                Tagscope                      you can read it
──────────────────      parse → decode → label       ───────────────────────────────
6F 15 84 0E 31 50 …  ─────────────────────────────▶   6F  File Control Information
                                                         84  DF Name = "1PAY.SYS.DDF01"
                                                         A5  Proprietary Template
                                                           88  Short File Identifier = 01
```

That's the whole idea. **Scary bytes in, plain labels out.** No manual byte-counting, no
40-year-old spec PDF open in another window.

> **What Tagscope is *not*:** it is not a payment kernel, it performs no cryptography
> (SDA/DDA/CDA), and it does not replace EMVCo certification. It reads and explains the EMV
> **data model** — the tags, their structure, and what each one *means*. It's a fluent
> translator for EMV's language, not a terminal. That honesty is the point.

---

## Why it exists (the 2 a.m. story)

It's the middle of the night. A certification run is failing, an acquirer is waiting, and you
are staring at a Terminal Verification Results byte — `BC 50 BC 88 00` — trying to work out
*why* a transaction declined. Bit by bit. By hand. Against a table in Book 3.

Tagscope turns that byte into the answer in one command: **"offline data authentication was not
performed; SDA failed."** The thing that used to take a printout and twenty minutes takes a
second. That is the entire reason this exists — to give the bytes back their meaning when you
need it most.

---

## See it work

**Nested structure + text decoding** — a real Payment System Environment record:

```console
$ tagscope 6F15840E315041592E5359532E4444463031A503880101

6F      File Control Information (FCI) Template              [21]
  84    Dedicated File (DF) Name                             [14]  1PAY.SYS.DDF01
  A5    File Control Information (FCI) Proprietary Template  [3]
    88  Short File Identifier (SFI)                          [1]   01
```

**Bit-fields, in plain English** — this is the part a plain TLV dumper can't do. The two bytes
`1C 00` don't mean anything until you read them bit-by-bit:

```console
$ tagscope 770A82021C00940408010100

77    Response Message Template Format 2  [10]
  82  Application Interchange Profile     [2]   1C00
        - Cardholder verification is supported
        - Terminal risk management is to be performed
        - Issuer authentication is supported
  94  Application File Locator (AFL)      [4]   08010100
```

**The lists a terminal fills** — a PDOL is not a value but a *request*: the card telling the
terminal which objects to hand it, and how many octets each must be. Tagscope unpacks it into that
request, name-resolved:

```console
$ tagscope 9F380C9F33039F1A029F35019F4005

9F38  Processing Options Data Object List (PDOL)  [12]
      - 9F33  Terminal Capabilities  (3 bytes)
      - 9F1A  Terminal Country Code  (2 bytes)
      - 9F35  Terminal Type  (1 byte)
      - 9F40  Additional Terminal Capabilities  (5 bytes)
```

**Cardholder verification, spelled out** — the CVM List (`8E`) is a run of packed two-byte rules.
Tagscope reads the amount thresholds and turns each rule into its method and the condition it fires
under — exactly the "why did it ask for a PIN?" reading:

```console
$ tagscope 8E14000000000000000042014403410342031E031F03

8E  Cardholder Verification Method (CVM) List  [20]
      amounts: X=0  Y=0
      - Enciphered PIN verified online — If unattended cash (else apply next)
      - Enciphered PIN verification performed by ICC — If terminal supports the CVM (else apply next)
      - Plaintext PIN verification performed by ICC — If terminal supports the CVM (else apply next)
      - Enciphered PIN verified online — If terminal supports the CVM (else apply next)
      - Signature — If terminal supports the CVM (else fail)
      - No CVM required — If terminal supports the CVM (else fail)
```

**Card numbers are masked by default.** You have to *ask* to see one:

```console
$ tagscope 5A085570295626678085
5A  Application Primary Account Number (PAN)  [8]  ••• masked (use --reveal)

$ tagscope --reveal 5A085570295626678085
5A  Application Primary Account Number (PAN)  [8]  5570295626678085
```

**Machine-readable output** for pipelines and scripts:

```console
$ tagscope --json 6F15840E315041592E5359532E4444463031A503880101
[
  {
    "tag": "6F",
    "name": "File Control Information (FCI) Template",
    "class": "application",
    "constructed": true,
    "length": 21,
    "children": [
      {
        "tag": "84",
        "name": "Dedicated File (DF) Name",
        "class": "context-specific",
        "constructed": false,
        "length": 14,
        "value": "1PAY.SYS.DDF01",
        "hex": "315041592E5359532E4444463031"
      },
      {
        "tag": "A5",
        "name": "File Control Information (FCI) Proprietary Template",
        "class": "context-specific",
        "constructed": true,
        "length": 3,
        "children": [
          {
            "tag": "88",
            "name": "Short File Identifier (SFI)",
            "class": "context-specific",
            "constructed": false,
            "length": 1,
            "value": "01",
            "hex": "01"
          }
        ]
      }
    ]
  }
]
```

---

## Validate, don't just decode

Decoding tells you what the bytes *say*. A certification run needs to know whether what they say is
*self-consistent* — mandatory tags present, reserved bits clear, lists well-formed. `tagscope lint`
runs a set of spec-cited EMV rules over the decoded tree and reports what it finds, exiting non-zero
on any error so it can gate a script or a CI step the way a real cert check does.

Take a Cardholder Verification Method List that decodes without a murmur:

```console
$ tagscope 8E0A00000000000000002003

8E  Cardholder Verification Method (CVM) List  [10]
      amounts: X=0  Y=0
      - Reserved for use by the individual payment systems — If terminal supports the CVM (else fail)
```

It parses. But that method code, `0x20`, sits in Annex C3's payment-system-reserved range — not a
CVM a terminal is required to recognise. The linter says so, and names the rule and the tag:

```console
$ tagscope lint 8E0A00000000000000002003

1 finding: 1 warning

WARNING  cvm-well-formed  8E  CVM List (8E) names method 0x20, which is reserved for the payment systems, not a defined CVM
```

A *truncated* list — an odd octet where a two-byte CV Rule should be, exactly what a short read or
an erased record leaves behind — is caught before it ever reaches a kernel:

```console
$ tagscope lint 8E09000000000000000042

1 finding: 1 warning

WARNING  cvm-well-formed  8E  CVM List (8E) has a trailing octet that cannot complete a two-octet CV Rule
```

A missing *mandatory* element is an **error**, and errors set the exit code — so a bad SELECT
response fails a scripted check outright:

```console
$ tagscope lint 6F02A500

1 finding: 1 error

ERROR  fci-mandatory  6F  FCI template (6F) is missing mandatory DF Name (84)

$ echo $?
1
```

The rules today: mandatory FCI tags (`84`/`A5`, and a PPSE directory entry's `4F`); reserved bits
set in a bit field (AIP, TVR, the Issuer Action Codes, AUC); DOL anomalies (a duplicate or
zero-length entry, an unnamed tag); CVM List well-formedness and method codes; and any tag the
dictionary doesn't name. Each rule cites the EMV clause it enforces. A finding names a tag and
describes the defect — it **never** prints a value, so the report is safe to paste even for a record
that carries a PAN.

---

## Build the command, not just read the response

Everything above reads the card's half of the conversation. But a DOL isn't a value — it's a
*question*. When the card sends a PDOL it is telling the terminal: **hand me these elements, in this
order, in exactly these widths.** Tagscope could decode that question all day and still not answer
it. Now it can.

```kotlin
// A tag from the hex the spec prints it as.
fun tag(hex: String) = TlvTag(hex.toLong(16), hex.length / 2)

// pdol is the 9F38 the card sent, already decoded: (9F33,3)(9F1A,2)(9F35,1)(9F40,5)
val terminalData = mapOf(
    tag("9F33") to byteArrayOf(0xE0.toByte(), 0xF8.toByte(), 0xC8.toByte()), // Terminal Capabilities
    tag("9F1A") to byteArrayOf(0x08, 0x40),                                  // Terminal Country Code
    tag("9F35") to byteArrayOf(0x22),                                        // Terminal Type
)   // ...and nothing for 9F40: this terminal has no value for it

val command = DolEncoder.build(pdol, terminalData)   // EncodeResult.Success, 11 octets
```

```
E0 F8 C8   08 40   22     00 00 00 00 00
└─ 9F33 ┘  └9F1A┘  9F35   └─── 9F40 ───┘
  exact     exact   exact   absent → zeros
```

The command data carries **no tags and no lengths** — just values, back to back. That is what makes
the widths load-bearing: the card recovers each field's boundary by re-walking its own DOL, so one
field a byte short silently corrupts every field after it. Which is why fitting a value to its length
is not a `memcpy`. EMV Book 3 §5.4 gives different rules per direction, and which one applies depends
on the tag's format:

| The value is… | `n` (numeric) | `cn` (compressed numeric) | everything else |
|---|---|---|---|
| **too short** → pad | leading `00` | trailing `FF` | trailing `00` |
| **too long** → truncate | keep the **rightmost** | keep the **leftmost** | keep the **leftmost** |

The asymmetry is real, and it's the part worth getting right: `n` is right-justified, so its fill
sits on the left and *both* its rules act there; `cn` is left-justified, so both of its rules act on
the right. Either way the octets added or dropped are always padding, never a digit. A tag you supply
no value for fills with zeroes — the spec's own rule for an element the terminal doesn't hold.

So an authorised amount of 1234.56 handed to a CDOL1 entry six octets wide goes out the way a real
terminal sends it:

```
9F02  Amount, Authorised — format n, CDOL1 asks for 6 octets

supplied   12 34 56
built      00 00 00 12 34 56     ← leading zeroes, because n is right-justified
```

**What this is not.** It builds the command *data field* and nothing around it: no CLA/INS header, no
`Lc`, no `Le`, no cryptogram, and no decision about what to send or when. Tagscope still isn't a
kernel. It just no longer stops halfway through the sentence.

---

## Anatomy of an EMV transaction

The data objects Tagscope decodes are the messages of a chip/contactless transaction, exchanged in a
fixed order. Here is the spine of one — each step decoded *and* linted, the way you would walk a
trace looking for where it went wrong.

**1 — PPSE.** The terminal selects the Proximity Payment System Environment (`2PAY.SYS.DDF01`) and
the card answers with a directory of the applications it holds, each pointed at by an ADF Name
(`4F`):

```console
$ tagscope 6F20840E325041592E5359532E4444463031A50EBF0C0B61094F07A0000000031010

6F          File Control Information (FCI) Template              [32]
  84        Dedicated File (DF) Name                             [14]  2PAY.SYS.DDF01
  A5        File Control Information (FCI) Proprietary Template  [14]
    BF0C    Unknown                                              [11]
      61    Unknown                                              [9]
        4F  Application Dedicated File (ADF) Name                [7]   A0000000031010

$ tagscope lint 6F20840E325041592E5359532E4444463031A50EBF0C0B61094F07A0000000031010

2 findings: 2 info

INFO  unknown-tag  6F › A5 › BF0C       tag BF0C is not in the dictionary
INFO  unknown-tag  6F › A5 › BF0C › 61  tag 61 is not in the dictionary
```

**2 — SELECT (AID).** The terminal selects the chosen application by its AID; the card returns its
FCI — here carrying a **PDOL**, the card telling the terminal exactly what to pass to the next
command:

```console
$ tagscope 6F288407A0000000031010A51D5004564953418701019F380C9F33039F1A029F35019F40055F2D026465

6F        File Control Information (FCI) Template              [40]
  84      Dedicated File (DF) Name                             [7]   A0000000031010
  A5      File Control Information (FCI) Proprietary Template  [29]
    50    Application Label                                    [4]   VISA
    87    Application Priority Indicator                       [1]   01
    9F38  Processing Options Data Object List (PDOL)           [12]
          - 9F33  Terminal Capabilities  (3 bytes)
          - 9F1A  Terminal Country Code  (2 bytes)
          - 9F35  Terminal Type  (1 byte)
          - 9F40  Additional Terminal Capabilities  (5 bytes)
    5F2D  Language Preference                                  [2]   de
```

**3 — GET PROCESSING OPTIONS.** The terminal sends the PDOL-filled command — the one step of this
walk Tagscope can *build* as well as read, per *Build the command, not just read the response* above
— and the card returns the **AIP** (what it supports) and the **AFL** (which records the terminal
must now read):

```console
$ tagscope 770A82021C00940408010100

77    Response Message Template Format 2  [10]
  82  Application Interchange Profile     [2]   1C00
        - Cardholder verification is supported
        - Terminal risk management is to be performed
        - Issuer authentication is supported
  94  Application File Locator (AFL)      [4]   08010100
```

**4 — READ RECORD.** The terminal reads the records the AFL named. This is where the application
data lives — the CVM List, the Issuer Action Codes, the (masked) PAN — and where the linter earns
its keep on a real record, here surfacing the two CDOL tags this build does not yet name so an
analyst can triage them:

```console
$ tagscope lint 70818C9F420206435F25031603015F24032011305A0855702956266780855F3401029F0702FF008C219F02069F03069F1A0295055F2A029A039C019F37049F35019F45029F4C089F34038D0C910A8A0295059F37049F4C088E14000000000000000042014403410342031E031F039F0D05BC50BC88009F0E0500000800009F0F05BC70BC98005F280206439F4A0182

5 findings: 5 info

INFO  dol-entries  70 › 8C › 9F45  DOL 8C names tag 9F45, which the dictionary does not describe
INFO  dol-entries  70 › 8C › 9F4C  DOL 8C names tag 9F4C, which the dictionary does not describe
INFO  dol-entries  70 › 8D › 91    DOL 8D names tag 91, which the dictionary does not describe
INFO  dol-entries  70 › 8D › 8A    DOL 8D names tag 8A, which the dictionary does not describe
INFO  dol-entries  70 › 8D › 9F4C  DOL 8D names tag 9F4C, which the dictionary does not describe
```

From here a real kernel runs the cryptography, the terminal risk management, and the action analysis
that Tagscope deliberately does not — see *What this demonstrates* below. Tagscope's job is the data
model underneath all of it: read every object, name every tag, and say when they don't add up.

---

## Quick start

**Run it now (from source):**

```bash
git clone https://github.com/sebkoo/tagscope-emv-tlv.git
cd tagscope-emv-tlv
./gradlew :tagscope-cli:run --args="6F15840E315041592E5359532E4444463031A503880101"
```

**Use the library** — published to Maven Central as `io.github.sebkoo:tagscope`:

Gradle (Kotlin DSL):

```kotlin
dependencies {
    implementation("io.github.sebkoo:tagscope:0.2.0")
}
```

Maven:

```xml
<dependency>
  <groupId>io.github.sebkoo</groupId>
  <artifactId>tagscope</artifactId>
  <version>0.2.0</version>
</dependency>
```

```kotlin
import io.github.sebkoo.tagscope.tlv.TlvParser

when (val result = TlvParser.parse(bytes)) {
    is TlvResult.Success -> result.value.forEach(::printTree)   // walk the tag tree
    is TlvResult.Failure -> System.err.println(result.error)    // structured, offset-carrying
}
```

---

## What it does today

- **Parses** BER-TLV per ISO/IEC 7816-4 & EMVCo Book 3 — multi-byte tags, short/long length
  forms, correct handling of the constructed vs. primitive bit (including the classic `80`
  "looks-constructed-but-isn't" trap).
- **Decodes ~40 core EMV tags** into typed values: numbers, dates, amounts, text, and the
  bit-fields that matter — **AIP, CID, TVR-shaped IACs, AUC**.
- **Decodes Data Object Lists** — the **PDOL** and **CDOL1/CDOL2** a terminal fills for GET
  PROCESSING OPTIONS and GENERATE AC, unpacked into the typed `(tag, length)` entries each requests.
- **Decodes the CVM List (`8E`)** — the two amount thresholds and every cardholder-verification
  rule, each rendered as its method and the condition under which it applies.
- **Lints for EMV consistency** — a `tagscope lint` subcommand runs spec-cited rules over the
  decoded tree (mandatory FCI tags, reserved bits, DOL and CVM-List anomalies, unknown tags),
  reports findings by severity, and **exits non-zero on any error** so it can gate a script or CI.
- **Builds the command a terminal sends** — fills a PDOL or CDOL from a map of terminal data into
  the GET PROCESSING OPTIONS / GENERATE AC data field, applying EMV Book 3 §5.4's format-specific
  rules byte-exactly (`n` left-pads `00`, `cn` right-pads `FF`, everything else right-pads `00`; an
  element you don't supply fills with zeroes). The DOL layer now round-trips: read the question,
  write the answer.
- **Round-trips** — `encode(parse(x)) == x`, byte-for-byte. What it reads, it can write back.
- **Masks sensitive data by default** — PAN, Track 1/2 (incl. discretionary), PIN, and cardholder
  name are redacted unless you pass `--reveal`. See *Security* below for why this is impossible to
  get wrong.
- **A friendly CLI** — argument or stdin, a human tree or `--json`, clean exit codes, and error
  messages that never echo card data.
- **Zero runtime dependencies.** Nothing to audit, nothing to update, nothing to break.

### Tag coverage (a sample of the ~40 supported)

| Tag | Name | Kind | Decoded meaning |
|----|------|------|-----------------|
| `6F` | File Control Information | constructed | recurses into children |
| `84` | DF Name / AID | primitive | identifier bytes |
| `50` | Application Label | primitive | text (e.g. `VISA`) |
| `5A` | Application PAN | primitive | **masked** by default |
| `57` | Track 2 Equivalent Data | primitive | **masked** by default |
| `5F24` | Application Expiration Date | primitive | `YY-MM-DD` |
| `82` | Application Interchange Profile | primitive | bit-field → plain English |
| `95` | Terminal Verification Results | primitive | 5-byte bit-field |
| `9F27` | Cryptogram Information Data | primitive | `AAC` / `TC` / `ARQC` + reason |
| `9F0D/0E/0F` | Issuer Action Codes | primitive | TVR-shaped bit-fields |
| `9F38` | Processing Options DOL (PDOL) | primitive | list of `(tag, length)` entries |
| `8C/8D` | Card Risk Management DOLs (CDOL1/2) | primitive | list of `(tag, length)` entries |
| `8E` | Cardholder Verification Method List | primitive | amounts + CV rules → plain English |
| `80` | Response Template (Format 1) | **primitive** | opaque — *does not* recurse |
| `77` | Response Template (Format 2) | constructed | recurses into children |

Full list in the tag dictionary. Adding a tag is a one-line data change — see *Contributing*.

---

## Project status — the bird's-eye view

The library and command-line tool are **done and fully tested**. Here's the whole map:

| # | Milestone | Status |
|---|-----------|--------|
| 1–4 | Core parser — tag/length readers, recursive-descent tree, `80`-as-primitive | ✅ done |
| 5 | Tag dictionary (~40 core EMV tags) | ✅ done |
| 6 | Value decoders — numbers, dates, Track 2, AIP/TVR/CID/CVM bit-fields | ✅ done |
| 7 | `encode()` — the byte-exact inverse of parse | ✅ done |
| 8 | **Golden vectors** — 6 real EMV records, byte-verified vs. Book 3 | ✅ done |
| 9 | The **CLI** — decode, `--json`, PAN masking, `--reveal` | ✅ done |
| 10 | Comprehensive CLI test suite (**460+ tests total**) | ✅ done |
| 11 | This README + first public release | ✅ done |
| 12 | DOL parsing — PDOL/CDOL into typed (tag, length) entries | ✅ done |
| 13 | CVM-List (`8E`) — amounts + cardholder-verification rules | ✅ done |
| 14 | Wider tag & sensitive-field coverage (masking + named terminal tags) | ✅ done |
| 15 | **EMV consistency checker** (`lint`) — spec-cited rules + negative-test vectors | ✅ done |
| 16 | **Build command data from a DOL** (terminal side) + Java interop test | ✅ done |
| — | Publish to Maven Central | ✅ Published to Maven Central |

**460+ tests. Zero runtime dependencies. No real cardholder data anywhere in the repo.**

---

## How this was built — engineering discipline, not vibes

Tagscope was built commit-by-commit with an AI pair-engineering workflow. The value is in the
**discipline**, and it's worth spelling out because it's the same instinct EMV certification
work demands.

- **Prompt engineering** — every commit began from a precise, self-contained brief: one concern,
  explicit guardrails, a clear definition of done. Atomic, reviewable steps — never a sprawling
  "build me an EMV parser."
- **Context engineering** — a persistent `CLAUDE.md` encodes the project's non-negotiables (zero
  dependencies, *fixtures are the oracle*, *never log a PAN*). Every step is grounded in the real
  repository, not in assumptions. When a widely-published summary of a test vector disagreed with
  the raw bytes, **the bytes won** — and that correction became a test.
- **Harness engineering** — correctness is enforced *by construction*, not by hope. A **single
  masking site** makes a card-number leak structurally impossible; **golden vectors** verified
  byte-for-byte against EMVCo Book 3 are the source of truth; **mutation testing** proves the
  tests actually bite by breaking the code on purpose and confirming they turn red.
- **Loop engineering** — Plan → independent adversarial review → execute → verify → atomic commit.
  Every algorithmic step was checked by an independent reviewer whose only job was to *refute* it.
  That loop caught real defects — including a PAN that leaked through an argument-parsing error
  path the main design never touched.

Trust the bytes over the summary. Verify against the primary spec. Make the unsafe thing
*impossible*, not merely unlikely. That's the whole method.

---

## What this demonstrates — EMV data-model fluency, honestly scoped

EMV terminal and certification work rests on one foundation: reading the byte-level data model
fluently, and trusting the bytes when the documentation is wrong. That foundation is exactly what
Tagscope is — stated with its limits, because the honesty is the point.

| What EMV terminal & certification work involves | What Tagscope shows | What it deliberately is *not* |
|---|---|---|
| Terminal development & field-level troubleshooting | Byte-level fluency in the EMV data model — BER-TLV parsing, ~40 core tags, and TVR / AIP / CID / IAC bit-fields decoded to plain English: the exact reading you do when a terminal declines in the field | A terminal or payment kernel — no live transaction execution |
| Certification testing & defect resolution | The triage instinct cert work runs on — *trust the raw bytes over the published summary* (a real published-summary error is corrected and pinned in the tests) — plus a `lint` consistency checker whose spec-cited rules are exercised by **deliberately-malformed negative-test vectors** that reproduce each defect, and golden vectors verified byte-for-byte against **EMVCo Book 3 v4.4** | Certification experience — no payment-processor integration |
| EMV transaction flows & terminal parameters | Decodes the PDOL/CDOL the terminal fills for GPO / GENERATE AC into their `(tag, length)` entries **and builds the command data they ask for**, applying Book 3 §5.4's format-specific padding and truncation byte-exactly; fully decodes the AIP / CID / TVR action-analysis data and the IAC / AUC parameters, and walks a whole PPSE → SELECT → GPO → READ RECORD flow, decoding **and** linting each step | The flow engine or a parameter-management system — it builds the data field, never the APDU, the cryptogram or the decision |
| Java & Kotlin application development | Production **Kotlin/JVM**: idiomatic sealed types, **zero runtime dependencies**, **460+ tests**, green CI, byte-verified against the spec — and a **Java interop test** that drives the whole parse → decode → build path from Java, so the JVM API is proven from both languages | Large-scale application delivery — this is a focused library and CLI, not a system |
| Cross-functional certification collaboration | Collaboration-ready habits: honestly-scoped claims, atomic reviewable commits, and docs a QA or cert partner can follow | Team/cross-functional experience — this is a solo project |

**In one line:** this is the EMV data-model fluency and the byte-level verification discipline the
work rests on — *not* certification experience. Stating that plainly is the honest starting line
for the rest of the conversation.

---

## Security & PCI posture

- **Masked by default, everywhere.** All rendering flows through one function that redacts
  sensitive tags *before* either the tree or the JSON writer ever sees the bytes — so no output
  path can print a full PAN without an explicit `--reveal`.
- **PAN, Track 1/2, PIN — and cardholder name.** The PAN (5A) and both track images (56/57,
  including the discretionary fields 9F1F/9F20) and the PIN (99) are masked as card data; the
  cardholder name (5F20) is masked too, as a privacy default — it is PII, not card data, and
  `--reveal` shows it when you need it.
- **Keyed on the tag, not the decode.** A sensitive tag whose value fails to decode is *still*
  withheld, because masking is decided from the dictionary, not from the parsed result.
- **No real cardholder data.** Every test vector is published EMVCo/OpenSCDP sample data. Error
  messages carry offsets and counts — never value bytes.

This is a data-inspection tool, not a compliance product; it helps you *avoid* mishandling card
data, it does not by itself make a system PCI-compliant.

---

## Contributing

The highest-leverage contribution is **adding a tag** to the dictionary — it's a single typed
row, and the test harness does the rest. Bug reports with a hex vector that reproduces are gold.
See [`CONTRIBUTING.md`](CONTRIBUTING.md).

## License

Apache License 2.0 — permissive, with an explicit patent grant that matters in the
payments space. See `LICENSE`.

## Support

If Tagscope saves you a late night, a ⭐ on the repo is genuinely appreciated, and
[GitHub Sponsors](https://github.com/sponsors/sebkoo) helps keep the tag dictionary growing.

---

<sub>Tagscope is an independent open-source project. EMV® is a registered trademark of EMVCo, LLC.
Tagscope is not affiliated with, endorsed by, or certified by EMVCo. Built by
<a href="https://github.com/sebkoo">Ben Koo</a>.</sub>
