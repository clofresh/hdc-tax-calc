# Docker Compose Local Development Environment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Docker Compose environment that runs the frontend (Vite dev server), backend (Spring Boot), and PostgreSQL locally, plus LocalStack (S3) and Mailhog (SMTP) — so day-to-day local development no longer requires the SSH tunnel to AWS RDS.

**Architecture:** Single `docker-compose.yml` at the repo root defines five long-running services on one bridge network. Custom Postgres image extends `postgis/postgis:16-3.4` with pgvector. Backend container runs Maven inside, source bind-mounted, Spring DevTools restarts on class change. Frontend container runs Vite with bind-mounted source and a named volume for `node_modules`. LocalStack substitutes for S3; Mailhog catches SMTP. AWS Bedrock and Google OAuth degrade gracefully when credentials are absent.

**Tech Stack:** Docker Compose v2, PostgreSQL 16 + PostGIS 3.4 + pgvector, Eclipse Temurin 17 JDK, Node 22, LocalStack 3, Mailhog.

**Reference spec:** `docs/superpowers/specs/2026-05-14-docker-compose-local-dev-design.md`

---

## Verified facts (resolved during plan writing)

- `S3Service.java:21` reads `${aws.s3.bucket-name}` → Spring env var binding `AWS_S3_BUCKET_NAME`.
- `S3Service.java:24` reads `${aws.region}` → `AWS_REGION` (also matches AWS SDK default).
- `AWSConfig.java:13-15` reads `aws.access.key.id` / `aws.secret.access.key` → `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` (also matches AWS SDK defaults).
- `frontend/src/services/api.ts:4` reads `import.meta.env.VITE_API_BASE_URL`, defaulting to `http://localhost:8080/api`. The `/api` suffix is part of the base URL.
- `frontend/src/App.tsx:21` reads `import.meta.env.VITE_GOOGLE_CLIENT_ID`.
- `S3Service.java` builds `S3Client` directly in `@PostConstruct init()` (lines 35-52) — no current endpoint override. Two builder branches (with/without static creds).
- `pom.xml:51` declares `spring-boot-devtools` runtime. Hot restart is available.
- `pom.xml:127` declares `me.paulschwarz:spring-dotenv:4.0.0` — auto-loads `.env` from working dir when backend runs on host.

---

## File structure

**Files created:**

| Path                                                                                           | Responsibility                                                       |
|------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| `docker-compose.yml`                                                                           | Service definitions, network, volumes                                |
| `.env.example`                                                                                 | Committed template for `.env` (which is gitignored)                  |
| `docker/postgres/Dockerfile`                                                                   | Custom Postgres image = PostGIS + pgvector                           |
| `docker/postgres/init/01-extensions-and-schemas.sql`                                           | Create extensions and schemas on first DB boot                       |
| `backend/Dockerfile.dev`                                                                       | JDK 17 + Maven, runs `mvnw spring-boot:run`                          |
| `backend/.dockerignore`                                                                        | Keep `target/`, IDE files, etc. out of build context                 |
| `frontend/Dockerfile.dev`                                                                      | Node 22 + `npm ci`, runs Vite dev server                             |
| `frontend/.dockerignore`                                                                       | Keep `node_modules`, `dist`, etc. out of build context               |
| `backend/src/test/java/com/hdc/hdc_map_backend/service/S3EndpointOverrideTest.java`            | Unit test for the new S3 endpoint helper                             |
| `backend/src/test/java/com/hdc/hdc_map_backend/config/BedrockCredentialResolverTest.java`      | Unit test for the new Bedrock cred fallback helper                   |

**Files modified:**

| Path                                                                                  | Change                                                            |
|---------------------------------------------------------------------------------------|-------------------------------------------------------------------|
| `backend/src/main/java/com/hdc/hdc_map_backend/service/S3Service.java`                | Add endpoint override + path-style addressing when env var set    |
| `backend/src/main/java/com/hdc/hdc_map_backend/config/AWSConfig.java`                 | Split Bedrock credentials with fallback to shared creds           |
| `CLAUDE.md`                                                                           | Add "Local development with Docker Compose" section + troubleshooting |

**Files explicitly untouched:** `backend/dev.sh` (kept for RDS-tunnel workflow), `AGENTS.md`, frontend source files, any production config.

---

## Task list

1. Postgres image — Dockerfile and init SQL
2. `.env.example` at repo root
3. Compose v1: skeleton + Postgres service only; smoke check
4. S3 endpoint override (TDD)
5. Bedrock credential split (TDD)
6. Backend Dockerfile.dev + .dockerignore
7. Compose v2: add backend; smoke check ddl-auto creates schemas
8. LocalStack + bucket init container
9. Backend ↔ LocalStack S3 end-to-end check
10. Mailhog service + end-to-end email check
11. Frontend Dockerfile.dev + .dockerignore
12. Compose v3: add frontend; smoke check HMR
13. Full end-to-end smoke test
14. CLAUDE.md update

---

### Task 1: Postgres image — Dockerfile and init SQL

**Files:**
- Create: `docker/postgres/Dockerfile`
- Create: `docker/postgres/init/01-extensions-and-schemas.sql`

- [ ] **Step 1: Create the Dockerfile**

```dockerfile
# docker/postgres/Dockerfile
FROM postgis/postgis:16-3.4
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*
```

- [ ] **Step 2: Create the init SQL**

```sql
-- docker/postgres/init/01-extensions-and-schemas.sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS tax_benefits;

GRANT ALL ON SCHEMA user_schema, tax_benefits TO hdc;
```

- [ ] **Step 3: Build the image standalone to verify pgvector apt package exists**

Run: `docker build -t hdc-postgres-test docker/postgres`
Expected: Build completes without errors. If apt cannot find `postgresql-16-pgvector`, the Debian 12 base may have changed; substitute `postgresql-16-pgvector` for a community pgvector image (`pgvector/pgvector:pg16`) and reverse the layering — add PostGIS to that instead.

- [ ] **Step 4: Commit**

```bash
git add docker/postgres/
git commit -m "feat(docker): postgres image with PostGIS + pgvector"
```

---

### Task 2: `.env.example` at repo root

**Files:**
- Create: `.env.example`

- [ ] **Step 1: Create the file**

```bash
# .env.example
# Copy to .env and adjust as needed. .env is gitignored.

# === Postgres ===
POSTGRES_PASSWORD=hdc_local

# === Mail (Mailhog defaults — override if hitting real SMTP) ===
SPRING_MAIL_HOST=mailhog
SPRING_MAIL_PORT=1025
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=

# === S3 (LocalStack defaults — leave as-is for local) ===
AWS_S3_ENDPOINT=http://localstack:4566
AWS_S3_BUCKET_NAME=hdc-local
AWS_REGION=us-east-2
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# === Bedrock (optional — leave blank to disable chat endpoints) ===
# Set these only if you want Bedrock against real AWS. They override
# the shared AWS creds above for Bedrock requests only.
# AWS_BEDROCK_ACCESS_KEY_ID=
# AWS_BEDROCK_SECRET_ACCESS_KEY=
BEDROCK_REGION=us-east-2

# === Google OAuth (optional — leave blank to disable Google sign-in) ===
# VITE_GOOGLE_CLIENT_ID=
# GOOGLE_CLIENT_ID=
# GOOGLE_CLIENT_SECRET=

# === JWT signing secret (required) ===
JWT_SECRET=change-me-locally-must-be-32-chars-or-more

# === Frontend ===
VITE_API_BASE_URL=http://localhost:8080/api
```

- [ ] **Step 2: Confirm `.env*` is gitignored**

Run: `grep -n "^\.env" .gitignore`
Expected: line 24 matches `.env*`. If absent, add `.env` on its own line.

- [ ] **Step 3: Commit**

```bash
git add .env.example
git commit -m "feat(docker): .env.example with local dev defaults"
```

---

### Task 3: Compose v1 — skeleton + Postgres service

**Files:**
- Create: `docker-compose.yml`

- [ ] **Step 1: Create the compose file with only the Postgres service**

```yaml
# docker-compose.yml
services:
  postgres:
    build: ./docker/postgres
    environment:
      POSTGRES_DB: hdc
      POSTGRES_USER: hdc
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-hdc_local}
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hdc -d hdc"]
      interval: 5s
      timeout: 3s
      retries: 10
    networks:
      - hdc-net

networks:
  hdc-net:
    driver: bridge

volumes:
  postgres-data:
```

- [ ] **Step 2: Set up `.env` locally for the smoke test**

Run: `cp .env.example .env` (PowerShell: `Copy-Item .env.example .env`).

- [ ] **Step 3: Bring up Postgres and verify**

Run:
```bash
docker compose up -d postgres
docker compose ps
```
Expected: `postgres` shows `healthy` within ~15s.

- [ ] **Step 4: Verify extensions and schemas were created**

Run:
```bash
docker compose exec postgres psql -U hdc hdc -c "\dx"
docker compose exec postgres psql -U hdc hdc -c "\dn"
```
Expected output of `\dx` lists `postgis` and `vector`. Expected `\dn` lists schemas `public`, `tax_benefits`, `user_schema`.

- [ ] **Step 5: Bring it down (preserving data) and back up to confirm idempotency**

Run:
```bash
docker compose down
docker compose up -d postgres
docker compose exec postgres psql -U hdc hdc -c "\dn"
```
Expected: schemas still present.

- [ ] **Step 6: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): compose skeleton with postgres service"
```

---

### Task 4: S3 endpoint override (TDD)

The change adds an optional `aws.s3.endpoint` property; when set, the S3 client uses that endpoint with path-style addressing (required for LocalStack). When unset, behavior is identical to today.

**Files:**
- Modify: `backend/src/main/java/com/hdc/hdc_map_backend/service/S3Service.java`
- Create: `backend/src/test/java/com/hdc/hdc_map_backend/service/S3EndpointOverrideTest.java`

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/hdc/hdc_map_backend/service/S3EndpointOverrideTest.java
package com.hdc.hdc_map_backend.service;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class S3EndpointOverrideTest {

    @Test
    void applyEndpointOverride_setsEndpoint_whenUrlProvided() {
        S3ClientBuilder b = S3Client.builder().region(Region.US_EAST_2);
        S3Service.applyEndpointOverride(b, "http://localstack:4566");
        // The builder's endpoint override is package-private; we exercise the
        // method by building and reading the resulting client's serviceClientConfiguration.
        try (S3Client client = b.build()) {
            URI endpoint = client.serviceClientConfiguration().endpointOverride().orElseThrow();
            assertEquals(URI.create("http://localstack:4566"), endpoint);
        }
    }

    @Test
    void applyEndpointOverride_isNoop_whenNull() {
        S3ClientBuilder b = S3Client.builder().region(Region.US_EAST_2);
        S3Service.applyEndpointOverride(b, null);
        try (S3Client client = b.build()) {
            assertTrue(client.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }

    @Test
    void applyEndpointOverride_isNoop_whenEmpty() {
        S3ClientBuilder b = S3Client.builder().region(Region.US_EAST_2);
        S3Service.applyEndpointOverride(b, "");
        try (S3Client client = b.build()) {
            assertTrue(client.serviceClientConfiguration().endpointOverride().isEmpty());
        }
    }
}
```

- [ ] **Step 2: Run the test, expect it to fail**

Run: `cd backend && ./mvnw -q -Dtest=S3EndpointOverrideTest test`
Expected: FAIL with "cannot find symbol method applyEndpointOverride".

- [ ] **Step 3: Modify `S3Service.java` to add the helper and use it from `init()`**

Add this static helper to `S3Service.java` (place after the field declarations, before `init()`):

```java
static void applyEndpointOverride(software.amazon.awssdk.services.s3.S3ClientBuilder builder,
                                  String endpointUrl) {
    if (endpointUrl != null && !endpointUrl.isEmpty()) {
        builder.endpointOverride(java.net.URI.create(endpointUrl))
               .forcePathStyle(true);
    }
}
```

Add a new `@Value` field near the existing ones:

```java
@Value("${aws.s3.endpoint:}")
private String s3Endpoint;
```

Replace the existing `init()` method body so both builder branches call the helper:

```java
@PostConstruct
public void init() {
    if (accessKeyId != null && !accessKeyId.isEmpty()
            && secretAccessKey != null && !secretAccessKey.isEmpty()) {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKeyId, secretAccessKey);
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(credentials));
        applyEndpointOverride(builder, s3Endpoint);
        this.s3Client = builder.build();
    } else {
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region));
        applyEndpointOverride(builder, s3Endpoint);
        this.s3Client = builder.build();
    }
}
```

Add the import `import software.amazon.awssdk.services.s3.S3ClientBuilder;` at the top.

- [ ] **Step 4: Run the test, expect it to pass**

Run: `cd backend && ./mvnw -q -Dtest=S3EndpointOverrideTest test`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the full backend test suite to confirm no regression**

Run: `cd backend && ./mvnw test`
Expected: All tests pass (baseline is the existing `HdcMapBackendApplicationTests` + the 3 new tests). The smoke test loads the Spring context — it will pass without `aws.s3.endpoint` set because the property has an empty default.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hdc/hdc_map_backend/service/S3Service.java
git add backend/src/test/java/com/hdc/hdc_map_backend/service/S3EndpointOverrideTest.java
git commit -m "feat(backend): S3 endpoint override for LocalStack in local dev"
```

---

### Task 5: Bedrock credential split (TDD)

Add Bedrock-specific credentials with fallback to the shared AWS creds. When `aws.bedrock.access.key.id` is non-empty, it overrides for Bedrock requests; otherwise the shared `aws.access.key.id` is used. Production behavior is unchanged (both unset → DefaultCredentialsProvider; shared set → shared creds).

**Files:**
- Modify: `backend/src/main/java/com/hdc/hdc_map_backend/config/AWSConfig.java`
- Create: `backend/src/test/java/com/hdc/hdc_map_backend/config/BedrockCredentialResolverTest.java`

- [ ] **Step 1: Write the failing test**

```java
// backend/src/test/java/com/hdc/hdc_map_backend/config/BedrockCredentialResolverTest.java
package com.hdc.hdc_map_backend.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BedrockCredentialResolverTest {

    @Test
    void usesBedrockSpecificKey_whenBothBedrockValuesPresent() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("bedrock-id", "bedrock-secret",
                                                              "shared-id", "shared-secret");
        assertEquals("bedrock-id", r.accessKeyId());
        assertEquals("bedrock-secret", r.secretAccessKey());
    }

    @Test
    void fallsBackToShared_whenBedrockValuesEmpty() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("", "", "shared-id", "shared-secret");
        assertEquals("shared-id", r.accessKeyId());
        assertEquals("shared-secret", r.secretAccessKey());
    }

    @Test
    void fallsBackToShared_whenBedrockValuesNull() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey(null, null, "shared-id", "shared-secret");
        assertEquals("shared-id", r.accessKeyId());
        assertEquals("shared-secret", r.secretAccessKey());
    }

    @Test
    void fallsBackToShared_whenOnlyBedrockAccessKeyMissing() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("", "bedrock-secret",
                                                              "shared-id", "shared-secret");
        // Partial Bedrock creds → fall back to shared (don't mix half from each)
        assertEquals("shared-id", r.accessKeyId());
        assertEquals("shared-secret", r.secretAccessKey());
    }

    @Test
    void bothEmpty_returnsEmpty() {
        AWSConfig.ResolvedKey r = AWSConfig.resolveBedrockKey("", "", "", "");
        assertEquals("", r.accessKeyId());
        assertEquals("", r.secretAccessKey());
    }
}
```

- [ ] **Step 2: Run the test, expect it to fail**

Run: `cd backend && ./mvnw -q -Dtest=BedrockCredentialResolverTest test`
Expected: FAIL with "cannot find symbol AWSConfig.ResolvedKey / AWSConfig.resolveBedrockKey".

- [ ] **Step 3: Modify `AWSConfig.java`**

Add two new `@Value` fields below the existing ones:

```java
@Value("${aws.bedrock.access.key.id:}")
private String bedrockAccessKeyId;

@Value("${aws.bedrock.secret.access.key:}")
private String bedrockSecretAccessKey;
```

Add the static helper and record at the bottom of the class:

```java
public record ResolvedKey(String accessKeyId, String secretAccessKey) {}

static ResolvedKey resolveBedrockKey(String bedrockAccessKeyId,
                                     String bedrockSecretAccessKey,
                                     String sharedAccessKeyId,
                                     String sharedSecretAccessKey) {
    String bid = bedrockAccessKeyId == null ? "" : bedrockAccessKeyId;
    String bsk = bedrockSecretAccessKey == null ? "" : bedrockSecretAccessKey;
    if (!bid.isEmpty() && !bsk.isEmpty()) {
        return new ResolvedKey(bid, bsk);
    }
    String sid = sharedAccessKeyId == null ? "" : sharedAccessKeyId;
    String ssk = sharedSecretAccessKey == null ? "" : sharedSecretAccessKey;
    return new ResolvedKey(sid, ssk);
}
```

Update `bedrockRuntimeClient()` to use the resolved key. Replace the existing if/else block that picks credentials with this:

```java
ResolvedKey resolved = resolveBedrockKey(bedrockAccessKeyId, bedrockSecretAccessKey,
                                         accessKeyId, secretAccessKey);

AwsCredentialsProvider credentialsProvider;
if (!resolved.accessKeyId().isEmpty() && !resolved.secretAccessKey().isEmpty()) {
    System.out.println("Using configured AWS credentials for Bedrock"
            + (bedrockAccessKeyId != null && !bedrockAccessKeyId.isEmpty()
                ? " (Bedrock-specific)" : " (shared)"));
    credentialsProvider = StaticCredentialsProvider.create(
        AwsBasicCredentials.create(resolved.accessKeyId(), resolved.secretAccessKey())
    );
} else {
    System.out.println("Attempting to use default AWS credentials provider chain for Bedrock");
    try {
        credentialsProvider = DefaultCredentialsProvider.builder().build();
        credentialsProvider.resolveCredentials();
        System.out.println("Default credentials provider chain successful");
    } catch (Exception e) {
        System.err.println("No AWS credentials available for Bedrock: " + e.getMessage());
        System.err.println("To use Bedrock, set aws.access.key.id and aws.secret.access.key, "
                + "or aws.bedrock.access.key.id and aws.bedrock.secret.access.key");
        return null;
    }
}
```

- [ ] **Step 4: Run the test, expect it to pass**

Run: `cd backend && ./mvnw -q -Dtest=BedrockCredentialResolverTest test`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the full backend test suite**

Run: `cd backend && ./mvnw test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/hdc/hdc_map_backend/config/AWSConfig.java
git add backend/src/test/java/com/hdc/hdc_map_backend/config/BedrockCredentialResolverTest.java
git commit -m "feat(backend): split Bedrock AWS credentials from shared S3 creds"
```

---

### Task 6: Backend Dockerfile.dev + .dockerignore

**Files:**
- Create: `backend/Dockerfile.dev`
- Create: `backend/.dockerignore`

- [ ] **Step 1: Create the Dockerfile**

```dockerfile
# backend/Dockerfile.dev
FROM eclipse-temurin:17-jdk
WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline

EXPOSE 8080
CMD ["./mvnw", "spring-boot:run", "-Dspring-boot.run.fork=false"]
```

- [ ] **Step 2: Create .dockerignore**

```
# backend/.dockerignore
target/
.idea/
.vscode/
*.iml
.DS_Store
*.log
```

- [ ] **Step 3: Build the image standalone**

Run: `docker build -f backend/Dockerfile.dev -t hdc-backend-test backend/`
Expected: Build completes. Pre-fetching Maven deps takes 3-5 min on first run; subsequent builds use cache.

- [ ] **Step 4: Commit**

```bash
git add backend/Dockerfile.dev backend/.dockerignore
git commit -m "feat(docker): backend dev image with JDK 17 + Maven"
```

---

### Task 7: Compose v2 — add backend service; verify schema creation

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Append the backend service**

Add under `services:` (after the `postgres` block):

```yaml
  backend:
    build:
      context: ./backend
      dockerfile: Dockerfile.dev
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "8080:8080"
    env_file: .env
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/hdc?currentSchema=user_schema
      SPRING_DATASOURCE_USERNAME: hdc
      SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-hdc_local}
      SPRING_JPA_HIBERNATE_DDL_AUTO: update
      SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA: user_schema
    volumes:
      - ./backend/src:/app/src
      - ./backend/pom.xml:/app/pom.xml
      - maven-repo:/root/.m2
    networks:
      - hdc-net
```

Add to the `volumes:` block at the bottom of the file:

```yaml
  maven-repo:
```

- [ ] **Step 2: Bring up the backend**

Run:
```bash
docker compose up -d backend
docker compose logs -f backend
```
Expected within ~60-90s: log lines including "✅ Successfully connected to RDS:" (the message is misleading — it's actually local Postgres, but that's existing code) and "HDC Map Spring Boot Application started successfully".

- [ ] **Step 3: Verify schemas now contain tables**

Run:
```bash
docker compose exec postgres psql -U hdc hdc -c "\dt user_schema.*"
docker compose exec postgres psql -U hdc hdc -c "\dt tax_benefits.*"
```
Expected: `user_schema.*` lists tables including `user`, `password_reset_token`. `tax_benefits.*` lists `deal_conduit`, `investment_pool`, `investor_tax_info`, `pool_membership`, and the various `input_*` tables.

- [ ] **Step 4: Verify the API is reachable from the host**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/api/public/health || curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8080/`
Expected: any HTTP response (200, 401, 404 — all confirm the server is listening). Connection refused means the container isn't ready or didn't start.

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add backend service to compose"
```

---

### Task 8: LocalStack + bucket init container

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Append the `localstack` and `localstack-init` services**

Add under `services:`:

```yaml
  localstack:
    image: localstack/localstack:3
    ports:
      - "4566:4566"
    environment:
      SERVICES: s3
      DEBUG: 0
      PERSISTENCE: 1
    volumes:
      - localstack-data:/var/lib/localstack
    healthcheck:
      test: ["CMD", "curl", "-fs", "http://localhost:4566/_localstack/health"]
      interval: 5s
      timeout: 3s
      retries: 10
    networks:
      - hdc-net

  localstack-init:
    image: amazon/aws-cli:latest
    depends_on:
      localstack:
        condition: service_healthy
    environment:
      AWS_ACCESS_KEY_ID: test
      AWS_SECRET_ACCESS_KEY: test
      AWS_DEFAULT_REGION: us-east-2
    entrypoint:
      - sh
      - -c
      - |
        aws --endpoint-url=http://localstack:4566 s3 mb s3://hdc-local --region us-east-2 || true
        aws --endpoint-url=http://localstack:4566 s3api put-bucket-cors --bucket hdc-local --cors-configuration '{"CORSRules":[{"AllowedOrigins":["*"],"AllowedMethods":["GET","PUT","POST","DELETE"],"AllowedHeaders":["*"]}]}'
    networks:
      - hdc-net
```

Update the `backend` service's `depends_on` to also wait on LocalStack:

```yaml
    depends_on:
      postgres:
        condition: service_healthy
      localstack:
        condition: service_healthy
```

Add to the `volumes:` block:

```yaml
  localstack-data:
```

- [ ] **Step 2: Bring up LocalStack**

Run:
```bash
docker compose up -d localstack localstack-init
docker compose ps
```
Expected: `localstack` is healthy; `localstack-init` exits with code 0 after creating the bucket.

- [ ] **Step 3: Verify the bucket exists**

Run: `docker compose run --rm localstack-init aws --endpoint-url=http://localstack:4566 s3 ls`
Expected: `2026-... hdc-local` in output.

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add localstack + bucket init"
```

---

### Task 9: Backend ↔ LocalStack S3 end-to-end check

This verifies the S3 endpoint override from Task 4 works inside the compose stack. The `backend` service already inherits `AWS_S3_ENDPOINT=http://localstack:4566` from `.env`.

**Files:**
- (No new files. Read-only verification task.)

- [ ] **Step 1: Restart backend so it picks up the LocalStack endpoint**

Run:
```bash
docker compose up -d --force-recreate backend
docker compose logs backend | grep -E "(S3|started|Started)"
```
Expected: backend boots without errors related to AWS endpoint resolution.

- [ ] **Step 2: Trigger a profile image upload via the API (or by signing up + uploading through the frontend later)**

Manual: log in as a test user and upload an avatar, or `curl` the endpoint directly if you have a JWT. If sign-up is blocked because mail is not yet wired, defer the upload check until after Task 10 — but verify the boot at minimum here.

- [ ] **Step 3 (deferred): Confirm the object lands in LocalStack**

Run: `docker compose run --rm localstack-init aws --endpoint-url=http://localstack:4566 s3 ls s3://hdc-local/profile-images/ --recursive`
Expected: at least one object after a successful upload. If you defer the upload to Task 13's full smoke test, mark this step done now and circle back.

- [ ] **Step 4: Commit (nothing new, but tag the verification)**

If there are no file changes, skip the commit. Otherwise:

```bash
# nothing to commit here unless config tweaks were needed
```

---

### Task 10: Mailhog service + email check

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Append the `mailhog` service**

Add under `services:`:

```yaml
  mailhog:
    image: mailhog/mailhog:latest
    ports:
      - "1025:1025"
      - "8025:8025"
    networks:
      - hdc-net
```

Update the `backend` service's `depends_on` to add mailhog (start condition only — mailhog has no healthcheck):

```yaml
    depends_on:
      postgres:
        condition: service_healthy
      localstack:
        condition: service_healthy
      mailhog:
        condition: service_started
```

The backend already reads `SPRING_MAIL_HOST` and `SPRING_MAIL_PORT` from `.env`.

- [ ] **Step 2: Restart backend**

Run: `docker compose up -d --force-recreate backend`

- [ ] **Step 3: Trigger a password-reset email**

Endpoint is `POST /api/public/forgot-password` (`UserController.java:135`). Assuming a registered email `test@example.com`:

```bash
curl -X POST http://localhost:8080/api/public/forgot-password \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com"}'
```

If no user exists yet, sign one up first via the frontend (after Task 12) and circle back. Mark this step done after the round-trip works once.

- [ ] **Step 4: Verify the email landed in Mailhog**

Open `http://localhost:8025` in a browser. Expected: one message with subject containing "password reset" (or similar — depends on `EmailService` content).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add mailhog smtp catcher"
```

---

### Task 11: Frontend Dockerfile.dev + .dockerignore

**Files:**
- Create: `frontend/Dockerfile.dev`
- Create: `frontend/.dockerignore`

- [ ] **Step 1: Create the Dockerfile**

```dockerfile
# frontend/Dockerfile.dev
FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
```

- [ ] **Step 2: Create .dockerignore**

```
# frontend/.dockerignore
node_modules
dist
.cache
.vscode
.idea
*.log
.DS_Store
```

- [ ] **Step 3: Build the image standalone**

Run: `docker build -f frontend/Dockerfile.dev -t hdc-frontend-test frontend/`
Expected: `npm ci` completes; image builds. First build takes 1-2 min.

- [ ] **Step 4: Commit**

```bash
git add frontend/Dockerfile.dev frontend/.dockerignore
git commit -m "feat(docker): frontend dev image with node 22 + vite"
```

---

### Task 12: Compose v3 — add frontend service; verify HMR

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Append the `frontend` service**

Add under `services:`:

```yaml
  frontend:
    build:
      context: ./frontend
      dockerfile: Dockerfile.dev
    ports:
      - "5173:5173"
    environment:
      VITE_API_BASE_URL: http://localhost:8080/api
      CHOKIDAR_USEPOLLING: "true"
    volumes:
      - ./frontend:/app
      - frontend-node-modules:/app/node_modules
    networks:
      - hdc-net
```

Add to the `volumes:` block:

```yaml
  frontend-node-modules:
```

- [ ] **Step 2: Bring up the frontend**

Run:
```bash
docker compose up -d frontend
docker compose logs -f frontend
```
Expected: Vite logs "VITE vX.Y.Z ready in NNN ms" and "Local: http://localhost:5173/".

- [ ] **Step 3: Open the app in a browser**

Open `http://localhost:5173`. Expected: the app loads. Network requests to `http://localhost:8080/api/...` succeed (or fail with 401, which is fine — confirms the URL is right).

- [ ] **Step 4: Verify HMR**

Edit `frontend/src/App.tsx` — change any visible string. Save. Expected: browser updates within ~1s without a full reload.

If HMR does not fire, double-check `CHOKIDAR_USEPOLLING=true` is set in the container env (`docker compose exec frontend env | grep CHOKIDAR`).

- [ ] **Step 5: Commit**

```bash
git add docker-compose.yml
git commit -m "feat(docker): add frontend service to compose"
```

---

### Task 13: Full end-to-end smoke test

**Files:**
- (No new files. Verification task only.)

- [ ] **Step 1: Clean slate**

Run:
```bash
docker compose down -v
docker compose up -d
docker compose ps
```
Expected: all five long-running services (`postgres`, `backend`, `frontend`, `localstack`, `mailhog`) reach their expected states (`healthy` or `running`). `localstack-init` exited 0.

- [ ] **Step 2: End-to-end user flow**

In a browser:
1. Open `http://localhost:5173`.
2. Sign up a new user with email `test@example.com`.
3. Upload a profile image.
4. Log out, then click "Forgot password".
5. Open `http://localhost:8025` and click the reset link in the email.
6. Reset the password and log back in.

Expected: each step works without errors.

- [ ] **Step 3: Verify side-effects**

```bash
docker compose exec postgres psql -U hdc hdc -c "SELECT email FROM user_schema.\"user\";"
docker compose run --rm localstack-init aws --endpoint-url=http://localstack:4566 s3 ls s3://hdc-local --recursive
```

Expected: user row present in Postgres; profile image present in S3.

- [ ] **Step 4: Verify hot reload still works after the clean-slate restart**

- Edit a backend Java file (e.g. add a print statement to `HdcMapBackendApplication.java`).
- Run: `docker compose exec backend ./mvnw -q compile`
- Expected: backend logs show DevTools restart within ~5s.

- Edit a frontend `.tsx` file. Save.
- Expected: browser HMR within ~1s.

- [ ] **Step 5: No commit needed unless something was tweaked along the way.**

---

### Task 14: CLAUDE.md update

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Read the current `CLAUDE.md`**

Read `CLAUDE.md`. Find the heading `## Local Development`.

- [ ] **Step 2: Insert a new subsection above the existing `### Starting the backend` section**

After the `## Local Development` heading and before `### Starting the backend`, insert:

````markdown
### Starting everything with Docker Compose (preferred)

```bash
cp .env.example .env
docker compose up -d
```

Brings up Postgres, backend, frontend, LocalStack (S3 stub), and Mailhog (SMTP catcher) on one bridge network. Hibernate's `ddl-auto=update` creates the schema on first boot.

| Service     | URL                              | Notes                              |
|-------------|----------------------------------|------------------------------------|
| Frontend    | http://localhost:5173            | Vite dev server, HMR enabled       |
| Backend API | http://localhost:8080            | Spring Boot, Swagger at /swagger-ui |
| Postgres    | localhost:5432 (user `hdc`)      | DB name `hdc`                      |
| Mailhog UI  | http://localhost:8025            | Catches all outbound SMTP          |
| LocalStack  | http://localhost:4566            | S3 endpoint                        |

**Hot reload:**
- Frontend: save a file → Vite HMR (~200ms).
- Backend: save a Java file → run `docker compose exec backend ./mvnw -q compile` → DevTools restarts (~5s).

**Reset the local DB:** `docker compose down -v && docker compose up -d`.

**Troubleshooting:**
- "port 5432 already in use" — kill any local Postgres or stale SSH tunnel on 5432 before `up`.
- Frontend HMR doesn't fire — confirm `CHOKIDAR_USEPOLLING=true` in the `frontend` service env.
- Backend can't reach DB — check `docker compose logs postgres` for init errors; if seen, `down -v` and retry.
- Schema looks wrong after entity change — `ddl-auto=update` adds columns but doesn't drop or rename them. `down -v` for destructive changes.
- Email features fail — make sure `mailhog` is running and `SPRING_MAIL_HOST=mailhog`, `SPRING_MAIL_PORT=1025` in `.env`.
- S3 features fail — make sure `localstack` is healthy and `AWS_S3_ENDPOINT=http://localstack:4566`, `AWS_S3_BUCKET_NAME=hdc-local` in `.env`.

The existing SSH-tunnel workflow (below) is still available if you need to point local code at RDS directly.
````

- [ ] **Step 3: Verify the existing SSH-tunnel section is unchanged**

Read `CLAUDE.md` again. Confirm the `### Starting the backend` section and the `### Database` section are intact.

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: document docker compose local dev workflow"
```

---

## Done criteria

- `docker compose up -d` from a clean repo (with `.env` copied from `.env.example`) brings up all five services successfully.
- Backend connects to the local Postgres; Hibernate creates the `user_schema` and `tax_benefits` tables on first boot.
- Profile-image upload writes to LocalStack (visible via `awslocal s3 ls`).
- Password-reset email arrives in Mailhog UI at `http://localhost:8025`.
- Frontend HMR fires on file save.
- Backend DevTools restart fires after `mvnw compile`.
- Full test suite (`./mvnw test`) passes — `HdcMapBackendApplicationTests` + `S3EndpointOverrideTest` (3) + `BedrockCredentialResolverTest` (5).
- `dev.sh` is untouched and still works for the RDS-tunnel flow (validated by reading, not running).
- `CLAUDE.md` documents the new flow.
