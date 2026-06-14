import paramiko, subprocess, time, sys

def get_ssh_details():
    """Get codespace SSH host and key via gh CLI"""
    # First get the codespace name
    result = subprocess.run(['gh', 'codespace', 'list', '--json', 'name,machine'], 
                          capture_output=True, text=True, timeout=15)
    print(f"Codespaces: {result.stdout}")
    
    # Get SSH config for the codespace
    result = subprocess.run(['gh', 'codespace', 'ssh', '-c', 'refactored-spork-jjj77pgqwg7j3qjwp',
                           '--', 'echo', 'CONNECTION_OK'], 
                          capture_output=True, text=True, timeout=30)
    print(f"SSH test: {result.stdout} {result.stderr}")
    return result.returncode == 0

if __name__ == '__main__':
    get_ssh_details()
