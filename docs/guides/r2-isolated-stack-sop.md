# R2-style Isolated Docker Stack — SOP

**Purpose:** safely run OSS Playwright suites against an isolated docker
stack instead of the host backend. Avoids cross-worktree contention on
the host's shared singletons (Postgres :5432, vite :5173, BFF :3500,
backend :6443) and exposes test-fixture coupling that host-mode runs
would silently mask.

**When to use:** any time you need full-stack verification, complete
Playwright E2E, merge-before-confirmation checks, or parallel subagent
validation that must not disturb host services.

For daily development with multiple active worktrees, prefer the
infra-only workflow below: Docker owns stateful services, while backend /
Vite / BFF run on host with worktree-specific ports.

**Reference branch:** `fix/oss-suite-r2` — landed today (2026-05-08).

## Quick start

### Mode A: daily development (isolated infra + host apps)

Use this when you are iterating on code and do not need a full Docker
backend/frontend stack:

```bash
# 1. Start only Postgres + Redis for this worktree.
./scripts/dev/start-dev-infra.sh --slug=<topic>

# Optional: include MinIO with isolated ports.
./scripts/dev/start-dev-infra.sh --slug=<topic> --with-storage

# 2. Export the generated env contract.
source scripts/dev/r2-env-export.sh <topic>

# 3. Run backend / frontend on host, using the exported ports and DB env.
cd platform
./gradlew bootRun

cd ../web-admin
pnpm dev:full
```

`--dry-run` on both `start-dev-infra.sh` and `start-isolated.sh` is
read-only: it prints the resolved plan but does not write
`.aura-stack/<slug>.env` and does not start containers.

Mode A rules:

- Do not use default host `5432` / `6379` / `6443` / `5173` / `3500` when
  another worktree is active.
- Keep Postgres / Redis data per worktree.
- Do not run complete Playwright E2E against this mode unless the test
  explicitly uses isolated storage/output and the stack has been prepared
  for that suite.
- For OSS -> enterprise SNAPSHOT linkage, prefer a per-worktree Maven
  local repo:

```bash
export AURA_MAVEN_REPO="$PWD/.m2/repository"
./gradlew publishToMavenLocal -Dmaven.repo.local="$AURA_MAVEN_REPO"
```

Or source the helper:

```bash
source scripts/dev/maven-local-export.sh
./gradlew publishToMavenLocal -Dmaven.repo.local="$AURA_MAVEN_REPO"
```

### Mode B: merge verification (full Docker isolated stack)

```bash
# 1. Create / open a worktree (skip if reusing an existing one).
git worktree add ../auraboot-wt/<topic> -b fix/<topic>
cd ../auraboot-wt/<topic>

# 2. Bring up the isolated stack. First time = ~6 min cold;
#    subsequent = ~22 s warm.
./scripts/dev/start-isolated.sh --slug=<topic>

# 3. One-line env setup — exports the entire env contract
#    (BE_PORT / VITE_PORT / BFF_PORT / PG_*  + BACKEND_URL /
#     PLAYWRIGHT_BASE_URL / BFF_URL / PGPASSWORD) for the current shell.
source scripts/dev/r2-env-export.sh <topic>

# Now every command inherits the right env automatically — no need
# to repeat them on each playwright/seed invocation.

# 4. Bootstrap + import plugins via Aura CLI.
for p in core-meta core-bpm core-aurabot page-manager platform-admin \
         org-management crm-starter showcase agent-control-plane \
         workflow-demo test-fixtures; do
  [ -f "plugins/$p/plugin.json" ] && aura plugin publish "plugins/$p" \
      --target "$BACKEND_URL" \
      --user admin@auraboot.com --password Test2026x --yes
done

# 5. Platform-level seeds. Env is already in scope from step 3.
bash scripts/seed-marketplace.sh
psql -f scripts/seed-cs-agent.sql
psql -f scripts/seed-aurabot-agent.sql
psql -f scripts/backfill-model-displayname.sql

# 6. Run any Playwright suite. The setup project (idempotent) handles
#    /api/bootstrap/setup + multi-role users + test pages, so step 4-5
#    can run in any order before the suite.
cd web-admin
npx playwright test --config=playwright.oss.config.ts <args>
```

Mode B rules:

- Use for complete E2E, merge-before-confirmation checks, and parallel
  subagent validation.
- Backend + frontend + Postgres + Redis live in one compose project.
- Stop with `scripts/dev/stop-isolated.sh --slug=<topic>`.
- Use `--purge` when the branch is done or the DB state must be discarded.
  Purge removes project-scoped runtime volumes; host-backed shared caches
  are preserved.
- `start-isolated.sh` performs a Docker VM free-space preflight before it
  writes the stack env file or starts containers. The default threshold is
  `AURA_MIN_DOCKER_FREE_MB=2048`; raise it before large E2E runs or override
  with `AURA_SKIP_DOCKER_DISK_CHECK=1` only when the full-stack run is
  intentionally allowed to risk a full Docker VM.

### Mode C: CI / nightly / long run

Start from a fresh stack:

```bash
scripts/dev/stop-isolated.sh --slug=<topic> --purge || true
scripts/dev/start-isolated.sh --slug=<topic> --rebuild --wait
```

Use the stack-scoped Playwright output directories from
`source scripts/dev/r2-env-export.sh <topic>` and avoid repeated full-suite
runs against a tired container instance.

### Mode B1 vs Mode B2

`start-isolated.sh` now has two verification meanings:

```text
Mode B1: isolated service smoke
  backend container
  lightweight Vite/BFF container
  host Playwright or curl smoke
  target: fast merge-readiness signal for ports, DB, Redis, API, and routing

Mode B2: production-like pre-merge
  backend bootJar image from Dockerfile
  frontend production build/serving path when that surface is under review
  optional playwright-runner profile for Linux browser parity
  target: final confidence before merge or CI-like validation
```

B1 is the default local full-stack check. B2 should be explicitly chosen for
changes that touch build packaging, static serving, browser parity, or release
confidence. Both modes are short-lived; run `stop-isolated.sh --purge` or
`cleanup-stack.sh` after verification unless you intentionally keep the DB for
debugging.

Run B2 production-like frontend against an existing full isolated stack:

```bash
scripts/dev/start-isolated.sh --slug=<topic>-verify --rebuild --wait
scripts/dev/start-production-like.sh --slug=<topic>-verify --rebuild --wait
```

This starts `isolated-prod-frontend` from `web-admin/Dockerfile`, publishes it
on `PROD_FRONTEND_PORT`, and keeps the daily `isolated-frontend` path
unchanged.

### Lightweight frontend + explicit runner

For lowest Docker VM disk usage, the isolated frontend and Playwright runner
are split by responsibility:

```text
isolated-frontend:
  node:22-bookworm-slim
  runs Vite + BFF only

Playwright:
  default: run from host with PLAYWRIGHT_BASE_URL / BACKEND_URL
  optional: run a separate playwright-runner profile for CI-like Linux browser verification
```

This keeps the 3.7 GB Playwright Docker image out of the default full stack.
It is only pulled when the optional runner profile is needed.

Run the optional Linux runner with:

```bash
scripts/dev/run-playwright-runner.sh --slug=<topic>
```

If the Playwright image is not already cached, the script stops and points to
`scripts/dev/doctor-disk.sh`. Pulling the image requires an explicit
`--allow-pull` because the image is large and only belongs to Linux-browser
parity validation.

Override the runner command when needed:

```bash
scripts/dev/run-playwright-runner.sh \
  --slug=<topic> \
  --allow-pull \
  --command='pnpm exec playwright test -c playwright.oss.config.ts --project=chromium --reporter=line'
```

## Required env vars (single source of truth)

The full chain — reset script ⟶ seed scripts ⟶ Playwright runner ⟶ test
helpers — reads the same five variables. Defaults preserve host-mode.

| Var | Default (host) | r2 example | Used by |
|-----|---------------|------------|---------|
| `BE_PORT` | `6443` | `6478` | reset-and-init.sh, BACKEND_URL auto-derive |
| `VITE_PORT` | `5173` | `5208` | reset-and-init.sh, BASE_URL auto-derive, PLAYWRIGHT_BASE_URL |
| `BFF_PORT` | `3500` | `3535` | reset-and-init.sh, BFF_INTERNAL_URL |
| `PG_HOST` | `localhost` | `localhost` | reset-db.sh / seed-marketplace.sh / pg-env.ts |
| `PG_PORT` | `5432` | `5467` | same as above |
| `PG_USER` | `$USER` | `auraboot` | same |
| `PG_DB` | `aura_boot` | `aura_boot` | same |
| `PGPASSWORD` | (libpq trust) | `auraboot_dev` | psql sub-processes (when DB requires md5 auth) |
| `PLAYWRIGHT_BASE_URL` | `http://localhost:5173` | `http://localhost:5208` | playwright base navigation |
| `BACKEND_URL` | derived | `http://localhost:6478` | optional override; specs auto-derive from BE_PORT |
| `PW_E2E_RUN_ROOT` | unset | `test-results/runs/<slug>/<date>` | slug/date-scoped Playwright run root |
| `PW_ARTIFACT_DIR` | `./test-results/artifacts` | `$PW_E2E_RUN_ROOT/artifacts` | traces, screenshots, videos |
| `PW_REPORT_DIR` | `./test-results/html-report` | `$PW_E2E_RUN_ROOT/html-report` | HTML report |
| `PW_RESULTS_JSON` | `./test-results/results.json` | `$PW_E2E_RUN_ROOT/results.json` | JSON report |
| `PW_STORAGE_DIR` | `./tests/storage` defaults | `tests/storage/<slug>/<date>` | storageState output |

**Don't** invent new env names — every existing helper either uses one
of these or auto-derives. Adding a new name re-introduces the cross-stack
drift these conventions exist to prevent.

## Cold vs warm cost

| Operation | Cold | Warm | Notes |
|-----------|------|------|-------|
| `start-isolated.sh --rebuild` | 3:41 backend healthy / 6:28 frontend healthy | n/a | First worktree pays gradle / m2 dep download into shared BuildKit cache |
| `start-isolated.sh` (default = `--no-build`) | n/a | 22 s full stack healthy | Subsequent stack creations on same volume |
| `stop-isolated.sh + start-isolated.sh` | n/a | 22 s | Persistent volumes preserve `postgres_data` etc. |
| `docker volume rm <slug>_postgres_data` then `start-isolated.sh` | + 30-60 s schema init | n/a | Fresh DB; bootstrap re-required |

Pass `--rebuild` only when `Dockerfile`, the selected
`ISOLATED_BACKEND_DOCKERFILE`, `build.gradle`, or backend source changed
since last image — gates cold rebuild behind explicit opt-in. See
`docs/plans/2026-05/2026-05-08-docker-warm-stack-and-suite-r2.md` for
the full rationale (commits b7303cad / 0925d4ed / 97349f08).

## Disk policy

The intended split is:

```text
Per stack:
  Postgres / Redis data
  backend_data
  repo node_modules
  web-admin node_modules

Shared across stacks:
  Docker base image layers
  Docker BuildKit cache
  host-backed Gradle dependency cache
  host-backed Maven dependency cache
  host-backed pnpm store
  host-backed Playwright browser cache
```

Shared dependency caches are mounted from the host, not stored in Docker
named volumes. Default root:

```text
~/.cache/auraboot
  container-linux/
    gradle/
    m2/
    pnpm-store/
    ms-playwright/
```

Override with `AURA_CACHE_ROOT=/path/to/cache`. Keeping these caches on the
host minimizes Docker VM disk growth while still letting every worktree share
warm dependencies. `docker compose down -v` can purge a single stack without
touching these host caches. Do not let macOS host tools and Linux containers
write the same pnpm or Playwright browser cache; use platform-specific cache
directories.

`node_modules` stays per stack because it contains platform-specific links
and install layout. `pnpm store` is content-addressed and shared, so it is
safe to reuse and avoids N worktrees downloading the same packages.

Do not treat `publishToMavenLocal` as a normal cache write. In
multi-worktree mode, publish to a per-worktree Maven repo or serialize the
operation with an audited `FORCE_HOST=1` override.

```bash
source scripts/dev/maven-local-export.sh
./gradlew publishToMavenLocal -Dmaven.repo.local="$AURA_MAVEN_REPO"
```

`maven.repo.local` is not a perfect split between dependency cache and publish
output: Maven uses it for both. The current helper prioritizes correctness
over maximal cache reuse. Longer term, prefer a per-worktree file repository
or composite build for OSS -> enterprise SNAPSHOT wiring so third-party
dependency cache can stay shared while internal publish output remains
isolated.

Read-only disk inspection:

```bash
scripts/dev/doctor-disk.sh
```

If Docker VM free space is below the start threshold, clean stale stacks or
increase Docker Desktop disk size before starting a full stack.

Slug/date-scoped E2E artifact inspection and cleanup:

```bash
scripts/dev/doctor-disk.sh
scripts/dev/cleanup-artifacts.sh --days=14
scripts/dev/cleanup-artifacts.sh --slug=<topic> --days=7 --apply
```

`cleanup-artifacts.sh` only targets directories created by the isolated env
contract:

```text
web-admin/test-results/runs/<slug>/<run-id>
web-admin/tests/storage/<slug>/<run-id>
```

Cleanup defaults:

```bash
# Stop but keep warm volumes.
scripts/dev/stop-isolated.sh --slug=<topic>

# Drop this worktree's project volumes.
scripts/dev/stop-isolated.sh --slug=<topic> --purge

# Drop this worktree's image as well when the stack was only for verification.
scripts/dev/cleanup-stack.sh --slug=<topic> --images --apply

# Prune old build cache only when needed.
docker builder prune --filter until=240h
```

Targeted report-first cleanup:

```bash
scripts/dev/cleanup-stack.sh --slug=<topic> --volumes --images
scripts/dev/cleanup-stack.sh --slug=<topic> --volumes --images --apply
```

Avoid `docker system prune -a --volumes` as a routine command; it destroys
warm caches and makes the next stack cold again.

## Per-stack resource budget

`docker-compose.isolated.yml` caps each stack at:

| Service | CPU | Memory |
|---------|-----|--------|
| backend | 2.0 | 2 GB |
| frontend (vite + BFF) | 2.0 | 2 GB |
| postgres | 0.5 | 512 MB |
| redis | 0.25 | 256 MB |
| **total** | **4.75** | **4.75 GB** |

Docker Desktop VM at 16 GB / 12 CPU therefore comfortably hosts ≥ 3
concurrent stacks. Override per-stack via env: `BE_MEM=4g BE_CPUS=3`,
`FE_MEM=3g FE_CPUS=3`, etc.

## Spring profile note

The isolated stack runs the backend with `SPRING_PROFILES_ACTIVE=community,test`
so test-only beans (`TestUserSpoofFilter`, `TestFixtureController`,
`TestSeedController`) register. Without `test`, specs that depend on
`X-Test-Spoof-User-Id` (e.g. user-soul-profile-real specs) silently fail
because the spoof header is dropped. Host runs that go through
`oss-reset-and-init.sh` get the same profile activation via the script's
own bootRun env.

## Common failure modes (tracked)

- **"401 from /api/.../{pid}"**: spec hard-codes `BACKEND_URL` to host port.
  See `feedback_backend_url_be_port_consistency`. Fix: replace literal with
  `process.env.BACKEND_URL ?? \`http://localhost:${process.env.BE_PORT ?? '6443'}\``.

- **"helper writes to one DB, backend reads another"**: psql in helper
  hard-coded to host postgres. See `feedback_psql_helpers_must_be_env_aware`.
  Fix: `tests/helpers/pg-env.ts#PSQL_BASE`.

- **"sidebar menu missing"**: usually the per-test menu seed in
  `_real-backend-helpers.ts` couldn't find the parent `AI 中心` because
  the spoof filter wasn't installed (Spring profile gap above).

## Next steps (out of scope for this SOP)

- Tier-2 docker (CI nightly base image / local registry mirror) — see
  the warm-stack plan doc.
- `tests/helpers/playwright-env.ts` central export so future specs
  can't reintroduce hard-coded ports / hosts.
- Stack lease pool (Tier-3) for instant warm allocation.
