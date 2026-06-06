# Docker Compose Watch Dev Loop — Design

**Date:** 2026-06-05
**Status:** Approved design, pending implementation plan
**Scope:** Replace the current bind-mount + polling dev workflow with `docker compose watch`. Backend gets automatic compile-on-save (no more manual `./mvnw compile`). Frontend drops `CHOKIDAR_USEPOLLING` in favor of host-driven sync.

## Motivation

The docker-compose stack we shipped works, but the dev loop has two friction points:

1. **Backend has no hot reload.** Editing a `.java` file requires `docker compose exec backend ./mvnw -q compile` for Spring Boot DevTools to notice. The file change reaches the container via bind mount, but nothing inside the container compiles it.
2. **Frontend depends on `CHOKIDAR_USEPOLLING`.** Chokidar polls the bind-mounted source because inotify doesn't propagate across Docker Desktop's filesystem boundary on Mac or Windows. It works, but it's CPU-wasteful and the poll interval introduces lag.

Docker Compose 2.22 (October 2023) added `docker compose watch` — a host-side watcher that detects file changes and pushes them into containers via the Docker daemon, then optionally runs commands. This sidesteps the inotify-on-bind-mount problem entirely, gives us a clean place to hook Maven compilation, and uses one mechanism for both services.

The host has Compose v5.1.3, well past the floor.

## Decisions (locked in during brainstorming)

1. **Use `docker compose watch` for both services.** One paradigm. Frontend doesn't need its own watcher (Vite + chokidar still handle HMR; compose watch only delivers the file). Backend uses `sync+exec` to trigger Maven compile.
2. **Spring Boot DevTools handles the restart.** Compose watch produces `.class` files in `target/classes`; DevTools sees them and fires its two-classloader restart. DevTools is already a dependency.
3. **Drop bind mounts on the synced paths.** Bind mounts and compose watch sync are redundant — the sync action does the same job using a different channel. Keeping both works but is confusing. Image build copies source in at build time; watch keeps it current after.
4. **Drop `CHOKIDAR_USEPOLLING`.** Sync writes are real filesystem events inside the container; standard inotify picks them up.
5. **Manual acceptance only.** No automated tests for the watch loop itself — it's infrastructure plumbing, not application code.

## Architecture

```
host filesystem                  Docker daemon                    container
─────────────────                ─────────────                    ─────────
backend/src/Foo.java  ──────►   compose watcher  ──sync──►  /app/src/Foo.java
                                       │
                                       └──exec──►  ./mvnw -q compile
                                                          │
                                                          ▼
                                                  target/classes/Foo.class
                                                          │
                                                          ▼
                                                  Spring Boot DevTools
                                                  (already running)
                                                          │
                                                          ▼
                                                  hot restart (~2s)

frontend/src/Bar.tsx  ──────►   compose watcher  ──sync──►  /app/src/Bar.tsx
                                                          │
                                                          ▼
                                                  Vite + chokidar
                                                  (inotify on real write)
                                                          │
                                                          ▼
                                                  HMR (immediate)
```

The watcher runs on the host (as part of `docker compose watch`). Each save triggers exactly one sync. Backend additionally runs Maven inside the container after each sync; frontend doesn't need an `exec` step because Vite already watches `/app/src` inside the container.

## File changes

| File | Change |
|---|---|
| `docker-compose.yml` | Drop `./backend/src` and `./backend/pom.xml` bind mounts from `backend.volumes`. Drop `./frontend:/app` bind mount from `frontend.volumes`. Drop `CHOKIDAR_USEPOLLING` env. Add `develop.watch` blocks to both services. Named volumes (`maven-repo`, `frontend-node-modules`, `postgres-data`, `localstack-data`) all stay. |
| `backend/Dockerfile.dev` | Add `COPY src ./src` after the dep-cache step so the image has source baked in. Add `RUN ./mvnw -q compile` so the first container start doesn't pay the cold-compile cost. |
| `frontend/Dockerfile.dev` | Add `COPY . .` after `npm ci`. |
| `backend/.dockerignore` *(new)* | Exclude `target/`, `.git/`, `.mvn/wrapper/maven-wrapper.jar` (kept anyway via explicit COPY). |
| `frontend/.dockerignore` *(new)* | Exclude `node_modules/`, `dist/`, `.git/`. Critical — without this, the host `node_modules` gets baked into the image and shadows the named volume. |
| `CLAUDE.md` | Update the local-dev section: `docker compose watch` instead of `docker compose up -d`. Drop the manual `./mvnw compile` instruction. Add the Ctrl+C note. |

### docker-compose.yml `develop.watch` blocks

**Backend:**

```yaml
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

**Frontend:**

```yaml
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

The frontend block syncs the whole project dir minus build artifacts, deps, and the manifest files that trigger rebuild instead. One entry covers `src/`, `public/`, `index.html`, `vite.config.ts`, `tailwind.config.js`, etc. The `package.json` / `package-lock.json` ignore prevents a double-action (sync + rebuild) when those files change.

## Workflow

Primary command:

```
docker compose watch
```

Builds, starts, and attaches the file watcher in the foreground. File events stream to stdout. Ctrl+C stops the watcher *and* the containers cleanly.

Background variant (if you want the stack running without an attached watcher terminal):

```
docker compose up -d
docker compose watch    # in a separate terminal; only attaches the watcher
```

Tests still run inside the container, unchanged: `docker compose exec backend ./mvnw test`.

## Error handling

- **Java compile error after save** — Maven prints error to compose watch output. No `target/classes` change, no DevTools restart. Backend keeps serving the previous code. User fixes the file, saves, recompile fires.
- **TypeScript / Vite error after save** — Vite shows the error in the browser overlay (existing behavior). Sync still happens; compose watch isn't involved.
- **`pom.xml` edit** — `rebuild` action: backend container is rebuilt and restarted, ~30s downtime. Documented behavior.
- **`package.json` edit** — same: frontend container rebuilds.
- **`compose watch` process killed (Ctrl+C, terminal closed)** — containers stop (default behavior when watch was the foreground process) or stay running (background variant). Re-running `docker compose watch` reattaches.
- **Watch process fails to start (compose version too old)** — `docker compose watch` exits with a version-mismatch message. CLAUDE.md will note the 2.22 floor.

## Testing

Manual acceptance criteria — no automated tests for infrastructure plumbing.

1. **Backend hot reload.** Edit a `String` returned by `UserController.register`, save. Within ~5s, compose watch output shows: sync → mvn compile → Spring DevTools restart. Subsequent `curl POST /api/public/register` returns the new behavior.
2. **Frontend HMR.** Edit a `.tsx` file. Browser updates within ~200ms (no full reload).
3. **Backend syntax error.** Introduce a Java syntax error, save. Maven error visible in watch output. Backend continues serving prior code. Fix the error, save, normal flow resumes.
4. **pom.xml change.** Add a comment to `pom.xml`, save. Backend container rebuilds and comes back healthy. ~30s.
5. **package.json change.** Bump a frontend dep patch version, save. Frontend container rebuilds and comes back healthy.
6. **Clean shutdown.** Ctrl+C in the watch terminal stops both containers within 10s. No zombies.
7. **First-boot speed.** Fresh `docker compose watch` from a clean state reaches the "backend ready" state in under 60s on the dev machine. (Validates the `RUN ./mvnw -q compile` pre-bake.)

## Out of scope

- Production / staging images. This only changes `Dockerfile.dev`. Production `Dockerfile` (if it exists separately) is untouched.
- Test-source watching. Tests still run on demand via `docker compose exec backend ./mvnw test`.
- Remote-debugger attach. Separate concern; not blocked by this work.
- IDE-side compilation (the alternate approach we considered and rejected — required Java SDK on host and per-IDE config).
- Compose version detection / fallback. We assume 2.22+; if a teammate has older Compose, they'll see a clear error and can upgrade.

## Rejected alternatives

- **`watchexec` inside the container.** Worked but added a new system dependency (watchexec + tini), an entrypoint script, and parallel process management. Compose watch is a native mechanism doing the same job with less custom code.
- **`entr` / `inotifywait` inside the container.** Both rely on inotify, which doesn't propagate across Docker Desktop's bind-mount filesystem boundary on Mac or Windows. Would have needed polling fallback either way.
- **`fizzed-watcher-maven-plugin`.** Pure Maven, but uses Java NIO `WatchService` which hits the same inotify-on-bind-mount problem.
- **Spring DevTools remote restart.** Solves a different problem (compile locally, run remotely on a real server). Requires IDE-side compilation, per-developer secret, and an extra port. Overkill for a local container loop.
- **Host IDE compilation + bind-mounted `target/classes`.** Requires a Java SDK on the host and per-IDE auto-build configuration. Rejected during brainstorming for portability reasons.
