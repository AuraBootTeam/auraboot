# GA-E2E Docker Stack — Operator Notes

A fully isolated OSS test stack on non-default host ports. Use it when you want to run Playwright (or curl-driven sanity checks) against a clean backend without disturbing the dev `bootRun` (6443) or `pnpm dev:full` (5173/3500) you have running for daily work.

## What runs

| Service | Container | Host port → container | Notes |
|---------|-----------|------------------------|-------|
| postgres | `auraboot-ga-e2e-postgres` | 5433 → 5432 | pgvector/pg16 with auto schema bootstrap |
| backend  | `auraboot-ga-e2e-backend`  | 6444 → 6443 | OSS gradle bootJar; AURABOOT_BOOTSTRAP_ENABLED=true creates admin + default tenant |
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

`up.sh` waits up to 5 minutes for the frontend container's first-boot `pnpm install`. Subsequent `up` cycles reuse the named `ga_e2e_node_modules` / `ga_e2e_web_admin_node_modules` volumes and finish in seconds.

## Running Playwright against the stack

```bash
cd web-admin
PLAYWRIGHT_BASE_URL=http://localhost:5174 \
PW_SKIP_WEBSERVER=1 \
NO_PROXY=localhost \
  npx playwright test tests/e2e/showcase/... \
    --reporter=line --output=test-results/ga-a 2>&1 | tee /tmp/pw-ga-$(date +%Y%m%d-%H%M%S).log
```

`PW_SKIP_WEBSERVER=1` is required — the docker stack is the webServer; without this Playwright tries to start a host vite on top.

`NO_PROXY=localhost` is required if you have a system HTTP proxy set (per `auraboot-enterprise/AGENTS.md`); the BFF inherits proxy env and 502s on outgoing fetches without it.

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
