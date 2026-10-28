# Security policy

## Supported versions

Security fixes are made on the latest `0.99.x` release. Older releases may not
receive a backport.

## Report a vulnerability

Please do not open a public issue with an API key, a token, a device ID, a
private path, a chat transcript, or an exploitable proof of concept.

Use GitHub's **Security** tab and **Report a vulnerability** to open a private
security advisory for this repository. Include:

- the affected version and Android version;
- the smallest steps that reproduce the problem;
- the impact you observed;
- any relevant log line, with secrets and personal data removed.

If the Security tab is not available, contact a maintainer through their
GitHub profile and ask for a private channel before sending details.

## Scope

Useful reports include a way to escape the project root, execute a file that
should not run, expose a stored key, bypass the loopback-only browser bridge,
or make a skill gain a permission it was not granted. A missing feature, a
crash without a security impact, or a deliberately unsupported root workflow
is a normal bug report, not a vulnerability.

## What to expect

Maintainers will acknowledge a report when they can, ask for the smallest
reproduction, and coordinate a fix and disclosure. This is a small project;
there is no guaranteed response time or bounty.
