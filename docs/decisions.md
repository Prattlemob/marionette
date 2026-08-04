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

## D5 — Camera smoothing model — **Settled: constant max angular velocity with ease-in/out (capped-rate)** (M3.2 experiment, 2026-08-04)

Tested against a fixed visual scenario (the "look at five points" script,
`examples/look_points.py`) with `scripts/analyze_pan.py` run over VERBOSE
frame logs for the numeric pass criteria — no snap, no overshoot,
convergence budget, speed-multiplier effect — across a five-point demo plus
a speed test (seven pans total, default speed 180 deg/s), captured twice
independently.

**CAPPED_RATE — winner.** Passed all seven pans on both capture runs:

- **No snap:** every frame step stayed within `2 x speed x dt + 0.2 deg` —
  holds by construction, since the model's own velocity cap enforces the
  bound.
- **No overshoot:** no pan exceeded the target by more than 0.5 deg.
- **Convergence:** 478–739 ms for ~90 deg pans — the fastest of the three
  candidates.
- **Final error:** <= 0.1 deg on every pan.
- **Speed multiplier:** a 2.0x multiplier converged ~2x faster (e.g. the
  90 deg pan: 0.95 s at 1.0x -> 0.70 s at 2.0x).

**EXPONENTIAL — eliminated numerically.** 5 of 7 pans failed the no-snap
criterion outright, with first-frame jumps of 1.9–6.5 deg within 3.9–7.4 ms
of pan start — the predicted "ease-out only, slightly robotic" weakness
front-loading motion into an immediate large step rather than a ramped one.

**DAMPED_SPRING — near-clean but not chosen.** Numerically almost clean:
one non-reproducing cold-start-hitch failure (a 12.44 deg step inside a
32.3 ms frame, on the very first pan of one capture run only — a second
independent run passed 7/7). Its mid-pan peak velocity scales with pan
size, so large pans can brush the velocity bound during frame hitches.
Convergence was also the slowest of the three: 936–1003 ms for ~90 deg
pans, versus capped-rate's 478–739 ms.

**Visual verdict (user judgment, 2026-08-04):** captured at 60 fps via
`gpu-screen-recorder` (a KMS-path capture that bypassed the Wayland/portal
capture failures hit with `ffmpeg`/x11grab, `wf-recorder`, and Spectacle on
the capture box). CAPPED_RATE read cleanest on the stationary five-point
sequence and under normal movement — a follow-up capture had the player
walking with a mid-walk heading change, then sprinting for 10 s while
smooth-tracking a fixed side point, which verified a further ~13 deg of
silent re-aiming continuing after the pan's initial convergence, still
reading clean.

**Cleanup:** `DampedSpringModel`, `ExponentialModel`, `SmoothingModelType`,
and the experimental `camera.smoothingModel` config entry were removed once
the winner was picked. The `SmoothingModel` interface seam stays; the mod
now always constructs `CappedRateModel` directly (speed = max angular
velocity in deg/s, acceleration = 4x speed).

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

## D7 — Multi-client policy: single controller + observers — **Settled**

- Exactly **one controlling agent connection**; a second controller is
  rejected with a documented error while one is attached.
- The `hello` message carries `role`, defaulting to `"controller"`.

### D7a — Observer role — **Settled** (2026-08-04, M2.4 design)

`role: "observer"` is accepted: a read-only connection that receives
observation frames and errors, may send `hello` and `configure`, and is
refused non-fatally (`role_forbidden`) on every actuation message. Capped
by `bridge.maxObservers` (default 2, range 0–8; `0` disables the role).
`"director"` — a future role owning the camera while the controller owns
movement — is reserved as a name only.

**Rationale:** the motivating shape is two agents on one client (a brains
agent driving, a commentator agent watching and narrating a stream). Each
connection carries its own rate divisor and its own latest-wins coalescing,
so a slow observer drops only its own frames — the M2.3 backpressure model
applied per connection rather than per server.

**Safety asymmetry (normative):** observer loss is **not** an agent loss —
no release-all, no watchdog action, no effect on held controls — because an
observer can never actuate and so has nothing stuck. Controller loss is
unchanged. M5.1 inherits this: a missed pong from an observer drops that
observer; a missed pong from the controller releases all controls. Whether
the panic key also disconnects observers is left to M5.1 (recommendation:
no — panic should stop the puppet, not blind the stream).

**Admission ordering:** the mod cannot know whether a new connection wants
`controller` or `observer` until `hello` arrives, so admission necessarily
moves *after* hello-processing; `controller_attached` is no longer sent
pre-handshake and now carries the offending frame. This forces a hello
timeout (`bridge.helloTimeoutSeconds`, default 10) so unauthenticated
connections cannot accumulate. Corrected in `v1.md` in place with no version
bump: the mod is unreleased and no third-party agents exist, so there is
nothing to stay compatible with.

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

## D13 — MCP is a harness concern, not a transport — **Settled** (2026-08-04)

Marionette does **not** become an MCP server. The WebSocket bridge (D1)
stays the wire contract. MCP's place is inside an out-of-repo harness, as
the interface between it and whichever model fills each agent role — which
is where per-role model swapping is actually useful. It never talks to the
mod.

**Rationale:**

- **Cadence mismatch.** The core is a 20 Hz tick-synced stream with
  latest-wins coalescing and set-and-hold actuation. MCP is host-driven
  request/response with no notion of a stale frame to drop, and no host
  pulls at tick rate.
- **Event-driven agents still need a loop.** A commentator must speak
  unprompted when something happens; MCP hosts are turn-driven, so a harness
  loop is required either way. MCP does not remove it.
- **Dependency footprint.** The MCP Java SDK is Project Reactor + Jackson
  inside a NeoForge client mod, on the jar-in-jar path that already drew
  blood once (see the D1a dev-classpath addendum).
- **Agent-agnosticism** (CLAUDE.md). MCP is an agent-framework contract;
  making it the transport excludes the scripted, RL, and browser agents that
  D1 chose WebSocket to include.

## Minor open points (decide inside their milestones)

- Bridge lifecycle polish carried out of Phase 1 review — **partially
  resolved in M2.1** (2026-07-15): the disconnect latch is now gated on a
  completed hello (a probe that never sent hello no longer logs a spurious
  agent-disconnect), and duplicate-hello rejection is specced
  (`unexpected_hello`, non-fatal) and tested.
  **Remaining items resolved in M2.3** (2026-07-23): daemon thread factory
  (`marionette-bridge`, never blocks JVM exit); `exceptionCaught` causes
  logged at WARN before closing; shutdown ordering defined — controls
  release before bridge stop, and `stop()` sends close 1001 (going away)
  with a bounded event-loop shutdown; binary-frame violations now
  explicitly `close()` the session so pipelined text frames are ignored
  (the disconnect latch keys off `helloCompleted()`, which survives the
  close, so release-all safety still fires).
- JSON strictness on the wire — **resolved in M2.3** (2026-07-23):
  `MessageParser` parses with Gson `Strictness.STRICT` (RFC 8259) and
  rejects trailing content; unquoted keys, single quotes, and NaN are
  `invalid_json`. Strict-from-the-start avoids the breaking-change bump
  that tightening after release would have required. (Raised in final
  M2.1 review, 2026-07-15.)
- Backpressure policy (M2.3, 2026-07-23): writability-gated latest-wins
  coalescing in the transport — `WriteBufferWaterMark` 32/64 KiB is the
  hard bound; one-slot stash flushed on writability recovery; only
  observation frames coalesce. Ping interval fixed at 10 s until M5.1
  makes the watchdog timeout configurable.
- `configure` message (M2.3): deliberately generic session-settings
  envelope — M4.1's D2 section mask lands in it additively. Advertised
  as the `configure` capability flag. Processed even while no world is
  loaded, unlike actuation commands.
- Movement axes on the wire — **resolved in M3.1** (2026-07-29):
  boolean, key-like. Vanilla physics already smooths boolean input
  (acceleration/friction ramps), curved paths come from camera yaw
  (M3.2 smoothing), and booleans keep the D4 mixin's vanilla-parity
  guarantees (sneak speed cap, sprint gating, edge-stop) for free.
  Analog floats would add speed granularity, not smoothness; if a real
  use case appears they arrive later as an additive message behind a
  capability flag. One-shot presses ride `input.tap` (array of control
  names, one-tick press, `tap` capability flag) — the shape M3.3
  reuses for attack/use.
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
- Streaming while unfocused — **M2.2 half resolved** (2026-07-23): the
  `client.suppressPauseOnLostFocus` toggle (default on) suppresses the vanilla
  focus-loss pause **only while a hello-completed controller is attached**,
  via a `GameRenderer#render` mixin (a `@Redirect` swallowing the focus-loss
  `pauseGame` call; the planned `Minecraft#pauseIfInactive` target does not
  exist in 1.21.8) that never mutates `options.pauseOnLostFocus` (options.txt
  cannot be persisted wrong; the moment the agent detaches while unfocused,
  vanilla pauses on the next frame). With no agent connected, vanilla pause
  behavior is untouched. Still open for M5.1: how focus loss behaves in each
  precedence mode (pausing on focus loss is arguably a safety feature when a
  human walks away).
- Non-loopback bind handling — **resolved for M2.2** (2026-07-23): a
  non-loopback `bridge.bindAddress` is clamped to `127.0.0.1` with a WARN
  naming the ignored value; the bridge still starts, on loopback, so a config
  typo never silently kills external control. The explicit "I understand"
  opt-out gate for real non-loopback binding remains M5.1's
  loopback-enforcement item. Known gap to close in M5.1: the clamp check and
  the Netty bind re-resolve the configured string independently, so a
  *hostname* whose resolution changes between the two calls could in
  principle bind non-loopback — resolve once and pass the resulting
  `InetAddress` through when M5.1 hardens loopback enforcement.
- Mod-version ↔ protocol-version relationship in the changelog policy — M9.2.
