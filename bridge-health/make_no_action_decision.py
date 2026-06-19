#!/usr/bin/env python3
"""Create a no-action decision JSON for the no-AI path."""
import json
from pathlib import Path

decision = {
    "actions": [],
    "pr_title": "",
    "pr_body": "",
    "summary": "no changes needed",
}

Path("bridge-health/decision.json").write_text(json.dumps(decision, indent=2))
print("No-action decision written")
