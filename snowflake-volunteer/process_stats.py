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
    """Generate a professional dark-themed SVG dashboard.

    Design principles:
    - Deep charcoal background (#0A0A0A) for depth, not harsh pure black
    - Gradient-filled bars with glow effect when clients connected
    - LIVE indicator showing the system is active
    - Clear visual hierarchy: title > stats > chart > timeline
    - Session duration as bar height (always visible)
    - Client count as annotation (jade when >0, dim when 0)
    - Dashed grid lines for subtle reference
    - Rounded bar corners for modern aesthetic
    """
    recent = stats[-30:] if len(stats) >= 30 else stats

    width = 820
    height = 280
    padding_left = 55
    padding_right = 25
    padding_top = 70  # More room for header
    padding_bottom = 50
    chart_width = width - padding_left - padding_right
    chart_height = height - padding_top - padding_bottom

    # Use duration_seconds for bar height (always > 0)
    max_duration = max((s.get("duration_seconds", 0) for s in recent), default=1)
    if max_duration == 0:
        max_duration = 1

    bar_count = len(recent)
    if bar_count == 0:
        bar_width = 0
        bar_spacing = 0
    else:
        bar_width = chart_width / bar_count * 0.65
        bar_spacing = chart_width / bar_count * 0.35

    # Calculate totals for the header
    total_sessions = len(stats)
    total_clients = sum(s.get("clients_helped", 0) for s in stats)
    total_duration = sum(s.get("duration_seconds", 0) for s in stats)
    total_hours = total_duration // 3600
    total_minutes = (total_duration % 3600) // 60

    # Status message
    if total_clients > 0:
        status_msg = f"{total_clients} people helped across {total_sessions} sessions"
        status_color = "#00E5C4"
    elif total_sessions > 0:
        status_msg = f"Armed & monitoring — {total_sessions} sessions completed, awaiting first client"
        status_color = "#888888"
    else:
        status_msg = "Initializing — first session pending"
        status_color = "#555555"

    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
        f'viewBox="0 0 {width} {height}" font-family="-apple-system, BlinkMacSystemFont, Segoe UI, Helvetica, Arial, sans-serif">',

        # === Background with subtle gradient ===
        f'<defs>',
        f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">',
        f'<stop offset="0%" stop-color="#0D0D0D"/>',
        f'<stop offset="100%" stop-color="#080808"/>',
        f'</linearGradient>',
        # Bar gradient (with clients — jade glow)
        f'<linearGradient id="barActive" x1="0" y1="0" x2="0" y2="1">',
        f'<stop offset="0%" stop-color="#00E5C4"/>',
        f'<stop offset="100%" stop-color="#006B5A"/>',
        f'</linearGradient>',
        # Bar gradient (no clients — dim teal)
        f'<linearGradient id="barIdle" x1="0" y1="0" x2="0" y2="1">',
        f'<stop offset="0%" stop-color="#1A3A35"/>',
        f'<stop offset="100%" stop-color="#0D1F1C"/>',
        f'</linearGradient>',
        # Glow filter for active bars
        f'<filter id="glow" x="-20%" y="-20%" width="140%" height="140%">',
        f'<feGaussianBlur stdDeviation="3" result="blur"/>',
        f'<feMerge><feMergeNode in="blur"/><feMergeNode in="SourceGraphic"/></feMerge>',
        f'</filter>',
        f'</defs>',

        f'<rect width="{width}" height="{height}" fill="url(#bg)" rx="8"/>',

        # === Header bar ===
        # LIVE indicator (pulsing circle + text)
        f'<circle cx="20" cy="20" r="4" fill="#00E5C4">',
        f'<animate attributeName="opacity" values="1;0.3;1" dur="2s" repeatCount="indefinite"/>',
        f'</circle>',
        f'<text x="30" y="24" fill="#00E5C4" font-size="11" font-weight="600" letter-spacing="1.5">LIVE</text>',

        # Title
        f'<text x="55" y="24" fill="#E0E0E0" font-size="14" font-weight="700">'
        f'Snowflake Volunteer Dashboard</text>',

        # Stats summary (right-aligned)
        f'<text x="{width - padding_right}" y="24" fill="#888888" font-size="11" text-anchor="end" font-weight="500">'
        f'{total_sessions} sessions  ·  {total_clients} helped  ·  {total_hours}h {total_minutes}m donated</text>',

        # Divider line
        f'<line x1="{padding_left}" y1="38" x2="{width - padding_right}" y2="38" '
        f'stroke="#1A1A1A" stroke-width="1"/>',

        # Subtitle
        f'<text x="{padding_left}" y="55" fill="#555555" font-size="10">'
        f'Session duration (minutes) — green bars indicate sessions where censored users connected</text>',
    ]

    # === Grid lines (dashed, subtle) ===
    max_minutes = max_duration / 60
    for pct in [0, 25, 50, 75, 100]:
        y = padding_top + chart_height - (chart_height * pct / 100)
        value_minutes = int(max_minutes * pct / 100)
        # Dashed grid line
        parts.append(
            f'<line x1="{padding_left}" y1="{y:.1f}" '
            f'x2="{width - padding_right}" y2="{y:.1f}" '
            f'stroke="#1A1A1A" stroke-width="1" stroke-dasharray="3,4"/>'
        )
        # Y-axis label
        parts.append(
            f'<text x="{padding_left - 8}" y="{y + 4:.1f}" '
            f'fill="#555555" font-size="9" text-anchor="end">{value_minutes}m</text>'
        )

    # === Bars ===
    for i, session in enumerate(recent):
        x = padding_left + i * (bar_width + bar_spacing) + bar_spacing / 2
        duration = session.get("duration_seconds", 0)
        clients = session.get("clients_helped", 0)
        bar_h = (duration / max_duration) * chart_height if max_duration > 0 else 0
        y = padding_top + chart_height - bar_h

        # Choose gradient based on whether clients connected
        grad_id = "barActive" if clients > 0 else "barIdle"
        filter_attr = ' filter="url(#glow)"' if clients > 0 else ''

        # Bar with rounded top corners
        parts.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_width:.1f}" height="{max(bar_h, 3):.1f}" '
            f'fill="url(#{grad_id})" rx="3"{filter_attr}/>'
        )

        # Duration label inside bar (if bar is tall enough)
        if bar_h > 25:
            duration_min = int(duration / 60)
            if duration_min > 0:
                label_color = "#000000" if clients > 0 else "#3A5A55"
                parts.append(
                    f'<text x="{x + bar_width/2:.1f}" y="{y + 15:.1f}" '
                    f'fill="{label_color}" font-size="9" text-anchor="middle" font-weight="600">'
                    f'{duration_min}m</text>'
                )

        # Client count above bar (only if > 0)
        if clients > 0:
            parts.append(
                f'<text x="{x + bar_width/2:.1f}" y="{y - 5:.1f}" '
                f'fill="#00E5C4" font-size="11" text-anchor="middle" font-weight="700">'
                f'{clients}</text>'
            )

        # Date label (first, every 5th, and last bar)
        if i == 0 or i % 5 == 0 or i == bar_count - 1:
            ts = session.get("timestamp", "")[:10]  # YYYY-MM-DD
            parts.append(
                f'<text x="{x + bar_width/2:.1f}" y="{height - 18}" '
                f'fill="#555555" font-size="9" text-anchor="middle">{ts[5:]}</text>'
            )

    # === Status message (bottom) ===
    parts.append(
        f'<text x="{width/2:.0f}" y="{height - 5}" fill="{status_color}" font-size="10" '
        f'text-anchor="middle" font-style="italic">{status_msg}</text>'
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
    total_hours = total_duration // 3600
    total_minutes = (total_duration % 3600) // 60

    # Cache-buster for the SVG image URL
    last_ts = stats[-1].get("timestamp", "").replace(":", "").replace("-", "") if stats else ""
    svg_url = f"snowflake-volunteer/dashboard.svg?v={last_ts}"

    # Dynamic status badge
    if total_clients > 0:
        status_badge = "https://img.shields.io/badge/status-HELPING%20USERS-00E5C4?style=for-the-badge"
    elif total_sessions > 0:
        status_badge = "https://img.shields.io/badge/status-ARMED%20%26%20MONITORING-00E5C4?style=for-the-badge"
    else:
        status_badge = "https://img.shields.io/badge/status-INITIALIZING-888888?style=for-the-badge"

    section = f"""<!-- SNOWFLAKE-VOLUNTEER-START -->
## Snowflake Volunteer

![Status]({status_badge})
![Sessions](https://img.shields.io/badge/sessions-{total_sessions}-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![People Helped](https://img.shields.io/badge/people%20helped-{total_clients}-00E5C4?style=for-the-badge&labelColor=1A1A1A)
![Time Donated](https://img.shields.io/badge/time%20donated-{total_hours}h%20{total_minutes}m-0D0D0D?style=for-the-badge&labelColor=1A1A1A)
![Bandwidth](https://img.shields.io/badge/bandwidth-{format_bytes(total_bytes).replace("%20","%20").replace(" ","%20")}-0D0D0D?style=for-the-badge&labelColor=1A1A1A)

This repository runs a **Tor Snowflake proxy** on GitHub Actions runners every 6 hours, donating ~5h50m of compute per session to help people in censored countries bypass internet restrictions. Zero external services, zero cost — pure creative use of CI compute for human rights.

![Dashboard]({svg_url})

> *Last updated: {datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")} · Auto-regenerates after each session*

<details>
<summary>How it works</summary>

1. Every 6 hours, a GitHub Actions runner (ubuntu-latest) spins up
2. Builds the snowflake-proxy binary from source (Tor Project, Go)
3. Runs the proxy for ~5h50m (just under the 6h job timeout)
4. Real censored users connect via WebRTC through our proxy
5. Stats are parsed from the proxy log and committed back to `main`
6. This README section + the SVG dashboard auto-regenerate

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
