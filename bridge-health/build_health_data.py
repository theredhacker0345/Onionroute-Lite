#!/usr/bin/env python3
"""Combine current results + historical failure counts into a single
JSON that the AI agent reads."""
import json
from pathlib import Path

# Load current results
with open("bridge-health/current_results.json") as f:
    current = json.load(f)

# Load failure counts
try:
    with open("bridge-health/health.json") as f:
        health = json.load(f)
    failure_counts = health.get("failure_counts", {})
except FileNotFoundError:
    failure_counts = {}

# Build health data
health_data = {
    "current_results": current,
    "failure_counts": failure_counts,
}

Path("bridge-health/health_data.json").write_text(json.dumps(health_data, indent=2))
print("Health data prepared for AI")
