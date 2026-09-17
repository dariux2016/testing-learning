# AGENTS.md

Preferences and ground rules for any agent (Claude Code or otherwise) working in this repo.

## Project purpose

This is a **learning project**, not a production service. Its entire point is to practice the
classical testing pyramid — unit, slice, integration, end-to-end — against every kind of
component a real Spring Boot application has: controllers, services, repositories, Kafka
consumers/producers, outbound REST clients, security, and edge cases.

Optimize for pedagogical clarity over production shortcuts. Prefer explicit, readable test setup
over clever/DRY test helpers, at least until the basic patterns are solid. It's fine — good, even
— for early tests to be a little repetitive if that makes the technique being learned obvious.

## Stack

- Java 21
- Spring Boot 4 / Spring Framework 7
- Maven (chosen over Gradle)
- Testing stack: JUnit 5/6, AssertJ, Mockito, Testcontainers 2.x, WireMock, Awaitility,
  Spring Security Test, ArchUnit. Optional/later: jqwik (property-based testing), Spring Cloud
  Contract, PIT (mutation testing).

See [docs/testing-strategy.md](docs/testing-strategy.md) for the full point-by-point plan on how
to test each kind of component. That file is the canonical reference — when a new testing topic
or technique gets added to the project, append it there rather than only describing it in a
commit message.

## Workflow preferences

- **Always produce or update a plan before writing code** for anything non-trivial. Don't jump
  straight to implementation on a new feature/topic — sketch the approach first.
- Build up the project topic-by-topic (see the "suggested build-up order" section in the testing
  strategy doc), each with its own small, working feature slice, rather than scaffolding
  everything up front.
- No CI/CD pipeline and no cloud deployment concerns unless explicitly asked for — this is local
  learning only until stated otherwise.
- No third-party Claude Code skill/plugin marketplaces have been added to this project; stick to
  built-in tooling/knowledge unless the user explicitly asks to add one.
