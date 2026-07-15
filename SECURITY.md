# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately via GitHub's
[private vulnerability reporting](https://github.com/Prattlemob/marionette/security/advisories/new)
(Security tab → "Report a vulnerability") rather than opening a public issue.
We will acknowledge reports as quickly as we can; there is no formal SLA yet.

## Security posture

Marionette is, by design, a control channel into a running Minecraft client.
That makes the security of the mod↔agent boundary a first-class design
requirement, not an afterthought. The current posture, as specified in
[protocol/v1.md](protocol/v1.md):

- **Loopback only.** The WebSocket bridge binds `127.0.0.1` exclusively; it
  never accepts connections from beyond the local machine. (A config file
  arrives in M2.2 with the same enforced-safe default; any explicit opt-out
  gate is an M5.1 decision.)
- **Single controller.** Exactly one controlling connection at a time; a
  second connection is rejected with a documented error and closed.
- **No authentication yet.** Loopback-only binding is the current trust
  boundary. Authentication must be revisited before any non-loopback binding
  is ever allowed — that question blocks release of such a feature, per
  [docs/decisions.md](docs/decisions.md).
- **Bounded agent power.** A connected agent can only issue the documented
  protocol commands (in v1: movement, camera, release). Malformed input
  never crashes the client and never alters held controls; on disconnect,
  socket error, or watchdog trip, all controls release within one client
  tick and the player idles.

## Supported versions

There are no releases yet, so no versions receive security support.
