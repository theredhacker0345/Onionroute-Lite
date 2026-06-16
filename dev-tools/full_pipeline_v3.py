#!/usr/bin/env python3
"""Full autonomous pipeline using exec_command for proper command execution."""

import subprocess, time, select, paramiko, socket, sys

GH_PATH = "/home/z/local/bin/gh"
AUTO_KEY = "/home/z/.ssh/codespaces.auto"
CODESPACE = "fluffy-eureka-7v5j4j6q7gq2r5wg"

class DirectSock:
    def __init__(self, proc):
        self.proc = proc
        self._timeout = 60
        self._closed = False
    def settimeout(self, t):
        self._timeout = t
    def send(self, data):
        self.proc.stdin.write(data)
        self.proc.stdin.flush()
        return len(data)
    def recv(self, n):
        r, _, _ = select.select([self.proc.stdout], [], [], self._timeout)
        if r:
            data = self.proc.stdout.read(n)
            if not data:
                raise socket.error("empty")
            return data
        raise socket.timeout()
    def close(self):
        self._closed = True


class CodespaceSSH:
    def __init__(self):
        self.proc = None
        self.transport = None

    def connect(self):
        self.proc = subprocess.Popen(
            [GH_PATH, 'codespace', 'ssh', '-c', CODESPACE, '--stdio', '--', '-i', AUTO_KEY],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )
        time.sleep(3)
        sock = DirectSock(self.proc)
        self.transport = paramiko.Transport(sock)
        self.transport.use_compression(True)
        self.transport.start_client()
        key = paramiko.Ed25519Key.from_private_key_file(AUTO_KEY)
        self.transport.auth_publickey('codespace', key)
        print("Connected!")

    def run(self, cmd, timeout=120):
        """Run command using exec_command (non-interactive) for proper execution."""
        chan = self.transport.open_session()
        chan.settimeout(timeout)
        
        # Set up environment
        env_cmd = f'export ANDROID_HOME=$HOME/android-sdk; export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH; {cmd}'
        chan.exec_command(env_cmd)
        
        stdout = b''
        stderr = b''
        
        start = time.time()
        while time.time() - start < timeout:
            if chan.recv_ready():
                data = chan.recv(4096)
                stdout += data
            if chan.recv_stderr_ready():
                data = chan.recv_stderr(4096)
                stderr += data
            if chan.exit_status_ready():
                # Drain remaining
                while chan.recv_ready():
                    stdout += chan.recv(4096)
                while chan.recv_stderr_ready():
                    stderr += chan.recv_stderr(4096)
                break
            time.sleep(0.05)
        
        exit_code = chan.recv_exit_status()
        chan.close()
        
        out = stdout.decode('utf-8', errors='replace').strip()
        err = stderr.decode('utf-8', errors='replace').strip()
        
        if err and not out:
            return f"[exit:{exit_code}] {err}"
        return out if out else f"[exit:{exit_code}]"

    def close(self):
        try:
            if self.transport: self.transport.close()
            if self.proc: self.proc.terminate()
        except: pass


def main():
    cs = CodespaceSSH()
    cs.connect()

    # Step 1: Environment check
    print("=" * 60)
    print("STEP 1: Environment Check")
    print("=" * 60)
    print(cs.run("uname -a && df -h / | tail -1 && free -h | head -2", timeout=15))

    # Step 2: Check SDK
    print("\n" + "=" * 60)
    print("STEP 2: Android SDK Status")
    print("=" * 60)
    print(cs.run("ls ~/android-sdk/ 2>/dev/null | head -10", timeout=10))

    # Step 3: Install missing SDK components
    print("\n" + "=" * 60)
    print("STEP 3: Install SDK Components")
    print("=" * 60)
    print(cs.run("""
        if [ ! -d ~/android-sdk/platform-tools ]; then
            echo "Installing platform-tools..."; yes | sdkmanager --licenses > /dev/null 2>&1; sdkmanager "platform-tools" 2>&1 | tail -3
        else
            echo "platform-tools OK"
        fi
    """, timeout=300))

    print(cs.run("""
        if [ ! -d ~/android-sdk/emulator ]; then
            echo "Installing emulator..."; sdkmanager "emulator" 2>&1 | tail -3
        else
            echo "emulator OK"
        fi
    """, timeout=300))

    print(cs.run("""
        if [ -z "$(ls ~/android-sdk/system-images/ 2>/dev/null)" ]; then
            echo "Installing system image..."; sdkmanager "system-images;android-34;default;x86_64" 2>&1 | tail -3
        else
            echo "system image OK"
        fi
    """, timeout=600))

    # Step 4: Create AVD
    print("\n" + "=" * 60)
    print("STEP 4: Create AVD")
    print("=" * 60)
    print(cs.run("""
        if [ -z "$(ls ~/.android/avd/*.ini 2>/dev/null)" ]; then
            echo "Creating AVD..."; echo "no" | avdmanager create avd -n test -k "system-images;android-34;default;x86_64" --force 2>&1 | tail -5
        else
            echo "AVD already exists"
        fi
    """, timeout=60))

    # Step 5: Install libpulse
    print("\n" + "=" * 60)
    print("STEP 5: Install libpulse")
    print("=" * 60)
    print(cs.run("sudo apt-get install -y libpulse0 2>&1 | tail -3", timeout=60))

    # Step 6: Kill old emulators and start fresh
    print("\n" + "=" * 60)
    print("STEP 6: Start Emulator")
    print("=" * 60)
    print(cs.run("pkill -9 emulator 2>/dev/null; pkill -9 qemu 2>/dev/null; sleep 1; echo killed old", timeout=10))
    
    print(cs.run("nohup emulator -avd test -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -memory 2048 -accel on > /tmp/emulator.log 2>&1 & echo PID=$!", timeout=15))

    # Step 7: Wait for emulator
    print("\n" + "=" * 60)
    print("STEP 7: Wait for Emulator Boot")
    print("=" * 60)
    result = cs.run("""
        for i in $(seq 1 60); do
            if adb devices 2>/dev/null | grep -q "emulator"; then
                adb wait-for-device 2>/dev/null
                BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r\\n')
                if [ "$BOOT" = "1" ]; then
                    echo "EMULATOR_READY"
                    adb devices
                    exit 0
                fi
            fi
            if [ $((i % 10)) -eq 0 ]; then
                echo "Attempt $i..."
            fi
            sleep 5
        done
        echo "EMULATOR_TIMEOUT"
        tail -20 /tmp/emulator.log 2>/dev/null
    """, timeout=400)
    print(result)

    if "EMULATOR_READY" not in result:
        print("Emulator failed! Checking log...")
        print(cs.run("cat /tmp/emulator.log 2>/dev/null | tail -30", timeout=10))
        cs.close()
        return

    # Step 8: Build or get APK
    print("\n" + "=" * 60)
    print("STEP 8: Get APK")
    print("=" * 60)
    
    # First check if APK already exists
    apk = cs.run("find /workspaces/Onionroute-Lite/app/build/outputs -name '*.apk' -type f 2>/dev/null | head -1", timeout=10)
    print(f"Existing APK: {apk}")

    if not apk.strip() or "No such file" in apk:
        # Try downloading from GitHub Actions
        print("No APK locally, downloading from CI...")
        dl_result = cs.run("""
            cd /workspaces/Onionroute-Lite
            gh run download --name onionroute-lite-fdroid-debug 2>&1 | tail -5
            find . -name '*.apk' -type f | head -3
        """, timeout=120)
        print(dl_result)
        apk = cs.run("find /workspaces/Onionroute-Lite -name '*.apk' -type f 2>/dev/null | head -1", timeout=10)

    if not apk.strip():
        # Build from source
        print("Downloading failed, building from source...")
        print(cs.run("cd /workspaces/Onionroute-Lite && bash scripts/build-tor.sh 2>&1 | tail -10", timeout=300))
        print(cs.run("cd /workspaces/Onionroute-Lite && bash scripts/build-obfs4.sh 2>&1 | tail -10", timeout=300))
        print(cs.run("cd /workspaces/Onionroute-Lite && bash scripts/build-snowflake.sh 2>&1 | tail -10", timeout=300))
        print(cs.run("cd /workspaces/Onionroute-Lite && bash scripts/build-tun2socks.sh 2>&1 | tail -10", timeout=300))
        
        print(cs.run("""
            cd /workspaces/Onionroute-Lite
            mkdir -p app/src/main/jniLibs/x86_64
            cp /tmp/native-builds/x86_64/*.so app/src/main/jniLibs/x86_64/ 2>/dev/null || true
            ls app/src/main/jniLibs/x86_64/
        """, timeout=15))
        
        print(cs.run("cd /workspaces/Onionroute-Lite && ./gradlew assembleFdroidDebug 2>&1 | tail -15", timeout=600))
        apk = cs.run("find /workspaces/Onionroute-Lite/app/build/outputs -name '*.apk' -type f 2>/dev/null | head -1", timeout=10)

    print(f"Final APK: {apk}")

    # Step 9: Install APK
    print("\n" + "=" * 60)
    print("STEP 9: Install APK on Emulator")
    print("=" * 60)
    if apk.strip():
        print(cs.run(f"adb install -r {apk.strip()}", timeout=60))
    else:
        print("NO APK FOUND - cannot install")
        cs.close()
        return

    # Step 10: Launch app
    print("\n" + "=" * 60)
    print("STEP 10: Launch App")
    print("=" * 60)
    pkg = cs.run("aapt dump badging /workspaces/Onionroute-Lite/app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk 2>/dev/null | grep package | head -1 | sed \"s/.*name='\\([^']*\\)'.*/\\1/\"", timeout=10)
    print(f"Package: {pkg}")
    if pkg.strip():
        print(cs.run(f"adb shell am start -n {pkg.strip()}/.MainActivity", timeout=15))

    # Step 11: Capture logcat
    print("\n" + "=" * 60)
    print("STEP 11: Logcat")
    print("=" * 60)
    time.sleep(5)
    print(cs.run("adb logcat -d -t 100 | grep -iE 'tor|onion|vpn|proxy|snowflake|obfs' | tail -40", timeout=20))

    cs.close()
    print("\n" + "=" * 60)
    print("PIPELINE COMPLETE!")
    print("=" * 60)

if __name__ == '__main__':
    main()
