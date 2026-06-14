import subprocess, time, paramiko, select, socket, os

GH_PATH = os.path.expanduser("~/local/bin/gh")
AUTO_KEY = os.path.expanduser("~/.ssh/auto_key")
CODESPACE = "fluffy-eureka-7v5j4j6q7gq2r5wg"

class Sock:
    def __init__(self, p): self.p = p; self._timeout = 60; self._closed = False
    def settimeout(self, t): self._timeout = t
    def send(self, d): self.p.stdin.write(d); self.p.stdin.flush(); return len(d)
    def recv(self, n):
        r,_,_ = select.select([self.p.stdout],[],[],self._timeout)
        if r:
            d = self.p.stdout.read(n)
            if not d: raise socket.error('empty')
            return d
        raise socket.timeout()
    def close(self): self._closed = True

def run_cmd(cmd, timeout=30):
    proc = subprocess.Popen(
        [GH_PATH, 'codespace', 'ssh', '-c', CODESPACE, '--stdio', '--', '-i', AUTO_KEY],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
    time.sleep(3)
    t = paramiko.Transport(Sock(proc))
    t.use_compression(True); t.banner_timeout = 30; t.start_client()
    key = paramiko.Ed25519Key.from_private_key_file(AUTO_KEY)
    t.auth_publickey('codespace', key)
    chan = t.open_session()
    chan.settimeout(timeout)
    chan.exec_command(cmd)
    out = b''
    try:
        while True:
            out += chan.recv(65536)
    except:
        pass
    chan.close(); t.close(); proc.terminate()
    return out.decode('utf-8', errors='replace')

# Find project structure
print("=== Finding KindnessService ===")
result = run_cmd("find /workspaces -name 'KindnessService.kt' -o -name 'KindnessService.java' 2>/dev/null", timeout=30)
print(result)

print("\n=== Project src dirs ===")
result2 = run_cmd("find /workspaces -type d -name 'src' 2>/dev/null | head -20", timeout=30)
print(result2)

print("\n=== Find snowflake/Kindness files ===")
result3 = run_cmd(r"grep -rl 'KindnessService\|snowflake.proxy\|libsnowflake' /workspaces --include='*.kt' --include='*.java' 2>/dev/null | head -30", timeout=60)
print(result3)

