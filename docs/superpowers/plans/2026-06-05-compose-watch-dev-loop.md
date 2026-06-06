# Docker Compose Watch Dev Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the bind-mount + polling dev workflow with `docker compose watch`. Backend gets automatic compile-on-save via `sync+exec → mvn compile → Spring DevTools restart`. Frontend drops `CHOKIDAR_USEPOLLING` for host-driven sync that produces real inotify events inside the container.

**Architecture:** Both services declare `develop.watch` blocks in `docker-compose.yml`. Bind mounts on synced paths are removed (compose watch sync replaces them; the image build copies source in so the container is self-contained at startup). Source is pre-compiled in the backend image to make first-start fast. The user runs `docker compose watch` instead of `docker compose up -d`.

**Tech Stack:** Docker Compose 2.22+ (watch action), Spring Boot DevTools (already on the classpath), Vite + chokidar (default inotify, no polling needed).

**Spec:** `docs/superpowers/specs/2026-06-05-compose-watch-dev-loop-design.md`

**Verification model:** This is infrastructure plumbing — no new unit tests. Each task ends with a concrete manual check (build, curl, edit-and-observe). The full acceptance run-through is Task 7.

**Local note (this machine only):** Default Docker context is `stillmatic` (remote SSH, offline). Either run `docker context use desktop-linux` once before starting, or prefix every `docker compose` command with `--context desktop-linux`. The plan uses plain `docker compose` for brevity.

---

### Task 1: Add `.dockerignore` files

Prevents host-side build artifacts and IDE caches from getting baked into the dev images when later tasks add `COPY` of the whole project dir. Must come before Tasks 2 and 3.

**Files:**
- Create: `backend/.dockerignore`
- Create: `frontend/.dockerignore`

- [ ] **Step 1: Create `backend/.dockerignore`**

```
target/
.git/
.idea/
*.log
*.iml
```

- [ ] **Step 2: Create `frontend/.dockerignore`**

```
node_modules/
dist/
.git/
.vscode/
.idea/
*.log
coverage/
```

- [ ] **Step 3: Verify both files exist with expected contents**

Run:
```
cat backend/.dockerignore frontend/.dockerignore
```

Expected: contents of both files printed in full. No errors.

- [ ] **Step 4: Commit**

```
git add backend/.dockerignore frontend/.dockerignore
git commit -m "chore(docker): add .dockerignore for backend and frontend"
```

---

### Task 2: Bake source + pre-compile into backend image

Without this, removing the bind mount in Task 4 leaves the container with no `src/`. Pre-compiling makes the first `docker compose watch` start avoid a ~30s cold compile.

**Files:**
- Modify: `backend/Dockerfile.dev`

- [ ] **Step 1: Edit `backend/Dockerfile.dev` — replace its full contents with the version below**

```dockerfile
# backend/Dockerfile.dev
FROM eclipse-temurin:17-jdk
WORKDIR /app

COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q dependency:go-offline

COPY src ./src
RUN ./mvnw -q compile

EXPOSE 8080
CMD ["./mvnw", "spring-boot:run", "-Dspring-boot.run.fork=false"]
```

The two new lines (`COPY src ./src` and `RUN ./mvnw -q compile`) sit *after* the dependency-cache step so dep changes don't bust the layer that copies source.

- [ ] **Step 2: Rebuild the backend image**

Run:
```
docker compose build backend
```

Expected: build finishes successfully. The new `RUN ./mvnw -q compile` step runs and produces no errors (warnings about deprecated APIs are fine; we want exit code 0).

- [ ] **Step 3: Start the stack and verify the backend still serves**

Run:
```
docker compose up -d
```

Expected: all services start. Then poll:
```
until docker compose exec -T backend curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/public/login | grep -qE "^(401|403|405)$"; do sleep 2; done && echo "backend ready"
```

Expected: `backend ready` prints within ~60s. The bind mount from compose still shadows the baked-in source at this point, so behavior is identical to before — that's the point of the gradual rollout.

- [ ] **Step 4: Commit**

```
git add backend/Dockerfile.dev
git commit -m "feat(docker): bake backend source and pre-compile into dev image"
```

---

### Task 3: Bake source into frontend image

Same rationale as Task 2 — Task 5 removes the bind mount, so the image needs its own source.

**Files:**
- Modify: `frontend/Dockerfile.dev`

- [ ] **Step 1: Edit `frontend/Dockerfile.dev` — replace its full contents with the version below**

```dockerfile
# frontend/Dockerfile.dev
FROM node:22-alpine
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .

EXPOSE 5173
CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0"]
```

The `COPY . .` comes *after* `npm ci` so changes to source don't bust the install cache. The `.dockerignore` from Task 1 prevents `node_modules/`, `dist/`, etc. from being included.

- [ ] **Step 2: Rebuild the frontend image**

Run:
```
docker compose build frontend
```

Expected: build finishes successfully.

- [ ] **Step 3: Recreate the frontend container and verify Vite serves**

Run:
```
docker compose up -d --force-recreate frontend
```

Then:
```
curl -o NUL -s -w "%{http_code}\n" http://localhost:5173
```

(PowerShell: use `curl.exe`. Bash: `curl -o /dev/null`.)

Expected: `200`.

- [ ] **Step 4: Commit**

```
git add frontend/Dockerfile.dev
git commit -m "feat(docker): bake frontend source into dev image"
```

---

### Task 4: Switch backend to compose watch

Remove the `src` / `pom.xml` bind mounts and add a `develop.watch` block. After this task, file changes only propagate when `docker compose watch` is running.

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: In `docker-compose.yml`, replace the `backend.volumes` block and add `develop`**

Find the current block (under `backend:`):
```yaml
    volumes:
      - ./backend/src:/app/src
      - ./backend/pom.xml:/app/pom.xml
      - maven-repo:/root/.m2
```

Replace with:
```yaml
    volumes:
      - maven-repo:/root/.m2
    develop:
      watch:
        - action: sync+exec
          path: ./backend/src
          target: /app/src
          exec:
            command: ./mvnw -q compile
        - action: rebuild
          path: ./backend/pom.xml
```

Leave the existing `expose`, `env_file`, `environment`, `depends_on`, and `networks` keys untouched. Indentation under `backend:` should match the other top-level keys (typically 4 spaces from the service name).

- [ ] **Step 2: Recreate the backend container with the new compose config**

Run:
```
docker compose up -d --force-recreate backend
```

Expected: backend container is recreated and reaches healthy state. The bind mounts are gone now — the container is running off the baked-in source from Task 2.

Verify:
```
until docker compose exec -T backend curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/public/login | grep -qE "^(401|403|405)$"; do sleep 2; done && echo "backend ready"
```

Expected: `backend ready` within ~30s (faster than first boot because the image was pre-compiled).

- [ ] **Step 3: Start `docker compose watch` in the foreground and verify the sync+exec fires on a Java edit**

In one terminal:
```
docker compose watch
```

Expected: watch attaches, prints something like "watch enabled" for backend and frontend (frontend watch block is added in Task 5 — for now the backend block is the only one configured; that's fine).

In a second terminal, edit `backend/src/main/java/com/hdc/hdc_map_backend/HdcMapBackendApplication.java` — add a no-op log line at the end of `main`, e.g.:

```java
System.out.println("dev loop verified");
```

Save the file.

Expected in the watch terminal: a sync event for `backend/src/main/java/.../HdcMapBackendApplication.java`, followed by Maven compile output, followed by Spring DevTools restart logs. End-to-end under ~10s.

Revert the no-op edit, save again — second compile + restart cycle should fire identically.

- [ ] **Step 4: Stop the watch (Ctrl+C in the watch terminal), revert any leftover edits**

Run:
```
git checkout -- backend/src/main/java/com/hdc/hdc_map_backend/HdcMapBackendApplication.java
```

Expected: no diff remains in that file.

- [ ] **Step 5: Commit**

```
git add docker-compose.yml
git commit -m "feat(docker): switch backend to compose watch sync+exec"
```

---

### Task 5: Switch frontend to compose watch

Remove the `./frontend:/app` bind mount, drop `CHOKIDAR_USEPOLLING`, add a `develop.watch` block.

**Files:**
- Modify: `docker-compose.yml`

- [ ] **Step 1: In `docker-compose.yml`, modify the `frontend` service**

Find the current `frontend.environment` block:
```yaml
    environment:
      VITE_API_BASE_URL: /api
      VITE_BACKEND_PROXY_TARGET: http://backend:8080
      CHOKIDAR_USEPOLLING: "true"
```

Remove the `CHOKIDAR_USEPOLLING` line:
```yaml
    environment:
      VITE_API_BASE_URL: /api
      VITE_BACKEND_PROXY_TARGET: http://backend:8080
```

Find the current `frontend.volumes` block:
```yaml
    volumes:
      - ./frontend:/app
      - frontend-node-modules:/app/node_modules
```

Replace with:
```yaml
    volumes:
      - frontend-node-modules:/app/node_modules
    develop:
      watch:
        - action: sync
          path: ./frontend
          target: /app
          ignore:
            - node_modules/
            - dist/
            - .git/
            - package.json
            - package-lock.json
        - action: rebuild
          path: ./frontend/package.json
```

Leave `build`, `depends_on`, `ports`, and `networks` untouched.

- [ ] **Step 2: Recreate the frontend container**

Run:
```
docker compose up -d --force-recreate frontend
```

Verify:
```
curl -o NUL -s -w "%{http_code}\n" http://localhost:5173
```

Expected: `200`. Frontend is running off baked-in source from Task 3.

- [ ] **Step 3: Start `docker compose watch` and verify a `.tsx` edit triggers HMR**

In one terminal:
```
docker compose watch
```

Expected: watch attaches and prints sync events for both backend and frontend now.

Open `http://localhost:5173` in a browser. Open DevTools → Network → preserve log + filter by WS to watch the Vite HMR websocket.

In a second terminal, edit `frontend/src/App.tsx` (or whatever file is currently rendered at `/`). Change a visible string (e.g. add an extra word to a button label).

Save.

Expected in the watch terminal: sync event for the edited file. Expected in the browser: the change appears within ~500ms, no full page reload (the HMR websocket frame fires, the component re-renders). The DevTools Network panel should show an `[HMR]` update message in the websocket, not a fresh document fetch.

Revert the edit (Ctrl+Z or `git checkout --`), save, confirm it reverts in the browser the same way.

- [ ] **Step 4: Stop the watch, ensure no leftover edits**

Run:
```
git status
```

Expected: only `docker-compose.yml` modified. No frontend source files modified.

- [ ] **Step 5: Commit**

```
git add docker-compose.yml
git commit -m "feat(docker): switch frontend to compose watch, drop chokidar polling"
```

---

### Task 6: Update `CLAUDE.md`

Reflect the new dev workflow in the docs. Drop the manual `./mvnw compile` instruction. Make the Compose 2.22+ requirement explicit.

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: In `CLAUDE.md`, locate the "Starting everything with Docker Compose (preferred)" section**

Find this block:
```markdown
```bash
cp .env.example .env
docker compose up -d
```

Brings up Postgres, backend, frontend, LocalStack (S3 stub), and Mailhog (SMTP catcher) on one bridge network. Hibernate's `ddl-auto=update` creates the schema on first boot.
```

Replace with:
```markdown
```bash
cp .env.example .env
docker compose watch
```

Brings up Postgres, backend, frontend, LocalStack (S3 stub), and Mailhog (SMTP catcher) on one bridge network, and attaches a host-side file watcher in the foreground. Hibernate's `ddl-auto=update` creates the schema on first boot. Requires Docker Compose 2.22+ for the `watch` action — check `docker compose version` if anything fails to start.

Ctrl+C stops the watcher *and* the containers cleanly. To run the stack in the background instead, `docker compose up -d` and then `docker compose watch` in a separate terminal — that variant only attaches the watcher.
```

- [ ] **Step 2: In the same file, locate the "Hot reload" section**

Find this block:
```markdown
**Hot reload:**
- Frontend: save a file → Vite HMR (~200ms).
- Backend: save a Java file → run `docker compose exec backend ./mvnw -q compile` → DevTools restarts (~5s).
```

Replace with:
```markdown
**Hot reload:**
- Frontend: save a file → compose watch syncs it into the container → Vite HMR (~200ms).
- Backend: save a Java file → compose watch syncs it into the container and runs `mvn compile` → Spring DevTools restarts (~5s). No manual compile step needed.
- `pom.xml` or `package.json` change: compose watch rebuilds the affected container (~30s).
```

- [ ] **Step 3: Verify the file edits**

Run:
```
git diff CLAUDE.md
```

Expected: diff shows the two replacements above, no other changes.

- [ ] **Step 4: Commit**

```
git add CLAUDE.md
git commit -m "docs: update local dev instructions for compose watch"
```

---

### Task 7: Full acceptance run-through

Walk through the seven acceptance criteria from the spec on a clean stack. No commit — this is verification that everything composed correctly.

**Files:** none

- [ ] **Step 1: Bring everything down clean**

Run:
```
docker compose down
```

Expected: all containers stop, network removed. Named volumes (`postgres-data`, `maven-repo`, `frontend-node-modules`, `localstack-data`) survive — that's intentional, we're testing the watch loop, not the seeder.

- [ ] **Step 2: Cold-start the stack with watch and time it**

Run:
```
docker compose watch
```

Expected: backend reaches healthy state in under 60s on the dev machine (validates the pre-bake from Task 2). Watch attaches and prints "watch enabled" for backend and frontend.

This validates spec acceptance criterion 7 (first-boot speed).

- [ ] **Step 3: Backend hot reload**

In a second terminal, edit `backend/src/main/java/com/hdc/hdc_map_backend/controller/UserController.java`. Find this line in the `register` method:

```java
return ResponseEntity.ok(userRepo.save(user));
```

Temporarily change it to log a marker before returning:

```java
System.out.println("register marker 7-3");
return ResponseEntity.ok(userRepo.save(user));
```

Save.

Expected in the watch terminal: sync event → `mvn compile` output → Spring DevTools restart logs. Under ~5s.

Verify:
```
curl -s -X POST http://localhost:5173/api/public/register -H "Content-Type: application/json" -d "{\"username\":\"t7-3@hdc.local\",\"password\":\"testpass\",\"fullName\":\"T 7-3\"}"
```

Expected: 200 response, and `register marker 7-3` visible in `docker compose logs backend --tail=20`.

Revert the edit:
```
git checkout -- backend/src/main/java/com/hdc/hdc_map_backend/controller/UserController.java
```

Spec acceptance criterion 1.

- [ ] **Step 4: Frontend HMR**

Open `http://localhost:5173` in a browser with DevTools open (Network panel filtered to WS).

Edit a visible string in `frontend/src/App.tsx`. Save.

Expected: change appears in the browser within ~500ms. Network panel shows HMR websocket frames, not a fresh document fetch.

Revert with `git checkout -- frontend/src/App.tsx`.

Spec acceptance criterion 2.

- [ ] **Step 5: Backend syntax error**

Edit `backend/src/main/java/com/hdc/hdc_map_backend/HdcMapBackendApplication.java`. Introduce a deliberate syntax error — e.g. remove a closing brace.

Save.

Expected in the watch terminal: sync event → `mvn compile` output with a clear compile error → no DevTools restart. Backend continues serving the previous code:
```
curl -s -o NUL -w "%{http_code}\n" http://localhost:5173/api/public/login -X POST -d "{}"
```
Expected: still `401` or `403` (i.e. backend up).

Revert with `git checkout -- backend/src/main/java/com/hdc/hdc_map_backend/HdcMapBackendApplication.java`. Compile succeeds, DevTools restarts.

Spec acceptance criterion 3.

- [ ] **Step 6: pom.xml change triggers rebuild**

Edit `backend/pom.xml` — add a harmless XML comment near the top, e.g.:

```xml
<!-- acceptance test 7-6 -->
```

Save.

Expected in the watch terminal: a `Rebuilding service "backend"` message; backend container is recreated. ~30s downtime. Eventually backend is healthy again.

Verify:
```
docker compose exec -T backend curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/public/login
```
Expected: `401` or `403`.

Revert with `git checkout -- backend/pom.xml`.

Spec acceptance criterion 4.

- [ ] **Step 7: package.json change triggers rebuild**

Edit `frontend/package.json` — change a description field or add a harmless `"//"` comment-like key with a string value (npm tolerates unknown top-level keys). Or simply add a trailing newline at the very end of the file (a real change to a file with no source impact).

Actually, the cleanest test: bump the `version` field by a patch level (e.g. `0.0.0` → `0.0.1`). Save.

Expected: frontend container rebuilds, ~30s downtime. After it comes back, `curl http://localhost:5173` returns `200`.

Revert with `git checkout -- frontend/package.json`.

Spec acceptance criterion 5.

- [ ] **Step 8: Clean shutdown**

In the watch terminal, press Ctrl+C.

Expected: watch detaches, containers stop within ~10s. `docker compose ps` shows nothing running.

Spec acceptance criterion 6.

- [ ] **Step 9: Final cleanup check**

Run:
```
git status
```

Expected: clean working tree. No leftover acceptance edits in tracked files.

If anything is dirty, `git checkout --` it and figure out what reverted incompletely.

---

## Self-review notes

Coverage check against the spec:
- **Architecture (diagram + behavior):** Tasks 2–5 implement it.
- **File changes table:** every row in the spec table maps to a step — `docker-compose.yml` (Tasks 4–5), `Dockerfile.dev` (Tasks 2–3), `.dockerignore` (Task 1), `CLAUDE.md` (Task 6).
- **Workflow change:** Task 4 step 3 introduces `docker compose watch`; Task 6 documents it.
- **Error handling:** Task 7 steps 5 and 6 exercise the compile-error and pom.xml-rebuild paths.
- **Testing (7 acceptance criteria):** All seven covered in Task 7.
- **Out of scope:** spec lists production images, test-source watching, remote debugger, IDE compilation, version-fallback. None of these need plan tasks.
- **Rejected alternatives:** spec lists watchexec/entr/inotifywait/fizzed/remote-devtools/IDE-mount — none implemented, as intended.

No placeholders, no `TBD`, no "implement similar to Task N" — each task carries its own code/commands.
