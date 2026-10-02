# Contributing to Marionette

Marionette implements protocol v1, controller and observer connections,
configuration, movement, and camera smoothing. See [ROADMAP.md](ROADMAP.md)
for completed milestones and upcoming work, and [docs/decisions.md](docs/decisions.md)
for the design rationale.

## Development

1. Use a Java 21 toolchain and the checked-in Gradle wrapper.
2. Run `./gradlew build` to compile, run headless tests, and package the mod.
3. Run `./gradlew runClient` for interactive verification in a local world.
4. Use the reference clients in [examples/](examples/) to exercise the bridge.

The source layout, threading boundaries, and verification guidance are in
[AGENTS.md](AGENTS.md). No AI tool or workflow plugin is required.

## Changes and pull requests

- Discuss substantial features or design changes in an issue before starting.
- Keep pull requests focused and describe the behavior changed and checks run.
- Specify wire changes in [protocol/](protocol/) before implementing them, and
  update relevant examples alongside the implementation.
- Add regression coverage for behavior changes. Input, mixin, rendering, and
  lifecycle changes also need in-game verification; state what was not tested.
- For documentation-only changes, check links and paths.

## Ground rules

- Be respectful and constructive.
- Keep Marionette agent-agnostic. Integrations tied to a particular AI model,
  service, or agent framework belong in examples or external projects.
- Preserve the runtime and safety boundaries described in [AGENTS.md](AGENTS.md)
  and [SECURITY.md](SECURITY.md).

## License

By contributing, you agree that your contributions will be licensed under the
same license as the project (see [LICENSE](LICENSE)).
