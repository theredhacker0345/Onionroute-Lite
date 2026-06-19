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
    """Generate a GitHub-safe SVG dashboard header.

    GitHub strips <animate>, <filter>, <script> from SVGs in README.
    This SVG uses ONLY safe elements (rect, text, line, gradient) so
    it renders correctly on GitHub.

    The actual chart is rendered by QuickChart.io (PNG, no sanitization)
    and embedded separately in the README. This SVG is just the header
    card with stats + status.
    """
    total_sessions = len(stats)
    total_clients = sum(s.get("clients_helped", 0) for s in stats)
    total_duration = sum(s.get("duration_seconds", 0) for s in stats)
    total_hours = total_duration // 3600
    total_minutes = (total_duration % 3600) // 60
    total_bytes = sum(s.get("bytes_relayed", 0) for s in stats)

    width = 820
    height = 100

    # Status
    if total_clients > 0:
        status_text = "HELPING USERS"
        status_color = "#00E5C4"
    elif total_sessions > 0:
        status_text = "ARMED & MONITORING"
        status_color = "#00E5C4"
    else:
        status_text = "INITIALIZING"
        status_color = "#888888"

    bw_str = format_bytes(total_bytes)

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Helvetica, Arial, sans-serif">',

        f'<defs>',
        f'<linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">',
        f'<stop offset="0%" stop-color="#0D1F1C"/>',
        f'<stop offset="100%" stop-color="#0A0A0A"/>',
        f'</linearGradient>',
        f'<linearGradient id="accent" x1="0" y1="0" x2="1" y2="0">',
        f'<stop offset="0%" stop-color="#00E5C4"/>',
        f'<stop offset="100%" stop-color="#006B5A"/>',
        f'</linearGradient>',
        f'</defs>',

        # Background
        f'<rect width="{width}" height="{height}" fill="url(#bg)" rx="10"/>',

        # Left accent bar
        f'<rect x="0" y="0" width="5" height="{height}" fill="url(#accent)" rx="2"/>',

        # Status indicator (solid circle — no animation, GitHub-safe)
        f'<circle cx="30" cy="28" r="5" fill="{status_color}"/>',
        f'<text x="42" y="32" fill="{status_color}" font-size="11" font-weight="700" letter-spacing="1">{status_text}</text>',

        # Title
        f'<text x="30" y="58" fill="#E0E0E0" font-size="16" font-weight="700">Snowflake Volunteer</text>',
        f'<text x="30" y="75" fill="#666666" font-size="10">Helping censored users bypass internet restrictions 24/7</text>',

        # Stats grid (right side)
        # Sessions
        f'<text x="430" y="28" fill="#666" font-size="9" font-weight="600" letter-spacing="0.5">SESSIONS</text>',
        f'<text x="430" y="48" fill="#E0E0E0" font-size="20" font-weight="700">{total_sessions}</text>',

        # People helped
        f'<text x="540" y="28" fill="#666" font-size="9" font-weight="600" letter-spacing="0.5">HELPED</text>',
        f'<text x="540" y="48" fill="#00E5C4" font-size="20" font-weight="700">{total_clients}</text>',

        # Time donated
        f'<text x="620" y="28" fill="#666" font-size="9" font-weight="600" letter-spacing="0.5">DONATED</text>',
        f'<text x="620" y="48" fill="#E0E0E0" font-size="20" font-weight="700">{total_hours}h{total_minutes}m</text>',

        # Bandwidth
        f'<text x="720" y="28" fill="#666" font-size="9" font-weight="600" letter-spacing="0.5">BANDWIDTH</text>',
        f'<text x="720" y="48" fill="#E0E0E0" font-size="14" font-weight="700">{bw_str}</text>',

        # Divider
        f'<line x1="30" y1="85" x2="{width-20}" y2="85" stroke="#1A2A28" stroke-width="1"/>',

        # Footer
        f'<text x="30" y="95" fill="#444" font-size="8">Runs every 6h on GitHub Actions · 0 external services · $0 cost</text>',
    ]

    parts.append("</svg>")
    return "\n".join(parts)


def generate_quickchart_url(stats: list) -> str:
    """Generate a QuickChart.io URL for the bar chart.

    QuickChart renders PNG images from URL params — no SVG sanitization
    issues, renders perfectly in GitHub README. Free, no API key.

    Chart shows session duration (bars) + client count (overlay).
    """
    import urllib.parse

    recent = stats[-30:] if len(stats) >= 30 else stats
    if not recent:
        return ""

    labels = [s.get("timestamp", "")[5:10] for s in recent]  # MM-DD
    durations = [round(s.get("duration_seconds", 0) / 60, 1) for s in recent]  # minutes
    clients = [s.get("clients_helped", 0) for s in recent]

    # Bar colors: jade for sessions with clients, dark teal for idle
    bg_colors = ["#00E5C4" if c > 0 else "#1A3A35" for c in clients]

    chart_config = {
        "type": "bar",
        "data": {
            "labels": labels,
            "datasets": [{
                "label": "Session Duration (min)",
                "data": durations,
                "backgroundColor": bg_colors,
                "borderColor": "#00E5C4",
                "borderWidth": 0,
                "borderRadius": 4,
            }]
        },
        "options": {
            "plugins": {
                "title": {
                    "display": True,
                    "text": "Session History — Duration (minutes)",
                    "color": "#888888",
                    "font": {"size": 13, "family": "sans-serif"}
                },
                "legend": {"display": False},
                "datalabels": {
                    "display": True,
                    "color": "#00E5C4",
                    "anchor": "end",
                    "align": "top",
                    "font": {"size": 10, "weight": "bold"},
                    "formatter": "function(ctx) { return ctx.dataset.data[ctx.dataIndex] > 0 ? ctx.dataset.data[ctx.dataIndex] + 'm' : ''; }"
                }
            },
            "scales": {
                "x": {
                    "ticks": {"color": "#666666", "font": {"size": 9}},
                    "grid": {"color": "#1A1A1A", "display": False}
                },
                "y": {
                    "ticks": {"color": "#666666", "font": {"size": 9}},
                    "grid": {"color": "#1A1A1A"}
                }
            },
            "layout": {"padding": {"top": 20, "bottom": 5, "left": 5, "right": 10}},
            "backgroundColor": "#0A0A0A"
        }
    }

    config_json = json.dumps(chart_config, separators=(',', ':'))
    encoded = urllib.parse.quote(config_json)
    return f"https://quickchart.io/chart?w=820&h=250&bkg=%230A0A0A&c={encoded}"


def update_readme(stats: list) -> None:
    """Update the README's snowflake-volunteer section with live totals."""
    if not README_FILE.exists():
        print(f"WARN: {README_FILE} not found — skipping README update", file=sys.stderr)
        return

    total_sessions = len(stats)
    total_clients = sum(s["clients_helped"] for s in stats)
    total_bytes = sum(s["bytes_relayed"] for s in stats)
    total_duration = sum(s.get("duration_seconds", 0) for s in stats)
    total_hours = total_duration // 3600
    total_minutes = (total_duration % 3600) // 60

    # Cache-buster for the SVG image URL
    last_ts = stats[-1].get("timestamp", "").replace(":", "").replace("-", "") if stats else ""
    svg_url = f"snowflake-volunteer/dashboard.svg?v={last_ts}"

    # QuickChart URL for the bar chart (dynamic, PNG, no sanitization)
    chart_url = generate_quickchart_url(stats)
    # Add cache-buster to chart URL too
    if chart_url:
        chart_url = f"{chart_url}&v={last_ts}"

    # Dynamic status badge
    if total_clients > 0:
        status_badge = "https://img.shields.io/badge/status-HELPING%20USERS-00E5C4?style=for-the-badge"
    elif total_sessions > 0:
        status_badge = "https://img.shields.io/badge/status-ARMED%20%26%20MONITORING-00E5C4?style=for-the-badge"
    else:
        status_badge = "https://img.shields.io/badge/status-INITIALIZING-888888?style=for-the-badge"

    # Chart image markdown — only if we have data
    chart_md = f'\n![Session History Chart]({chart_url})\n' if chart_url else ''

    section = f"""<!-- SNOWFLAKE-VOLUNTEER-START -->
## Snowflake Volunteer

![Status]({status_badge})
![Sessions](https://img.shields.io/badge/sessions-{total_sessions}-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![People Helped](https://img.shields.io/badge/people%20helped-{total_clients}-00E5C4?style=for-the-badge&labelColor=1A1A1A)
![Time Donated](https://img.shields.io/badge/time%20donated-{total_hours}h%20{total_minutes}m-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![Bandwidth](https://img.shields.io/badge/bandwidth-{format_bytes(total_bytes).replace("%20","%20").replace(" ","%20")}-0D0D0D?style=for-the-badge&labelColor=1A1A1A)

![Dashboard Header]({svg_url})
{chart_md}
> *Last updated: {datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")} · Auto-regenerates after each session · Chart by [QuickChart.io](https://quickchart.io)*

<details>
<summary>How it works</summary>

1. Every 6 hours, a GitHub Actions runner (ubuntu-latest) spins up
2. Builds the snowflake-proxy binary from source (Tor Project, Go)
3. Runs the proxy for ~5h50m (just under the 6h job timeout)
4. Real censored users connect via WebRTC through our proxy
5. Stats are parsed from the proxy log and committed back to `main`
6. This README section + the SVG header + QuickChart chart auto-regenerate

Workflow: [`.github/workflows/snowflake-volunteer.yml`](.github/workflows/snowflake-volunteer.yml)

</details>

<details>
<summary>Start a session manually</summary>

1. Go to **Actions** -> **Snowflake Volunteer**
2. Click **Run workflow**
3. Set duration (default 350 min, max 350)
4. Watch the logs — real users may connect within minutes

> Public repos have unlimited Actions minutes, so this is genuinely free compute for a good cause.

</details>
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
