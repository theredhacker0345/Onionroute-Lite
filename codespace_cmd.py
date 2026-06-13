import paramiko, subprocess, time, sys, os, socket, select, re

CODESPACE = "friendly-happiness-v66rrj5q7w4r3xjqg"

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
        try: self.stdin.close()
        except: pass
        try: self.stdout.close()
        except: pass

def run_remote(cmd, timeout=120, retries=3):
    for attempt in range(retries):
        try:
            proxy = subprocess.Popen(
                ['gh', 'codespace', 'ssh', '-c', CODESPACE, '--stdio'],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0
            )
            time.sleep(4)
            proxy_sock = ProxySocket(proxy.stdin, proxy.stdout)
            transport = paramiko.Transport(proxy_sock)
            transport.use_compression(True)
            transport.start_client()
            key_path = os.path.expanduser("~/.ssh/codespaces.auto")
            pkey = paramiko.Ed25519Key.from_private_key_file(key_path)
            transport.auth_publickey('codespace', pkey)
            
            ssh = transport.open_session()
            # Use exec_command style instead of shell
            ssh.exec_command(cmd)
            
            output = b''
            err = b''
            start = time.time()
            while time.time() - start < timeout:
                if ssh.recv_ready():
                    data = ssh.recv(65536)
                    output += data
                if ssh.recv_stderr_ready():
                    data = ssh.recv_stderr(65536)
                    err += data
                if ssh.exit_status_ready():
                    # Read any remaining data
                    while ssh.recv_ready():
                        output += ssh.recv(65536)
                    while ssh.recv_stderr_ready():
                        err += ssh.recv_stderr(65536)
                    break
                time.sleep(0.1)
            
            result = output.decode('utf-8', errors='replace')
            error = err.decode('utf-8', errors='replace')
            # Remove ANSI codes
            result = re.sub(r'\x1b\[[0-9;]*[a-zA-Z]', '', result)
            result = re.sub(r'\x1b\[[0-9;]*m', '', result)
            
            ssh.close()
            transport.close()
            proxy.terminate()
            
            if error.strip():
                return f"{result.strip()}\nSTDERR: {error.strip()}"
            return result.strip()
            
        except Exception as e:
            try: proxy.terminate()
            except: pass
            if attempt < retries - 1:
                print(f"Attempt {attempt+1} failed: {e}. Retrying...", file=sys.stderr)
                time.sleep(5)
            else:
                raise Exception(f"All {retries} attempts failed: {e}")

if __name__ == '__main__':
    cmd = ' '.join(sys.argv[1:]) if len(sys.argv) > 1 else 'echo hello'
    result = run_remote(cmd)
    print(result)
