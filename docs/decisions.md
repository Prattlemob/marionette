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

### D1a — WebSocket server implementation — **Settled** (2026-07-11, Phase 1 design)

**Netty, with `io.netty:netty-codec-http:4.1.118.Final` bundled via
NeoForge Jar-in-Jar.**

The original premise ("the Netty Minecraft bundles ships WebSocket codecs")
turned out to be **false**: Minecraft ships only Netty's core modules
(buffer, codec, common, handler, resolver, transport, epoll). The WebSocket
codecs live in `netty-codec-http`, which is not on the runtime classpath
(verified against this project's `runtimeClasspath` on MC 1.21.8 /
NeoForge 21.8.53, Netty core 4.1.118.Final).

Resolution: Jar-in-Jar the missing codec jar (Apache-2.0, ~660 KB),
version-matched to MC's Netty core, `transitive = false` so no Netty core
classes are duplicated. No relocation: the jar only adds codec classes on
top of the Netty core MC already loads, and Jar-in-Jar negotiates versions
if another mod bundles it too. The rest of the original rationale stands
(single event-loop thread, ping/pong frames free, writability-based
backpressure for M2.3, binary frames available later).

Rejected: `Java-WebSocket` (kept as fallback; independent of MC's Netty but
brings its own thread model and the bridge design is built around a Netty
pipeline); hand-rolling WS framing on bare Netty core (framing-for-free was
the point of choosing WebSocket, per D1).

**Dev-environment addendum (2026-07-13, Task 8 verification):** Jar-in-Jar
metadata only exists inside the packaged mod jar, and NeoForge dev run
tasks (`runClient*`) load the mod from `build/classes` without packaging
one — so FML's mod/library discovery never sees the bundled
`netty-codec-http` at all in dev, and plain external libraries are not
loaded into dev runs on their own (per NeoForge's non-MC-dependency docs).
The codec was therefore simply absent from the dev classpath: first real
connection threw `NoClassDefFoundError:
io/netty/handler/codec/http/HttpServerCodec`. Fix: also declare the same
artifact via ModDevGradle's `additionalRuntimeClasspath` in `build.gradle`,
which exists precisely to put libraries on the dev-run classpath. It is
additive and dev-only: production packaging still relies solely on the
jarJar bundle.

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

## D4 — Input injection mechanism — **Settled** (M1.1 experiment, 2026-07-13)

**Input-path mixin.** `KeyboardInputMixin` OR-merges the agent's
`ControlState` into `KeyboardInput.tick()`'s semantic output and recomputes
the move vector vanilla-faithfully, rather than faking key presses.

Both candidates were prototyped and run through the same scripted grid (six
runs: plain / gui-stunt / toggle-stress × each variant) plus a human
coexistence-and-focus-loss checklist. Per-criterion results:

| Criterion | KeyMapping-forcing | Input-path mixin |
|---|---|---|
| Stuck state after release | **AMBIGUOUS** — `release()`'s `setDown(false)` is a documented no-op on a toggled `ToggleKeyMapping`; WALK_2's sustained ~2.7–2.8 blocks/sample flicker (below) shows crouch was "on" roughly half the time, so a latched crouch on release is structurally possible, just not directly filmed (evidence logging stops the instant controls release). | **PASS** — release is a direct `ControlState` clear, no toggle-based no-op path; no code-level staleness mechanism; clean in all runs. |
| GUI open/close survival | **PASS**, with a quirk — full lifecycle (open → close → COAST → `Demo complete` → clean release) completes every time, but movement nearly halts while the inventory is open (0.31 blocks/sample vs. ~5.3 expected) because vanilla routes key input away from gameplay while a `Screen` has focus; resumes at full speed on close. | **PASS, cleaner** — same full lifecycle completes, and movement continues undisturbed through the GUI-open window (5.50–5.62 blocks/sample) because the semantic merge happens beneath screen-focus routing. Recorded as data: the two variants *diverge* on this axis by design, not by defect; the mixin's continue-through-GUI behavior was chosen deliberately. |
| Sprint/sneak toggle-logic correctness | **FAIL** — with `toggleCrouch`/`toggleSprint` enabled, `ToggleKeyMapping.setDown(true)` toggles the key state *per tick* instead of holding it. Sprint (edge-triggered/latched in vanilla) was unaffected (5.4–5.6, no flicker), but sneak flickered to a sustained ~2.7–2.8 blocks/sample instead of the expected ~1.3 — roughly the midpoint between walk and sneak speed, i.e. visible per-tick toggling, reproducible across three consecutive samples. | **PASS** — reads `ControlState` directly, never touches `KeyMapping.setDown`; both sprint (5.43–5.61) and sneak (1.29–1.39) match the clean baseline ranges exactly, fully immune to toggle-key settings. |
| Human coexistence (W/S mid-demo) | Skipped — moot given the scripted toggle-logic FAIL. | **PASS** (human checklist) — human W/S input OR-merges sanely; S does not counter a scripted forward hold (defined behavior: human can *add*, not *counter*, agent-held controls). |
| Focus loss (alt-tab mid-demo) | Skipped — moot given the scripted toggle-logic FAIL. | **PASS** (human checklist) — alt-tab mid-demo survived, ended with a clean release and no stuck input afterward. |

Outcome per the D4 rule ("one candidate passes all criteria → it wins"): the
mixin passed every criterion; the KeyMapping-forcing variant failed
toggle-logic correctness outright (criterion c) with the stuck-state
criterion left structurally ambiguous by the same root cause, so its human
checklist was skipped as moot.

**Loser's concrete failure mode:** `ToggleKeyMapping` (backing
`keyShift`/`keySprint` when `toggleCrouch`/`toggleSprint` are enabled in
`options.txt`) treats every `setDown(true)` call as a toggle, not a hold.
Driving it once per tick therefore flips the underlying state once per tick,
which vanilla's edge-triggered sprint-start logic happens to absorb
invisibly but which visibly halves the effective sneak speed (measured
~2.7–2.8 blocks/sample vs. the expected ~1.3). The same semantics make
`setDown(false)` a documented no-op on release, leaving a real,
code-supported risk of a latched crouch after `Controls released`.

Full per-run evidence and delta tables are archived outside the repo (dev-box
scratch); the criteria matrix above, plus the human checklist notes folded
into this record, is the durable record.

**Feeds forward to M5.1:** the mixin's OR-merge means a human can *add*
input on top of agent-held controls but cannot *counter* them (pressing S
does not stop a scripted forward hold). M5.1's human-input precedence policy
must add an explicit "human counters/overrides agent" layer on top of this
OR-merge base — it is not free from D4 and needs its own design.

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

- Bridge lifecycle polish carried out of Phase 1 review — **partially
  resolved in M2.1** (2026-07-15): the disconnect latch is now gated on a
  completed hello (a probe that never sent hello no longer logs a spurious
  agent-disconnect), and duplicate-hello rejection is specced
  (`unexpected_hello`, non-fatal) and tested. Still open for M2.3: daemon
  thread factory for the Netty event loop; log/diagnose `exceptionCaught`
  causes; define release-vs-bridge-stop ordering on shutdown (currently
  inert — ticks have stopped — observed as `Bridge stopped` before
  `Controls released` on window close); halt frame processing after a binary-frame violation (the 1003 close path leaves already-pipelined text frames parsed and enqueued until channelInactive — give the session an explicit close()).
- JSON strictness on the wire — pre-release. `MessageParser` uses Gson's lenient
  `JsonParser.parseString`, which accepts non-standard JSON (unquoted keys,
  single quotes). Since `protocol/README.md` defines tightened validation as a
  breaking change, accidental leniency hardens into contract: before the first
  tagged release, either switch to strict parsing or add a spec sentence that
  acceptance of non-conforming JSON is unspecified and may tighten without a
  version bump. (Final M2.1 review, 2026-07-15.)
- Movement axes on the wire: boolean (key-like) vs. analog floats
  (controller-like) — M3.1.
- Item-component serialization depth (enchantments, custom names) — M4.2.
- Crosshair ray-cast distance: vanilla reach vs. configurable gaze — M4.3.
- Hostility classification source; client-side aggro inference depth — M4.4.
- Raw-input vs. active-Baritone-goal conflict policy; which Baritone settings
  are exposed — M6.2.
- Human-input precedence policy details and watchdog timeout default — M5.1.
  The policy must define **three modes** (owner-requested, 2026-07-13):
  **human-priority** (default — human input overrides/pauses agent control),
  **agent-exclusive** (a rebindable *input-lockout* keybind suppresses all
  local gameplay inputs — movement/jump/sneak/sprint keys, mouse look,
  attack/use, hotbar — so only the agent drives), and **panic** (existing —
  instant human control, agent severed). Invariants settled now because they
  are trust rules, not scope: the lockout toggle and panic key are **never**
  suppressed; system/UI keys (Escape, F3, chat) stay live (exact list decided
  at M5.1); lockout **auto-drops the moment no controller is attached**
  (disconnect, watchdog trip, world leave) so a dead agent can never leave a
  locked keyboard; the M5.2 HUD must show the active mode prominently.
  Implementation note: with D4's OR-merge mixin, agent-exclusive means
  *replace* instead of *merge* in `KeyboardInputMixin`, plus new mouse-look
  suppression (vanilla `MouseHandler.turnPlayer` path).
- Streaming while unfocused — M2.2/M5.1. Vanilla singleplayer opens the pause
  menu on window-focus loss (`pauseOnLostFocus`), freezing an agent-driven
  session the moment the operator alt-tabs. M2.2 adds a config toggle
  (suggested default: suppress the focus-loss pause while an agent is
  connected) so a puppeted client keeps running and streamable unfocused;
  M5.1 decides how focus loss behaves in each precedence mode (pausing on
  focus loss is arguably a safety feature when a human walks away). Interim
  workaround: `pauseOnLostFocus:false` in options.txt (or F3+P).
- Mod-version ↔ protocol-version relationship in the changelog policy — M9.2.
