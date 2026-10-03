# Working on Marionette

Marionette is a client-side NeoForge mod that lets external programs observe
and control a real, rendered Minecraft client. It ships no AI models and makes
no gameplay decisions. Keep it agent-agnostic: model, service, and framework
integrations belong in external agents or `examples/`.

## Sources of truth

- `gradle.properties` and `build.gradle`: platform, dependency versions, and
  build configuration. The target is Minecraft 1.21.8, NeoForge 21.8, and a
  Java 21 toolchain; do not copy APIs from newer Minecraft versions.
- `protocol/v1.md`: current wire contract. Specify protocol changes here
  before implementing them; update relevant examples in the same change.
  Breaking changes bump the protocol integer; additive features use capabilities.
- `docs/decisions.md`: settled design decisions and unresolved questions.
  Preserve settled decisions. Resolve open or experiment-gated choices with
  the user when the task does not already authorize them, and record resolutions.
- `ROADMAP.md`: milestone order, completion status, and acceptance criteria.
- `docs/specs/` and `docs/plans/`: dated design and implementation context.
  These can describe superseded code; they are not current task instructions.

## Layout and boundaries

Keep the single Gradle project unless a concrete need justifies a new module.
Java sources live under `src/main/java/com/prattlemob/marionette/`:

- `Marionette`: common entry point; safe to load on a dedicated server.
- `MarionetteClient`: client lifecycle and tick/render orchestration.
- `bridge/`: Netty transport, connection ownership, queues, and backpressure.
- `bridge/protocol/`: parsing, messages, commands, and protocol sessions.
  Keep transport and protocol code free of Minecraft imports for headless tests.
- `control/`: control state, camera smoothing, and the input-applier seam.
  Keep state/math independent of Minecraft; isolate game access in the applier.
- `config/`: NeoForge configuration and validation.
- `mixin/`: narrowly scoped vanilla input and focus/pause hooks.

Tests mirror these packages under `src/test/java/`. Assets and mixin metadata
live in `src/main/resources/`. Mod metadata is generated from
`src/main/templates/META-INF/neoforge.mods.toml` using `gradle.properties`;
edit the template, not generated output. Literal `${` in template comments
also triggers Groovy expansion and must be avoided.

`examples/` contains reference protocol clients; `scripts/` contains development
and verification helpers. Do not commit local worlds, logs, caches, worktrees,
or build output. The repository's Minecraft skill is in
`.agents/skills/minecraft-modding/`; `.claude/skills/` links to it.

## Runtime invariants

- Netty threads parse and enqueue; game-state access stays on the client
  thread. Tick-side code changes control state; render-side camera interpolation
  also runs on the client thread.
- Preserve release-all behavior on controller loss and world exit. Observer
  disconnects must not release the controller's inputs.
- Preserve loopback-only binding, a single controller, read-only observers,
  and per-connection observation backpressure. See `SECURITY.md` and the protocol.
- Keep client-only classes behind client-side entry points; the common entry
  point must remain loadable on a dedicated server.

## Workflow and verification

Use a direct workflow: inspect the relevant code and contract, make a focused
change, and verify it. No workflow plugin is required. Add plans only when
their complexity warrants a durable record; routine fixes do not need one.

Optional roadmap workflows live in `.agents/skills/` (also linked under
`.claude/skills/`): `marionette-next-roadmap-item` implements one eligible
milestone; `marionette-roadmap` supervises sequential Orca workers across the
requested roadmap scope. Use the latter only for continuous coordination.

- `./gradlew test` runs headless JUnit tests.
- `./gradlew test --tests 'com.prattlemob.marionette.control.CameraSmootherTest'`
  runs a focused suite (substitute the relevant class).
- `./gradlew build` compiles, tests, and packages the mod; use for code/build changes.
- `./gradlew runClient` launches the interactive development client. Changes to
  mixins, input, rendering, or lifecycle also need an appropriate in-game check;
  unit tests alone do not establish that these work. Report unperformed checks.
- Documentation-only changes need link/path and diff checks, not a client launch.

Keep bundled and development-runtime Netty codec versions aligned when changing
dependencies, and verify both build and development-runtime resolution.

Identity is fixed: mod id `marionette`, group/package
`com.prattlemob.marionette`, repository `Prattlemob/marionette`. The current
license is MIT but provisional; confirm it before release-facing work.
