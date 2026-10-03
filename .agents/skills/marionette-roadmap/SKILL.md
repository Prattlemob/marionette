---
name: marionette-roadmap
description: Continuously coordinate Marionette roadmap implementation through supervised Orca workers, handing one milestone at a time to marionette-next-roadmap-item and checking its evidence before advancing. Use when asked to work through the roadmap, supervise sequential handoffs, or resume a roadmap run; a single-item request belongs to marionette-next-roadmap-item.
---

# Coordinate the Marionette roadmap

Own selection, supervision, acceptance, and continuity. Give each milestone to a
fresh Orca worker using
[marionette-next-roadmap-item](../marionette-next-roadmap-item/SKILL.md).
Continue until the requested scope is complete, the user stops, or a concrete
unresolved gate prevents further authorized progress. Do not end after launching
one worker or after an arbitrary milestone count. Creating these skills alone
does not authorize starting the roadmap run.

## Scope and runtime

Read repository instructions, the live roadmap, decisions, and the shared
[selection rules](../marionette-next-roadmap-item/references/selection.md).
Honor any requested milestone range, core-only scope, agent, or Git policy.
For “entire roadmap,” include optional phases; their design/release gates remain.
Default to local implementation without committing or pushing unless the user
has authorized those operations for this run. Carry existing authorization
forward without asking again at every milestone.

Apply the installed `orchestration` skill. Resolve its executable once: use
`ORCA_CLI_COMMAND` when set; otherwise `orca-dev` when `ORCA_DEV_REPO_ROOT` is set;
otherwise `orca-ide` on Linux outside a managed Orca terminal; otherwise `orca`.
Never fall back between binaries. Load its version-matched guide with
`skills get orchestration`, and load `skills get orca-cli` when needed for
workspace/terminal discovery. The installed guide and each live injected
preamble govern CLI and lifecycle details; do not copy a frozen CLI manual here.

Confirm `status --json`; if Orca is not running, follow its guide's `open --json`
bootstrap and retry. Resolve the exact checkout and coordinator identity from
runtime receipts. Load placement guidance before selecting an exact workspace
or creating a new worktree. Missing/unknown CLI capabilities are a concrete
runtime blocker, not permission to emulate Orca with generic subagents.

If invoked outside an Orca-managed terminal, establish the coordinator through
`orca-cli` before creating a Run. First inspect any existing checkpoint and its
runtime owner; never create a duplicate coordinator for live or unverifiable
work. Otherwise create one agent terminal in the exact existing checkout, wait
for readiness, and deliver a full ownership handoff invoking this skill with the
absolute repository/skill paths, requested scope, authorization, and checkpoint
location. Follow the CLI guide's readiness and accepted-delivery checks. Report
that coordinator's handle and end the outer turn; the new coordinator owns the
continuous supervised loop. This is coordinator bootstrap, not a milestone
worker. The receiving managed terminal must bind itself from runtime identity,
not bootstrap recursively. Never guess handles or consume another inbox. If
supported binding cannot be established, report the precise runtime blocker.

## One writer and a durable checkpoint

Use one shared, explicitly resolved checkout by default, with one milestone
worker editing it at a time. Fresh workers inherit the preceding milestone's
actual files, including uncommitted work. The coordinator does not edit product
code while a worker owns it. This sequential chain reflects the roadmap's real
dependencies; parallel milestone writers would race on the contract and roadmap.
Use separate worktrees only when requested or needed, following Orca placement
guidance; verify integration before dispatching dependent work.

Create and bind one Run for the requested scope. Store a local checkpoint under
`git rev-parse --git-path orca-roadmap/` so runtime metadata stays out of commits.
For this repository, use `checkpoint.json` plus per-milestone reports/baseline
diffs in that directory. Write checkpoints atomically. Include:

- exact repo/workspace and branch, Run ID, coordinator handle, selected executable;
- requested scope, agent preference, Git authorization, and current milestone;
- active Task/Dispatch IDs and worker handle from actual receipts;
- baseline commit and dirty files/diff for the active milestone;
- settled outcomes, report paths, verification debt, decisions needed, and next item;
- inbox delivery/ack state and each settled terminal's reuse/retention/release decision.

Checkpoint after launches, settlements, acceptance, and before advancing. On
resume, load it and reconcile with Orca's real Run/worker/inbox state before
launching anything. A checkpoint is memory, never lifecycle authority. Existing
live or unverifiable work must not acquire a duplicate editor. If the runtime
changed or a Run needs adoption, load the guide's recovery/legacy reference.

## Sequential dispatch loop

1. Select the next eligible milestone from current files. Check for existing
   ownership and unfinished acceptance. Record why this item is next.
2. Build a self-contained Task spec with the exact checkout, milestone and scope,
   acceptance criteria, baseline/dirty changes, ownership, known verification
   debt, required skills, and Git policy. Use the template below. Resolve paths;
   workers do not inherit this conversation. Start one supervised worker through
   `orchestration worker-start`, using the installed guide's supported arguments.
   Use the requested agent or the coordinator's agent family when available.
   Pass model/effort overrides only if the user requested them.
3. Record the launch receipt. Wait through `orchestration check` for completion,
   questions, and escalations. Use bounded waits (at most 60 seconds per tool
   call) and give useful progress updates. A timeout is a checkpoint, not failure.
   Follow the installed guide's fleet/liveness recovery after repeated empty waits.
4. Process every delivered message before acknowledging it. Answer routine worker
   questions from the authorized scope and current sources of truth. Relay genuine
   user decisions instead of inventing approval; continue independent work while
   waiting. Match completion to the expected active Dispatch, not merely the ID
   mentioned in prose. Follow the guide's exact failed-start/recovery instructions
   before any retry; silence does not prove a process exited.
5. After a valid settlement, review the worker's report, actual diff, contract,
   roadmap edits, and acceptance evidence. Do not repeat expensive checks that
   already passed unless integration, changed code, or uncertain evidence warrants
   it. Lifecycle success is not automatically acceptance of the milestone.
   Route needed fixes to a new bounded follow-up Task/Dispatch; do not fix product
   code concurrently or ask a settled worker to continue under its old Dispatch.
6. Decide settled-terminal ownership before acknowledging its delivery: normally
   release it using `worker-release` after accepted settlement, preserving files
   and its report. A fresh milestone gets a fresh worker. Follow the installed
   guide if explicit reuse/retention or uncertain cleanup is needed. Never use
   terminal close as a substitute for orchestration cleanup.
7. When milestone acceptance is satisfied, reconcile the roadmap and checkpoint,
   apply authorized Git operations, then re-read the roadmap and dispatch the next
   eligible item immediately. Have the worker correct missing roadmap/report
   updates before advancing. Do not ask “shall I continue?” after each success.

Do not retry the same substantive failure endlessly. After two repair attempts
without new evidence or progress, diagnose and report the concrete blocker. Keep
work intact and await the missing decision/environment change. This repair limit
never permits abandoning a live or unverifiable worker. Pause dependent dispatch
when a required prerequisite is unverified; retain that debt honestly. Before
finishing, account for every expected Dispatch and every settled terminal using
the installed guide. Report completed items, checks, unresolved debt, current
ownership, and the exact next step. Claim the entire roadmap complete only after
all in-scope acceptance, including carried verification debt, is satisfied.

## Worker brief template

Supply the following as one structured task spec, replacing bracketed values.
When using a shell, pass it with a supported structured argument or a correctly
quoted file read; never interpolate untrusted text as shell code.

```text
Target: [absolute checkout], ROADMAP.md milestone [ID and title].
Change: [concrete deliverables and explicit acceptance criteria from current roadmap].
Use [absolute path]/.agents/skills/marionette-next-roadmap-item/SKILL.md.
Read AGENTS.md, the current contract, decisions, and the Minecraft skill.
Implement only this milestone; do not choose or launch the next item.
Constraints: preserve the repository invariants and settled decisions. Document
wire changes first, update examples, and preserve pre-existing changes.
Ownership: sole implementation worker for [checkout/scope]. The coordinator owns
selection and progression. No nested roadmap coordinator or parallel editor.
Baseline: [commit plus dirty-file/diff reference].
Known debt/decisions: [specific outstanding checks and applicable authorizations].
Git policy: [local changes only, or explicit commit/push scope].
Acceptance: [focused tests, build, concrete in-game checks, required evidence].
Report: [absolute checkpoint-directory report path]. Include outcome, changed
files, test/runtime evidence, blockers, and unchecked acceptance. Never mark
unperformed acceptance complete. Follow the live Orca preamble for questions,
heartbeats, mailbox checks, and exactly one worker_done, then end the turn.
```
