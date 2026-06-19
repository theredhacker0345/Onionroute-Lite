#!/usr/bin/env python3
"""Check if any bridges need AI judgment.

Returns 'true' if any bridge has 3+ consecutive failures, 'false' otherwise.
Writes the result to bridge-health/need_ai.txt and prints it.
"""
import json
import sys
from pathlib import Path

HEALTH_FILE = Path("bridge-health/health.json")
RESULTS_FILE = Path("bridge-health/current_results.json")

# Load current results
with open(RESULTS_FILE) as f:
    results = json.load(f)

# Load existing failure counts
try:
    with open(HEALTH_FILE) as f:
        health = json.load(f)
    failure_counts = health.get("failure_counts", {})
except FileNotFoundError:
    failure_counts = {}

# Check if any bridge has 3+ failures (including current check)
need_ai = False
for b in results:
    if b["status"] != "alive":
        count = failure_counts.get(b["line"], 0) + 1  # +1 for current failure
        if count >= 3:
            need_ai = True
            break

result = "true" if need_ai else "false"
Path("bridge-health/need_ai.txt").write_text(result)
print(f"need_ai={result}")
