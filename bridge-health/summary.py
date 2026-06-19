#!/usr/bin/env python3
"""Generate the Actions run summary for the bridge health workflow."""
import json
import sys
from pathlib import Path

RESULTS_FILE = Path("bridge-health/current_results.json")

if not RESULTS_FILE.exists():
    print("_(no results — bridge testing may have failed)_")
    sys.exit(0)

with open(RESULTS_FILE) as f:
    results = json.load(f)

alive = sum(1 for b in results if b["status"] == "alive")
total = len(results)
print(f"**Result:** {alive}/{total} bridges alive")
print()
print("| Bridge | Status | Bootstrap |")
print("|--------|--------|-----------|")
for b in results:
    status = "alive" if b["status"] == "alive" else b["status"]
    bt = b.get("bootstrap_time", 0)
    bt_str = f"{bt}s" if bt > 0 else "—"
    line = b["line"][:30] + "..."
    print(f"| `{line}` | {status} | {bt_str} |")
