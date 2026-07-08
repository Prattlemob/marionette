# Phase 1 — Walking Skeleton: design

Design for ROADMAP Phase 1 (M1.1 input injection, M1.2 minimal localhost
bridge, M1.3 end-to-end demo + safety baseline). Goal: retire the project's
biggest risk by proving an external process can move the real, rendered
player, with disconnect safety from day one.

Decisions resolved during this design (with the project owner):

- **Scope:** one spec for the whole phase; M1.1–M1.3 share interfaces that
  are designed together. The D4 experiment remains a gate inside
  implementation.
- **Verification:** hybrid — scripted runs with log/state evidence for
  everything automatable; a human at the keyboard only for the D4 criteria
  that need real input (key press mid-agent-control, focus loss) and a final
  eyeball of the walking demo.
- **D1a:** bundled Netty (the Netty Minecraft ships, with its WebSocket
  codecs). Zero new runtime dependencies; coupling to MC's Netty version is
  acceptable on a 1.21.8-pinned mod. Fallback remains shaded Java-WebSocket
  if the bundled route proves awkward. To be recorded in `docs/decisions.md`
  when M1.2 lands.
- **Architecture:** three bounded subsystems (below), chosen over both a
  minimal hang-it-off-`MarionetteClient` skeleton (would force a Phase-2
  refactor and leaves D4 without a seam) and forward-designed protocol-v1
  abstractions (speculative; M2.1 redesigns the envelope anyway).

## 1. Components & threading model

Three units under `com.prattlemob.marionette`, wired by the existing
`MarionetteClient` singleton.

### `control` package

- **`ControlState`** — the set-and-hold holder: forward/back/left/right,
  jump, sneak, sprint (booleans in Phase 1; analog-vs-boolean on the wire is
  M3.1's open point), plus an optional yaw/pitch intent. Held values persist
  until changed; `releaseAll()` zeroes everything. Pure data, no Minecraft
  imports — first JUnit target.
- **`ControlStateApplier`** — the interface the D4 experiment lives behind:
  `apply(state)` each tick, `release()`. Two implementations are built
  (KeyMapping-forcing and input-path mixin); the winner stays, the loser is
  deleted and its failure mode recorded in `docs/decisions.md`. Raw rotation
  (set yaw/pitch on the player) is common code outside the experiment — D4
  concerns the movement/key path.
- **`DemoScript`** — temporary hardcoded walk-3s → turn 90° → walk state
  machine, armed by `-Dmarionette.demo=true`, auto-running 100 ticks (5 s)
  after world join so scripted verification needs no input tool. Disabled or
  deleted after M1.3.

### `bridge` package

- **`BridgeServer`** — bundled-Netty WebSocket server on `127.0.0.1:24680`
  (constant until M2.2 makes it config), accepting one connection at a time;
  a further connect while one is live is refused with a WebSocket close
  reason (`"controller already connected"`; formal error shape is M2.1).
- Inbound: the network thread parses JSON (Gson, bundled) into typed command
  objects and enqueues them on a thread-safe mailbox. **Nothing on the
  network thread ever touches game state.**
- Outbound: the tick thread serializes the observation and hands it to
  Netty's non-blocking write.
- The bridge deliberately avoids Minecraft classes (commands are plain
  objects handed to a consumer) so it is integration-testable headless in
  JUnit.

### Wiring (`MarionetteClient`)

Per tick, in order: drain mailbox → mutate `ControlState` → applier injects
(client tick *pre*) → game ticks → build + send observation (client tick
*post*). Actuation is gated on `isInWorld()`; the listener lives for the
whole client session, so agents may connect from the title screen.

Threading: one Netty event-loop thread does IO + parse; a thread-safe MPSC
mailbox is the only handoff; the client tick thread is the only thing that
reads or writes game state.

## 2. The D4 experiment plan

Both variants implement `ControlStateApplier`; demo script and verification
harness are shared, so the experiment is a fair A/B behind one seam.

**Variant 1 — KeyMapping-forcing** (built first: cheap, no mixin plumbing,
proves the demo harness early). Each tick, `KeyMapping.setDown()` on the
vanilla movement/jump/sneak/sprint mappings from `ControlState`; `release()`
raises them all. Known risk areas to probe: vanilla toggle-sneak/toggle-sprint
settings, Minecraft's own `KeyMapping.releaseAll()` on focus loss fighting
with us, stuck-key leaks.

**Variant 2 — input-path mixin.** A mixin into `KeyboardInput.tick()` (the
class populating the player's semantic movement impulses from key state)
that, after vanilla runs, overwrites impulse/jump/sneak/sprint from
`ControlState`. Manipulates meaning rather than faking key presses; expected
cleaner for M5.1's human-override policy because vanilla key state stays
visible underneath.

**Judging against the four D4 pass criteria** (docs/decisions.md), hybrid
split:

- *Never leaks stuck state* and *survives GUI open/close* — scripted: the
  demo script opens/closes the pause menu and inventory mid-run, then
  releases; log assertions check impulses and key states are zeroed after.
- *Plays correctly with sprint/sneak toggle logic* — scripted run with the
  toggle options flipped, verified via movement-speed deltas in the log.
- *Coexists with real keyboard input* and *survives window focus loss* —
  human at the keyboard: press W/S mid-agent-walk, alt-tab mid-walk, observe
  defined behaviour.

**Outcome rules:** both pass → mixin wins (cleaner override story). One
passes → it wins. Both fail any criterion → stop, revisit D4 in
`docs/decisions.md` before starting M1.2. Winner and loser's failure mode
recorded either way.

## 3. Bridge wire contract (protocol v0) & data flow

**Server pipeline** (bundled Netty classes): `HttpServerCodec` →
`HttpObjectAggregator` → `WebSocketServerProtocolHandler` (answers ping/pong
for free) → text-frame handler.

**Protocol v0** — deliberately tiny, documented verbatim in
`protocol/v0-draft.md`, explicitly marked a throwaway draft that M2.1
replaces. One JSON object per WebSocket text frame.

Agent → mod:

- `{"type": "hello", "version": 0}` — required first message; anything else
  first → close. Mod replies `{"type": "hello", "version": 0, "mod":
  "<mod version>"}`.
- `{"type": "input", "forward": true, "sprint": true, ...}` — partial
  set-and-hold update; omitted fields mean *unchanged*. Fields: `forward`,
  `back`, `left`, `right`, `jump`, `sneak`, `sprint` (booleans).
- `{"type": "look", "yaw": 90.0, "pitch": 0.0}` — raw instant set (smoothing
  is M3.2).
- `{"type": "release"}` — release-all.

Mod → agent, per tick while in a world:

- `{"type": "observation", "tick": n, "x": .., "y": .., "z": .., "yaw": ..,
  "pitch": ..}`

**Data flow.** Network thread: decode frame → Gson parse → validate →
enqueue typed command (malformed input never crosses the boundary). Client
tick pre: drain all queued commands in arrival order onto `ControlState` →
applier injects. Client tick post: if in world, serialize observation,
non-blocking write. No backpressure handling beyond Netty's bounded buffers —
that is M2.3; at a few hundred bytes per tick on loopback it cannot bite in
Phase 1.

**Probe.** `examples/probe.py` (Python 3 + `websockets`): connects, prints
observations, sends forward-on then forward-off — mirroring M1.2's
definition of done exactly.

## 4. Safety baseline & error handling (M1.3)

- **One-tick release guarantee, enforced tick-side.** The connection holds an
  atomic liveness flag; every tick, before applying controls, the wiring
  checks it. Netty `channelInactive`/`exceptionCaught` flip the flag (a
  `kill -9`'d agent closes its socket at the OS level, so `channelInactive`
  fires immediately on loopback). Next tick: `releaseAll()`, `ControlState`
  cleared, player idles. Worst case exactly one tick, by construction — the
  guarantee never depends on the network thread being healthy.
- **Malformed input.** Parse/validation failures are caught on the network
  thread, answered with `{"type": "error", "message": ...}`, connection stays
  up, controls untouched. Unknown `type` treated the same. Nothing malformed
  reaches the mailbox, so it cannot crash the tick thread.
- **Reconnect.** Single-connection means one *at a time*: when a connection
  drops, the listener accepts the next. Must work repeatedly in one game
  session, no Minecraft restart.
- **World leave.** The existing `onLoggingOut` hook triggers `releaseAll()`,
  suspends actuation and observations, and leaves the socket connected — the
  agent keeps its session; observations resume on rejoin. Death screen and
  pause menu get the simple Phase-1 rule: actuate only while a player entity
  exists (singleplayer pause stops ticks anyway). The exhaustive edge-case
  matrix is M5.1.
- **Shutdown.** Quitting Minecraft stops the Netty event loop gracefully — no
  orphaned threads holding the JVM open. (M2.3 formalizes this; the basic
  version ships now.)
- **Demo.** `examples/walk_square.py` walks a square using `look` for the 90°
  corners and observations for feedback, returning roughly to start; it is
  the target of the `kill -9` test.

## 5. Testing & verification

- **JUnit** (first test infrastructure in the repo): `ControlState`
  set-and-hold/release semantics; JSON command parsing + validation including
  malformed cases; demo-script state machine. Because the bridge avoids
  Minecraft classes, it gets a headless integration test: start
  `BridgeServer` in JUnit, connect a WS client, assert handshake, command
  delivery to a fake mailbox, second-connection rejection, disconnect signal.
  Only the applier and wiring need a live client.
- **Scripted in-game runs** (reusing Phase-0 tooling: quick-play into a saved
  world, log-marker assertions, KWin window close): demo-script actuation
  evidence via logged position/rotation deltas; D4's scripted criteria (GUI
  open/close, toggle settings, stuck-state checks); probe and walk-square
  runs; `kill -9` mid-walk → released-within-one-tick log assertion; repeated
  reconnects.
- **Human checklist** (the hybrid's manual half): real key press
  mid-agent-control and alt-tab focus loss (D4), plus one watch-through of
  walk-square confirming it looks like walking, not teleport-sliding.
- **Milestone gates** are the roadmap's own definitions of done: M1.1
  walk-turn-walk with clean vanilla resume; M1.2 Python probe start/stop;
  M1.3 square + kill-safety + reconnect. Phase 1 ends with the roadmap
  checkboxes ticked and the D4 and D1a outcomes recorded in
  `docs/decisions.md`.
