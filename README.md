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

**Very early — pre-implementation.** The repository is a clean mod scaffold with no functionality; the design is not finalized and everything below is still open:

- **TODO:** choose the transport between mod and agent.
- **TODO:** define the observation and action formats.
- **TODO:** decide whether anything runs server-side, or the mod stays purely client-side.
- **TODO:** decide on integrations with other mods (none are planned or bundled yet).

Design discussion happens in [issues](https://github.com/Prattlemob/marionette/issues) — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Installation

*Coming soon.* There is nothing to install yet.

## Usage

*Coming soon.* How to connect an agent will be documented once the design lands. Reference agents will live in [examples/](examples/).

## Protocol

*To be defined.* The wire contract will be specified in [protocol/](protocol/) before it is implemented, so that agents in any language can target it.

## License

MIT — see [LICENSE](LICENSE). (The license choice is provisional until the first public release.)

## Disclaimer

Marionette is not an official Minecraft product and is not affiliated with, or endorsed by, Mojang or Microsoft. It ships no AI models and makes no gameplay decisions; what an agent does while connected is the responsibility of whoever runs it. In particular, many multiplayer servers forbid automated play — check a server's rules before connecting an agent to it.

---

A [Prattlemob](https://github.com/Prattlemob) project — [prattlemob.com](https://prattlemob.com)
