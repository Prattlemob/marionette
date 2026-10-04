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
OR-merge base — it is not free from D4 and needs its own design.

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
- Crosshair ray-cast distance: vanilla reach vs. configurable gaze — M4.3.
- Hostility classification source; client-side aggro inference depth — M4.4.
- Raw-input vs. active-Baritone-goal conflict policy; which Baritone settings
  are exposed — M6.2.
- Human-input precedence policy details — M5.1. (The watchdog timeout default
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
No non-loopback opt-out exists. Bridge limits and overload behavior are normative
in protocol/v1.md under `bridgeSafety`. Pong timeout defaults to two seconds
(D16a; originally five); local panic defaults to rebindable F8 and latches
controller admission off until a separate re-arm key (default F9) clears it
(D16b). These emergency controls do not settle
M5.1 human-precedence modes or per-mode focus-loss policy.

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
