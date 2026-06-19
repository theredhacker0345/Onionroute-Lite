#!/usr/bin/env python3
"""
Snowflake Volunteer Stats Processor
====================================

Parses the snowflake-proxy stdout log, extracts contribution stats
(clients helped, bandwidth donated, runtime), appends to the historical
JSON, and regenerates an SVG dashboard embedded in the README.

Called by .github/workflows/snowflake-volunteer.yml after each
proxy session ends.
"""
import json
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
STATS_FILE = REPO_ROOT / "snowflake-volunteer" / "stats.json"
SVG_FILE = REPO_ROOT / "snowflake-volunteer" / "dashboard.svg"
README_FILE = REPO_ROOT / "README.md"
LOG_FILE = sys.argv[1] if len(sys.argv) > 1 else "proxy.log"
DURATION_SECONDS = int(sys.argv[2]) if len(sys.argv) > 2 else 0


def parse_log(log_path: str) -> dict:
    """Extract clients_helped and bytes_relayed from snowflake proxy log."""
    max_people = 0
    max_bytes = 0

    try:
        with open(log_path, "r", errors="replace") as f:
            for line in f:
                m = re.search(r"(\d+)\s+people\s+helped", line, re.IGNORECASE)
                if m:
                    n = int(m.group(1))
                    if n > max_people:
                        max_people = n

                m = re.search(r"data\s+relayed:\s+(\d+)", line, re.IGNORECASE)
                if m:
                    n = int(m.group(1))
                    if n > max_bytes:
                        max_bytes = n
    except FileNotFoundError:
        print(f"WARN: log file {log_path} not found — recording zero stats", file=sys.stderr)

    return {"clients_helped": max_people, "bytes_relayed": max_bytes}


def load_stats() -> list:
    if STATS_FILE.exists():
        try:
            return json.loads(STATS_FILE.read_text())
        except json.JSONDecodeError:
            print(f"WARN: {STATS_FILE} corrupted — starting fresh", file=sys.stderr)
    return []


def save_stats(stats: list) -> None:
    STATS_FILE.parent.mkdir(parents=True, exist_ok=True)
    STATS_FILE.write_text(json.dumps(stats, indent=2) + "\n")


def append_session(stats: list, session_data: dict) -> list:
    stats.append(session_data)
    if len(stats) > 365:
        stats = stats[-365:]
    return stats


def format_bytes(n: int) -> str:
    if n >= 1_099_511_627_776:
        return f"{n / 1_099_511_627_776:.2f} TB"
    if n >= 1_073_741_824:
        return f"{n / 1_073_741_824:.2f} GB"
    if n >= 1_048_576:
        return f"{n / 1_048_576:.2f} MB"
    if n >= 1024:
        return f"{n / 1024:.2f} KB"
    return f"{n} B"


def format_duration(seconds: int) -> str:
    hours = seconds // 3600
    minutes = (seconds % 3600) // 60
    if hours > 0:
        return f"{hours}h {minutes}m"
    return f"{minutes}m"


def generate_svg(stats: list) -> str:
    """Generate a dark-themed SVG bar chart of the last 30 sessions."""
    recent = stats[-30:] if len(stats) >= 30 else stats

    width = 800
    height = 200
    padding_left = 60
    padding_right = 20
    padding_top = 30
    padding_bottom = 40
    chart_width = width - padding_left - padding_right
    chart_height = height - padding_top - padding_bottom

    max_clients = max((s["clients_helped"] for s in recent), default=1)
    if max_clients == 0:
        max_clients = 1

    bar_count = len(recent)
    if bar_count == 0:
        bar_width = 0
        bar_spacing = 0
    else:
        bar_width = chart_width / bar_count * 0.7
        bar_spacing = chart_width / bar_count * 0.3

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" font-family="monospace">',
        f'<rect width="{width}" height="{height}" fill="#000000"/>',
        f'<text x="{padding_left}" y="20" fill="#00E5C4" font-size="13" font-weight="bold">'
        f'SNOWFLAKE VOLUNTEER — LAST {bar_count} SESSIONS</text>',
    ]

    for pct in [0, 25, 50, 75, 100]:
        y = padding_top + chart_height - (chart_height * pct / 100)
        value = int(max_clients * pct / 100)
        parts.append(
            f'<line x1="{padding_left}" y1="{y:.1f}" '
            f'x2="{width - padding_right}" y2="{y:.1f}" '
            f'stroke="#1C1C1C" stroke-width="1"/>'
        )
        parts.append(
            f'<text x="{padding_left - 8}" y="{y + 4:.1f}" '
            f'fill="#5C5C5C" font-size="10" text-anchor="end">{value}</text>'
        )

    for i, session in enumerate(recent):
        x = padding_left + i * (bar_width + bar_spacing) + bar_spacing / 2
        clients = session["clients_helped"]
        bar_h = (clients / max_clients) * chart_height if max_clients > 0 else 0
        y = padding_top + chart_height - bar_h

        parts.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_width:.1f}" height="{bar_h:.1f}" '
            f'fill="#00E5C4" rx="2"/>'
        )

        if i % 5 == 0 or i == bar_count - 1:
            ts = session.get("timestamp", "")[:10]
            parts.append(
                f'<text x="{x + bar_width/2:.1f}" y="{height - 10}" '
                f'fill="#5C5C5C" font-size="9" text-anchor="middle">{ts[5:]}</text>'
            )

    parts.append("</svg>")
    return "\n".join(parts)


def update_readme(stats: list) -> None:
    """Update the README's snowflake-volunteer section with live totals."""
    if not README_FILE.exists():
        print(f"WARN: {README_FILE} not found — skipping README update", file=sys.stderr)
        return

    total_sessions = len(stats)
    total_clients = sum(s["clients_helped"] for s in stats)
    total_bytes = sum(s["bytes_relayed"] for s in stats)
    total_duration = sum(s.get("duration_seconds", 0) for s in stats)

    badge_url = "https://img.shields.io/badge/snowflake%20volunteer-active-00E5C4?style=for-the-badge"

    section = f"""<!-- SNOWFLAKE-VOLUNTEER-START -->
## Snowflake Volunteer — This Repo Helps Censored Users 24/7

![Snowflake Volunteer]({badge_url})

This repository runs a **Tor Snowflake proxy** on GitHub Actions runners
every 6 hours, donating ~5 hours 50 minutes of compute per session to
help people in censored countries (Iran, China, Russia, etc.) reach the
free internet. Zero external services, zero cost — pure creative use of
CI compute for human rights.

### Live Contribution Stats

| Metric | Total |
|--------|-------|
| **Sessions run** | {total_sessions} |
| **People helped** | {total_clients} |
| **Bandwidth donated** | {format_bytes(total_bytes)} |
| **Compute time volunteered** | {format_duration(total_duration)} |

![Contribution Dashboard](snowflake-volunteer/dashboard.svg)

*Dashboard auto-regenerates after each session. Last updated:
{datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")}*

### How it works

1. Every 6 hours, a GitHub Actions runner (ubuntu-latest) spins up
2. Builds the snowflake-proxy binary from source (Tor Project, Go)
3. Runs the proxy for ~5h50m (just under the 6h job timeout)
4. Real censored users connect via WebRTC through our proxy
5. After the session, stats are parsed from the proxy log
6. `snowflake-volunteer/stats.json` is updated + SVG dashboard regenerated
7. This README section is rewritten with the new totals
8. Everything is committed back to `main`

The workflow file: [`.github/workflows/snowflake-volunteer.yml`](.github/workflows/snowflake-volunteer.yml)

### Manual trigger

You can start a volunteer session manually from the Actions tab:
1. Go to **Actions** -> **Snowflake Volunteer**
2. Click **Run workflow**
3. Watch the logs — real users may connect within minutes

> **Note:** This is an opt-in contribution. The workflow only runs on
> schedule + manual trigger. It does NOT run on every push (to avoid
> wasting Actions minutes). Public repos have unlimited Actions minutes,
> so this is genuinely free compute for a good cause.
<!-- SNOWFLAKE-VOLUNTEER-END -->"""

    content = README_FILE.read_text()
    start_marker = "<!-- SNOWFLAKE-VOLUNTEER-START -->"
    end_marker = "<!-- SNOWFLAKE-VOLUNTEER-END -->"

    if start_marker in content and end_marker in content:
        start_idx = content.index(start_marker)
        end_idx = content.index(end_marker) + len(end_marker)
        content = content[:start_idx] + section + content[end_idx:]
    else:
        content = content.rstrip() + "\n\n" + section + "\n"

    README_FILE.write_text(content)


def main():
    print(f"[*] Parsing log: {LOG_FILE}")
    log_stats = parse_log(LOG_FILE)
    print(f"[*] Log stats: {log_stats}")

    session_data = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "duration_seconds": DURATION_SECONDS,
        "clients_helped": log_stats["clients_helped"],
        "bytes_relayed": log_stats["bytes_relayed"],
        "runner": os.environ.get("RUNNER_OS", "unknown"),
    }
    print(f"[*] Session: {json.dumps(session_data, indent=2)}")

    print(f"[*] Loading existing stats from {STATS_FILE}")
    stats = load_stats()
    print(f"[*] Loaded {len(stats)} historical sessions")

    stats = append_session(stats, session_data)
    print(f"[*] After append: {len(stats)} sessions")

    print(f"[*] Saving stats to {STATS_FILE}")
    save_stats(stats)

    print(f"[*] Generating SVG dashboard to {SVG_FILE}")
    SVG_FILE.parent.mkdir(parents=True, exist_ok=True)
    SVG_FILE.write_text(generate_svg(stats))

    print(f"[*] Updating README badge + stats table")
    update_readme(stats)

    total_clients = sum(s["clients_helped"] for s in stats)
    total_bytes = sum(s["bytes_relayed"] for s in stats)
    print()
    print("=" * 60)
    print(f"  SESSION: {session_data['clients_helped']} clients, "
          f"{format_bytes(session_data['bytes_relayed'])} relayed, "
          f"{format_duration(session_data['duration_seconds'])}")
    print(f"  ALL-TIME: {len(stats)} sessions, {total_clients} clients helped, "
          f"{format_bytes(total_bytes)} donated")
    print("=" * 60)


if __name__ == "__main__":
    main()
