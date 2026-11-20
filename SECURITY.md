# Security Policy

## Supported versions
Only the latest release is supported.

## Reporting a vulnerability
Please report security issues privately to the maintainers instead of opening a public issue.

Use GitHub's private vulnerability reporting for this repository:
- Open the repository's **Security** tab.
- Choose **Report a vulnerability**.

Include:
- Steps to reproduce.
- Affected version/commit.
- Device/Android version.
- Impact assessment.

We aim to respond within 7 days.

## Scope notes
- The app is designed for a trusted LAN. The pairing token is the primary access control.
- The embedded HTTP server binds all interfaces; do not expose the TV to an untrusted network.
- APK auto-install from uploads has been removed. Unknown files are downloads only.