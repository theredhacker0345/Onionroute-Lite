#!/usr/bin/env python3
"""Connect to GitHub Codespace using paramiko with proper pipe handling."""

import paramiko, subprocess, threading, time, sys, os, socket, select, queue

CODESPACE = "fluffy-eureka-7v5j4j6q7gq2r5wg"
GH_PATH = os.path.expanduser("~/local/bin/gh")
AUTO_KEY_PATH = os.path.expanduser("~/.ssh/codespaces.auto")

class PipeSocket:
    """A socket-like wrapper around subprocess pipes for paramiko Transport."""
    def __init__(self, proc):
        self.proc = proc
        self._timeout = 30
        
    def settimeout(self, timeout):
        self._timeout = timeout
        
    def send(self, data):
        try:
            self.proc.stdin.write(data)
            self.proc.stdin.flush()
            return len(data)
        except Exception as e:
            raise socket.error(str(e))
        
    def recv(self, bufsize):
        ready, _, _ = select.select([self.proc.stdout], [], [], self._timeout)
        if ready:
            data = self.proc.stdout.read(bufsize)
            if not data:
                raise socket.error("Connection closed")
            return data
        raise socket.timeout()
        
    def close(self):
        pass  # Let the process handle its own cleanup


class CodespaceSSH:
    def __init__(self):
        self.transport = None
        self.chan = None
        self.proc = None
        
    def connect(self):
        # Start the gh proxy process with proper pipes
        self.proc = subprocess.Popen(
            [GH_PATH, 'codespace', 'ssh', '-c', CODESPACE, '--stdio', '--',
             '-i', AUTO_KEY_PATH],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0
        )
        
        # Wait for SSH banner from the codespace
        print("Waiting for SSH banner...")
        start = time.time()
        banner = b''
        while time.time() - start < 20:
            ready, _, _ = select.select([self.proc.stdout], [], [], 1)
            if ready:
                data = self.proc.stdout.read(4096)
                banner += data
                if b'\n' in banner:
                    print(f"Got banner: {banner.decode('utf-8', errors='replace').strip()}")
                    break
        
        if not banner:
            raise Exception("No SSH banner received")
        
        # Now set up paramiko Transport using the pipes
        sock = PipeSocket(self.proc)
        
        self.transport = paramiko.Transport(sock)
        self.transport.use_compression(True)
        
        print("Starting SSH client negotiation...")
        self.transport.start_client()
        print("SSH negotiation complete!")
        
        # Authenticate with the codespaces.auto key
        key = paramiko.Ed25519Key.from_private_key_file(AUTO_KEY_PATH)
        self.transport.auth_publickey('codespace', key)
        print("Authenticated!")
        
        # Open an interactive session
        self.chan = self.transport.open_session()
        self.chan.get_pty('xterm-256color', 80, 24)
        self.chan.invoke_shell()
        time.sleep(2)
        
        # Read any initial output (motd, etc)
        while self.chan.recv_ready():
            self.chan.recv(4096)
        
        return True
    
    def run(self, cmd, timeout=120):
        """Run a command and return output."""
        # Drain any pending data
        while self.chan.recv_ready():
            self.chan.recv(4096)
        
        # Send command with end marker
        marker = f"__ENDCMD_{int(time.time()*1000)}__"
        self.chan.send(f"{cmd}; echo {marker}\n")
        
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.chan.recv_ready():
                data = self.chan.recv(4096)
                output += data
                if marker.encode() in output:
                    break
            time.sleep(0.1)
        
        text = output.decode('utf-8', errors='replace')
        # Clean up output - remove echo of command and marker
        lines = text.split('\n')
        clean = []
        for line in lines:
            if marker in line:
                continue
            stripped = line.strip()
            if stripped.endswith('$') and len(stripped) < 100:
                continue
            clean.append(line)
        return '\n'.join(clean).strip()
    
    def close(self):
        try:
            if self.chan: self.chan.close()
            if self.transport: self.transport.close()
            if self.proc: self.proc.terminate()
        except: pass


def main():
    cs = CodespaceSSH()
    print("Connecting to codespace...")
    cs.connect()
    print("Connected!")
    
    # Step 1: Check basic environment
    print("\n=== Step 1: Environment Check ===")
    print(cs.run("uname -a && whoami && df -h / | tail -1"))
    
    # Step 2: Check Android SDK
    print("\n=== Step 2: Android SDK ===")
    print(cs.run("echo ANDROID_HOME=$ANDROID_HOME; ls $ANDROID_HOME 2>/dev/null | head -10 || echo 'SDK_NOT_FOUND'"))
    
    # Step 3: Check for KVM and emulator tools
    print("\n=== Step 3: KVM & Emulator ===")
    print(cs.run("ls -la /dev/kvm 2>/dev/null || echo 'NO_KVM'; which emulator 2>/dev/null || echo 'NO_EMULATOR'; which adb 2>/dev/null || echo 'NO_ADB'"))
    
    cs.close()

if __name__ == '__main__':
    main()
