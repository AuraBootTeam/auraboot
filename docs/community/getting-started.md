# Getting Started (OSS Core)

This guide gets a fresh clone of the AuraBoot open-source core running on your laptop in a few minutes. It targets contributors and evaluators who want to try the core without the enterprise repo.

## Prerequisites

| Tool | Version | Check |
|---|---|---|
| Java (Temurin or similar) | 21+ | `java -version` |
| Node.js | 20+ | `node -v` |
| pnpm | 9+ | `pnpm -v` |
| PostgreSQL | 15+ | `psql --version` |
| Redis | 7+ | `redis-cli --version` |
| Git | 2.40+ | `git --version` |

PostgreSQL must be reachable on `localhost:5432` with a superuser that can create databases. Redis must be reachable on `localhost:6379`. The default config assumes local Unix-socket / trust auth — override via env vars if your setup differs.

## 1. Clone

```bash
git clone https://github.com/AuraBootTeam/auraboot.git
cd auraboot
```

## 2. Initialize the database

```bash
./scripts/oss-reset-and-init.sh
```

This drops and recreates the `aura_boot` database, applies `schema.sql`, and seeds the default admin tenant and user. Re-run it any time you want a clean slate — it is idempotent but destructive.

If you prefer to prepare the database only (no backend/frontend startup), use `./scripts/oss-init-env-only.sh`.

## 3. Start the backend

```bash
cd platform
./gradlew bootRun
```

The Spring Boot app listens on `http://localhost:6443`. Wait until you see `Started Application in ...` in the log.

## 4. Start the frontend

In a second terminal:

```bash
cd web-admin
pnpm install      # first run only
pnpm dev:full
```

This launches Vite + the BFF on `http://localhost:5173`.

`pnpm dev:full` is intended for foreground development. If you need a background setup, run `pnpm sync-plugins` once and then launch `pnpm dev:web` and `pnpm dev:bff` separately.

## 5. Log in

Open `http://localhost:5173` and sign in with:

- Email: `admin@example.com`
- Password: `Test2026x`

You will be prompted to rotate the password on first login.

## 6. Smoke check

Confirm the backend is healthy without opening a browser:

```bash
curl -s http://localhost:6443/actuator/health
# expected: {"status":"UP", ...}
```

A successful `UP` response means the database connection, Redis, and core subsystems all started correctly.

## Next steps

- Read [`docs/getting-started/first-app.md`](../getting-started/first-app.md) to build your first model and page.
- Browse [`docs/core-concepts/`](../core-concepts/) for the DSL engine, commands, and permissions.
- Join the conversation on [GitHub Discussions](https://github.com/AuraBootTeam/auraboot/discussions) or [Discord](https://discord.gg/auraboot).

## Troubleshooting

- **`bootRun` fails with `relation does not exist`** — re-run `./scripts/oss-reset-and-init.sh`; the schema is out of sync.
- **Frontend shows `undefined/api/...` in the network tab** — set `SPRING_BOOT_URL=http://127.0.0.1:6443` in `web-admin/.env.local`.
- **`pnpm dev:full` cannot reach the backend** — prefix the command with `NO_PROXY=localhost` if you have a system HTTP proxy.
- **Need frontend in background mode** — do not run `nohup pnpm dev:full`. Use `pnpm sync-plugins`, then start `nohup pnpm dev:web` and `nohup pnpm dev:bff` separately.
- **Port already in use** — `pkill -f MetaApplication` (backend) or `pkill -f "bff.server"` (frontend), then retry.

For deeper issues please open a [GitHub issue](https://github.com/AuraBootTeam/auraboot/issues) with the failing command and its full output.
