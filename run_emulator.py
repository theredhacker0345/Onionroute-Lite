#!/usr/bin/env python3
"""Connect to GitHub Codespace, build/get APK, and run on Android emulator."""

import paramiko, subprocess, threading, time, sys, os, socket, select

CODESPACE = "fluffy-eureka-7v5j4j6q7gq2r5wg"
GH_PATH = os.path.expanduser("~/local/bin/gh")
AUTO_KEY_PATH = os.path.expanduser("~/.ssh/codespaces.auto")

class ProxySocket:
    def __init__(self, stdin_pipe, stdout_pipe):
        self.stdin = stdin_pipe
        self.stdout = stdout_pipe
        self._timeout = 60
        self._closed = False
    def settimeout(self, timeout):
        self._timeout = timeout
    def send(self, data):
        return self.stdin.write(data)
    def recv(self, bufsize):
        ready, _, _ = select.select([self.stdout], [], [], self._timeout)
        if ready:
            return self.stdout.read(bufsize)
        raise socket.timeout()
    def close(self):
        self._closed = True
        self.stdin.close()
        self.stdout.close()

class CodespaceSSH:
    def __init__(self):
        self.transport = None
        self.ssh = None
        self.proxy = None
        
    def connect(self):
        # Start the gh proxy process
        self.proxy = subprocess.Popen(
            [GH_PATH, 'codespace', 'ssh', '-c', CODESPACE, '--stdio', '--',
             '-i', AUTO_KEY_PATH],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            bufsize=0
        )
        time.sleep(5)
        
        proxy_sock = ProxySocket(self.proxy.stdin, self.proxy.stdout)
        
        self.transport = paramiko.Transport(proxy_sock)
        self.transport.use_compression(True)
        self.transport.start_client()
        
        # Try the codespaces.auto key
        key = paramiko.Ed25519Key.from_private_key_file(AUTO_KEY_PATH)
        self.transport.auth_publickey('codespace', key)
        print("Authenticated with codespaces.auto key (Ed25519)")
        
        self.ssh = self.transport.open_session()
        self.ssh.get_pty()
        self.ssh.invoke_shell()
        time.sleep(2)
        
        # Read any banner/motd
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
        
        return True
    
    def run(self, cmd, timeout=120):
        """Run a command and return output"""
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
            
        # Send command with a marker at the end
        marker = f"__END_CMD_{int(time.time())}__"
        full_cmd = f"{cmd}; echo {marker}"
        self.ssh.send(full_cmd + '\n')
        
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.ssh.recv_ready():
                data = self.ssh.recv(4096)
                output += data
                if marker.encode() in output:
                    break
            time.sleep(0.1)
        
        text = output.decode('utf-8', errors='replace')
        # Remove the marker line and command echo
        lines = text.split('\n')
        clean = []
        for line in lines:
            if marker in line:
                continue
            stripped = line.strip()
            # Skip shell prompt lines
            if stripped.endswith('$') and len(stripped) < 80:
                continue
            clean.append(line)
        return '\n'.join(clean).strip()
    
    def run_long(self, cmd, timeout=600, stream=False):
        """Run a long command and optionally stream output"""
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
            
        marker = f"__END_CMD_{int(time.time())}__"
        full_cmd = f"{cmd}; echo {marker}"
        self.ssh.send(full_cmd + '\n')
        
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.ssh.recv_ready():
                data = self.ssh.recv(4096)
                output += data
                decoded = data.decode('utf-8', errors='replace')
                if stream:
                    print(decoded, end='', flush=True)
                if marker.encode() in output:
                    break
            time.sleep(0.1)
        
        text = output.decode('utf-8', errors='replace')
        lines = text.split('\n')
        clean = []
        for line in lines:
            if marker in line:
                continue
            stripped = line.strip()
            if stripped.endswith('$') and len(stripped) < 80:
                continue
            clean.append(line)
        return '\n'.join(clean).strip()
    
    def close(self):
        try:
            if self.ssh: self.ssh.close()
            if self.transport: self.transport.close()
            if self.proxy: self.proxy.terminate()
        except: pass

def main():
    cs = CodespaceSSH()
    print("Connecting to codespace...")
    cs.connect()
    print("Connected!")
    
    # Step 1: Check environment
    print("\n=== Step 1: Check environment ===")
    result = cs.run("uname -a && whoami && df -h / && free -h | head -3", timeout=15)
    print(result)
    
    # Step 2: Check if Android SDK is available
    print("\n=== Step 2: Check Android SDK ===")
    result = cs.run("ls -la /home/codespace/android-sdk/ 2>/dev/null || echo 'NO_ANDROID_SDK'; echo ANDROID_HOME=$ANDROID_HOME; echo PATH=$PATH | tr ':' '\n' | grep android | head -5", timeout=15)
    print(result)
    
    # Step 3: Check if emulator tools exist
    print("\n=== Step 3: Check emulator tools ===")
    result = cs.run("which emulator 2>/dev/null || echo 'NO_EMULATOR'; which adb 2>/dev/null || echo 'NO_ADB'; ls /dev/kvm 2>/dev/null || echo 'NO_KVM'", timeout=15)
    print(result)
    
    cs.close()

if __name__ == '__main__':
    main()
