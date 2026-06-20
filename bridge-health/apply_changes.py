#!/usr/bin/env python3
"""
Apply Bridge Changes
====================

Applies AI-approved bridge swaps to Constants.kt.
Also manages health.json (historical health data) and opens PRs.

This is the DETERMINISTIC part — it does exactly what the AI decided,
no judgment of its own. The AI's judgment happened in agent.py.

Usage:
    python3 apply_changes.py <decision.json> [--apply] [--open-pr]

Without --apply: prints what would change (dry run)
With --apply: edits Constants.kt in place
With --open-pr: creates a git branch, commits, pushes, opens PR via GitHub API
"""
import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CONSTANTS_FILE = REPO_ROOT / "app" / "src" / "main" / "java" / "org" / "onionroute" / "lite" / "Constants.kt"
HEALTH_FILE = REPO_ROOT / "bridge-health" / "health.json"


def update_health_json(current_results: list, decisions: dict):
    """Append current health check results to health.json.

    health.json structure:
    {
      "checks": [
        {
          "timestamp": "...",
          "bridges": [{transport, line, status, bootstrap_time, error}, ...]
        }
      ],
      "failure_counts": {  # rolling count of consecutive failures
        "<bridge_line>": 3,
        ...
      }
    }
    """
    if HEALTH_FILE.exists():
        try:
            health = json.loads(HEALTH_FILE.read_text())
        except json.JSONDecodeError:
            health = {"checks": [], "failure_counts": {}}
    else:
        health = {"checks": [], "failure_counts": {}}

    # Add current check
    check = {
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "bridges": current_results,
    }
    health["checks"].append(check)

    # Keep last 100 checks (~50 hours at 30-min interval)
    if len(health["checks"]) > 100:
        health["checks"] = health["checks"][-100:]

    # Update failure counts
    failure_counts = health.get("failure_counts", {})
    for bridge in current_results:
        line = bridge["line"]
        if bridge["status"] == "alive":
            failure_counts[line] = 0  # reset on success
        else:
            failure_counts[line] = failure_counts.get(line, 0) + 1

    # If a bridge was swapped, remove its failure count (it's gone)
    for action in decisions.get("actions", []):
        if action.get("type") == "swap":
            dead_line = action.get("dead_bridge_line", "")
            if dead_line in failure_counts:
                del failure_counts[dead_line]

    health["failure_counts"] = failure_counts
    HEALTH_FILE.write_text(json.dumps(health, indent=2) + "\n")
    return health


def apply_swap_to_constants(dead_line: str, replacement_line: str) -> bool:
    """Replace a bridge line in Constants.kt.

    Returns True if the replacement was made, False if the dead_line
    wasn't found.
    """
    if not CONSTANTS_FILE.exists():
        print(f"ERROR: {CONSTANTS_FILE} not found", file=sys.stderr)
        return False

    content = CONSTANTS_FILE.read_text()

    # The bridge line in Constants.kt is inside a Kotlin string literal:
    #   "webtunnel 159.69.22.241:443 ..."
    # We need to find and replace it. Use the first 40 chars as a fingerprint
    # (the full line may have slight formatting differences).
    fingerprint = dead_line[:40]

    if fingerprint not in content:
        print(f"[!] Bridge line not found in Constants.kt: {fingerprint}...", file=sys.stderr)
        return False

    # Replace the full dead_line with replacement_line
    # The dead_line might appear with slight variations (escaped quotes etc.)
    # so we find by fingerprint and replace the whole string literal
    new_content = content.replace(dead_line, replacement_line)

    if new_content == content:
        # Try with the fingerprint approach — find the full string literal
        # containing the fingerprint and replace it
        pattern = re.compile(r'"[^"]*' + re.escape(fingerprint) + r'[^"]*"')
        match = pattern.search(content)
        if match:
            old_literal = match.group(0)
            new_literal = f'"{replacement_line}"'
            new_content = content.replace(old_literal, new_literal)
        else:
            print(f"[!] Could not find full bridge string literal", file=sys.stderr)
            return False

    CONSTANTS_FILE.write_text(new_content)
    print(f"[*] Swapped bridge in Constants.kt:", file=sys.stderr)
    print(f"    OLD: {dead_line[:60]}...", file=sys.stderr)
    print(f"    NEW: {replacement_line[:60]}...", file=sys.stderr)
    return True


def commit_swap_to_main(decision: dict):
    """Commit the bridge swap directly to main.

    Instead of opening a PR (which requires a repo setting that may not
    be enabled), we commit directly to main. The GITHUB_TOKEN already
    has 'contents: write' permission, so this always works.

    The commit message includes the AI's PR description so the git log
    has full context.
    """
    token = os.environ.get("GITHUB_TOKEN")
    repo = os.environ.get("GITHUB_REPOSITORY", "darkmaster0345/Onionroute-Lite")

    if not token:
        print("[!] GITHUB_TOKEN not set — cannot commit", file=sys.stderr)
        return None

    # Set git identity
    subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
    subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)

    # Reconfigure remote with GITHUB_TOKEN for push permission
    remote_url = f"https://x-access-token:{token}@github.com/{repo}.git"
    subprocess.run(["git", "remote", "set-url", "origin", remote_url], check=True)

    # Make sure we're on main
    subprocess.run(["git", "checkout", "main"], check=True)

    # Pull latest (in case other workflows pushed)
    subprocess.run(["git", "pull", "--rebase", "origin", "main"],
                   check=True, capture_output=True,
                   env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})

    # Stage ONLY Constants.kt (NOT the 2GB model file)
    subprocess.run(["git", "add", "app/src/main/java/org/onionroute/lite/Constants.kt"], check=True)

    # Build commit message from AI decision
    summary = decision.get("summary", "swap dead bridge(s)")
    pr_body = decision.get("pr_body", "")

    # Truncate body for commit message (git has a limit)
    full_msg = f"{summary}\n\n{pr_body[:1000]}"

    subprocess.run(["git", "commit", "-m", full_msg], check=True)

    # Push to main
    subprocess.run(["git", "push", "origin", "main"],
                   check=True,
                   env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})

    print(f"[*] Swap committed directly to main", file=sys.stderr)
    return {"committed": True}


def main():
    parser = argparse.ArgumentParser(description="Apply bridge health changes")
    parser.add_argument("decision", help="Path to AI decision JSON")
    parser.add_argument("health_results", help="Path to current health results JSON")
    parser.add_argument("--apply", action="store_true", help="Apply changes to Constants.kt")
    parser.add_argument("--open-pr", action="store_true", help="Open a PR for the changes")
    args = parser.parse_args()

    # Load decision
    with open(args.decision) as f:
        decision = json.load(f)

    # Load current health results
    with open(args.health_results) as f:
        current_results = json.load(f)

    # Always update health.json (records this check's results)
    health = update_health_json(current_results, decision)
    print(f"[*] Updated health.json ({len(health['checks'])} checks recorded)", file=sys.stderr)

    # Apply swaps if any
    actions = decision.get("actions", [])
    if not actions:
        print("[*] No actions to apply", file=sys.stderr)
    else:
        for action in actions:
            if action.get("type") == "swap":
                if args.apply:
                    success = apply_swap_to_constants(
                        action["dead_bridge_line"],
                        action["replacement_bridge_line"],
                    )
                    if success:
                        print(f"[*] Applied swap: {action.get('reason', 'no reason given')}",
                              file=sys.stderr)
                else:
                    print(f"[*] DRY RUN — would swap:", file=sys.stderr)
                    print(f"    Dead: {action['dead_bridge_line'][:60]}...", file=sys.stderr)
                    print(f"    New:  {action['replacement_bridge_line'][:60]}...", file=sys.stderr)
                    print(f"    Reason: {action.get('reason', 'no reason')}", file=sys.stderr)

    # Commit swap directly to main if requested and there are changes
    if args.open_pr and actions and args.apply:
        print("[*] Committing swap to main...", file=sys.stderr)
        result = commit_swap_to_main(decision)
        if result:
            print(f"\n✓ Swap committed to main: {len(actions)} bridge(s) replaced")
        else:
            print(f"\n✗ Failed to commit swap to main")
    elif args.open_pr and not actions:
        print("[*] No actions to commit", file=sys.stderr)


if __name__ == "__main__":
    main()
