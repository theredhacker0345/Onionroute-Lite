#!/usr/bin/env python3
"""
Bridge Health Tester
====================

Tests each bundled bridge in Constants.kt.

For obfs4 and webtunnel bridges: TCP connectivity check to the
bridge's IP:port. This tells us if the bridge server is online
(accepting TCP connections) → "alive", or offline → "dead".

For snowflake bridges: the bridge line uses a virtual placeholder
address (192.0.2.3:80, RFC 5737 TEST-NET-1). Snowflake doesn't
connect directly to a bridge — it uses WebRTC through a broker, so
TCP-testing the placeholder IP is meaningless. Instead, we test the
snowflake broker URL (HTTPS GET) and STUN servers (TCP connect).
If the broker is reachable, the snowflake transport is functional.

Usage:
    python3 test_bridges.py [--timeout 5] [--output results.json]
"""
import argparse
import json
import os
import re
import socket
import struct
import sys
import time
import urllib.request
import urllib.error
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
CONSTANTS_FILE = REPO_ROOT / "app" / "src" / "main" / "java" / "org" / "onionroute" / "lite" / "Constants.kt"

# How long to wait for TCP connect before declaring failure.
DEFAULT_TIMEOUT = 5  # seconds per bridge


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
    obfs4_match = re.search(
        r'DEFAULT_OBFS4_BRIDGES\s*=\s*(?:listOf|arrayOf)\((.*?)\)',
        content, re.DOTALL
    )
    if obfs4_match:
        for line in re.findall(r'"([^"]+)"', obfs4_match.group(1)):
            # Derive the transport from the line rather than trusting the
            # variable name; see the DEFAULT_WEBTUNNEL_BRIDGES note below.
            transport = line.split(" ")[0].strip().lower()
            bridges.append({"transport": transport, "line": line, "raw": line})

    # Extract bridges from DEFAULT_WEBTUNNEL_BRIDGES.
    #
    # The transport is derived from each line's FIRST TOKEN, not hardcoded to
    # "webtunnel" as it was. That list is not homogeneous: it also carries an
    # obfs4 fallback (67.205.142.190:9889) for when the webtunnel bridges are
    # unreachable. Labelling that line "webtunnel" made health.json - and the
    # README table rendered from it - report an obfs4 bridge as a webtunnel
    # one.
    #
    # That is not cosmetic. TorProcessManager picks the proxy binary by parsing
    # the transport off the line itself, so the app connected it correctly; but
    # the health tooling tested obfs4 connectivity while labelling it webtunnel,
    # so the results table attributed obfs4 outcomes to the wrong transport and
    # could retire a working obfs4 bridge - or keep a dead webtunnel one -
    # based on the other transport's results.
    #
    # Deriving the token also means a future mixed list is handled correctly
    # without touching this code.
    wt_match = re.search(
        r'DEFAULT_WEBTUNNEL_BRIDGES\s*=\s*(?:listOf|arrayOf)\((.*?)\)',
        content, re.DOTALL
    )
    if wt_match:
        for line in re.findall(r'"([^"]+)"', wt_match.group(1)):
            transport = line.split(" ")[0].strip().lower()
            bridges.append({"transport": transport, "line": line, "raw": line})

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


def is_documentation_address(host: str) -> bool:
    """Check if an IP address is a documentation/test address that should
    never be TCP-tested.

    Per RFC 5737: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24 (TEST-NET-1/2/3)
    Per RFC 3849: 2001:db8::/32 (IPv6 documentation)
    Per RFC 1918: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16 (private — but
      these MIGHT be valid for bridges on private networks, so we DON'T
      filter them; only filter documentation addresses)

    Snowflake's bridge line uses 192.0.2.3:80 (TEST-NET-1) as a virtual
    placeholder. The snowflake client doesn't connect there — it uses
    WebRTC through a broker. TCP-testing 192.0.2.3 always times out
    because that address is reserved and not routable on the internet.
    """
    # IPv4 documentation ranges
    try:
        import ipaddress
        ip = ipaddress.ip_address(host)
        if ip.is_loopback or ip.is_unspecified or ip.is_link_local:
            return True
        # ipaddress.is_reserved covers 240/4, 255.255.255.255 etc.
        if ip.is_reserved:
            return True
        # IPv4 documentation (TEST-NET-1/2/3)
        if ip.version == 4:
            for net in ["192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"]:
                if ip in ipaddress.ip_network(net):
                    return True
        # IPv6 documentation
        if ip.version == 6:
            if ip in ipaddress.ip_network("2001:db8::/32"):
                return True
    except ValueError:
        # Not an IP address (maybe a hostname) — allow it
        return False
    return False


def test_snowflake_bridge(bridge_line: str) -> dict:
    """Test a snowflake bridge by checking its broker + STUN servers.

    Snowflake bridge lines look like:
      snowflake 192.0.2.3:80 <fingerprint> url=https://snowflake-broker.torproject.net/ fronts=... ice=stun:stun.example.com:3478,...

    The 192.0.2.3:80 is a VIRTUAL placeholder (RFC 5737 TEST-NET-1).
    Snowflake doesn't connect there — it uses WebRTC through the broker.
    So we test:
      1. HTTPS HEAD to the broker URL (url=...) — broker must be reachable
      2. STUN Binding Request (UDP) to each STUN server (ice=...) — at
         least one must respond

    If the broker responds (even with 4xx) and at least one STUN server
    returns a STUN Binding Success Response, the snowflake transport is
    functional.

    NOTE: STUN uses UDP, not TCP. The previous version of this test used
    TCP connect which always failed because STUN servers don't accept
    TCP connections on port 3478 — they only accept UDP. This made
    snowflake look permanently dead even though it was working fine.
    """
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    start_time = time.time()

    # Extract broker URL
    broker_match = re.search(r'url=(\S+)', bridge_line)
    if not broker_match:
        return {
            "transport": "snowflake",
            "line": bridge_line,
            "status": "error",
            "bootstrap_time": 0,
            "error": "no url= in snowflake bridge line",
            "timestamp": timestamp,
        }
    broker_url = broker_match.group(1)

    # Extract STUN servers (comma-separated stun:host:port)
    stun_match = re.search(r'ice=(\S+)', bridge_line)
    stun_servers = []
    if stun_match:
        for entry in stun_match.group(1).split(","):
            # Format: stun:host:port
            m = re.match(r'stun:(.+):(\d+)', entry.strip())
            if m:
                stun_servers.append((m.group(1), int(m.group(2))))

    # Test broker URL (HTTPS HEAD, accept any HTTP status — we just want
    # to know if the broker is reachable)
    broker_ok = False
    broker_error = None
    try:
        req = urllib.request.Request(broker_url, method="HEAD",
                                      headers={"User-Agent": "OnionRoute-Lite/1.0"})
        urllib.request.urlopen(req, timeout=10)
        broker_ok = True
    except urllib.error.HTTPError:
        # HTTP error (4xx, 5xx) still means the broker is reachable —
        # it's responding, just not with 200. That's fine for our purposes.
        broker_ok = True
    except Exception as e:
        broker_error = str(e)

    # Test STUN servers via UDP (STUN Binding Request, RFC 5389)
    # STUN is a UDP protocol — TCP connect always fails because STUN
    # servers don't accept TCP on port 3478.
    stun_ok = 0
    stun_errors = []
    # STUN Binding Request: type=0x0001, length=0, magic=0x2112A442, 12-byte TX ID
    stun_request = struct.pack('!HHI12s', 0x0001, 0, 0x2112A442,
                                os.urandom(12))
    for host, port in stun_servers:
        try:
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(3)
            sock.sendto(stun_request, (host, port))
            data, addr = sock.recvfrom(1024)
            sock.close()
            # Check if response is a STUN Binding Success Response (0x0101)
            if len(data) >= 2:
                msg_type = struct.unpack('!H', data[:2])[0]
                if msg_type == 0x0101:  # Binding Success Response
                    stun_ok += 1
                else:
                    stun_errors.append(f"{host}:{port} (STUN msg type 0x{msg_type:04x})")
            else:
                stun_errors.append(f"{host}:{port} (short response)")
        except socket.timeout:
            stun_errors.append(f"{host}:{port} (UDP timeout)")
        except Exception as e:
            stun_errors.append(f"{host}:{port} ({e})")

    elapsed = time.time() - start_time

    # Snowflake is "alive" if broker is reachable AND at least one STUN
    # server responded with a valid STUN Binding Success Response.
    # If no STUN servers are configured, just the broker being reachable
    # is enough (Tor can fall back to default STUN).
    if broker_ok and (stun_ok > 0 or not stun_servers):
        return {
            "transport": "snowflake",
            "line": bridge_line,
            "status": "alive",
            "bootstrap_time": round(elapsed, 2),
            "error": None,
            "timestamp": timestamp,
        }
    else:
        error_parts = []
        if not broker_ok:
            error_parts.append(f"broker unreachable: {broker_error}")
        if stun_servers and stun_ok == 0:
            error_parts.append(f"all {len(stun_servers)} STUN servers unreachable (UDP): "
                               f"{'; '.join(stun_errors[:2])}")
        return {
            "transport": "snowflake",
            "line": bridge_line,
            "status": "dead",
            "bootstrap_time": 0,
            "error": "; ".join(error_parts),
            "timestamp": timestamp,
        }


def test_tcp_bridge(bridge: dict, timeout: int) -> dict:
    """Test an obfs4 or webtunnel bridge via TCP connectivity check."""
    transport = bridge["transport"]
    bridge_line = bridge["line"]
    timestamp = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

    # Extract IP:port from bridge line
    # Format: "obfs4 192.168.1.1:443 FINGERPRINT cert=..."
    #         "webtunnel 192.168.1.1:443 FINGERPRINT url=..."
    #         "webtunnel [2001:db8::1]:443 FINGERPRINT url=..."
    ip_match = re.search(r'(\d+\.\d+\.\d+\.\d+):(\d+)', bridge_line)
    use_ipv6 = False
    if not ip_match:
        # Try IPv6 format: [2001:db8::1]:443
        ip_match = re.search(r'\[([0-9a-fA-F:]+)\]:(\d+)', bridge_line)
        if ip_match:
            use_ipv6 = True
            host = ip_match.group(1)
            port = int(ip_match.group(2))
        else:
            return {
                "transport": transport,
                "line": bridge_line,
                "status": "error",
                "bootstrap_time": 0,
                "error": "could not parse IP:port from bridge line",
                "timestamp": timestamp,
            }
    else:
        host = ip_match.group(1)
        port = int(ip_match.group(2))

    # Reject documentation/test addresses — these are never real bridges.
    # (BridgeDB sometimes returns 2001:db8::/32 IPv6 placeholders.)
    if is_documentation_address(host):
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "dead",
            "bootstrap_time": 0,
            "error": f"bridge uses documentation/test address {host} (RFC 3849/5737) — not a real server",
            "timestamp": timestamp,
        }

    # TCP connectivity check
    start_time = time.time()
    family = socket.AF_INET6 if use_ipv6 else socket.AF_INET
    sock = socket.socket(family, socket.SOCK_STREAM)
    sock.settimeout(timeout)
    try:
        sock.connect((host, port))
        sock.close()
        elapsed = time.time() - start_time
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "alive",
            "bootstrap_time": round(elapsed, 2),
            "error": None,
            "timestamp": timestamp,
        }
    except socket.timeout:
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "timeout",
            "bootstrap_time": 0,
            "error": f"TCP connect to {host}:{port} timed out ({timeout}s)",
            "timestamp": timestamp,
        }
    except ConnectionRefusedError:
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "dead",
            "bootstrap_time": 0,
            "error": f"connection refused by {host}:{port}",
            "timestamp": timestamp,
        }
    except Exception as e:
        return {
            "transport": transport,
            "line": bridge_line,
            "status": "dead",
            "bootstrap_time": 0,
            "error": f"TCP connect failed: {e}",
            "timestamp": timestamp,
        }
    finally:
        try:
            sock.close()
        except:
            pass


def test_single_bridge(bridge: dict, timeout: int) -> dict:
    """Test a single bridge. Dispatches to the right tester based on transport.

    - snowflake: test broker URL + STUN servers (the bridge line's IP is a
      virtual placeholder that can't be TCP-tested)
    - obfs4 / webtunnel / other: TCP connectivity check to IP:port
    """
    transport = bridge["transport"]
    bridge_line = bridge["line"]

    if transport == "snowflake":
        return test_snowflake_bridge(bridge_line)
    else:
        return test_tcp_bridge(bridge, timeout)


def main():
    parser = argparse.ArgumentParser(description="Test Tor bridge health")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT,
                        help="Seconds to wait for TCP connect (default: 5)")
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
        result = test_single_bridge(bridge, args.timeout)
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
