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

**Phase 1 walking skeleton works.** An external script can already drive the rendered player over a localhost WebSocket — see [examples/](examples/) for a working agent. The design direction for what's next is settled:

- The implementation plan lives in [ROADMAP.md](ROADMAP.md) — phases, milestones, and definitions of done.
- Design decisions (settled, experiment-gated, and still open) are recorded in [docs/decisions.md](docs/decisions.md). Highlights: the transport is a localhost WebSocket carrying JSON; the core is client-only with an optional server component later; [Baritone](https://github.com/cabaletta/baritone) is planned as an optional (never bundled) integration for high-level navigation.

Design discussion happens in [issues](https://github.com/Prattlemob/marionette/issues) — see [CONTRIBUTING.md](CONTRIBUTING.md).

## Installation

*Coming soon.* There is nothing to install yet.

## Usage

Connect an agent over the localhost WebSocket bridge; see [examples/](examples/) for reference agents (`probe.py`, `walk_square.py`).

## Protocol

The current wire contract is the throwaway [v0 draft](protocol/v0-draft.md); it will be replaced by protocol v1 in Phase 2. The stable, documented contract will be specified in [protocol/](protocol/), so that agents in any language can target it.

## License

MIT — see [LICENSE](LICENSE). (The license choice is provisional until the first public release.)

## Disclaimer

Marionette is not an official Minecraft product and is not affiliated with, or endorsed by, Mojang or Microsoft. It ships no AI models and makes no gameplay decisions; what an agent does while connected is the responsibility of whoever runs it. In particular, many multiplayer servers forbid automated play — check a server's rules before connecting an agent to it.

---

A [Prattlemob](https://github.com/Prattlemob) project — [prattlemob.com](https://prattlemob.com)
