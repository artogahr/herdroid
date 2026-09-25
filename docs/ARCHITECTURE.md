# Architecture

Herdroid is a single Android app with no server component. Everything it knows comes from
three sources on the herdr machine, all reached over SSH.

```
Compose UI (app/ui)
  -> Connection, HerdrSession        app/data: saved servers, reconnect, live snapshot
  -> ThreadController                app/thread: one chat, its outbox and prompt card
  -> RemoteTerminal                  app/terminal: terminal view over herdr's terminal stream
       -> HerdrApi, TranscriptSource core: herdr API client, transcript reading and parsing
            -> SshHostTransport      core/transport: one sshj connection, one channel per job
```

## Modules

| Module | Contents |
| --- | --- |
| `android/core` | Plain Kotlin with no Android UI: the SSH transport, the herdr API client and models, transcript parsers, the prompt parser, and thread assembly. Unit tests run on the JVM. |
| `android/app` | The Compose UI, connection state, saved servers, the device key, and the glue between core and the screens. |
| `android/terminal` | The Termux terminal emulator and view, changed to read from a remote byte stream instead of a local process. See its `NOTICE.md`. |

## SSH transport

`SshHostTransport` holds one sshj connection per server. Each job runs as its own exec
channel:

- A **request** runs `herdr remote-api-bridge`, writes one JSON line and reads one reply.
  The herdr API answers one request per connection, so every request gets a new channel.
- A **stream** is a long-running command, such as `events.subscribe`, `tail -F` or
  `herdr terminal session observe`. `tail -F` and `observe` do not exit when the channel
  closes, so streams run under a small shell wrapper that kills the command's process
  group when stdin reaches end of file.

Commands are wrapped in `sh -c` because sshd runs them through the user's login shell,
which may be fish or another non-POSIX shell. The herdr binary is found once per
connection with `$SHELL -lc 'command -v herdr'`.

The device key lives in the Android Keystore. sshj signs through BouncyCastle, which
cannot use Keystore keys, so `KeystoreEd25519Signature` and `KeystoreEcdsaSignature` sign
through the platform JCA provider instead.

## herdr API

`HerdrApi` wraps the methods Herdroid uses: `session.snapshot` for spaces, tabs, panes and
agents; `events.subscribe` for live changes; `agent.prompt`, `pane.send_keys` and
`pane.send_text` for input; `pane.read` for the screen text behind prompt cards; and
`workspace.create`, `workspace.rename` and `pane.rename`.

`HerdrSession` keeps the latest snapshot and refreshes it when events arrive.

## Transcripts

Chats come from the transcript files the agents write, not from the terminal screen:

| Agent | File |
| --- | --- |
| Claude Code | `~/.claude/projects/<escaped cwd>/<session id>.jsonl` |
| Codex | `~/.codex/sessions/**/rollout-*-<session id>.jsonl` |
| Kimi Code | `~/.kimi-code/sessions/*/<session id>/agents/main/wire.jsonl` |

herdr reports each agent's session ID. When it does not, `TranscriptSource.locateByFolder`
takes the newest transcript of that agent kind in the pane's folder that was written after
the agent started.

A chat opens from the last 512 KB of the transcript and pages in older history as you
scroll up. New records arrive through `tail -F`. Each parser turns its format into the
shared `Message` model; records a parser does not understand are kept as `UNKNOWN` rather
than dropped. `ThreadItems` groups messages into what the chat shows, such as tool runs.

## Prompt cards

herdr has no structured API for agent questions. `PromptParser` reads the bottom of the
pane's screen, with ANSI styling from `AnsiScreen`, and looks for the shape all three
agents draw: a question, a list of choices with a cursor, and a key hint. Styling matters:
muted lines are descriptions, not choices. To avoid showing a card for a numbered list in
an ordinary answer, a question must either be reported as blocked by herdr or be seen on
two reads in a row, and never while the agent is working.

Tapping a choice first reads the screen again and stops if the question changed. It then
moves the agent's cursor with arrow keys, checks that the cursor landed on the choice, and
presses Enter: the same keys you would type.

## Sending messages

`Outbox` tracks each message you send: sending, submitted, and then confirmed when the same
text appears as a new user record in the transcript. A message that is never confirmed
becomes unclear. Nothing is resent automatically; you choose to resend or dismiss.

## Terminal

`RemoteTerminal` runs `herdr terminal session observe`, or `control --takeover` when you
take control, and feeds the base64 ANSI frames into the Termux emulator. Taking control
resizes the pane on the computer to the phone's size; releasing it gives the size back.
