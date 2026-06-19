#!/usr/bin/env python3
"""Helper for the workflow's commit step — prints a single stat from
the last session in stats.json. Used to build the commit message.

Usage:
    python3 get_last_stat.py <field_name>

Example:
    python3 get_last_stat.py clients_helped
    # prints: 3
"""
import json
import sys
from pathlib import Path

STATS_FILE = Path(__file__).parent / "stats.json"

if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: get_last_stat.py <field_name>", file=sys.stderr)
        sys.exit(1)

    field = sys.argv[1]

    try:
        stats = json.loads(STATS_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        print(0)
        sys.exit(0)

    if not stats:
        print(0)
        sys.exit(0)

    last = stats[-1]
    print(last.get(field, 0))
