# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in OnionRoute Lite, please report it responsibly.

**Do NOT open a public GitLab issue for security vulnerabilities.**

Instead, please:

1. Use GitLab private vulnerability reporting if it is enabled for the canonical project
2. Otherwise contact the maintainer privately through a verified contact channel listed on the GitLab project

Please include:
- Description of the vulnerability
- Steps to reproduce
- Potential impact
- Suggested fix (if you have one)

## Security Model

OnionRoute Lite is a Tor client application. Key security considerations:

- **VPN Kill Switch**: When the kill switch is enabled and Tor disconnects, the VPN TUN interface stays active but the SOCKS5 forwarder stops. This blocks all traffic since Android routes everything through the VPN interface.
- **DNS Leak Prevention**: All DNS queries are routed through Tor's DNSPort when VPN mode is active.
- **No Root Required**: The app uses Android's VpnService API exclusively.
- **No Tracking**: Zero analytics, telemetry, or proprietary SDKs.

## Supported Versions

| Version | Supported |
|---------|-----------|
| 1.0.x   | Yes       |

## Known Limitations

- Tor only supports TCP; UDP traffic is blocked at the VPN layer
- The app does not protect against malicious exit nodes (use HTTPS)
- On Android 14+, foreground service type must be declared as `specialUse`
