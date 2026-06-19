#!/usr/bin/env python3
"""
Bridge Health Tester
====================

Tests each bundled bridge in Constants.kt by running a real Tor
client with that bridge and measuring bootstrap time.

This is the NON-AI part — pure networking. The AI agent (agent.py)
calls this to get health data, then decides what to do.

Usage:
    python3 test_bridges.py [--timeout 60]

Output: JSON array of bridge health results to stdout.
"""
import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CONSTANTS_FILE = REPO_ROOT / "app" / "src" / "main" / "java" / "org" / "onionroute" / "lite" / "Constants.kt"

# Tor binary — installed by the workflow before calling this script.
# On GitHub Actions, we install tor via apt-get. On local dev, it's
# usually /usr/bin/tor.
TOR_BIN = os.environ.get("TOR_BIN", "tor")

# How long to wait for bootstrap before declaring failure.
DEFAULT_TIMEOUT = 60  # seconds


def parse_bridges_from_constants():
    """Extract bridge lines from Constants.kt.

    Looks for:
      - DEFAULT_OBFS4_BRIDGES = ["obfs4 ...", "obfs4 ..."]
      - DEFAULT_WEBTUNNEL_BRIDGES = ["webtunnel ...", ...]
      - Snowflake bridge (single line with snowflake transport)

    Returns a list of dicts: {transport, line, raw}
    """
    if not CONSTANTS_FILE.exists():
        print(f"ERROR: {CONSTANTS_FILE} not found", file=sys.stderr)
        return []

    content = CONSTANTS_FILE.read_text()
    bridges = []

    # Extract obfs4 bridges — Kotlin listOf("...", "...")
    # Also matches arrayOf() for backwards compatibility
    obfs4_match = re.search(
        r'DEFAULT_OBFS4_BRIDGES\s*=\s*(?:listOf|arrayOf)\((.*?)\)',
        content, re.DOTALL
    )
    if obfs4_match:
        for line in re.findall(r'"([^"]+)"', obfs4_match.group(1)):
            bridges.append({"transport": "obfs4", "line": line, "raw": line})

    # Extract webtunnel bridges
    wt_match = re.search(
        r'DEFAULT_WEBTUNNEL_BRIDGES\s*=\s*(?:listOf|arrayOf)\((.*?)\)',
        content, re.DOTALL
    )
    if wt_match:
        for line in re.findall(r'"([^"]+)"', wt_match.group(1)):
            bridges.append({"transport": "webtunnel", "line": line, "raw": line})

    # Extract snowflake bridge — it's embedded in the Bridge line in torrc
    # generation (TorProcessManager.kt), not directly in Constants. We
    # construct it from the snowflake constants.
    sf_broker = re.search(r'SNOWFLAKE_BROKER_URL\s*=\s*"([^"]+)"', content)
    sf_front = re.search(r'SNOWFLAKE_FRONT_DOMAIN\s*=\s*"([^"]+)"', content)
    sf_ice = re.search(r'SNOWFLAKE_ICE_SERVERS\s*=\s*"([^"]+)"', content)
    if sf_broker and sf_front and sf_ice:
        sf_line = (
            f"snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
            f"fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 "
            f"url={sf_broker.group(1)} fronts={sf_front.group(1)} "
            f"ice={sf_ice.group(1)}"
        )
        bridges.append({"transport": "snowflake", "line": sf_line, "raw": sf_line})

    return bridges


def test_single_bridge(bridge: dict, timeout: int, bridge_plugin_path: str = None) -> dict:
    """Test a single bridge by running Tor with it and waiting for bootstrap.

    Returns: {transport, line, status, bootstrap_time, error, timestamp}
    """
    transport = bridge["transport"]
    bridge_line = bridge["line"]
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    # Create a temp torrc for this bridge
    import tempfile
    tmpdir = tempfile.mkdtemp(prefix="tor-bridge-test-")
    data_dir = os.path.join(tmpdir, "data")
    os.makedirs(data_dir, exist_ok=True)
    torrc_path = os.path.join(tmpdir, "torrc")
    log_path = os.path.join(tmpdir, "tor.log")

    torrc_lines = [
        "SocksPort 0",
        "ControlPort auto",
        "CookieAuthentication 1",
        f"DataDirectory {data_dir}",
        f"Log notice file {log_path}",
        "UseBridges 1",
    ]

    # Add transport plugin if needed (obfs4 needs the binary; snowflake too)
    if transport in ("obfs4", "snowflake") and bridge_plugin_path:
        plugin_name = "obfs4proxy" if transport == "obfs4" else "snowflake-client"
        torrc_lines.append(f"ClientTransportPlugin {transport} exec {bridge_plugin_path}/{plugin_name}")

    torrc_lines.append(f"Bridge {bridge_line}")

    with open(torrc_path, "w") as f:
        f.write("\n".join(torrc_lines) + "\n")

    start_time = time.time()

    try:
        # Start Tor with --verify-config first
        verify = subprocess.run(
            [TOR_BIN, "-f", torrc_path, "--verify-config"],
            capture_output=True, text=True, timeout=10
        )
        if verify.returncode != 0:
            return {
                "transport": transport,
                "line": bridge_line,
                "status": "config_error",
                "bootstrap_time": 0,
                "error": f"Config verification failed: {verify.stderr[:200]}",
                "timestamp": timestamp,
            }

        # Start Tor daemon
        proc = subprocess.Popen(
            [TOR_BIN, "-f", torrc_path],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
        )

        # Monitor the log file for bootstrap progress
        bootstrap_progress = 0
        bootstrap_done = False
        error_msg = None

        deadline = start_time + timeout
        while time.time() < deadline:
            if proc.poll() is not None:
                # Tor exited
                error_msg = f"Tor exited with code {proc.returncode}"
                break

            # Read log file for bootstrap status
            if os.path.exists(log_path):
                with open(log_path, "r", errors="replace") as f:
                    log_content = f.read()
                    # Look for bootstrap progress
                    for m in re.finditer(r"Bootstrapped (\d+)%", log_content):
                        bootstrap_progress = int(m.group(1))
                    if "Bootstrapped 100%" in log_content:
                        bootstrap_done = True
                        break
                    # Check for common errors
                    if "Unable to connect" in log_content or "connection refused" in log_content:
                        error_msg = "connection refused"
                        break
                    if "Bridge line" in log_content and "unparseable" in log_content:
                        error_msg = "unparseable bridge line"
                        break

            time.sleep(1)

        elapsed = time.time() - start_time

        # Kill Tor
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            proc.kill()

        if bootstrap_done:
            return {
                "transport": transport,
                "line": bridge_line,
                "status": "alive",
                "bootstrap_time": round(elapsed, 1),
                "error": None,
                "timestamp": timestamp,
            }
        elif error_msg:
            return {
                "transport": transport,
                "line": bridge_line,
                "status": "dead",
                "bootstrap_time": 0,
                "error": error_msg,
                "timestamp": timestamp,
            }
        else:
            return {
                "transport": transport,
                "line": bridge_line,
                "status": "timeout",
                "bootstrap_time": 0,
                "error": f"stuck at {bootstrap_progress}% after {timeout}s",
                "timestamp": timestamp,
            }

    except subprocess.TimeoutExpired:
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "timeout",
            "bootstrap_time": 0,
            "error": f"Tor startup timed out after {timeout}s",
            "timestamp": timestamp,
        }
    except FileNotFoundError:
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "error",
            "bootstrap_time": 0,
            "error": "tor binary not found",
            "timestamp": timestamp,
        }
    finally:
        # Cleanup temp dir
        import shutil
        try:
            shutil.rmtree(tmpdir, ignore_errors=True)
        except Exception:
            pass


def main():
    parser = argparse.ArgumentParser(description="Test Tor bridge health")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help="Seconds to wait for bootstrap (default: 60)")
    parser.add_argument("--plugin-path", type=str, default=None,
                        help="Path to obfs4proxy/snowflake binaries")
    parser.add_argument("--output", type=str, default="-",
                        help="Output file (- for stdout)")
    args = parser.parse_args()

    print(f"[*] Parsing bridges from {CONSTANTS_FILE}", file=sys.stderr)
    bridges = parse_bridges_from_constants()
    print(f"[*] Found {len(bridges)} bridges to test", file=sys.stderr)

    results = []
    for i, bridge in enumerate(bridges):
        print(f"[*] Testing {i+1}/{len(bridges)}: {bridge['transport']} "
              f"({bridge['line'][:60]}...)", file=sys.stderr)
        result = test_single_bridge(bridge, args.timeout, args.plugin_path)
        print(f"    -> {result['status']} "
              f"({result['bootstrap_time']}s, {result.get('error', 'ok')})",
              file=sys.stderr)
        results.append(result)

    output = json.dumps(results, indent=2)
    if args.output == "-":
        print(output)
    else:
        with open(args.output, "w") as f:
            f.write(output)
        print(f"[*] Results written to {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
