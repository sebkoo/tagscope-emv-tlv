# Tagscope

Kotlin EMV BER-TLV parser, decoder & tag inspector CLI.

Tagscope turns raw chip-card and terminal APDU hex into a labelled, nested EMV tag tree — as a
JVM library and as an offline command-line tool.

It is not an EMV kernel. It runs no cryptography, executes no transaction flow, and manages no
terminal parameters; cryptogram tags are surfaced as opaque bytes. It reads bytes and tells you
what they mean.

## Status

Work in progress. This commit is the build skeleton only — no parser yet. The tag readers,
recursive-descent parser, tag dictionary, value decoders, test vectors and CLI land in
subsequent commits.

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
