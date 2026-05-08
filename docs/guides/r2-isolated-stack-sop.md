# R2-style Isolated Docker Stack — SOP

**Purpose:** safely run OSS Playwright suites against an isolated docker
stack instead of the host backend. Avoids cross-worktree contention on
the host's shared singletons (Postgres :5432, vite :5173, BFF :3500,
backend :6443) and exposes test-fixture coupling that host-mode runs
would silently mask.

**When to use:** any time you have ≥ 2 active git worktrees in this
repo (per `auraboot-enterprise/AGENTS.md` §11), or any test work that
should not disturb your running host services.

**Reference branch:** `fix/oss-suite-r2` — landed today (2026-05-08).

## Quick start

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
         acp-showcase workflow-demo test-fixtures; do
  [ -d "plugins/$p" ] && aura plugin publish "plugins/$p" \
      --target "$BACKEND_URL" \
      --user admin@example.com --password Test2026x --yes
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

Pass `--rebuild` only when Dockerfile.dev or build.gradle changed since
last image — gates cold rebuild behind explicit opt-in. See
`docs/plans/2026-05/2026-05-08-docker-warm-stack-and-suite-r2.md` for
the full rationale (commits b7303cad / 0925d4ed / 97349f08).

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
