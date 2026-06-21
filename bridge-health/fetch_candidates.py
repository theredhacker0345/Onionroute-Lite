#!/usr/bin/env python3
"""Fetch replacement bridges from BridgeDB, test them, make swap decisions.

No AI. Pure Python. Deterministic logic:
- Bridge dead 3+ times + alive replacement available = SWAP
- No alive replacement = SKIP

Also writes decision.json for apply_changes.py to execute.
"""
import json
import os
import re
import sys
import socket
import urllib.request
import urllib.error
from pathlib import Path

BRIDGEDB_URL = "https://bridges.torproject.org/bridges"
USER_AGENT = "OnionRoute-Lite/1.0 (bridge health monitor; +https://github.com/darkmaster0345/Onionroute-Lite)"
SUPPORTED_TRANSPORTS = ["obfs4", "webtunnel", "snowflake"]


def fetch_bridges_from_bridgedb(transport):
    """Fetch bridge lines from BridgeDB. Returns list of bridge line strings."""
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
        if clean.startswith(transport + " "):
            bridges.append(clean)
    return bridges


def tcp_test(bridge_line, timeout=5):
    """TCP connectivity check. Returns True if bridge is accepting connections."""
    # Extract IP:port
    match = re.search(r'(\d+\.\d+\.\d+\.\d+):(\d+)', bridge_line)
    if not match:
        match = re.search(r'\[([0-9a-fA-F:]+)\]:(\d+)', bridge_line)
        if match:
            host, port = match.group(1), int(match.group(2))
        else:
            return False
    else:
        host, port = match.group(1), int(match.group(2))

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
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

    # Find dead bridges needing replacement (3+ failures)
    dead_bridges = []
    for r in results:
        if r["status"] != "alive" and failure_counts.get(r["line"], 0) >= 3:
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
    actions = []
    used_candidates = set()
    for dead in dead_bridges:
        for i, c in enumerate(candidates):
            if i in used_candidates:
                continue
            if c["transport"] == dead["transport"]:
                actions.append({
                    "type": "swap",
                    "dead_bridge_line": dead["line"],
                    "replacement_bridge_line": c["line"],
                    "reason": f"{dead['transport']} bridge failed {failure_counts.get(dead['line'], 0)}x. "
                              f"Replacement TCP-alive.",
                })
                used_candidates.add(i)
                print(f"[*] SWAP: {dead['transport']} (failed {failure_counts.get(dead['line'], 0)}x) "
                      f"→ {c['line'][:40]}...", file=sys.stderr)
                break

    # Write decision
    if actions:
        transports = list(set(a["dead_bridge_line"].split(" ")[0] for a in actions))
        decision = {
            "actions": actions,
            "pr_title": f"fix(bridges): swap {len(actions)} dead {', '.join(transports)} bridge(s)",
            "pr_body": f"## Bridge Health — Auto-Swap\n\n"
                       f"Swapped {len(actions)} dead bridge(s) for verified-alive replacements from BridgeDB.\n\n"
                       f"Each replacement was TCP-tested (5s timeout) to confirm the bridge server is online.",
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
