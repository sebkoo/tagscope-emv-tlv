# CLAUDE.md — Tagscope

Context for agents working in this repository. Durable facts only; anything derivable from
the code itself is deliberately not repeated here.

## What this is

Tagscope is a Kotlin/JVM library and CLI that parses EMV BER-TLV data — the byte structures
exchanged between a chip card and a payment terminal — into a labelled, nested tag tree, and
decodes the values of a curated set of tags.

## Scope boundary

Read this before adding anything.

Tagscope parses BER-TLV structure and decodes a curated subset of EMV tags. That is all it does.

It is **not** an EMV kernel. It performs no cryptography (ARQC/ARPC, MAC, SDA/DDA/CDA — tags
`9F26`, `9F10` and `9F46` are surfaced as opaque bytes and are never interpreted). It does not
execute the transaction flow, make terminal decisions, manage terminal or kernel parameters,
handle PINs, speak ISO 8583, or go online. It decodes the CVM List (`8E`) and CVM Results
(`9F34`) as data structures; it never processes a PIN or performs cardholder verification.

It also **writes** the two things it reads: `TlvEncoder` turns a tag tree back into octets, and
`DolEncoder` fills a DOL into the command data field a terminal would send. Both are data assembly,
not flow — `DolEncoder` builds the data field only, never the APDU header, `Lc`, `Le`, a cryptogram,
or the decision to send. That is inside the boundary and deliberately at its edge; do not extend it
into APDU construction or transaction execution.

Do not add features from that list, and do not describe the project as having them. If a task
seems to require kernel behaviour, stop and ask rather than widening the scope.

## Commands

```
./gradlew build          # compile, lint and test everything
./gradlew test           # tests only
./gradlew ktlintCheck    # lint (this gates CI)
./gradlew ktlintFormat   # apply lint fixes
```

CI runs exactly `./gradlew ktlintCheck test build` on every push and pull request.

## Conventions

- **Explicit API mode** is on in `:tagscope-lib`. Every public declaration needs an explicit
  visibility modifier and an explicit return type. It is deliberately off in `:tagscope-cli`.
- **Errors are returned, not thrown.** Parse failures are a sealed result type carrying the
  failing byte offset. No exceptions for malformed input — malformed input is expected input.
- **Pure functions.** Parsing and decoding take bytes and return values, with no I/O and no
  mutable shared state.
- **No nullable public returns.** Absence is modelled in the result type, not as `null`.
- **Fixtures are the oracle.** The test vectors in `tagscope-lib/src/test/resources/vectors/`
  are hand-verified against the raw bytes. When a test fails, the code is wrong. Never edit a
  fixture or an expected value to make a test pass. If you believe a fixture is genuinely
  wrong, say so explicitly and justify it byte by byte.
- **Every bug becomes a regression vector.** Before fixing a defect, add the input that
  reproduces it to the vector suite.
- **The dictionary's length bounds are advisory.** `TagInfo.minLength` and `maxLength` say what
  EMV states a field normally holds. They are not a validation gate, and nothing may start
  rejecting a data object for falling outside them: EMV states no minimum for a variable-length
  field, and `6F 00` — an empty template — is well-formed BER-TLV that the parser accepts. The
  parser decides what is well-formed; the dictionary only says what to expect.
- **Only `00` is filler.** ISO/IEC 7816-4 §5.2.2.1 permits both `00` and `FF` octets without
  meaning before, between and after data objects; EMV Book 3, Annex B1 permits only `00`. This
  library parses EMV data, so the parser skips `00` at any object boundary and reports a stray
  `FF` as `TlvError.UnexpectedFillerOctet`. That is deliberate for an inspection tool: an `FF`
  where a tag should begin usually means a short read or an erased record, which is the thing
  the user is looking for. Do not widen the filler set to match ISO; if a lenient mode is ever
  wanted, it belongs behind an explicit option, never in the default.

## Card data rules

Never commit, log, print, or place in a commit message a real PAN or real track data. All
fixtures are synthetic or drawn from published specification samples.

PAN (`5A`) and Track 2 Equivalent Data (`57`) are masked in all output by default. Revealing
them is an explicit opt-in via `--reveal`. Do not change that default and do not add a code
path that logs an unmasked PAN.

## Where the facts live

- `tagscope-lib/src/main/kotlin/io/github/sebkoo/tagscope/tags/TagDictionary.kt` — the tag
  dictionary: tag, name, format, length bounds, an optional note and a sensitive flag. Class and
  constructed-ness are read off the identifier octets rather than stored beside them, so the
  dictionary cannot contradict the wire. Adding a tag means editing this table, not the parser.
- `tagscope-lib/src/test/resources/vectors/*.hex` — golden test vectors, one hex data object per
  file with a `#` provenance header. They are test-only: no runtime code reads them and no public
  API exposes them, so they stay out of `src/main` and out of the published JAR. The expected
  trees and decoded values live beside them in the typed `GoldenVectors` Kotlin table (same test
  package), compiler-checked against the decoder API rather than parsed from a JSON sidecar.

The dictionary is a Kotlin table and not the `tags.json` this file first named. **The library has
no runtime dependencies and is to keep none.** A JSON resource would cost either a JSON library or
a hand-rolled reader with its own malformed-input tests, to parse data that is fixed at build time
and checked by the compiler as it stands. Do not convert it to a resource file in order to add a
tag.

## Build notes

- **Gradle 9 requires an explicit `junit-platform-launcher`.** Gradle no longer injects it, so
  any module calling `useJUnitPlatform()` must declare
  `testRuntimeOnly(libs.junit.platform.launcher)` or every test task fails with
  "Failed to load JUnit Platform".
- **detekt is intentionally absent.** The latest stable detekt (1.23.8) embeds the Kotlin
  2.0.21 compiler, which cannot read class metadata from Kotlin 2.2 or later, and this project
  is on Kotlin 2.4. detekt 2.0 is still alpha. Do not add detekt back until 2.0 is stable.
  ktlint is the static-analysis gate until then.
- **The daemon runs on a newer JDK than the build targets.** Toolchain 21 is auto-provisioned
  by the foojay resolver. On a machine whose default JVM is 24 or newer, ktlint's embedded
  Kotlin compiler emits `sun.misc.Unsafe` deprecation warnings. They are benign and do not
  appear in CI, which runs on JDK 21. Do not chase them.

## Commit identity

This repository commits under the GitHub noreply address
`61488202+sebkoo@users.noreply.github.com`, configured repo-locally and never globally, so no
real address enters public git history. Do not change it to a real address.

Commits follow Conventional Commits and stay single-concern.

## Never put in this repository

- Real cardholder data: PANs, track data, cardholder names, expiry dates from real cards.
- Processor-proprietary or NDA material: certification test scripts, letters of approval,
  host specifications from Fiserv, TSYS, Elavon or any other processor.
- Whole specification PDFs. Cite the book and section instead, for example
  "EMV Book 3, Annex B" or "ISO/IEC 7816-4 §5.2".
- Secrets, keys, or credentials of any kind.

## Glossary

- **TLV / BER-TLV** — Tag-Length-Value encoding as used by EMV, a subset of ASN.1 BER.
- **Primitive** — a data object whose value is a terminal value.
- **Constructed** — a data object whose value is itself a sequence of TLV objects. Signalled by
  bit 6 (`0x20`) of the first tag byte. `6F`, `70`, `77` and `A5` are constructed templates.
  `80` is primitive despite carrying structured data, which is a common source of parser bugs.
- **Template** — a constructed data object that groups related objects, such as the FCI (`6F`).
- **DOL** — Data Object List: a list of tag-and-length pairs, with no values, telling one party
  which objects to supply and how long each must be.
- **PDOL** (`9F38`) — the DOL the card sends in the FCI, listing what the terminal must pass to
  GET PROCESSING OPTIONS.
- **CDOL1** (`8C`) / **CDOL2** (`8D`) — the DOLs the terminal fills for the first and second
  GENERATE AC commands.
- **AIP** (`82`) — Application Interchange Profile: a bit field stating which functions the card
  supports, such as SDA, DDA, cardholder verification and terminal risk management.
- **AFL** (`94`) — Application File Locator: which records the terminal must read.
- **TVR** (`95`) — Terminal Verification Results: a five-byte bit field recording what the
  terminal observed during the transaction. Compared against the Issuer Action Codes
  (`9F0D`, `9F0E`, `9F0F`) during action analysis.
- **ATC** (`9F36`) — Application Transaction Counter, incremented by the card per transaction.
- **CID** (`9F27`) — Cryptogram Information Data: the top bits say which cryptogram the card
  returned. `0x00` AAC (decline), `0x40` TC (offline approve), `0x80` ARQC (go online).
- **FCI** — File Control Information, returned by SELECT.
- **EMV Book 3** — EMVCo Integrated Circuit Card Specifications, Book 3 (Application
  Specification). Annex A is the Data Elements Dictionary (A1 by name, A2 by tag) and is the
  reference for the tag dictionary. Annex B gives the rules for BER-TLV data objects — the tag,
  length and value coding this parser implements.
- **ISO/IEC 7816-4** — defines the BER-TLV encoding rules EMV builds on.

"EMV" is a trademark of EMVCo LLC and is used here descriptively. This project is not
endorsed by or affiliated with EMVCo.
