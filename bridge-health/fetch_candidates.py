#!/usr/bin/env python3
"""Fetch REAL replacement bridges from Tor Project's BridgeDB.

Tor Project provides bridges via:
1. BridgeDB HTTPS API: https://bridges.torproject.org/bridges?transport=<type>
   - Returns HTML with bridge lines. Rate-limited but works from CI.
2. Moat API: https://bridges.torproject.org/moat/circumvention
   - Returns JSON with working bridges by country.
   - Better for automated use.

This script:
1. Queries BridgeDB for bridges of each transport type that has dead bridges
2. Parses the response to extract bridge lines
3. Tests each candidate with real Tor bootstrap (via test_bridges.py)
4. Only returns VERIFIED ALIVE bridges as candidates

If BridgeDB is rate-limited or unavailable, falls back to the Tor
Project's public bridge distribution endpoints.
"""
import json
import re
import subprocess
import sys
import os
from pathlib import Path

# BridgeDB endpoints
BRIDGEDB_URL = "https://bridges.torproject.org/bridges"
MOAT_URL = "https://bridges.torproject.org/moat/circumvention"

# User agent — BridgeDB requires one, blocks empty/default UAs
USER_AGENT = "OnionRoute-Lite/1.0 (bridge health monitor; +https://github.com/darkmaster0345/Onionroute-Lite)"

# Transports supported by BridgeDB
SUPPORTED_TRANSPORTS = ["obfs4", "snowflake", "webtunnel"]


def fetch_bridges_from_bridgedb(transport: str) -> list:
    """Fetch bridge lines from BridgeDB HTTPS endpoint.

    Returns a list of bridge line strings.
    BridgeDB returns HTML like:
      <div class="bridge-lines">
        obfs4 1.2.3.4:443 FINGERPRINT cert=... iat-mode=0
        obfs4 5.6.7.8:443 FINGERPRINT cert=... iat-mode=0
      </div>
    """
    import urllib.request
    import urllib.error

    url = f"{BRIDGEDB_URL}?transport={transport}"
    print(f"[*] Fetching {transport} bridges from BridgeDB: {url}", file=sys.stderr)

    req = urllib.request.Request(url, headers={
        "User-Agent": USER_AGENT,
        "Accept": "text/html",
    })

    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            html = resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        print(f"[!] BridgeDB returned HTTP {e.code} for {transport}", file=sys.stderr)
        return []
    except Exception as e:
        print(f"[!] BridgeDB fetch failed for {transport}: {e}", file=sys.stderr)
        return []

    # Parse bridge lines from HTML
    # Look for lines that start with the transport name
    bridge_lines = []
    for line in html.split("\n"):
        line = line.strip()
        # Strip HTML tags
        clean = re.sub(r"<[^>]+>", "", line).strip()
        if clean.startswith(transport + " "):
            bridge_lines.append(clean)

    print(f"[*] Found {len(bridge_lines)} {transport} bridge lines from BridgeDB", file=sys.stderr)
    return bridge_lines


def fetch_bridges_from_moat(transport: str) -> list:
    """Fetch bridges from Tor's Moat circumvention API.

    Moat returns JSON with bridges categorized by transport.
    POST to /moat/circumvention with {"country": "xx"} returns
    bridges that work in that country.
    """
    import urllib.request
    import urllib.error

    print(f"[*] Fetching {transport} bridges from Moat API", file=sys.stderr)

    # Moat requires a POST with country code
    data = json.dumps({"country": "us"}).encode()
    req = urllib.request.Request(
        MOAT_URL,
        data=data,
        headers={
            "User-Agent": USER_AGENT,
            "Content-Type": "application/vnd.api+json",
            "Accept": "application/vnd.api+json",
        },
        method="POST",
    )

    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            result = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"[!] Moat API failed: {e}", file=sys.stderr)
        return []

    # Parse moat response — it has a "settings" array with bridges
    bridge_lines = []
    settings = result.get("data", [{}])[0].get("settings", [])
    for setting in settings:
        bridges = setting.get("bridges", {})
        transport_bridges = bridges.get(transport, [])
        for b in transport_bridges:
            bridge_lines.append(b)

    print(f"[*] Found {len(bridge_lines)} {transport} bridges from Moat", file=sys.stderr)
    return bridge_lines


def test_candidate_bridge(bridge_line: str, transport: str, plugin_path: str, timeout: int = 60) -> dict:
    """Test a single candidate bridge with real Tor bootstrap.

    Returns: {transport, line, status, bootstrap_time, error}
    """
    # Use test_bridges.py's test_single_bridge function
    sys.path.insert(0, str(Path(__file__).parent))
    from test_bridges import test_single_bridge

    bridge = {"transport": transport, "line": bridge_line, "raw": bridge_line}
    result = test_single_bridge(bridge, timeout, plugin_path)
    return result


def main():
    # Load current health data to find which transports have dead bridges
    health_path = Path("bridge-health/health.json")
    if not health_path.exists():
        print("[!] No health.json — cannot determine which transports need replacements", file=sys.stderr)
        Path("bridge-health/candidates.json").write_text("[]")
        return

    health = json.loads(health_path.read_text())
    failure_counts = health.get("failure_counts", {})

    # Find transports with dead bridges (3+ failures)
    dead_transports = set()
    for line, count in failure_counts.items():
        if count >= 3:
            if line.startswith("obfs4"):
                dead_transports.add("obfs4")
            elif line.startswith("snowflake"):
                dead_transports.add("snowflake")
            elif line.startswith("webtunnel"):
                dead_transports.add("webtunnel")

    if not dead_transports:
        print("[*] No transports have dead bridges — no candidates needed", file=sys.stderr)
        Path("bridge-health/candidates.json").write_text("[]")
        return

    print(f"[*] Dead transports needing replacements: {dead_transports}", file=sys.stderr)

    # ALWAYS fetch ALL transport types — BridgeDB returns different bridges
    # each time, and we want a pool of alive replacements for any transport
    # that has dead bridges. If we only fetch dead transports, we might
    # miss available obfs4 bridges when BridgeDB happens to return
    # webtunnel/snowflake instead.
    fetch_transports = dead_transports if dead_transports else set(SUPPORTED_TRANSPORTS)
    # Always include ALL supported transports to maximize chances of
    # finding alive replacements
    fetch_transports = set(SUPPORTED_TRANSPORTS)

    # Get plugin path from environment (set by the workflow)
    plugin_path = os.environ.get("PLUGIN_PATH", "/usr/bin")
    go_path = os.environ.get("GOPATH", "/home/runner/go")
    plugin_path = f"{go_path}/bin:{plugin_path}"

    candidates = []

    for transport in fetch_transports:
        # Try BridgeDB first
        bridge_lines = fetch_bridges_from_bridgedb(transport)

        # Fall back to Moat if BridgeDB returned nothing
        if not bridge_lines:
            bridge_lines = fetch_bridges_from_moat(transport)

        if not bridge_lines:
            print(f"[!] No {transport} bridges found from any source", file=sys.stderr)
            continue

        # Test each candidate (limit to 2 — we only need ONE alive bridge per transport)
        tested = 0
        max_test = 2
        for line in bridge_lines:
            if tested >= max_test:
                print(f"[*] Tested {max_test} {transport} candidates, stopping", file=sys.stderr)
                break

            print(f"[*] Testing candidate {tested+1}: {line[:60]}...", file=sys.stderr)
            result = test_candidate_bridge(line, transport, plugin_path, timeout=90)

            if result["status"] == "alive":
                print(f"    ✓ ALIVE — bootstrap in {result['bootstrap_time']}s", file=sys.stderr)
                candidates.append({
                    "transport": transport,
                    "line": line,
                    "bootstrap_time": result["bootstrap_time"],
                    "source": "BridgeDB",
                })
                # Found a working replacement — one is enough per transport
                break
            else:
                print(f"    ✗ {result['status']} — {result.get('error', 'unknown')}", file=sys.stderr)
                tested += 1

    # Write candidates
    Path("bridge-health/candidates.json").write_text(json.dumps(candidates, indent=2))
    print(f"\n[*] Found {len(candidates)} verified-alive replacement bridges:", file=sys.stderr)
    for c in candidates:
        print(f"    [{c['transport']}] {c['line'][:60]}... ({c['bootstrap_time']}s)", file=sys.stderr)


if __name__ == "__main__":
    main()
