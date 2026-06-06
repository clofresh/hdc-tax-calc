# CLAUDE.md

## Project Overview

HDC Tax Calculator — a React + Spring Boot application for modeling tax benefits on housing development deals.

- **Frontend:** React + TypeScript + Vite at `frontend/`, runs on port 5173
- **Backend:** Spring Boot (Java 17) at `backend/`, runs on port 8080
- **Database:** PostgreSQL 16 on AWS RDS (not local), accessed via SSH tunnel through a bastion host

## Local Development

### Starting everything with Docker Compose (preferred)

```bash
cp .env.example .env
docker compose watch
```

Brings up Postgres, backend, frontend, LocalStack (S3 stub), and Mailhog (SMTP catcher) on one bridge network, and attaches a host-side file watcher in the foreground. Hibernate's `ddl-auto=update` creates the schema on first boot. Requires Docker Compose 2.22+ for the `watch` action — check `docker compose version` if anything fails to start.

Ctrl+C stops the watcher *and* the containers cleanly. To run the stack in the background instead, `docker compose up -d` and then `docker compose watch` in a separate terminal — that variant only attaches the watcher.

| Service     | URL                              | Notes                              |
|-------------|----------------------------------|------------------------------------|
| Frontend    | http://localhost:5173            | Vite dev server, HMR enabled       |
| Backend API | http://localhost:5173/api/...    | Reached through the Vite proxy; container has no host port |
| Postgres    | localhost:5432 (user `hdc`)      | DB name `hdc`                      |
| Mailhog UI  | http://localhost:8025            | Catches all outbound SMTP          |
| LocalStack  | http://localhost:4566            | S3 endpoint                        |

The backend container is not published on the host. Browsers and host-side
curls reach it through Vite at `http://localhost:5173/api/...`. Port 8080 on
the host is intentionally left free (e.g. for llamacpp). To hit the backend
directly for debugging, either `docker compose exec backend curl ...` or
temporarily add a `8081:8080` mapping to `docker-compose.yml`.

**Hot reload:**
- Frontend: save a file → compose watch syncs it into the container → Vite HMR (~200ms).
- Backend: save a Java file → compose watch syncs it into the container and runs `mvn compile` → Spring DevTools restarts (~5s). No manual compile step needed.
- `pom.xml` or `package.json` change: compose watch rebuilds the affected container (~30s).

**Reset the local DB:** `docker compose down -v && docker compose up -d`.

**Troubleshooting:**
- "port 5432 already in use" — kill any local Postgres or stale SSH tunnel on 5432 before `up`.
- Frontend HMR doesn't fire — confirm `CHOKIDAR_USEPOLLING=true` in the `frontend` service env.
- Browser gets 404 on `/api/...` requests — make sure `VITE_API_BASE_URL=/api` in `.env` (not the absolute `http://localhost:8080/api` from older docs). Absolute URLs bypass the Vite proxy and may hit whatever else is on 8080.
- Backend can't reach DB — check `docker compose logs postgres` for init errors; if seen, `down -v` and retry.
- Schema looks wrong after entity change — `ddl-auto=update` adds columns but doesn't drop or rename them. `down -v` for destructive changes.
- Email features fail — make sure `mailhog` is running and `SPRING_MAIL_HOST=mailhog`, `SPRING_MAIL_PORT=1025` in `.env`.
- S3 features fail — make sure `localstack` is healthy and `AWS_S3_ENDPOINT=http://localstack:4566`, `AWS_S3_BUCKET_NAME=hdc-local` in `.env`.

The existing SSH-tunnel workflow (below) is still available if you need to point local code at RDS directly.

### Starting the backend

```bash
cd backend && ./dev.sh
```

`dev.sh` compiles, opens an SSH tunnel to RDS through the bastion, and starts Spring Boot.

**Known issue:** If local PostgreSQL is running (e.g. `postgresql@16` via Homebrew), it occupies port 5432 and the SSH tunnel to RDS silently fails. The backend then connects to the local (empty) database instead of RDS. `dev.sh` now auto-detects and stops local PostgreSQL before tunneling. If you see empty data or "No configurations found", check `lsof -i :5432` to verify the tunnel is active, not a local postgres process.

### Database

- RDS host: tunneled through `calc.angelfhr.com` bastion to `localhost:5432`
- Schemas: `public`, `user_schema`, `tax_benefits`
- Hibernate default schema: `user_schema`
- `ddl-auto=update` — Hibernate manages schema migrations
- Tax benefits tables (deal_conduit, input_* child tables) live in `tax_benefits` schema

### Frontend

```bash
cd frontend && npm run dev
```

## Architecture Notes

- **DealConduit** is the central entity for saved configurations/presets, with child entities (InputProjectDefinition, InputCapitalStructure, etc.) using `@JsonUnwrapped` for a flat API response
- Repository queries JOIN on `portalSettings` — configs without portal settings are excluded from results
- JWT auth for all endpoints except `/api/public/**` and `/api/chat/**`
- CORS configured for localhost:3000, localhost:5173, and production domains
