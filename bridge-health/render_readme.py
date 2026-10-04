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

sys.path.insert(0, str(Path(__file__).resolve().parent))
from health_common import normalize_bridge_line, normalize_failure_counts  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parent.parent
HEALTH_FILE = REPO_ROOT / "bridge-health" / "health.json"
README_FILE = REPO_ROOT / "README.md"


def render_health_table(health: dict) -> str:
    """Generate the markdown health table from the latest check.

    Includes a visual failure history bar (last 6 checks) using
    colored square characters:
      🟩 = alive    🟥 = dead    🟨 = timeout    ⬜ = no data
    """
    checks = health.get("checks", [])
    if not checks:
        return "_(no health checks yet — first run pending)_"

    latest = checks[-1]
    bridges = latest.get("bridges", [])
    failure_counts = normalize_failure_counts(health.get("failure_counts", {}))

    if not bridges:
        return "_(no bridges found in Constants.kt)_"

    # Build history lookup: for each bridge line, get last 6 statuses
    recent_checks = checks[-6:] if len(checks) >= 6 else checks

    lines = [
        "| Bridge | Type | Status | Bootstrap | Failures | History |",
        "|--------|------|--------|-----------|----------|---------|",
    ]

    for b in bridges:
        transport = b.get("transport", "?")
        line = b.get("line", "")
        # Show a shorter bridge ID — just IP:port
        import re
        ip_match = re.search(r'(\d+\.\d+\.\d+\.\d+:\d+)', line)
        bridge_id = ip_match.group(1) if ip_match else (line[:25] + "..." if len(line) > 25 else line)
        status = b.get("status", "?")
        bootstrap = b.get("bootstrap_time", 0)
        failures = failure_counts.get(normalize_bridge_line(line), 0)

        # Status with emoji
        if status == "alive":
            status_str = "✅ alive"
        elif status == "dead":
            status_str = "❌ dead"
        elif status == "timeout":
            status_str = "⏱️ timeout"
        elif status == "error":
            status_str = "⚠️ error"
        else:
            status_str = f"❓ {status}"

        # Bootstrap time
        if bootstrap > 0:
            bootstrap_str = f"{bootstrap}s"
        else:
            bootstrap_str = "—"

        # Failure count with visual indicator
        if failures == 0:
            failures_str = "0"
        elif failures >= 3:
            failures_str = f"**{failures}** ⚠️"
        else:
            failures_str = str(failures)

        # History bar — last 6 checks for this bridge
        history = ""
        for check in recent_checks:
            found = False
            for cb in check.get("bridges", []):
                if cb.get("line") == line:
                    s = cb.get("status", "?")
                    if s == "alive":
                        history += "🟩"
                    elif s == "dead":
                        history += "🟥"
                    elif s == "timeout":
                        history += "🟨"
                    else:
                        history += "🟧"
                    found = True
                    break
            if not found:
                history += "⬜"

        lines.append(
            f"| `{bridge_id}` | {transport} | {status_str} | {bootstrap_str} | {failures_str} | {history} |"
        )

    # Add legend
    lines.append("")
    lines.append("*History: 🟩 alive · 🟨 timeout · 🟥 dead · 🟧 error · ⬜ no data (last 6 checks)*")

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
    total_checks = len(checks)

    # Dynamic badge color
    if bridge_count > 0 and alive_count == bridge_count:
        badge_text = f"{alive_count}/{bridge_count}%20alive"
        badge_color = "00E5C4"
    elif alive_count > 0:
        badge_text = f"{alive_count}/{bridge_count}%20alive"
        badge_color = "FFA500"
    else:
        badge_text = f"{alive_count}/{bridge_count}%20alive"
        badge_color = "FF4444"

    section = f"""<!-- BRIDGE-HEALTH-START -->
## Bridge Health

![Bridge Status](https://img.shields.io/badge/bridges-{badge_text}-{badge_color}?style=for-the-badge&labelColor=1A1A1A)
![Checks](https://img.shields.io/badge/checks-{total_checks}-0D0D0D?style=for-the-badge&labelColor=1A1A1A)

A maintainer can manually start the GitLab Bridge Health job to test bundled bridges and generate current bridge data. After the owner explicitly starts the protected `main` job, it commits only the generated `alive_bridges.json`, `health.json`, and this README section. The job never runs on a schedule or push, changes `Constants.kt`, creates a release, or commits candidate source swaps. The app fetches the current [`alive_bridges.json`](bridge-health/alive_bridges.json) from the canonical GitLab project.

**Last check:** `{latest_check}`

{health_table}

<details>
<summary>How it works</summary>

1. When a maintainer manually starts `bridge_health_diagnostics` on protected `main` in GitLab, a runner tests all bridges in `Constants.kt`:
   - **obfs4 / webtunnel**: TCP connectivity check to the bridge's IP:port
   - **snowflake**: HTTPS HEAD to the broker URL + UDP STUN Binding Request to each STUN server (the bridge line's IP is a virtual placeholder)
2. If any bridge has 2+ consecutive failures, the diagnostic artifact may include candidate replacements:
   - Fetch replacement bridges from Tor Project's BridgeDB (multiple requests per transport type)
   - Filter out documentation/test addresses (RFC 3849 IPv6, RFC 5737 IPv4)
   - TCP-test each candidate (5s timeout)
   - Propose same-transport or cross-transport alternatives for maintainer review
   - Keep Snowflake unchanged (the bridge line is a fixed virtual placeholder pointing at the Tor Project broker)
3. Save results, candidate data, a decision, and the generated bridge data as job artifacts
4. Commit only `alive_bridges.json`, `health.json`, and this rendered README section with the protected project write-back token

The app uses a 3-layer bridge sourcing strategy:
1. **Runtime JSON** — fetches reviewed `alive_bridges.json` from GitLab
2. **Local cache** — last successful fetch (works offline, 24h validity)
3. **Hardcoded** — `Constants.kt` fallback (changed only through reviewed source commits)

Pipeline details: [`docs/GITLAB_CI.md`](docs/GITLAB_CI.md)

</details>
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
