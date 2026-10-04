# Idle-safe state machine

This page describes how Marionette decides, on every client tick and rendered
frame, whether an agent may move the player. The normative wire behavior is in
[protocol/v1.md](../protocol/v1.md) (Safety behavior, Emergency release and
Human precedence). The decisions and their open review points are D16, D16a,
D16b and D25 in [decisions.md](decisions.md).

## The rule

The player is **idle-safe** unless an admitted controller is attached and the
human has not taken control. Idle-safe means that every agent actuator is
neutral:

- held movement, `jump`, `sneak`, `sprint`, `attack` and `use`;
- pending taps (`jump`, `attack`, `use`, `swap_hands`), hotbar selects and
  look intents;
- camera smoothing;
- inventory animation (cancelled with safe cursor recovery) and queued
  `respawn`/`chat` requests.

Releasing is one operation (`releaseControls` in `MarionetteClient`), used by
every path below. Nothing that was released is ever restored or replayed; an
agent re-sends what it wants once it may drive again. World state that the
agent already changed (the selected hotbar slot, completed inventory clicks,
sent chat, riding) is not rolled back.

## States

| State | Agent commands | Local human input | Leaves by |
|---|---|---|---|
| **Detached** — no admitted controller | none (observers only) | vanilla | a controller hello is admitted → *Driving* |
| **Driving** — human priority, not paused | applied | applied, and pauses → *Paused* | human input → *Paused*; lockout key → *Exclusive*; any loss → *Detached*; panic → *Latched* |
| **Paused** — human priority | `input`/`look` discarded; inventory mutations, `respawn`, `chat` refused (`human_paused`); `release`, `configure`, `inspect`, `scan` work | applied | quiet for `precedence.resumeAfterMillis` with no key held and no pause menu → *Driving*; lockout key → *Exclusive*; loss → *Detached*; panic → *Latched* |
| **Exclusive** — agent exclusive (input lockout) | applied | gameplay input suppressed; lockout, panic, re-arm and interface keys live | lockout key → *Driving*; any loss → *Detached*; panic → *Latched* |
| **Latched** — panic | every controller hello refused (`panic_latched`) | vanilla | re-arm key in game → *Detached* |

The *Detached*, *Paused* and *Latched* states are idle-safe. Entering them
always releases first:

- **Loss** — disconnect, socket error, pong watchdog, overload (`overloaded`,
  event overflow), world exit (which severs the controller) and game shutdown.
  The bridge marks the controller not ready on its network thread at once; the
  client thread releases at its next tick or rendered frame, whichever comes
  first, before any other work.
- **Panic** — the panic key releases and severs on the client thread
  immediately, then latches admission.
- **Human input** — the key, button, scroll or mouse-look callback releases on
  the client thread immediately, before the next tick processes commands.

Entering *Exclusive* releases nothing: the agent keeps driving and only local
input is suppressed. Leaving *Exclusive* by the lockout key releases nothing
either; the human's input simply counts again, and the next human input
pauses the agent as usual.

## Lockout safety

The input-lockout suppression is evaluated on every read, not cached: the
mixins ask whether the lockout is engaged **for the controller that is
attached right now** (compared by identity, with panic not latched). A
controller that is closing is already not attached, so the lockout ends the
moment the bridge loses the controller, before the client thread has even
noticed; a different controller attaching never inherits it. The lockout,
panic and re-arm keys are handled from raw key events and are never
suppressed.

## Lifecycle edge cases

These events keep the controller attached (except world exit) but release
every actuator, so no hold can survive a change of situation:

| Event | Behavior |
|---|---|
| Death | Released once when the player is first seen dead. The death screen is a screen: interaction taps are dropped. Commands sent while dead apply to the dead player and are released by the respawn. Vanilla's own state on the corpse (an in-progress item use or block break behind the death screen) is the same as for a human and ends with the respawn. |
| Respawn | The client replaces its player: released again. Respawning is only ever an explicit `respawn` request or the human's button. |
| Dimension change | The client replaces its player: released. The `dimension_change` event (and `respawn` after death) tells subscribed agents. |
| Screen opened (GUI) | Agent `attack`/`use` released and interaction taps, hotbar selects dropped (D4a); movement holds continue through screens (D4). Human clicks inside the screen cancel agent inventory work. |
| Pause menu | In human priority it is human input and keeps the agent paused while open. In agent exclusive Escape stays live; the menu opens and the agent is not paused (a singleplayer world is frozen anyway). |
| Focus loss | Never releases by itself. While a controller is attached, `client.suppressPauseOnLostFocus` (default on) keeps the game running; with it off the vanilla pause menu opens, which pauses a human-priority agent. With no controller, or panic latched, vanilla pauses. |
| World leave | The controller is severed (`left world`), so the lockout ends and everything is released. The panic latch survives rejoining. |
| Game shutdown | Released before the bridge stops (close 1001). |

## Timing

Measured in the M5.1 rendered verification (D25): human input, panic and
disconnect release within a few frames or ticks (about 10–110 ms); a frozen agent is
released by the 2-second pong watchdog within the documented 2.5 seconds. A
stalled Minecraft client thread cannot release anything until it runs again.
