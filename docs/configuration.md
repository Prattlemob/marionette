# Configuration

Marionette writes `config/marionette-client.toml` in the game directory the
first time the client starts: `run/config/` for the development client
(`./gradlew runClient`), or the `config/` folder of your Minecraft instance.
Values marked *(live)* are picked up as soon as the file is saved (NeoForge
watches config files); values marked *(restart required)* are read once at
startup, so they take effect at the next launch.

The generated file, with every default:

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

## Notes

- `bindAddress` is resolved once at start. A non-loopback or unresolvable
  value is clamped to `127.0.0.1` with a loud warning. Only the explicit
  opt-out `iUnderstandNonLoopbackIsUnauthenticated = true` binds a resolvable
  non-loopback address as configured, with a warning at every start; the
  bridge has no authentication or encryption, and remote access remains
  unsupported (D16, D25 in [decisions.md](decisions.md)).
- `pongTimeoutSeconds` is the agent-liveness watchdog (D16a). Agents must keep
  answering WebSocket pings; see the [safety model](safety-model.md).
- `allowCommands` is the human's switch: agents can never enable command
  execution themselves (D24).
- `suppressPauseOnLostFocus` only takes effect while an agent is connected
  and panic is not latched; otherwise the game pauses on focus loss exactly
  as vanilla. With it off, the focus-loss pause menu pauses a human-priority
  agent like any other human input (D25).
- `[precedence] resumeAfterMillis` is how long a human-priority agent stays
  paused after your last gameplay input ([safety model](safety-model.md)).
- `[observation]` sets the default stream cadence (agents override it per
  session with `configure`) and the caps of the `entities` section and of
  `scan` requests. The caps apply to every session; agents cannot raise them.
- `[logging]` sets a default verbosity and per-category overrides. Each
  category logs through its own logger, `marionette.<category>`; connection
  lines carry `key=value` fields. Warnings and errors are never silenced.
- `[hud] enabled` is also flipped and saved by the HUD toggle key (F6).
