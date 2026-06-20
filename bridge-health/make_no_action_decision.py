#!/usr/bin/env python3
"""Make a no-action decision JSON."""
import json
from pathlib import Path

Path("bridge-health/decision.json").write_text(json.dumps({
    "actions": [], "pr_title": "", "pr_body": "",
    "summary": "no changes needed",
}, indent=2))
