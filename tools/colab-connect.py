#!/usr/bin/env python3
"""
OnionRoute Lite — Colab/Kaggle Emulator Connector
==================================================
Connects to a running Colab/Kaggle notebook via SSH tunnel,
downloads the latest APK from GitHub Actions, installs it,
launches it, captures logcat, and reports any issues found.

Usage:
  python3 tools/colab-connect.py --host <tunnel-host> [--port 2222] [--user root] [--pass onionroute]

  # Or just set ORL_HOST env var:
  ORL_HOST=abc-xyz.trycloudflare.com python3 tools/colab-connect.py

Workflow:
  1. SSH into the Colab VM
  2. Check emulator status
  3. Download latest APK from GitHub Actions CI
  4. Install APK on emulator
  5. Launch app
  6. Capture logcat for 60 seconds
  7. Analyze logcat for errors
  8. Report findings

Requirements:
  pip install paramiko requests
"""

import argparse
import io
import json
import os
import re
import sys
import time
import zipfile

import paramiko
import requests

REPO = "darkmaster0345/Onionroute-Lite"
PACKAGE = "org.onionroute.lite"
ACTIVITY = "org.onionroute.lite.MainActivity"


class ColabConnector:
    """Manages SSH connection to the Colab/Kaggle emulator VM."""

    def __init__(self, host, port=2222, username="root", password="onionroute"):
        self.host = host
        self.port = port
        self.username = username
        self.password = password
        self.ssh = paramiko.SSHClient()
        self.ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())

    def connect(self):
        """Establish SSH connection to the Colab VM."""
        print(f"[CONNECT] Connecting to {self.host}:{self.port}...")
        try:
            self.ssh.connect(
                hostname=self.host,
                port=self.port,
                username=self.username,
                password=self.password,
                timeout=30,
                look_for_keys=False,
                allow_agent=False,
            )
            print("[CONNECT] Connected!")
            return True
        except Exception as e:
            print(f"[CONNECT] Failed: {e}")
            return False

    def run(self, cmd, timeout=120):
        """Execute a command on the remote VM and return stdout/stderr."""
        print(f"[CMD] {cmd[:120]}{'...' if len(cmd) > 120 else ''}")
        try:
            stdin, stdout, stderr = self.ssh.exec_command(cmd, timeout=timeout)
            out = stdout.read().decode("utf-8", errors="replace")
            err = stderr.read().decode("utf-8", errors="replace")
            exit_code = stdout.channel.recv_exit_status()
            return out, err, exit_code
        except Exception as e:
            return "", str(e), -1

    def close(self):
        """Close SSH connection."""
        self.ssh.close()
        print("[CONNECT] Disconnected.")


def download_apk_locally(github_token=""):
    """Download the latest debug APK from GitHub Actions to local /tmp/."""
    headers = {"Authorization": f"token {github_token}"} if github_token else {}

    print("[APK] Finding latest successful CI run...")
    runs_resp = requests.get(
        f"https://api.github.com/repos/{REPO}/actions/runs?per_page=10",
        headers=headers,
    )
    runs_data = runs_resp.json()

    run_id = None
    for run_info in runs_data.get("workflow_runs", []):
        if run_info["conclusion"] == "success" and run_info["name"] == "Android Build":
            run_id = run_info["id"]
            print(f"[APK] Found CI run #{run_info['run_number']} (ID: {run_id})")
            break

    if not run_id:
        print("[APK] No successful CI run found!")
        return None

    artifacts_resp = requests.get(
        f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts",
        headers=headers,
    )
    artifacts_data = artifacts_resp.json()

    for art in artifacts_data.get("artifacts", []):
        if "fdroid-debug" in art["name"] and not art["expired"]:
            print(f"[APK] Downloading {art['name']} ({art['size_in_bytes']/1024/1024:.1f} MB)...")
            dl_resp = requests.get(
                f"https://api.github.com/repos/{REPO}/actions/artifacts/{art['id']}/zip",
                headers=headers,
            )
            with zipfile.ZipFile(io.BytesIO(dl_resp.content)) as zf:
                for name in zf.namelist():
                    if name.endswith(".apk"):
                        local_path = f"/tmp/{os.path.basename(name)}"
                        with open(local_path, "wb") as f:
                            f.write(zf.read(name))
                        print(f"[APK] Saved: {local_path} ({os.path.getsize(local_path)/1024/1024:.1f} MB)")
                        return local_path

    print("[APK] No debug APK artifact found!")
    return None


def upload_apk(conn, local_path):
    """Upload APK to the remote Colab VM via SFTP."""
    remote_path = f"/tmp/{os.path.basename(local_path)}"
    print(f"[UPLOAD] Uploading {local_path} -> {remote_path}...")
    try:
        sftp = conn.ssh.open_sftp()
        sftp.put(local_path, remote_path)
        sftp.close()
        print("[UPLOAD] Done!")
        return remote_path
    except Exception as e:
        print(f"[UPLOAD] Failed: {e}")
        return None


def analyze_logcat(logcat_output):
    """Analyze logcat output for known error patterns."""
    issues = []

    error_patterns = [
        (r"CRITICAL:\s+Native binary NOT FOUND", "MISSING_NATIVE_BINARY",
         "A native binary was not found in the APK. The build scripts may have failed."),
        (r"CRITICAL:\s+\S+ is only \d+ bytes", "STUB_BINARY",
         "A native binary is a placeholder stub (too small). Rebuild with ./scripts/build-all.sh"),
        (r"Exec format error", "WRONG_ARCH",
         "Binary is wrong architecture (e.g. ARM binary on x86_64 emulator). Check jniLibs/ contents."),
        (r"Tor process exited with code", "TOR_CRASH",
         "Tor daemon crashed. Check torrc and native binary compatibility."),
        (r"Failed to connect to Tor control port", "CONTROL_PORT_FAIL",
         "Control port connection failed. Tor may not have started or the port is not open."),
        (r"NetworkOnMainThreadException", "MAIN_THREAD_NETWORK",
         "Network I/O on main thread. Add Dispatchers.IO to the offending call."),
        (r"android\.os\.StrictMode", "STRICT_MODE_VIOLATION",
         "StrictMode violation. Move network/disk I/O off the main thread."),
        (r"FATAL EXCEPTION", "FATAL_EXCEPTION",
         "Uncaught exception crashed the app. Check the stack trace below."),
        (r"java\.lang\.SecurityException", "SECURITY_EXCEPTION",
         "Permission denied. Check manifest permissions and runtime permission requests."),
        (r"Tor config verification failed", "TORRC_INVALID",
         "Torrc configuration is invalid. Check generated torrc for syntax errors."),
        (r"not found in", "BINARY_NOT_IN_APK",
         "A native binary was not found in the extracted nativeLibraryDir."),
        (r"Permission Denial.*starting.*Service", "SERVICE_PERMISSION",
         "Cannot start service. Check foreground service type declaration and permissions."),
        (r"Fatal signal \d+", "NATIVE_CRASH",
         "Native code crashed (SIGSEGV, SIGABRT, etc.). Check binary compatibility."),
    ]

    for line in logcat_output.split("\n"):
        for pattern, code, description in error_patterns:
            if re.search(pattern, line, re.IGNORECASE):
                # Avoid duplicate issue codes
                if not any(i["code"] == code for i in issues):
                    issues.append({
                        "code": code,
                        "description": description,
                        "sample_line": line.strip()[:200],
                    })

    return issues


def run_test_loop(conn, apk_remote_path, logcat_duration=60):
    """Run the full test: install, launch, logcat, analyze."""
    print("\n" + "=" * 60)
    print("ONIONROUTE LITE — AUTOMATED TEST LOOP")
    print("=" * 60)

    # Step 1: Check emulator status
    print("\n[1/5] Checking emulator status...")
    out, err, code = conn.run("adb devices")
    print(out)
    if "device" not in out:
        print("[ERROR] No emulator attached! The emulator may not have booted.")
        return False

    out, err, code = conn.run("adb shell getprop sys.boot_completed")
    if out.strip() != "1":
        print("[ERROR] Emulator not booted yet!")
        return False
    print("  Emulator is online.")

    # Step 2: Install APK
    print(f"\n[2/5] Installing APK: {apk_remote_path}...")
    out, err, code = conn.run(f"adb install -r {apk_remote_path}")
    print(out)
    if "Success" not in out:
        print(f"[ERROR] APK install failed: {err}")
        return False
    print("  APK installed!")

    # Step 3: Inspect installed APK
    print("\n[3/5] Inspecting native binaries...")
    out, err, code = conn.run(
        'adb shell find /data/app/ -path "*onionroute*/lib/*" -ls 2>/dev/null',
        timeout=30,
    )
    if out.strip():
        print(out)
    else:
        print("  (Could not list native libs — need root access)")

    # Step 4: Launch app
    print(f"\n[4/5] Launching {PACKAGE}/{ACTIVITY}...")
    out, err, code = conn.run(f"adb shell am start -n {PACKAGE}/{ACTIVITY}")
    print(out)
    if "Error" in out:
        print(f"[ERROR] App launch failed: {out}")
        return False

    # Give the app a few seconds to start the Tor service
    print("  Waiting 5s for service to initialize...")
    time.sleep(5)

    # Step 5: Capture logcat
    print(f"\n[5/5] Capturing logcat for {logcat_duration}s...")
    # Clear logcat first
    conn.run("adb logcat -c")

    logcat_cmd = (
        f'timeout {logcat_duration} adb logcat -v time '
        f'-s "TorService:*" "TorProcessManager:*" "TorControlConnection:*" '
        f'"BandwidthTracker:*" "BridgeConfig:*" "KillSwitch:*" '
        f'"AndroidRuntime:E" "System.err:W" "onionroute:*" 2>/dev/null || true'
    )
    out, err, code = conn.run(logcat_cmd, timeout=logcat_duration + 30)

    print("\n" + "-" * 60)
    print("LOGCAT OUTPUT:")
    print("-" * 60)
    print(out[:5000])
    if len(out) > 5000:
        print(f"\n... ({len(out) - 5000} more bytes)")

    # Save full logcat locally
    logcat_file = "/home/z/my-project/download/logcat-colab.txt"
    with open(logcat_file, "w") as f:
        f.write(out)
    print(f"\nFull logcat saved to: {logcat_file}")

    # Analyze
    print("\n" + "=" * 60)
    print("ANALYSIS:")
    print("=" * 60)
    issues = analyze_logcat(out)
    if issues:
        print(f"\nFound {len(issues)} issue(s):\n")
        for i, issue in enumerate(issues, 1):
            print(f"  {i}. [{issue['code']}] {issue['description']}")
            print(f"     Line: {issue['sample_line']}")
            print()
    else:
        print("\nNo critical issues detected in logcat!")
        # Check for successful connection indicators
        if "Tor started successfully" in out or "CONNECTED" in out:
            print("  Tor appears to have started successfully!")
        elif "CONNECTING" in out:
            print("  Tor is connecting but may not have completed in time.")
            print("  Try increasing --logcat-duration.")
        else:
            print("  No Tor status indicators found. The app may not have been launched.")

    return issues


def main():
    parser = argparse.ArgumentParser(
        description="OnionRoute Lite — Colab/Kaggle Emulator Connector"
    )
    parser.add_argument(
        "--host",
        default=os.environ.get("ORL_HOST", ""),
        help="SSH tunnel hostname (e.g. abc-xyz.trycloudflare.com)",
    )
    parser.add_argument("--port", type=int, default=2222, help="SSH port (default: 2222)")
    parser.add_argument("--user", default="root", help="SSH username (default: root)")
    parser.add_argument(
        "--password", default="onionroute", help="SSH password (default: onionroute)"
    )
    parser.add_argument(
        "--github-token",
        default=os.environ.get("GITHUB_TOKEN", ""),
        help="GitHub PAT for downloading artifacts (needed for private repos)",
    )
    parser.add_argument(
        "--apk",
        default="",
        help="Local APK file path (skips download from GitHub Actions)",
    )
    parser.add_argument(
        "--logcat-duration",
        type=int,
        default=60,
        help="How many seconds to capture logcat (default: 60)",
    )
    parser.add_argument(
        "--loop",
        action="store_true",
        help="Keep running: fix, push, rebuild, retest in a loop",
    )

    args = parser.parse_args()

    if not args.host:
        print("ERROR: --host is required (or set ORL_HOST env var)")
        print("  Get the host from the Colab notebook output after running all cells.")
        print("  Example: python3 tools/colab-connect.py --host abc-xyz.trycloudflare.com")
        sys.exit(1)

    # Connect
    conn = ColabConnector(args.host, args.port, args.user, args.password)
    if not conn.connect():
        sys.exit(1)

    try:
        # Get APK
        if args.apk:
            local_apk = args.apk
        else:
            local_apk = download_apk_locally(args.github_token)
            if not local_apk:
                print("[ERROR] Could not download APK from GitHub Actions.")
                print("  Try: --apk /path/to/local.apk")
                sys.exit(1)

        # Upload APK to Colab VM
        remote_apk = upload_apk(conn, local_apk)
        if not remote_apk:
            sys.exit(1)

        # Run test
        issues = run_test_loop(conn, remote_apk, args.logcat_duration)

        # Summary
        if issues:
            print("\n" + "=" * 60)
            print("NEXT STEPS:")
            print("=" * 60)
            for issue in issues:
                if issue["code"] == "MISSING_NATIVE_BINARY":
                    print("  - Check that CI built native binaries (scripts/build-all.sh)")
                    print("  - Ensure android:extractNativeLibs=true in manifest")
                elif issue["code"] == "STUB_BINARY":
                    print("  - Rebuild native binaries with ./scripts/build-all.sh")
                    print("  - Ensure jniLibs/ contains real compiled .so files")
                elif issue["code"] == "WRONG_ARCH":
                    print("  - Ensure x86_64 native binaries are in jniLibs/x86_64/")
                    print("  - CI builds both armeabi-v7a and x86_64 for emulator testing")
                elif issue["code"] == "TOR_CRASH":
                    print("  - Check torrc for syntax errors")
                    print("  - Verify Tor binary is compatible with Android's libc")
                elif issue["code"] == "CONTROL_PORT_FAIL":
                    print("  - Tor may not have started; check for TOR_CRASH issues")
                    print("  - Increase control port retry count or delay")
                elif issue["code"] == "MAIN_THREAD_NETWORK":
                    print("  - Move network I/O to Dispatchers.IO coroutine")
                elif issue["code"] == "FATAL_EXCEPTION":
                    print("  - Check stack trace in logcat for the root cause")
                elif issue["code"] == "SERVICE_PERMISSION":
                    print("  - Check foreground service type in manifest")
                    print("  - Ensure POST_NOTIFICATIONS permission on API 33+")

    finally:
        conn.close()


if __name__ == "__main__":
    main()
