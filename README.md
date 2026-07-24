<h1 align="center">Tagscope</h1>

<p align="center">
  <strong>Turn the secret language of a chip card into plain, labelled English — one command.</strong><br>
  A Kotlin EMV <a href="https://en.wikipedia.org/wiki/X.690#BER_encoding">BER-TLV</a> parser, decoder &amp; tag-inspector CLI.
</p>

<p align="center">
  <a href="https://github.com/sebkoo/tagscope-emv-tlv/actions"><img alt="Build" src="https://github.com/sebkoo/tagscope-emv-tlv/actions/workflows/ci.yml/badge.svg"></a>
  <img alt="Tests" src="https://img.shields.io/badge/tests-360%20passing-brightgreen">
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

## Quick start

**Run it now (from source):**

```bash
git clone https://github.com/sebkoo/tagscope-emv-tlv.git
cd tagscope-emv-tlv
./gradlew :tagscope-cli:run --args="6F15840E315041592E5359532E4444463031A503880101"
```

**Use the library** (Maven Central coordinate — *planned*, once published):

```kotlin
dependencies {
    implementation("io.github.sebkoo:tagscope:0.1.0")
}
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
- **Round-trips** — `encode(parse(x)) == x`, byte-for-byte. What it reads, it can write back.
- **Masks sensitive data by default** — PAN (5A) and Track 2 (57) are redacted unless you pass
  `--reveal`. See *Security* below for why this is impossible to get wrong.
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
| 10 | Comprehensive CLI test suite (**360 tests total**) | ✅ done |
| 11 | This README + first public release | 🔜 now |
| 12+ | DOL parsing · CVM-List decoding · wider tag & sensitive-field coverage | ⬜ planned |
| — | Publish to Maven Central | ⬜ planned |

**360 tests. Zero runtime dependencies. No real cardholder data anywhere in the repo.**

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
| Certification testing & defect resolution | The triage instinct cert work runs on — *trust the raw bytes over the published summary* (a real published-summary error is corrected and pinned in the tests) — plus golden vectors verified byte-for-byte against **EMVCo Book 3 v4.4** and mutation-tested | Certification experience — no payment-processor integration |
| EMV transaction flows & terminal parameters | Recognizes the PDOL/CDOL a terminal fills for GPO / GENERATE AC, and fully decodes the AIP / CID / TVR action-analysis data and the IAC / AUC parameters themselves | The flow engine or a parameter-management system |
| Java & Kotlin application development | Production **Kotlin/JVM**: idiomatic sealed types, **zero runtime dependencies**, **360 tests**, green CI, byte-verified against the spec | (The codebase is Kotlin on the JVM; Java interop is native but not exercised here) |
| Cross-functional certification collaboration | Collaboration-ready habits: honestly-scoped claims, atomic reviewable commits, and docs a QA or cert partner can follow | Team/cross-functional experience — this is a solo project |

**In one line:** this is the EMV data-model fluency and the byte-level verification discipline the
work rests on — *not* certification experience. Stating that plainly is the honest starting line
for the rest of the conversation.

---

## Security & PCI posture

- **Masked by default, everywhere.** All rendering flows through one function that redacts
  sensitive tags *before* either the tree or the JSON writer ever sees the bytes — so no output
  path can print a full PAN without an explicit `--reveal`.
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
See `CONTRIBUTING.md` (coming with the first release).

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
