# Marionette

**Marionette moves the body — you bring the brain.**

Marionette is a [NeoForge](https://neoforged.net/) mod that turns a real, rendered Minecraft client into one an external program can observe and control. Your agent — an LLM, a script, a reinforcement-learning policy, written in any language — connects to the client, receives what the player perceives, and sends back actions. Minecraft plays on screen exactly as it always does; something else is holding the controller.

## What it is — and isn't

- **Infrastructure, not an AI.** Marionette ships no models and makes no gameplay decisions. It is the strings, not the puppeteer: you supply whatever intelligence pulls them.
- **A real client, not a headless bot.** Unlike protocol-level bots, Marionette drives an actual game client. The world renders normally, so you can watch, record, or stream a session (for example through OBS) with no special viewer.
- **Agent-agnostic.** Marionette does not care what connects to it or what language it is written in. The contract between mod and agent is a documented, language-neutral [protocol](protocol/v1.md), not a library you must import.

## What it does

- **Perception out.** Player state every tick, plus opt-in inventory and open-menu contents, the crosshair target, world context (dimension, time, weather, light), nearby entities, bounded block scans, and one-shot events (damage, death, respawn, item pickup, chat, blocks broken, dimension changes).
- **Action in.** Movement, smoothed or instant camera control, attack/use/hotbar, inventory and container actions (including modded storage, crafting and furnaces), chat, respawn and swapping hands.
- **Safety for the human at the keyboard.** Loopback-only binding, one controller at a time with read-only observers, a 2-second liveness watchdog, a latched panic key, human input that always outranks the agent, and a status HUD that shows what the agent is doing.
- **A stable, documented contract.** Protocol 2 over a localhost WebSocket carrying JSON; new features arrive as capability flags, so existing agents keep working.

## Quickstart

From nothing to an external script walking the player in a square. This uses
the development client built from this repository; there is no released mod
jar yet.

**You need:** a Java 21 JDK, Git, Python 3.11 or newer, and a desktop session
(the game opens a window). The first build downloads Minecraft and NeoForge,
about 1 GB, which takes a few minutes. The development client starts as an
offline player named `Dev`; no login is involved.

**1. Get the code.**

```sh
git clone https://github.com/Prattlemob/marionette.git
cd marionette
```

**2. Start Minecraft with Marionette.**

```sh
./gradlew runClient
```

(On Windows use `gradlew.bat runClient`.) Leave this terminal running; the
game window opens on the title screen once the build finishes.

**3. Open a test world.** Click **Singleplayer → Create New World**. Set
**Game Mode** to **Creative**, open the **World** tab and set **World Type** to
**Superflat**, then click **Create New World**. A flat creative world gives the
agent room to walk and keeps your real worlds out of the way. Once you are in
the world, the top-left corner shows `Marionette: idle`: the mod is loaded and
waiting for an agent. Press **F3+P** once (hold F3, tap P); chat confirms
`Pause on lost focus: disabled`. Otherwise the game opens its pause menu when
you switch to a terminal, and an open pause menu pauses the agent.

**4. Install the Python client** in a second terminal, from the repository
root:

```sh
python3 -m venv .venv
. .venv/bin/activate            # Windows: .venv\Scripts\activate
python -m pip install -e ./python
```

This installs the client from your checkout, which supports every example.
The published alpha, `python -m pip install "marionette-mc==0.1.0a1"`, is
enough for the probe, the dashboard and the square walk; see the
[examples](examples/README.md#choosing-a-client-package) for which needs what.

**5. Run the examples** from the second terminal, with the game still
running:

```sh
python examples/probe.py
python examples/walk_square.py
```

`probe.py` prints the handshake and a few observations and nudges the player
forward for 0.2 seconds. `walk_square.py` walks four sides of about five blocks
each, turning 90° between them, and prints how far from the start it finished.
While it runs the HUD shows `Marionette: connected` and the held controls.
Every hold is bounded and the player is released when the script exits.

**6. Stop.** Agents stop by themselves; Ctrl-C a running script to end it.
Press **F8** in the game at any time to cut an agent off (press **F9** to
allow agents again). Close the game window to end `runClient`.

Next, try the [scripted reference agent](examples/reference_agent.py) and
the [other examples](examples/README.md), or read the [protocol](protocol/v1.md)
to write an agent in another language.

### Using your own Minecraft installation

Build the jar with `./gradlew build` and copy `build/libs/marionette-0.1.0.jar`
into the `mods` folder of a **Minecraft 1.21.8** instance with **NeoForge
21.8.53** or a later 21.8 release (for example a NeoForge profile in the
official launcher, Prism Launcher or MultiMC). Marionette is client-only;
multiplayer servers do not need it, but many forbid automated play.

## Configuration

Marionette writes `config/marionette-client.toml` on first launch (in `run/`
for the development client). The defaults suit local use: the bridge listens
on `127.0.0.1:24680`, allows two observers, disconnects an agent that stops
answering pings for 2 seconds, allows ordinary chat but not commands, and
shows the status HUD. Commonly changed settings:

| Setting | Default | Meaning |
|---|---|---|
| `[bridge] port` | `24680` | Bridge TCP port (restart required); pass the same port to the examples |
| `[bridge] maxObservers` | `2` | Read-only connections allowed besides the controller; 0 disables them |
| `[observation] rateDivisor` | `1` | Default observation cadence: one frame every N ticks (live) |
| `[chat] allowCommands` | `false` | Whether agents may run commands through `chat` (live) |
| `[precedence] resumeAfterMillis` | `2000` | How long the agent stays paused after your last input (live) |
| `[hud] enabled` | `true` | Status overlay; F6 toggles and saves it (live) |
| `[logging] verbosity` | `NORMAL` | `QUIET`, `NORMAL` or `VERBOSE`, with per-category overrides (live) |

Every setting, its range and notes are in
[docs/configuration.md](docs/configuration.md).

## Staying in control

Your own input always outranks the agent. The keys below are defaults; all
are rebindable under **Options → Controls → Key Binds → Marionette**.

| Key | Action |
|---|---|
| any gameplay input | In the default *human priority* mode, moving, looking, attacking, using, hotbar keys or the pause menu release everything the agent holds and pause it. It may drive again 2 seconds after your last input; nothing it held is restored. |
| **F7** | Toggle *agent exclusive* mode while an agent is attached: your gameplay input is ignored so only the agent drives. Escape, chat, inventory, F1–F3/F5/F11, F7, F8 and F9 keep working, and the mode ends by itself when the agent goes away. |
| **F8** | Panic: release the agent and disconnect it at once. Panic **latches**: every agent that tries to take control is refused until you press F9. Observers stay attached. |
| **F9** | Allow agent control again after panic. It resumes nothing; an agent must connect afresh. |
| **F6** | Show or hide the status HUD. |

The status HUD in the top-left corner shows `Marionette: idle` with no agent;
`Marionette: connected` with the agent's name, the observer count, the mode
(`Human priority`, `PAUSED by your input` or `AGENT EXCLUSIVE: input
locked`), the controls the agent holds and live counters while one is
attached; and `Marionette: PANIC` while panic is latched. Agents can read the
same data with the `status` query ([examples/status.py](examples/status.py)).

The bridge only listens on loopback, admits one controlling agent at a time,
and trusts every native process on this computer without authentication;
browser pages are refused. An agent that stops answering pings is
disconnected after 2 seconds, and losing the agent for any reason releases
every control. The [safety model](docs/safety-model.md) explains how these
layers fit together, and [SECURITY.md](SECURITY.md) states the trust policy.

## Protocol and clients

The current wire contract is [protocol 2](protocol/v1.md) (the canonical
document keeps its `v1.md` path). The protocol integer changes only for
breaking changes; everything else is an additive capability flag in the
`hello` reply, which agents feature-detect. See
[protocol/README.md](protocol/README.md) for the versioning rules and
[docs/protocol-evolution.md](docs/protocol-evolution.md) for the rationale
and history.

The typed asyncio Python client lives in [python/](python/README.md)
(distribution `marionette-mc`, Python 3.11+). The development alpha
`marionette-mc==0.1.0a1` is published on
[PyPI](https://pypi.org/project/marionette-mc/0.1.0a1/); it passed
clean-environment installation and rendered acceptance, works with the current
mod, and covers movement, camera, interaction, observers and inventory
actions. Events, the extra observation sections, block scans, crafting,
chat/respawn and `status` need the in-repository client
(`pip install -e ./python`) until a further release is authorized.

## Project status

All core milestones through Phase 5 are implemented: protocol 2 and bridge
hardening, the full movement and camera set, interaction, inventory,
containers, modded storage and crafting, gameplay controls, every perception
section, one-shot events, human precedence and lifecycle safety, and
diagnostics. Outstanding verification is tracked in [ROADMAP.md](ROADMAP.md):
physical-keyboard confirmation of the human-precedence checks (performed with
synthetic input) and the camera's alt-tab acceptance remain open. Optional
integrations (Baritone navigation, a server companion, pixel streaming) and
release packaging come next. Design decisions, settled and open, are recorded
in [docs/decisions.md](docs/decisions.md).

Design discussion happens in [issues](https://github.com/Prattlemob/marionette/issues) — see [CONTRIBUTING.md](CONTRIBUTING.md).
Repository layout and coding-agent guidance live in [AGENTS.md](AGENTS.md);
the [documentation index](docs/README.md) links the design notes, protocol,
decisions, and historical milestone designs and plans.

## License

MIT — see [LICENSE](LICENSE); confirmed by the owner on 2026-10-03. Publication still requires explicit authorization.

## Disclaimer

Marionette is not an official Minecraft product and is not affiliated with, or endorsed by, Mojang or Microsoft. It ships no AI models and makes no gameplay decisions; what an agent does while connected is the responsibility of whoever runs it. In particular, many multiplayer servers forbid automated play — check a server's rules before connecting an agent to it.

---

A [Prattlemob](https://github.com/Prattlemob) project — [prattlemob.com](https://prattlemob.com)
