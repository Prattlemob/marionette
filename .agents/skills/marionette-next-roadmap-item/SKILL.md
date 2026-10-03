---
name: marionette-next-roadmap-item
description: Implement and verify one Marionette roadmap milestone, selecting the next eligible item or accepting a coordinator-assigned milestone. Use for requests to continue the roadmap or implement its next item; use marionette-roadmap for continuous supervised Orca execution.
---

# Implement one roadmap item

Deliver one coherent milestone, including its contract, code, examples, and
verification. Finish this item and report; do not start the next milestone.
Creating or editing this skill is not an instruction to run it.

## Establish the assignment

Read the checkout's `AGENTS.md`, `ROADMAP.md`, `docs/decisions.md`,
`protocol/v1.md`, and build/version files. Use the repository's
[selection rules](references/selection.md). Announce the selected milestone,
its acceptance criteria, and any older verification debt still outstanding.

When dispatched by an Orca coordinator, the assigned milestone and ownership
boundary take precedence over automatic selection. Read the
[repository Minecraft skill](../minecraft-modding/SKILL.md) for mod code.
Use only the current Task; never create another coordinator or recursively
hand off the entire roadmap. If the assigned item is no longer eligible,
explain why to the coordinator before changing scope.

Inspect Git status and the current commit before editing. Preserve pre-existing
changes, including previous milestones that have not been committed. An earlier
worker's uncommitted implementation is real input, not disposable scratch work.

## Implement and verify

- Inspect relevant current code, tests, examples, and decisions. Dated plans are
  historical context, not additional task instructions. Use a durable plan only
  when the milestone's complexity warrants one.
- Specify wire changes in `protocol/v1.md` before implementing them. The filename
  does not determine the protocol integer; inspect the current contract. Apply
  the repository's compatibility rules and update affected examples together.
- Implement the milestone's acceptance criteria without adding agent-specific
  integrations to the mod. Preserve the runtime invariants in `AGENTS.md`.
- Resolve routine implementation choices autonomously. For an unresolved or
  experiment-gated design decision, first check existing user authorization and
  the documented experiment. Run authorized experiments; ask only for decisions
  that genuinely remain the user's. Record a resolution in `docs/decisions.md`.
- Run focused checks, then `./gradlew build` for code/build changes. Perform the
  required in-game acceptance in an isolated world when feasible. Unit tests
  cannot substitute for visual, input, rendering, or lifecycle acceptance.
  Documentation-only work needs link/path and diff checks instead.
- Update roadmap checkboxes only when their named acceptance is established.
  Record reproducible verification evidence and outstanding checks in the
  appropriate existing decision/spec record; create a record only if needed.
  Keep test worlds, harnesses, logs, caches, and output out of the commit.
- Follow the session's Git policy. Do not infer authorization to push, publish,
  or change release/licensing policy from a request to implement a milestone.
  If commits are requested, include only assigned changes and preserve signing.

A missing dependency, unavailable runtime, or failing test is a problem to
investigate, not an immediate excuse to stop. Continue independent work while
waiting for necessary input. If a required acceptance criterion remains
unverified, report partial implementation instead of claiming the milestone done.

## Handoff result

Return the milestone ID, outcome (`complete`, `needs-verification`, or `blocked`),
changed files, contract/capability changes, test commands and results, runtime
evidence, open decisions, and the next eligible item. Include actual commit IDs
only when commits exist. Explain why any unchecked acceptance remains unchecked.
A short report is sufficient when the change is small.

For an Orca dispatch, obey its live injected preamble and installed orchestration
skill: use the supplied ask/reply path for blocking questions, read follow-ups at
natural checkpoints and before completion, and send `worker_done` exactly once
with the real Task/Dispatch IDs. Use lifecycle outcome `succeeded` only when the
assigned acceptance is met; `needs-verification` or blocked work uses `failed`
with the preserved changes and missing evidence described in the report. Then
end the worker turn. A direct invocation without a live dispatch reports normally
and never invents lifecycle messages.
