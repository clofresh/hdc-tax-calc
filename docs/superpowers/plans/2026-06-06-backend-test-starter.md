# Backend Test Starter Pack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add integration test infrastructure (separate `hdc_test` Postgres DB, test profile, `BaseIntegrationTest`, `JwtTestHelper`) and integration tests for `UserController` (auth flow, 10 tests) and `DealConduitController` (preset/config CRUD, 8 tests). Tests run via `docker compose exec backend ./mvnw test`.

**Architecture:** Tests stay in-container. DB isolation via separate `hdc_test` database on the same Postgres instance, created by a new init script. Test profile (`application-test.properties`) points Spring at `hdc_test` with `ddl-auto=create-drop`. Shared `BaseIntegrationTest` parent class wires `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`. MockMvc for HTTP assertions. No new dependencies — `spring-boot-starter-test` covers everything.

**Tech Stack:** JUnit 5, Mockito, AssertJ, MockMvc, Spring Boot Test (all pulled in by `spring-boot-starter-test` already on the pom).

**Spec:** `docs/superpowers/specs/2026-06-06-backend-test-starter-design.md`

**Verification model:** Each task ends with `docker compose exec backend ./mvnw test` returning green. The full acceptance run-through is Task 8.

**Local note (this machine only):** Default Docker context is `stillmatic` (remote SSH, offline). Either `docker context use desktop-linux` once or prefix every command with `docker --context desktop-linux compose ...`. The plan uses plain `docker compose` for brevity.

**Pre-flight:** This plan starts with `docker compose down -v` to wipe the dev DB volume and force the Postgres init scripts to re-run (which creates the new `hdc_test` DB). Any local dev data not in source control is destroyed by this step. If you have local presets or test users you want to keep, dump them first.

---

### Task 1: Add `hdc_test` database via Postgres init script

The Postgres image runs `/docker-entrypoint-initdb.d/*.sql` once on first boot (when the data volume is empty). Add a second script that creates `hdc_test` with the same extensions and schemas as `hdc`.

**Files:**
- Create: `docker/postgres/init/02-create-test-db.sql`

- [ ] **Step 1: Create `docker/postgres/init/02-create-test-db.sql`**

```sql
-- docker/postgres/init/02-create-test-db.sql
-- Creates a parallel test database alongside `hdc`. Backend integration
-- tests (application-test.properties) connect here, isolated from dev data.
CREATE DATABASE hdc_test OWNER hdc;

\c hdc_test

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS tax_benefits;

GRANT ALL ON SCHEMA user_schema, tax_benefits TO hdc;
```

- [ ] **Step 2: Wipe the Postgres volume and re-init**

The existing dev volume `hdc-tax-calc_postgres-data` (or whatever your worktree's project prefixes) skips init scripts because the data dir isn't empty. Force re-init:

```
docker compose down -v
docker compose up -d
```

Expected: all containers come back up. Postgres logs (during `up -d` build/startup) should show both init scripts running.

Verify the init logs:
```
docker compose logs postgres 2>&1 | grep -i "running\|database system is ready"
```

Expected: lines like `running /docker-entrypoint-initdb.d/01-extensions-and-schemas.sql` and `running /docker-entrypoint-initdb.d/02-create-test-db.sql`, then `database system is ready to accept connections`.

- [ ] **Step 3: Verify both databases exist with correct schemas**

```
docker compose exec -T postgres psql -U hdc -d hdc -c "\dn" -tA
```

Expected output includes both `user_schema|hdc` and `tax_benefits|hdc` (the `|hdc` is the owner).

```
docker compose exec -T postgres psql -U hdc -d hdc_test -c "\dn" -tA
```

Expected: same `user_schema|hdc` and `tax_benefits|hdc` rows. If `hdc_test` doesn't exist, this command fails with `database "hdc_test" does not exist` — re-do Step 2.

- [ ] **Step 4: Commit**

```
git add docker/postgres/init/02-create-test-db.sql
git commit -m "feat(test): add hdc_test database for backend integration tests"
```

---

### Task 2: Test profile + base infrastructure + refactor existing context test

Creates `application-test.properties`, the `JwtTestHelper` static utility, and the `BaseIntegrationTest` parent class. Refactors the existing `HdcMapBackendApplicationTests.contextLoads` to use the new base — proves the wiring works end-to-end before any new tests are written.

**Files:**
- Create: `backend/src/test/resources/application-test.properties`
- Create: `backend/src/test/java/com/hdc/hdc_map_backend/support/JwtTestHelper.java`
- Create: `backend/src/test/java/com/hdc/hdc_map_backend/support/BaseIntegrationTest.java`
- Modify: `backend/src/test/java/com/hdc/hdc_map_backend/HdcMapBackendApplicationTests.java`

- [ ] **Step 1: Create `backend/src/test/resources/application-test.properties`**

```properties
# backend/src/test/resources/application-test.properties
# Loaded when @ActiveProfiles("test") — see BaseIntegrationTest.
spring.datasource.url=jdbc:postgresql://postgres:5432/hdc_test?currentSchema=user_schema
spring.datasource.username=hdc
spring.datasource.password=hdc_local
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.properties.hibernate.default_schema=user_schema

# Fixed base64 secret — never used outside tests. Decoded value is at
# least 32 bytes so JwtUtil's HMAC-SHA256 key generation succeeds.
jwt.secret=dGVzdC1qd3Qtc2VjcmV0LWZvci10ZXN0cy1tdXN0LWJlLTMyLWNoYXJzLW9yLW1vcmU=

# Tests don't talk to LocalStack — disable S3 endpoint override.
aws.s3.endpoint=
aws.s3.bucket-name=test-bucket
aws.region=us-east-2
aws.access.key.id=test
aws.secret.access.key=test

# Bedrock disabled in tests
bedrock.region=us-east-2

# Email — EmailService is mocked in tests that exercise it; these values
# satisfy @Value placeholder resolution at context boot.
spring.mail.host=mailhog
spring.mail.port=1025
email.from.address=test@hdc.local
email.from.name=HDC Test
app.frontend.url=http://localhost:5173

# Google OAuth — empty; tests mock GoogleOAuthService directly.
google.oauth.client-id=
```

- [ ] **Step 2: Create `backend/src/test/java/com/hdc/hdc_map_backend/support/JwtTestHelper.java`**

```java
package com.hdc.hdc_map_backend.support;

import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.util.JwtUtil;
import java.util.Collections;
import org.springframework.security.core.userdetails.UserDetails;

/** Static helper: mints JWTs for integration tests using the production JwtUtil. */
public final class JwtTestHelper {
    private JwtTestHelper() {}

    public static String tokenFor(JwtUtil jwtUtil, User user) {
        UserDetails ud = new org.springframework.security.core.userdetails.User(
                user.getUsername(), user.getPassword(), Collections.emptyList());
        return jwtUtil.generateToken(ud);
    }

    public static String bearer(JwtUtil jwtUtil, User user) {
        return "Bearer " + tokenFor(jwtUtil, user);
    }
}
```

- [ ] **Step 3: Create `backend/src/test/java/com/hdc/hdc_map_backend/support/BaseIntegrationTest.java`**

```java
package com.hdc.hdc_map_backend.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.repository.user.UserRepo;
import com.hdc.hdc_map_backend.util.JwtUtil;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIntegrationTest {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtUtil jwtUtil;
    @Autowired protected UserRepo userRepo;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected DataSource dataSource;

    /** Safety: refuse to run tests against any DB that isn't hdc_test. */
    @BeforeEach
    void assertTestDatabase() throws SQLException {
        try (var c = dataSource.getConnection()) {
            String url = c.getMetaData().getURL();
            if (!url.contains("hdc_test")) {
                throw new IllegalStateException(
                        "Integration tests must run against hdc_test. Got: " + url);
            }
        }
    }

    /** Convenience: persist a User with a BCrypt-hashed password "testpass". */
    protected User createUser(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode("testpass"));
        u.setRole("USER");
        u.setFullName("Test " + username);
        return userRepo.save(u);
    }

    protected String bearerFor(User user) {
        return JwtTestHelper.bearer(jwtUtil, user);
    }
}
```

- [ ] **Step 4: Refactor `HdcMapBackendApplicationTests` to extend BaseIntegrationTest**

Open `backend/src/test/java/com/hdc/hdc_map_backend/HdcMapBackendApplicationTests.java` and replace its contents with:

```java
package com.hdc.hdc_map_backend;

import com.hdc.hdc_map_backend.support.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

class HdcMapBackendApplicationTests extends BaseIntegrationTest {

    @Test
    void contextLoads() {
        // Inherits @SpringBootTest + @ActiveProfiles("test") from BaseIntegrationTest.
    }
}
```

- [ ] **Step 5: Run the test suite to verify infrastructure works**

```
docker compose exec -T backend ./mvnw test
```

Expected: `BUILD SUCCESS`. Existing 9 tests pass (1 contextLoads via new base + 5 BedrockCredentialResolverTest + 3 S3EndpointOverrideTest). Test runtime should be similar to before — maybe slightly slower on the first run because Hibernate creates the schema in `hdc_test`.

If `contextLoads` fails with "database hdc_test does not exist", redo Task 1 step 2 (`down -v && up -d`).

- [ ] **Step 6: Commit**

```
git add backend/src/test/resources/application-test.properties \
        backend/src/test/java/com/hdc/hdc_map_backend/support/ \
        backend/src/test/java/com/hdc/hdc_map_backend/HdcMapBackendApplicationTests.java
git commit -m "feat(test): add test profile, BaseIntegrationTest, JwtTestHelper"
```

---

### Task 3: UserControllerIntegrationTest — register + login (5 tests)

Five tests covering the register and login endpoints. Documents the known duplicate-username bug rather than fixing it.

**Files:**
- Create: `backend/src/test/java/com/hdc/hdc_map_backend/controller/UserControllerIntegrationTest.java`

- [ ] **Step 1: Create the test class skeleton with the register happy-path test**

```java
package com.hdc.hdc_map_backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.support.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class UserControllerIntegrationTest extends BaseIntegrationTest {

    private String uniqueUsername() {
        return "u-" + UUID.randomUUID() + "@hdc.local";
    }

    @Test
    void register_happyPath_persistsUserWithBcryptPassword() throws Exception {
        String username = uniqueUsername();
        String body = """
                {"username":"%s","password":"testpass","fullName":"Test User"}
                """.formatted(username);

        mockMvc.perform(post("/api/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.role").value("USER"));

        User saved = userRepo.findByUsername(username).orElseThrow();
        assertThat(saved.getPassword()).startsWith("$2a$");
        assertThat(saved.getPassword()).isNotEqualTo("testpass");
        assertThat(saved.getFullName()).isEqualTo("Test User");
    }
}
```

- [ ] **Step 2: Run this single test to verify the skeleton compiles and runs**

```
docker compose exec -T backend ./mvnw test -Dtest=UserControllerIntegrationTest
```

Expected: 1 test passes. If it fails on missing imports or compile errors, fix and retry.

- [ ] **Step 3: Add the duplicate-username test (documents known buggy behavior)**

Append to the class (above the closing brace):

```java
    @Test
    void register_existingUsername_currentlyCreatesDuplicateRow_KNOWN_BUG() throws Exception {
        // TODO: when the duplicate-username-fix thread lands, this test should
        // be updated to assert 409 Conflict and a single row.
        String username = uniqueUsername();
        String body = """
                {"username":"%s","password":"testpass","fullName":"Test"}
                """.formatted(username);

        mockMvc.perform(post("/api/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Second registration with same username — currently succeeds, creating a dup.
        mockMvc.perform(post("/api/public/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        long count = userRepo.findAll().stream()
                .filter(u -> username.equals(u.getUsername()))
                .count();
        assertThat(count).as("duplicate-username bug: expect 2 rows until fixed").isEqualTo(2);
    }
```

- [ ] **Step 4: Add the three login tests**

Append:

```java
    @Test
    void login_validCredentials_returnsJwt() throws Exception {
        String username = uniqueUsername();
        createUser(username); // password is "testpass" (set by createUser helper)

        String body = """
                {"username":"%s","password":"testpass"}
                """.formatted(username);

        String response = mockMvc.perform(post("/api/public/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String jwt = objectMapper.readTree(response).get("jwt").asText();
        assertThat(jwtUtil.extractUsername(jwt)).isEqualTo(username);
    }

    @Test
    void login_wrongPassword_returns5xx() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        String body = """
                {"username":"%s","password":"wrongpass"}
                """.formatted(username);

        // UserController.createAuthenticationToken throws Exception on bad creds,
        // which Spring surfaces as 5xx (no @ControllerAdvice to map it cleanly).
        mockMvc.perform(post("/api/public/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void login_unknownUsername_returns5xx() throws Exception {
        String body = """
                {"username":"%s","password":"testpass"}
                """.formatted(uniqueUsername()); // never persisted

        mockMvc.perform(post("/api/public/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is5xxServerError());
    }
```

- [ ] **Step 5: Run all 5 tests and verify they pass**

```
docker compose exec -T backend ./mvnw test -Dtest=UserControllerIntegrationTest
```

Expected: 5 tests pass. If `login_wrongPassword` or `login_unknownUsername` returns a different status code than 5xx (e.g., the controller was updated to use a `@ControllerAdvice`), adjust the assertion to match actual current behavior — these tests document what is, not what should be.

- [ ] **Step 6: Commit**

```
git add backend/src/test/java/com/hdc/hdc_map_backend/controller/UserControllerIntegrationTest.java
git commit -m "test: UserController register and login integration tests"
```

---

### Task 4: UserControllerIntegrationTest — forgot/reset/google (5 tests)

Adds the password-reset flow (forgot + reset) and the Google OAuth happy path.

**Files:**
- Modify: `backend/src/test/java/com/hdc/hdc_map_backend/controller/UserControllerIntegrationTest.java`

- [ ] **Step 1: Add the `@MockitoBean` import and field declarations**

At the top of the class (after `import org.springframework.http.MediaType;`), add these imports:

```java
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.hdc.hdc_map_backend.entity.PasswordResetToken;
import com.hdc.hdc_map_backend.repository.user.PasswordResetTokenRepository;
import com.hdc.hdc_map_backend.service.EmailService;
import com.hdc.hdc_map_backend.service.GoogleOAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
```

Inside the class body, just after the `uniqueUsername()` helper, add:

```java
    @MockitoBean private EmailService emailService;
    @MockitoBean private GoogleOAuthService googleOAuthService;
    @Autowired private PasswordResetTokenRepository tokenRepo;
```

- [ ] **Step 2: Add the two forgot-password tests**

Append to the class:

```java
    @Test
    void forgotPassword_knownEmail_createsTokenAndCallsEmailService() throws Exception {
        String username = uniqueUsername();
        createUser(username);

        String body = """
                {"email":"%s"}
                """.formatted(username);

        mockMvc.perform(post("/api/public/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        assertThat(tokenRepo.findByEmail(username)).isPresent();
        verify(emailService).sendPasswordResetEmail(eqIgnoreCase(username), anyString());
    }

    @Test
    void forgotPassword_unknownEmail_returnsOkButDoesNothing() throws Exception {
        String unknown = uniqueUsername(); // never persisted
        String body = """
                {"email":"%s"}
                """.formatted(unknown);

        mockMvc.perform(post("/api/public/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Security property: no token row, no email sent, no info leak.
        assertThat(tokenRepo.findByEmail(unknown)).isEmpty();
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    // Helper because Mockito's eq() is strict about casing; users of usernames
    // are case-sensitive in this codebase, so a plain eq() works — alias for clarity.
    private static String eqIgnoreCase(String s) {
        return org.mockito.ArgumentMatchers.eq(s);
    }
```

- [ ] **Step 3: Add the two reset-password tests**

Append:

```java
    @Test
    void resetPassword_validToken_updatesPasswordAndMarksUsed() throws Exception {
        String username = uniqueUsername();
        User user = createUser(username);
        String originalHash = user.getPassword();

        // Create a token directly via the repo (bypassing forgot-password)
        // so this test only exercises reset-password.
        String token = java.util.UUID.randomUUID().toString();
        PasswordResetToken prt = new PasswordResetToken(token, username);
        tokenRepo.save(prt);

        String body = """
                {"token":"%s","newPassword":"newsecret"}
                """.formatted(token);

        mockMvc.perform(post("/api/public/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        User updated = userRepo.findByUsername(username).orElseThrow();
        assertThat(updated.getPassword()).isNotEqualTo(originalHash);
        assertThat(passwordEncoder.matches("newsecret", updated.getPassword())).isTrue();
    }

    @Test
    void resetPassword_invalidToken_returns400() throws Exception {
        String body = """
                {"token":"not-a-real-token","newPassword":"newsecret"}
                """;

        mockMvc.perform(post("/api/public/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 4: Add the Google OAuth happy-path test**

Append:

```java
    @Test
    void googleAuth_existingUser_returnsJwtWithoutCreatingNewRow() throws Exception {
        String email = uniqueUsername();
        createUser(email); // pre-existing user

        GoogleIdToken.Payload payload = new GoogleIdToken.Payload();
        payload.setEmail(email);
        payload.set("name", "Google Name");
        when(googleOAuthService.verifyToken("fake-google-token")).thenReturn(payload);

        long countBefore = userRepo.count();

        String body = """
                {"token":"fake-google-token"}
                """;

        mockMvc.perform(post("/api/public/google-auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jwt").isNotEmpty())
                .andExpect(jsonPath("$.email").value(email));

        // No new row for an existing user.
        assertThat(userRepo.count()).isEqualTo(countBefore);
    }
```

- [ ] **Step 5: Run the full UserControllerIntegrationTest and verify all 10 tests pass**

```
docker compose exec -T backend ./mvnw test -Dtest=UserControllerIntegrationTest
```

Expected: 10 tests pass. If `reset-password invalid token` returns 200 instead of 400, the implementation may handle it differently — read the actual response and adjust the assertion to document current behavior.

- [ ] **Step 6: Commit**

```
git add backend/src/test/java/com/hdc/hdc_map_backend/controller/UserControllerIntegrationTest.java
git commit -m "test: UserController forgot-password, reset-password, google-auth tests"
```

---

### Task 5: DealConduitControllerIntegrationTest — preset CRUD (4 tests)

Auth gate + create/update/delete on the presets endpoints. Uses the existing seed fixture as the test payload to ensure the test asserts against a realistic shape.

**Files:**
- Create: `backend/src/test/java/com/hdc/hdc_map_backend/controller/taxBenefits/DealConduitControllerIntegrationTest.java`

- [ ] **Step 1: Create the test class skeleton with the auth-gate and create-preset tests**

```java
package com.hdc.hdc_map_backend.controller.taxBenefits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.hdc.hdc_map_backend.entity.User;
import com.hdc.hdc_map_backend.repository.taxBenefits.DealConduitRepository;
import com.hdc.hdc_map_backend.support.BaseIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class DealConduitControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired private DealConduitRepository conduitRepo;

    private String uniqueUsername() {
        return "u-" + UUID.randomUUID() + "@hdc.local";
    }

    /** Minimal preset payload — enough fields to exercise @JsonUnwrapped round-trip. */
    private String presetPayload(String dealName) {
        return """
                {
                  "projectCost": 50.0,
                  "selectedState": "Washington",
                  "yearOneNOI": 2.0,
                  "investorEquityPct": 25.0,
                  "seniorDebtPct": 20.0,
                  "lihtcEnabled": true,
                  "creditRate": 0.04,
                  "ozEnabled": false,
                  "dealName": "%s",
                  "configurationName": "%s",
                  "isActive": true
                }
                """.formatted(dealName, dealName);
    }

    @Test
    void getPresets_withoutAuth_returns403() throws Exception {
        mockMvc.perform(get("/api/deal-conduits/presets"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createPreset_withAuth_returns201AndRoundTripsFields() throws Exception {
        User user = createUser(uniqueUsername());
        String dealName = "Preset " + UUID.randomUUID();

        mockMvc.perform(post("/api/deal-conduits/presets")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetPayload(dealName)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.isPreset").value(true))
                .andExpect(jsonPath("$.dealName").value(dealName))
                .andExpect(jsonPath("$.projectCost").value(50.0))
                .andExpect(jsonPath("$.investorEquityPct").value(25.0))
                .andExpect(jsonPath("$.lihtcEnabled").value(true))
                .andExpect(jsonPath("$.creditRate").value(0.04));
    }
}
```

- [ ] **Step 2: Run the two tests to verify they pass**

```
docker compose exec -T backend ./mvnw test -Dtest=DealConduitControllerIntegrationTest
```

Expected: 2 tests pass.

- [ ] **Step 3: Add the update-preset and delete-preset tests**

Append to the class:

```java
    @Test
    void updatePreset_changesFieldsAndKeepsIsPresetTrue() throws Exception {
        User user = createUser(uniqueUsername());
        String original = "Original " + UUID.randomUUID();

        // Create
        String createResp = mockMvc.perform(post("/api/deal-conduits/presets")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetPayload(original)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(createResp).get("id").asLong();

        // Update with a different dealName and projectCost
        String updated = "Updated " + UUID.randomUUID();
        String updateBody = """
                {
                  "projectCost": 75.0,
                  "selectedState": "Washington",
                  "yearOneNOI": 2.0,
                  "investorEquityPct": 25.0,
                  "seniorDebtPct": 20.0,
                  "lihtcEnabled": true,
                  "creditRate": 0.04,
                  "ozEnabled": false,
                  "dealName": "%s",
                  "configurationName": "%s",
                  "isActive": true
                }
                """.formatted(updated, updated);

        mockMvc.perform(put("/api/deal-conduits/presets/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.isPreset").value(true))
                .andExpect(jsonPath("$.dealName").value(updated))
                .andExpect(jsonPath("$.projectCost").value(75.0));
    }

    @Test
    void deletePreset_removesRow() throws Exception {
        User user = createUser(uniqueUsername());
        String dealName = "ToDelete " + UUID.randomUUID();

        String createResp = mockMvc.perform(post("/api/deal-conduits/presets")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetPayload(dealName)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id = objectMapper.readTree(createResp).get("id").asLong();

        mockMvc.perform(delete("/api/deal-conduits/presets/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isNoContent());

        assertThat(conduitRepo.findById(id)).isEmpty();
    }
```

- [ ] **Step 4: Run all 4 tests and verify they pass**

```
docker compose exec -T backend ./mvnw test -Dtest=DealConduitControllerIntegrationTest
```

Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```
git add backend/src/test/java/com/hdc/hdc_map_backend/controller/taxBenefits/DealConduitControllerIntegrationTest.java
git commit -m "test: DealConduitController preset CRUD integration tests"
```

---

### Task 6: DealConduitControllerIntegrationTest — configurations (4 tests)

User-scoped configuration CRUD: list (user A only sees A's configs), create (assigns owner), default lookup (404 when none), set-default (unsets previous default).

**Files:**
- Modify: `backend/src/test/java/com/hdc/hdc_map_backend/controller/taxBenefits/DealConduitControllerIntegrationTest.java`

- [ ] **Step 1: Add a helper for building configuration payloads + the list test**

Append a helper inside the class (above the test methods, after `presetPayload`):

```java
    /** Same shape as presetPayload, but for /configurations endpoints (no isPreset hint needed). */
    private String configPayload(String configName) {
        return """
                {
                  "projectCost": 30.0,
                  "selectedState": "Washington",
                  "yearOneNOI": 1.5,
                  "investorEquityPct": 20.0,
                  "seniorDebtPct": 30.0,
                  "lihtcEnabled": false,
                  "creditRate": 0.04,
                  "ozEnabled": false,
                  "configurationName": "%s",
                  "dealName": "%s"
                }
                """.formatted(configName, configName);
    }
```

Append the list-scope test:

```java
    @Test
    void listConfigurations_onlyReturnsCurrentUsersConfigs() throws Exception {
        User userA = createUser(uniqueUsername());
        User userB = createUser(uniqueUsername());

        // A creates 2 configs
        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("A-1")))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("A-2")))
                .andExpect(status().isCreated());

        // B creates 1 config
        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("B-1")))
                .andExpect(status().isCreated());

        // A's listing should be exactly A's two configs.
        String response = mockMvc.perform(get("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(userA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Parse and assert all returned names are A's
        com.fasterxml.jackson.databind.JsonNode arr = objectMapper.readTree(response);
        assertThat(arr.isArray()).isTrue();
        for (com.fasterxml.jackson.databind.JsonNode node : arr) {
            String name = node.get("configurationName").asText();
            assertThat(name).startsWith("A-");
        }
        assertThat(arr.size()).isEqualTo(2);
    }
```

- [ ] **Step 2: Add the create-configuration assignment test**

Append:

```java
    @Test
    void createConfiguration_assignsOwnerUserIdAndIsPresetFalse() throws Exception {
        User user = createUser(uniqueUsername());

        mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("MyConfig")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.isPreset").value(false))
                .andExpect(jsonPath("$.userId").value(user.getId()));
    }
```

Note: this asserts the response includes `userId` at the top level — that comes from `@JsonUnwrapped` on `InputInvPortalSettings.userId`. If the JSON field name turns out to differ, adjust the JsonPath after seeing the actual response.

- [ ] **Step 3: Add the default-config tests**

Append:

```java
    @Test
    void getDefaultConfiguration_noneSet_returns404() throws Exception {
        User user = createUser(uniqueUsername());

        mockMvc.perform(get("/api/deal-conduits/configurations/default")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isNotFound());
    }

    @Test
    void setAsDefault_unsetsPreviousDefaultForSameUser() throws Exception {
        User user = createUser(uniqueUsername());

        // Create two configs
        String resp1 = mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("First")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id1 = objectMapper.readTree(resp1).get("id").asLong();

        String resp2 = mockMvc.perform(post("/api/deal-conduits/configurations")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(configPayload("Second")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        Long id2 = objectMapper.readTree(resp2).get("id").asLong();

        // Set first as default
        mockMvc.perform(put("/api/deal-conduits/configurations/" + id1 + "/set-default")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isOk());

        // Set second as default — expect first to lose its default flag
        mockMvc.perform(put("/api/deal-conduits/configurations/" + id2 + "/set-default")
                        .header(HttpHeaders.AUTHORIZATION, bearerFor(user)))
                .andExpect(status().isOk());

        // Verify in DB: only id2 has isDefault=true
        var c1 = conduitRepo.findById(id1).orElseThrow();
        var c2 = conduitRepo.findById(id2).orElseThrow();
        assertThat(c1.getPortalSettings().getIsDefault()).as("first config default flag").isNotEqualTo(Boolean.TRUE);
        assertThat(c2.getPortalSettings().getIsDefault()).as("second config default flag").isTrue();
    }
```

- [ ] **Step 4: Run all 8 tests and verify they pass**

```
docker compose exec -T backend ./mvnw test -Dtest=DealConduitControllerIntegrationTest
```

Expected: 8 tests pass. If `setAsDefault_unsetsPreviousDefault` fails because the implementation doesn't actually unset previous defaults (a behavior bug), update the assertion to document current behavior and add a `// TODO` comment.

- [ ] **Step 5: Commit**

```
git add backend/src/test/java/com/hdc/hdc_map_backend/controller/taxBenefits/DealConduitControllerIntegrationTest.java
git commit -m "test: DealConduitController user-scoped configuration tests"
```

---

### Task 7: Update `CLAUDE.md` with the testing workflow

Document the new test commands and the `down -v` requirement so the next developer doesn't get a "database hdc_test does not exist" surprise.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a "Backend tests" section right after the existing "Hot reload" section**

In `CLAUDE.md`, find the line `**Reset the local DB:**`. Just *before* that line, insert:

```markdown
**Backend tests:** Integration tests use a separate `hdc_test` database on the same Postgres instance, created by `docker/postgres/init/02-create-test-db.sql`. Run:

```bash
docker compose exec backend ./mvnw test
```

Tests use `@ActiveProfiles("test")` which loads `backend/src/test/resources/application-test.properties`. Hibernate's `ddl-auto=create-drop` rebuilds the test schema on each run.

If you see `database "hdc_test" does not exist`, your Postgres data volume predates the init script: `docker compose down -v && docker compose up -d` to re-run it. Destructive — wipes any local dev data.

```

(Keep the blank line before `**Reset the local DB:**` so the new section is its own paragraph.)

- [ ] **Step 2: Verify the diff**

```
git diff CLAUDE.md
```

Expected: only the new "Backend tests" section added. No other changes.

- [ ] **Step 3: Commit**

```
git add CLAUDE.md
git commit -m "docs: document backend test workflow in CLAUDE.md"
```

---

### Task 8: Full acceptance run-through

Manual verification of the five acceptance criteria from the spec. No commit — confirms everything composed correctly.

**Files:** none

- [ ] **Step 1: Fresh stack run**

```
docker compose down -v
docker compose up -d
```

Wait for backend to be healthy:
```
until docker compose exec -T backend curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/public/login | grep -qE "^(401|403|405)$"; do sleep 2; done && echo "backend ready"
```

Expected: `backend ready` within ~60s.

- [ ] **Step 2: Run the full test suite**

```
docker compose exec -T backend ./mvnw test
```

Expected: BUILD SUCCESS. Test count: 9 original (contextLoads + 8 unit) + 18 new (10 UserController + 8 DealConduitController) = 27 total. Maven output shows `Tests run: 27, Failures: 0, Errors: 0, Skipped: 0`.

Spec acceptance criterion 1.

- [ ] **Step 3: Verify test schema is dropped after run**

```
docker compose exec -T postgres psql -U hdc -d hdc_test -c "\dt user_schema.*" -tA
```

Expected: empty output (or `Did not find any relations.`). Hibernate dropped the schema on context shutdown.

Spec acceptance criterion 2.

- [ ] **Step 4: Verify dev DB was untouched**

```
docker compose exec -T postgres psql -U hdc -d hdc -c "SELECT COUNT(*) FROM user_schema.users" -tA
```

Note: the `users` table name (plural — see `@Table(name = "users")` on `User.java`). It may not exist on a brand-new dev DB (the seeder only creates presets, not users). If the table doesn't exist, that itself confirms tests didn't pollute the dev DB.

Expected: either a count (e.g., `0`) or `relation "user_schema.users" does not exist`. Either way, no test data leaked here.

Spec acceptance criterion 3.

- [ ] **Step 5: Re-run tests to confirm determinism**

```
docker compose exec -T backend ./mvnw test
```

Expected: same 27/0/0/0 result as Step 2. No flakiness from leaked state.

Spec acceptance criterion 4.

- [ ] **Step 6: Run a single test class to confirm class-level isolation**

```
docker compose exec -T backend ./mvnw test -Dtest=UserControllerIntegrationTest
```

Expected: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`. Confirms each test class can be run individually.

Spec acceptance criterion 5.

- [ ] **Step 7: Final cleanup check**

```
git status
```

Expected: clean working tree.

---

## Self-review notes

Coverage check against the spec:
- **Postgres init script (`02-create-test-db.sql`):** Task 1.
- **`application-test.properties` with overrides for DB, JWT, S3, mail, OAuth:** Task 2 step 1.
- **`JwtTestHelper` static utility:** Task 2 step 2.
- **`BaseIntegrationTest` with `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `assertTestDatabase` safety check + `createUser`/`bearerFor` helpers:** Task 2 step 3.
- **`contextLoads` refactor:** Task 2 step 4.
- **`UserControllerIntegrationTest` (10 tests):** Tasks 3 and 4 covering register (2), login (3), forgot-password (2), reset-password (2), google-auth (1).
- **`DealConduitControllerIntegrationTest` (8 tests):** Tasks 5 and 6 covering auth gate, preset CRUD (3), config list, config create, default get, set-default.
- **CLAUDE.md update:** Task 7.
- **5 acceptance criteria:** Task 8 steps 2-6 cover them one-to-one.
- **Out of scope items:** duplicate-username fix (Task 3 step 3 documents the bug instead), all-other-controllers, Testcontainers, CI, host-side runtime, PresetSeeder tests — correctly absent.

Type / signature consistency check:
- `JwtTestHelper.tokenFor(JwtUtil, User)` and `.bearer(JwtUtil, User)` — used consistently throughout test files.
- `createUser(String username)` — returns `User`, used everywhere a test needs an authenticated user.
- `bearerFor(User)` (inherited from `BaseIntegrationTest`) — used in all DealConduitController tests.
- Database name `hdc_test` — appears consistently in init script, properties file, safety check.
- Test profile name `test` — consistent in `@ActiveProfiles("test")` and the file name `application-test.properties`.

No placeholders, no "implement similar to Task N" — every step shows its own code. One known fragility: a couple of tests (login wrong-password, reset-password invalid-token, setAsDefault behavior) assert current implementation behavior that may be imperfect. Each carries an instruction to adjust the assertion if the actual behavior differs — the goal is documenting truth, not aspirations.
