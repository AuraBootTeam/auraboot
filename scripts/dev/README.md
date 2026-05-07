# Per-worktree isolated dev stack

Implements **P0 #1-4** of [`docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md`](../../docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md).

## Why

When two or more git worktrees run a host-mode dev stack at the same time, they collide on five shared singletons: Postgres `:5432`, `~/.m2`, backend `:6443`, vite/BFF `:5174:3501`, redis `:6379`. The 2026-05-07 incident (env-layering worktree's `reset-db.sh` rebuilt schema while host backend held a stale m2 jar → 104 `POST /api/pages` failures) is what triggered this design.

Multi-worktree mode requires a per-worktree Docker stack so each worktree owns its own Postgres / m2 (in-container) / backend / vite / BFF / redis at unique host ports.

## Quick start

```bash
# in any worktree
scripts/dev/start-isolated.sh                   # auto-derives slug from branch name
# … work …
scripts/dev/list-isolated.sh                    # see all running stacks
scripts/dev/stop-isolated.sh                    # tear down (volumes preserved for fast restart)
scripts/dev/stop-isolated.sh --purge            # tear down + drop volumes
```

## Slug + offset

`start-isolated.sh` derives a **slug** (per-worktree namespace) from the current branch name; runs are scoped under `COMPOSE_PROJECT_NAME=auraboot-${slug}`. From the slug it computes a **port offset** (1-89, plus the special `slug=ga-e2e → offset=0` historical convention) via SHA1 hash, then probes the resulting host ports for availability and walks forward (up to 5 attempts) on collision.

The chosen port assignments persist to `.aura-stack/${slug}.env` so subsequent invocations of `stop-isolated.sh` / `list-isolated.sh` see consistent values.

| Service   | Base port | Formula            |
|-----------|-----------|--------------------|
| Postgres  | 5433      | `5433 + offset`    |
| Backend   | 6444      | `6444 + offset`    |
| Vite      | 5174      | `5174 + offset`    |
| BFF       | 3501      | `3501 + offset`    |
| Redis     | 6479      | `6479 + offset`    |

## Flags

```text
start-isolated.sh
  --slug=<name>    override auto slug (matching ^[a-z0-9][a-z0-9-]{0,23}$)
  --offset=<N>     skip auto-probing; force offset (1-89)
  --no-build       don't rebuild backend image (faster restart)
  --dry-run        print plan, don't start docker

stop-isolated.sh
  --slug=<name>    explicit slug (defaults to current-branch slug)
  --purge          also drop named volumes (postgres data, node_modules cache)

list-isolated.sh
  --quiet, -q      print just slug names per line (machine-readable)
```

## Relationship to other compose files

- `docker-compose.yml` — base infrastructure (postgres, redis, prod backend/frontend builds).
- `docker-compose.isolated.yml` — overrides used by the scripts here; parameterizes ports + adds `isolated-frontend` (dev-mode pnpm flow).
- `docker-compose.ga-e2e.override.yml` — separate, preserved as-is for the GA-E2E test runner pipeline.

## Out of scope (deferred to later P1 / P2)

- A unified `aura dev start --isolated` CLI (P1 #6 in the design doc) — these shell scripts are the underlying primitives.
- Enterprise-side isolated stack (P1 #8) — only OSS covered today.
- Probe-based port allocation (P2 #11) — current code uses hash + walk-forward; long-term plan is a probing scheme.
