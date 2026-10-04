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

### M4.1 implementation and verification (2026-10-03)

Protocol **2** replaces flat observations with `player` at the frame root;
`protocol/v1.md` remains the canonical contract path. Protocol 1 is rejected
cleanly rather than maintaining two observation shapes. All reference clients
now offer version 2. This follows D6's existing breaking-change rule.

Each session defaults to `["player"]`; hello or configure may replace its
`sections` mask, including `[]` for cadence-only frames. Unimplemented section
names are rejected until their capabilities ship. The client samples player
state once per due tick and serializes once per distinct due mask. Network
code receives only JSON snapshots; it never accesses Minecraft state.

The player schema reports client-visible data. Food saturation and total XP
can differ from server bookkeeping; neither is inferred from other fields.
Effects use registry ids, tick durations (-1 for infinite), and zero-based
amplifiers, sorted by id for stable output.

Verification used a rendered `runClientDemo` in an isolated copy of the test
world, with temporary server setup instrumentation outside the repository.
Control commands and observations used the normal WebSocket bridge:

- Sprinting became true with nonzero velocity; sneak was observed separately.
- A nine-block fall reduced observed health from 20 to 14, matching an
  independent client-state read after landing.
- Submersion set `inWater` and reduced air below 295 ticks.
- Speed II reported amplifier 1 and decreasing duration; infinite night vision
  reported duration -1. XP level changed from 0 to 3.
- Smoothed rotation reported an intermediate yaw near 8 degrees before reaching
  90 degrees, rather than reporting the target immediately.
- An observer switched from empty frames to player frames at divisor 5 and
  back, independently of the controller. Invalid combined cadence/mask changes
  left both settings unchanged. A protocol-1 hello returned `supported: [2]`.
- The committed dashboard rendered live values from that client.

Headless tests cover strict mask parsing, omitted versus empty selection,
handshake defaults, v1 rejection before admission, per-session mask/cadence
isolation, and lazy shared sampling. Build, Python syntax and diff checks pass.
Sleeping/on-fire transitions, multiplayer synchronization, and a fresh long
backpressure soak were not separately tested. M3.3's physical alt-tab and human
animation acceptance remain pending. No temporary harness or world is packaged.

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
OR-merge base — it is not free from D4 and needs its own design. M5.1 does
this by releasing and pausing the agent on human input (D25).

### D4a — Interaction injection (attack/use/hotbar) — **Settled** (2026-08-05, M3.3 design)

Attack/use route through a second scoped mixin on
`Minecraft.handleKeybinds()`: three `@Redirect`s OR agent intent into
`KeyMapping.isDown()`, `KeyMapping.consumeClick()` (consume-once taps;
a hold's rising edge queues the click a vanilla press carries), and
`MouseHandler.isMouseGrabbed()` (vanilla gates held-mining on a grabbed
mouse — false while unfocused, which the M2.2 suppress-pause feature
makes a normal operating state). Vanilla runs
startAttack/startUseItem/continueAttack itself, so attack cooldown,
missTime, rightClickDelay, and use ticks stay vanilla. Hotbar is a
consumed one-shot intent applied via `Inventory.setSelectedSlot`, not
routed through key state. Rejected: KeyMapping forcing (held mining
dies unfocused; release stomps a human-held button — the D4 family
failure), direct invoker calls (duplicates vanilla orchestration).

**Screen-open rule (normative):** opening any screen releases agent
attack/use holds and drops pending interaction taps and hotbar selects;
holds do not resume on close. Vanilla releases every real key on
setScreen, and while a screen is open it sets `missTime = 10000` each
tick (decaying 1/tick) — an agent hold that "resumed" on close would
keep it huge and silently stall mining for ~8 minutes. Movement's
continue-through-GUI divergence (D4) is unaffected. Full design:
`docs/specs/2026-08-05-m3.3-attack-use-hotbar-design.md`.

Enforcement happens when `setScreen` returns with an open screen, as well
as at tick-pre. Vanilla can open a screen midway through `handleKeybinds`,
after tick-pre has run; waiting for the next tick would leave interaction
intent active during the remainder of that keybind pass. Check the actual
screen after NeoForge's opening event so canceled/replaced screens are respected.

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
no — panic should stop the puppet, not blind the stream). Resolved: panic and
its latch leave observers attached and admissible (M5.1a, D16b).

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

### D8a — Inventory execution and initial scope — **Settled** (2026-10-03, M3.4)

The additive `inventory` capability provides intent-level open, close, inspect,
move, hotbar swap, drop, and equip requests. The on-demand menu descriptor is
necessary to safely address actions before M4.2's periodic inventory observation.
It does not change the existing observation frame or protocol integer.

Each mutation checks the current screen's menu type, container id and server
state id, then validates the entire operation before routing through vanilla
`handleInventoryMouseClick`. Player aliases resolve through each slot's backing
inventory and container index, not its position in the menu. InventoryMenu has
no registered MenuType, so its wire name is `minecraft:inventory`.

Initial execution scope is survival/adventure player inventory and plain vanilla
storage menus (chest/barrel, hopper, dispenser/dropper, shulker). Crafting slots,
creative inventory, arbitrary menu subclasses and bundle click behavior are
refused. The addressing remains generic for future menu support. Move requires
the whole stack to fit; equip requires an empty matching armor slot; swaps
validate both directions to avoid vanilla's overflow/drop branch. An occupied
cursor prevents mutation. No operation chooses destinations for the agent except
`equip`'s mechanically determined armor slot.

Results acknowledge client prediction, not a server transaction. State ids are
server revisions and may remain unchanged across local predictions; agents must
re-inspect after synchronization. Multi-click moves finish in the same tick,
with checks after each click. Unexpected click behavior stops without silently
dropping or relocating cursor contents. See the
[implementation and verification record](specs/2026-10-03-m3.4-inventory-actions.md).

### D8b — Visible inventory interactions — **Settled** (2026-10-03, user request)

Keep the default wire behavior instant and add opt-in `animated: true`, advertised
as `inventoryAnimation`. The reference demo opts in by default. The client draws
a virtual agent cursor and supplies its coordinates to the current screen's
render path, so vanilla hover and carried-stack rendering show the actual action.
The OS cursor is not warped. Swaps retain vanilla's number-key semantics; drops
retain vanilla throw. Both approach their source and show a click cue.

One action at a time runs as tick-side clicks separated by frame-smooth cursor
travel and brief dwell. Results arrive after the final click. New mutations are
refused while busy; inspection remains available. Each step checks screen/menu
identity and expected slot/cursor contents. Release, controller loss, human
press/scroll input, world exit, resized/replaced screens, and content changes
cancel remaining clicks. Recovery is a vanilla click back into the original,
empty, valid source, only for an exact match of the carried stack in the same
live menu. Otherwise retain the cursor for human recovery or vanilla close.
Human input restores the real pointer's hover before vanilla handles it.

This extends the initial same-tick implementation in D8a; it does not add a
background job queue, change controller/observer ownership, or claim server
transaction guarantees. The user explicitly requested watchable cursor motion
and accepted retaining instant execution.

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

## D12 — License — **Settled** (2026-10-03)

The project owner confirmed MIT on 2026-10-03. This settles the license only;
publication, tagged releases and package uploads still require explicit approval.

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
- **Agent-agnosticism** (AGENTS.md). MCP is an agent-framework contract;
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
- Item-component serialization depth (enchantments, custom names) — **resolved in
  M4.2** (2026-10-04): an enumerated, capped set of stack extras; see D18.
- Crosshair ray-cast distance: vanilla reach vs. configurable gaze — **resolved in
  M4.3** (2026-10-04): vanilla reach only, no gaze distance; see D20.
- Hostility classification source; client-side aggro inference depth — **resolved
  in M4.4** (2026-10-04): a vanilla type table over class markers, and
  `targetingMe` only from synchronized attack targets, otherwise `"unknown"`;
  see D21.
- Block scan request shape, caps, budget and observer access — **resolved in
  M4.5** (2026-10-04): a box with a fixed 8192 volume limit, config radius and
  per-tick budget, controller only; see D22.
- Raw-input vs. active-Baritone-goal conflict policy; which Baritone settings
  are exposed — M6.2.
- Human-input precedence policy details — **implemented in M5.1** (2026-10-04;
  details decided pending owner review, D25). (The watchdog timeout default
  was resolved early: 2 seconds, D16a, 2026-10-03.)
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
  behavior is untouched. Per-mode focus-loss behavior was decided in M5.1
  (D25, item 6, pending owner review).
- Non-loopback bind handling — **resolved for M2.2** (2026-07-23): a
  non-loopback `bridge.bindAddress` is clamped to `127.0.0.1` with a WARN
  naming the ignored value; the bridge still starts, on loopback, so a config
  typo never silently kills external control. The explicit "I understand"
  opt-out gate for real non-loopback binding remains M5.1's
  loopback-enforcement item. Known gap to close in M5.1: the clamp check and
  the Netty bind re-resolve the configured string independently, so a
  *hostname* whose resolution changes between the two calls could in
  principle bind non-loopback — resolve once and pass the resulting
  `InetAddress` through when M5.1 hardens loopback enforcement. **Closed:**
  M5.1a passes the single resolved object to Netty and M5.1 adds the explicit
  opt-out gate on the same path (D25, item 9).
- Mod-version ↔ protocol-version relationship in the changelog policy — M9.2.

## D14 — Published Python client — **Settled; initial alpha published** (2026-10-03)

The owner selected the PyPI distribution `marionette-mc`, independent semantic
versioning, protocol 2 support, and explicitly pinned development prereleases.
Consumers require a pinned published package rather than copied examples or a
local shim. M2.5 implements packaging separately under `python/`, importing as
`marionette_mc`; its initial published version is `0.1.0a1` (PEP 440 spelling of
0.1.0 alpha 1). Python 3.11 is the baseline. Builds and tests pin dependencies;
client versioning is independent of mod and protocol integers. Publication remains subject to
explicit approval after build and review; confirming MIT does not authorize it.

The owner explicitly approved publishing the exact reviewed `0.1.0a1` wheel and
sdist as a **client-only development-alpha exception** to D16. They are now on
[PyPI](https://pypi.org/project/marionette-mc/0.1.0a1/). A fresh Python 3.11
installation from PyPI, with caching disabled and origin/hash recorded, passed
rendered observation, brief movement and release acceptance on 2026-10-03.
This completes M2.5, not a separate consumer integration acceptance. No further
publication or stable/mod release is authorized by this exception.

Approved SHA256 values:

- Wheel: `d084f4fd104b2b200724359c9dff0bc3b6572ad6deab0c327f5760aa63c2beca`
- Sdist: `de7f464ebbac2676920a41a2981280671b2640badb382665c3a8f25b2410a180`

The uploaded artifacts retain their reviewed pre-publication README text.
Later source-documentation updates do not change or replace those bytes.

## D15 — Cross-project coordination — **Settled** (2026-10-03)

The mod, its consumers and private system coordination remain separate repositories.
A system coordinator may assign bounded upstream milestones and validate published
client compatibility. It does not import consumer policy into this public project.
Product roadmap acceptance and end-to-end consumer acceptance are separate gates.
A published Python client is an early deliverable, not deferred release documentation.

## D16 — Development local trust and bridge safety — **Interim policy approved** (2026-10-03)

The owner approved native no-Origin loopback clients for development. Reject all
HTTP requests carrying an Origin header, including `null`, with HTTP 403 before
WebSocket upgrade. This temporarily narrows D1's browser-dashboard rationale;
shipped native Python observers/dashboard remain supported. No browser allowlist
or remote access is introduced. Local processes are trusted without tokens;
loopback and Origin filtering do not authenticate local users or processes.
Authentication/provisioning remains unresolved and blocks release, including
loopback-only mod and stable releases. The owner approved only the exact
`marionette-mc==0.1.0a1` client development-alpha exception recorded in D14;
this does not resolve authentication or authorize other releases. Revisit
browser support with that decision.

M5.1a resolves the configured address once to an InetAddress, clamps non-loopback
or unresolvable values to 127.0.0.1, and passes the same address object to Netty.
No non-loopback opt-out existed until M5.1 added the explicit
`bridge.iUnderstandNonLoopbackIsUnauthenticated` gate (D25). Bridge limits and overload behavior are normative
in protocol/v1.md under `bridgeSafety`. Pong timeout defaults to two seconds
(D16a; originally five); local panic defaults to rebindable F8 and latches
controller admission off until a separate re-arm key (default F9) clears it
(D16b). These emergency controls did not settle the M5.1 human-precedence
modes or per-mode focus-loss policy; D25 does.

The earlier M2.3 watermark described as a hard bound was insufficient for
reliable replies and tasks waiting for the event loop. M5.1a reserves output
bytes before scheduling writes, caps inbound work per connection, prioritizes
release, and caps pending connections from TCP accept rather than only upgrade.
The client checks safety at both tick and render boundaries, including paused
screens; world exit severs the controller to invalidate stale commands.

### D16a — Pong watchdog default: 2 seconds — **Settled** (2026-10-03, owner decision)

A consumer measured that a frozen (SIGSTOP) or network-blackholed agent kept the
player walking for 4.2–5.2 seconds (about 18–22 blocks) at the old 5-second
default. The owner decided to fix this in the mod only, by lowering the default
`bridge.pongTimeoutSeconds` into the 1–2 second range. The range (1–60),
restart-required semantics, ping cadence rule (min(1 s, timeout/4)), close code,
observer isolation and the wire protocol are unchanged; this is not a protocol
or capability change.

The default is **2 seconds** (pings every 0.5 s). Rendered measurements are in
the [M5.1a safety record](specs/2026-10-03-m5.1a-safety-verification.md#watchdog-default-follow-up--2026-10-03):

- At 2 seconds, freeze/blackhole detection took 2.02–2.39 s (8.9–10.4 blocks),
  and a healthy agent with pauses of up to 1 s ran for 154 s with no
  disconnects. A single pause of 1.5 s survived; one of 1.9 s was sometimes
  dropped.
- At 1 second, exposure fell to 0.84–1.15 s (3.7–5.0 blocks), but the same
  pause profile was spuriously disconnected within 53–60 s in both runs, and
  single pauses of 0.9–1.0 s were sometimes dropped.

A healthy agent survives pauses up to the timeout minus one ping interval
(1.5 s at the default), and sometimes slightly longer. One second therefore
cannot tolerate ordinary sub-second agent, GC or event-loop pauses. Two seconds
does, while still cutting walking exposure by more than half. A spurious
disconnect fails safe but drops held intent without replay, so frequent ones
make agents unusable. Operators may still set 1 second.

Rejected for now: expiring held inputs after a lease, protocol changes (for
example, a liveness or lease field), and a client keepalive in the published
Python client. Revisit them only with a new owner decision.

### D16b — Panic latch and separate re-arm key — **Settled** (2026-10-04, owner decision)

An end-to-end consumer integration check found that local panic released every
control and severed the controller within a millisecond (close 1008
`local panic`), but nothing stopped the agent from reconnecting: a reconnecting
agent drove the player again 0.4–2.6 s after each of 25 physical panic presses.
Panic therefore did not restore human control. The owner decided:

- Panic **latches**. While latched the mod refuses every new controller hello.
  Observer admission and attached observers are unaffected.
- The panic key only ever disengages. Pressing it again, or repeatedly, never
  re-enables agent control.
- A **separate**, rebindable "Allow agent control" key, registered in the
  Controls menu (default F9; vanilla 1.21.8 does not bind F9), clears the latch.
  Re-arming only clears the latch: it admits, grants, restores and replays
  nothing. It is handled only during gameplay with no screen open, so typing
  in a text field or rebinding a key cannot re-arm. If both mappings share a
  key, panic wins.
- The latch persists across world exit and rejoin until re-armed. It does
  **not** persist across a client restart: it is in-memory state and each
  launch starts unlatched. Restarting Minecraft is itself a deliberate human
  action that requires no running agent connection, and persisting a latch
  to disk would add a stale-state failure mode without improving safety
  during a session.

Signal (protocol/v1.md, Emergency release): a latched refusal is an `error`
with the new fatal code `panic_latched` followed by close 1008 with reason
`panic_latched`; the sever itself is unchanged (close 1008 `local panic`, no
error frame). This reuses the handshake-rejection mechanism, so the published
`marionette-mc==0.1.0a1` already raises `ServerError` with code `panic_latched`,
distinct from `Disconnected(1008, "local panic")`, `controller_attached`, the
`pong timeout` watchdog and transport loss. No wire field was added; the
additive `panicLatch` capability lets observers feature-detect the latch.
Latch and controller-slot admission share one lock, so a hello racing the
panic is either severed or refused, never left attached; the client tick path
additionally treats any controller as absent while latched. A toast reports
"disabled", "still disabled" (repeat press and rejoin while latched) and
"re-enabled".

Rejected:

- **Toggle on the panic key** (press again to re-enable): a panic key must be
  safe to mash. Toggling makes the outcome depend on press parity, so a
  frightened double press would hand control straight back.
- **Hold panic to re-arm**: overloads the emergency control with a gesture
  that a held or stuck key can perform unintentionally, and is still not
  discoverable or rebindable separately in the Controls menu.
- A reconnect cool-down or timed latch: control would return without a human
  decision.

## D17 — One-shot events: opt-in, sequenced, never coalesced — **Settled** (2026-10-04, M4.6)

Events (`protocol/v1.md`, Events) are separate `event` messages with an
envelope of `event` kind, per-connection `seq`, `worldSession`, `tick` and
`basis`. They are delivered only to sessions that subscribe with
`events: true` in `hello` or `configure`; the `events` capability is additive.

**Why opt-in:** the published `marionette-mc==0.1.0a1` decoder rejects any
unknown message type, and its reader then ends the session. Sending events
unconditionally would disconnect every existing client, so the protocol
integer stays 2 and only subscribers receive the new type. The in-repository
client now subscribes on request, ignores unknown message types and event
kinds, and keeps events in their own bounded queue.

**Delivery:** events are recorded on the client thread and written at once,
so events, observations and tick-side replies share one write order: an event
stamped with tick T follows observations of earlier ticks and precedes the
observation of T. An older stashed observation is dropped rather than sent
after an event. Events never carry `id`. Each connection has its own bound
(1024 events not yet accepted by the socket, plus the shared 256 KiB reliable
budget). Exceeding it closes only that connection (1013 `event overflow`)
instead of skipping or coalescing an event, so a received stream is always a
gapless prefix. There is no replay and no exactly-once claim across reconnects.

**Facts:** `basis` is `server` for packet-reported facts and `client` for local
predictions (block breaking). Unknown values are explicit `null`, never zero.
Damage pairs the server's damage report with server-synced health, measured
against a per-player baseline because health can arrive in the health packet
or in entity data. Unpaired halves are reported with `null` fields. `/title`
action bars, client-local messages and other mods' suppressed chat are not
reported.

Rejected: events inside observation frames (they would coalesce away),
unconditional delivery (breaks 0.1.0a1), a protocol bump (the change is
additive), and resumable sequence numbers across connections (would imply a
replay buffer and exactly-once guarantees the mod cannot keep).

### M4.6 implementation and verification (2026-10-04)

`./gradlew build` passed with 250 tests (229 before). In-repository Python
client tests passed on 3.11 and 3.14 (27 tests, mypy strict clean). A rendered
client in an isolated copy of the test world, driven by temporary server-side
setup outside the repository, delivered through real WebSocket sessions:

- `/damage 3` → `damage` (generic, amount 3, health 17); `tellraw` → system
  `chat`; `say` → player `chat` (`minecraft:say_command`); a dropped diamond
  stack → `item_pickup` (3); a controller-held attack on glass → `block_broken`
  (basis `client`); `/kill` → `damage`, `death`, the death-message `chat`,
  `respawn`; teleport to the Nether → `dimension_change`; `/kill` there →
  `damage` (source `null`: the server sent no damage report), `death`, `chat`,
  `respawn`, `dimension_change`. Each appeared exactly once, in order, on both
  a controller and an observer, with identical content and independent `seq`.
- Every event followed the observations of earlier ticks and preceded the
  observation of its own tick, which showed the reported health. Three
  inventory requests sent during chat bursts were answered only by their own
  `inventory_result` while events arrived before and after them.
- A non-reading subscriber with a small receive window was closed with
  `event overflow` during a 2000-event burst; it had received a contiguous
  prefix (seq 1–184), while the controller received all 2000 in order.
- Published 0.1.0a1, never subscribing, observed and controlled (configure,
  observations, inventory inspect, release) alongside the event subscribers and
  received only `hello`, `observation` and `inventory_result` frames.

Not demonstrated in the rendered run: overlay `action_bar` system messages
(no command emits one; covered by unit tests), arrow pickups, chat-delay and
blocked-player filtering, and multiplayer servers. M3.3's physical alt-tab and
human animation acceptance and the remaining M5.1 items stay outstanding.

## D18 — Inventory observation: opt-in section, enumerated item detail — **Settled** (2026-10-04, M4.2)

The `inventory` observation section (`protocol/v1.md`, Inventory section) is
advertised as `inventoryState` and selected through the existing D2 `sections`
mask. It carries the selected hotbar index, the held stack, hotbar, main
inventory, armor, offhand and the visible container menu. The menu descriptor
is the same object `inspect` returns, so observation mirrors the D8 action
addressing exactly: menu type, container and state ids, slot indexes, player
aliases and the carried stack.

**Compatibility:** the published `marionette-mc==0.1.0a1` validates sections
against `["player"]` and therefore cannot select the new section; sessions that
keep the default mask receive byte-for-byte the same frame shape as before. New
descriptor fields in `inventory_result` (`slotCount`, `operations`, `refusal`,
`refused`, stack extras) are additive keys, which 0.1.0a1 keeps. No new message
type exists, so D17's unknown-type hazard does not arise. Protocol stays 2.

**Item detail (the M4.2 minor decision):** stacks always have `item` and
`count`. Non-empty stacks add only an enumerated list of extras: durability
(`damage`, `maxDamage`), a custom name (plain text, cut at 64 UTF-16 characters
with `nameTruncated`), enchantments and stored enchantments (sorted by id, at
most 8 each, with a `...Truncated` flag), and a base potion id. Lore, custom
data, container contents (shulkers, bundles), attribute modifiers, custom
effects and similar components are deliberately not serialized: they are
unbounded or mod-defined, and agents can request richer detail as a later
additive capability. A whole section, or an `inspect` menu, is limited to
96 KiB of JSON; beyond that every stack drops to id/count/durability
(`reduced`), then trailing menu slots are omitted (`truncated`, with the real
`slotCount`). This keeps frames under the existing 128 KiB observation and
reply caps instead of silently dropping frames or disconnecting on a large
modded menu.

**Observable but not mutable:** `inspect` and the section describe any
container screen in any game mode. The descriptor lists the mutating
`operations` the mod would attempt, or a `refusal`
(`player_unavailable`, `unsupported_menu`, `busy`, `cursor_occupied`). In
menus within the mutation scope, slots the mod never acts on carry `refused`
(`crafting`, `inactive`, `bundle`). Mutation scope itself is unchanged (D8a);
widening it is M3.5. Previously `inspect` failed with `inventory_unavailable`
for such menus; it now succeeds, which no client could have relied on as a
success path.

Rejected: always-on inventory in every frame (breaks the D2 default and
inflates every 0.1.0a1 frame), a separate inventory message type (fatal for
0.1.0a1, D17), raw component/NBT dumps (unbounded, version-specific), and
omitting player slots from the menu descriptor (breaks D8 symmetry with
`inspect`).

### M4.2 implementation and verification (2026-10-04)

`./gradlew build` passed with 261 tests (250 before). In-repository Python
client tests passed on 3.11 and 3.14 (33 tests, 27 before; mypy strict clean).
A rendered client in an isolated copy of the test world, provisioned by a
temporary client-side harness outside the repository (server-console triggers,
dumps and screenshots only), was observed through real WebSocket sessions with
the in-repository client (observer, player+inventory, divisor 1) while a
controller acted through the protocol:

- At nine checkpoints (baseline, survival inventory open, after an animated
  move, after picking up a dropped item stack, after eating a golden apple
  with a bounded use hold, chest open, after a chest-to-player move, furnace
  open, final) every one of the 41 player slots and every menu slot (index,
  alias, item, count, durability, name, enchantments, potion) matched both the
  rendered screen's menu and the integrated server's authoritative inventory
  and menu; menu ids and state ids matched the screen. Screenshots agree.
- Updates: the move, pickup (+4 apples) and consumption (golden apples 3 → 2,
  one transition during the hold) each appeared in the stream; during the
  animated move the frames showed the carried stack with refusal `busy`.
- Chest: `minecraft:generic_9x3`, 63 slots, chest slots 0–26 then
  `main.0`–`main.26`, `hotbar.0`–`hotbar.8`, operations
  `move, swap, drop, close`; a protocol move from slot 13 to `main.6` was
  reflected in both the menu and the player section.
- Unsupported mutation menu: a furnace reported `minecraft:furnace`, its
  contents, no operations and refusal `unsupported_menu`; a move was refused
  with `inventory_unavailable`.
- Caps: a 70-character name arrived as 64 characters with `nameTruncated`; a
  sword with nine enchantments listed the first eight by id with
  `enchantmentsTruncated`. The largest section was about 7 KiB.
- Published 0.1.0a1, unmodified, observed the whole run as an observer and
  later controlled the client with the furnace open: it received only
  `hello`, `observation` (always `player`/`tick`/`type`) and one
  `inventory_result`, and decoded the new unsupported-menu descriptor. Offline,
  its decoder also accepted every recorded inventory frame.

Not demonstrated in the rendered run: the `reduced`/`truncated` size fallbacks
(no vanilla menu approaches 96 KiB; covered by unit tests), `player_unavailable`
and `cursor_occupied` refusals, bundle and inactive slot refusals, and modded
menus (M3.5). M3.3's physical alt-tab and human animation acceptance and the
remaining M5.1 items stay outstanding.

## D19 — Modded storage: structural support analysis, reasons, test-only mod — **Settled** (2026-10-04, M3.5)

Inventory mutation support is decided by a structural **storage analysis** of
the live menu, never by a menu or slot class name or registry id
(`protocol/v1.md`, Storage support; capability `inventoryStorage`). A menu is
storage when (1) of the vanilla menu methods its class hierarchy overrides only
shift-click transfer, validity, close, pick-all and drag eligibility, none of
which the mod's PICKUP/SWAP/THROW clicks use; (2) it has no synchronized data
slots (furnaces, brewing stands, crafters, enchanting tables); (3) every slot
overrides only placement, pickup, capacity, activity and appearance rules, plus
vanilla's equipment hook for player-inventory slots, so result slots and
take/insert side effects are excluded; and (4) no two slots share a container
position. Under those rules vanilla click code alone determines the outcome
from the same predicates the preflight evaluates. The player's own inventory
menu keeps its existing scope by identity. Unit tests run the analysis against
the real 1.21.8 classes: the previously allow-listed chest, hopper,
dispenser and shulker menus still qualify, and crafting, anvil, enchanting,
loom, stonecutter, merchant, lectern and beacon menus and result,
furnace-result, potion and crafter slots do not.

Prediction is not trusted blindly: every click is checked against the whole
menu (the involved slots and the cursor hold exactly the predicted stacks, and
every other slot is unchanged). A deviation stops the sequence with
`unexpected_click`, leaving any carried stack visible. Server synchronization
stays authoritative: results remain client predictions, and a correction
arriving mid-animation cancels with `contents_changed` and the existing safe
recovery.

Descriptors gain `support` (`scope` `player`/`storage`/`null` and the sorted
failed rules); `inventory_unavailable`, `inventory_impossible` and
`inventory_cancelled` errors gain a machine-readable `reason`. Both are
additive keys on existing message types and the protocol integer stays 2, so
the published `marionette-mc==0.1.0a1` (which ends its session only on unknown
message *types*, D17) is unaffected; this was verified live. Rejected:
class-name or namespace allowlists (unverifiable for unknown mods), per-mod
adapters (M3.8), widening to processing menus (M3.6), a new message type, and
recovery operations for a server-corrected cursor (the stack stays visible for
the human or vanilla close, as before).

**Test-only third-party mod.** Iron Chests by progwml6 (cpw, alexbegt,
progwml6), version `1.21.7-neoforge-16.5.4`, declared for Minecraft
1.21.7–1.21.8 and NeoForge 21.7.25+, licensed GPL-3.0-only. Official source:
the Modrinth project `iron-chests` (https://modrinth.com/mod/iron-chests,
file https://cdn.modrinth.com/data/P3iIrPH3/versions/gBAj2t9I/ironchest-1.21.7-neoforge-16.5.4.jar),
resolved through Modrinth's official Maven as
`maven.modrinth:iron-chests:1.21.7-neoforge-16.5.4`, SHA-256
`6e9f9add5556ea1357675ebc8b931d59b1fcf01b3e97d76d87200546e7052582`.
It is only used by the opt-in `installStorageCompatMod` task, which verifies
that checksum and copies the jar into the ignored `run/mods`
(`removeStorageCompatMod` deletes it). It is never bundled, published, compiled
against or required, and the jar is not committed. Its menus use their own
`AbstractContainerMenu` subclass with plain slots (diamond chest: 108
container slots, 144 in all) and a restricted dirt-chest slot, so it exercises
large layouts, a modded restricted slot and a menu class Marionette has never
named.

### M3.5 implementation and verification (2026-10-04)

`./gradlew build` passed with 276 tests (264 before). In-repository Python
client tests passed on 3.11 and 3.14 (36 tests, 33 before; mypy strict clean).
A rendered client in an isolated copy of the test world, with Iron Chests
installed by the task above and provisioned by a temporary harness outside the
repository (server-console setup, dumps, screenshots and one client-only slot
desynchronization), was driven only through the protocol (smooth looks, use
taps and inventory requests; no movement). At 25 checkpoints, all 191 checks
passed: the protocol observation, the rendered screen's menu and the integrated
server's authoritative menu agreed slot for slot, including the cursor, after
synchronization had settled.

| Case | Vanilla (double chest, 90 slots; shulker box) | Modded (Iron Chests diamond chest, 144 slots; dirt chest) |
|---|---|---|
| Support descriptor | `storage` | `storage` (class never named) |
| Large layout + animated cursor | far slot → player, cursor carried the real stack | slot 107 → player, same |
| Restricted slot | shulker box into shulker slot: `destination_rejects`; dirt accepted | stone into dirt chest: `destination_rejects`; dirt accepted |
| Full destination / other item | `destination_full` / `destination_mismatch`, nothing clicked | same |
| Server correction of a wrong prediction | client copy of a stone slot emptied locally; the move predicted success, the server swapped, cursor corrected to stone on both sides; `cursor_occupied`; vanilla close returned it | same |
| Server update mid-animation | `inventory_cancelled`/`contents_changed`, stack recovered into its source | same |
| `release` mid-animation | `released`, stack recovered | same |
| Controller disconnect mid-animation | release-all, stack recovered into its source | same |
| Unsupported menu (furnace) | observable; `support` `null` with `menu_data`, `slot_behavior`; move and close refused `unsupported_menu` | — |

Published 0.1.0a1, unmodified, observed the whole run (1169 player-only
observations, only `hello` and `observation` frames) and, as a controller with
the diamond chest open, decoded the `support` descriptor and an error carrying
`reason` before releasing cleanly.

Not demonstrated in the rendered run: human-input and panic cancellation during
storage actions (no human present; unchanged code paths from D8b), swap, equip
and drop in modded menus (same verified-click path as move; unit and vanilla
coverage only), `shared_slots` and `click_behavior` on a live modded menu
(unit tests only), and multiplayer servers. The 854×480 development window clips
the top and bottom rows of the 276-pixel-tall diamond chest screen; actions
address slots by index and were unaffected. M3.3's physical alt-tab and human
animation acceptance and the remaining M5.1 items stay outstanding.

## D20 — Crosshair target and world context: vanilla reach, opt-in sections — **Settled** (2026-10-04, M4.3)

The `target` and `world` observation sections (`protocol/v1.md`, Target and
World sections) are advertised as `targetState` and `worldState` and selected
through the existing D2 `sections` mask, like D18's inventory section.

**Reach (the M4.3 minor decision):** `target` is exactly vanilla's crosshair
pick, `Minecraft.hitResult`, which the game recomputes every rendered frame
from the camera entity. It is bounded by the player's own interaction-range
attributes (survival 4.5 blocks for blocks and 3.0 for entities, creative 5.0
for both, plus any modifiers), reported as `reach` in every target. The mod
casts no ray of its own and there is no configurable extended "gaze" distance.

- The target then means one thing: the block or entity the crosshair outline
  shows and that `attack`/`use` will act on. A gaze ray would create a second
  "what am I looking at" that can disagree with both the outline and the
  outcome of an interaction.
- It costs nothing extra per tick and cannot drift from vanilla behavior
  (fluids skipped, unpickable entities skipped, an entity beyond entity reach
  hiding a block behind it), including under mods that change reach.
- Far perception belongs to the M4.4 entity list and M4.5 block scans, which
  are bounded by their own configured caps. If agents need a longer look ray
  later, it is an additive opt-in section or field that leaves `target`
  unchanged.
- F3's "Targeted Block" uses a separate fixed 20-block ray. The two agree
  within reach; beyond it F3 can show a block while `target` is `"none"`. This
  difference is documented in the protocol rather than hidden.

**World context:** `dimension`, `dayTime` with derived `timeOfDay` and `day`
(F3's day), `weather` from vanilla's thresholds with the raw `rainLevel` and
`thunderLevel`, and light at the feet block (`feet`, F3's "Block") as
`block`/`sky`/`combined` (F3's "Client Light") plus `effective`, the
time- and weather-adjusted local brightness. All are client-known values.

**Compatibility:** 0.1.0a1 validates sections against `["player"]`, so it
cannot select either section; default-mask frames are unchanged. The new
capability flags are additive hello keys. No message type was added. Protocol
stays 2.

Rejected: a configurable gaze distance in `target` (two meanings of target;
F3 already diverges at its fixed 20 blocks), always-on target/world data in
every frame (changes 0.1.0a1 frames and the D2 default), block state
properties in the target (unbounded for modded blocks; a later additive field),
and per-position precipitation (biome-dependent; `weather` is level-wide).

### M4.3 implementation and verification (2026-10-04)

`./gradlew build` passed with 286 tests (277 before). In-repository Python
client tests passed on 3.11 and 3.14 (42 tests, 36 before; mypy strict clean).
A rendered client in an isolated copy of the test world, provisioned by a
temporary client-side harness outside the repository (server-console commands,
per-tick reads of vanilla's hit result and the literal F3 debug text, dumps and
screenshots), was observed through a real WebSocket session (observer,
`player`/`target`/`world`, divisor 1) while a controller sent only smooth looks
across a prepared floating scene. No movement or jump input was used.

| Look at | Observed `target` | Vanilla hit result and F3 |
|---|---|---|
| Oak log, south face | block, its position, `south`, 2.74 | same block and face; F3 Targeted Block same |
| Emerald block overhead | block, `down`, 1.70 | same |
| Gold block to the west | block, `east` | same |
| Bricks to the south | block, `north` | same |
| Diamond floor block | block, `up` | same |
| Iron block to the east | block, `west` | same |
| Pig at 1.8 blocks | entity, `minecraft:pig`, its id | same entity; F3 Targeted Entity `minecraft:pig` |
| Open sky | none | miss; F3 shows no targeted block |
| Sheep at about 3.7 blocks (entity reach 3) | none | miss; F3 shows no targeted entity |
| Lapis block at about 5.1 blocks (block reach 4.5) | none | miss; F3's 20-block ray still names the lapis block |
| Nether roof (after a dimension change) | block bedrock, `up` | same |

Every one of 1677 consecutive frames, including those during pans, matched
vanilla's hit result at the same tick end. World context, driven by commands,
matched F3 (dimension line, feet block, day, client light) and the integrated
server (dimension, day time, weather, sky and block light) at all 24
checkpoints, 357 checks in total: time 13000 and 18000 lowered `effective`
light to 9 and 4; rain and thunder reported `rain`/`thunder` with levels 1.0
and `effective` 12 and 10; a glowstone block beside the feet raised block light
to 14; a stone roof at night reduced sky light to 13; the Nether reported
`minecraft:the_nether` with no sky light.

Published 0.1.0a1, unmodified, observed the whole run (1676 player-only
observations; only `hello` and `observation` frames), and as a controller it
received unchanged frames, panned the camera and released cleanly. It cannot
select the new sections itself (its local validation refuses them). Offline,
its decoder accepted every recorded frame carrying the new sections.

Not demonstrated in the rendered run: spectating another entity (camera entity
other than the player), creative-mode reach, reach modifiers and modded blocks
or entities, multiplayer servers, and day counts above zero (unit tests cover
the arithmetic). M3.3's physical alt-tab and human animation acceptance and the
remaining M5.1 items stay outstanding.

## D21 — Nearby entities: type-table hostility, synchronized-target aggro only — **Settled** (2026-10-04, M4.4)

The `entities` observation section (`protocol/v1.md`, Entities section) is
advertised as `entityState` and selected through the D2 `sections` mask, like
D18 and D20. It lists the entities the client has loaded within
`observation.entityRadius` of the player's feet, nearest first, at most
`observation.entityMaxCount`, with `total` and an always-present `truncated`
flag. Both caps are the existing M2.2 config values, now enforced and live; they
apply to every session alike rather than being negotiable per session.

**Hostility source (minor decision):** a fixed per-type classification, not
current behavior. Precedence: players, dropped items, then a mod-maintained
table of vanilla registry ids, then class markers for everything else (vanilla
`NeutralMob` is neutral; `Enemy` or the monster spawn category is hostile; any
other mob is passive; non-mobs are other).

- The vanilla table only needs the types whose class does not say what players
  experience: spiders, cave spiders and piglins are `Enemy` subclasses, and
  goats, llamas, trader llamas, pandas and dolphins are plain animals, but all
  are neutral (attack when provoked or under conditions). The resulting table
  for every vanilla type is published in the protocol, and a unit test
  classifies each 1.21.8 entity class and compares it with that published
  table, so code and contract cannot drift.
- Tags were rejected: vanilla has no hostility tag, and the existing entity
  tags (`undead`, `raiders`, `arthropod` and similar) describe other things.
  A new tag would be server data that a vanilla server never sends. Class
  markers already give modded entities a sensible default.
- Dynamic mood is deliberately not part of the classification: an angered wolf
  stays neutral and a tamed one too. What an entity is doing now belongs in
  `targetingMe`.

**Aggro inference depth (minor decision): none.** `targetingMe` is `"yes"` or
`"no"` only when the entity synchronizes its attack target to clients and that
target is set: in vanilla, guardians and elder guardians (beam target) and the
wither (main target). Everything else, and those entities while no target is
synchronized, is `"unknown"`. Zombies, skeletons, creepers and other mobs never
tell the client their target, so no client-side signal can confirm it; distance,
facing, raised arms, anger timers and recent damage are heuristics that fail
with several players, pets, golems and villagers around. Agents may add their
own heuristics on top of the reported facts. Values are strings so later
evidence classes can be added without a protocol bump.

Other choices: positions and health are client-visible (interpolated) values,
not server state; `velocity` is the client's position change over the last
tick, which is what was rendered, rather than the client's rarely meaningful
delta movement for remote entities; parts of multipart entities are not
listed; players carry their profile `name` and item entities the dropped stack's
id and count. No other per-entity detail (equipment, custom names, effects,
passengers) is included; additions would be optional fields under the same
capability.

**Compatibility:** 0.1.0a1 validates sections against `["player"]`, so it
cannot select `entities`; default-mask frames are unchanged and no message type
was added. Protocol stays 2.

Rejected: entity-type tags as the classification source; dynamic hostility from
current behavior; inferring aggro from heuristics; reading privileged server
state (the integrated server's AI targets) in singleplayer, which would make
the field mean different things in singleplayer and on servers; per-session
radius and count negotiation (config is the ceiling; per-session limits could
later be added through `configure` without a bump).

### M4.4 implementation and verification (2026-10-04)

`./gradlew build` passed with 295 tests (287 before). In-repository Python
client tests passed on 3.11 and 3.14 (48 tests, 42 before; mypy strict clean).
A rendered client in an isolated copy of the test world, provisioned by a
temporary client-side harness outside the repository (server-console commands,
and the integrated server's own entity state recorded on the server thread
every server tick, paired with each client tick), was observed through a real
WebSocket session (observer, `player`/`entities`, divisor 1; 1217 gapless
frames, none coalesced). The player stood still on a floating arena under
resistance and regeneration; no controller input was sent except one smooth
look by the published client (no movement, no jump, no holds).

| Scene | Observed `entities` | Integrated server |
|---|---|---|
| Zombie, cow and dropped diamonds ×3 | all three listed: zombie `hostile`, cow `passive`, item `item` with `minecraft:diamond` ×3; nearest first | same ids and types; the zombie walked 12 blocks to the player and the cow 5.6 blocks |
| Their positions over the 18-second scene | within 0.1 blocks of the server position (medians below 0.001) | at the server tick a few ticks earlier, the interpolation lag |
| Guardian in a pool beside the player | `hostile`, `targetingMe` `"yes"` in 275 frames | its beam was on the player in 270 of them; the other 5 within the synchronization lag |
| Guardian about 29 blocks away hunting a squid | `"no"` in 166 frames | its beam was on the squid |
| Zombie attacking the player | `"unknown"` in all 691 frames | its AI target was the player throughout |
| 100 pigs, radius 32, cap 64 | `total` 77, 64 listed, `truncated` true | 77 within 32 blocks; the listed ids are exactly the 64 nearest |
| Caps changed live to radius 8 and cap 10 | `radius` 8, `maxCount` 10, `total` 17, 10 listed, `truncated` true | the 10 nearest of 17 |
| Caps changed live to radius 64 and cap 256 | `total` 102, 102 listed, `truncated` false | 102 within 64 blocks |
| After cleanup | `total` 0 | no entities within 60 blocks |

In every frame the count equalled `min(total, maxCount)`, `truncated` equalled
`total > maxCount`, entries were sorted by distance, and every distance was
within the radius (86,910 checks, 0 failures). Selections matched the server's
nearest set in all 335 settled busy frames; in 2 more the summon was still
arriving on the client (71 of 77 spawn packets received). Health matched the
server, allowing the same few ticks of lag, in all 26,448 comparisons. `targetingMe` changes reached the client 1 to
6 ticks after the server's beam changed.

Published 0.1.0a1, unmodified, observed the whole run (1216 player-only
observations; only `hello` and `observation` frames), and as a controller it
received unchanged frames, panned the camera and released cleanly. It cannot
select the new section itself (its local validation refuses it). Offline, its
decoder accepted every recorded frame carrying the section.

Not demonstrated in the rendered run: the wither's target and elder guardians
(same synchronized-data path as the guardian, unit-tested mapping), other
players and their names, modded entities, multiplayer servers and their entity
tracking ranges, and entities in vehicles. M3.3's physical alt-tab and human
animation acceptance and the remaining M5.1 items stay outstanding.

## D22 — Block scan: controller request, box caps, budgeted reads — **Settled** (2026-10-04, M4.5)

D3 settled the shape (on-demand request/response, a `palette` of block ids
plus flat y→z→x indices as JSON, chunked reads, config radius cap, reserved
deltas and binary frames). M4.5 fixed the remaining details
(`protocol/v1.md`, scan and scan_result), advertised as the additive
`blockScan` capability:

- **Request:** a box by `size` and an optional absolute `min` corner; without
  `min` the box is centred on the feet block. A box rather than a radius lets an
  agent ask for the non-cubic shapes it needs (16×8×16) and re-scan an exact
  region; the result always echoes the box actually read.
- **Caps:** every position must lie within `observation.blockScanRadius`
  (4–32, default 16, live) of the feet block on each axis, checked when the
  request is applied, and the volume is at most 8192 positions, a fixed
  protocol limit that keeps every vanilla result far inside the 128 KiB reply
  limit (the measured 32×8×32 result was 17 KB). Over-cap requests fail with
  `scan_refused` (`over_radius`/`over_volume`) carrying `limits`, so agents can
  shrink a request without guessing. An oversized result (only possible with
  extreme modded ids) becomes `scan_cancelled` `too_large`, never a 1013 close.
- **Work budget:** reads happen at the end of client ticks in index order, at
  most `observation.blockScanBlocksPerTick` (64–8192, default 1024, live)
  positions per tick, so 16×8×16 spans two ticks. The result is sent before
  that tick's observation, with `startTick`/`tick`; it is not an atomic
  snapshot. One scan runs at a time (`busy` otherwise).
- **Controller only.** D7a keeps observers to `hello` and `configure`;
  inventory inspection set the precedent for read-only requests. Opening scans
  to observers would be a separate decision.
- **Block ids only.** No block states; `null` palette entries mark unloaded
  chunks; outside the build height reads `minecraft:void_air` as vanilla does.
- **Cancellation:** `release` and safety releases cancel with `released`,
  world exit with `world_exit`, a replaced client level with `level_changed`.

**Compatibility:** published 0.1.0a1 has no scan API and never sends `scan`,
so it never receives `scan_result` or the new error codes; the extra hello
capability is ignored. Protocol stays 2.

Rejected: a periodic or observation-section scan (D3); a radius-only request;
server-side or integrated-server block access (the field would mean different
things in singleplayer); queueing several scans (an unbounded queue, or a
second bound, for little gain over `busy`); per-session budgets.

### M4.5 implementation and verification (2026-10-04)

`./gradlew build` passed with 315 tests (295 before). In-repository Python
client tests passed on 3.11 and 3.14 (57 tests, 48 before; mypy strict clean).
A rendered client in an isolated copy of the test world, provisioned by a
temporary client-side harness outside the repository, was driven through a
real WebSocket controller session. Ground truth was the integrated server's own
blocks for each scanned box, read on the server thread; the result was decoded
with the client's `scan_block`/`scan_blocks`.

| Scan | Positions | Mismatches vs server | Ticks |
|---|---|---|---|
| Natural (superflat) terrain, 16×8×16 centred on the feet | 2048 | 0 | 2 |
| Prepared varied area, 16×8×16 at an explicit corner (18 solid kinds, air, an enclosed water pool and lava pool; 20 palette entries) | 2048 | 0 | 2 |
| The same box centred on the feet above it | 2048 | 0 | 2 |
| The prepared area again after three refusals | 2048 | 0 | 2 |
| The prepared area at a live budget of 64 blocks per tick | 2048 | 0 | 32 |
| 32×8×32 (8192) at the default budget | 8192 | 0 | 8 |
| The same at a budget of 8192 | 8192 | 0 | 1 |

Over-radius (horizontally and vertically) and over-volume requests were refused
with `scan_refused`, the right reason and `limits`, and the session continued
(the next observation and scan succeeded). A second concurrent scan was refused
as `busy`; `release` during a 32-tick scan cancelled it (`scan_cancelled`,
`released`); an observer's scan was refused with `role_forbidden` and the
observer stayed connected. 652 back-to-back scans in the timing windows all
matched the server's blocks.

Frame and tick timing, 20-second windows (the unfocused window is held at
30 fps by vanilla's inactivity limit, so frame intervals sit at 33.4 ms):

| Window | Frame p50 / p99 / max (ms) | Frames > 50 ms | Render + ticks per frame p99 / max (ms) |
|---|---|---|---|
| No scans | 33.41 / 34.50 / 34.76 | 0 | 2.08 / 4.67 |
| Back-to-back 16×8×16 (201 scans) | 33.40 / 34.89 / 49.52 | 0 | 2.92 / 17.35 |
| Back-to-back 32×8×32 (51 scans) | 33.42 / 34.68 / 35.10 | 0 | 2.50 / 3.58 |
| No scans | 33.40 / 34.56 / 37.05 | 0 | 2.47 / 4.05 |
| Back-to-back 32×8×32, budget 8192 (400 scans) | 33.40 / 35.55 / 38.23 | 0 | 3.49 / 5.50 |

A 1024-block portion took 0.05 ms median (p99 0.30, max 1.45) and an
8192-block portion 0.36 ms median (max 1.18). The one slow frame in the
16×8×16 window came from a 16 ms client tick whose scan portion took 0.06 ms.
No scan window had a frame above 50 ms.

Published 0.1.0a1, unmodified, observed the whole run (3058 player-only
observations; only `hello` and `observation` frames) and afterwards controlled
(configure, observations, inventory inspect, smooth look, release) without ever
receiving a scan frame.

Not demonstrated in the rendered run: unloaded-chunk `null` entries and
`void_air` outside the build height (the radius cap keeps boxes near the
player; unit-tested), `level_changed` and `world_exit` cancellation, a modded
oversized result, and multiplayer servers. M3.3's physical alt-tab and human
animation acceptance and the remaining M5.1 items stay outstanding.

## D23 — Crafting and processing menus: workstation analysis, counted moves, craft — **Settled** (2026-10-04, M3.6)

M3.6 adds the additive `crafting` capability (`protocol/v1.md`, Crafting and
processing menus); protocol stays 2 and no message type is added.

- **Scope by structure, not name.** Besides the player's own menu (2×2 grid, by
  identity) a menu is supported when it is vanilla's crafting menu or furnace
  menu base, or a subclass that overrides only the methods storage menus may
  override (D19), with exactly the base's data values (none / four), the
  vanilla slot kind at each role index (result, fuel, furnace result; a
  subclass may change only placement, pickup, capacity, activity and
  appearance rules) and storage-rule slots elsewhere. Vanilla furnace, blast
  furnace and smoker share the furnace base and qualify; the crafter, brewing
  stand, stonecutter, smithing table and anvil do not. The `instanceof` test
  only selects which vanilla base the override analysis measures against.
- **Observation.** Descriptors carry `crafting` (result and row-major grid
  indices) or `processing` (input/fuel/result slots, burn and cook ticks,
  `lit`, and `smeltable` from the client's recipe property set). Since 1.21.2 a
  client has no recipe ids or smelting results; the server-synchronized result
  slot is the recipe observation. Agents bring their own recipe knowledge.
- **Operations.** `move` gains `count` (items): pickup, single secondary-click
  placements, and a primary click returning the rest, each click verified
  against the whole menu (D19). Vanilla result slots give only whole stacks
  (`allowModification` false), so partial takes are refused
  (`whole_stack_only`). `craft` (crafts, default 1) takes the offered result into
  an agent-chosen destination; preflight requires an offered result
  (`no_result`), at least `count` items in every nonempty grid slot
  (`missing_ingredients`), remainder-bearing ingredients alone in their slot
  (`remainder_unsupported`) and room for every crafted item. The take is
  predicted as vanilla's client-side craft (one item per nonempty grid slot,
  crafting remainders). Later crafts wait until the server re-offers exactly
  the same result (up to 40 ticks, else `result_changed`), because the client
  cannot predict the next result; the two clicks of a craft share a tick unless
  animated. Shift-click, recipe-book placement and drag are not used: their
  outcomes are server- or recipe-decided, so destinations would not be the
  agent's.
- **Cancellation.** Existing D8b rules. A partly placed `count` stack returns to
  its source when it is the same item, no larger, and the source is empty and
  accepts it. A crafted stack carried by an animated craft has no source, so it
  stays visibly on the cursor for human recovery or vanilla closure. Workstation
  slots the server changes on its own (result; furnace input, fuel, result) do
  not cancel an animation between clicks; each click re-checks its own slots.
- **State ids.** Each grid change makes the server send a new result with a new
  state id one or two ticks later. Requests keep the exact-`stateId` rule; agents
  build each request from a fresh inspect and retry `stale_menu`, which is
  refused before any click.

Rejected: a recipe lookup or "craft item X" API (planning belongs to agents, and
the client has no recipes); the vanilla recipe-book placement packet (server
chooses ingredients and slots, and needs unlocked recipes); shift-click output
(server chooses destinations); relaxing the state-id check for crafting menus;
widening support to other workstations by type name; a new message type or
event (0.1.0a1 ends its session on unknown types, D17).

### M3.6 implementation and verification (2026-10-04)

`./gradlew build` passed with 332 tests (315 before). In-repository Python
client tests passed on 3.11 and 3.14 (62 tests, 57 before; mypy strict clean).
A rendered client in an isolated copy of the test world, provisioned by a
temporary client-side harness outside the repository (server-console setup,
dumps of the screen's menu and the integrated server's menu, data values and
inventory, screenshots, a human-equivalent screen close, and simulated F8/F9
keys), was driven only through the protocol: smooth looks, use taps and
inventory requests, never movement or jump. At 22 checkpoints all 2442 checks
passed: the protocol observation, the rendered screen and the server agreed
slot for slot (and cursor), and furnace data values agreed within the
observation-to-dump interval.

| Case | Outcome |
|---|---|
| 2×2 grid: 2 logs, `craft` ×2 (instant, waits for the re-offer) | 8 planks at the chosen slot; server equal |
| 2×2 grid: 4 counted moves, animated `craft` | crafting table at the chosen slot |
| 3×3 table: 8-plank ring, animated `craft` | chest; cursor carried the real stack |
| 3×3 table: 2+2 planks, `craft` ×2 | 8 sticks |
| Furnace: 3 raw iron, 1 coal; 12 one-second samples | lit, burn 1574→1354 ticks, one item finished within the samples (input 3→2, output 0→1); 3 ingots taken as whole stacks |
| Blast furnace (shared base) | `processing` scope, cook duration 100; ingot taken |
| Empty grid / too many crafts | `no_result` / `missing_ingredients`, nothing clicked |
| Craft into the grid; move from the result; slot 999; count 99 of 6 | `destination_rejects` / `slot_refused` / `slot_out_of_range` / `count_exceeds_source` |
| Dirt as fuel; item into furnace result; craft in a furnace; 1 of 2 from the furnace result | `destination_rejects` ×2 / `not_crafting` / `whole_stack_only` |
| `release` during an animated counted move (10 planks) | `released`; 2 placed, the rest returned to its source, cursor empty |
| Screen closed while carrying | `menu_changed`; vanilla closure returned the stack and grid, nothing dropped |
| Controller disconnect between animated crafts | release-all; one craft done, cursor empty |
| Panic while an animated craft carried the result | severed; 4 planks stayed visible on the cursor, vanilla close returned them, nothing dropped |
| Panic during an animated counted move | severed; the coal returned to its source |

Item totals (excluding the result slot's offer) were conserved across every
cancellation, allowing for completed crafts; no item entity appeared; no
control was ever held, jump included, and the 5-second safety net never fired.
No `stale_menu` retry was needed in this run. Published 0.1.0a1, unmodified,
observed the whole run (1687 player-only observations; only `hello` and
`observation` frames) and, as a controller with a crafting table open, decoded
the `crafting` descriptor and an error carrying `reason` before releasing.

Not demonstrated in the rendered run: human mouse/keyboard cancellation (no
human present; unchanged D8b path), the watchdog (same release path as
disconnect), `remainder_unsupported` and `result_changed` (unit and contract
coverage only), the smoker (same base and analysis as the blast furnace), a
modded workstation, and multiplayer servers. M3.3's physical alt-tab and human
animation acceptance and the remaining M5.1 items stay outstanding.

## D24 — Gameplay and social controls: vanilla paths, explicit requests, commands off by default — **Settled** (2026-10-04, M3.7)

M3.7 audited the existing primitives against swimming, boats and mounts,
elytra, shields and charged items before adding anything. Protocol stays 2; all
additions are additive capabilities (`protocol/v1.md`).

- **Existing primitives cover most gameplay.** Held `forward`/`sprint` swims
  (sprinting underwater is the swimming pose); held `sneak` sinks and
  dismounts; a `use` tap boards a boat or mounts a saddled animal; movement
  holds steer boats and mounts; held `use` raises a shield, draws a bow and
  charges a crossbow or trident, and a `use` tap fires a loaded crossbow.
  Vanilla `use` falls through to the offhand, so an offhand shield needs no new
  input. No new movement or use actuator was added.
- **Demonstrated gaps, filled:** the swap-offhand key (`swap_hands` tap,
  `swapHands`), the death screen's Respawn button (`respawn` request), sending
  chat and commands (`chat` request), and observation of what these controls
  visibly do (`playerActivity`: `swimming`, `fallFlying`, `blocking`,
  `usingItem`, `vehicle`, and `charged` on loaded crossbows), plus identity
  (`playerIdentity`: own `uuid`/`name`, player-entity `uuid`, chat
  `senderName`, damage `attackerPlayer`). Both requests answer with the new
  `action_result` type or an error, so only clients that send them receive
  either; 0.1.0a1 never does (D17).
- **Chat versus commands.** Separate fields, separate switches:
  `chat.allowChat` (default on) and `chat.allowCommands` (default **off**, so a
  command never runs unless the human enables it). Limits are fixed before
  sending and refuse rather than alter: vanilla chat-screen whitespace
  normalization only; at most 256 characters; no `§`, control characters or
  DEL (servers kick for them); no leading `/` in text; at most
  `chat.maxMessages` (1–8, default 5) accepted requests per 10 seconds for the
  whole client, so reconnecting cannot reset it and agents stay inside
  vanilla's server spam limit. Messages go through vanilla's own chat path
  (signing, NeoForge client chat hooks, client commands) and are not added to
  the human's chat history. A result means "sent", never "accepted": servers
  still decide (an un-opped singleplayer player gets "Unknown or incomplete
  command" as a system `chat` event).
- **Respawn** is never automatic and is refused while alive and in hardcore
  worlds (where vanilla offers only spectating, a game-mode decision for the
  human).
- **Safety.** Every new actuator is inside the existing release path: the
  pending `swap_hands` tap is cleared by release-all and by the screen rule,
  and queued `respawn`/`chat` requests are discarded with the rest of the
  queue on release, disconnect, watchdog and panic. Releasing `use` is a
  vanilla release, not a cancellation: a drawn bow fires and a charged trident
  is thrown when a safety release lets go, exactly as for a human releasing
  the button. Riding is world state and is not undone by a release.

Rejected: a separate "use offhand" input (vanilla has none; hand selection
stays vanilla), automatic respawn, truncating or rewriting over-limit chat,
allowing commands by default or through a per-session flag (the human owns
that switch), per-session rate windows (reconnect would reset them), and
social policy of any kind (whom to answer, what to say) in the mod.

### Gameplay coverage matrix (M3.7)

"Live" rows passed named rendered scenarios (see the verification record
below); "unit" rows rest on headless tests of an existing primitive.

| Control | Primitive | Status |
|---|---|---|
| Swim horizontally | held `forward` + `sprint` underwater | live |
| Swim up / surface | held `jump` | not live-verified (no-jump constraint); existing `jump` primitive, unit |
| Sink in water | held `sneak` | unit (existing primitive; not staged) |
| Board a boat | `use` tap on the boat | live |
| Steer a boat (forward, turn) | held `forward`, `left`/`right` | live |
| Leave a boat or mount | held `sneak` | live |
| Mount a saddled horse | `use` tap | live |
| Ride a horse | held `forward` (+ turn by look) | live |
| Horse / camel jump or dash | held `jump` | not live-verified (no-jump constraint) |
| Pig / strider boost item | `use` with the steering item | excluded from live staging (same `use` path; not staged) |
| Elytra deploy | `jump` tap while falling | not live-verified (no-jump constraint); existing primitive, unit |
| Elytra glide steering / firework boost | `look` / `use` while gliding | not live-verified (requires deployment, which needs jump); `fallFlying` observed |
| Shield block (offhand) | held `use`, empty main hand | live |
| Bow draw and release | held `use`, then `use: false` | live |
| Crossbow charge, stay loaded, fire | held `use`, then `use` tap; `charged` observed | live |
| Trident throw | held `use`, then `use: false` | live |
| Trident riptide | held `use` in water or rain | excluded (launches the player; not staged) |
| Swap main hand and offhand | `swap_hands` tap | live |
| Respawn after death | `respawn` | live |
| Ordinary chat | `chat` `text` | live |
| Commands | `chat` `command`, `chat.allowCommands` | live (refused by default; executed only when enabled) |
| Separate "use offhand only" input | — | excluded (vanilla has no such input) |

### M3.7 implementation and verification (2026-10-04)

`./gradlew build` passed with 355 tests (332 before). In-repository Python
client tests passed on 3.11 and 3.14 (72 tests, 62 before; mypy strict clean).
A rendered client in an isolated copy of the test world, provisioned by a
temporary client-side harness outside the repository (server-console setup, a
per-tick trace of held controls and the merged vanilla input, server dumps,
screenshots, and synthetic F8/F9 through the registered GLFW key callback),
was driven only through the protocol by controller sessions in separate
processes, with a recording event-subscribed observer. **No jump input was
ever sent or merged** (owner constraint; the per-tick trace never showed the
vanilla jump input true), and the 6-second held-input safety net never fired.
The singleplayer test world has cheats off, so the harness granted the player
operator permission for the command scenarios; without it the server answered
the agent's command with "Unknown or incomplete command" (a system `chat`
event), as the contract describes.

All 24 named scenarios passed:

- Chat: ordinary chat delivered and echoed as a `chat` event whose `sender` was
  the player's own `uuid` and `senderName` `Dev`; a command refused by default
  (`commands_disabled`); `slash_prefix`, `too_long` (257), `illegal_character`
  (`§`), `empty`; the sixth request in 10 s refused (`rate_limited`, with
  `limits` and `retryAfterMs`), still refused after reconnecting, accepted once
  the window passed; refused requests never reached the server (exactly six
  chat lines); `allowChat=false` refused text (live config reload).
- Commands with `allowCommands=true`: the agent's `/kill` and `time set 6000`
  executed (server day time 6000); refused again after switching it off.
- Respawn: refused while alive (`not_dead`); after a console `/kill` and after
  the agent's own `/kill`, `respawn` closed the death screen and restored 20
  health, with `death` and `respawn` events.
- `swap_hands` moved a shield main hand → offhand → main hand (server dump and
  observation agree); offhand shield blocking by held `use` (`blocking`,
  `usingItem` off_hand) and lowered on release; bow drawn 26 ticks and fired
  (16 → 15 arrows, one arrow entity); crossbow charged, observed `charged`,
  fired by a `use` tap; trident charged 20 ticks and thrown.
- Horizontal swimming (sprint+forward underwater, `swimming` true, 4 blocks in
  1.5 s); boat boarded by a `use` tap (target and server agree), driven forward
  and turned, left with sneak; tamed saddled horse mounted by a `use` tap,
  ridden forward, left with sneak.

Safety release, each actuator engaged and then released by each method
(milliseconds from the trigger to held controls empty; activity stopped in the
same or the next tick):

| Actuator | Panic (synthetic F8) | Watchdog (agent SIGSTOP) | Disconnect (agent SIGKILL) |
|---|---|---|---|
| Shield (held `use`) | 59.5 | 1589 | 45.2 |
| Bow draw | 43.3 | 2327 | 30.6 |
| Crossbow charge | 16.9 | 2397 | 40.0 |
| Trident charge | 41.7 | 1829 | 15.1 |
| Swimming (`forward`+`sprint`) | 45.2 (pose 95.6) | 2498 (2548) | 35.5 (85.8) |
| Boat (`forward`+`left`) | 39.9 | 2439 | 19.8 |
| Horse (`forward`) | 50.6 | 1532 | 25.8 |

Panic latched every time and was re-armed by synthetic F9. Watchdog releases
fell within the documented bound (timeout plus one ping interval, 2.5 s).
Releasing a drawn bow or charged trident fires or throws it, as documented.
Queued `chat`/`respawn` requests and pending `swap_hands` taps being discarded
by release, disconnect, watchdog and panic is covered by bridge and control
unit tests, not staged live (a request is applied within a tick of arrival).

Published 0.1.0a1, unmodified, observed the whole run (2823 observations; only
`hello` and `observation` frames) and, as a controller, decoded the new
capabilities and player fields as untyped extras, held and released sneak, and
inspected the inventory (only `hello`, `observation` and `inventory_result`).

Not demonstrated in the rendered run: anything needing jump (surfacing, elytra,
horse jump; matrix above), riptide, pig/strider boosting, hardcore and
`client_restricted` refusals (unit/contract only), multiplayer servers and
other mods' chat hooks. M3.3's physical alt-tab and human animation acceptance
and the remaining M5.1 items stay outstanding.

## D25 — Human precedence and lifecycle safety — **Settled modes; details decided pending owner review** (2026-10-04, M5.1)

The owner settled the three modes and their trust invariants earlier (Minor open
points, 2026-07-13): **human-priority** by default, **agent-exclusive** through a
rebindable input-lockout key, and **panic** (D16b); the lockout and panic keys
are never suppressed, system and interface keys stay live, the lockout drops
the moment no controller is attached, and the M5.2 HUD shows the mode (D26).
M5.1 implements them and the remaining safety items. The contract is
`protocol/v1.md` (Human precedence, Safety behavior, the `control` event and
the `human_paused` reasons); the states are in
[safety-state-machine.md](safety-state-machine.md). The additive capability is
`humanPrecedence`; the protocol integer stays 2.

**Implementation.** `HumanPrecedence` (Minecraft-free) holds the mode, the pause
and the lockout, and tracks the attached controller by identity.
`KeyboardInputMixin` replaces rather than merges the human's movement keys
while locked out; `MinecraftInteractionMixin` drops the human's attack/use
holds and attack, use, pick-block, drop, swap-hands and hotbar clicks;
`MouseLookMixin` drops mouse turning at vanilla's single `player.turn` call in
`MouseHandler.turnPlayer` and otherwise reports it as human look input; the
hotbar scroll event is cancelled. Human input is detected from NeoForge key and
mouse-button events in game with no screen open, the scroll event, mouse look,
gameplay key mappings held down each tick, and an open pause menu.

### Decided (pending owner review)

These details were open at M5.1. They were decided conservatively and need the
owner's review; each is a local client policy, not a wire change, except where
noted.

1. **Always-live keys.** Agent-exclusive suppresses only gameplay input:
   movement, jump, sneak, sprint, mouse look, attack, use, pick-block, drop,
   swap hands, hotbar keys and the hotbar scroll wheel. Everything else stays
   live: the lockout (default F7), panic (F8) and re-arm (F9) keys; Escape and
   the pause menu; F1, F2, F3 and F3 combinations, F5, F11; chat and command;
   player list; inventory, advancements, social interactions; screenshots,
   perspective, smooth camera; and every input inside a screen. Opening the
   inventory is treated as an interface key; human clicks in the agent's
   inventory screen still cancel agent inventory work.
2. **What counts as human input** (human-priority). Any of the gameplay inputs
   above, made in game with no screen open, including any non-zero mouse turn,
   plus the pause menu (Escape, or vanilla's focus-loss pause when not
   suppressed). Sneak and sprint in toggle mode count on presses only, so a
   toggled-on sneak does not pause the agent forever. Chat typing, F-keys and
   interface keys are not human input.
3. **Pause semantics.** Human input releases every agent actuator immediately
   (inventory work is cancelled with `human_input`; a block scan keeps running
   because it only reads) and pauses the agent. While paused, `input` and
   `look` are discarded silently; mutating `inventory` requests, `respawn` and
   `chat` are refused with reason `human_paused` (wire: three additive reasons);
   `release`, `configure`, inventory `inspect` and `scan` keep working.
   Discarding instead of erroring keeps unsubscribed clients such as
   0.1.0a1 from accumulating unread error replies.
4. **Resume.** The agent may drive again `precedence.resumeAfterMillis`
   (default 2000, range 250–60000, live) after the last human input; held
   gameplay keys or an open pause menu keep it paused. Resuming restores and
   replays nothing: the agent must re-send its holds. Two seconds is long
   enough to see a human's correction take effect and short enough not to
   strand an attentive agent.
5. **Lockout key behavior.** Default F7 (unbound in vanilla 1.21.8, next to F8
   and F9). It engages only in game with no screen open, only while a
   controller is attached and panic is not latched; otherwise a notice says it
   is unavailable or that panic is engaged. Pressing it again releases the
   lockout anywhere, including in screens. Panic, controller loss and a
   different controller attaching all drop it; it is in-memory and never
   restored.
6. **Focus loss per mode.** Focus loss never releases or pauses by itself. In
   human-priority and agent-exclusive modes the M2.2
   `client.suppressPauseOnLostFocus` toggle (default on) keeps the game running
   while a controller is attached; with it off the vanilla pause menu opens and,
   in human-priority mode, pauses the agent as human input. In panic mode, and
   with no controller, vanilla applies. The lockout survives focus loss: it is
   a deliberate human hand-over.
7. **Agent notification.** A new event kind `control` (`mode`, `paused`,
   `cause`, `inputs`) on the existing opt-in event stream (D17), advertised by
   `humanPrecedence`. It is sent on each change of mode or pause and as a
   start state when a controller attaches (`controller_attached`). No new
   message type is introduced, so marionette-mc 0.1.0a1, which ends a session
   on unknown message types and never subscribes to events, never receives it.
   Panic is still signalled to the controller by its close (`local panic`);
   observers receive the `panic` and `controller_lost` events.
8. **Lifecycle releases.** Every agent actuator is released when the player is
   first seen dead and again when the client replaces its player (respawn or
   dimension change). The controller stays attached. Holds therefore never
   survive a death screen, respawn or dimension change.
9. **Loopback opt-out.** The gate is the boolean
   `bridge.iUnderstandNonLoopbackIsUnauthenticated` (default false, restart
   required). Only when it is true is a resolvable non-loopback `bindAddress`
   (including the wildcard) bound, with a WARN at every start naming the address
   and the missing authentication; an unresolvable value is still clamped. The
   address is resolved exactly once and the same `InetAddress` object is passed
   to Netty, which closes the M2.2 re-resolution gap (M5.1a already passed the
   object; M5.1 keeps the single resolution for the opt-out path). The Origin
   rule and the lack of authentication are unchanged; remote access remains
   unsupported and D16's release gate stands.
10. **Notices.** Toasts report lockout engaged, released, dropped and
    unavailable; a human pause has no toast (it would fire on every correction)
    and is logged; the M5.2 HUD shows the mode and pause (D26).

Rejected: pausing without releasing (a held agent forward would still counter
the human, the D4 OR-merge problem); auto-resume with replay of the agent's
last holds (resumes motion without a fresh agent decision); answering
discarded `input`/`look` with errors (floods unsubscribed clients); a new
message type for notifications (breaks 0.1.0a1, D17); counting interface keys
as human input (would pause an agent whenever the human opens chat or F3);
persisting the lockout across controllers or restarts (a dead agent could
leave a locked keyboard).

### M5.1 implementation and verification (2026-10-04)

`./gradlew build` passed with 378 tests (355 before). The in-repository Python
client passed on 3.11 and 3.14 (78 tests, mypy strict clean); its shared
fixtures gained the capability, the `control` events and the `human_paused`
refusals. Nothing was published.

Rendered verification ran in an isolated copy of the test world with a
temporary harness (outside the repository) that injected **synthetic** human
input through Minecraft's registered GLFW key, mouse-button, cursor, scroll and
focus callbacks; physical keyboard and mouse confirmation is pending the
owner's availability. Controllers ran as separate processes through real
WebSocket sessions. No input jumped (the harness refuses the space key; the
traced merged jump input stayed false). The final matrix below ran on the
committed code; milliseconds are from the trigger to the agent's holds being
empty, sampled at frame boundaries (button rows include a 150 ms press):

| Check | Result |
|---|---|
| Human S key over agent forward+sprint | released 11 ms; only the human's back input applied (moved backward); agent input during the pause discarded; still paused 1.5 s after release; resumed 2.02 s after the last input; events `controller_attached`, `human_input [movement]`, `human_idle` |
| Mouse look over an agent smooth pan + forward | released 21 ms, pan cancelled, `inputs [look]`, resumed |
| Attack, use buttons; hotbar key; scroll; drop; swap keys | each paused with exactly its category (`attack`, `use`, `hotbar`, `hotbar`, `drop`, `swap_hands`) |
| Pause menu (Escape) | released 31 ms, paused (`pause_menu`) and kept paused for 3 s while open; `chat_refused`, `respawn_refused`, `inventory_unavailable` all `human_paused`; `inspect` answered; resumed 2.02 s after closing |
| Agent-exclusive suppression | human S, A, sneak: no effect (agent forward kept); mouse look: yaw unchanged; attack: no swing or mining; use: no item use; hotbar key and scroll: slot unchanged; Q and F: no drop or swap; agent look and hotbar still applied |
| Agent-exclusive live keys | F3 on/off; T opened chat and Escape closed it; Escape opened the pause menu (agent not paused) and closed it; E opened the inventory; F7 released; lockout survived all of these |
| Lockout auto-drop | disconnect 20 ms; watchdog 2.46 s; world leave 31 ms; panic 10 ms (and F7 while latched refused, reconnect refused `panic_latched`) |
| SIGSTOP mid-sprint (3 trials) | released 2.36–2.38 s, idle 2.60–2.61 s (13.5–13.8 blocks of sprint) |
| Panic mid-control (forward, sprint, attack, smooth pan) | released 11 ms; controller closed 1008 `local panic`; observer received `control` `panic`; under a lockout, human S and mouse look worked 61 ms after panic |
| Death and respawn (agent sprinting and drawing a bow) | released 112 ms after `/kill`; input sent while dead was released by the respawn; after the agent's `respawn`: nothing held, no use, did not move |
| Dimension change (Nether and back) | released 51 ms and 31 ms; controller stayed attached; `dimension_change` events |
| GUI open | agent attack released, forward continued (D4); attack did not resume on close |
| Focus loss | suppression on: no pause, agent kept driving; off: pause menu, agent paused; agent-exclusive: no pause, lockout kept; no controller: vanilla pause |
| Every actuator per trigger: A = forward, left, sprint, attack, smooth pan; B = back, right, sneak, bow `use`, hotbar, smooth pan | panic 11/11; watchdog 1955/2483; disconnect 20/20; human look 42/22; death 52/31; dimension 51/31; world leave 31/31 — all neutral afterwards (death checked after respawn, leave after rejoin) |
| Inventory animation + panic | 12 ms; stack recovered to its source slot |
| Loopback | `172.17.0.1` without the opt-out: clamped with a WARN, listening only on 127.0.0.1; with the opt-out (a host-local bridge address, not the LAN): two WARNs, listening only on 172.17.0.1, an agent connected there, 127.0.0.1 refused |

Published 0.1.0a1, unmodified, observed the whole first and final runs (9131
and 5711 observations, only `hello` and `observation` frames). As a controller it held
sneak, was paused by human W, had its input silently discarded, received
`inventory_unavailable`/`human_paused` as an ordinary `ServerError`, still got
its `inspect` answer, resumed driving after the pause and never received an
event. In the final run an event-subscribed observer received 111 events with
gapless `seq`, including every `control` cause.

Not established: physical (non-synthetic) keyboard and mouse input, which needs
the owner; M3.3's physical alt-tab and human animation acceptance (separate
debt); multiplayer servers. Queued `respawn`/`chat` discard on every release
remains covered by unit tests (M3.7), as a request is applied within a tick.

## D26 — Diagnostics: status HUD, `status` query, per-category logging — **Settled** (2026-10-04, M5.2)

M5.2 makes Marionette's state visible to the person at the keyboard and to any
connected program. The contract is `protocol/v1.md` (status, status_result, the
hello `agent` field); the protocol integer stays 2 and the additive capability
is `status`.

**HUD.** A small top-left overlay, drawn as a GUI layer below chat, from the
same `StatusReport` a `status` query returns. It is **on by default**: D25
requires the precedence mode to be shown prominently, and an overlay that
starts hidden would not show it. It stays unobtrusive for streaming: one dim
`Marionette: idle` line without a controller; six short lines while connected
(state, agent name and observer count, the mode — `Human priority`, a yellow
`PAUSED by your input` or an orange `AGENT EXCLUSIVE: input locked` —, the
agent's held controls, observation rate/sent/dropped, ping round trip and
command latency); two red lines while panic is latched, naming the re-arm key.
Lines stay under about 40 characters, and agent names are cut to 16 on the HUD,
so a top-right toast never covers them at 854×480 (an earlier, longer layout
was covered by the panic toast and was rejected). A rebindable "Toggle
Marionette status HUD" key (default F6, unbound in vanilla 1.21.8, next to
F7–F9; handled only in game with no screen open, never human input and never
suppressed) flips `[hud] enabled`, which is saved, so a streamer who hides it
keeps it hidden. F1 and the F3 debug screen also hide it.

**`status` query.** Request/reply, only to the requester, behind `status`.
Both roles may send it: it is read-only, like `configure`, and observers
(dashboards) are its main users. It is applied on the client tick in order with
the session's other commands, so it reflects exactly what the HUD shows at that
moment, and it is answered while no world is loaded and while paused. A new
reply type is safe for marionette-mc 0.1.0a1 (which ends its session on unknown
message types, D17) because only a session that sends `status` receives it;
0.1.0a1 never does.

**Counters.** Per connection, from admission: frames sent; frames *dropped*
(never delivered: replaced in the latest-wins stash, discarded before an event,
or over the frame cap — unlike the older `coalesced` total, a delayed but
delivered frame is not counted); the observation rate over the last completed
one-second window; the last event `seq`; queued commands; the latest
WebSocket ping round trip (the existing liveness pings; unsolicited pongs are
ignored); and the latest command's receipt-to-apply latency, which includes
waiting for the next client tick (up to about 50 ms).

**Agent name.** An optional hello `agent` string (1–64 UTF-16 characters, no
control characters or `§`, which would style HUD text), shown on the HUD and in
`status`. It is informational and authenticates nothing. Invalid values are
`invalid_field` (fatal in hello); older mods ignore the field.

**Logging.** `[logging] verbosity` remains the default; six categories
(`bridge`, `control`, `precedence`, `observation`, `events`, `client`) each
take `INHERIT`, `QUIET`, `NORMAL` or `VERBOSE`, live. Each logs through its own
logger, `marionette.<category>`, so a log line names its subsystem, and new
connection lines carry `key=value` fields (`role=`, `agent=`, `code=`,
`reason=`). Warnings and errors are never gated. The pan log markers that
`scripts/analyze_pan.py` parses are unchanged.

Rejected: a HUD hidden by default (contradicts D25); broadcasting status to
every session (breaks 0.1.0a1, D17); carrying the counters inside observation
frames (they would coalesce away and grow every frame); a status `event` kind
(events are for things that happened, and would need a subscription); and an
agent name inside `configure` (the HUD needs it from the first frame).

### M5.2 implementation and verification (2026-10-04)

`./gradlew build` passed with 405 tests (378 before). The in-repository Python
client passed on 3.11 and 3.14 (86 tests, previously 78; mypy strict clean); it
gained `Client.status()`, `connect(agent=...)`, the typed `StatusResult`, the
shared fixtures (`statusResults`, the capability, the agent field) and
`examples/status.py`. Nothing was published.

Rendered verification ran in an isolated copy of the test world with a
temporary harness (outside the repository) that recorded, on every rendered
frame, the exact lines the HUD layer draws, and injected the F-keys and the
human `S` key **synthetically** through Minecraft's registered GLFW key
callback. Agents ran as separate processes over real WebSocket sessions; every
agent hold was bounded (at most about 1.5 s) and nothing jumped (the merged
jump input stayed false for all 946 traced ticks). Results of the final run:

| Check | Result |
|---|---|
| Idle | `Marionette: idle +2 observers` (two observers attached) |
| Connected, walking | `Marionette: connected`, `walker (controller) +2 observers`, `Human priority`, `Held: forward`, `Obs 20.1/s sent 27 drop 0`, `Ping 0.3 ms cmd 4.1 ms` while the player walked |
| `status` vs HUD | an observer's query matched the HUD's state, mode, held controls, agent and observer count; sent within 3 frames, dropped equal, rate within 1/s |
| Live counters | two controller queries 2 s apart: +41 frames, 19.9–20.1/s, ping and command latency present; `controller` mirrored `session` |
| Disconnect → idle | SIGKILL ×3 and clean close ×2: HUD showed `idle` 2.2–6.1 ms after the loss (the next rendered frame); holds released 3–46 ms after |
| Human pause | `PAUSED by your input`, `Held: none` while the human held S |
| Lockout | `AGENT EXCLUSIVE: input locked (F7)` with the agent holding forward; `Human priority` after F7 again |
| Panic | `Marionette: PANIC` / `Agent control off (F9 allows it)` 8.8 ms after F8; controller closed 1008 `local panic`; `status` reported `latched`, mode `panic`, no controller; a reconnect was refused `panic_latched`; F9 returned the HUD to `idle` |
| Toggle | F6 hid the HUD and saved `enabled = false` (it stayed hidden after the config reload); F6 showed it and saved `true`; F3 hid and restored it |
| Logging | with `control = "QUIET"` and `bridge = "VERBOSE"`: `marionette.bridge` lines `Agent connected role=controller agent=logcheck` and no control lines; with `INHERIT`, control lines returned |
| Controls menu | F6 "Toggle Marionette status HUD" listed under Marionette with F7–F9 |

Published 0.1.0a1, unmodified (wheel SHA256 as in D14), observed the whole run
(843 observations; only `hello` and `observation` frames) and, as a
controller, walked 6.5 blocks with a bounded hold while the HUD showed
`unnamed agent (controller)` and `Held: forward`; it received only `hello` and
`observation` frames.

Not established: physical (non-synthetic) keys, which need the owner;
multiplayer servers; HUD legibility at other GUI scales or window sizes than
the development window (854×480, automatic scale). M3.3's physical alt-tab and
animation acceptance and the M5.1 owner-review items remain separate debt.
