# Design decisions

The running record of Marionette's design decisions: what is settled, what is
deliberately deferred, and what must be resolved by experiment. Each decision
is tied to the roadmap milestone that needs it — see [ROADMAP.md](../ROADMAP.md).

Statuses: **Settled** (decided; change requires revisiting this doc),
**Experiment-gated** (decided *how* it will be decided; pass criteria below),
**Deferred** (intentionally not decided yet; the input that will decide it is
named), **Open**.

---

## D1 — Transport: WebSocket — **Settled**

Communication between mod and agent is a localhost **WebSocket** connection
carrying JSON text messages.

**Rationale:**

- Framing comes free — no hand-rolled length prefixes or newline discipline.
- WS ping/pong frames provide the liveness signal for the safety watchdog
  (M5.1) without inventing a protocol-level heartbeat.
- Binary WS frames are available later for bandwidth-heavy payloads (block
  scan escape hatch per D3, framebuffer per D11) without changing transport.
- Every mainstream language has a mature WebSocket client; browser-based
  agents/dashboards work with zero extra tooling.

### D1a — WebSocket server implementation — Open (decide in M1.2)

Candidates:

- **Netty-based, using the Netty Minecraft already bundles** (recommended):
  zero new runtime dependencies; Netty ships WebSocket codecs. Risk: coupled
  to MC's Netty version — acceptable since we are pinned to 1.21.8.
- **Shaded `Java-WebSocket`** (MIT, small): fallback if the bundled-Netty
  route proves awkward in the mod environment.

## D2 — Observation composition: composite frame + section mask — **Settled**

One observation message per cadence tick, containing named sections
(`player`, `inventory`, `target`, `entities`, `world`). Agents can toggle
sections via the handshake or a `configure` message. One-shot events (M4.6)
are separate messages and are never coalesced. Block scans (D3) and vision
(D11) stay *out* of the frame.

**Rationale:** the agent-side contract stays as simple as possible — read one
message and you have the world. Bandwidth is a non-issue at these sizes (a few
KB of JSON at 20 Hz on loopback). Full pub/sub channels would add protocol
surface with no payoff for the payloads that live in the frame.

## D3 — Block scan: on-demand, JSON palette + indices — **Settled**

- **On-demand request/response**, not a periodic stream — terrain doesn't
  change at 20 Hz, and this keeps the steady-state frame small.
- Encoding: a `palette` array of block ids + a flat index array in documented
  y→z→x order. A 16×8×16 scan is 2,048 indices ≈ 5–10 KB of JSON — one-line
  parseable in any language.
- The scan fill is chunked across ticks under a per-tick work budget; hard
  radius caps live in config.
- Delta "block changed" events are **reserved** in `protocol/` as a post-v1
  follow-up.
- Escape hatch: if profiling ever demands it, the same palette+indices
  structure moves into a binary WebSocket frame without redesign.

## D4 — Input injection mechanism — **Experiment-gated** (M1.1)

Prototype both candidates during M1.1; leaning mixin, but the KeyMapping
variant is cheap insurance and the fallback if the mixin target proves
fragile.

Candidates:

1. **Mixin into the player input path** (e.g. `ClientInput`/keyboard-input
   population) — manipulates the *semantic* movement values rather than faking
   key presses; expected to make the human-override policy (M5.1) cleaner.
2. **KeyMapping-forcing** — set the pressed state of vanilla key mappings.

A candidate wins by passing **all** of:

- Coexists with real keyboard input (a human pressing a key mid-agent-control
  produces sane, defined behavior).
- Survives GUI open/close and window focus loss.
- Plays correctly with vanilla sprint/sneak toggle logic.
- Never leaks stuck keys / stuck `KeyMapping` state.

Record the winner and the loser's failure mode here when decided.

## D5 — Camera smoothing model — **Experiment-gated** (M3.2)

Test with a fixed visual scenario (the "look at five points" script) captured
at 60 fps. Candidates, in order of expected quality:

1. **Critically damped spring** — natural accel/decel, no overshoot by
   construction.
2. **Exponential smoothing** — simplest; ease-out only, slightly robotic.
3. **Constant max angular velocity with ease-in/out.**

Pass criteria: no snap, no overshoot, configurable speed, and pointing
accuracy converges within a stated tolerance so "look at X then attack"
sequencing is reliable. Record the winner here when decided.

## D6 — Protocol versioning: integer version + capability flags — **Settled**

- The protocol version is a **single integer** that bumps only on breaking
  changes.
- Everything additive (Baritone, server component, vision, containers) is
  advertised as **capability flags** in the handshake; agents feature-detect
  instead of version-sniffing.
- Handshake: agent sends `hello` with the protocol versions it supports; mod
  replies with the chosen version plus its capability map, or a documented
  rejection.
- The mod jar uses semver independently (e.g. mod 0.4.2 speaks protocol 1).

**Rationale:** semver on a wire contract creates ambiguity about what
minor/patch mean; integer + capabilities answers every question an agent can
ask.

## D7 — Multi-client policy: single controller — **Settled**

- Exactly **one controlling agent connection** in v1; a second connection is
  rejected with a documented error while a controller is attached.
- The `hello` message includes `role: "controller"` (the only accepted value
  in v1), so `role: "observer"` (read-only observation stream — dashboards,
  overlays) can be added later behind a capability flag as a purely additive
  change.

## D8 — Inventory actions: intent-level, menu-generic addressing — **Settled**

- Operations are **intent-level** (move stack A→B, swap into hotbar N, drop,
  equip), not raw click-slot emulation.
- Slots are addressed through the currently open `AbstractContainerMenu`
  (menu-type id + slot index), with stable names for player inventory
  sections — **never hardcoded player-inventory layouts**.
- v1 scopes to the player inventory and simple vanilla containers, but the
  addressing generalizes to any menu-based container — including **modded
  containers**, which was the deciding requirement.
- Observation mirrors actuation: when a menu is open, the frame includes its
  menu-type id and slot contents via the same addressing (M4.2).

## D9 — Baritone surface: API adapter behind an interface — **Settled**

- Core defines a `NavigationBackend` interface; the Baritone implementation
  lives in an adapter class that is **classloaded only after runtime
  detection**.
- Baritone is a `compileOnly` Gradle dependency — LGPL code is never bundled
  into the shipped jar.
- The `navigation.*` protocol namespace returns a documented "capability
  unavailable" error when Baritone is absent.

## D10 — Server component scope — **Deferred by design** (M7.1)

The exact field list the server component provides is defined by the
**Phase-4 gap list** ("what client-side observation can't provide"), not
speculation. Likely entries, for expectation-setting only: exact entity
health/data the client only knows visually, unopened container contents,
world state beyond client awareness, ground-truth mob aggro.

One rule **settled now** because it is trust, not scope: on dedicated servers
the component is **per-player opt-in** (server-side whitelist config) — nobody
gets puppeted or observed without the operator enabling it for that player.

## D11 — Framebuffer capture path & format — **Open** (M8.1)

To decide: GL readback point, encoding (e.g. JPEG), resolution/rate defaults,
and same-socket binary frames vs. a secondary connection (secondary
recommended so vision can never degrade the core tick-synced stream).

## D12 — License — **Open, blocks release** (M9.2)

MIT is provisional. Must be confirmed with the project owner before anything
release-facing (Modrinth/CurseForge listings, tagged releases). Worth settling
earlier if outside contributions arrive.

---

## Minor open points (decide inside their milestones)

- Movement axes on the wire: boolean (key-like) vs. analog floats
  (controller-like) — M3.1.
- Item-component serialization depth (enchantments, custom names) — M4.2.
- Crosshair ray-cast distance: vanilla reach vs. configurable gaze — M4.3.
- Hostility classification source; client-side aggro inference depth — M4.4.
- Raw-input vs. active-Baritone-goal conflict policy; which Baritone settings
  are exposed — M6.2.
- Human-input precedence policy details and watchdog timeout default — M5.1.
- Mod-version ↔ protocol-version relationship in the changelog policy — M9.2.
