# Security Policy

## Supported versions

Security fixes are prioritized for the latest stable release of Kadb and its
published modules. Fixes for older versions are not guaranteed. Reports affecting
unreleased code are also welcome; include the relevant commit when reporting them.

## Reporting a vulnerability

Please report suspected vulnerabilities privately to
[flyfishxu@outlook.com](mailto:flyfishxu@outlook.com), with `Kadb security report` in
the subject. Do not publish exploit details in a public issue or pull request
before we have had an opportunity to investigate and coordinate disclosure.

Include, where available:

- The affected module and version or commit.
- The host OS, Java or Android version, and connection type (USB or TCP).
- A description of the impact and any required access or preconditions.
- Reproduction steps or a minimal proof of concept using devices you control.
- Relevant logs with credentials, pairing codes, private keys and personal data removed.

Potential security issues include authentication or TLS validation flaws,
unintended disclosure of host credentials, and unsafe handling of untrusted ADB,
USB or discovery data. Ordinary connection problems and feature requests can be
reported through GitHub issues; if you are unsure whether an issue is security
related, use the private contact above.

## Handling reports

We will assess reports and coordinate any fixes and disclosure with the reporter.
Response and resolution times depend on maintainer availability and the issue's
complexity. Please keep vulnerability details private while we investigate and
agree on an appropriate disclosure timeline.

