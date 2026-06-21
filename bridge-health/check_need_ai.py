#!/usr/bin/env python3
"""Check if any bridges need replacement (2+ consecutive failures).

Threshold is 2 (not 3) because GitHub Actions throttles scheduled
workflows on free repos — actual check frequency is every 1-4 hours,
not every 15 minutes as the cron suggests. With 2 failures, a dead
bridge gets swapped after 2-8 hours instead of 3-12 hours.
"""
import json
from pathlib import Path

RESULTS_FILE = Path("bridge-health/current_results.json")
HEALTH_FILE = Path("bridge-health/health.json")

with open(RESULTS_FILE) as f:
    results = json.load(f)

try:
    with open(HEALTH_FILE) as f:
        health = json.load(f)
    failure_counts = health.get("failure_counts", {})
except (FileNotFoundError, json.JSONDecodeError):
    failure_counts = {}

need = False
for b in results:
    if b["status"] != "alive":
        count = failure_counts.get(b["line"], 0) + 1
        if count >= 2:
            need = True
            break

result = "true" if need else "false"
Path("bridge-health/need_ai.txt").write_text(result)
print(f"need_swap={result}")
