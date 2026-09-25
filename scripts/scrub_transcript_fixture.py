#!/usr/bin/env python3
"""Copy selected JSONL records while replacing every free-text string."""

import argparse
import json
import re
import sys

SAFE_ENUMS = {"type", "subtype", "role", "phase", "status", "stop_reason", "kind", "event"}
SAFE_IDS = {"id", "uuid", "parentUuid", "tool_use_id", "call_id", "session_id", "turn_id"}


def scrub(value, key=None):
    if isinstance(value, dict):
        return {name: scrub(item, name) for name, item in value.items()}
    if isinstance(value, list):
        return [scrub(item, key) for item in value]
    if isinstance(value, str):
        if key in SAFE_ENUMS:
            return value
        if key in SAFE_IDS and re.fullmatch(r"[A-Za-z0-9_-]{1,128}", value):
            return value
        if key == "timestamp" and re.fullmatch(r"\d{4}-\d\d-\d\dT[^\s]{1,40}", value):
            return value
        return "[scrubbed]"
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="local JSONL transcript")
    parser.add_argument("output", help="fixture JSONL destination")
    parser.add_argument("indices", nargs="+", type=int, help="zero-based source line indices")
    args = parser.parse_args()
    selected = set(args.indices)
    try:
        with open(args.source, encoding="utf-8") as source, open(args.output, "w", encoding="utf-8") as output:
            for index, line in enumerate(source):
                if index not in selected:
                    continue
                record = json.loads(line)
                output.write(json.dumps(scrub(record), separators=(",", ":")) + "\n")
                selected.remove(index)
    except (OSError, json.JSONDecodeError) as error:
        print(f"fixture scrub failed: {error}", file=sys.stderr)
        return 1
    if selected:
        print(f"source line indices not found: {sorted(selected)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
