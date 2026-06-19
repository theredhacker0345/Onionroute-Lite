#!/usr/bin/env python3
"""
Render Bridge Health README Section
====================================

Updates the README with a live bridge health table + recent AI actions.

Called after every health check (regardless of whether any bridges
were swapped) so the README always reflects current status.

Usage:
    python3 render_readme.py
"""
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
HEALTH_FILE = REPO_ROOT / "bridge-health" / "health.json"
README_FILE = REPO_ROOT / "README.md"


def render_health_table(health: dict) -> str:
    """Generate the markdown health table from the latest check."""
    checks = health.get("checks", [])
    if not checks:
        return "_(no health checks yet — first run pending)_"

    latest = checks[-1]
    bridges = latest.get("bridges", [])
    failure_counts = health.get("failure_counts", {})

    if not bridges:
        return "_(no bridges found in Constants.kt)_"

    lines = [
        "| Bridge | Type | Status | Bootstrap | Failures |",
        "|--------|------|--------|-----------|----------|",
    ]

    for b in bridges:
        transport = b.get("transport", "?")
        line = b.get("line", "")
        # Show first 30 chars of bridge line as identifier
        bridge_id = line[:30] + "..." if len(line) > 30 else line
        status = b.get("status", "?")
        bootstrap = b.get("bootstrap_time", 0)
        failures = failure_counts.get(line, 0)

        # Status emoji
        if status == "alive":
            status_str = "✓ alive"
        elif status == "dead":
            status_str = f"✗ dead"
        elif status == "timeout":
            status_str = "⏳ timeout"
        else:
            status_str = f"? {status}"

        # Bootstrap time
        if bootstrap > 0:
            bootstrap_str = f"{bootstrap}s"
        else:
            bootstrap_str = "—"

        # Failure history bar (last 6 checks)
        lines.append(
            f"| `{bridge_id}` | {transport} | {status_str} | {bootstrap_str} | {failures} |"
        )

    return "\n".join(lines)


def render_readme_section(health: dict) -> str:
    """Generate the full README section for bridge health."""
    checks = health.get("checks", [])
    latest_check = checks[-1]["timestamp"] if checks else "never"
    bridge_count = len(checks[-1].get("bridges", [])) if checks else 0

    alive_count = sum(
        1 for b in (checks[-1].get("bridges", []) if checks else [])
        if b.get("status") == "alive"
    )

    health_table = render_health_table(health)

    # Summary stats
    total_checks = len(checks)

    section = f"""<!-- BRIDGE-HEALTH-START -->
## Bridge Health — AI-Monitored

![Bridge Health](https://img.shields.io/badge/bridges%20alive-{alive_count}/{bridge_count}-00E5C4?style=for-the-badge)

An AI agent (Qwen 2.5 3B, running locally on the GitHub Actions runner
via llama.cpp — no external API) tests every bundled bridge every 30
minutes. If a bridge has been dead for 3+ consecutive checks, the AI
opens a PR to swap it for a working replacement.

### Live Status

**Last check:** {latest_check} | **Total checks:** {total_checks} | **Alive:** {alive_count}/{bridge_count}

{health_table}

### How it works

1. Every 30 min, a GitHub Actions runner installs `tor` + `llama.cpp`
2. Tests each bridge in `Constants.kt` — real Tor bootstrap, 60s timeout
3. If any bridge has 3+ consecutive failures, the AI agent decides:
   - Fetch replacement bridges from Tor Project's BridgeDB
   - Test each replacement live (must bootstrap in < 30s)
   - Ask the local LLM: "should we swap? which replacement?"
4. If the AI says swap, the workflow edits `Constants.kt` and opens a PR
5. The PR description is written by the AI with evidence + reasoning
6. You review and merge — the AI never auto-merges

### AI details

- **Model:** Qwen 2.5 3B Instruct (Q4_K_M, 2GB, Apache 2.0 license)
- **Framework:** llama.cpp (single binary, no daemon, no GPU)
- **Inference:** ~25s per decision on 4-core CPU
- **Cost:** $0 — model is cached in `actions/cache`, no API calls
- **Privacy:** All inference happens on GitHub's runner, no data leaves

Workflow: [`.github/workflows/bridge-health.yml`](.github/workflows/bridge-health.yml)
Health data: [`bridge-health/health.json`](bridge-health/health.json)
<!-- BRIDGE-HEALTH-END -->"""

    return section


def main():
    if not HEALTH_FILE.exists():
        print("[*] No health.json yet — creating minimal README section", file=sys.stderr)
        health = {"checks": [], "failure_counts": {}}
    else:
        try:
            health = json.loads(HEALTH_FILE.read_text())
        except json.JSONDecodeError:
            print(f"[!] health.json corrupted — using empty", file=sys.stderr)
            health = {"checks": [], "failure_counts": {}}

    section = render_readme_section(health)

    if not README_FILE.exists():
        print(f"[!] README.md not found", file=sys.stderr)
        sys.exit(1)

    content = README_FILE.read_text()
    start_marker = "<!-- BRIDGE-HEALTH-START -->"
    end_marker = "<!-- BRIDGE-HEALTH-END -->"

    if start_marker in content and end_marker in content:
        start_idx = content.index(start_marker)
        end_idx = content.index(end_marker) + len(end_marker)
        content = content[:start_idx] + section + content[end_idx:]
    else:
        # Append to end
        content = content.rstrip() + "\n\n" + section + "\n"

    README_FILE.write_text(content)
    print("[*] README updated with bridge health section", file=sys.stderr)


if __name__ == "__main__":
    main()
