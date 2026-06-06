# Multi-Stack Friendly Port Bindings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let multiple docker-compose stacks coexist on the same host. Frontend / Postgres / Mailhog UI become env-templated with current defaults. LocalStack and Mailhog SMTP lose their host bindings (only the backend container uses them, via Docker DNS).

**Architecture:** Three remaining host-bound ports use `${VAR:-default}` interpolation in `docker-compose.yml`. Two ports drop their host binding and gain a documentary `expose:` entry. Compose's default `COMPOSE_PROJECT_NAME` (parent directory name) provides per-worktree container/network namespacing automatically — no env config needed for that.

**Tech Stack:** Docker Compose env interpolation. No code changes.

**Spec:** `docs/superpowers/specs/2026-06-06-multi-stack-ports-design.md`

**Verification model:** Manual smoke checks per task. The full multi-stack acceptance is Task 5 (assumes a secondary worktree is available; the executor should set one up or skip steps 2-3 of Task 5 with a note).

**Local note (this machine only):** Default Docker context is `stillmatic` (remote SSH, offline). Either run `docker context use desktop-linux` once at the start, or prefix every command with `docker --context desktop-linux compose ...`. This plan uses plain `docker compose` for brevity.

**Pre-flight:** The stack can be either up or down at the start. Each task force-recreates the services it changes, so state at task boundaries is consistent. Task 5 wants a cold start anyway.

---

### Task 1: Drop host bindings for LocalStack and Mailhog SMTP

These services are only consumed by the backend container, which reaches them via Docker network DNS (`http://localstack:4566`, `mailhog:1025`). Removing the host bindings frees those ports across all stacks and is the biggest single-task win.

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: In `docker-compose.yml`, replace the `localstack` service's `ports` block with `expose`**

Find this block (under `localstack:`):
```yaml
    ports:
      - "4566:4566"
```

Replace with:
```yaml
    expose:
      - "4566"
```

Leave the `image`, `environment`, `volumes`, `healthcheck`, and `networks` keys untouched.

- [ ] **Step 2: In the same file, modify the `mailhog` service's `ports` block**

Find:
```yaml
    ports:
      - "1025:1025"
      - "8025:8025"
```

Replace with:
```yaml
    ports:
      - "8025:8025"
    expose:
      - "1025"
```

(The `8025:8025` line stays because it's still host-bound; Task 2 will template it. The `1025` line moves from `ports` to `expose`.)

- [ ] **Step 3: Recreate the affected services**

Run:
```
docker compose up -d --force-recreate localstack mailhog
```

Expected: both containers recreate without errors. `mailhog` shows only the 8025 host binding now; `localstack` shows no host bindings. The init container for LocalStack (`localstack-init`) may also recreate — that's fine.

- [ ] **Step 4: Verify host ports are gone but internal connectivity is preserved**

From host:
```
docker compose ps
```

Expected: `localstack` row has no port number after the container port (just `4566/tcp`, no `0.0.0.0:4566->...`). `mailhog` row has `0.0.0.0:8025->8025/tcp` but no `1025` mapping.

From inside the backend container:
```
docker compose exec backend curl -fs http://localstack:4566/_localstack/health
```

Expected: JSON payload showing s3 service status. Confirms backend can still reach LocalStack via Docker DNS.

```
docker compose exec backend sh -c "echo 'EHLO test' | nc -q 1 mailhog 1025"
```

Expected: SMTP banner from Mailhog (something like `220 mailhog.example ESMTP MailHog`). Confirms backend can still reach Mailhog SMTP.

(If `nc` isn't installed in the backend image, skip this sub-check — the host-side `docker compose ps` already confirms the port mapping is gone, and the SMTP path is exercised by the EmailService at runtime if needed.)

From host, confirm the dropped bindings really are dropped:
```
curl -s -o NUL -w "%{http_code}\n" --max-time 2 http://localhost:4566 2>&1
```

Expected: connection refused or timeout (NOT `200`). The host can no longer reach LocalStack directly. Same check for SMTP would need a different tool; the `docker compose ps` output is sufficient.

- [ ] **Step 5: Commit**

```
git add docker-compose.yml
git commit -m "feat(docker): drop host bindings for localstack and mailhog SMTP"
```

---

### Task 2: Template the three remaining host bindings

Make frontend, Postgres, and Mailhog UI host ports overridable via env vars with current values as defaults. Primary worktree behavior is unchanged.

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: Template the `frontend` service's port**

Find (under `frontend:`):
```yaml
    ports:
      - "5173:5173"
```

Replace with:
```yaml
    ports:
      - "${FRONTEND_PORT:-5173}:5173"
```

- [ ] **Step 2: Template the `postgres` service's port**

Find (under `postgres:`):
```yaml
    ports:
      - "5432:5432"
```

Replace with:
```yaml
    ports:
      - "${POSTGRES_PORT:-5432}:5432"
```

- [ ] **Step 3: Template the `mailhog` service's port**

Find:
```yaml
    ports:
      - "8025:8025"
```

Replace with:
```yaml
    ports:
      - "${MAILHOG_UI_PORT:-8025}:8025"
```

The `expose: ["1025"]` line added in Task 1 stays as-is.

- [ ] **Step 4: Recreate the three affected services**

Run:
```
docker compose up -d --force-recreate frontend postgres mailhog
```

Expected: all three containers recreate without errors. Defaults apply because no `FRONTEND_PORT`, `POSTGRES_PORT`, or `MAILHOG_UI_PORT` are set in `.env` yet.

- [ ] **Step 5: Verify defaults still work**

```
docker compose ps
```

Expected: frontend on `0.0.0.0:5173->5173/tcp`, postgres on `0.0.0.0:5432->5432/tcp`, mailhog on `0.0.0.0:8025->8025/tcp`. Identical to behavior before this task.

```
curl -s -o NUL -w "frontend %{http_code}\n" http://localhost:5173
curl -s -o NUL -w "mailhog %{http_code}\n" http://localhost:8025
```

Expected: both return `200`. Postgres host check requires `psql` or `pg_isready` — skip if not available; the `docker compose ps` mapping is sufficient evidence.

- [ ] **Step 6: Commit**

```
git add docker-compose.yml
git commit -m "feat(docker): template frontend, postgres, mailhog UI host ports"
```

---

### Task 3: Document port overrides in `.env.example`

Make the override mechanism discoverable. New checkouts and worktree-setup will see the commented-out section and know what to set.

**Files:**
- Modify: `.env.example`

- [ ] **Step 1: Open `.env.example` and find the `# === Frontend ===` section**

The current Frontend section ends with the `VITE_API_BASE_URL=/api` line. Find this block:

```
# === Frontend ===
# Same-origin: browser hits Vite at :5173, Vite proxies /api/* to the backend
# container. Keeps port 8080 free on the host (e.g. for llamacpp).
VITE_API_BASE_URL=/api
```

- [ ] **Step 2: Append a new "Multi-worktree port overrides" section after the Frontend section**

Add at the bottom of `.env.example`:

```

# === Multi-worktree port overrides ===
# Uncomment and set these in a SECONDARY worktree's .env so its stack can
# coexist with the primary stack on the same host. Pick offsets that don't
# collide with anything else on your machine (e.g., +1 for the first
# secondary worktree, +2 for the next).
# FRONTEND_PORT=5174
# POSTGRES_PORT=5433
# MAILHOG_UI_PORT=8026
```

- [ ] **Step 3: Verify the file looks right**

```
git diff .env.example
```

Expected: only the new section appended at the end. No other lines changed.

- [ ] **Step 4: Commit**

```
git add .env.example
git commit -m "docs: document multi-worktree port overrides in .env.example"
```

---

### Task 4: Update `CLAUDE.md`

Reflect the new architecture: service URL table updates, a brief "Running multiple worktrees" subsection, a troubleshooting line, and notes about the dropped host bindings.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the service URL table**

Find this block in `CLAUDE.md`:

```markdown
| Service     | URL                              | Notes                              |
|-------------|----------------------------------|------------------------------------|
| Frontend    | http://localhost:5173            | Vite dev server, HMR enabled       |
| Backend API | http://localhost:5173/api/...    | Reached through the Vite proxy; container has no host port |
| Postgres    | localhost:5432 (user `hdc`)      | DB name `hdc`                      |
| Mailhog UI  | http://localhost:8025            | Catches all outbound SMTP          |
| LocalStack  | http://localhost:4566            | S3 endpoint                        |
```

Replace with:

```markdown
| Service     | URL                                            | Notes                                       |
|-------------|------------------------------------------------|---------------------------------------------|
| Frontend    | http://localhost:${FRONTEND_PORT:-5173}        | Vite dev server, HMR enabled                |
| Backend API | http://localhost:${FRONTEND_PORT:-5173}/api/...| Reached through the Vite proxy; container has no host port |
| Postgres    | localhost:${POSTGRES_PORT:-5432} (user `hdc`)  | DB name `hdc`                               |
| Mailhog UI  | http://localhost:${MAILHOG_UI_PORT:-8025}      | Catches all outbound SMTP                   |
| LocalStack  | not host-bound — backend reaches via DNS       | `docker compose exec backend curl http://localstack:4566/...` for debugging |
```

- [ ] **Step 2: Update the paragraph just below the table**

Find this block:

```markdown
The backend container is not published on the host. Browsers and host-side
curls reach it through Vite at `http://localhost:5173/api/...`. Port 8080 on
the host is intentionally left free (e.g. for llamacpp). To hit the backend
directly for debugging, either `docker compose exec backend curl ...` or
temporarily add a `8081:8080` mapping to `docker-compose.yml`.
```

Replace with:

```markdown
The backend container is not published on the host. Browsers and host-side
curls reach it through Vite at `http://localhost:${FRONTEND_PORT:-5173}/api/...`.
Port 8080 on the host is intentionally left free (e.g. for llamacpp). To hit
the backend directly for debugging, either `docker compose exec backend curl ...`
or temporarily add a `8081:8080` mapping to `docker-compose.yml`.

Mailhog SMTP (1025) and LocalStack (4566) are no longer host-bound either —
they're only used by the backend container, which reaches them via Docker DNS.
If you need host access to either (e.g. `aws --endpoint-url` from the host),
temporarily add the `ports:` line back to `docker-compose.yml`.
```

- [ ] **Step 3: Add a "Running multiple worktrees" subsection**

Find the existing `**Reset the local DB:**` line. Insert a new subsection *before* it:

```markdown
**Running multiple worktrees:** Each worktree's directory is its own
`COMPOSE_PROJECT_NAME` so containers, networks, and volumes are isolated
automatically. To let two worktrees' stacks coexist, set
`FRONTEND_PORT`, `POSTGRES_PORT`, and `MAILHOG_UI_PORT` in the secondary
worktree's `.env` (see `.env.example` for the template). Typical pattern:
+1 offset for the first secondary worktree, +2 for the next.

```

(Make sure there's a blank line before and after the new block so it renders as a paragraph.)

- [ ] **Step 4: Add a troubleshooting line**

Find the `**Troubleshooting:**` list. Insert a new bullet right after the existing `port 5432 already in use` line:

```markdown
- "bind: address already in use" on a worktree stack — another worktree (or system service) already grabbed that port. Bump `FRONTEND_PORT`, `POSTGRES_PORT`, or `MAILHOG_UI_PORT` in this worktree's `.env` to a free number.
```

- [ ] **Step 5: Verify the file edits**

```
git diff CLAUDE.md
```

Expected: diff shows the four changes above (table replacement, paragraph replacement, new subsection, new troubleshooting bullet). No other changes.

- [ ] **Step 6: Commit**

```
git add CLAUDE.md
git commit -m "docs: document multi-worktree port overrides in CLAUDE.md"
```

---

### Task 5: Acceptance run-through

Manual verification of the five acceptance criteria from the spec. No commit — this confirms everything composed correctly.

**Files:** none

- [ ] **Step 1: Primary stack on defaults**

In the primary worktree:
```
docker compose down
docker compose watch
```

Wait for the watch output to show all services started. Then verify:
- Browser to `http://localhost:5173` loads the frontend.
- `curl -s -o NUL -w "%{http_code}\n" http://localhost:8025` returns `200`.
- `docker compose exec postgres pg_isready -U hdc -d hdc` returns `accepting connections`.

Spec acceptance criterion 1.

- [ ] **Step 2: Secondary worktree with offsets (skip if no secondary worktree available)**

If you don't have a second worktree of this repo handy: create one with `git worktree add ../hdc-tax-calc-alt feat/docker-compose-local-dev` (or any branch), then `cp .env.example .env` inside that worktree.

In the secondary worktree, edit its `.env` to uncomment and set:
```
FRONTEND_PORT=5174
POSTGRES_PORT=5433
MAILHOG_UI_PORT=8026
```

Bring it up (use a project name to avoid container collisions with the primary even though the dirs differ):
```
docker compose watch
```

Wait for the secondary stack to come up. Then verify:
- Browser to `http://localhost:5174` loads the secondary frontend (note: separate React state from the primary — they're fully independent stacks).
- `curl -s -o NUL -w "%{http_code}\n" http://localhost:8026` returns `200`.
- `docker compose -p <secondary-project-name> exec postgres pg_isready -U hdc -d hdc` returns `accepting connections`.

Spec acceptance criterion 2.

- [ ] **Step 3: Both running simultaneously**

With both stacks still up:
```
docker compose ls
```

Expected: two projects listed, both with `running(6)` or `running(7)` status. Different project names (typically derived from the parent directory).

```
docker network ls | grep hdc-net
```

Expected: two distinct networks, one per project (e.g., `hdc-tax-calc_hdc-net` and `hdc-tax-calc-alt_hdc-net`).

Confirm both frontends respond:
```
curl -s -o NUL -w "primary %{http_code}, secondary " http://localhost:5173
curl -s -o NUL -w "%{http_code}\n" http://localhost:5174
```

Expected: `primary 200, secondary 200`.

Spec acceptance criterion 3.

- [ ] **Step 4: Internal connectivity preserved**

In each stack's backend container, confirm DNS-based access to LocalStack works (this is the path the application uses for S3 calls):
```
docker compose exec backend curl -fs http://localstack:4566/_localstack/health
```

Expected: JSON health payload returned for both the primary and secondary backends. Confirms the `expose:` change in Task 1 didn't break internal connectivity.

Spec acceptance criterion 4.

- [ ] **Step 5: Conflict detection**

Edit the secondary worktree's `.env` and intentionally set `FRONTEND_PORT=5173` (same as primary). Restart its frontend:
```
docker compose up -d --force-recreate frontend
```

Expected: container fails to start with a clear `bind: address already in use` error. Restore the secondary's `.env` to `FRONTEND_PORT=5174` and retry — succeeds.

Spec acceptance criterion 5.

- [ ] **Step 6: Tear down the secondary stack**

In the secondary worktree:
```
docker compose down
```

Optional: remove the secondary worktree if it was created just for this test:
```
git worktree remove ../hdc-tax-calc-alt
```

- [ ] **Step 7: Final cleanup check**

In the primary worktree:
```
git status
```

Expected: clean working tree. Primary stack can stay running (or `docker compose down` if you're done).

If anything is dirty, `git checkout --` it and figure out what reverted incompletely.

---

## Self-review notes

Coverage check against the spec:
- **Port binding table (5 rows):** Task 1 covers the two dropped ports; Task 2 covers the three templated ports.
- **Per-worktree behavior (primary defaults / secondary overrides):** documented in Task 3 (.env.example) and verified in Task 5 steps 1-2.
- **Escape hatches (LocalStack/Mailhog SMTP):** documented in Task 4 step 2.
- **`COMPOSE_PROJECT_NAME` stays implicit:** Task 4 step 3 mentions it in the "Running multiple worktrees" subsection.
- **Error handling (port conflict):** Task 4 step 4 troubleshooting line + Task 5 step 5 verification.
- **Testing (5 acceptance criteria):** Task 5 steps 1-5 cover them one-to-one.
- **Out of scope items** (auto-offset script, persistent escape-hatch bindings, backwards-compat shim) — correctly absent from the plan.

No placeholders, no `TBD`, no "implement similar to Task N". Each task carries its own exact YAML/markdown snippets.

One ambiguity worth calling out for the executor: Task 5 step 2 requires a secondary worktree. If unavailable, that step (and steps 3-5 which depend on it) can be skipped with a noted deviation — the primary-stack-only behavior is already validated by Task 5 step 1.
