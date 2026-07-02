# Security Policy

## Reporting a vulnerability

Please report vulnerabilities privately via GitHub's
[private vulnerability reporting](https://github.com/Prattlemob/marionette/security/advisories/new)
(Security tab → "Report a vulnerability") rather than opening a public issue.
We will acknowledge reports as quickly as we can; there is no formal SLA yet.

## Security posture

Marionette is, by design, a control channel into a running Minecraft client.
That makes the security of the mod↔agent boundary a first-class design
requirement, not an afterthought. The transport has not been chosen yet, but
whatever is chosen must answer — before release — at least:

- Does it accept connections from beyond the local machine by default?
- How are connecting agents authenticated?
- What can a connected agent *not* do?

These questions will be addressed in the [protocol specification](protocol/)
as it is written.

## Supported versions

Pre-implementation — there are no releases yet, so no versions receive
security support.
