#!/usr/bin/env python3
"""Full autonomous pipeline: Codespace -> SDK -> Emulator -> APK -> Install -> Run"""

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
        self.chan = None

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
        self.chan = self.transport.open_session()
        self.chan.get_pty()
        self.chan.invoke_shell()
        time.sleep(2)
        while self.chan.recv_ready():
            self.chan.recv(4096)
        print("Connected!")

    def run(self, cmd, timeout=120):
        """Run a single command and return clean output."""
        while self.chan.recv_ready():
            self.chan.recv(4096)
        marker = f"XENDX{int(time.time()*1000)}X"
        # Use base64-encoded marker to avoid shell interpretation issues
        self.chan.send(f"{cmd} ; echo {marker}\n")
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.chan.recv_ready():
                data = self.chan.recv(4096)
                output += data
                if marker.encode() in output:
                    break
            time.sleep(0.05)
        text = output.decode('utf-8', errors='replace')
        lines = []
        for line in text.split('\n'):
            if marker in line:
                continue
            s = line.strip()
            if not s:
                continue
            # Skip zsh prompt lines
            if s.startswith('@') and s.endswith('$'):
                continue
            if '➜' in s and '(main)' in s:
                continue
            lines.append(line)
        return '\n'.join(lines).strip()

    def run_script(self, script, timeout=600):
        """Run a multi-line script by writing it to a temp file and executing."""
        # Write script to temp file on the codespace
        self.run("cat > /tmp/run_script.sh << 'SCRIPTEOF'", timeout=5)
        for line in script.strip().split('\n'):
            self.chan.send(line + '\n')
            time.sleep(0.02)
        self.run("SCRIPTEOF", timeout=5)
        return self.run("bash /tmp/run_script.sh", timeout=timeout)

    def close(self):
        try:
            if self.chan: self.chan.close()
            if self.transport: self.transport.close()
            if self.proc: self.proc.terminate()
        except: pass


def main():
    cs = CodespaceSSH()
    cs.connect()

    # Step 1: Basic check
    print("=" * 50)
    print("STEP 1: Environment")
    print("=" * 50)
    print(cs.run("uname -a", timeout=10))
    print(cs.run("df -h / | tail -1", timeout=5))
    print(cs.run("free -h | head -2", timeout=5))

    # Step 2: Check Android SDK
    print("\n" + "=" * 50)
    print("STEP 2: Android SDK")
    print("=" * 50)
    sdk_result = cs.run("ls ~/android-sdk/ 2>/dev/null | head -10 || echo NO_SDK", timeout=10)
    print(sdk_result)

    # Step 3: Install missing SDK components
    print("\n" + "=" * 50)
    print("STEP 3: Install SDK Components")
    print("=" * 50)
    script = """#!/bin/bash
set -e
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

# Check if platform-tools exist
if [ ! -d "$ANDROID_HOME/platform-tools" ]; then
    echo "Installing platform-tools..."
    yes | sdkmanager --licenses > /dev/null 2>&1 || true
    sdkmanager "platform-tools" 2>&1 | tail -3
else
    echo "platform-tools already installed"
fi

# Check if emulator exists
if [ ! -d "$ANDROID_HOME/emulator" ]; then
    echo "Installing emulator..."
    sdkmanager "emulator" 2>&1 | tail -3
else
    echo "emulator already installed"
fi

# Check if system image exists
if [ -z "$(ls $ANDROID_HOME/system-images/ 2>/dev/null)" ]; then
    echo "Installing system image..."
    sdkmanager "system-images;android-34;default;x86_64" 2>&1 | tail -3
else
    echo "system image already installed"
fi

echo "SDK_SETUP_COMPLETE"
"""
    result = cs.run_script(script, timeout=600)
    print(result)

    # Step 4: Create AVD
    print("\n" + "=" * 50)
    print("STEP 4: Create AVD")
    print("=" * 50)
    avd_result = cs.run("ls ~/.android/avd/*.ini 2>/dev/null | head -3 || echo NO_AVD", timeout=10)
    print(f"Current AVDs: {avd_result}")

    if "NO_AVD" in avd_result or not avd_result.strip():
        avd_script = """#!/bin/bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
echo "no" | avdmanager create avd -n test -k "system-images;android-34;default;x86_64" --force 2>&1 | tail -5
echo "AVD_CREATED"
"""
        result = cs.run_script(avd_script, timeout=60)
        print(result)

    # Step 5: Install libpulse
    print("\n" + "=" * 50)
    print("STEP 5: Install libpulse")
    print("=" * 50)
    print(cs.run("sudo apt-get install -y libpulse0 2>&1 | tail -3", timeout=60))

    # Step 6: Start emulator
    print("\n" + "=" * 50)
    print("STEP 6: Start Emulator")
    print("=" * 50)
    
    # Kill any existing emulator
    cs.run("pkill -9 emulator 2>/dev/null; pkill -9 qemu 2>/dev/null; sleep 1; echo DONE", timeout=10)
    
    emu_script = """#!/bin/bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
export QEMU_EMULATOR_OPTS="-no-window"

nohup emulator -avd test -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -memory 2048 -accel on > /tmp/emulator.log 2>&1 &
EMU_PID=$!
echo "Emulator started with PID $EMU_PID"
sleep 3
echo "Emulator process running: $(kill -0 $EMU_PID 2>/dev/null && echo YES || echo NO)"
"""
    print(cs.run_script(emu_script, timeout=30))

    # Step 7: Wait for emulator
    print("\n" + "=" * 50)
    print("STEP 7: Wait for Emulator Boot")
    print("=" * 50)
    wait_script = """#!/bin/bash
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH

echo "Waiting for emulator device..."
for i in $(seq 1 60); do
    if adb devices 2>/dev/null | grep -q "emulator"; then
        echo "Device found! Waiting for boot..."
        adb wait-for-device
        BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r\\n')
        if [ "$BOOT" = "1" ]; then
            echo "EMULATOR_READY"
            adb devices
            exit 0
        fi
    fi
    if [ $((i % 10)) -eq 0 ]; then
        echo "Attempt $i - still waiting..."
    fi
    sleep 5
done
echo "EMULATOR_TIMEOUT"
tail -20 /tmp/emulator.log 2>/dev/null
"""
    result = cs.run_script(wait_script, timeout=400)
    print(result)

    if "EMULATOR_READY" not in result:
        print("Emulator failed to boot! Checking logs...")
        print(cs.run("tail -30 /tmp/emulator.log 2>/dev/null", timeout=10))
        cs.close()
        return

    # Step 8: Build APK
    print("\n" + "=" * 50)
    print("STEP 8: Build APK")
    print("=" * 50)
    build_script = """#!/bin/bash
set -e
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
cd /workspaces/Onionroute-Lite

# Check if APK already exists
APK=$(find app/build/outputs -name '*.apk' -type f 2>/dev/null | head -1)
if [ -n "$APK" ]; then
    echo "APK already exists: $APK"
    echo "$APK"
    exit 0
fi

# Build native binaries
echo "Building native binaries..."
mkdir -p /tmp/native-builds/x86_64

# Build Tor
if [ ! -f /tmp/native-builds/x86_64/libtor.so ]; then
    echo "Building libtor..."
    bash scripts/build-tor.sh 2>&1 | tail -5
fi

# Build obfs4
if [ ! -f /tmp/native-builds/x86_64/libobfs4proxy.so ]; then
    echo "Building libobfs4proxy..."
    bash scripts/build-obfs4.sh 2>&1 | tail -5
fi

# Build snowflake
if [ ! -f /tmp/native-builds/x86_64/libsnowflake.so ]; then
    echo "Building libsnowflake..."
    bash scripts/build-snowflake.sh 2>&1 | tail -5
fi

# Build tun2socks
if [ ! -f /tmp/native-builds/x86_64/libtun2socks.so ]; then
    echo "Building libtun2socks..."
    bash scripts/build-tun2socks.sh 2>&1 | tail -5
fi

# Copy to jniLibs
echo "Copying native binaries to jniLibs..."
mkdir -p app/src/main/jniLibs/x86_64
cp /tmp/native-builds/x86_64/*.so app/src/main/jniLibs/x86_64/ 2>/dev/null || true
ls -la app/src/main/jniLibs/x86_64/

# Build APK
echo "Running Gradle..."
./gradlew assembleFdroidDebug 2>&1 | tail -15

# Find APK
find app/build/outputs -name '*.apk' -type f 2>/dev/null
echo "BUILD_COMPLETE"
"""
    result = cs.run_script(build_script, timeout=900)
    print(result)

    # Step 9: Find and install APK
    print("\n" + "=" * 50)
    print("STEP 9: Install APK")
    print("=" * 50)
    apk_path = cs.run("find /workspaces/Onionroute-Lite/app/build/outputs -name '*.apk' -type f 2>/dev/null | head -1", timeout=10)
    print(f"APK: {apk_path}")

    if apk_path.strip():
        install_result = cs.run(f"adb install -r {apk_path.strip()}", timeout=60)
        print(f"Install: {install_result}")
    else:
        print("No APK found! Cannot install.")
        cs.close()
        return

    # Step 10: Launch app
    print("\n" + "=" * 50)
    print("STEP 10: Launch App")
    print("=" * 50)
    pkg = cs.run("aapt dump badging /workspaces/Onionroute-Lite/app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk 2>/dev/null | grep package | head -1 | sed \"s/.*name='\\([^']*\\)'.*/\\1/\"", timeout=10)
    print(f"Package: {pkg}")
    
    if pkg.strip():
        launch_result = cs.run(f"adb shell am start -n {pkg.strip()}/.MainActivity", timeout=15)
        print(f"Launch: {launch_result}")

    # Step 11: Capture logcat
    print("\n" + "=" * 50)
    print("STEP 11: Logcat")
    print("=" * 50)
    time.sleep(5)
    logcat = cs.run("adb logcat -d -t 100 | grep -iE 'tor|onion|vpn|proxy|snowflake|obfs' | tail -40", timeout=20)
    print(logcat)

    cs.close()
    print("\n" + "=" * 50)
    print("PIPELINE COMPLETE!")
    print("=" * 50)

if __name__ == '__main__':
    main()
