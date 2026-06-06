# Multi-Stack Friendly Port Bindings — Design

**Date:** 2026-06-06
**Status:** Approved design, pending implementation plan
**Scope:** Allow multiple docker-compose stacks (one per worktree, typically) to coexist on the same host without fighting over fixed port bindings. Frontend, Postgres, and Mailhog UI become env-var-templated. LocalStack and Mailhog SMTP lose their host bindings (only the backend container uses them).

## Motivation

We expect routine worktree-based dev — multiple checkouts of the repo, each on a different feature branch, ideally each with its own running stack so you can context-switch without `docker compose down`-ing and losing state. Today the compose file uses fixed `5173:5173`, `5432:5432`, etc., so the second stack to start fails to bind any of those ports.

The recently-completed compose-watch dev loop already showed this friction: the subagent's worktree stack couldn't start while the parent's stack was running. The subagent had to `down` the parent to free ports. That's not a workflow that scales to 3+ worktrees.

## Decisions (locked in during brainstorming)

1. **Template three ports, drop two.** Frontend (browser), Postgres (DBeaver/psql), and Mailhog UI (browser) need host access. LocalStack and Mailhog SMTP are only used by the backend container, which reaches them via Docker network DNS — they don't need host port bindings.
2. **Default values preserve current behavior.** The primary worktree's stack works on `5173`/`5432`/`8025` with zero `.env` changes. Backwards-compatible — no migration needed for existing checkouts.
3. **User picks the offsets manually.** No auto-derivation script. Adding three lines to a worktree's `.env` is fine for the common case (typically 1-3 secondary worktrees). Helper script can come later if it gets painful.
4. **`COMPOSE_PROJECT_NAME` stays implicit.** Compose defaults to the parent directory name. For worktrees, that's the worktree's dir name, which is already unique. No need to set it explicitly.
5. **Container-to-container ports are unaffected.** Backend still talks to `postgres:5432`, `mailhog:1025`, `localstack:4566` via the docker network. Internal port numbers never change.

## Architecture

### Port binding table

| Service | Current | After this change |
|---|---|---|
| Frontend | `ports: ["5173:5173"]` | `ports: ["${FRONTEND_PORT:-5173}:5173"]` |
| Postgres | `ports: ["5432:5432"]` | `ports: ["${POSTGRES_PORT:-5432}:5432"]` |
| Mailhog UI | `ports: ["8025:8025"]` (and `"1025:1025"`) | `ports: ["${MAILHOG_UI_PORT:-8025}:8025"]` (SMTP binding dropped) |
| Mailhog SMTP | `ports: ["1025:1025"]` | dropped — `expose: ["1025"]` only |
| LocalStack | `ports: ["4566:4566"]` | dropped — `expose: ["4566"]` only |
| Backend | already `expose: ["8080"]` | unchanged |

### Per-worktree behavior

**Primary worktree (default `.env`):**
```
# no port overrides
```
Stack binds 5173/5432/8025 on the host. Identical to today.

**Secondary worktree:**
```
FRONTEND_PORT=5174
POSTGRES_PORT=5433
MAILHOG_UI_PORT=8026
```
Stack binds 5174/5433/8026 on the host. Coexists peacefully with the primary.

Containers within each stack still use the internal default ports (`postgres:5432`, etc.), because the container-side of the mapping never changes — only the host side does. Cross-stack network isolation is provided by separate compose networks per project.

### Reaching the dropped services for debugging

LocalStack and Mailhog SMTP are no longer host-bound, but escape hatches remain:

- **`aws --endpoint-url=...` from host:** add a temporary `ports: ["4566:4566"]` line to `docker-compose.yml` and `docker compose up -d localstack`.
- **`docker compose exec` into the backend container:** `docker compose exec backend curl http://localstack:4566/...` works without any port-binding change.
- **A second compose project that DOES bind LocalStack:** rare, but possible if needed.

## File changes

| File | Change |
|---|---|
| `docker-compose.yml` | Template the three host bindings with env vars + defaults. Remove host bindings for Mailhog SMTP (`1025:1025`) and LocalStack (`4566:4566`). Add `expose:` entries for those two so the container network port advertisement stays explicit. |
| `.env.example` | Add a commented-out section labeled "Multi-worktree port overrides" with the three port vars and a one-line explanation. |
| `CLAUDE.md` | Update the service URL table to show the env-var-driven defaults. Add a "Running multiple worktrees" subsection (~6 lines) covering how to pick offsets. Add a troubleshooting line for "port already in use" → pick a different offset. Note that LocalStack and Mailhog SMTP are no longer host-accessible and point to the escape hatches. |

## Error handling

- **Two stacks both pick the same `FRONTEND_PORT` (e.g., user forgets to set the override on a secondary worktree):** Docker's bind fails with `bind: address already in use` and the offending container fails to start. Compose surfaces this clearly. User edits `.env` to pick a different port and re-runs. Document this in CLAUDE.md troubleshooting.
- **User sets a port that's bound by something other than another compose stack (e.g., system Postgres on 5432):** identical failure mode — Docker's clear error message points at the conflicting port.
- **No graceful fallback / auto-port-picking.** Explicit user choice keeps URLs stable across restarts. Random fallback would break bookmarked URLs and IDE config.

## Testing

Manual acceptance only — this is config plumbing, no application code changes.

1. **Primary on defaults.** From the primary worktree: `docker compose watch`. Browser at `http://localhost:5173` loads. `psql -h localhost -p 5432 -U hdc hdc` connects. `http://localhost:8025` shows Mailhog UI.
2. **Secondary with offsets.** From a second worktree with `FRONTEND_PORT=5174`, `POSTGRES_PORT=5433`, `MAILHOG_UI_PORT=8026` in its `.env`: `docker compose watch`. Browser at `http://localhost:5174` loads (the secondary's frontend, distinct from primary's). `psql -h localhost -p 5433` connects to the secondary's DB. `http://localhost:8026` shows the secondary's Mailhog UI.
3. **Both running simultaneously.** `docker compose ls` from anywhere shows two projects up, each with their own containers, each serving on their own ports. `docker network ls` shows two distinct `hdc-net` networks (one per project).
4. **Internal connectivity preserved.** From inside either backend container: `docker compose exec backend curl -fs http://localstack:4566/_localstack/health` returns the expected payload. Same for `mailhog:1025` (telnet check or similar).
5. **Conflict detection.** Set both worktrees' `.env` to `FRONTEND_PORT=5173`. Try to bring up the second stack. Expected: Docker emits `bind: address already in use`, container fails to start, compose surfaces the error clearly. Fix by changing the secondary's `.env`, retry, succeeds.

## Out of scope

- **Auto-offset script** — manual is fine for the common case. Add later if 3+ worktrees becomes painful in practice.
- **Persistent LocalStack/Mailhog SMTP host access** — escape hatches documented above are sufficient. Permanently binding those would defeat the multi-stack purpose.
- **Backwards-compat shim** — defaults preserve current behavior. Existing `.env` files keep working unchanged.
- **`COMPOSE_PROJECT_NAME` manipulation** — Compose's directory-based default is already worktree-aware. Setting it explicitly would just add config without benefit.
- **Random ephemeral ports** — would break stable URLs across restarts. Considered and rejected for that reason.

## Rejected alternatives

- **Single `PORT_OFFSET` env var with arithmetic.** Compose's variable interpolation doesn't support arithmetic (`${PORT_OFFSET + 5173}` isn't a thing). Would require a wrapper script doing the math before `docker compose` runs. More complexity for one less env var; not worth it.
- **`docker-compose.override.yml` per worktree.** Idiomatic Docker pattern, but creates a new file per worktree that has to be gitignored and re-created on fresh clones. Three env-var lines in `.env` is less ceremony.
- **First-stack-wins + secondaries-go-internal-only.** Secondary stacks would have no host access at all — can't browse the frontend, can't connect with DBeaver. Defeats the purpose of running multiple stacks.
- **Two named profiles (primary/secondary) with hardcoded alternate ports.** Doesn't scale beyond two simultaneous stacks. User explicitly wants 3+.
- **Helper script that auto-detects free ports.** Convenient but produces unstable URLs across runs (port picked depends on what's free at startup). Bookmarks and IDE config break. Defer until manual workflow proves painful.
