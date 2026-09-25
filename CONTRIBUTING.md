# Contributing

Bug reports, fixes and new agent adapters are welcome. For a larger change, open an issue
first so we can agree on the approach.

## Set up

The flake provides everything: JDK 17, the Android SDK, adb, ktlint and nixfmt.

```sh
nix develop          # or `direnv allow` with nix-direnv
cd android
./gradlew :app:assembleDebug
```

Connect a phone with USB debugging (or `adb pair` over Wi-Fi) and run:

```sh
nix run .#install    # build, install and start the debug app
nix run .#logcat     # the app's log
```

The debug app is `dev.herdroid.debug` and has its own key and saved servers, separate from
a release install.

## Tests

| Command | What it runs |
| --- | --- |
| `nix run .#test` | JVM unit tests in `core`: parsers, prompt detection, thread assembly, the outbox. |
| `nix run .#device-test` | Instrumented tests on the connected phone or emulator. |
| `nix flake check` | Formatting: ktlint for Kotlin, nixfmt for Nix. |

CI runs the unit tests, lint, the format check and a release build on every pull request.

Some core tests talk to a real machine and are skipped unless you turn them on:

- `HERDROID_LIVE=1` runs the tests against the local herdr server and the agent
  transcripts in your home folder.
- `HERDROID_SSHD_PORT` and `HERDROID_SSHD_AUTHKEYS` point `SshLiveTest` at a
  throwaway sshd. See the test for details.

## Fixtures

Parser tests read fixtures from `android/core/src/test/resources/fixtures`. Never commit a
real transcript or screen capture as is: they contain your prompts, code and paths.

- For transcripts, `scripts/scrub_transcript_fixture.py` copies records and replaces every
  free-text string while keeping the structure.
- For prompt screens, capture them from throwaway agents in a scratch folder, as described
  in `fixtures/prompts/README.md`.

## Style

- Run `ktlint -F 'android/**/*.kt'` before committing. CI rejects unformatted code.
- Match the surrounding code. Comments explain only what the code cannot say: an external
  fact, a constraint, or why an obvious alternative does not work.
- Commit subjects are lowercase and imperative, for example `show kimi approvals as cards`.
  The body says why.
- Update the docs in the same pull request as the change.

## Adding an agent

A chat needs a transcript parser. Add the kind to `AgentKind`, implement
`TranscriptParser` for its file format, teach `TranscriptSource` where the files live, and
add a scrubbed fixture with a test. Prompt cards usually work without changes. If the
agent draws its questions differently, add a captured screen to `fixtures/prompts` and a
test in `PromptParserTest`.

## Releasing

Maintainers release by pushing a tag:

1. Move the `Unreleased` notes in `CHANGELOG.md` under the new version and commit.
2. `git tag v0.2.0 && git push origin v0.2.0`

The release workflow builds the APK with the version from the tag, signs it, and publishes
a GitHub release with the changelog section, the checksum and the certificate
fingerprint. It needs these repository secrets:

| Secret | Value |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The release keystore, base64 encoded |
| `RELEASE_KEYSTORE_PASSWORD` | Keystore password |
| `RELEASE_KEY_ALIAS` | Key alias |
| `RELEASE_KEY_PASSWORD` | Key password |

Keep a backup of the keystore outside GitHub. Android refuses an update signed with a
different key, so losing it means every user must uninstall and reinstall.
