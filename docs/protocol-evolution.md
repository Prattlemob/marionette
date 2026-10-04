# Protocol rationale and evolution

Why the wire contract looks the way it does, how it has changed, and how it
may change next. The contract itself is [protocol/v1.md](../protocol/v1.md)
(protocol **2**; the file keeps its original path); the versioning rules are in
[protocol/README.md](../protocol/README.md); the decisions cited are in
[decisions.md](decisions.md).

## Design choices

**WebSocket on loopback, JSON text frames (D1, D1a).** Every mainstream
language has a WebSocket client, so agents need no Marionette library. The
connection gives framing, ordered delivery and built-in ping/pong, which the
liveness watchdog reuses. JSON keeps the contract readable in logs and easy
to test. Binary frames are reserved for bulk data (block-scan deltas, pixels)
if bandwidth ever demands them. The server is Netty, already in the game, with
the WebSocket codec bundled inside the mod jar.

**A flat envelope with two reserved keys.** Every message is one object whose
`type` selects its meaning; an optional `id` is echoed by the reply it causes.
Unsolicited messages (`observation`, `event`) never carry an `id`, so they can
never be mistaken for an answer.

**An integer version plus capability flags (D6).** The protocol integer
changes only for breaking changes. Everything additive is a capability flag in
the `hello` reply plus new optional fields or messages; agents feature-detect
(`capabilities.x` is truthy) and never compare versions. Both sides ignore
unknown non-reserved fields, which is what makes additive change safe. The mod
version is separate and informational.

**Set-and-hold controls (D4).** An agent states what is held (`forward: true`)
rather than streaming key presses, and the mod applies the state each tick
through the same input path a keyboard uses. Holding is cheap on the wire and
makes "release everything" a single, total operation. One-shot actions are
explicit `tap`s.

**Composite observations with section selection (D2).** One frame per tick
carries the sections a session asked for (`player`, `inventory`, `target`,
`world`, `entities`), each sampled at most once per tick and serialized once
per selection. Observations are state, so a slow reader may skip frames: only
the latest is kept.

**Events are separate from state (D17).** Things that happen (damage, death,
pickups, chat) cannot be coalesced away, so they travel on their own opt-in,
sequenced, bounded stream. Each event says whether it is the server's account
or a client prediction (`basis`), and gaps in `seq` are detectable. There is
no exactly-once guarantee across reconnects.

**Requests with correlated replies for anything that can fail.** Inventory
actions, scans, chat, respawn and `status` return a reply carrying the
request's `id`, or an `error` with a machine-readable `code` and, where useful,
`reason` and `limits`. Movement and camera commands are silent: their effect
is observed, not acknowledged.

**Intent-level, menu-generic inventory (D8).** Agents address slots through
the open menu's descriptor (`hotbar.0`, `main.3`, numeric menu slots) and ask
for intents (move, swap, equip, drop, craft); the mod performs the clicks. The
same requests therefore work for vanilla and modded containers that pass the
storage analysis (D19, D23).

**Roles, not permissions (D7).** One controller drives; observers read.
Observers exist for dashboards, commentators and loggers, and can never
release or disturb the controller.

**Safety is part of the contract.** Loopback binding, Origin rejection, the
pong watchdog, bounded queues, panic and human precedence are normative
protocol behavior, not implementation details: agents must answer pings, must
not retry a latched panic, and must treat overload outcomes as unknown.

**The transport is not an AI framework (D13).** MCP servers, LLM tool
schemas and agent planners belong in harnesses built on top of the protocol.

## History

| Protocol | Status | What changed |
|---|---|---|
| v0 ([draft](../protocol/v0-draft.md)) | superseded | Throwaway walking-skeleton protocol: hello, held controls, flat position frames. |
| 1 | superseded | The envelope with reserved `type`/`id`, the versioned handshake with capability flags and `role`, the error taxonomy, and the first capabilities built on them (`configure`, observers, taps, camera modes, interaction). |
| **2** | **current** | Composite observations: the frame root keeps `tick`, and player state moved under `player` with per-session `sections`. Everything since is additive. |

Moving to 2 was the one breaking change so far: protocol 1 clients must offer
version 2 and read position and rotation from `observation.player`.

## Capabilities in protocol 2

Each flag was added without a version bump. A mod that lacks a flag lacks the
feature; agents must check.

| Capability | Adds | Milestone |
|---|---|---|
| `configure` | per-session `configure` (cadence, sections, events) | M2.1 |
| `observer` | read-only observer connections | M2.4 |
| `tap` | one-shot `input.tap` | M3.1 |
| `camera` | `look` delta and smooth modes | M3.2 |
| `interact` | `attack`, `use`, `hotbar` | M3.3 |
| `inventory`, `inventoryAnimation` | inventory requests; visible cursor animation | M3.4 |
| `playerState` | the `player` section and `sections` selection | M4.1 |
| `bridgeSafety`, `panicLatch` | bounded transport, emergency behavior; panic latch | M5.1a |
| `events` | the one-shot event stream | M4.6 |
| `inventoryState` | the `inventory` section | M4.2 |
| `inventoryStorage` | generic storage support and rejection reasons | M3.5 |
| `targetState`, `worldState` | the `target` and `world` sections | M4.3 |
| `entityState` | the `entities` section | M4.4 |
| `blockScan` | bounded `scan` requests | M4.5 |
| `crafting` | crafting and processing menus, counted moves, `craft` | M3.6 |
| `swapHands`, `respawn`, `chat`, `playerIdentity`, `playerActivity` | gameplay and social controls and fields | M3.7 |
| `humanPrecedence` | precedence modes, `control` events, `human_paused` | M5.1 |
| `status` | the `status` query and hello `agent` name | M5.2 |

Reserved names, not yet implemented: capabilities `baritone`, `server`,
`vision`, `blockChanges` and `blockScanBinary`, and the role `director`.

## Compatibility with published clients

The published Python client `marionette-mc==0.1.0a1` was built against
protocol 2 before most of these capabilities existed. It keeps working against
the current mod because every addition is opt-in: a session receives events,
extra sections and new reply types only after asking for them, and 0.1.0a1
never asks. (That client ends its session on an unknown message type, so
unsolicited new message types must stay opt-in.) Each milestone since has
re-checked 0.1.0a1 against the current mod.

## Rules for future changes

1. Specify the change in `protocol/` before implementing it, and update the
   examples and the Python client's shared fixtures in the same change.
2. Prefer additive: a new capability flag, optional fields, opt-in messages.
   Never send a new unsolicited message type to a session that did not opt in.
3. A breaking change (removing or renaming anything, changing a type or a
   meaning, tightening validation) bumps the integer and adds a migration note.
   Protocol 1 was rejected outright rather than kept alongside 2; whether
   a later bump keeps a transition period is decided with that change.
4. Record design decisions, not just field additions, in `docs/decisions.md`.
5. Safety behavior may become stricter only through the same process; it is
   part of the contract agents rely on.

## Finalization checklist (not yet authorized)

The roadmap's M9.1 item "protocol/ finalized as v1 and tagged" predates
protocol 2. Finalizing means freezing the current contract as the first
release's protocol and tagging it. No tag has been made: tags and releases need
the owner's explicit authorization. When authorized:

- [ ] Decide the naming: the release protocol is the integer **2**. Choose
      whether the canonical document stays at `protocol/v1.md` (with the
      current note) or moves to `protocol/v2.md` with a redirect, and the tag
      name (for example `protocol-2`), and record the choice in decisions.md.
- [ ] Resolve the release gates that could change the wire before freezing:
      authentication and provisioning (D16) may need a new handshake field or
      a breaking change; settle it first.
- [ ] Review the reserved names (`baritone`, `server`, `vision`,
      `blockChanges`, `blockScanBinary`, `director`) and keep or drop each.
- [ ] Confirm every example and the Python client's shared fixtures
      (`python/tests/fixtures/protocol2.json`, `events-wire.json`) match the
      document, and that `PythonCompatibilityTest` passes.
- [ ] Verify the examples against the release jar (M9.1), not only a locally
      built one.
- [ ] Document the mod-version ↔ protocol-version relationship in the
      changelog policy (M9.2, D12).
- [ ] With authorization, create a signed tag on the reviewed commit and never
      move it; later changes follow the rules above.
