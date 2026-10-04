# Marionette

**Marionette moves the body — you bring the brain.**

Marionette is a [NeoForge](https://neoforged.net/) mod that turns a real, rendered Minecraft client into one an external program can observe and control. Your agent — an LLM, a script, a reinforcement-learning policy, written in any language — connects to the client, receives what the player perceives, and sends back actions. Minecraft plays on screen exactly as it always does; something else is holding the controller.

## What it is — and isn't

- **Infrastructure, not an AI.** Marionette ships no models and makes no gameplay decisions. It is the strings, not the puppeteer: you supply whatever intelligence pulls them.
- **A real client, not a headless bot.** Unlike protocol-level bots, Marionette drives an actual game client. The world renders normally, so you can watch, record, or stream a session (for example through OBS) with no special viewer.
- **Agent-agnostic.** Marionette does not care what connects to it or what language it is written in. The contract between mod and agent will be a documented, language-neutral protocol, not a library you must import.

## What it will do

Marionette aims to provide:

- **Perception out.** The agent receives what the player would perceive.
- **Action in.** The agent sends back actions a player could take, and Marionette performs them in the client.
- **A spectator-friendly window.** Because the client renders normally, humans can watch the agent play — live or recorded.
- **A stable, documented contract.** The protocol is the product: anyone should be able to build an agent against it without touching the mod's internals.

## Project status

**Protocol 2, bridge hardening, movement, camera smoothing, attack/use/hotbar control, inventory/container actions (vanilla and generically verified modded storage), player, inventory, crosshair-target, world and nearby-entity observations, on-demand bounded block scans, opt-in one-shot events, human precedence and a status HUD with a `status` diagnostics query are implemented.** An external script can drive the rendered player over a localhost WebSocket, with read-only observers alongside the controller — see [examples/](examples/) for working clients. Events (damage, death, respawn, item pickup, chat, block breaking, dimension change) the opt-in inventory section (held item, hotbar, inventory, armor, offhand and open-menu contents) and the opt-in target and world sections (crosshair block or entity within vanilla reach; dimension, time, weather and light) and the opt-in entities section (nearby mobs, players and items with hostility classification, nearest first under configured caps) and block scans (a palette of block ids plus indices for a capped box around the player, read across ticks under a work budget) require the in-repository Python client; the published 0.1.0a1 alpha predates them and never receives them. See [ROADMAP.md](ROADMAP.md) for verification status.

- The implementation plan lives in [ROADMAP.md](ROADMAP.md) — phases, milestones, and definitions of done.
- Design decisions (settled, experiment-gated, and still open) are recorded in [docs/decisions.md](docs/decisions.md). Highlights: the transport is a localhost WebSocket carrying JSON; the core is client-only with an optional server component later; [Baritone](https://github.com/cabaletta/baritone) is planned as an optional (never bundled) integration for high-level navigation.

Design discussion happens in [issues](https://github.com/Prattlemob/marionette/issues) — see [CONTRIBUTING.md](CONTRIBUTING.md).

Repository layout and coding-agent guidance live in [AGENTS.md](AGENTS.md).
The [documentation index](docs/README.md) links the protocol, decisions, and
historical milestone designs and plans.

## Installation

*Coming soon.* There is nothing to install yet.

## Usage

Connect an agent over the localhost WebSocket bridge; see [examples/](examples/) for reference agents (`probe.py`, `walk_square.py`).

## Configuration

Marionette generates `config/marionette-client.toml` on first launch. Values
marked *(live)* are picked up as soon as the file is saved (NeoForge watches
config files); values marked *(restart required)* are read once at startup.

```toml
[bridge]
	#Master switch: when false, the WebSocket bridge never starts. (restart required)
	enabled = true
	#TCP port for the bridge listener. (restart required)
	# Default: 24680
	# Range: 1 ~ 65535
	port = 24680
	#Bind address, resolved once at start. Non-loopback or unresolvable values are
	#clamped to 127.0.0.1 with a warning unless the opt-out below is set. (restart required)
	bindAddress = "127.0.0.1"
	#Explicit opt-out of loopback enforcement. Only when true is a non-loopback
	#bindAddress bound as configured, with a warning at every start. The bridge has
	#NO authentication or encryption: anyone who can reach the port can control your
	#game. Remote access is unsupported; leave false. (restart required)
	iUnderstandNonLoopbackIsUnauthenticated = false
	#Maximum simultaneous read-only observer connections (role "observer");
	#0 disables the observer role entirely. (restart required)
	# Default: 2
	# Range: 0 ~ 8
	maxObservers = 2
	#Seconds a new connection may take to complete the hello handshake
	#before it is closed. (restart required)
	# Default: 10
	# Range: 1 ~ 60
	helloTimeoutSeconds = 10
	#Maximum seconds without a pong before disconnect and release.
	#Agents must keep answering pings; a stall longer than about three
	#quarters of this value can disconnect a healthy agent. (restart required)
	# Default: 2
	# Range: 1 ~ 60
	pongTimeoutSeconds = 2

[observation]
	#Send one observation frame every N client ticks. (live)
	#Agents can override this per session with the `configure` protocol
	#message; this config value is the default and is restored on disconnect.
	# Default: 1
	# Range: 1 ~ 100
	rateDivisor = 1
	#Radius in blocks of the `entities` observation section, for every session. (live)
	# Default: 32
	# Range: 4 ~ 64
	entityRadius = 32
	#Most entities listed in the `entities` section; the nearest are kept and
	#the section is flagged truncated. (live)
	# Default: 64
	# Range: 1 ~ 256
	entityMaxCount = 64
	#Hard cap for `scan` requests: every scanned block must lie within this many
	#blocks of the player's feet block on each axis; larger requests are refused. (live)
	# Default: 16
	# Range: 4 ~ 32
	blockScanRadius = 16
	#Most block positions a scan reads per client tick; larger scans continue on
	#later ticks so a scan never stalls a frame. (live)
	# Default: 1024
	# Range: 64 ~ 8192
	blockScanBlocksPerTick = 1024

[client]
	#While an agent is connected, suppress the vanilla pause-on-focus-loss so
	#the session keeps running and streaming when unfocused. (live)
	suppressPauseOnLostFocus = true

[precedence]
	#Human-priority mode: milliseconds after the last human gameplay input before
	#a paused agent may drive again. Held keys and an open pause menu keep the pause. (live)
	# Default: 2000
	# Range: 250 ~ 60000
	resumeAfterMillis = 2000

[chat]
	#Allow the controller to send ordinary chat messages with the `chat` request. (live)
	allowChat = true
	#Allow the controller to execute commands with the `chat` request's `command`
	#field, as if typed after '/'. Off by default: commands can change the world,
	#game rules and other players. (live)
	allowCommands = false
	#Most accepted chat messages and commands together in any 10-second window,
	#for the whole client (reconnecting does not reset it). (live)
	# Default: 5
	# Range: 1 ~ 8
	maxMessages = 5

[camera]
	#Characteristic speed of smoothed camera pans, in degrees/second. (live)
	#Agents scale it per pan with the `speed` multiplier on look mode "smooth".
	# Default: 180.0
	# Range: 10.0 ~ 1080.0
	smoothingSpeed = 180.0

[logging]
	#QUIET: warnings/errors only. NORMAL: lifecycle + connection events.
	#VERBOSE: adds per-tick heartbeat and puppet-position evidence logs. (live)
	#The default for every category below.
	#Allowed Values: QUIET, NORMAL, VERBOSE
	verbosity = "NORMAL"
	#Verbosity of the 'bridge' category (logger marionette.bridge); INHERIT uses verbosity above. (live)
	#Allowed Values: INHERIT, QUIET, NORMAL, VERBOSE
	bridge = "INHERIT"
	#Verbosity of the 'control' category (logger marionette.control); INHERIT uses verbosity above. (live)
	#Allowed Values: INHERIT, QUIET, NORMAL, VERBOSE
	control = "INHERIT"
	#Verbosity of the 'precedence' category (logger marionette.precedence); INHERIT uses verbosity above. (live)
	#Allowed Values: INHERIT, QUIET, NORMAL, VERBOSE
	precedence = "INHERIT"
	#Verbosity of the 'observation' category (logger marionette.observation); INHERIT uses verbosity above. (live)
	#Allowed Values: INHERIT, QUIET, NORMAL, VERBOSE
	observation = "INHERIT"
	#Verbosity of the 'events' category (logger marionette.events); INHERIT uses verbosity above. (live)
	#Allowed Values: INHERIT, QUIET, NORMAL, VERBOSE
	events = "INHERIT"
	#Verbosity of the 'client' category (logger marionette.client); INHERIT uses verbosity above. (live)
	#Allowed Values: INHERIT, QUIET, NORMAL, VERBOSE
	client = "INHERIT"

[hud]
	#Show the Marionette status overlay (connection, precedence mode, held agent
	#controls, counters). The rebindable HUD key toggles it and saves this value. (live)
	enabled = true
```

Notes:

- `bindAddress` is resolved once at start. A non-loopback or unresolvable
  value is clamped to `127.0.0.1` with a loud warning. Only the explicit
  opt-out `iUnderstandNonLoopbackIsUnauthenticated = true` binds a resolvable
  non-loopback address as configured, with a warning at every start; the
  bridge has no authentication or encryption, and remote access remains
  unsupported (D16, D25).
- `allowCommands` is the human's switch: agents can never enable command
  execution themselves (D24).
- `suppressPauseOnLostFocus` only takes effect while an agent is connected
  and panic is not latched; otherwise the game pauses on focus loss exactly
  as vanilla. With it off, the focus-loss pause menu pauses a human-priority
  agent like any other human input (D25).
- `[precedence] resumeAfterMillis` is how long a human-priority agent stays
  paused after your last gameplay input (see Human precedence below).
- The `[observation]` radius/count caps are defined ahead of the features
  that consume them (Phase 4) so operators can see the ceilings; they have
  no effect yet.

## Protocol

The current wire contract is [protocol 2](protocol/v1.md) (canonical document path retained). Protocol 1 clients must offer version 2 and read position/rotation from `observation.player`. See [examples/dashboard.py](examples/dashboard.py) for a live read-only player dashboard.

## License

MIT — see [LICENSE](LICENSE); confirmed by the owner on 2026-10-03. Publication still requires explicit authorization.

## Disclaimer

Marionette is not an official Minecraft product and is not affiliated with, or endorsed by, Mojang or Microsoft. It ships no AI models and makes no gameplay decisions; what an agent does while connected is the responsibility of whoever runs it. In particular, many multiplayer servers forbid automated play — check a server's rules before connecting an agent to it.

---

A [Prattlemob](https://github.com/Prattlemob) project — [prattlemob.com](https://prattlemob.com)

### Human precedence

Your own input always outranks the agent (D25, protocol/v1.md, Human
precedence). There are three modes:

- **Human priority** (default). Any gameplay input of yours in game — moving,
  jumping, sneaking, sprinting, turning the camera with the mouse, attacking,
  using, picking a block, the hotbar keys or scroll wheel, drop, swap hands, or
  opening the pause menu — immediately releases everything the agent holds and
  pauses it. While paused the agent's movement and camera commands are
  discarded and its inventory changes, chat and respawn requests are refused.
  The agent may drive again `resumeAfterMillis` (default 2 s) after your last
  input; nothing it held is restored.
- **Agent exclusive.** Press the rebindable **F7** "Toggle agent input
  lockout" key (Controls → Marionette) in game while an agent is attached. Your
  movement, jump, sneak, sprint, mouse look, attack/use, pick-block, drop,
  swap-hands and hotbar input are then ignored, so only the agent drives.
  Escape and the pause menu, F1–F3/F5/F11, chat, inventory and every other
  interface key keep working, and so do F7 (press again to take control back,
  even from a screen), panic and re-arm. The lockout ends by itself the moment
  no agent is attached: disconnect, watchdog, panic or leaving the world.
- **Panic** (below).

Toasts report lockout changes. Agents that subscribe to events receive a
`control` event for each mode or pause change.

### Status HUD and diagnostics

A small overlay in the top-left corner shows what Marionette is doing (M5.2).
It is on by default, because the precedence mode must always be visible (D25).
Toggle it with the rebindable **F6** "Toggle Marionette status HUD" key
(Controls → Marionette); the choice is saved as `[hud] enabled`. F1 and the F3
debug screen hide it too. It shows:

- `Marionette: idle`, one dim line, while no controller is attached;
- `Marionette: connected`, then the agent's name and the observer count, the
  precedence mode (`Human priority`, `PAUSED by your input` or `AGENT
  EXCLUSIVE: input locked`), the controls the agent holds right now, and the
  controller's observation rate, frames sent and dropped, ping round trip and
  command latency;
- `Marionette: PANIC` in red, naming the re-arm key, while panic is latched.

Lines are kept short so toasts in the top-right corner never cover them.
Agents name themselves with the optional hello `agent` field. Any connection
can request the same data with the `status` query (`status` capability,
[protocol](protocol/v1.md#status--diagnostics-query-status)); see
[examples/status.py](examples/status.py).

Logging is per category: `[logging] verbosity` sets the default, and `bridge`,
`control`, `precedence`, `observation`, `events` and `client` override it
(`INHERIT`, `QUIET`, `NORMAL` or `VERBOSE`, live). Each category logs through
its own logger, `marionette.<category>`; connection lines carry `key=value` fields.
Warnings and errors are never silenced.

### Emergency control and development trust

The rebindable **F8** panic key (Controls → Marionette) releases the agent and
severs its controller connection, including during inventory animation or a
paused screen, and ends any input lockout. Panic then **latches**: every reconnecting controller is refused
(`panic_latched`, close 1008) until you press the separate, rebindable
**F9** "Allow agent control" key in game. Pressing F8 again never re-enables
anything, and re-arming resumes nothing; an agent must connect afresh. The
latch survives leaving and rejoining worlds and resets when Minecraft
restarts. A small toast shows when agent control is disabled or re-enabled.
Read-only observers remain attached and may still connect. The pong watchdog uses
`bridge.pongTimeoutSeconds` (default 2, range 1–60, restart required).
WebSocket libraries must continue reading and answering pings even when the
agent has no new command to send; an agent event loop that blocks for more
than about 1.5 seconds at the default can be disconnected. A frozen controller
is disconnected after the timeout plus at most one ping interval (0.5 seconds
at the default), then released on the next client tick or rendered frame. A
frozen Minecraft process cannot execute release until resumed.

The `bridgeSafety` capability advertises bounded inbound/outbound queues,
32-command/2 ms per-tick scheduling, priority release, and pending-connection
limits. Overload disconnects the offending connection (1013); outstanding
inventory outcomes may be unknown, so inspect state before retrying. See
[the wire limits](protocol/v1.md#transport).

Development access trusts native local processes. Browser Origin headers are
rejected, including `null`; native clients must omit Origin. This is not local
process authentication. Authentication remains a release gate; see
[the security policy](SECURITY.md).

## Python client

Install the published development alpha with
`python -m pip install "marionette-mc==0.1.0a1"`, then run
`python examples/probe.py` from this checkout. The typed asyncio client supports
Python 3.11+ and protocol 2; see [the API](python/README.md) and
[PyPI release](https://pypi.org/project/marionette-mc/0.1.0a1/).

The exact published pin passed clean-environment installation and rendered
observation/movement/release acceptance. The owner approved this client-only
alpha exception; mod and stable-release authentication gates remain in force.
M2.5 completion does not establish separate consumer integration acceptance.
