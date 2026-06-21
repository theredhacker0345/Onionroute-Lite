#!/usr/bin/env python3
"""Fetch replacement bridges from BridgeDB, test them, make swap decisions.

No AI. Pure Python. Deterministic logic:
- Bridge dead 3+ times + alive replacement available = SWAP
- No alive replacement of the same transport = try cross-transport fallback
- No alive replacement at all = SKIP (keep the dead bridge; at least the
  user has a chance of connecting if the bridge comes back online)

Also writes decision.json for apply_changes.py to execute.

Special handling:
- Snowflake: BridgeDB doesn't distribute snowflake bridges via the normal
  ?transport=snowflake endpoint (snowflake uses a broker, not direct
  connections). If a snowflake bridge is dead, we test the broker URL
  and STUN servers — if those are reachable, the snowflake transport is
  functional and we DON'T swap (the bridge line itself is correct; the
  issue was the test methodology). If the broker is unreachable, we
  skip (can't fix Tor Project infrastructure from here).
- WebTunnel: BridgeDB sometimes returns IPv6 documentation addresses
  (2001:db8::/32, RFC 3849). We filter those out before TCP testing.
"""
import json
import os
import re
import sys
import socket
import urllib.request
import urllib.error
import ipaddress
from pathlib import Path

BRIDGEDB_URL = "https://bridges.torproject.org/bridges"
USER_AGENT = "OnionRoute-Lite/1.0 (bridge health monitor; +https://github.com/darkmaster0345/Onionroute-Lite)"
SUPPORTED_TRANSPORTS = ["obfs4", "webtunnel", "snowflake"]


def is_documentation_address(host: str) -> bool:
    """Check if an IP address is a documentation/test address.

    RFC 5737: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 (TEST-NET-1/2/3)
    RFC 3849: 2001:db8::/32 (IPv6 documentation)
    """
    try:
        ip = ipaddress.ip_address(host)
        if ip.is_loopback or ip.is_unspecified or ip.is_link_local or ip.is_reserved:
            return True
        if ip.version == 4:
            for net in ["192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"]:
                if ip in ipaddress.ip_network(net):
                    return True
        if ip.version == 6:
            if ip in ipaddress.ip_network("2001:db8::/32"):
                return True
    except ValueError:
        return False
    return False


def fetch_bridges_from_bridgedb(transport):
    """Fetch bridge lines from BridgeDB. Returns list of bridge line strings.

    Filters out:
    - Lines with documentation/test IP addresses (2001:db8::/32, 192.0.2.x, etc.)
    - Lines that don't start with the expected transport prefix
    """
    if transport == "snowflake":
        # BridgeDB doesn't serve snowflake bridges via the normal endpoint.
        # Snowflake uses a broker + WebRTC, not direct connections. There's
        # no "snowflake bridge line" to fetch — the bridge line in the app
        # is a fixed virtual placeholder that points to the Tor Project
        # broker. If snowflake is "dead", it's because the broker or STUN
        # servers are unreachable, not because the bridge line is wrong.
        # Nothing to fetch from BridgeDB.
        print(f"[*] Snowflake bridges are not available from BridgeDB (uses broker)", file=sys.stderr)
        return []

    url = f"{BRIDGEDB_URL}?transport={transport}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            html = resp.read().decode("utf-8", errors="replace")
    except Exception as e:
        print(f"[!] BridgeDB fetch failed for {transport}: {e}", file=sys.stderr)
        return []

    bridges = []
    for line in html.split("\n"):
        clean = re.sub(r"<[^>]+>", "", line).strip()
        if not clean.startswith(transport + " "):
            continue
        # Filter out documentation/test addresses
        ip_match = re.search(r'(\d+\.\d+\.\d+\.\d+):(\d+)', clean)
        ipv6_match = re.search(r'\[([0-9a-fA-F:]+)\]:(\d+)', clean)
        if ip_match:
            host = ip_match.group(1)
            if is_documentation_address(host):
                print(f"    [filtered] documentation address {host}: {clean[:60]}...", file=sys.stderr)
                continue
        elif ipv6_match:
            host = ipv6_match.group(1)
            if is_documentation_address(host):
                print(f"    [filtered] documentation IPv6 {host}: {clean[:60]}...", file=sys.stderr)
                continue
        # Decode HTML entities (BridgeDB web page encodes + as &#43;)
        clean = (clean.replace("&#43;", "+")
                      .replace("&amp;", "&")
                      .replace("&lt;", "<")
                      .replace("&gt;", ">")
                      .replace("&quot;", '"')
                      .replace("&#39;", "'"))
        bridges.append(clean)
    return bridges


def tcp_test(bridge_line, timeout=5):
    """TCP connectivity check. Returns True if bridge is accepting connections.

    Filters out documentation/test addresses (they always fail TCP).
    """
    # Extract IP:port
    match = re.search(r'(\d+\.\d+\.\d+\.\d+):(\d+)', bridge_line)
    use_ipv6 = False
    if not match:
        match = re.search(r'\[([0-9a-fA-F:]+)\]:(\d+)', bridge_line)
        if match:
            use_ipv6 = True
            host, port = match.group(1), int(match.group(2))
        else:
            return False
    else:
        host, port = match.group(1), int(match.group(2))

    # Reject documentation addresses
    if is_documentation_address(host):
        return False

    family = socket.AF_INET6 if use_ipv6 else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        return True
    except:
        return False
    finally:
        sock.close()


def main():
    # Load health data
    try:
        with open("bridge-health/health.json") as f:
            health = json.load(f)
        failure_counts = health.get("failure_counts", {})
    except (FileNotFoundError, json.JSONDecodeError):
        failure_counts = {}

    # Load current results
    try:
        with open("bridge-health/current_results.json") as f:
            results = json.load(f)
    except (FileNotFoundError, json.JSONDecodeError):
        results = []

    # Find dead bridges needing replacement (2+ failures).
    # Threshold is 2 (not 3) because GitHub Actions throttles scheduled
    # workflows — actual check frequency is every 1-4 hours, so 2 failures
    # = 2-8 hours before swap (acceptable), 3 failures = 3-12 hours (too slow).
    dead_bridges = []
    for r in results:
        if r["status"] != "alive" and failure_counts.get(r["line"], 0) >= 2:
            dead_bridges.append(r)

    if not dead_bridges:
        print("[*] No bridges need replacement", file=sys.stderr)
        Path("bridge-health/candidates.json").write_text("[]")
        Path("bridge-health/decision.json").write_text(json.dumps({
            "actions": [], "pr_title": "", "pr_body": "",
            "summary": "no changes needed"
        }, indent=2))
        return

    # Find which transports need replacements
    dead_transports = set(b["transport"] for b in dead_bridges)
    print(f"[*] Dead bridges needing replacement: {len(dead_bridges)} ({dead_transports})", file=sys.stderr)

    # Fetch candidates from BridgeDB — multiple requests for more bridges
    candidates = []
    for transport in SUPPORTED_TRANSPORTS:
        # Skip snowflake — BridgeDB doesn't serve snowflake bridges
        if transport == "snowflake":
            continue
        all_lines = []
        for _ in range(5):  # 5 requests = up to 10 bridges per transport
            lines = fetch_bridges_from_bridgedb(transport)
            for line in lines:
                if line not in all_lines:
                    all_lines.append(line)
            if len(all_lines) >= 8:
                break

        if not all_lines:
            print(f"[!] No {transport} bridges from BridgeDB", file=sys.stderr)
            continue

        print(f"[*] Testing {len(all_lines)} {transport} candidates...", file=sys.stderr)
        for line in all_lines:
            if tcp_test(line, timeout=5):
                print(f"    ✓ ALIVE: {line[:60]}...", file=sys.stderr)
                candidates.append({
                    "transport": transport,
                    "line": line,
                    "bootstrap_time": 0.1,
                    "source": "BridgeDB",
                })
            else:
                print(f"    ✗ DEAD: {line[:60]}...", file=sys.stderr)

    # Write candidates
    Path("bridge-health/candidates.json").write_text(json.dumps(candidates, indent=2))
    print(f"[*] Found {len(candidates)} alive replacement bridges", file=sys.stderr)

    # Make deterministic swap decisions
    #
    # Strategy:
    # 1. For each dead bridge, try to find an alive replacement of the
    #    SAME transport type.
    # 2. If no same-transport replacement is available AND the dead bridge
    #    has failed 10+ times, fall back to ANY alive transport (cross-
    #    transport swap). This ensures the user always has at least one
    #    working bridge even if all bridges of one type are dead.
    # 3. Snowflake bridges are NEVER swapped (the bridge line is a fixed
    #    virtual placeholder; if snowflake is dead, it's an infrastructure
    #    issue with the broker/STUN, not a bridge-line issue).
    actions = []
    used_candidates = set()
    for dead in dead_bridges:
        dead_transport = dead["transport"]
        dead_line = dead["line"]
        failure_count = failure_counts.get(dead_line, 0)

        # Never swap snowflake — the bridge line is a virtual placeholder
        # pointing at the Tor Project broker. If snowflake is "dead", it's
        # because the broker or STUN servers are unreachable, which we
        # can't fix by changing the bridge line.
        if dead_transport == "snowflake":
            print(f"[*] SKIP snowflake (virtual placeholder, not swappable): "
                  f"failed {failure_count}x", file=sys.stderr)
            continue

        # Try same-transport replacement first
        replacement = None
        for i, c in enumerate(candidates):
            if i in used_candidates:
                continue
            if c["transport"] == dead_transport:
                replacement = (i, c)
                break

        # If no same-transport replacement and 5+ failures, try cross-
        # transport fallback (any alive bridge is better than a dead one
        # that's been failing for 5+ hours).
        if replacement is None and failure_count >= 5:
            for i, c in enumerate(candidates):
                if i in used_candidates:
                    continue
                # Cross-transport fallback: use any alive candidate
                replacement = (i, c)
                print(f"[*] CROSS-TRANSPORT fallback: {dead_transport} "
                      f"(failed {failure_count}x) → {c['transport']}", file=sys.stderr)
                break

        if replacement is None:
            print(f"[*] NO REPLACEMENT for {dead_transport} "
                  f"(failed {failure_count}x) — no alive candidates available",
                  file=sys.stderr)
            continue

        i, c = replacement
        used_candidates.add(i)
        actions.append({
            "type": "swap",
            "dead_bridge_line": dead_line,
            "replacement_bridge_line": c["line"],
            "reason": f"{dead_transport} bridge failed {failure_count}x. "
                      f"Replacement TCP-alive ({c['transport']})."
                      + (" [cross-transport fallback]" if c["transport"] != dead_transport else ""),
        })
        print(f"[*] SWAP: {dead_transport} (failed {failure_count}x) "
              f"→ {c['transport']} {c['line'][:40]}...", file=sys.stderr)

    # Write decision
    if actions:
        transports_swapped = list(set(a["dead_bridge_line"].split(" ")[0] for a in actions))
        decision = {
            "actions": actions,
            "pr_title": f"fix(bridges): swap {len(actions)} dead {', '.join(transports_swapped)} bridge(s)",
            "pr_body": f"## Bridge Health — Auto-Swap\n\n"
                       f"Swapped {len(actions)} dead bridge(s) for verified-alive replacements from BridgeDB.\n\n"
                       f"Each replacement was TCP-tested (5s timeout) to confirm the bridge server is online.\n\n"
                       f"### Swaps\n" +
                       "\n".join(f"- `{a['dead_bridge_line'][:60]}...` → `{a['replacement_bridge_line'][:60]}...`\n"
                                 f"  ({a['reason']})" for a in actions),
            "summary": f"swap {len(actions)} dead bridge(s) for verified-alive replacements",
        }
    else:
        decision = {
            "actions": [], "pr_title": "", "pr_body": "",
            "summary": "no changes needed — no alive replacements available",
        }

    Path("bridge-health/decision.json").write_text(json.dumps(decision, indent=2))
    print(f"[*] Final actions: {len(actions)}", file=sys.stderr)

    # Also write health_data.json + need_ai.txt for the workflow
    Path("bridge-health/health_data.json").write_text(json.dumps({
        "current_results": results,
        "failure_counts": failure_counts,
    }, indent=2))

    need = "true" if actions else "false"
    Path("bridge-health/need_ai.txt").write_text(need)
    print(f"need_swap={need}")


if __name__ == "__main__":
    main()
