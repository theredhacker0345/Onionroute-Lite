#!/usr/bin/env python3
"""Generate the Actions run summary."""
import json
from pathlib import Path

RESULTS_FILE = Path("bridge-health/current_results.json")

if not RESULTS_FILE.exists():
    print("_(no results)_")
    raise SystemExit(0)

with open(RESULTS_FILE) as f:
    results = json.load(f)

alive = sum(1 for b in results if b["status"] == "alive")
total = len(results)
print(f"**Result:** {alive}/{total} bridges alive")
print()
print("| Bridge | Status |")
print("|--------|--------|")
for b in results:
    status = "alive" if b["status"] == "alive" else b["status"]
    line = b["line"][:30] + "..."
    print(f"| `{line}` | {status} |")
