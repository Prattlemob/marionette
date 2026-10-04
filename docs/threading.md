# Threading model

Marionette touches two kinds of thread: one Netty event-loop thread that owns
every socket, and Minecraft's client thread, which owns every piece of game
state. The rule that keeps them apart is short:

> **Netty threads parse and enqueue; game state is read and changed only on
> the client thread.**

Everything below follows from that rule. The normative wire behavior is in
[protocol/v1.md](../protocol/v1.md); the package boundaries are in
[AGENTS.md](../AGENTS.md).

## The threads

| Thread | Owns | Never does |
|---|---|---|
| `marionette-bridge` (one Netty `NioEventLoopGroup` thread, daemon) | TCP accept, HTTP upgrade, Origin and hello-timeout checks, WebSocket framing, frame-size limits, ping/pong liveness, parsing JSON into typed commands, the handshake and admission (`ProtocolSession`), per-connection inbound queues, outbound writes and backpressure | touch `Minecraft`, the player, the world or any control state |
| Minecraft client thread (ticks and rendered frames) | Control state, applying commands, sampling observations, recording events, block-scan reads, inventory clicks, human precedence, panic, the HUD | block on the network |

Both the tick handlers and the render-frame handler run on the client thread,
so the control state needs no locks; the tick changes it and the render frame
only advances camera interpolation.

The bridge and protocol packages (`bridge/`, `bridge/protocol/`) contain no
Minecraft imports, so the whole transport, parser and session state machine
run in headless JUnit tests. Minecraft access is isolated in
`MarionetteClient`, the mixins and the observation/event adapters, behind
small Minecraft-free cores (`ControlState`, `CameraSmoother`, `EventRecorder`,
`InventoryJson`, `HudLines`, `MarionetteLog`).

## Inbound: from frame to effect

1. A text frame arrives on the Netty thread. It is size-checked, parsed and
   validated into an `AgentCommand`. Malformed input is answered with an
   `error` frame from the Netty thread, without involving the game.
2. Valid commands enter that connection's bounded queue (128 commands, plus
   one priority slot for `release`). A full queue closes only that
   connection with 1013. The queue entry carries the connection's generation,
   so work from a closed or replaced session is recognizably stale.
3. On each client tick (`ClientTickEvent.Pre`) the client thread drains
   commands round-robin across connections: at most 32 per tick and no new
   command after 2 ms. A `release` is always taken first. Each command is
   applied on the client thread: control holds, look intents, inventory
   operations, scans, chat, respawn, `configure` and `status`.
4. Replies (`inventory_result`, `scan_result`, `action_result`,
   `status_result`, `error`) are built on the client thread and handed to
   Netty for writing.

A command is therefore applied at the next client tick, normally within
50 ms; the `status` counter `commandLatencyMillis` measures exactly this.

## Outbound: observations and events

- **Observations** are sampled on the client thread at the end of each tick
  (`ClientTickEvent.Post`), only for sections some due session selected, and
  serialized once per distinct section selection. Each connection gets the
  frame on ticks its own divisor divides. If the channel is not writable, the
  frame replaces the connection's single stashed frame (latest wins) and a
  flush is scheduled on the Netty thread. A slow reader only ever loses its own
  observations; it never delays the game or other agents.
- **Events** are recorded on the client thread when vanilla reports them
  (mixins and NeoForge hooks, through `MinecraftEvents` and `EventRecorder`),
  stamped with a per-connection `seq`, and written immediately into a separate
  bounded queue that never coalesces. Overflowing it closes that connection.
- Reliable writes share a 256 KiB per-connection budget; exceeding it closes
  the connection with 1013 rather than blocking the client thread.

The client thread never waits for a socket. Every cross-thread hand-off is a
bounded queue, an atomic latest-value slot or a scheduled Netty task.

## Rendered frames

`RenderFrameEvent.Pre` advances an active smoothed camera pan by the real
frame time, writing the player's rotation the same way vanilla mouse input
does, so pans look smooth at any frame rate (D5). The pan's target is set
tick-side by a `look` command; the render frame only interpolates towards it.
The status HUD is drawn as a GUI layer from the same `StatusReport` a
`status` query returns.

## Safety across threads

Releasing controls is a client-thread operation, so a network-side event can
only *request* it:

- When a controller is lost (disconnect, socket error, pong timeout,
  overload), the Netty thread marks it not ready at once. From that moment it
  is no longer "the attached controller", so the input lockout ends and none of
  its queued work is applied.
- The client thread checks for a lost controller and a priority release at
  the start of every tick, between commands, and on every rendered frame
  (`processSafety`), and releases everything at whichever comes first.
- Panic runs on the client thread from a raw key event: it releases, then
  severs and latches under the bridge's admission lock, so a controller hello
  racing the panic is either severed or refused, never admitted afterwards.
- Human input is detected on the client thread from vanilla's key, mouse and
  scroll callbacks and releases immediately, before the next tick applies any
  command.

A frozen *agent* is caught by the pong watchdog on the Netty thread. A frozen
*client thread* cannot release anything until it runs again; no thread can
change game state on its behalf. See the [safety model](safety-model.md).

## Lifecycle

- The bridge starts once client setup has loaded the config, on the main
  thread, and binds the address resolved once from `bridge.bindAddress`.
- Leaving a world severs the controller (`left world`) and releases. Observers
  stay connected and receive no frames until a world is joined again.
- On shutdown controls are released before the bridge stops, and connections
  close with 1001.
- The common entry point `Marionette` loads no client classes, so the jar is
  safe on a dedicated server, where it does nothing.
