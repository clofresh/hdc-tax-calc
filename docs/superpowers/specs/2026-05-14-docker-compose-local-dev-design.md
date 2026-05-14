# Docker Compose Local Development Environment — Design

**Date:** 2026-05-14
**Status:** Approved design, pending implementation plan
**Scope:** Add a Docker Compose environment for hdc-tax-calc that runs the frontend, backend, and Postgres locally, removing the dependency on the SSH tunnel to AWS RDS for day-to-day development.

## Motivation

Today's local development requires an SSH tunnel through a bastion host to AWS RDS (see `backend/dev.sh`). This couples local dev to production data, requires bastion access and pem keys, and uses a bash script that is macOS-only (`brew`, `lsof`). The goal of this work is a fully local, OS-portable, container-based development environment that replaces the RDS dependency for everyday work. The existing `dev.sh` flow is kept for anyone who still needs to point local code at RDS.

## Decisions (locked in during brainstorming)

1. **Local Postgres replaces the RDS tunnel.** No more bastion dependency for local dev.
2. **Empty database on first start.** Hibernate's `ddl-auto=update` builds tables from the JPA entities on first backend boot. No seed data, no SQL dumps to maintain.
3. **External services use local substitutes where possible.** LocalStack stands in for S3, Mailhog catches SMTP. AWS Bedrock and Google OAuth have no local equivalents — they are left optional and degrade gracefully when credentials are absent.
4. **Frontend runs in a container with Vite HMR and a source bind mount.** `node_modules` lives in a named volume to avoid Windows bind-mount performance issues.
5. **Single compose file, all five services always up.** No profiles. Simpler now; profiles can be added later if needed.

## Architecture

Five services on one user-defined Docker bridge network (`hdc-net`):

| Service       | Image                                                   | Host → Container ports                   | Purpose                                                |
|---------------|---------------------------------------------------------|------------------------------------------|--------------------------------------------------------|
| `postgres`    | custom — `docker/postgres/Dockerfile`                   | `5432:5432`                              | Local DB, PostGIS + pgvector extensions, schemas       |
| `backend`     | custom — `backend/Dockerfile.dev`                       | `8080:8080`                              | Spring Boot with DevTools, source bind-mounted         |
| `frontend`    | custom — `frontend/Dockerfile.dev`                      | `5173:5173`                              | Vite dev server with HMR, source bind-mounted          |
| `localstack`  | `localstack/localstack:3`                               | `4566:4566`                              | S3 substitute                                          |
| `mailhog`     | `mailhog/mailhog:latest`                                | `1025:1025` (SMTP), `8025:8025` (UI)     | SMTP catcher + web UI for password reset emails        |

Plus one one-shot init container:

| Service           | Image                       | Purpose                                                              |
|-------------------|-----------------------------|----------------------------------------------------------------------|
| `localstack-init` | `amazon/aws-cli:latest`     | Creates the `hdc-local` S3 bucket on first start, then exits         |

**Inter-service URLs** (used inside containers): `postgres:5432`, `localstack:4566`, `mailhog:1025`. **Host-side URLs** (browser, IDE, CLI tools): `localhost:5432`, `localhost:8080`, `localhost:5173`, `localhost:8025`, `localhost:4566`.

**Dependency order:** `backend` depends on `postgres` and `localstack` being healthy and `mailhog` being started. `frontend` has no service dependencies — it talks to the backend over `localhost:8080` from the user's browser, not from inside the container network.

**Named volumes:**

- `postgres-data` — Postgres data dir, survives `docker compose down`
- `maven-repo` — mounted at `/root/.m2` in the backend container so Maven deps cache across container rebuilds
- `frontend-node-modules` — mounted at `/app/node_modules` in the frontend container, shadowing the host's `node_modules` to avoid Windows perf issues
- `localstack-data` — LocalStack persistence

## Postgres image

Backend uses both **PostGIS** (`hibernate-spatial`) and **pgvector** (`com.pgvector:pgvector`). No official image ships both, so a thin custom image extends `postgis/postgis:16-3.4` with the pgvector package from Debian 12's apt repo:

```dockerfile
# docker/postgres/Dockerfile
FROM postgis/postgis:16-3.4
RUN apt-get update \
    && apt-get install -y --no-install-recommends postgresql-16-pgvector \
    && rm -rf /var/lib/apt/lists/*
```

**Compose service:**

```yaml
postgres:
  build: ./docker/postgres
  environment:
    POSTGRES_DB: hdc
    POSTGRES_USER: hdc
    POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-hdc_local}
  ports: ["5432:5432"]
  volumes:
    - postgres-data:/var/lib/postgresql/data
    - ./docker/postgres/init:/docker-entrypoint-initdb.d:ro
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U hdc -d hdc"]
    interval: 5s
    timeout: 3s
    retries: 10
```

**Init script** — runs once on first boot (Postgres convention: scripts in `/docker-entrypoint-initdb.d/` only execute when the data directory is empty):

```sql
-- docker/postgres/init/01-extensions-and-schemas.sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE SCHEMA IF NOT EXISTS user_schema;
CREATE SCHEMA IF NOT EXISTS tax_benefits;

GRANT ALL ON SCHEMA user_schema, tax_benefits TO hdc;
```

After init, Hibernate's `ddl-auto=update` creates the tables under `user_schema` (default) and `tax_benefits` on first backend boot.

**Reset path:** `docker compose down -v` removes the `postgres-data` volume, so the next `up` re-runs init scripts and Hibernate rebuilds the schema from scratch. Required when changing the init script.

## Backend container

A dev-focused Dockerfile runs Maven inside the container so Spring DevTools can pick up classpath changes via hot restart.

```dockerfile
# backend/Dockerfile.dev
FROM eclipse-temurin:17-jdk
WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -q dependency:go-offline

EXPOSE 8080
CMD ["./mvnw", "spring-boot:run", "-Dspring-boot.run.fork=false"]
```

Source (`backend/src/` and `backend/pom.xml`) is bind-mounted at runtime, not copied at build time. The `maven-repo` named volume caches `/root/.m2` so dep resolution is fast across rebuilds.

**Compose service** (env values shown are defaults; secrets come from `.env`):

```yaml
backend:
  build: { context: ./backend, dockerfile: Dockerfile.dev }
  depends_on:
    postgres:    { condition: service_healthy }
    localstack:  { condition: service_healthy }
    mailhog:     { condition: service_started }
  ports: ["8080:8080"]
  env_file: .env
  environment:
    SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/hdc?currentSchema=user_schema
    SPRING_DATASOURCE_USERNAME: hdc
    SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD:-hdc_local}
    SPRING_JPA_HIBERNATE_DDL_AUTO: update
    SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA: user_schema
    SPRING_MAIL_HOST: mailhog
    SPRING_MAIL_PORT: 1025
    AWS_S3_ENDPOINT: http://localstack:4566
    AWS_REGION: us-east-2
    AWS_ACCESS_KEY_ID: test
    AWS_SECRET_ACCESS_KEY: test
  volumes:
    - ./backend/src:/app/src
    - ./backend/pom.xml:/app/pom.xml
    - maven-repo:/root/.m2
```

**Hot reload:** `spring-boot-devtools` is already declared in `pom.xml`. With source bind-mounted, editing a `.java` file on the host and running `./mvnw compile` (or letting an IDE auto-compile on save) updates the class files inside the container, and DevTools restarts the app in ~2 seconds.

## Backend code changes in scope

Two small changes to existing backend code are required for the LocalStack S3 substitute and the dual-cred problem (S3 on LocalStack, Bedrock optionally on real AWS):

1. **S3 client endpoint override.** `S3Service` currently builds an `S3Client` without an endpoint override. Add `endpointOverride(URI.create(endpoint))` when `AWS_S3_ENDPOINT` env var is non-empty; otherwise leave default AWS endpoint behavior unchanged. Verified by: production deployments do not set `AWS_S3_ENDPOINT`, so default behavior is preserved.
2. **AWS credentials split for Bedrock.** `AWSConfig` reads one shared set of credentials. Add a separate `aws.bedrock.access.key.id` / `aws.bedrock.secret.access.key` pair that falls back to the shared values when unset. This lets the local env keep S3 on LocalStack (test creds) while optionally pointing Bedrock at real AWS.

These two changes are spec'd here but live in the implementation plan as one task. They must not change behavior when run outside the local Docker env.

## Frontend container

```dockerfile
# frontend/Dockerfile.dev
FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
```

**Compose service:**

```yaml
frontend:
  build: { context: ./frontend, dockerfile: Dockerfile.dev }
  ports: ["5173:5173"]
  environment:
    VITE_API_BASE_URL: http://localhost:8080
    CHOKIDAR_USEPOLLING: "true"
  volumes:
    - ./frontend:/app
    - frontend-node-modules:/app/node_modules
```

**Three things that need to be exactly right:**

- **`--host 0.0.0.0`** — Vite binds to localhost inside the container by default. The host can't reach it without `--host`.
- **Named volume over `node_modules`** — the bind mount `./frontend:/app` would otherwise replace the container's `node_modules` (installed during image build) with the host's. The named volume shadows that path so the container's own platform-correct `node_modules` survives.
- **`CHOKIDAR_USEPOLLING=true`** — Vite's native file watching does not fire reliably through Windows → Linux bind mounts. Polling is the standard workaround; it costs a small amount of CPU but is set unconditionally for simplicity.

**`VITE_API_BASE_URL`** — the bundle runs in the user's browser, not inside the container, so `localhost:8080` is the correct address. Implementation step: confirm the frontend actually reads this env var name (vs. `VITE_BACKEND_URL`, a hardcoded URL, or something else) by grepping `frontend/src/`. If the name differs, the compose env var name is updated to match.

## LocalStack + Mailhog

**LocalStack** runs S3 only (other services not needed):

```yaml
localstack:
  image: localstack/localstack:3
  ports: ["4566:4566"]
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
```

**One-shot bucket init** creates the `hdc-local` bucket (and a permissive CORS rule) on first start, so the backend has somewhere to put profile and banner images without manual setup:

```yaml
localstack-init:
  image: amazon/aws-cli:latest
  depends_on: { localstack: { condition: service_healthy } }
  environment:
    AWS_ACCESS_KEY_ID: test
    AWS_SECRET_ACCESS_KEY: test
    AWS_DEFAULT_REGION: us-east-2
  entrypoint: >
    sh -c "
      aws --endpoint-url=http://localstack:4566 s3 mb s3://hdc-local --region us-east-2 || true;
      aws --endpoint-url=http://localstack:4566 s3api put-bucket-cors --bucket hdc-local --cors-configuration '{\"CORSRules\":[{\"AllowedOrigins\":[\"*\"],\"AllowedMethods\":[\"GET\",\"PUT\",\"POST\",\"DELETE\"],\"AllowedHeaders\":[\"*\"]}]}'
    "
```

Implementation step: read `S3Service.java` to confirm the bucket-name env var (likely `AWS_S3_BUCKET` or similar). Match the `.env.example` and init-container bucket name to whatever the backend actually reads.

**Mailhog** is a drop-in SMTP catcher, no config needed:

```yaml
mailhog:
  image: mailhog/mailhog:latest
  ports:
    - "1025:1025"   # SMTP
    - "8025:8025"   # Web UI
```

Backend points `SPRING_MAIL_HOST=mailhog` / `SPRING_MAIL_PORT=1025`. Password reset emails land at `http://localhost:8025`.

**AWS Bedrock and Google OAuth** have no local substitutes. Both degrade gracefully when credentials are missing:

- Bedrock — `AWSConfig.bedrockRuntimeClient()` already returns `null` when creds are unset (`AWSConfig.java:46`). Chat endpoints fail; everything else works.
- Google OAuth — sign-in fails when creds are unset. Email/password signup against the local DB still works.

To use either against real AWS / real Google, the user supplies real credentials in `.env`. With the Bedrock cred split (above), real Bedrock creds do not affect the local S3 endpoint override.

## Configuration

Compose's top-level `env_file: .env` injects vars into every service that opts in via `env_file:`. `spring-dotenv` (`me.paulschwarz:spring-dotenv:4.0.0`) loads `.env` if the backend is ever run on the host instead of in the container — same vars, same effect. `.env` is gitignored (line 24 of root `.gitignore`: `.env*`).

**`.env.example`** (committed):

```bash
# === Postgres ===
POSTGRES_PASSWORD=hdc_local

# === Mail (Mailhog defaults — override if hitting real SMTP) ===
SPRING_MAIL_HOST=mailhog
SPRING_MAIL_PORT=1025
SPRING_MAIL_USERNAME=
SPRING_MAIL_PASSWORD=

# === S3 (LocalStack defaults — leave as-is for local) ===
AWS_S3_ENDPOINT=http://localstack:4566
AWS_S3_BUCKET=hdc-local
AWS_REGION=us-east-2
AWS_ACCESS_KEY_ID=test
AWS_SECRET_ACCESS_KEY=test

# === Bedrock (optional — leave blank to disable chat) ===
# AWS_BEDROCK_ACCESS_KEY_ID=
# AWS_BEDROCK_SECRET_ACCESS_KEY=
# BEDROCK_REGION=us-east-2

# === Google OAuth (optional — leave blank to disable Google sign-in) ===
# GOOGLE_CLIENT_ID=
# GOOGLE_CLIENT_SECRET=

# === JWT signing secret ===
JWT_SECRET=change-me-locally-32-chars-minimum

# === Frontend ===
VITE_API_BASE_URL=http://localhost:8080
```

**Port summary** (host-side):

| Port  | Service       | Notes                                  |
|-------|---------------|----------------------------------------|
| 5432  | Postgres      | `psql -h localhost -U hdc hdc`         |
| 8080  | Backend       | Spring Boot, Swagger at `/swagger-ui`  |
| 5173  | Frontend      | Vite dev server                        |
| 4566  | LocalStack    | S3 endpoint                            |
| 1025  | Mailhog SMTP  | Used by backend                        |
| 8025  | Mailhog UI    | Inspect emails in browser              |

The old `dev.sh` SSH-tunnel flow also binds 5432. Both cannot run simultaneously; compose intentionally takes the port for local dev.

## Developer workflow

**First-time setup** (once per machine):

```
cp .env.example .env
# Optionally edit .env to add real Bedrock or Google OAuth credentials
docker compose build
docker compose up -d
```

First `up` takes 3–5 min (Maven dep download, `npm ci`, image builds). Subsequent `up` is ~10 sec.

**Daily flow:**

```
docker compose up -d                # bring everything online
docker compose logs -f backend      # tail backend logs
docker compose logs -f frontend     # tail frontend logs
docker compose down                 # stop, preserves DB
```

**Code-change loops:**

| Change                       | What to do                                                                       |
|------------------------------|----------------------------------------------------------------------------------|
| Frontend `.tsx`/`.ts`/`.css` | Save file. Vite HMR picks it up automatically (~200ms)                           |
| Backend Java                 | Save file. Run `./mvnw compile` from host (or IDE auto-compile). DevTools restarts in ~2s |
| `pom.xml`                    | `docker compose up -d --build backend`                                           |
| `package.json`               | `docker compose up -d --build frontend`                                          |
| Postgres init SQL            | `docker compose down -v && docker compose up -d` (wipes DB volume)               |

**Common operations:**

```
docker compose exec postgres psql -U hdc hdc                       # psql into local DB
start http://localhost:8025                                        # Mailhog inbox
docker compose exec localstack awslocal s3 ls s3://hdc-local       # inspect S3
docker compose down -v && docker compose up -d                     # wipe everything
docker compose exec backend ./mvnw test                            # run backend tests
```

## Documentation updates

In the same commit as the compose setup:

- **`CLAUDE.md`** — add a "Local development with Docker Compose" section above the existing SSH-tunnel section. Mark compose as the preferred path. Leave the tunnel section and `dev.sh` intact for anyone who still needs to point local code at RDS. Fold troubleshooting notes (port 5432 conflict, HMR polling, DB reset) into this section.
- **`AGENTS.md`** — no changes. Its directives (bd tasks, IMPL prompt template, spec process) are orthogonal to local infra.

## What is explicitly out of scope

- Production Docker images. The `Dockerfile.dev` files are dev-only. A separate spec can later add `Dockerfile` and `docker-compose.prod.yml` if production containerization becomes a goal.
- CI integration. Tests still run via `./mvnw test` / `npm test` directly. Adding compose to CI is a separate task.
- Migrations system (Flyway/Liquibase). `ddl-auto=update` continues to manage schema. Migrations may become necessary later but are not part of this work.
- Removing `dev.sh`. The RDS-tunnel path is retained.
- Frontend production build (nginx-served). Out of scope for daily dev; addressed by the future production-Docker spec.
- Pre-seeded users or sample deals. Local DB starts empty; users sign up through the app.

## Files added by this change

```
docker-compose.yml
.env.example
docker/postgres/Dockerfile
docker/postgres/init/01-extensions-and-schemas.sql
backend/Dockerfile.dev
backend/.dockerignore
frontend/Dockerfile.dev
frontend/.dockerignore
```

## Files modified by this change

```
CLAUDE.md                                                              (new local-dev section)
backend/src/main/java/com/hdc/hdc_map_backend/config/AWSConfig.java    (Bedrock cred split)
backend/src/main/java/com/hdc/hdc_map_backend/service/S3Service.java   (endpoint override)
```
