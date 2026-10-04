# Marionette Roadmap

Ordered, dependency-aware plan from empty scaffold to full intended behaviour.
The execution-order overrides below take precedence over numeric order; each milestone is
independently buildable, testable, and demoable. Design decisions referenced as
`D<n>` are recorded in [docs/decisions.md](docs/decisions.md).

**Tiers:** Core = required for the mod to fulfil its purpose. Optional =
additive layers (Baritone, server component, vision) that the core never
depends on.

**Assumptions:**

1. One **controlling** agent connection at a time (D7); additional read-only
   `observer` connections arrive in M2.4 behind a capability flag, capped by
   config.
2. The protocol is text JSON end-to-end for the core; binary WebSocket frames
   are reserved for where bandwidth demands it (block scan, framebuffer).
3. Dev-loop testing happens in a local singleplayer world via
   `./gradlew runClient`.
4. The walking skeleton proves **both** pipe directions (a minimal observation
   out, actions in).
5. `protocol/` docs are Markdown + JSON examples.

---

## Cross-project execution order (2026-10-03)

Keep milestone IDs and completed history. **M2.5 (published client) and M5.1a
(bridge safety) are complete; M4.6 (one-shot events), M4.2 (inventory
observation) and M3.5 (modded storage) are complete.** The remaining
implementation order is **M4.3 → M4.4 → M4.5 → M3.6 → M3.7 → M5.1 → M5.2**.
Further package publication retains D12/D14 decision gates; a blocked release
does not prevent independent safety/perception work. M3.3's outstanding visual
acceptance remains debt and must be reconciled for milestones requiring it.
M3.8 and M4.7 are optional extensions after the core, selected by requested scope.

Consumers develop alongside the mod. Every consumer requirement needs a provider
capability, Python-client support and an end-to-end acceptance scenario. Product
implementation completion does not establish consumer integration. A combined
coordinator may assign single milestones; autonomous repo-only coordinators must
check shared ownership before dispatching. No agent persona, planning, reflex
policy, overlays or highlight selection belongs in this mod.

## Phase 0 — Foundations (Core)

### M0.1 — Verified do-nothing scaffold

- **Goal:** Confirm the existing scaffold builds, runs, and loads as mod id
  `marionette` on NeoForge 21.8.x / MC 1.21.8.
- **Delivers:** A green baseline: local build, dev client launch, CI passing.
- **Prerequisites:** None.
- **Definition of done:** `./gradlew build` succeeds; `./gradlew runClient`
  launches a rendered client; the mod list shows Marionette; a startup log line
  proves the mod entrypoint ran; the GitHub Actions build is green.
- **Tier:** Core.

Items:

- [x] `./gradlew build` passes locally and in CI
- [x] Mod entrypoint class exists and logs a startup line
- [x] `runClient` reaches the title screen with Marionette in the mod list

### M0.2 — Client lifecycle & tick hook

- **Goal:** Establish the mod's heartbeat: a client-tick hook and world
  join/leave awareness.
- **Delivers:** A `MarionetteClient` (or similar) singleton that receives every
  client tick, knows whether a player is in a world, and logs join/leave
  transitions. This is the anchor everything else hangs off.
- **Prerequisites:** M0.1.
- **Definition of done:** Log output shows tick counting only while in a world,
  and clean "entered world" / "left world" transitions when joining and
  disconnecting from a singleplayer world.
- **Tier:** Core.

Items:

- [x] Client tick event handler wired (pre/post tick as appropriate)
- [x] In-world vs. menu state tracked; handlers no-op outside a world
- [x] World join/leave detection with log evidence
- [x] Mod marked client-side (no-op / not required on dedicated servers)

---

## Phase 1 — Walking Skeleton (Core) — *retire the biggest risk first*

### M1.1 — Input injection: set-and-hold control state (scripted, no network)

- **Goal:** Prove the mod can move and steer the real, rendered player from
  code.
- **Delivers:** A `ControlState` holder (movement axes, sneak/sprint/jump,
  yaw/pitch intent) applied to the player every client tick, plus a temporary
  hardcoded test script that exercises it. No smoothing, no network — just
  authoritative proof of actuation.
- **Prerequisites:** M0.2.
- **Definition of done:** With a debug toggle enabled, the player **visibly
  walks forward for ~3 seconds, turns 90°, and walks again** in a rendered
  singleplayer world, with no keyboard input, and vanilla input resumes cleanly
  when the script ends.
- **Tier:** Core.
- **Decision:** **D4 (experiment-gated)** — injection mechanism. Prototype both
  the input-path mixin and the KeyMapping-forcing variant during this
  milestone; a candidate wins by passing all D4 pass criteria (see
  docs/decisions.md).

Items:

- [x] `ControlState` model: held controls persist until changed (set-and-hold)
- [x] D4 experiment: mixin variant vs. KeyMapping variant, judged against the
      documented pass criteria; result recorded in docs/decisions.md
- [x] Movement injection (forward/back/strafe) applied each tick
- [x] Raw camera rotation injection (set yaw/pitch — smoothing comes later,
      M3.2)
- [x] "Release all" operation that returns input to vanilla instantly
- [x] Hardcoded demo script behind a debug toggle (deleted/disabled after M1.3)

### M1.2 — Minimal localhost bridge

- **Goal:** Get one external process talking to the client: JSON in, JSON out,
  everything marshalled to the tick loop.
- **Delivers:** A localhost-only **WebSocket** server (D1) accepting a single
  connection; minimal `hello` handshake; inbound action messages queued and
  drained on the client tick (never touching game state on the network
  thread); a minimal per-tick observation out (position + rotation) proving
  the return path. A `protocol/v0-draft.md` describing exactly these messages.
- **Prerequisites:** M1.1.
- **Definition of done:** A short Python script (stdlib + a ws client library)
  connects, receives ticking position updates, sends `{"forward": true}`, and
  the player visibly walks; sending `{"forward": false}` stops them.
- **Tier:** Core.
- **Decision:** **D1a** — WebSocket server implementation: Netty-based using
  Minecraft's bundled Netty (recommended) vs. shaded `Java-WebSocket`. Decide
  at the start of this milestone.

Items:

- [x] D1a decided and recorded; WebSocket server bound to loopback,
      single-connection accept
- [x] Inbound messages parsed on network thread, applied only on client tick
- [x] Minimal handshake (`hello` with protocol version)
- [x] Minimal observation out: position + rotation per tick
- [x] `protocol/v0-draft.md` documenting the above verbatim
- [x] Minimal Python probe script in `examples/`

### M1.3 — Walking skeleton demo + safety baseline

- **Goal:** An external process drives the rendered player through a scripted
  pattern, and losing the agent never leaves controls stuck.
- **Delivers:** The end-to-end proof, plus the non-negotiable safety rule: on
  disconnect, error, or malformed input, all controls release and the player
  idles. This ships *in* the skeleton, not after it.
- **Prerequisites:** M1.2.
- **Definition of done:** An `examples/` script makes the player **visibly walk
  a square and return to roughly its start**; `kill -9`-ing the script mid-walk
  stops the player within one tick (it must NOT keep walking forward);
  reconnecting works without restarting Minecraft.
- **Tier:** Core.

Items:

- [x] `examples/walk_square.py` (or similar) driving the square via the bridge
- [x] Disconnect / socket error → immediate release-all + idle
- [x] Malformed message → error response (or drop) without crashing the
      client; controls unaffected or released
- [x] Reconnect works repeatedly in one game session
- [x] Leaving the world (menu/death screen) suspends actuation safely

> **Milestone gate:** if Phase 1 can't be made reliable, the project's premise
> is wrong. Everything after this is breadth, not risk.

---

## Phase 2 — Protocol & Bridge Hardening (Core)

### M2.1 — Protocol v1: source of truth in `protocol/`

- **Goal:** Turn the v0 draft into a versioned, documented contract that all
  later milestones extend rather than invent.
- **Delivers:** `protocol/` as the single source of truth: message envelope
  (type, id, payload), versioned handshake per **D6** (single integer protocol
  version + capability flags) with negotiation/rejection, `role` field per
  **D7** (only `controller` accepted in v1), error message shape, and a
  documented process for evolving the protocol.
- **Prerequisites:** M1.3.
- **Definition of done:** An agent connecting with an unsupported version gets
  a clean, documented rejection; every message the mod currently sends/accepts
  appears in `protocol/` and matches the implementation exactly (spot-checked
  by the example script).
- **Tier:** Core.

Items:

- [x] Message envelope spec (type, optional request id for request/response
      pairs)
- [x] Handshake spec: integer protocol version, capability flags, `role`
      field, rejection path (D6, D7)
- [x] Second controller connection rejected with documented error (D7)
- [x] Error message spec (code, human-readable message, offending input echo)
- [x] `protocol/README.md` explains versioning & change process
- [x] Implementation brought into exact conformance; example script updated

### M2.2 — Configuration file

- **Goal:** Make the bridge and its behavior operator-configurable without
  recompiling.
- **Delivers:** A NeoForge client config (TOML) covering: port, bind address
  (default + enforced-safe `127.0.0.1`), observation rate divisor, scan radii
  caps, feature toggles (bridge on/off; later: Baritone, vision), and log
  verbosity.
- **Prerequisites:** M1.3.
- **Definition of done:** Changing the port in the config file and restarting
  moves the listener; setting a non-loopback bind address is refused (or
  clamped) with a loud log warning (see M5.1).
- **Tier:** Core.

Items:

- [x] Config schema defined and documented in README
- [x] Port, bind address, enable/disable toggle
- [x] Observation rate + radii caps as config values with sane defaults/limits
- [x] Feature toggle scaffolding for future optional layers
- [x] Suppress-pause-on-focus-loss toggle so an agent-driven client keeps
      running (and streaming) while unfocused (see docs/decisions.md; M5.1
      decides per-mode behavior)

### M2.3 — Connection lifecycle, framing, rate & backpressure

- **Goal:** Make the bridge boringly reliable under real load and misbehaving
  agents.
- **Delivers:** Configurable observation rate (every N ticks) and an explicit
  backpressure policy: a slow-reading agent gets observation frames
  **coalesced to latest** (never an unbounded queue, never a stalled client
  thread). WebSocket ping/pong used as the liveness signal. Clean shutdown of
  the listener on game exit.
- **Prerequisites:** M2.1, M2.2.
- **Definition of done:** An agent that connects and never reads is
  demonstrated (via log counters) to cause dropped observations, zero
  client-FPS impact, and bounded memory; quitting Minecraft closes the socket
  cleanly; a soak test of ≥30 minutes connected shows no drift, leaks, or
  disconnects.
- **Tier:** Core.

Items:

- [x] Observation cadence: every-N-ticks divisor from config, changeable
      per-session via a protocol message
- [x] Send queue with hard bound + latest-wins coalescing for observation
      frames
- [x] Slow-consumer soak test evidence (log counters)
- [x] Clean listener shutdown on client quit; no orphaned threads
- [x] WebSocket ping/pong liveness (grounds the M5.1 watchdog)

### M2.4 — Observer role: read-only second connection

- **Goal:** Let a second agent watch without being able to touch the
  controls — the "two agents, one client" shape a streamed setup needs (a
  brains agent driving, a commentator agent watching and narrating).
- **Delivers:** `role: "observer"` accepted at handshake (reserved in v1 and
  D7, now real), advertised as the `observer` capability. Observers receive
  observation frames with their **own** rate divisor and their own
  latest-wins coalescing, so a slow observer can only ever drop its own
  frames. Actuation messages from an observer are refused non-fatally
  (`role_forbidden`). Admission moves after `hello` (the mod cannot know a
  connection's role before it speaks), which brings a hello timeout with it.
  The safety asymmetry is the point: **observer loss is not an agent loss** —
  no release-all — while controller loss behaves exactly as v1 specifies.
- **Prerequisites:** M2.3 (per-connection coalescing builds directly on it).
  Sequenced after M3.2 so the critical path to watchable footage is not
  disturbed.
- **Definition of done:** With a controller walking the player and an
  observer attached, `kill -9`-ing the observer leaves the player walking and
  the controller undisturbed; `kill -9`-ing the controller releases all
  controls within one tick while the observer stays connected and keeps
  receiving frames.
- **Tier:** Core.
- **Decision:** **D7 amended** (observer settled, admission ordering, cap);
  **D13** records why MCP is a harness concern rather than a transport. Full
  design: `docs/specs/2026-08-04-m2.4-observer-role-design.md`.

Items:

- [x] Per-connection state hoisted out of `BridgeServer` into an
      `AgentConnection` type (channel, session, role, pending frame, drop
      counter, last pong, divisor override)
- [x] `role: "observer"` accepted; `observer` capability flag; `director`
      reserved as a future role name
- [x] `role_forbidden` (non-fatal) and `observer_attached` (fatal, 1013) in
      the error taxonomy; `configure` allowed for both roles
- [x] Admission moved post-`hello`; `bridge.helloTimeoutSeconds` closes
      connections that never handshake
- [x] `bridge.maxObservers` config (default 2, range 0–8); observation
      fanout with independent per-connection backpressure
- [x] Inbound queue entries tagged with their originating connection
- [x] Observer loss does not trigger release-all; controller loss unchanged
- [x] `examples/observer.py` + side-by-side run documented

---

### M2.5 — Versioned Python client (Core, consumer prerequisite)

- **Goal:** Let external consumers depend on one supported protocol implementation.
- **Prerequisites:** M2.1; use the currently implemented protocol and capabilities.
- **Delivers:** Installable package with typed messages, handshake/capability checks,
  controller/observer support, observation/event routing, request correlation,
  explicit disconnect/error behavior and documented cleanup. Migrate examples to
  this package; keep show-specific reconnect and gameplay policy outside it.
- **Decisions:** D14 package name/version/publication route; D12 before publication.
- **Definition of done:** A clean environment installs a pinned published version,
  connects to a rendered client, observes, moves and releases. Unsupported versions
  and missing capabilities fail clearly. Publication is an explicit acceptance gate.

- [x] Package decisions recorded; current wire contract covered
- [x] Examples use the package; Java/Python compatibility fixtures agree
- [x] Recorded live packets exercise late results, errors and disconnects
- [x] Clean-environment published install and rendered smoke evidence

**2026-10-03: complete.** The exact reviewed `marionette-mc==0.1.0a1`
wheel/sdist are published on [PyPI](https://pypi.org/project/marionette-mc/0.1.0a1/)
under the owner's client-only development-alpha exception (D14/D16). A fresh
Python 3.11.13 environment installed that pin directly from PyPI with caching
disabled; the recorded download origin and both fetched artifact hashes match
the approved release. That installation observed the isolated rendered client,
moved about 0.86 blocks during a 150 ms forward hold, released all controls and
showed zero subsequent positional drift. The owned client was stopped and its
config restored. Video, installation report and runtime samples were retained.

The earlier local acceptance remains valid: typed protocol-2 coverage, all eight
migrated examples, shared Java/Python fixtures, 19 Python tests on 3.11/3.14,
220 Java tests, isolated wheel/sdist installs, and recorded late-result/error/
disconnect routing. See [the package API](python/README.md). Published-install
acceptance now completes M2.5; it does not establish separate consumer integration.
Authentication remains gated for mod and stable releases, and further uploads
require authorization. M3.3 animation-quality/physical-alt-tab debt remains
independent and unchecked. No later milestone was started in this acceptance run.

---

## Phase 3 — Full Actuation (Core) — *the whole body, still zero intelligence*

### M3.1 — Complete movement set

- **Goal:** Every locomotion control a human has, expressible as set-and-hold
  state.
- **Delivers:** Forward/back/strafe, sprint, sneak, jump (held and one-shot),
  and combinations.
- **Prerequisites:** M1.3; M2.1 (messages specced first).
- **Definition of done:** A scripted example demonstrates each control and
  combination (sprint-jump, sneak-walk to a block edge without falling)
  visibly in a rendered client.
- **Tier:** Core.
- **Decision (minor):** movement axes are boolean (key-like) on the wire —
  **resolved in M3.1**; see docs/decisions.md.

Items:

- [x] Sprint, sneak, jump (hold + one-shot tap)
- [x] All combinations behave like real key input (e.g. sneak caps speed)
- [x] Protocol messages specced in `protocol/` before implementation
- [x] `examples/` demo exercising the full set

### M3.2 — Camera control with stream-quality smoothing

- **Goal:** Camera movement that looks human on rendered/streamed footage, not
  teleporting snaps.
- **Delivers:** Three camera modes: instant set (for RL-style agents),
  relative delta, and smoothed look-at (mod interpolates yaw/pitch toward a
  target over time with configurable speed). Smoothing runs frame-interpolated
  so footage is smooth.
- **Prerequisites:** M1.1; M2.1.
- **Definition of done:** A "look at these five points" script produces smooth,
  natural pans with no snapping, while instant mode still snaps when
  requested; footage captured via OBS looks acceptable.
- **Tier:** Core.
- **Decision:** **D5 (experiment-gated)** — smoothing model. Test candidates
  against the documented pass criteria (see docs/decisions.md); record the
  winner.

Items:

- [x] Instant, delta, and smoothed look-at camera messages
- [x] D5 experiment: damped spring vs. exponential vs. capped-rate easing,
      judged on 60 fps footage; result recorded in docs/decisions.md
- [x] Configurable smoothing speed; per-message override
- [x] Smoothing is frame-interpolated (render-time), state changes tick-side
      only
- [x] Visual verification demo script

### M3.3 — Attack / use / hotbar

- **Goal:** Interaction primitives: hit things, use things, choose what's in
  hand.
- **Delivers:** Attack and use as both held controls (set-and-hold: mining,
  eating, bow-draw) and one-shot clicks; hotbar slot selection.
- **Prerequisites:** M3.1; M2.1.
- **Definition of done:** A script visibly: selects a pickaxe slot, holds
  attack to mine a block to completion, one-shot-uses to place a block, and
  holds use to eat food — all rendered, no keyboard.
- **Tier:** Core.

Items:

- [x] Attack: hold + one-shot
- [x] Use: hold + one-shot
- [x] Hotbar slot select (0–8)
- [x] Interactions respect vanilla timing (attack cooldown, use ticks)
- [ ] Visual acceptance: mining/placement/eating animations and physical alt-tab
  check. Automated checks in a rendered client passed; see the
  [M3.3 verification record](docs/specs/2026-08-05-m3.3-attack-use-hotbar-design.md#verification-record-2026-10-02).

### M3.4 — Inventory & container actions

- **Goal:** Intent-level inventory manipulation, addressed generically so
  modded containers work without protocol changes.
- **Delivers:** Per **D8**, slots are addressed through the currently open
  `AbstractContainerMenu` (menu-type id + slot index) — never hardcoded player
  layouts — with stable names for the player inventory sections. Operations:
  move stack A→B, swap into hotbar N, drop, equip armor, open/close inventory.
  v1 *scopes* to the player inventory and simple vanilla containers, but the
  addressing scheme already generalizes to any menu-based container.
- **Prerequisites:** M3.3; pairs with M4.2 (observe what you manipulate).
- **Definition of done:** A script moves an item from a main-inventory slot to
  the hotbar, equips armor, drops an item, and moves an item into an open
  chest — visible in the rendered screen or by observable effect.
- **Tier:** Core.

Items:

- [x] Menu-generic slot addressing scheme specced in `protocol/` (D8)
- [x] Move/swap/drop/equip operations, marshalled on tick, validated against
      the actual open menu
- [x] Works against a vanilla chest via the same generic addressing
- [x] Failure responses when an operation is impossible (slot empty, no menu
      open, etc.)

- [x] Optional visible cursor animation, with cancellable pickup/carry/place

Verified in a rendered client against an isolated survival world, including
server-confirmed chest contents and failure paths. See the
[M3.4 verification record](docs/specs/2026-10-03-m3.4-inventory-actions.md#verification).

---

### M3.5 — Modded storage compatibility (Core)

- **Prerequisites:** M3.4, M4.2. Executes immediately after M4.2.
- **Delivers:** Generic menu observation even for unsupported mutation types;
  validated storage operations against actual slot pickup/place/stack rules.
  Choose one storage mod compatible with MC 1.21.8/NeoForge 21.8; IronChests is
  an intended example, not an assumed available dependency. No class-name-only
  widening of mutation support; prove safe behavior and reject unsupported cases.
- **Definition of done:** Vanilla and selected modded storage pass large-layout,
  restricted-slot, full-destination, server-correction, animated-cursor and
  disconnect/cancellation checks in a rendered client.

- [x] Capability/support descriptors and rejection reasons specified
- [x] Generic storage execution verified against slot restrictions
- [x] Vanilla and modded compatibility matrix with synchronized contents evidence

Implemented as the additive `inventoryStorage` capability (protocol 2; D19):
a structural storage analysis instead of a class allowlist, per-click
whole-menu verification, `support` descriptors and rejection `reason`s. The
test-only modded storage is Iron Chests (GPL-3.0-only, never shipped). See the
[M3.5 verification record](docs/decisions.md#m35-implementation-and-verification-2026-10-04).
The published 0.1.0a1 client is unaffected; the new fields are source-only
until a further publication is authorized (D14).

### M3.6 — Crafting and processing menus (Core)

- **Prerequisites:** M3.5, Phase 4 core perception.
- **Delivers:** Recipe/menu information and mechanical operations for player/table
  crafting and furnace-style input/fuel/output/progress. Agents choose recipes,
  materials, quantities and destinations; the mod does not plan resource gathering.
- **Definition of done:** A client crafts and smelts specified items, observes
  synchronized results, and handles missing ingredients, invalid slots and
  cancellation without leaked controls or silently lost cursor stacks.

- [ ] Contract and client support; scope and count semantics explicit
- [ ] Recipe/menu observation and validated execution
- [ ] Rendered success/failure/cancellation evidence

### M3.7 — Gameplay and social control coverage (Core)

- **Prerequisites:** M3.6, M5.1a.
- **Delivers:** Explicit respawn, game chat, offhand/swap-hand support and a
  coverage matrix for swimming, boats/mounts, elytra, shields and charged items.
  Verify existing primitives first; add commands only for demonstrated gaps.
  Expose available player identity in relevant events; social decisions stay external.
- **Definition of done:** Named scenarios for every declared supported control
  pass in-game; each actuator releases on panic/watchdog/disconnect. Distinguish
  ordinary chat from command execution and define limits before implementation.

- [ ] Missing primitive contract and client support
- [ ] Gameplay matrix, explicit exclusions, rendered acceptance
- [ ] All new actuators included in safety checks

### M3.8 — Optional mod action extensions and workstations

- **Prerequisites:** M3.5, M3.7, M5.1.
- **Delivers:** Opt-in allowlisted mod keybindings; adapters where standard menu
  interfaces cannot represent a mechanic; selected trading/enchanting/brewing/anvil
  support. Derive a small extension API from real integrations, not speculation.
- **Definition of done:** Each selected integration advertises capability/support,
  validates requests and demonstrates cancellation/release. Absent mods do not
  classload optional adapters or break the core.

- [ ] Choose concrete integrations and scope before implementation
- [ ] Capability discovery, validation and optional loading verified
- [ ] Per-integration runtime and safety evidence

---

## Phase 4 — Perception (Core) — *what the puppet feels*

> **Implementation order within this phase: M4.1 → M4.6 → M4.2 → M4.3 →
> M4.4 → M4.5, with M3.5 immediately after M4.2.** Milestone numbers are kept stable (they are referenced
> throughout this file and in `protocol/`), but M4.6's one-shot events are
> what a commentator/observer agent actually consumes — *things that
> happened* — so events land straight after the base frame, ahead of
> inventory, target, entity, and block-scan detail. See M2.4.

### M4.1 — Player state observation stream

- **Goal:** The tick-synced core observation: everything about the player's
  own body.
- **Delivers:** Per **D2**, one composite observation frame per cadence tick
  with named sections (`player`, `inventory`, `target`, `entities`, `world`)
  and a section mask agents can configure. This milestone fills `player`:
  position, rotation (actual, post-smoothing), velocity, health,
  hunger/saturation, air, XP, and status flags (on-ground, in-water, sneaking,
  sprinting, sleeping, on-fire, active effects). Replaces the M1.2 minimal
  observation.
- **Prerequisites:** M2.3; M2.1.
- **Definition of done:** An `examples/` script prints a live dashboard;
  values visibly track reality (take fall damage → health drops; sprint →
  flag flips) with no more than one tick of lag.
- **Tier:** Core.

Items:

- [x] Composite frame + section mask specced in `protocol/` (D2)
- [x] Full player-state schema in `protocol/`
- [x] Frame emitted at the configured rate
- [x] Status effects list with durations/amplifiers
- [x] Dashboard example agent

Implemented as protocol 2, with the canonical contract at `protocol/v1.md`.
Build and rendered-client checks passed; see the
[M4.1 verification record](docs/decisions.md#m41-implementation-and-verification-2026-10-03).

### M4.2 — Held item, hotbar, inventory & open-menu observation

- **Goal:** The agent knows what it's holding, carrying, and looking at in any
  open container.
- **Delivers:** The `inventory` section: held item (id, count, durability, key
  components), hotbar, main inventory, armor + offhand; when a menu is open,
  its menu-type id and slot contents via the same D8 addressing the actions
  use.
- **Prerequisites:** M4.1.
- **Definition of done:** Dashboard shows inventory matching the rendered
  inventory screen exactly, updating on pickup/consume/move; opening a chest
  surfaces its type and contents.
- **Tier:** Core.
- **Decision (minor, resolved in D18):** enumerated, capped item extras
  (durability, 64-character custom name, at most 8 enchantments/stored
  enchantments, base potion) within a 96 KiB section bound.

Items:

- [x] Item serialization schema (id, count, durability, enumerated extras)
- [x] Hotbar + main inventory + armor + offhand in the frame
- [x] Open-menu observation (menu-type id + slots, D8 addressing)
- [x] Unsupported mutation menus remain observable; carried stack and supported
      operations/rejection reasons exposed with bounded component detail

Implemented as the additive, opt-in `inventory` section (`inventoryState`,
protocol 2; D18). Build, Python client and rendered-client checks passed; see the
[M4.2 verification record](docs/decisions.md#m42-implementation-and-verification-2026-10-04).
The published 0.1.0a1 client is unaffected; selecting the section is
source-only until a further publication is authorized (D14).

### M4.3 — Crosshair target & world context

- **Goal:** "What am I looking at, and where/when am I?"
- **Delivers:** The `target` and `world` sections: ray-cast result (block:
  position, id, face; entity: id, type, distance; or nothing) at crosshair,
  plus dimension, time of day, weather, and light level at feet.
- **Prerequisites:** M4.1.
- **Definition of done:** Dashboard shows the targeted block/entity matching
  the vanilla F3 debug info while panning the camera across a scene.
- **Tier:** Core.
- **Decision (minor, open):** ray-cast distance — vanilla reach vs.
  configurable extended "gaze" distance.

Items:

- [ ] Crosshair ray-cast (block + entity) in the frame
- [ ] Dimension, world time, weather, light level
- [ ] Reach-distance semantics documented in `protocol/`

### M4.4 — Nearby entities

- **Goal:** Situational awareness of mobs, players, and items around the body.
- **Delivers:** The `entities` section: bounded-radius list with type,
  position, velocity, health where client-visible, hostility classification
  (hostile/neutral/passive/player/item/other), and "targeting me" where
  knowable client-side. Radius and count caps from config.
- **Prerequisites:** M4.1; M2.2 (radii config).
- **Definition of done:** Standing near a zombie, a cow, and a dropped item,
  the dashboard lists all three with correct classification and live
  positions; entity count in a busy area stays under the configured cap with
  nearest-first priority.
- **Tier:** Core.
- **Decision (minor, open):** hostility classification source (type tables vs.
  tags); how much client-side aggro inference to attempt.

Items:

- [ ] Entity schema in `protocol/`; radius + max-count caps
- [ ] Nearest-first truncation, truncation flagged in the message
- [ ] Hostility classification table
- [ ] Live demo verification

### M4.5 — Bounded block scan

- **Goal:** Local terrain awareness without melting the connection.
- **Delivers:** Per **D3**: on-demand request/response (not a periodic
  stream); encoding is a `palette` array of block ids + flat index array in
  documented y→z→x order, as JSON. Chunked across ticks under a per-tick work
  budget; hard radius caps in config. Delta "block changed" events are
  **reserved** in `protocol/` as a post-v1 follow-up. If profiling demands it,
  the same structure moves into a binary WebSocket frame without redesign.
- **Prerequisites:** M4.1; M2.3 (backpressure must already work).
- **Definition of done:** An agent requests a 16×8×16 scan and receives a
  correct, documented encoding with no visible frame hitch (measured);
  requesting beyond the config cap is refused cleanly.
- **Tier:** Core.

Items:

- [ ] Palette + indices encoding documented in `protocol/` with a worked
      example (D3)
- [ ] On-demand scan with per-tick work budget (chunked across ticks)
- [ ] Config-capped radius; over-cap requests rejected with error
- [ ] Frame-time measurement demonstrating no render hitch
- [ ] Delta-update follow-up reserved in `protocol/`

### M4.6 — One-shot events (outbound)

- **Goal:** Things that *happen* (vs. state that *is*) reach the agent as
  discrete events.
- **Delivers:** Event messages: damage taken (source, amount), death, respawn,
  item pickup, chat/system messages received, block-broken-by-player,
  dimension change. Events flush immediately and are never coalesced away by
  observation backpressure (separate small bounded queue).
- **Prerequisites:** M4.1; M2.3.
- **Definition of done:** Dashboard logs each event type triggered manually
  in-game (take damage, die, respawn, pick up an item, receive a chat message)
  exactly once, in order.
- **Tier:** Core.

Items:

- [x] Event envelope + initial taxonomy in `protocol/`
- [x] Reliable (non-coalesced) event queue with its own bound
- [x] The event set above implemented and demoed
- [x] Sequence/world-session identity, ordering relative to state/results, and
      per-connection overflow/disconnect semantics specified; no unsupported
      exactly-once guarantee across reconnects
- [x] Recorded-wire fixtures verify unsolicited events cannot answer requests
- [x] Unknown, client-predicted and server-observed facts distinguished

Implemented as the additive, opt-in `events` capability (protocol 2; D17).
Build, Python client and rendered-client checks passed; see the
[M4.6 verification record](docs/decisions.md#m46-implementation-and-verification-2026-10-04).
The published 0.1.0a1 client is unaffected; event support in Python is
source-only until a further publication is authorized (D14).

---

### M4.7 — Sound/subtitle observations (Optional)

- **Prerequisites:** M4.6.
- **Delivers:** Bounded, filterable client-observed sound/subtitle events with
  available identity/location and timing. No salience or commentary policy.
- **Definition of done:** Selected sounds produce accurate events under burst load
  without delaying controller state; unknown locations remain unknown.

- [ ] Capability, filters and bounds specified
- [ ] Client support and rendered/burst evidence

---

## Phase 5 — Safety & Robustness Hardening (Core)

*(Baseline safety shipped in M1.3; M5.1a moves urgent hardening forward.)*

### M5.1a — Bridge limits and emergency control (Core, execute early)

- **Prerequisites:** M2.3/M2.4 and implemented current actuators. Does not wait
  for future Phase 3 additions; those must extend its release coverage.
- **Delivers:** Configurable pong watchdog, local panic, bounded inbound commands,
  per-tick processing budget, bounded reliable replies, pending-handshake limits,
  and explicit overflow behavior. Safety release must not wait behind queued work.
  Resolve binding once to the actual loopback address. Decide authentication and
  WebSocket Origin policy before release; remote access remains out of scope.
- **Definition of done:** Flooded commands and stalled readers cannot cause
  unbounded queues or tick starvation. SIGSTOP and panic release every current
  actuator within the documented measured deadline; observer loss is isolated.

- [x] Watchdog/panic implemented and tested, including inventory animation
- [x] Queue, work and pending-connection limits with overload tests
- [x] Resolve-once binding and local trust policy recorded
- [x] Rendered crash/freeze/panic evidence; existing visual debt tracked

Verification: [M5.1a safety record](docs/specs/2026-10-03-m5.1a-safety-verification.md).
M3.3 visual/alt-tab debt remains separate and unchecked.

Follow-up (2026-10-03, owner-approved, D16a): the default pong timeout was
lowered from 5 to 2 seconds after consumer measurements showed 18–22 blocks of
walking while a frozen agent held movement. Rendered re-measurement found
8.9–10.4 blocks, with no spurious disconnects for a healthy agent with pauses
of up to 1 s. No protocol or published-client change was made.

Follow-up (2026-10-04, owner-approved, D16b): local panic now latches. A consumer
check found a reconnecting agent re-drove the player 0.4–2.6 s after each panic.
While latched, controller hellos are refused with `panic_latched` (close 1008);
observers are unaffected; repeated panic never re-arms; a separate rebindable
"Allow agent control" key (default F9, Controls menu) clears the latch without
resuming anything. The latch survives world exit/rejoin and resets on client
restart. Additive `panicLatch` capability; the published client surfaces the
refusal unchanged. Evidence is in the M5.1a safety record.

### M5.1 — Human precedence and complete lifecycle safety

M5.1a delivers the watchdog/panic/binding foundation first. Reuse its evidence
where inputs are unchanged; this milestone completes human-priority,
agent-exclusive and lifecycle coverage across the expanded actuator set.

- **Goal:** Make it impossible for Marionette to hurt the player, the machine,
  or the user's control of their own game.
- **Delivers:** Agent-liveness watchdog on WebSocket ping/pong (missed pongs →
  release-all + idle), a local **panic key** that instantly severs agent
  control, human-input precedence policy (recommended: human input always
  overrides and optionally pauses agent control — decide and document),
  enforced loopback-only binding with a config override that requires an
  explicit "I understand" flag, and safe behavior across
  death/respawn/dimension-change/GUI-open edge cases.
- **Prerequisites:** M5.1a; Phase 3 core complete (M3.1–M3.7).
  M3.8 is optional and must repeat safety acceptance when selected.
- **Definition of done:** Freeze the agent process (SIGSTOP) mid-sprint →
  player idles within the watchdog window; press panic key mid-agent-control →
  instant human control, agent notified; die and respawn under agent control →
  no stuck inputs; every actuator verified to release.
- **Tier:** Core.

Items:

- [ ] Ping/pong watchdog with configurable timeout
- [x] Panic keybinding (registered, rebindable, shown in controls menu); panic
      latches until a separate rebindable re-arm key clears it (D16b, 2026-10-04,
      rendered evidence in the M5.1a safety record)
- [ ] Human-override policy implemented + documented (three modes per
      docs/decisions.md: human-priority / agent-exclusive / panic)
- [ ] Input-lockout keybind (agent-exclusive mode): rebindable, shown in
      controls menu; lockout + panic keys never suppressed; auto-drops when
      no controller is attached
- [ ] Focus-loss behavior decided per precedence mode (with the M2.2
      no-pause-while-agent-connected config toggle)
- [ ] Loopback enforcement + explicit-opt-out config gate + log warning
- [ ] Edge-case matrix tested: death, respawn, dimension change, GUI open,
      pause menu, world leave
- [ ] Idle-safe state machine documented in `docs/`

### M5.2 — Diagnostics & operator visibility

- **Goal:** Anyone running Marionette can see what it's doing and why.
- **Delivers:** A small toggleable HUD overlay: connection status, agent name,
  observation rate, dropped-frame counter, controls currently held by the
  agent. Structured logging levels per config.
- **Prerequisites:** M5.1.
- **Definition of done:** With an agent connected and walking the player, the
  HUD visibly shows "connected", the held controls, and live counters;
  disconnect flips it to "idle" immediately.
- **Tier:** Core.

Items:

- [ ] Status HUD overlay with toggle
- [ ] Held-controls indicator (viewers/users can see the agent's inputs)
- [ ] Drop/latency counters exposed on HUD and via a protocol `status` query

---

## Phase 6 — Baritone Integration (Optional)

### M6.1 — Runtime detection & capability advertisement

- **Goal:** Detect Baritone if present; change nothing when absent.
- **Delivers:** Per **D9**: a `NavigationBackend` interface in core, with the
  Baritone implementation in an adapter classloaded only after runtime
  detection; Baritone as `compileOnly` (LGPL never bundled). `baritone`
  capability flag in the handshake; a `navigation.*` protocol namespace that
  returns a documented "capability unavailable" error when absent.
- **Prerequisites:** M2.1 (capability flags); M5.1 (Baritone motion must obey
  release-all/panic too).
- **Definition of done:** Same mod jar: without Baritone installed, handshake
  advertises `baritone: false` and nav commands return the documented error;
  with Baritone in `run/mods`, it advertises `true`. No crash, no log spam, in
  either case.
- **Tier:** Optional.

Items:

- [ ] Gradle soft dependency (`compileOnly`); LGPL kept out of the shipped jar
- [ ] `NavigationBackend` interface; Baritone adapter classloaded only on
      detection (D9)
- [ ] Handshake capability flag + documented unavailable-error
- [ ] `protocol/` nav namespace specced

### M6.2 — Goal commands: goto / mine / follow / stop

Ship with M6.3 as one integration gate. Requests require correlated outcomes,
progress/cancellation reasons and explicit enforced time budgets. Commands being
accepted is not proof of completion; consumers must never infer it from log silence.

- **Goal:** Agents issue high-level navigation goals instead of steering.
- **Delivers:** `goto(x,y,z | block-type)`, `mine(block-type[, count])`,
  `follow(entity)`, `stop` mapped to Baritone processes; a clear ownership
  rule between goals and raw actions (raw movement input cancels/suspends the
  active goal — decide and document).
- **Prerequisites:** M6.1.
- **Definition of done:** An `examples/` script sends `goto` to coordinates
  ~100 blocks away and the player **visibly pathfinds there**; `stop` halts
  immediately; `mine iron_ore` acquires iron; agent disconnect cancels the
  active goal (the safety rule extends to goals).
- **Tier:** Optional.
- **Decision (minor, open):** raw-input vs. goal conflict policy; which
  Baritone settings Marionette exposes.

Items:

- [ ] goto / mine / follow / stop implemented
- [ ] Conflict policy: raw actions vs. active goal
- [ ] Disconnect/panic/watchdog all cancel active goals
- [ ] Example nav agent script

### M6.3 — Goal progress & completion events

- **Goal:** The agent knows how its goal is going without polling pixels.
- **Delivers:** Events: goal accepted/rejected, progress (path status,
  distance remaining), completed, failed (unreachable), canceled — riding the
  M4.6 event channel; current-goal status in the observation frame while
  active.
- **Prerequisites:** M6.2; M4.6.
- **Definition of done:** The example agent issues `goto`, receives progress
  events, and receives exactly one terminal event (completed) on arrival; an
  unreachable goal yields a `failed` event within a bounded time.
- **Tier:** Optional.

Items:

- [ ] Goal lifecycle events specced + implemented
- [ ] Goal status in observation frame
- [ ] Unreachable-goal failure demo

---

## Phase 7 — Optional Server-Side Component (Optional)

### M7.1 — Server companion for richer world state

- **Goal:** When Marionette also runs on the (integrated or dedicated) server,
  offer world truth beyond what the client can see.
- **Delivers:** A server-side module + client↔server channel (NeoForge
  networking) feeding extras into the same agent-facing observation stream as
  optional fields; handshake capability flag `server: true`. Client-only
  operation remains fully functional and the default. Per **D10**, the exact
  field list is defined by the Phase-4 gap list, not speculation; the one rule
  decided up front: on dedicated servers the component is **per-player opt-in**
  (server-side whitelist config) — nobody gets puppeted or observed without
  the operator enabling it for that player.
- **Prerequisites:** Phase 4 core complete (extends, not forks, the observation
  schema); M2.1.
- **Definition of done:** The same agent script gets strictly richer
  observations (documented fields flip from absent/approximate to exact) when
  the server module is present, with zero agent-side protocol changes beyond
  reading new optional fields; everything still works with a vanilla server.
- **Tier:** Optional.

Items:

- [ ] Gap list from Phase 4 documenting what client-side observation *can't*
      provide (this is the D10 scope input)
- [ ] Server module + custom payload channel
- [ ] Capability flag + optional observation fields (additive schema change)
- [ ] Per-player opt-in whitelist for dedicated servers
- [ ] Verified no-op with vanilla/absent server

---

## Phase 8 — Framebuffer / Vision Streaming (Optional)

### M8.1 — Pixel observation stream

- **Goal:** Pixel-based agents (VLMs, RL policies) can receive what the client
  renders.
- **Delivers:** Configurable-rate framebuffer capture (downscaled, encoded —
  e.g. JPEG) delivered on a channel that cannot degrade the core tick-synced
  stream (binary WebSocket frames; recommend a secondary connection — D11);
  capability flag `vision: true`; off by default.
- **Prerequisites:** M2.3 (backpressure story is life-or-death here); M5.2.
- **Definition of done:** An example agent saves received frames to disk at
  the configured rate while the structured observation stream's latency stays
  unchanged (measured); capture at 512×288 @ 4 fps costs a measured,
  documented FPS overhead.
- **Tier:** Optional.
- **Decision:** **D11 (open)** — capture path & format: GL readback point,
  encoding, resolution/rate defaults, same-socket binary frames vs. secondary
  connection (secondary recommended).

Items:

- [ ] Capture behind feature toggle, off by default
- [ ] Encoding + downscale off the render thread where possible
- [ ] Independent channel/backpressure (drop frames, never block)
- [ ] Performance measurement documented
- [ ] Frame-saving example agent

---

## Phase 9 — Release & Distribution (Core)

### M9.1 — Docs, examples, and reference agents

- **Goal:** A stranger can go from download to a moving player in ten minutes.
- **Delivers:** README quickstart; `docs/` design notes (threading model,
  safety model, protocol rationale); `examples/` cleaned up: minimal probe,
  dashboard, walk-square, and one "does something end-to-end" reference agent
  (scripted, no AI) in at least Python.
- **Prerequisites:** Core milestones in Phases 0–5 complete; examples updated for Phase 6
  if shipped.
- **Definition of done:** A fresh-machine walkthrough following only the
  README reaches "external script walks the player in a square" without
  insider knowledge.
- **Tier:** Core.

Items:

- [ ] README quickstart (install → config → run example)
- [ ] `docs/`: threading, safety, protocol-evolution notes
- [ ] `protocol/` finalized as v1 and tagged
- [ ] Examples verified against the released jar

### M9.2 — Packaging & publication

- **Goal:** Ship it: versioned artifacts on Modrinth and CurseForge for MC
  1.21.8.
- **Delivers:** Release build pipeline (CI builds tagged releases), correct
  `neoforge.mods.toml` metadata (Baritone as optional dependency), Modrinth +
  CurseForge listings, changelog process. MIT was confirmed under D12 on 2026-10-03; publication authorization and
  other release gates remain required.
- **Prerequisites:** M9.1.
- **Definition of done:** A user installs Marionette from Modrinth into a
  stock NeoForge 1.21.8 instance, follows the quickstart, and drives the
  player externally; the listing correctly declares 1.21.8-only support and
  optional Baritone.
- **Tier:** Core.
- **Decision:** **D12 (settled: MIT)** — explicit publication approval remains
  required; also decide mod-version ↔ protocol-version relationship in
  the changelog policy.

Items:

- [x] Confirm license — MIT confirmed 2026-10-03 (D12)
- [ ] Tag-triggered CI release workflow producing the jar
- [ ] mods.toml metadata: optional Baritone dependency declared
- [ ] Modrinth + CurseForge listings with the 1.21.8 pin explained (Baritone
      support)
- [ ] Changelog + versioning policy documented

---

## Critical path

The minimum sequence to a first working, externally-driven, streamable client:

**M0.1 → M0.2 → M1.1 → M1.2 → M1.3 → M3.2**

Verified scaffold → tick hook → prove code can move the rendered player →
minimal WebSocket bridge (both directions) → external script walks a square
with disconnect-safety → camera smoothing so the footage is watchable.
This historical walking-skeleton path is complete. The current consumer-enablement
path begins with M2.5 and M5.1a, followed by the execution order at the top of this
file. HUD, perception and expanded actuation have their own acceptance gates.

## Decision index

All design decisions, their status, and rationale live in
[docs/decisions.md](docs/decisions.md). Summary:

| # | Decision | Status | Needed by |
|---|----------|--------|-----------|
| D1 | Transport | **Settled: WebSocket** (D1a settled: Netty + jar-in-jar'd `netty-codec-http`) | M1.2 |
| D2 | Observation composition | **Settled: composite frame + section mask** | M4.1 |
| D3 | Block-scan strategy | **Settled: on-demand, JSON palette + indices; deltas reserved** | M4.5 |
| D4 | Input injection mechanism | **Settled: input-path mixin** (M1.1 experiment) | M1.1 |
| D5 | Camera smoothing model | **Settled: capped-rate easing** (M3.2 experiment) | M3.2 |
| D6 | Protocol versioning | **Settled: integer version + capability flags** | M2.1 |
| D7 | Multi-client policy | **Settled: single controller + capped read-only observers** | M2.1, M2.4 |
| D8 | Inventory granularity | **Settled: intent-level, menu-generic addressing** | M3.4 |
| D9 | Baritone surface | **Settled: adapter behind `NavigationBackend` interface** | M6.1 |
| D10 | Server component scope | **Deferred by design** (Phase-4 gap list; opt-in rule settled) | M7.1 |
| D11 | Framebuffer capture | Open | M8.1 |
| D12 | License confirmation | **Settled: MIT**; publication still gated | M2.5, M9.2 |
| D13 | MCP positioning | **Settled: harness-side concern, never the transport** | M2.4 |
| D14 | Published Python client specifics | **Settled**; initial alpha published and verified | M2.5 |
| D15 | Cross-project coordination | **Settled** | M2.5 onward |
