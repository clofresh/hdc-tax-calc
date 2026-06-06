# Backend Test Starter Pack — Design

**Date:** 2026-06-06
**Status:** Approved design, pending implementation plan
**Scope:** Add integration test infrastructure (separate test database, shared base class, JWT helper, test profile) and integration tests for `UserController` (auth flow) and `DealConduitController` (preset CRUD). Establish the pattern future backend tests follow.

## Motivation

Frontend has 1,854 passing tests; the backend has 9, of which only one (`HdcMapBackendApplicationTests.contextLoads`) loads the Spring context — and even that requires the compose Postgres to be up. There's zero integration coverage of the auth flow or the deal-conduit CRUD path, which are the two surfaces most likely to be touched in upcoming work (RBAC tightening, duplicate-username fix, preset library expansion).

Adding a proper test pattern now makes every subsequent backend change easier to verify and safer to ship. Two controllers' worth of tests is a starter pack — enough to prove the infrastructure works and to model the pattern, without ballooning into a months-long coverage project.

## Decisions (locked in during brainstorming)

1. **Tests run inside the backend container, same workflow as today.** `docker compose exec backend ./mvnw test`. No host Java/Maven dependency.
2. **DB isolation via a separate `hdc_test` database** on the same Postgres instance. Full isolation from dev data without Docker-in-Docker complexity. One-line init script change.
3. **`ddl-auto=create-drop` per test run**, not transactional rollback. Schema is rebuilt fresh on each `./mvnw test`. Tests can commit freely. No `@Transactional` rollback gymnastics.
4. **MockMvc, not TestRestTemplate.** Faster, sufficient for asserting controller + Spring Security filter behavior. Doesn't exercise the real network stack, which we don't need to verify.
5. **Shared `BaseIntegrationTest` parent class.** Centralizes `@SpringBootTest` / `@AutoConfigureMockMvc` / `@ActiveProfiles("test")`. Children inherit the wiring.
6. **`JwtTestHelper` for auth setup.** Mints valid JWTs using the production `JwtUtil` bean. Tests can authenticate as any user without going through `/login` round-trips.
7. **Existing tests mostly untouched.** `HdcMapBackendApplicationTests.contextLoads()` is refactored to extend `BaseIntegrationTest` so it uses the test profile and `hdc_test` (one-line change). The two pure-unit tests (`BedrockCredentialResolverTest`, `S3EndpointOverrideTest`) are not touched — they don't load a Spring context.
8. **No new dependencies.** `spring-boot-starter-test` already pulls in everything we need: JUnit 5, Mockito, AssertJ, MockMvc, Spring Security Test.

## Architecture

### Database layout

Postgres init script (`docker/postgres/init/`) gains a second file:

```
01-extensions-and-schemas.sql   (existing — sets up `hdc`)
02-create-test-db.sql           (new — sets up `hdc_test`)
```

The new script creates `hdc_test` owned by `hdc`, then connects to it (`\c hdc_test`) and creates the same extensions (`postgis`, `vector`) and schemas (`user_schema`, `tax_benefits`) as the dev DB. Both DBs are first-boot-only; users with an existing dev DB volume must `docker compose down -v && docker compose up -d` once to pick up the test DB.

### Test profile

`backend/src/test/resources/application-test.properties`:

- `spring.datasource.url=jdbc:postgresql://postgres:5432/hdc_test?currentSchema=user_schema`
- `spring.datasource.username=hdc`
- `spring.datasource.password=hdc_local` (matches `.env` default)
- `spring.jpa.hibernate.ddl-auto=create-drop` (schema rebuilt each test run; auto-dropped on context shutdown)
- `spring.jpa.properties.hibernate.default_schema=user_schema`
- `jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWZvci10ZXN0cy1tdXN0LWJlLTMyLWNoYXJzLW9yLW1vcmU=` (fixed base64 secret for test reproducibility — never used in real deployments)
- `aws.s3.endpoint=` (empty — tests won't talk to LocalStack)
- `email.from.address=test@hdc.local`
- `email.from.name=HDC Test`
- `app.frontend.url=http://localhost:5173`
- `google.oauth.client-id=` (empty — `/google-auth` tests will mock the verifier)

`@ActiveProfiles("test")` on `BaseIntegrationTest` activates these properties. Spring's profile system replaces the active list entirely, so the dev-profile `PresetSeeder` does not fire during tests.

### Test support layer

`backend/src/test/java/com/hdc/hdc_map_backend/support/`:

**`BaseIntegrationTest.java`** — abstract class:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {
    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtUtil jwtUtil;
    // shared assertion / setup helpers can land here as tests reveal repetition
}
```

**`JwtTestHelper.java`** — plain static utility class (no Spring registration):

```java
public final class JwtTestHelper {
    private JwtTestHelper() {}

    /** Mints a JWT for the given user using the production JwtUtil. */
    public static String tokenFor(JwtUtil jwtUtil, User user) {
        UserDetails ud = new org.springframework.security.core.userdetails.User(
            user.getUsername(), user.getPassword(), Collections.emptyList());
        return jwtUtil.generateToken(ud);
    }

    /** Convenience: builds the `Authorization: Bearer ...` header value. */
    public static String bearer(JwtUtil jwtUtil, User user) {
        return "Bearer " + tokenFor(jwtUtil, user);
    }
}
```

The helper lives in `src/test/java/com/hdc/hdc_map_backend/support/`. Pure utility — no `@Component`, no `@TestConfiguration`, no scan-path assumptions. Tests autowire `JwtUtil` (already a `@Component` in production code) via the inherited `protected JwtUtil jwtUtil` field and call `JwtTestHelper.tokenFor(jwtUtil, user)`.

### Integration test classes

`backend/src/test/java/com/hdc/hdc_map_backend/controller/UserControllerIntegrationTest.java` — ~10 tests:

| # | Endpoint | Scenario | Assertion |
|---|---|---|---|
| 1 | POST `/api/public/register` | new username + valid body | 200; row persisted; password is a BCrypt hash, not plaintext |
| 2 | POST `/api/public/register` | existing username (re-register) | Documents current buggy behavior: 200 with a duplicate row. Test asserts the duplicate is created. A `// TODO` comment flags this is a known bug awaiting the duplicate-username-fix thread. |
| 3 | POST `/api/public/login` | valid creds | 200; response body has `jwt`; JWT subject equals username |
| 4 | POST `/api/public/login` | wrong password | non-2xx (assert current behavior — likely 500 from rethrown Exception) |
| 5 | POST `/api/public/login` | unknown username | same shape as #4 |
| 6 | POST `/api/public/forgot-password` | known email | 200; password-reset token row created in `password_reset_token` table; `EmailService.sendPasswordResetEmail` invoked (mocked via `@MockitoBean`) |
| 7 | POST `/api/public/forgot-password` | unknown email | 200; no token row created; mocked email service never invoked (security property: no info leak) |
| 8 | POST `/api/public/reset-password` | valid token | 200; user's password column updated to a new BCrypt hash; token marked used |
| 9 | POST `/api/public/reset-password` | invalid/expired token | 400 |
| 10 | POST `/api/public/google-auth` | mocked `GoogleOAuthService` returns existing-user payload | 200; JWT issued for that email; no new User row created |

`GoogleOAuthService` is mocked via `@MockitoBean` (Spring Boot 3.4+ replacement for `@MockBean`) to avoid real Google verification.

`backend/src/test/java/com/hdc/hdc_map_backend/controller/taxBenefits/DealConduitControllerIntegrationTest.java` — ~8 tests:

| # | Endpoint | Scenario | Assertion |
|---|---|---|---|
| 1 | GET `/api/deal-conduits/presets` | no Authorization header | 403 |
| 2 | POST `/api/deal-conduits/presets` | full preset payload | 201; response has `id`; `isPreset=true`; nested fields (projectCost, dealName, etc.) round-trip via `@JsonUnwrapped` |
| 3 | PUT `/api/deal-conduits/presets/{id}` | partial update | 200; updated field changed in DB; `isPreset` still true |
| 4 | DELETE `/api/deal-conduits/presets/{id}` | existing preset | 204; row removed from DB |
| 5 | GET `/api/deal-conduits/configurations` | with JWT for user A; user B has configs too | only A's configs returned; B's are excluded |
| 6 | POST `/api/deal-conduits/configurations` | with JWT for user A | 201; `isPreset=false`; `portalSettings.userId` equals A's id |
| 7 | GET `/api/deal-conduits/configurations/default` | user has no default | 404 |
| 8 | PUT `/api/deal-conduits/configurations/{id}/set-default` | user has a previous default | 200; previous default's `isDefault` becomes false; new one's `isDefault` becomes true |

Each test creates the User entities it needs directly via `UserRepo` (faster than going through `/register`), gets a JWT via `JwtTestHelper`, and uses MockMvc to hit the endpoint. Per-test unique usernames (e.g., UUID-prefixed) avoid collisions across the test run.

## Data flow

```
JUnit invokes test
   │
   ▼
BaseIntegrationTest (@SpringBootTest, @ActiveProfiles("test"))
   │
   ▼
Spring loads application-test.properties → datasource = hdc_test
   │
   ▼
Hibernate ddl-auto=create-drop creates fresh schemas
   │
   ▼
Test method:
   - sets up User entity(s) via UserRepo
   - mints JWT via JwtTestHelper
   - hits endpoint via MockMvc with Authorization header
   - asserts on response + DB state
   │
   ▼
Test ends; data persists in hdc_test until next test run drops it
```

`ddl-auto=create-drop` rebuilds schemas at Spring context startup and drops them at context shutdown. All tests within one `./mvnw test` invocation share a single context (Spring's default behavior) — so they share a schema but use unique usernames to avoid stepping on each other.

## Error handling

- **First-run after upgrade: `hdc_test` doesn't exist.** Hibernate fails with "database does not exist" at test startup. Fix: `docker compose down -v && docker compose up -d` to re-run init scripts. Document this in CLAUDE.md.
- **Concurrent test runs.** Two `./mvnw test` invocations against the same `hdc_test` would race on schema create/drop. Don't do that. Not worth designing around.
- **Test pollution between runs.** Theoretically impossible because `ddl-auto=create-drop` drops everything on shutdown. If for some reason the test process crashes mid-run, leftover data sits in `hdc_test` until the next run drops + recreates. Acceptable.
- **Datasource accidentally pointed at `hdc`.** Belt-and-suspenders: `BaseIntegrationTest` could include a `@BeforeAll` assertion that the JDBC URL contains `hdc_test`. Decision: include it. Cheap insurance against a future `application-test.properties` typo wiping dev data.

## Testing the test infrastructure

The infrastructure is verified by the tests that use it. There's no meta-test layer. Acceptance criteria for this work:

1. Fresh `docker compose down -v && docker compose up -d` → `docker compose exec backend ./mvnw test` runs all tests (existing 9 + ~18 new) to green.
2. After tests finish, `docker compose exec postgres psql -U hdc -d hdc_test -c '\dt user_schema.*'` shows zero rows (Hibernate dropped the schema on shutdown).
3. After tests finish, `docker compose exec postgres psql -U hdc -d hdc -c 'SELECT COUNT(*) FROM user_schema.user'` shows the dev DB's user count unchanged (tests didn't touch dev data).
4. Running tests twice in a row produces the same results (no test-order dependency from leaked state).
5. Running `docker compose exec backend ./mvnw test -Dtest=UserControllerIntegrationTest` runs just that class and passes — proves the test isolation works at class level too.

## What's intentionally out of scope

- **Fixing the duplicate-username register bug.** Test #2 above documents current buggy behavior with a TODO. The fix is its own thread.
- **All-controllers coverage.** Other controllers (TaxBenefits, InvestmentPool, Account, Chat, etc.) get tests in a separate change once the pattern is proven.
- **Service-layer unit tests.** PasswordResetService token lifecycle, UserService loadByUsername, etc. — future scope.
- **Testcontainers / Docker-in-Docker.** Rejected during brainstorming as too much complexity for the in-container test runtime.
- **CI pipeline.** Tests still run manually via `docker compose exec`. GitHub Actions integration is a separate thread.
- **Host-side test running.** Deferred until someone explicitly wants to run `./mvnw test` outside the container.
- **PresetSeeder tests.** The seeder is `@Profile("dev")` and inactive during tests; its behavior under the dev profile is verified by the existing seeder integration (the preset shows up after a fresh `docker compose up`). A dedicated test would mostly test Spring's profile activation, not our code.

## Rejected alternatives

- **Same `hdc` DB + `@Transactional` rollback per test.** Cleaner in zero-infra terms but mixes dev and test data; a hard test failure can leave residue; multi-transaction code (async, scheduled) wouldn't roll back. The one-line init script change buys real isolation cheaply.
- **Testcontainers + Docker-in-Docker.** Most CI-friendly long-term but requires mounting `/var/run/docker.sock` into the backend container, installing the Docker CLI in the backend image, and explaining the failure mode to anyone debugging. Not worth it given tests stay in-container.
- **H2 in-memory + PostgreSQL mode.** Doesn't support our schemas reliably; would mask dialect-specific bugs; Hibernate quirks would surface differently than in real Postgres.
- **`@DataJpaTest` + slice tests.** Would isolate to repository layer only; we want full controller + filter chain + JSON serialization coverage, so a slice test wouldn't catch enough.
- **TestRestTemplate / WebTestClient.** Real network round-trip; useful when filter ordering or content negotiation differs from MockMvc. For this scope, MockMvc is sufficient and faster.
