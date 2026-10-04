#!/usr/bin/env python3
"""Build health_data.json from current_results.json + health.json."""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from health_common import normalize_bridge_line, normalize_failure_counts  # noqa: E402

with open("bridge-health/current_results.json") as f:
    current = json.load(f)

try:
    with open("bridge-health/health.json") as f:
        health = json.load(f)
    failure_counts = normalize_failure_counts(health.get("failure_counts", {}))
except (FileNotFoundError, json.JSONDecodeError):
    failure_counts = {}

Path("bridge-health/health_data.json").write_text(json.dumps({
    "current_results": current,
    "failure_counts": failure_counts,
}, indent=2))
print("Health data prepared")
