# Security

Herdroid holds an SSH key that can run commands on your computers, so security bugs matter
more than usual here.

## Reporting a problem

Report security problems privately through
[GitHub security advisories](https://github.com/artogahr/herdroid/security/advisories/new).
Do not open a public issue.

## What Herdroid protects

- The SSH private key is created in the Android Keystore and cannot be exported.
- Each server's host key is pinned on first use. A changed key stops the connection and
  shows both fingerprints.
- Herdroid connects only to servers you add, plus local network probes on the Add server
  screen.

## What it does not protect

- Anyone who can unlock the phone can use Herdroid, and with it your agents and shells.
- A server you add can show Herdroid any content. Prompt cards only send the arrow keys
  and Enter you would type yourself, but read what you approve.
