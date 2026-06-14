#!/usr/bin/env python3
"""
OnionRoute Lite — Kaggle Kernel: Android Emulator + SSH Bridge
===============================================================
Upload this as a Kaggle Notebook (Settings > Accelerator = GPU).

It will:
  1. Install Android SDK + emulator
  2. Start headless x86_64 emulator
  3. Download latest debug APK from GitHub Actions CI
  4. Install APK on emulator
  5. Launch the app
  6. Expose SSH via cloudflared tunnel
  7. Print connection info

After running, copy the SSH host from the output and use:
  python3 tools/colab-connect.py --host <host>
"""

import os
import subprocess
import time
import threading
import re
import requests
import zipfile
import io
from pathlib import Path

# ══════════════════════════════════════════════════════════════
# CONFIG
# ══════════════════════════════════════════════════════════════
GITHUB_TOKEN = ""  # Optional: for private repos
REPO = "darkmaster0345/Onionroute-Lite"
AVD_NAME = "onionroute_lite"
ANDROID_SDK_ROOT = os.path.expanduser("~/android-sdk")
SSH_PORT = 2222
SSH_USER = "root"
SSH_PASS = "onionroute"

os.environ["ANDROID_SDK_ROOT"] = ANDROID_SDK_ROOT
os.environ["ANDROID_AVD_HOME"] = os.path.expanduser("~/.android/avd")
os.environ["PATH"] = (
    f"{ANDROID_SDK_ROOT}/cmdline-tools/latest/bin:"
    f"{ANDROID_SDK_ROOT}/emulator:"
    f"{ANDROID_SDK_ROOT}/platform-tools:"
    f"{os.environ['PATH']}"
)


def run(cmd, check=True):
    print(f"$ {cmd[:120]}{'...' if len(cmd) > 120 else ''}")
    r = subprocess.run(cmd, shell=True, capture_output=True, text=True)
    if check and r.returncode != 0:
        print(f"FAILED: {r.stderr[:500]}")
        raise RuntimeError(f"Command failed: {cmd}")
    return r


# ══════════════════════════════════════════════════════════════
# STEP 1: Install System Dependencies + SSH + Android SDK
# ══════════════════════════════════════════════════════════════
print("[1/5] Installing system dependencies...")
run("apt-get update -qq 2>/dev/null", check=False)
run("apt-get install -y -qq qemu-kvm openssh-server curl wget unzip > /dev/null 2>&1", check=False)

# SSH server
print("[1/5] Configuring SSH server...")
run("mkdir -p /run/sshd")
with open("/etc/ssh/sshd_config", "a") as f:
    f.write(f"\nPort {SSH_PORT}\n")
    f.write("PermitRootLogin yes\n")
    f.write("PasswordAuthentication yes\n")
    f.write("PermitEmptyPasswords yes\n")
run(f'echo "{SSH_USER}:{SSH_PASS}" | chpasswd')
run("ssh-keygen -A")
run("/usr/sbin/sshd")

# Android SDK
print("[1/5] Installing Android SDK...")
if not Path(f"{ANDROID_SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager").exists():
    run(f"mkdir -p {ANDROID_SDK_ROOT}/cmdline-tools")
    run(f"cd {ANDROID_SDK_ROOT}/cmdline-tools && curl -sL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdline-tools.zip")
    run(f"cd {ANDROID_SDK_ROOT}/cmdline-tools && unzip -qo cmdline-tools.zip && rm -rf latest && mv cmdline-tools latest && rm cmdline-tools.zip")

run("yes | sdkmanager --licenses > /dev/null 2>&1", check=False)
print("[1/5] Installing emulator + system image (~3 min)...")
run('sdkmanager --install "platform-tools" "emulator" "platforms;android-34" "system-images;android-34;default;x86_64" > /dev/null 2>&1')

# ══════════════════════════════════════════════════════════════
# STEP 2: Create AVD + Start Emulator
# ══════════════════════════════════════════════════════════════
print("[2/5] Creating AVD...")
AVD_DIR = os.path.expanduser(f"~/.android/avd/{AVD_NAME}.avd")
run(f'echo no | avdmanager create avd -n {AVD_NAME} -k "system-images;android-34;default;x86_64" --force 2>&1', check=False)

ini_path = os.path.expanduser(f"~/.android/avd/{AVD_NAME}.ini")
with open(ini_path, "w") as f:
    f.write(f"path={AVD_DIR}\n")
    f.write(f"path.rel=avd/{AVD_NAME}.avd\n")

with open(f"{AVD_DIR}/config.ini", "a") as f:
    f.write("\nhw.ramSize=2048\n")
    f.write("vm.heapSize=256\n")
    f.write("hw.camera.back=none\n")
    f.write("hw.camera.front=none\n")
    f.write("hw.audioInput=no\n")
    f.write("hw.audioOutput=no\n")

run("pkill -9 emulator 2>/dev/null; pkill -9 qemu 2>/dev/null", check=False)
time.sleep(2)

has_kvm = os.path.exists("/dev/kvm")
gpu_mode = "host" if has_kvm else "swiftshader_indirect"
accel = "-accel on" if has_kvm else ""

print("[2/5] Starting emulator...")
os.system(
    f"emulator @{AVD_NAME} -no-window -no-audio -no-snapshot "
    f"-no-boot-anim -no-metrics -gpu {gpu_mode} -memory 2048 {accel} "
    f"2>/tmp/emulator.log &"
)

print("[2/5] Waiting for boot (1-3 min)...")
for i in range(80):
    time.sleep(3)
    r = subprocess.run("adb shell getprop sys.boot_completed 2>/dev/null", shell=True, capture_output=True, text=True)
    if r.stdout.strip() == "1":
        print(f"  Booted in {i*3}s")
        break
    if i % 15 == 0 and i > 0:
        print(f"  ...still booting ({i*3}s)")
else:
    print("  WARNING: Boot timeout")

# ══════════════════════════════════════════════════════════════
# STEP 3: Download & Install APK
# ══════════════════════════════════════════════════════════════
print("[3/5] Downloading latest debug APK from CI...")
headers = {"Authorization": f"token {GITHUB_TOKEN}"} if GITHUB_TOKEN else {}
runs = requests.get(f"https://api.github.com/repos/{REPO}/actions/runs?per_page=10", headers=headers).json()

run_id = None
for r in runs.get("workflow_runs", []):
    if r["conclusion"] == "success" and r["name"] == "Android Build":
        run_id = r["id"]
        print(f'  Found CI run #{r["run_number"]}')
        break

if run_id:
    arts = requests.get(f"https://api.github.com/repos/{REPO}/actions/runs/{run_id}/artifacts", headers=headers).json()
    for art in arts.get("artifacts", []):
        if "fdroid-debug" in art["name"] and not art["expired"]:
            resp = requests.get(f"https://api.github.com/repos/{REPO}/actions/artifacts/{art['id']}/zip", headers=headers)
            with zipfile.ZipFile(io.BytesIO(resp.content)) as zf:
                for name in zf.namelist():
                    if name.endswith(".apk"):
                        zf.extract(name, "/tmp/")
                        apk_path = f"/tmp/{name}"
                        print(f"  APK: {apk_path} ({os.path.getsize(apk_path)/1024/1024:.1f} MB)")
                        break
            break

    print("[3/5] Installing APK...")
    r = subprocess.run(f"adb install -r {apk_path}", shell=True, capture_output=True, text=True)
    print(r.stdout.strip())
else:
    print("  No successful CI run found")

# ══════════════════════════════════════════════════════════════
# STEP 4: Launch App + Capture Initial Logcat
# ══════════════════════════════════════════════════════════════
print("[4/5] Launching OnionRoute Lite...")
subprocess.run("adb shell am start -n org.onionroute.lite/org.onionroute.lite.MainActivity", shell=True)
time.sleep(5)

print("[4/5] Capturing logcat (30s)...")
subprocess.run("adb logcat -c", shell=True)
r = subprocess.run(
    'timeout 30 adb logcat -v time -s "TorService:*" "TorProcessManager:*" '
    '"TorControlConnection:*" "AndroidRuntime:E" 2>/dev/null || true',
    shell=True, capture_output=True, text=True
)
print(r.stdout[:3000])

# ══════════════════════════════════════════════════════════════
# STEP 5: Expose SSH via Cloudflare Tunnel
# ══════════════════════════════════════════════════════════════
print("[5/5] Starting SSH tunnel...")
os.system("curl -sL https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -o /usr/local/bin/cloudflared && chmod +x /usr/local/bin/cloudflared")


def run_tunnel():
    os.system("cloudflared tunnel --url ssh://localhost:2222 > /tmp/cloudflared.log 2>&1")


t = threading.Thread(target=run_tunnel, daemon=True)
t.start()
time.sleep(10)

tunnel_url = None
try:
    with open("/tmp/cloudflared.log", "r") as f:
        for line in f:
            m = re.search(r"https?://([a-z0-9-]+\.trycloudflare\.com)", line)
            if m:
                tunnel_url = m.group(1)
except:
    pass

print()
print("=" * 60)
print("ONIONROUTE LITE - EMULATOR READY")
print("=" * 60)
if tunnel_url:
    print(f"\nSSH Host: {tunnel_url}")
    print(f"SSH Port: {SSH_PORT}")
    print(f"User: {SSH_USER} / Pass: {SSH_PASS}")
    print(f"\nConnect:")
    print(f"  ssh -o StrictHostKeyChecking=no -p {SSH_PORT} {SSH_USER}@{tunnel_url}")
    print(f"\nOr use the test script:")
    print(f"  python3 tools/colab-connect.py --host {tunnel_url}")
else:
    print("\nTunnel URL not found. Check /tmp/cloudflared.log")
print("=" * 60)
