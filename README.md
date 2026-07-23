# Tagscope

Kotlin EMV BER-TLV parser, decoder & tag inspector CLI.

Tagscope turns raw chip-card and terminal APDU hex into a labelled, nested EMV tag tree — as a
JVM library and as an offline command-line tool.

It is not an EMV kernel. It runs no cryptography, executes no transaction flow, and manages no
terminal parameters; cryptogram tags are surfaced as opaque bytes. It reads bytes and tells you
what they mean.

## Status

Work in progress. In place so far: the BER-TLV tag and length octet readers, a recursive-descent
parser that turns a payload into a nested tag tree, a ~43-tag EMV dictionary that labels each tag,
and scalar value decoders (`n`, `cn`, `an`, `ans`, dates and amounts). Still to come: Track 2, the
bit-field decoders, the test vectors and the CLI. This README is itself a stub; a fuller one lands
with them.

## Build

```
./gradlew build
```

Requires a JVM to run Gradle; the Java 21 toolchain used for compilation is provisioned
automatically.

## License

Apache-2.0. See [LICENSE](LICENSE).

"EMV" is a trademark of EMVCo LLC, used here descriptively. This project is not endorsed by or
affiliated with EMVCo.
