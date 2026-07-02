# Marionette

NeoForge mod (Minecraft 1.21.8, Java 21) that lets an external program observe and
control a real, rendered Minecraft client. Infrastructure only: it ships no AI
models and makes no gameplay decisions. A Prattlemob project — prattlemob.com.

## Status & ground rules

- **Pre-implementation.** The mod classes are intentionally empty scaffolds.
- **Open decisions — do not lock in without asking:** the mod↔agent transport,
  the observation/action formats, client-only vs. server component, and any mod
  integrations (e.g. Baritone). Leave marked TODOs instead of choosing.
- **Protocol-first:** the wire contract gets specified in `protocol/` before it
  is implemented; `examples/` holds reference agents; `docs/` holds design notes.
- **Stay agent-agnostic:** never hard-wire a specific model, AI service, or
  agent framework into the mod itself.
- License is MIT but provisional — confirm before anything release-facing.

## Identity (fixed — do not rename)

Mod id `marionette`, package/group `com.prattlemob.marionette`,
GitHub `Prattlemob/marionette`.

## Build

- `./gradlew build` — full build; `./gradlew runClient` — launch a dev client.
- `src/main/templates/META-INF/neoforge.mods.toml` is a Groovy template; values
  come from `gradle.properties` (edit those, not the generated copy in `build/`).
  Any literal `${` in that file — even inside a comment — breaks
  `generateModMetadata`.
