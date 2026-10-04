# Documentation

Project documentation lives here: design notes, architecture decisions, and guides for agent authors.
New to Marionette? Start with the [quickstart](../README.md#quickstart) and the [examples](../examples/README.md).

Design notes:

- [threading.md](threading.md) — the threading model: what runs on the Netty thread and what on the client thread, and how commands, observations, events and releases cross between them.
- [safety-model.md](safety-model.md) — the safety model: the protective layers, release-all, human precedence, panic and the timing guarantees.
- [safety-state-machine.md](safety-state-machine.md) — the idle-safe state machine: when an agent may drive, human precedence modes and lifecycle releases.
- [protocol-evolution.md](protocol-evolution.md) — why the protocol looks the way it does, its history and capability timeline, the rules for future changes and the (not yet authorized) finalization checklist.
- [configuration.md](configuration.md) — every setting in `marionette-client.toml` with its default.

Reference and records:

- [../protocol/v1.md](../protocol/v1.md) — the current wire contract (protocol 2).
- [decisions.md](decisions.md) — the running record of design decisions (settled, experiment-gated, deferred, open).
- [../ROADMAP.md](../ROADMAP.md) — the ordered implementation roadmap: phases, milestones, and definitions of done.
- [../SECURITY.md](../SECURITY.md) — the security posture and how to report a vulnerability.
- [specs/](specs/) — dated milestone designs and experiment criteria.
- [plans/](plans/) — historical implementation plans and verification procedures.
- [../AGENTS.md](../AGENTS.md) — shared guidance for coding agents and package boundaries.

The dated specs and plans preserve implementation context, including old code
snippets and checklists. Consult the current protocol, decisions, roadmap, and
source before reusing them. No workflow plugin is required to work on this project.

The [main README](../README.md) is the best current overview of what Marionette is.
