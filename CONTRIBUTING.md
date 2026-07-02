# Contributing to Marionette

Thanks for your interest in Marionette. The project is very early — the design is not finalized and the mod is still an empty scaffold — so the most valuable contributions right now are ideas, not code.

## Right now

- **Open an issue** to discuss use cases, design questions, or the shape of the observation/action protocol. Early design input has the most leverage.
- **Hold off on large pull requests.** Until the core design lands, substantial code contributions are likely to conflict with it. If you want to build something sizeable, open an issue first so we can align.

## Once the project takes shape

The usual flow will apply:

1. Open an issue describing the change before starting work.
2. Fork, branch, and keep pull requests small and focused.
3. Make sure the project builds (`gradlew build`) before submitting.

## Ground rules

- Be respectful and constructive.
- Marionette is agent-agnostic infrastructure. Contributions that hard-wire a particular AI model, service, or agent framework into the mod itself are out of scope; they belong in [examples](examples/) or in your own project built on the protocol.

## License

By contributing, you agree that your contributions will be licensed under the same license as the project (see [LICENSE](LICENSE)).
