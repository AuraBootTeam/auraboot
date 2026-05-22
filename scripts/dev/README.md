# Per-worktree isolated dev stack

Implements **P0 #1-4** of [`docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md`](../../docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md).

## Why

When two or more git worktrees run a host-mode dev stack at the same time, they collide on five shared singletons: Postgres `:5432`, `~/.m2`, backend `:6443`, vite/BFF `:5174:3501`, redis `:6379`. The 2026-05-07 incident (env-layering worktree's `reset-db.sh` rebuilt schema while host backend held a stale m2 jar → 104 `POST /api/pages` failures) is what triggered this design.

Multi-worktree mode requires per-worktree runtime state. Daily development can use infra-only Docker plus host backend / vite / BFF; full Docker remains the merge-verification and E2E path.

## Quick start

```bash
# in any worktree
scripts/dev/env.sh start --mode=bugfix --slug=<slug>
scripts/dev/env.sh status --slug=<slug>
scripts/dev/env.sh list
scripts/dev/env.sh inspect --slug=<slug>
scripts/dev/env.sh verify --level=health --slug=<slug>
scripts/dev/env.sh logs --slug=<slug> --service=all
scripts/dev/env.sh stop --slug=<slug> --dry-run
scripts/dev/env.sh stop --slug=<slug>

# Lower-level primitives remain available when you only need infra.
scripts/dev/start-dev-infra.sh                 # daily dev: postgres + redis only
source scripts/dev/r2-env-export.sh <slug>     # export ports for host apps
echo "$PW_E2E_RUN_ROOT"                        # test-results/runs/<slug>/<date>

scripts/dev/start-isolated.sh                   # auto-derives slug from branch name
# … work …
scripts/dev/list-isolated.sh                    # see all running stacks
scripts/dev/stop-isolated.sh                    # tear down (volumes preserved for fast restart)
scripts/dev/stop-isolated.sh --purge            # tear down + drop project volumes

# Optional production-like frontend and CI-like Linux Playwright runner.
scripts/dev/start-production-like.sh --slug=<slug> --wait --rebuild
scripts/dev/run-playwright-runner.sh --slug=<slug> --allow-pull
```

Daily bugfix environments are registered globally under the mono-repo root,
outside any one worktree:

```text
/Users/ghj/work/auraboot/.aura/env-registry.json
/Users/ghj/work/auraboot/.aura/envs/<slug>/manifest.json
/Users/ghj/work/auraboot/.aura/envs/<slug>/exports.env
/Users/ghj/work/auraboot/.aura/envs/<slug>/auth/
```

`env-registry.json` is the source of truth for slug, worktree, branch,
compose project, and port ownership. `start-dev-infra.sh` and `env.sh start`
refuse registered port collisions across slugs even when the old process is not
currently listening. Override `AURA_ENV_REGISTRY_ROOT` only for tests or
throwaway experiments.

`r2-env-export.sh` reads the registry export and also exports slug/date-scoped
Playwright artifact paths plus private per-env storageState paths:

```text
PW_E2E_RUN_ROOT=test-results/runs/<slug>/<date>
PW_ARTIFACT_DIR=$PW_E2E_RUN_ROOT/artifacts
PW_REPORT_DIR=$PW_E2E_RUN_ROOT/html-report
PW_RESULTS_JSON=$PW_E2E_RUN_ROOT/results.json
PW_STORAGE_DIR=/Users/ghj/work/auraboot/.aura/envs/<slug>/auth
PW_ADMIN_STORAGE_STATE=$PW_STORAGE_DIR/admin.json
PW_OPERATOR_STORAGE_STATE=$PW_STORAGE_DIR/operator.json
PW_VIEWER_STORAGE_STATE=$PW_STORAGE_DIR/viewer.json
```

These paths keep host Playwright runs and optional container-runner runs from
overwriting each other's traces, videos, reports, and storageState files. The
auth files are intentionally not shared across environments.

## Slug + offset

`start-isolated.sh` derives a **slug** (per-worktree namespace) from the current branch name; runs are scoped under `COMPOSE_PROJECT_NAME=auraboot-${slug}`. From the slug it computes a **port offset** (1-89, plus the special `slug=ga-e2e → offset=0` historical convention) via SHA1 hash, then probes the resulting host ports for availability and walks forward (up to 5 attempts) on collision.

Daily bugfix port assignments persist to the global registry under
`.aura/envs/<slug>`. Full isolated stack helpers still persist their own
full-stack metadata under `.aura-stack/${slug}.env`.

| Service   | Base port | Formula            |
|-----------|-----------|--------------------|
| Postgres  | 5433      | `5433 + offset`    |
| Backend   | 6444      | `6444 + offset`    |
| Vite      | 5174      | `5174 + offset`    |
| BFF       | 3501      | `3501 + offset`    |
| Prod FE   | 3001      | `3001 + offset`    |
| Redis     | 6479      | `6479 + offset`    |

## Flags

```text
start-isolated.sh
  --slug=<name>    override auto slug (matching ^[a-z0-9][a-z0-9-]{0,23}$)
  --offset=<N>     skip auto-probing; force offset (1-89)
  --no-build       don't rebuild backend image (faster restart)
  --rebuild        rebuild backend image before starting
  --wait           wait for backend + frontend health
  --dry-run        print plan, don't start docker

start-dev-infra.sh
  --slug=<name>    override auto slug
  --product=<name> registry label: oss|enterprise
  --offset=<N>     skip auto-probing; force offset (1-89)
  --with-storage   also start MinIO with isolated host ports
  --dry-run        print plan, don't start docker

stop-isolated.sh
  --slug=<name>    explicit slug (defaults to current-branch slug)
  --purge          also drop project volumes (postgres data, node_modules)
                   host-backed shared caches are preserved

list-isolated.sh
  --quiet, -q      print just slug names per line (machine-readable)

doctor-disk.sh
  read-only report for Aura Docker images / volumes / build cache / worktrees
  includes Docker VM filesystem free-space and E2E artifact/storage sizes

cleanup-stack.sh
  targeted cleanup for one auraboot-<slug> compose project; dry-run by default

cleanup-artifacts.sh
  targeted cleanup for slug/date-scoped Playwright artifacts and storageState
  directories; dry-run by default

start-production-like.sh
  starts the B2 production-like frontend service for an existing full isolated
  stack; explicit because the prod build is heavier than daily Vite/BFF smoke

run-playwright-runner.sh
  runs the optional Linux Playwright runner; refuses uncached Playwright image
  by default so a normal dev command does not pull a multi-GB image silently

env.sh
  unified daily bugfix entrypoint for start/stop/status/reset/verify/logs/list/inspect;
  start uses Docker infra plus host backend/Vite/BFF and reuses an existing
  registry env at .aura/envs/<slug>, status reports env JSON, tmux sessions,
  and exact BE/Vite/BFF listener PIDs, stop avoids global pkill and stops the
  slug-scoped Docker infra while preserving volumes unless --purge is passed

test-dev-env-scripts.sh
  non-mutating smoke tests for dry-run, r2 env export, and Maven local helper

test-gradle-guard.sh
  non-publishing smoke test for the Gradle multi-worktree Maven guard
```

`--dry-run` is read-only for both start scripts: daily bugfix mode does not
write the global registry and full isolated mode does not write
`.aura-stack/<slug>.env`; neither starts containers.

`start-isolated.sh` refuses to start when Docker VM free space is below
`AURA_MIN_DOCKER_FREE_MB` (default `2048`) because full stacks create
per-stack `node_modules`, database data, and often a backend image. Use
`scripts/dev/doctor-disk.sh` and targeted cleanup first. Override with
`AURA_SKIP_DOCKER_DISK_CHECK=1` only when you intentionally accept the risk.

Shared dependency caches are host-backed by default under
`AURA_CONTAINER_CACHE_ROOT` (default
`~/.cache/auraboot/container-linux`):

```text
~/.cache/auraboot/container-linux/gradle
~/.cache/auraboot/container-linux/m2
~/.cache/auraboot/container-linux/pnpm-store
~/.cache/auraboot/container-linux/ms-playwright
```

This keeps warm dependency caches out of the Docker VM. Per-stack runtime
state (`postgres_data`, `redis_data`, `node_modules`) remains in Docker
volumes and can be dropped with `stop-isolated.sh --purge`. Host-local
Playwright or pnpm caches should use their own host cache paths rather than
sharing the Linux container cache.

Slug/date-scoped E2E artifacts can be inspected and cleaned separately:

```bash
scripts/dev/doctor-disk.sh
scripts/dev/cleanup-artifacts.sh --days=14
scripts/dev/cleanup-artifacts.sh --slug=<slug> --days=7 --apply
```

## Relationship to other compose files

- `docker-compose.yml` — base infrastructure (postgres, redis, prod backend/frontend builds).
- `docker-compose.isolated.yml` — overrides used by the scripts here; parameterizes ports, adds lightweight `isolated-frontend` (Node image, Vite+BFF only), provides explicit `production-like` frontend profile, and keeps `playwright-runner` optional.
- `docker-compose.ga-e2e.override.yml` — separate, preserved as-is for the GA-E2E test runner pipeline.

## Out of scope (deferred to later P1 / P2)

- A productized `aura dev ...` CLI wrapper (P1 #6 in the design doc) — `scripts/dev/env.sh` is the current shell entrypoint.
- Enterprise-side isolated stack (P1 #8) — only OSS covered today.
- Probe-based port allocation (P2 #11) — current code uses hash + walk-forward; long-term plan is a probing scheme.
