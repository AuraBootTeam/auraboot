# GA-E2E Docker Stack — Operator Notes

A fully isolated OSS test stack on non-default host ports. Use it when you want to run Playwright (or curl-driven sanity checks) against a clean backend without disturbing the dev `bootRun` (6443) or `pnpm dev:full` (5173/3500) you have running for daily work.

## What runs

| Service | Container | Host port → container | Notes |
|---------|-----------|------------------------|-------|
| postgres | `auraboot-ga-e2e-postgres` | 5433 → 5432 | pgvector/pg16 with auto schema bootstrap |
| backend  | `auraboot-ga-e2e-backend`  | 6444 → 6443 | OSS gradle bootJar; `docker-ga-e2e-bootstrap.sh` initializes admin + default tenant through `/api/bootstrap/setup` |
| frontend | `auraboot-ga-e2e-frontend` | 5174 → 5173 (vite) / 3501 → 3500 (BFF) | node:20-alpine; bind-mounts the worktree; runs `pnpm sync-plugins && pnpm dev:full` |

All three are gated behind the `ga-e2e-stack` compose profile and a distinct `COMPOSE_PROJECT_NAME=auraboot-ga-e2e` so volumes / networks / containers do not collide with any other compose stack.

## Lifecycle

```bash
./scripts/docker-ga-e2e-up.sh        # start, wait for backend health, then frontend health
./scripts/docker-ga-e2e-bootstrap.sh # import plugins + provision e2e-operator / e2e-viewer
./scripts/docker-ga-e2e-logs.sh [backend|postgres|ga-e2e-frontend] [tail]
./scripts/docker-ga-e2e-down.sh           # stop, keep volumes
./scripts/docker-ga-e2e-down.sh --purge   # stop + drop volumes (fresh DB on next up)
```

`up.sh` waits up to 5 minutes for the frontend container's first-boot dependency preparation. Subsequent `up` cycles reuse the named `ga_e2e_node_modules` / `ga_e2e_web_admin_node_modules` / `ga_e2e_pnpm_store` volumes. `scripts/ga-e2e-prepare-deps.sh` fingerprints `pnpm-lock.yaml` and workspace package manifests, then skips `pnpm install` when the fingerprint is unchanged.

## Running Playwright against the stack

Default topology:

- Host runs the Playwright test process only.
- Docker runs the full system under test: Vite, BFF, backend, Postgres, and Redis.
- Playwright reaches the app through the mapped frontend port, for example
  `PLAYWRIGHT_BASE_URL=http://localhost:5174`.
- `PW_SKIP_WEBSERVER=1` is mandatory so Playwright does not start a host Vite/BFF
  process on top of the Docker stack.
- Targeted OSS/BPM commands must pass the full URL/port contract. Setting only
  `PLAYWRIGHT_BASE_URL` is not enough because API helpers resolve direct backend
  calls from `BACKEND_URL` / `BE_PORT`, while SSR and BFF helpers depend on the
  BFF port. `tests/helpers/environments.ts` fails fast when a non-default Docker
  frontend port is used with an incomplete contract.

```bash
cd web-admin
PLAYWRIGHT_BASE_URL=http://localhost:5174 \
BACKEND_URL=http://localhost:6444 \
BE_PORT=6444 \
BFF_PORT=3501 \
PW_SKIP_WEBSERVER=1 \
NO_PROXY=localhost \
  npx playwright test tests/e2e/showcase/... \
    --reporter=line --output=test-results/ga-a 2>&1 | tee /tmp/pw-ga-$(date +%Y%m%d-%H%M%S).log
```

For a per-worktree isolated stack with custom ports, keep the same shape:

```bash
PLAYWRIGHT_BASE_URL=http://localhost:5226 \
BACKEND_URL=http://localhost:6496 \
BE_PORT=6496 \
BFF_PORT=3553 \
PW_SKIP_WEBSERVER=1 \
NO_PROXY=localhost,127.0.0.1 \
  pnpm exec playwright test -c playwright.noweb.config.ts \
    tests/e2e/bpm/task-center.spec.ts \
    --project=chromium --no-deps --reporter=line
```

For the pure Docker runner:

```bash
GA_E2E_SKIP_UP=1 GA_E2E_SKIP_BOOTSTRAP=1 ./scripts/docker-ga-showcase-e2e.sh auth
GA_E2E_CHROMIUM_WORKERS=3 ./scripts/docker-ga-showcase-e2e.sh chromium
GA_E2E_FORCE_PNPM_INSTALL=1 ./scripts/docker-ga-showcase-e2e.sh auth
```

Preparation speed controls:

- default: skip `pnpm install` when the dependency fingerprint and named `node_modules` volumes match.
- `GA_E2E_FORCE_PNPM_INSTALL=1`: force a fresh install.
- `GA_E2E_SKIP_PNPM_INSTALL=1`: skip install even if the fingerprint is stale; use only for local diagnostics.
- `GA_E2E_AUTH_ONCE=0`: restore the old `all` behavior and re-run auth after seed.
- `GA_E2E_CHROMIUM_WORKERS=N`: tune Docker runner chromium workers; default is `3`, while deep remains `1`.

`PW_SKIP_WEBSERVER=1` is required — the docker stack is the webServer; without this Playwright tries to start a host vite on top.

`NO_PROXY=localhost` is required if you have a system HTTP proxy set (per `auraboot-enterprise/AGENTS.md`); the BFF inherits proxy env and 502s on outgoing fetches without it.

Do not mix a host Vite/BFF with a Docker backend for E2E evidence. That shape is
acceptable only as a short-lived single-page debugging aid in one worktree. It is
not valid for targeted E2E, seed verification, or merge readiness because host
Node, proxy env, `node_modules`, session state, and port mappings can differ from
the isolated stack. In multi-worktree development, each worktree must use its own
isolated ports, storage state, and artifact directories; the safer default is:

```text
host: Playwright runner only
docker frontend: Vite + BFF
docker backend: Spring Boot
docker postgres/redis: data services
```

## Change propagation

The default isolated frontend container bind-mounts the worktree and runs
`pnpm dev:full`, so most `web-admin/app` UI changes are picked up by Vite HMR
without rebuilding an image.

Use this rule of thumb:

| Change | Required action |
|--------|-----------------|
| React/TSX/CSS under `web-admin/app` | Usually hot reloads through Vite |
| BFF code under `web-admin/app/server` | Restart the frontend container for a clean result |
| `package.json`, lockfile, generated plugin routes, sync scripts, frontend env | Re-run dependency prep or restart the frontend container |
| Java/backend code under `platform` | Rebuild the backend image and restart backend |
| Plugin JSON/config resources | Re-import/bootstrap/seed into the target DB; restart backend if runtime handlers or startup-scanned resources changed |
| PF4J/plugin handler JARs | Rebuild the JAR and restart backend |
| Schema/bootstrap/reset changes | Use reset/init or a fresh stack |

The optional pure Docker runner puts Playwright in Linux too. Use it for CI-like
browser validation when needed, but it is not the local default because it adds
browser runtime dependencies to the container path.

## Five traps this stack works around

Each was a real blocker the first time the stack was assembled. They are not OSS-specific bugs in the runtime; they are gaps in OSS *tooling* state that a fresh worktree exposes. Listed here so the next operator does not have to rediscover them.

### 1. `gradle-wrapper.jar` is `.gitignore`d

Backend Dockerfile copies `platform/gradle/` and runs `./gradlew bootJar`. The wrapper jar is in the root `.gitignore` (`*.jar` line) and is *not* git-tracked — a fresh worktree has only `gradle-wrapper.properties`, and the build fails with:

```
ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain
```

`docker-ga-e2e-up.sh` looks for the jar in sibling worktrees and copies it ONLY when `AURABOOT_AUTO_COPY_WRAPPER=1` is set; otherwise it fails-loud with the candidate path + sha256 so the operator decides.

CI takes a different path: `actions/setup-java` provides `gradle`, and the workflow runs `gradle wrapper --gradle-version <pinned>` to regenerate.

### 2. `web-admin/Dockerfile` uses npm but the build script chains pnpm

Both `Dockerfile` and `Dockerfile.dev` use `npm ci && npm run build`, but `npm run build` resolves to `pnpm sync-plugins && pnpm typecheck && react-router build`. Pnpm is not installed in `node:18-alpine`, so the prod image is broken on a clean workspace.

This stack sidesteps the prod image entirely: the `ga-e2e-frontend` service runs `node:20-alpine` + corepack pnpm + bind-mounts the source and runs `pnpm dev:full` directly — the same dev-mode flow developers use locally. Fixing the prod Dockerfile is tracked separately.

### 3. `web-admin/app/plugins/_public-routes.ts` is gitignored but required

`app/middleware/sessionMiddlewareFactory.ts` imports it unconditionally. `scripts/generate-plugin-routes.mjs` is the canonical writer but it returned early when 0 platform plugins were found. Result: every fresh checkout fails SSR with `Cannot find module '~/plugins/_public-routes'`.

This batch patches the script to *always* emit the file (with an empty `PLUGIN_PUBLIC_ROUTES = []` stub when no plugins are found). Re-run is idempotent.

### 4. `pnpm-lock.yaml` mismatch — must run `pnpm install` from the repo root

The current OSS lockfile lags behind the `web-admin/package.json` workspace deps (`@auraboot/dsl-types@workspace:*`, `@auraboot/nav-model@workspace:*`, `@auraboot/plugin-sdk@workspace:*`, `ajv@^8.17.1`). `pnpm install --frozen-lockfile` fails, **and** `pnpm install --no-frozen-lockfile` still fails when run from `web-admin/` because pnpm cannot see the workspace from inside a nested package.

Always install from the repo root:

```bash
pnpm install --no-frozen-lockfile      # ✅ workspace seen, deps resolve
```

The `ga-e2e-frontend` container does this in its boot command.

### 5. SSR HttpClient needs `BFF_INTERNAL_URL` set explicitly

`app/shared/services/http-client/URLBuilder.ts` resolves the SSR base URL in this priority:

1. Explicit `apiConfig.baseUrl`
2. `process.env.BFF_INTERNAL_URL`
3. `new URL(request.url).origin`
4. `http://localhost:${BFF_PORT}`

Inside the docker container, the incoming Playwright request has origin `http://localhost:5174` (the host port). Step 3 returns this — but `localhost:5174` is *not* mapped from inside the container, so SSR-side fetches (e.g. the `/login` action POSTing to `/api/auth/login`) hit `ECONNREFUSED`.

The fix is to pin `BFF_INTERNAL_URL=http://localhost:3500` in the frontend container env, which keeps SSR fetches on the in-container loopback regardless of where the request came from. This is set in `docker-compose.ga-e2e.override.yml`.

> **General lesson:** any SSR-driven dev stack that maps host ports differently from container ports needs `BFF_INTERNAL_URL` (or equivalent) pinned to the in-container address.

## Bootstrap responsibilities

`docker-ga-e2e-bootstrap.sh` mirrors the API-only subset of `auraboot-enterprise/scripts/reset-and-init.sh` that is relevant to the OSS test scope:

- 18 OSS plugins imported in dependency order via `/api/plugins/import/import-directory-sync` (idempotent — `overwrite: true`).
- `e2e-operator@test.com` and `e2e-viewer@test.com` provisioned via `/api/admin/users` (the auth.setup project expects both to exist; missing them fails operator/viewer storage state generation).

Out of scope for this script:
- Test page seeding (only used by enterprise specs).
- Marketplace / CS-agent SQL seeds.
- Playwright fixture seeding (run separately from `tests/api/setup/` if needed).

## Diagnostics

Symptom → first thing to check:

| Symptom | Likely cause | Diagnostic |
|---------|--------------|------------|
| Backend Dockerfile build fails on `bootJar` | trap #1 (wrapper jar) | `ls -l platform/gradle/wrapper/gradle-wrapper.jar` |
| Frontend container 500s with "Cannot find module '~/plugins/_public-routes'" | trap #3 | `docker exec ga-e2e-frontend ls /repo/web-admin/app/plugins/_public-routes.ts` |
| `pnpm install` fails with `ERR_PNPM_WORKSPACE_PKG_NOT_FOUND` | trap #4 | run from repo root, never from `web-admin/` |
| `/login` action returns 200 (HTML) instead of 302 | trap #5 (BFF_INTERNAL_URL) | `docker logs ga-e2e-frontend` for `[web]` ECONNREFUSED |
| Playwright auth.setup admin fails | bootstrap not run | `./scripts/docker-ga-e2e-bootstrap.sh` |
| `aura plugin publish` not found | CLI not installed in container | use the API path instead — `/api/plugins/import/import-directory-sync` |

## Constraints from AGENTS.md (apply here too)

- One Playwright run per worktree at a time, with isolated `--output` dirs.
- Always `tee` Playwright output to `/tmp/pw-*.log`. Never `| tail` or `| grep "passed"`.
- After each run, write the result summary to a task file (e.g. `OSS_E2E_TASKS.md`) before starting the next iteration.
- Never claim "done" without a fresh full run with 0 failed.
