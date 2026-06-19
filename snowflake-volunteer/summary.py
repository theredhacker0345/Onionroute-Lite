#!/usr/bin/env python3
"""Generates the Markdown summary for the Actions run summary page.
Prints a table of this session's stats + all-time totals.
"""
import json
from pathlib import Path

STATS_FILE = Path(__file__).parent / "stats.json"


def format_bytes(n: int) -> str:
    if n >= 1_099_511_627_776:
        return f"{n / 1_099_511_627_776:.2f} TB"
    if n >= 1_073_741_824:
        return f"{n / 1_073_741_824:.2f} GB"
    if n >= 1_048_576:
        return f"{n / 1_048_576:.2f} MB"
    if n >= 1024:
        return f"{n / 1024:.2f} KB"
    return f"{n} B"


if __name__ == "__main__":
    try:
        stats = json.loads(STATS_FILE.read_text())
    except (FileNotFoundError, json.JSONDecodeError):
        stats = []

    if not stats:
        print("(no stats recorded)")
        raise SystemExit(0)

    s = stats[-1]
    print("| Metric | Value |")
    print("|--------|-------|")
    print(f"| Session timestamp | {s.get('timestamp', '?')} |")
    print(f"| Duration | {s.get('duration_seconds', 0)}s |")
    print(f"| Clients helped | {s.get('clients_helped', 0)} |")
    print(f"| Bytes relayed | {format_bytes(s.get('bytes_relayed', 0))} |")
    print()
    total_clients = sum(x.get("clients_helped", 0) for x in stats)
    total_bytes = sum(x.get("bytes_relayed", 0) for x in stats)
    total_duration = sum(x.get("duration_seconds", 0) for x in stats)
    hours = total_duration // 3600
    print(f"**All-time:** {len(stats)} sessions, {total_clients} people helped, "
          f"{format_bytes(total_bytes)} donated, {hours}h compute volunteered")
