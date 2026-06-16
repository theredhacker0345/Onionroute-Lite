import paramiko, subprocess, threading, time, sys, os, socket, select

CODESPACE = "refactored-spork-jjj77pgqwg7j3qjwp"

class ProxySocket:
    def __init__(self, stdin_pipe, stdout_pipe):
        self.stdin = stdin_pipe
        self.stdout = stdout_pipe
        self._timeout = 30
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
        
    def connect(self):
        # Start the gh proxy process
        self.proxy = subprocess.Popen(
            ['gh', 'codespace', 'ssh', '-c', CODESPACE, '--stdio'],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0
        )
        time.sleep(3)
        
        proxy_sock = ProxySocket(self.proxy.stdin, self.proxy.stdout)
        
        self.transport = paramiko.Transport(proxy_sock)
        self.transport.use_compression(True)
        self.transport.start_client()
        
        # Use the codespaces.auto SSH key
        key_path = os.path.expanduser("~/.ssh/codespaces.auto")
        try:
            pkey = paramiko.Ed25519Key.from_private_key_file(key_path)
            self.transport.auth_publickey('codespace', pkey)
            print("Authenticated with codespaces.auto key (Ed25519)")
        except Exception as e1:
            print(f"Ed25519 failed: {e1}")
            try:
                pkey = paramiko.RSAKey.from_private_key_file(key_path)
                self.transport.auth_publickey('codespace', pkey)
                print("Authenticated with codespaces.auto key (RSA)")
            except Exception as e2:
                print(f"RSA failed: {e2}")
                # Try ECDSA
                try:
                    pkey = paramiko.ECDSAKey.from_private_key_file(key_path)
                    self.transport.auth_publickey('codespace', pkey)
                    print("Authenticated with codespaces.auto key (ECDSA)")
                except Exception as e3:
                    print(f"ECDSA failed: {e3}")
                    raise Exception("All key types failed")
        
        # Open a session
        self.ssh = self.transport.open_session()
        self.ssh.get_pty()
        self.ssh.invoke_shell()
        time.sleep(2)
        
        # Read any banner
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
        
        return True
    
    def run(self, cmd, timeout=60):
        """Run a command and return output"""
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
            
        self.ssh.send(cmd + '\n')
        
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.ssh.recv_ready():
                data = self.ssh.recv(4096)
                output += data
                decoded = data.decode('utf-8', errors='replace')
                if decoded.rstrip().endswith('$') or decoded.rstrip().endswith('#'):
                    break
            time.sleep(0.1)
        
        text = output.decode('utf-8', errors='replace')
        lines = text.split('\n')
        clean = []
        skip_first = True
        for line in lines:
            if skip_first and cmd.split()[0] in line:
                skip_first = False
                continue
            stripped = line.strip()
            if stripped.endswith('$') or stripped.endswith('#') or stripped.startswith('@') or 'vscode@' in stripped:
                continue
            clean.append(line)
        return '\n'.join(clean).strip()

    def close(self):
        try:
            if self.ssh:
                self.ssh.close()
            if self.transport:
                self.transport.close()
            if self.proxy:
                self.proxy.terminate()
        except:
            pass

if __name__ == '__main__':
    cs = CodespaceSSH()
    print("Connecting to codespace...")
    try:
        cs.connect()
        print("Connected! Testing...")
        result = cs.run("echo HELLO_CODESPACE && uname -a", timeout=15)
        print(f"Result: {result}")
        cs.close()
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
