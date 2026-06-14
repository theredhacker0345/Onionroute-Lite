import paramiko, subprocess, threading, time, sys, os, socket, select

CODESPACE = "fluffy-eureka-7v5j4j6q7gq2r5wg"
GH_PATH = os.path.expanduser("~/local/bin/gh")

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
        self.proxy = subprocess.Popen(
            [GH_PATH, 'codespace', 'ssh', '-c', CODESPACE, '--stdio'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
        )
        time.sleep(3)
        proxy_sock = ProxySocket(self.proxy.stdin, self.proxy.stdout)
        self.transport = paramiko.Transport(proxy_sock)
        self.transport.use_compression(True)
        self.transport.start_client()
        key_path = os.path.expanduser("~/.ssh/codespaces.auto")
        for KeyClass in [paramiko.Ed25519Key, paramiko.RSAKey, paramiko.ECDSAKey]:
            try:
                pkey = KeyClass.from_private_key_file(key_path)
                self.transport.auth_publickey('codespace', pkey)
                print(f"Authenticated with {KeyClass.__name__}")
                break
            except Exception as e:
                print(f"{KeyClass.__name__} failed: {e}")
        else:
            # Try no auth
            self.transport.auth_none('codespace')
            print("Authenticated with no auth")
        
        self.ssh = self.transport.open_session()
        self.ssh.get_pty()
        self.ssh.invoke_shell()
        time.sleep(2)
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
        return True
    
    def run(self, cmd, timeout=60):
        while self.ssh.recv_ready():
            self.ssh.recv(4096)
        self.ssh.send(cmd + '\n')
        output = b''
        start = time.time()
        while time.time() - start < timeout:
            if self.ssh.recv_ready():
                data = self.ssh.recv(4096)
                output += data
            time.sleep(0.1)
        text = output.decode('utf-8', errors='replace')
        # Clean up prompt lines
        lines = text.split('\n')
        clean = []
        for line in lines:
            stripped = line.strip()
            if stripped.endswith('$') or stripped.endswith('#'):
                if not any(kw in stripped for kw in ['echo', 'HELLO']):
                    continue
            clean.append(line)
        return '\n'.join(clean).strip()
    
    def close(self):
        try:
            if self.ssh: self.ssh.close()
            if self.transport: self.transport.close()
            if self.proxy: self.proxy.terminate()
        except: pass

if __name__ == '__main__':
    cs = CodespaceSSH()
    print("Connecting to codespace...")
    try:
        cs.connect()
        print("Connected! Testing...")
        result = cs.run("echo HELLO_CODESPACE && uname -a && whoami", timeout=15)
        print(f"Result: {result}")
        cs.close()
    except Exception as e:
        print(f"Error: {e}")
        import traceback
        traceback.print_exc()
