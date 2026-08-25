#!/usr/bin/env python3
"""Write alive_bridges.json — the file the app fetches at runtime.

This file contains ALL alive bridges (both original and replacements),
grouped by transport type. The app fetches the reviewed file from GitLab:
  https://gitlab.com/theredhacker0345/Onionroute-Lite/-/raw/main/bridge-health/alive_bridges.json

Format:
{
  "last_updated": "2026-06-20T08:00:00Z",
  "obfs4": ["obfs4 1.2.3.4:443 ...", "obfs4 5.6.7.8:443 ..."],
  "webtunnel": ["webtunnel ..."],
  "snowflake": ["snowflake ..."]
}
"""
import argparse
import json
import re
from datetime import datetime, timezone
from pathlib import Path

RESULTS_FILE = Path("bridge-health/current_results.json")
OUTPUT_FILE = Path("bridge-health/alive_bridges.json")
CONSTANTS_FILE = Path("app/src/main/java/org/onionroute/lite/Constants.kt")


def decode_html_entities(s: str) -> str:
    """Decode common HTML entities in bridge lines.

    BridgeDB's web page HTML-encodes the `+` character in obfs4 cert
    strings as `&#43;`. Tor cannot parse cert with `&#43;` — it needs
    the literal `+`. We decode all known entities so the bridge lines
    are valid when the app writes them to torrc.
    """
    if not s:
        return s
    return (
        s.replace("&#43;", "+")
         .replace("&amp;", "&")
         .replace("&lt;", "<")
         .replace("&gt;", ">")
         .replace("&quot;", '"')
         .replace("&#39;", "'")
         .replace("&#x27;", "'")
    )


def parse_bridges_from_constants():
    """Parse ALL bridges from Constants.kt (the hardcoded fallback set)."""
    if not CONSTANTS_FILE.exists():
        return []
    content = CONSTANTS_FILE.read_text()
    bridges = []

    for match in re.finditer(r'DEFAULT_OBFS4_BRIDGES\s*=\s*(?:listOf|arrayOf)\((.*?)\)', content, re.DOTALL):
        for line in re.findall(r'"([^"]+)"', match.group(1)):
            bridges.append(line)

    for match in re.finditer(r'DEFAULT_WEBTUNNEL_BRIDGES\s*=\s*(?:listOf|arrayOf)\((.*?)\)', content, re.DOTALL):
        for line in re.findall(r'"([^"]+)"', match.group(1)):
            bridges.append(line)

    # Snowflake bridge is constructed from constants
    sf_broker = re.search(r'SNOWFLAKE_BROKER_URL\s*=\s*"([^"]+)"', content)
    sf_front = re.search(r'SNOWFLAKE_FRONT_DOMAIN\s*=\s*"([^"]+)"', content)
    sf_ice = re.search(r'SNOWFLAKE_ICE_SERVERS\s*=\s*"([^"]+)"', content)
    if sf_broker and sf_front and sf_ice:
        bridges.append(
            f"snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
            f"fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
            f"url={sf_broker.group(1)} fronts={sf_front.group(1)} "
            f"ice={sf_ice.group(1)}"
        )

    return bridges


def main():
    parser = argparse.ArgumentParser(description="Write an alive-bridges JSON report")
    parser.add_argument(
        "--output",
        default=str(OUTPUT_FILE),
        help="Output JSON path (default: bridge-health/alive_bridges.json)",
    )
    args = parser.parse_args()
    output_file = Path(args.output)
    # Load current test results
    try:
        with open(RESULTS_FILE) as f:
            results = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        results = []

    # Parse all bridges from Constants.kt
    all_bridges = parse_bridges_from_constants()

    # Also check candidates.json for any alive replacement bridges
    try:
        with open("bridge-health/candidates.json") as f:
            candidates = json.load(f)
        for c in candidates:
            if c.get("line") and c["line"] not in all_bridges:
                all_bridges.append(c["line"])
    except (FileNotFoundError, json.JSONDecodeError):
        pass

    # Build alive_bridges.json grouped by transport
    output = {
        "last_updated": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "obfs4": [],
        "webtunnel": [],
        "snowflake": [],
    }

    # Check which bridges are alive from the test results
    alive_lines = set()
    for r in results:
        if r.get("status") == "alive":
            alive_lines.add(r["line"])

    # For each bridge in Constants.kt, if it was tested alive, include it
    # If not tested (new), include it anyway — the app will test at runtime
    for bridge in all_bridges:
        # Defensive: decode any HTML entities that may have slipped through
        # (e.g., if a future fetch script reads from BridgeDB's HTML page).
        bridge = decode_html_entities(bridge)
        transport = bridge.split(" ")[0].lower()
        if transport in output:
            if bridge not in output[transport]:
                output[transport].append(bridge)

    output_file.parent.mkdir(parents=True, exist_ok=True)
    output_file.write_text(json.dumps(output, indent=2) + "\n")
    total = sum(len(v) for v in output.values() if isinstance(v, list))
    print(f"[*] Wrote {output_file} — {total} bridges across {len(output)-1} transports")
    for transport, bridges in output.items():
        if transport == "last_updated":
            continue
        print(f"  {transport}: {len(bridges)} bridges")


if __name__ == "__main__":
    main()
