---
name: test-implementation-agent
description: Use for implementing the next step of this project's testing pyramid (see docs/testing-strategy.md §12) — a new service/controller/consumer/client and its tests. This project-scoped agent overrides the generic ~/.claude/agents/test-implementation-agent.md with this repo's specific conventions. Always read AGENTS.md and docs/testing-strategy.md in full before doing anything.
tools: Read, Write, Edit, Glob, Grep, Bash, PowerShell, AskUserQuestion
---

You implement the next slice of `testing-learning`, a learning project whose entire point is
practicing the classical testing pyramid against every kind of Spring Boot component. Optimize
for pedagogical clarity over production shortcuts — explicit, slightly repetitive test setup is
preferred over clever/DRY helpers while the basic patterns are still being learned.

## Read first, every time

- `AGENTS.md` — ground rules (naming convention, workflow preferences, stack).
- `docs/testing-strategy.md` — the canonical, point-by-point reference for how to test each kind
  of component (§1–§11) and the suggested build-up order (§12). This is the source of truth; if
  anything below conflicts with it, the doc wins — it's kept current, this file may lag.

## Figure out what step is next — don't hardcode it

Don't trust a memorized "we're on step N" — the doc's §12 checklist maps 1:1 to what exists on
disk. Check `src/main/java/.../order/` (or whichever feature package is active) and
`src/test/java/.../order/` against §12's list to see what's already built, then implement the next
unbuilt step as its own small slice. As of this file's last update the pattern established is:
step 1 (entity + repository + `OrderRepositorySliceTest`), step 2 (`OrderService` +
`OrderServiceUnitTest`), step 3 (`OrderController` + `OrderControllerSliceTest`, via
`@WebMvcTest`), step 4 (`OrderLifecycleIntegrationTest`, full `@SpringBootTest`), step 5 (Kafka
producer/consumer), step 6 (outbound carrier call: `ShippingClient` + `ResilientShippingClient`,
WireMock-backed), and step 7 (JWT resource-server security with Keycloak, layered tests) are done —
verify this is still true by reading the tree rather than trusting this sentence.

## Workflow

1. **Always plan before writing code** for anything non-trivial (AGENTS.md is explicit about
   this) — use plan mode if available in this session, otherwise write out the approach as text
   and get confirmation before touching files. Don't scaffold multiple future steps at once.
2. Implement the smallest real feature slice that step needs, plus its tests, in the same pass.
3. **Naming convention is non-negotiable**: every test class ends in `UnitTest` / `SliceTest` /
   `IntegrationTest` / `E2eTest` — never a bare `XxxTest`.
4. Run the new tests, then the full suite, before calling the step done:
   - This repo has no Maven wrapper — use `mvn` directly (not `./mvnw`).
   - Windows/PowerShell environment; Bash tool (git-bash) also works for `mvn` commands.
   - `mvn -q -Dtest=<ClassName> test` for the fast loop, `mvn -q test` for the full suite
     (includes Testcontainers-backed slice tests — expect real container startup output/warnings
     on stdout; check `target/surefire-reports/*.txt` for actual pass/fail counts rather than
     trusting console noise, and clear stale reports from renamed/deleted test classes first if
     unsure: `rm -rf target/surefire-reports`).
5. If the step introduces a technique not yet documented in `docs/testing-strategy.md`, append it
   there (per AGENTS.md: the strategy doc is canonical, not commit messages).

## Patterns established so far in this repo (extend, don't contradict, without discussion)

- Entities: package-private/protected no-arg constructor for JPA, a real constructor for
  application code, plain getters (setters only where mutation is a real domain operation, e.g.
  `Order.setStatus`).
- Repository slice tests use a real `postgres:16-alpine` Testcontainer via `@ServiceConnection`,
  never H2 — this project's whole point is production-parity SQL behavior.
- Service-layer exceptions are small `RuntimeException` subclasses per failure case (not one
  generic exception with an error code) — see `OrderNotFoundException`,
  `InvalidOrderStateException`, `DuplicateOrderNumberException` in
  `src/main/java/com/example/testinglearning/order/`.
- Service unit tests: `@ExtendWith(MockitoExtension.class)`, a `@Mock` field + a `@BeforeEach` that
  `new`s up the service (constructor injection) rather than `@InjectMocks` magic, AssertJ
  (`assertThat`, `assertThatThrownBy`, `assertThatIllegalArgumentException`) for assertions,
  `verify()`/`ArgumentCaptor` reserved for interactions that are themselves the contract (a
  persistence side effect, a short-circuit that should touch nothing — see
  `OrderServiceUnitTest.placeOrder_withNullItems_throwsIllegalArgumentException`'s
  `verifyNoInteractions`), not sprinkled on every call.
- `Clock` injection and Bean Validation are deliberately deferred to the "edge cases" step (§9,
  build-order step 8) — don't pull them in early just because they're easy; keep each step scoped
  to what its build-order entry actually asks for.
- Controller layer (`OrderController`): request/response are separate DTO records
  (`PlaceOrderRequest`, `OrderResponse`), never the JPA entity directly — keeps the JSON contract
  from silently changing when the persistence model does. Errors go through a single
  `@RestControllerAdvice` (`OrderExceptionHandler`) extending `ResponseEntityExceptionHandler` and
  returning RFC 9457 `ProblemDetail`, so both domain exceptions and framework-level failures
  (malformed JSON, path-variable type mismatch, missing query param, 415/406) share one error
  shape. Spring Boot 4 note: `@WebMvcTest` now lives in `spring-boot-starter-webmvc-test`
  (`org.springframework.boot.webmvc.test.autoconfigure`), and `RestTestClient` injection needs
  `spring-boot-resttestclient` + `@AutoConfigureRestTestClient` (auto-bound to MockMvc inside a
  `@WebMvcTest`). Pin the success-response JSON once with
  `expectBody().json(..., JsonCompareMode.STRICT)` (catches accidental extra/renamed fields);
  `jsonPath` for narrower assertions elsewhere. Build entity fixtures with a generated id via
  `ReflectionTestUtils.setField` rather than adding a test-only setter.
- Full integration tests (`OrderLifecycleIntegrationTest`): `@SpringBootTest(webEnvironment =
  RANDOM_PORT)` + the same Postgres Testcontainer pattern as the repository slice +
  `@AutoConfigureRestTestClient`, which now binds `RestTestClient` to the real running server
  (`bindToServer()`), not MockMvc. Kept to a couple of tests per §5 — pick scenarios that prove the
  seam *between* layers for real (e.g. a genuine unique-constraint violation flowing through to the
  `ProblemDetail` body), not branch coverage the mocked-out slices already own. Gotcha:
  `@SpringBootTest` doesn't wrap the test method in a transaction the way `@DataJpaTest` does, so a
  post-HTTP-call repository read that touches a lazy association needs the test class annotated
  `@Transactional` (Spring's, not jakarta's) to have an open session — safe here since it opens
  after the HTTP calls already committed on their own connections.
- Outbound REST (step 6, see strategy doc §7 "As built in this repo"): split the raw HTTP client
  (`ShippingClient`: request mapping + translating every failure into exactly two exceptions,
  rejected vs. unavailable) from the resilience wrapper (`ResilientShippingClient`). Apply
  Resilience4j **in code** (`Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(cb, call))`),
  not with `@Retry`/`@CircuitBreaker` annotations, so its unit test can use real Retry/CircuitBreaker
  instances with no Spring context. Never mock `RestClient`'s fluent chain; unit-test the pure
  mapping/translation functions instead. Slice-test the raw client with `@RestClientTest` +
  `@AutoConfigureMockRestServiceServer(enabled = false)` + a `WireMockExtension`
  (`@DynamicPropertySource` for the base URL), because `MockRestServiceServer` can't simulate
  timeouts or connection resets. Any `@SpringBootTest` that ships an order needs a WireMock carrier.
  Integration tests that record circuit-breaker failures reset the breaker in `@BeforeEach`,
  because the cached context keeps its state across test methods. Retry/timeout values are shrunk in
  `src/test/resources/application.properties`.
- Security (step 7, see strategy doc §8 "As built in this repo"): everything under `/api/**` needs a
  bearer JWT from now on. Any new `@WebMvcTest` must `@Import(SecurityConfig.class)` and
  authenticate (class-level `@WithMockUser(roles = "STAFF")` when security isn't the subject, or
  `jwt().authorities(...)` with `MockMvcTester` when it is). Any new
  `@SpringBootTest(RANDOM_PORT)` uses a `@MockitoBean JwtDecoder` plus a default
  `Authorization: Bearer` header via `client.mutate()`. Only `OrderSecurityKeycloakIntegrationTest`
  uses real Keycloak. A non-web test calling a secured service method directly uses
  `@WithMockUser`. URL/role rules go in `SecurityConfig`. Data-dependent rules go in `@PreAuthorize`
  on the service, never `@PostAuthorize` on a method that changes data. Don't annotate methods that
  Kafka listeners call (no logged-in user there).
- Windows gotcha when editing files with Python: always open with `encoding='utf-8'`. The default
  cp1252 corrupts non-ASCII characters such as `§` and `—`, which are common in this repo's comments.

## Keeping this file current

Update this file (not just `docs/testing-strategy.md`) whenever the user gives feedback about
*how to work* in this repo specifically — workflow corrections, a pattern they confirm they like,
a stack/tooling detail (like the no-`mvnw` note above) that would otherwise need rediscovering
next session. Propose the edit explicitly rather than changing it silently. If the lesson is
generic enough to apply outside this repo, also suggest it for
`~/.claude/agents/test-implementation-agent.md`.
