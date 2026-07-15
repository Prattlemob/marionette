# Protocol

The language-agnostic wire contract between Marionette and external
agents. The current contract is **[v1.md](v1.md)** — agents in any
language target that document, not the mod's source.

## Versioning

- The protocol version is a **single integer** (currently 1). It bumps
  **only on breaking changes**: removing or renaming a field or message,
  changing a type or semantic, tightening validation (D6).
- Everything additive ships as **capability flags** in the `hello` reply
  plus new optional fields/messages. Agents feature-detect
  (`capabilities.x` is true) — never version-sniff.
- Unknown non-reserved fields are ignored on both sides; that tolerance
  is the compatibility mechanism that makes additive change safe.
- The mod jar versions independently (semver); the `hello` reply carries
  both (`version`, `mod`).

## Change process

1. Spec first: the change lands in `protocol/` (this directory) before
   any implementation lands in `src/` (see CLAUDE.md).
2. Anything that is a design decision — not just a field addition — is
   recorded in [docs/decisions.md](../docs/decisions.md).
3. Breaking changes bump the integer version and document a migration
   note in the spec; additive changes add a capability flag or extend an
   existing message.
4. Reference agents in `examples/` are updated in the same change; they
   are the conformance spot-check.

## History

- **v1** ([v1.md](v1.md)) — current. Envelope with reserved `type`/`id`,
  versioned handshake with capability flags and `role`, error taxonomy.
- **v0** ([v0-draft.md](v0-draft.md)) — superseded throwaway draft that
  the Phase 1 walking skeleton spoke.

Security posture (loopback-only binding, single controller) is part of
the contract — see [SECURITY.md](../SECURITY.md).
