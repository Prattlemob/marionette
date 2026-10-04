# Safety model

Marionette hands control of a real game client to an external program, so its
first job is to keep the person at the keyboard in charge. This page is the
overview; the exact state machine is
[safety-state-machine.md](safety-state-machine.md), the wire rules are the
protocol's [Safety behavior](../protocol/v1.md#safety-behavior-normative)
section, and the trust policy is [SECURITY.md](../SECURITY.md). Decisions are
D7, D16 (with D16a and D16b), D24 and D25 in [decisions.md](decisions.md).

## Goals

1. **Nothing stays held by accident.** Whenever no admitted, live agent is
   driving, every agent control is neutral.
2. **The human always wins.** Local input outranks the agent, and one key
   takes control back and keeps it back.
3. **The game and machine are protected from the agent.** A misbehaving or
   flooding agent loses its own connection; it cannot stall the client or
   disturb other connections.
4. **Visible state.** The human can always see whether an agent is attached,
   whether it is paused and what it is holding.

## Layers

| Layer | Mechanism | Protects against |
|---|---|---|
| Reachability | The bridge binds loopback only (a non-loopback address needs an explicit "I understand" opt-out); browser requests carrying an Origin header are refused before upgrade | remote control; drive-by web pages |
| Admission | One controller at a time; read-only observers capped by config; the panic latch refuses controllers | competing agents; an agent retaking control after panic |
| Liveness | WebSocket pings with a 2-second pong deadline (D16a) | a frozen, stopped or deadlocked agent holding keys |
| Resource bounds | Bounded inbound queues, a per-tick command budget, bounded outbound bytes, latest-wins observations | floods stalling the client or growing memory |
| Release-all | One release operation used on every loss, panic, human input, death, respawn, dimension change, world exit and shutdown | stuck movement, attacks, item use, pans or inventory drags |
| Human precedence | Human input releases and pauses the agent (default); an optional agent-exclusive lockout that never suppresses the safety keys (D25) | the agent fighting the human for the controls |
| Panic | F8 releases, severs and latches; only the separate F9 re-arms (D16b) | an agent the human wants gone, immediately and for good |
| Explicit gates | Commands through `chat` are off unless the human enables them (D24); respawn is never automatic | an agent changing the world or rules unasked |
| Visibility | Status HUD on by default; `status` query; `control` events | silent or surprising agent activity |

## Release-all

Every path that should leave the player idle calls one release operation:
held movement, jump, sneak, sprint, attack and use go neutral; pending taps,
hotbar selects and look intents are dropped; an active camera pan stops; an
inventory animation is cancelled with safe cursor recovery; queued `respawn`
and `chat` requests are discarded. Nothing released is ever restored or
replayed: an agent re-sends what it wants once it may drive again. Changes the
agent already made to the world (a placed block, a completed inventory move,
sent chat) are not undone.

Observer connections never trigger a release: an observer disconnecting does
not touch the controller's inputs.

## Human precedence

In the default **human priority** mode any gameplay input — movement, mouse
look, attack, use, hotbar keys or scrolling, drop, swap hands, or opening the
pause menu — releases the agent and pauses it. While paused, its `input` and
`look` are discarded and inventory changes, `chat` and `respawn` are refused
with `human_paused`; reading state, `release`, `configure`, `inspect` and
`scan` still work. It may drive again `precedence.resumeAfterMillis` (2 s by
default) after the last human input, with no key held and no pause menu open.

**Agent exclusive** is a local choice (F7): the human's gameplay input is
ignored so the agent drives alone. The lockout never suppresses the lockout,
panic or re-arm keys or the interface keys (Escape, chat, inventory, F1–F3,
F5, F11), and it ends by itself the moment no controller is attached.

Subscribed agents receive a `control` event for each mode or pause change.

## Panic

The panic key (F8) releases, severs the controller with close 1008
`local panic`, and **latches**: every controller hello is refused with
`panic_latched` until the human presses the separate re-arm key (F9) in game.
Pressing panic again never re-enables anything, and re-arming resumes
nothing; an agent must connect afresh. The latch survives leaving and
rejoining worlds and resets when the game restarts. Observers stay attached.
Agents must not reconnect in a loop while latched.

## Timing guarantees

- Loss, panic and human input release at the next client tick or rendered
  frame, whichever comes first; measured at about 10–110 ms (D25).
- A frozen agent is disconnected after the pong timeout plus at most one ping
  interval: 2.5 seconds at the default, then released at the next tick or
  frame.
- These guarantees need a running client thread. A stalled Minecraft process
  cannot release anything until it runs again; see the
  [threading model](threading.md).

## What it does not protect against

- **Other local programs.** Any native process on the computer can connect;
  there is no authentication yet. Authentication is a release gate (D16).
- **The agent's decisions.** Marionette makes none. What an agent does while
  it legitimately drives is the responsibility of whoever runs it, including
  on multiplayer servers that forbid automation.
- **Server-side outcomes.** Inventory results and some events are client
  predictions; the server can still correct them.

## Open review points

The D25 details (which keys stay live, what counts as human input, resume
and focus-loss behavior) were decided pending the owner's review, and the human-precedence checks were
performed with synthetic input; physical keyboard and mouse confirmation is
still pending. M3.3's physical alt-tab acceptance is separate open debt. See
[ROADMAP.md](../ROADMAP.md).
