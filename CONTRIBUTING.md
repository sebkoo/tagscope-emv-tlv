# Contributing to Tagscope

Thanks for your interest. The highest-leverage contribution is **adding a tag** to the
dictionary — it's a single typed row and the test harness does the rest. Bug reports that come
with a hex vector reproducing the defect are gold: every fixed bug becomes a regression vector.

## Building & testing

```bash
./gradlew build                 # compile, lint and test everything
./gradlew :tagscope-lib:test    # library tests only
```

CI runs `./gradlew ktlintCheck test build` on every push and pull request. Match it locally
before opening a PR.

## Code style

[ktlint](https://pinterest.github.io/ktlint/) is the static-analysis gate.

```bash
./gradlew ktlintCheck    # verify (gates CI)
./gradlew ktlintFormat   # apply fixes
```

`:tagscope-lib` runs in **explicit API mode** — every public declaration needs an explicit
visibility modifier and return type. The library has, and keeps, **zero runtime dependencies**.

## Adding a tag

Edit the dictionary table in
`tagscope-lib/src/main/kotlin/io/github/sebkoo/tagscope/tags/TagDictionary.kt` — a tag's class
and constructed-ness are read off the identifier octets, not stored, so the dictionary cannot
contradict the wire. Add a golden vector under `tagscope-lib/src/test/resources/vectors/` with a
`#` provenance header when the change is worth pinning byte-for-byte.

## Releasing

The exact process used for a release (maintainers):

1. Bump `gradle.properties` to the release version (drop `-SNAPSHOT`); commit.
2. Tag and publish the GitHub Release:
   ```bash
   git tag -a vX.Y.Z -m "…"
   git push origin vX.Y.Z
   gh release create vX.Y.Z
   ```
3. Publish to Maven Central (signing key supplied via env, never committed):
   ```bash
   ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --export-secret-keys --armor <KEYID>)" \
     ./gradlew :tagscope-lib:publishToMavenCentral
   ```
4. In the [Central Portal](https://central.sonatype.com/) → **Deployments**, confirm the
   deployment is **VALIDATED**, then click **Publish**.
5. Bump `gradle.properties` to the next `-SNAPSHOT`; commit.

> **Credentials never live in the repo.** `mavenCentralUsername` / `mavenCentralPassword` and the
> signing key and passphrase belong only in `~/.gradle/gradle.properties` or environment
> variables.

> **GPG signing from a non-interactive shell.** If `publishToMavenCentral` fails at
> `signMavenPublication` with `Could not read PGP secret key`, and the `gpg --export-secret-keys`
> output shows `no pinentry` or `error receiving key from agent: Inappropriate ioctl for device`,
> gpg-agent has no terminal to prompt for the key passphrase. Run the publish from a real terminal
> (Terminal.app / iTerm, not an editor's task runner or an agent shell) and set the terminal for
> the agent first:
> ```bash
> export GPG_TTY=$(tty)
> ```
> Then run the publish command in that same terminal so pinentry can prompt.
