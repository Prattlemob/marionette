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

- **Loopback only.** The configured address is resolved once, checked and
  clamped to loopback, then that same address is bound. Remote binding is not
  supported.
- **Native clients only during development.** HTTP requests with any Origin
  header (including `null`) are rejected before WebSocket upgrade. Browser
  dashboards are not currently supported. Native Python clients omit Origin.
- **Local processes are trusted, not authenticated.** There are no tokens yet.
  Loopback does not isolate other users or programs on this computer. Origin
  rejection blocks browser drive-by access, not malicious native programs.
  Authentication and provisioning remain gates for mod and stable releases
  (D16). The owner approved only the exact `marionette-mc==0.1.0a1` client-only
  development alpha as an exception; this adds no authentication guarantee or
  authorization for other releases (D14).
- **One controller and bounded observers.** Observer loss cannot release a
  controller's inputs. Each connection has independent bounded queues.
- **Bounded work and emergency release.** See the protocol's `bridgeSafety`
  limits, overload/close behavior, pong watchdog and local F8 panic binding.
  Panic latches controller admission off until the player presses the
  separate re-arm key (default F9), so a reconnecting agent cannot retake
  control.
  Safety release bypasses queued commands and cancels inventory animation.
  A responsive client releases at its next tick or rendered frame; a stalled
  Minecraft client thread cannot execute a wall-clock safety guarantee.

## Supported versions

The Python client development alpha `marionette-mc==0.1.0a1` is published.
There is no stable mod release or security-support commitment yet. The alpha
uses the documented local-development trust model above.
