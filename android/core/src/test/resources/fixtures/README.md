# Transcript fixtures

These fixtures came from local Claude Code and Codex transcripts. Every free-text string, command, path, input, and tool output was replaced with `[scrubbed]`. Record structure, discriminators, timestamps, and IDs remain so parser behavior can be tested.

To scrub selected zero-based JSONL line indices from a local transcript:

```sh
python3 scripts/scrub_transcript_fixture.py /path/to/transcript.jsonl /tmp/fixture.jsonl 0 12 13
```

Review the scrubbed output before adding it. Combine selected output files in transcript order when a fixture uses records from more than one session. Never add an unprocessed transcript or tool-result file.
