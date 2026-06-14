#!/usr/bin/env python3
"""Full autonomous pipeline: Connect to codespace, setup Android SDK/emulator, build/get APK, install and run."""

import subprocess, time, select, paramiko, socket, sys, re

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
        print("Connected to codespace!")

    def run(self, cmd, timeout=120, stream=True):
        while self.chan.recv_ready():
            self.chan.recv(4096)
        marker = f"__END_{int(time.time()*1000)}__"
        self.chan.send(f"{cmd}; echo {marker}\n")
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.chan.recv_ready():
                data = self.chan.recv(4096)
                output += data
                decoded = data.decode('utf-8', errors='replace')
                if stream:
                    # Print non-prompt lines
                    for line in decoded.split('\n'):
                        s = line.strip()
                        if s and not (s.endswith('$') and len(s) < 100) and marker not in s:
                            print(line)
                if marker.encode() in output:
                    break
            time.sleep(0.05)
        text = output.decode('utf-8', errors='replace')
        lines = []
        for line in text.split('\n'):
            s = line.strip()
            if marker in s:
                continue
            if s.endswith('$') and len(s) < 100:
                continue
            lines.append(line)
        return '\n'.join(lines).strip()

    def close(self):
        try:
            if self.chan: self.chan.close()
            if self.transport: self.transport.close()
            if self.proc: self.proc.terminate()
        except: pass


def main():
    cs = CodespaceSSH()
    cs.connect()

    # === Step 1: Check environment ===
    print("\n" + "="*60)
    print("STEP 1: Check Environment")
    print("="*60)
    env = cs.run("uname -a && echo '---' && df -h / | tail -1 && echo '---' && free -h | head -2", timeout=15, stream=False)
    print(env)

    # === Step 2: Install Android SDK if needed ===
    print("\n" + "="*60)
    print("STEP 2: Setup Android SDK")
    print("="*60)
    sdk_check = cs.run("test -d $ANDROID_HOME && echo 'SDK_EXISTS' || echo 'NO_SDK'", timeout=10, stream=False)
    print(f"SDK status: {sdk_check}")

    if "NO_SDK" in sdk_check:
        print("Installing Android SDK...")
        install_cmd = """
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME
cd $ANDROID_HOME
curl -sL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdtools.zip
unzip -q cmdtools.zip
mkdir -p latest/cmdline-tools
mv cmdline-tools latest/cmdline-tools/tools 2>/dev/null || true
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
yes | sdkmanager --licenses > /dev/null 2>&1
sdkmanager "platform-tools" "platforms;android-34" "system-images;android-34;default;x86_64" "emulator" 2>&1 | tail -5
echo "SDK_INSTALL_DONE"
"""
        cs.run(install_cmd, timeout=600, stream=True)

    # === Step 3: Setup environment variables ===
    print("\n" + "="*60)
    print("STEP 3: Configure Environment")
    print("="*60)
    cs.run("""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$HOME/local/bin:$PATH
echo "ANDROID_HOME=$ANDROID_HOME"
which adb
which emulator
""", timeout=15)

    # === Step 4: Create AVD ===
    print("\n" + "="*60)
    print("STEP 4: Create AVD")
    print("="*60)
    avd_check = cs.run("test -d $HOME/.android/avd && ls $HOME/.android/avd/*.ini 2>/dev/null | head -3 || echo 'NO_AVD'", timeout=10, stream=False)
    print(f"AVD status: {avd_check}")

    if "NO_AVD" in avd_check or not avd_check.strip():
        cs.run("""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
echo "no" | avdmanager create avd -n test -k "system-images;android-34;default;x86_64" --force 2>&1 | tail -5
echo "AVD_CREATED"
""", timeout=60, stream=True)

    # === Step 5: Start Emulator ===
    print("\n" + "="*60)
    print("STEP 5: Start Emulator")
    print("="*60)
    cs.run("""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
# Install libpulse for audio
dpkg -l libpulse0 2>/dev/null | grep -q libpulse0 || (sudo apt-get update -qq && sudo apt-get install -y -qq libpulse0 2>/dev/null)
# Start emulator with KVM in background
nohup emulator -avd test -no-window -no-audio -gpu swiftshader_indirect -no-snapshot -memory 2048 -accel on > /tmp/emulator.log 2>&1 &
echo "Emulator PID: $!"
sleep 2
echo "EMULATOR_STARTING"
""", timeout=30, stream=True)

    # Wait for emulator to boot
    print("\nWaiting for emulator to boot...")
    boot_result = cs.run("""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
for i in $(seq 1 60); do
    if adb devices | grep -q "emulator"; then
        echo "Emulator detected, waiting for boot..."
        adb wait-for-device
        BOOT=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\\r')
        if [ "$BOOT" = "1" ]; then
            echo "EMULATOR_READY"
            break
        fi
    fi
    echo "Waiting... attempt $i"
    sleep 5
done
adb devices
""", timeout=300, stream=True)

    if "EMULATOR_READY" not in boot_result:
        print("Emulator did not boot! Checking logs...")
        cs.run("cat /tmp/emulator.log 2>/dev/null | tail -20", timeout=10, stream=True)
        cs.close()
        return

    # === Step 6: Build/Get APK ===
    print("\n" + "="*60)
    print("STEP 6: Get APK")
    print("="*60)
    apk_check = cs.run("find /workspaces/Onionroute-Lite -name '*.apk' -type f 2>/dev/null | head -5", timeout=15, stream=False)
    print(f"Existing APKs: {apk_check}")

    if not apk_check.strip() or "No such file" in apk_check:
        print("Building APK from source...")
        cs.run("""
cd /workspaces/Onionroute-Lite
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/latest/cmdline-tools/tools/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

# Build native binaries first
echo "Building native binaries..."
bash scripts/build-tor.sh 2>&1 | tail -5
bash scripts/build-obfs4.sh 2>&1 | tail -5
bash scripts/build-snowflake.sh 2>&1 | tail -5
bash scripts/build-tun2socks.sh 2>&1 | tail -5

# Copy to jniLibs
echo "Copying to jniLibs..."
mkdir -p app/src/main/jniLibs/x86_64
cp /tmp/native-builds/x86_64/*.so app/src/main/jniLibs/x86_64/ 2>/dev/null || true
ls app/src/main/jniLibs/x86_64/

# Build APK
echo "Building APK..."
./gradlew assembleFdroidDebug 2>&1 | tail -10
echo "BUILD_DONE"
""", timeout=600, stream=True)

    # Find the APK
    apk_path = cs.run("find /workspaces/Onionroute-Lite -name 'app-fdroid-debug.apk' -type f 2>/dev/null | head -1", timeout=15, stream=False)
    print(f"APK path: {apk_path.strip()}")

    if not apk_path.strip():
        # Try alternative names
        apk_path = cs.run("find /workspaces/Onionroute-Lite -name '*.apk' -type f 2>/dev/null | head -1", timeout=15, stream=False)
        print(f"Alternative APK path: {apk_path.strip()}")

    if not apk_path.strip():
        print("No APK found! Trying to download from GitHub Actions...")
        # Download APK from latest CI artifact
        cs.run("""
cd /workspaces/Onionroute-Lite
export GH_TOKEN=$(git remote get-url origin | sed 's|.*://\\([^@]*\\)@.*|\\1|')
# Use gh CLI to download artifact
gh run download --name onionroute-lite-fdroid-debug --repo darkmaster0345/Onionroute-Lite 2>&1 | tail -5
ls -la *.apk 2>/dev/null
find . -name '*.apk' -type f | head -3
echo "DOWNLOAD_DONE"
""", timeout=120, stream=True)
        apk_path = cs.run("find /workspaces/Onionroute-Lite -name '*.apk' -type f 2>/dev/null | head -1", timeout=15, stream=False)

    # === Step 7: Install APK on emulator ===
    print("\n" + "="*60)
    print("STEP 7: Install APK on Emulator")
    print("="*60)
    if apk_path.strip():
        apk = apk_path.strip()
        print(f"Installing: {apk}")
        cs.run(f"""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
adb install -r {apk} 2>&1
echo "INSTALL_DONE"
""", timeout=120, stream=True)
    else:
        print("ERROR: No APK available to install!")
        cs.close()
        return

    # === Step 8: Launch app ===
    print("\n" + "="*60)
    print("STEP 8: Launch App")
    print("="*60)
    cs.run("""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
# Find the package name
PKG=$(aapt dump badging /workspaces/Onionroute-Lite/app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk 2>/dev/null | grep package | head -1 | sed "s/.*name='\\([^']*\\)'.*/\\1/")
echo "Package: $PKG"
adb shell am start -n $PKG/.MainActivity 2>&1
echo "APP_LAUNCHED"
""", timeout=30, stream=True)

    # === Step 9: Capture logcat ===
    print("\n" + "="*60)
    print("STEP 9: Logcat Output")
    print("="*60)
    cs.run("""
export ANDROID_HOME=$HOME/android-sdk
export PATH=$ANDROID_HOME/platform-tools:$PATH
adb logcat -d -s "TorProcessManager" "TorControlConnection" "BandwidthTracker" "OrbotLite" "OnionRoute" | tail -50
echo "LOGCAT_DONE"
""", timeout=30, stream=True)

    cs.close()
    print("\n" + "="*60)
    print("PIPELINE COMPLETE!")
    print("="*60)

if __name__ == '__main__':
    main()
