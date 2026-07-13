# Protocol

This directory will hold the language-agnostic wire contract between Marionette and external agents: what an agent receives (observations) and what it may send back (actions).

The current contract is the **v0 draft** ([v0-draft.md](v0-draft.md)) — explicitly a throwaway that M2.1's protocol v1 replaces. The transport, message format, and observation/action vocabulary are still open design decisions beyond that draft — see the TODOs in the main [README](../README.md). Once settled, the stable specification will be written here first, so that agents in any language can target it without reading the mod's source.

Two requirements to bake in from message one, whatever shape the protocol takes:

- **Versioning.** The protocol must carry a version and define its compatibility rules from the start — retrofitting this later breaks every existing agent.
- **Security.** Authentication and connection scope are part of the contract, not an implementation detail — see [SECURITY.md](../SECURITY.md).
