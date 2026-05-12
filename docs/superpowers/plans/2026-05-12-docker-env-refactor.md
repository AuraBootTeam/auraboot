# Docker Environment Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make multi-worktree development use layered isolation: isolated runtime state, shared build caches, and clear daily-dev vs merge-verification workflows.

**Architecture:** Keep full Docker isolated stack for E2E and merge verification, but document daily development as host app processes connected to per-worktree isolated infra. Preserve per-stack `node_modules` for correctness while sharing pnpm and Playwright browser caches to reduce disk growth.

**Tech Stack:** Docker Compose, Bash, Gradle/Maven local repositories, pnpm, Playwright, AuraBoot OSS + Enterprise docs.

---

## File Map

- Modify: `docker-compose.isolated.yml` — add shared pnpm / Playwright cache volumes and environment wiring.
- Modify: `scripts/dev/start-isolated.sh` — fix help text so the default backend Dockerfile is described correctly.
- Create: `scripts/dev/start-dev-infra.sh` — start only Postgres / Redis / optional MinIO for daily host app development, using the same slug and port assignment contract.
- Create: `scripts/dev/doctor-disk.sh` — print Aura-related disk pressure signals without mutating Docker state.
- Create: `scripts/dev/maven-local-export.sh` — sourceable helper for per-worktree Maven local publishing.
- Modify: `scripts/lib/multi-worktree-guard.sh` — allow reset/init when already targeting isolated PG.
- Modify: `platform/build.gradle` — allow `publishToMavenLocal` when `-Dmaven.repo.local` points outside default `~/.m2/repository`.
- Modify: `scripts/dev/stop-isolated.sh` — stop both full and infra-only stacks, including optional storage profile.
- Modify: `scripts/dev/list-isolated.sh` — show stack mode and MinIO ports when `.aura-stack/<slug>.env` is present.
- Modify: `scripts/dev/lib/env-loader.sh` and `scripts/dev/r2-env-export.sh` — preserve and display real stack slug.
- Create: `scripts/dev/test-dev-env-scripts.sh` — non-mutating smoke tests for dry-run and source-only helpers.
- Modify: `scripts/dev/README.md` — document Mode A daily-dev commands and disk diagnostics.
- Modify: `docs/guides/r2-isolated-stack-sop.md` — document daily vs full-stack modes and disk policy.
- Create: `docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md` — preserve the analysis and risk assessment.
- Modify in enterprise repo: `docs/agent-rules/multi-worktree-isolation.md` — update the rule from absolute full-Docker wording to layered isolation.

## Task List

### Task 1: Persist Analysis And Plan

**Files:**
- Create: `docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md`
- Create: `docs/superpowers/plans/2026-05-12-docker-env-refactor.md`

- [x] **Step 1: Save the analysis document**

Create the analysis doc with sections:

```text
Background
Current implementation status
Current problems
Disk space risk
Target architecture
Isolation and sharing boundaries
Implementation priority
```

- [x] **Step 2: Save this implementation plan**

Run:

```bash
test -f docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md
test -f docs/superpowers/plans/2026-05-12-docker-env-refactor.md
```

Expected: both commands exit 0.

### Task 2: Share Frontend Dependency Caches In Isolated Stack

Note: this task originally implemented shared Docker named volumes. Task 21
superseded that implementation with host-backed bind mounts to minimize Docker
VM disk usage.

**Files:**
- Modify: `docker-compose.isolated.yml`

- [x] **Step 1: Add shared cache environment**

In `isolated-frontend.environment`, add:

```yaml
PNPM_STORE_DIR: /pnpm-store
PLAYWRIGHT_BROWSERS_PATH: /ms-playwright
```

- [x] **Step 2: Add shared cache mounts**

In `isolated-frontend.volumes`, keep the per-stack node_modules volumes and add:

```yaml
- aura_pnpm_store:/pnpm-store
- aura_playwright_browsers:/ms-playwright
```

- [x] **Step 3: Declare shared volumes**

At the bottom of `docker-compose.isolated.yml`, add:

```yaml
aura_pnpm_store:
  name: aura_pnpm_store
  external: false
aura_playwright_browsers:
  name: aura_playwright_browsers
  external: false
```

- [x] **Step 4: Validate compose syntax**

Run:

```bash
COMPOSE_PROJECT_NAME=auraboot-plancheck PG_PORT=15433 BE_PORT=16443 VITE_PORT=15173 BFF_PORT=13500 REDIS_PORT=16379 docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache config >/tmp/aura-isolated-compose.yml
```

Expected: command exits 0 and `/tmp/aura-isolated-compose.yml` contains `aura_pnpm_store`.

### Task 3: Fix Backend Dockerfile Semantics

**Files:**
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `docker-compose.isolated.yml`

- [x] **Step 1: Update comments and help text**

Replace wording that says `--rebuild` is needed when `Dockerfile.dev` changes with:

```text
Dockerfile / build.gradle / source
```

Also document that `ISOLATED_BACKEND_DOCKERFILE=Dockerfile.dev` is the opt-in path for Docker dev backend.

- [x] **Step 2: Validate dry-run**

Run:

```bash
scripts/dev/start-isolated.sh --slug=plancheck --dry-run
```

Expected: prints the resolved ports and does not start Docker containers.

### Task 4: Add Infra-Only Daily Dev Entry Point

**Files:**
- Create: `scripts/dev/start-dev-infra.sh`

- [x] **Step 1: Implement script**

Create a Bash script that:

```text
1. Accepts --slug, --offset, --with-storage, --dry-run.
2. Uses the same slug normalization and port offset model as start-isolated.sh.
3. Writes .aura-stack/<slug>.env with COMPOSE_PROJECT_NAME, PG_PORT, REDIS_PORT, and host-app default BE_PORT/VITE_PORT/BFF_PORT.
4. Runs docker compose with postgres + redis, and MinIO only when --with-storage is passed.
5. Prints host app exports for BACKEND_URL, PLAYWRIGHT_BASE_URL, BFF_URL, PG_* and REDIS_PORT.
```

- [x] **Step 2: Validate dry-run**

Run:

```bash
scripts/dev/start-dev-infra.sh --slug=plancheck --dry-run
```

Expected: prints an infra-only plan and exits 0.

### Task 5: Add Disk Diagnostic Script

**Files:**
- Create: `scripts/dev/doctor-disk.sh`

- [x] **Step 1: Implement read-only diagnostic**

Create a Bash script that prints:

```text
Docker system df, with timeout if available
Aura Docker volumes by name
Aura Docker images by repository/tag
.worktrees size
current worktree .aura-stack size
test-results size when present
```

The script must not prune or delete anything.

- [x] **Step 2: Validate execution**

Run:

```bash
scripts/dev/doctor-disk.sh
```

Expected: exits 0 even if Docker is slow or unavailable.

### Task 6: Update Operating Documentation

**Files:**
- Modify: `docs/guides/r2-isolated-stack-sop.md`
- Modify in enterprise repo: `docs/agent-rules/multi-worktree-isolation.md`

- [x] **Step 1: Update SOP**

Document:

```text
Mode A: daily dev = isolated infra + host apps
Mode B: merge verification = full Docker isolated stack
Mode C: CI/nightly = fresh full Docker
Disk policy: per-stack node_modules, shared pnpm store, shared Gradle/Maven dependency cache, per-worktree Maven publish output
```

- [x] **Step 2: Update enterprise rule**

Change the rule to:

```text
>=2 active worktree 时,写共享运行态或共享发布态的操作必须隔离。
日常 host app 可用,但必须使用 worktree 专属端口和独立 DB/Redis。
完整 E2E / 合并前验证 / 并行 subagent 验证必须 full Docker isolated stack。
```

### Task 7: Verification

**Files:**
- All changed files

- [x] **Step 1: Shell syntax checks**

Run:

```bash
bash -n scripts/dev/start-isolated.sh
bash -n scripts/dev/start-dev-infra.sh
bash -n scripts/dev/doctor-disk.sh
```

Expected: all exit 0.

- [x] **Step 2: Compose config check**

Run:

```bash
COMPOSE_PROJECT_NAME=auraboot-plancheck PG_PORT=15433 BE_PORT=16443 VITE_PORT=15173 BFF_PORT=13500 REDIS_PORT=16379 docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache config >/tmp/aura-isolated-compose.yml
```

Expected: exits 0.

- [x] **Step 3: Dry-run scripts**

Run:

```bash
scripts/dev/start-isolated.sh --slug=plancheck --dry-run
scripts/dev/start-dev-infra.sh --slug=plancheck --dry-run
```

Expected: both exit 0 and do not start containers.

### Task 8: Guard And Operations Follow-Up

**Files:**
- Modify: `scripts/lib/multi-worktree-guard.sh`
- Modify: `platform/build.gradle`
- Create: `scripts/dev/maven-local-export.sh`
- Modify: `scripts/dev/stop-isolated.sh`
- Modify: `scripts/dev/list-isolated.sh`
- Modify: `scripts/dev/README.md`
- Test: `scripts/lib/test-multi-worktree-guard.sh`

- [x] **Step 1: Allow isolated reset/init**

`scripts/lib/multi-worktree-guard.sh` now allows `reset-db.sh` and
`oss-reset-and-init.sh` when the command already targets isolated runtime
state via `AURA_ENV_PROFILE=r2`, `PG_PORT != 5432`, or
`COMPOSE_PROJECT_NAME`.

- [x] **Step 2: Allow per-worktree Maven publish**

`platform/build.gradle` now allows `publishToMavenLocal` when
`-Dmaven.repo.local` points outside the default `~/.m2/repository`.
Default shared `~/.m2` publishing remains blocked when multiple worktrees
exist.

- [x] **Step 3: Add Maven helper**

`scripts/dev/maven-local-export.sh` exports `AURA_MAVEN_REPO` and appends
the matching `-Dmaven.repo.local` to `GRADLE_OPTS`.

- [x] **Step 4: Make stop/list understand both stack modes**

`stop-isolated.sh` now includes the `storage` profile during `down`, so
infra-only stacks with MinIO are stopped too. `list-isolated.sh` now shows
`MODE` and `MINIO` columns when env metadata exists.

- [x] **Step 5: Verify**

Run:

```bash
bash -n scripts/dev/start-isolated.sh scripts/dev/start-dev-infra.sh scripts/dev/stop-isolated.sh scripts/dev/list-isolated.sh scripts/dev/doctor-disk.sh scripts/dev/maven-local-export.sh scripts/lib/multi-worktree-guard.sh scripts/lib/test-multi-worktree-guard.sh
bash scripts/lib/test-multi-worktree-guard.sh
cd platform && ./gradlew verifyMultiWorktreeGuard -q -Dmaven.repo.local=$PWD/../.m2/repository
```

Expected: all pass.

### Task 9: Dry-Run Hygiene

**Files:**
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `scripts/dev/start-dev-infra.sh`
- Modify: `scripts/dev/lib/env-loader.sh`
- Modify: `scripts/dev/r2-env-export.sh`

- [x] **Step 1: Make dry-run read-only**

`start-isolated.sh --dry-run` and `start-dev-infra.sh --dry-run` no
longer write `.aura-stack/<slug>.env`. The env file is written only
after the dry-run branch exits and before compose starts.

- [x] **Step 2: Fix r2 env summary**

`r2-env-export.sh` now reports the real stack slug via `AURA_ENV_SLUG`
instead of printing `slug=r2` for every stack.

- [x] **Step 3: Verify no dry-run env files are created**

Run:

```bash
rm -f .aura-stack/plancheck.env .aura-stack/planinfra.env
scripts/dev/start-isolated.sh --slug=plancheck --dry-run
scripts/dev/start-dev-infra.sh --slug=planinfra --with-storage --dry-run
test ! -e .aura-stack/plancheck.env
test ! -e .aura-stack/planinfra.env
```

Expected: all commands exit 0.

### Task 10: Script Smoke Tests

**Files:**
- Create: `scripts/dev/test-dev-env-scripts.sh`
- Modify: `scripts/dev/README.md`

- [x] **Step 1: Add non-mutating smoke tests**

`scripts/dev/test-dev-env-scripts.sh` verifies:

```text
start-isolated.sh --dry-run does not write .aura-stack/<slug>.env
start-dev-infra.sh --dry-run does not write .aura-stack/<slug>.env
r2-env-export.sh loads a synthetic stack env and reports the real slug
r2-env-export.sh and maven-local-export.sh reject direct execution
maven-local-export.sh exports a per-worktree Maven repo
```

- [x] **Step 2: Run smoke tests**

Run:

```bash
bash scripts/dev/test-dev-env-scripts.sh
```

Expected: all scenarios pass and no Docker containers are started.

---

## Follow-Up Task List

### Task 11: Make reset-db.sh Env-Aware

**Files:**
- Modify: `scripts/reset-db.sh`
- Test: shell syntax + generated command inspection via dry-run-compatible helper paths

- [x] **Step 1: Replace hard-coded DB defaults with PG env contract**

`reset-db.sh` must read:

```text
PG_HOST / PGPORT or PG_PORT
PGUSER or PG_USER
PGDATABASE or PG_DB
PGPASSWORD or PG_PASSWORD
```

Host defaults remain `localhost:5432`, user `${USER:-ghj}`, db `aura_boot`.

- [x] **Step 2: Use psql wrapper for every DB command**

Every psql call must go through one wrapper so host and isolated mode use
the same connection parameters.

- [x] **Step 3: Keep guard before destructive action**

`aura_multi_worktree_guard "reset-db.sh"` stays before confirmation and
destructive SQL.

### Task 12: Add Gradle Guard Smoke Script

**Files:**
- Create: `scripts/dev/test-gradle-guard.sh`
- Modify: `scripts/dev/README.md`

- [x] **Step 1: Verify default shared Maven local remains blocked**

Run `cd platform && ./gradlew verifyMultiWorktreeGuard -q` and expect a
non-zero exit when multiple worktrees exist.

- [x] **Step 2: Verify per-worktree Maven local passes**

Run:

```bash
cd platform && ./gradlew verifyMultiWorktreeGuard -q -Dmaven.repo.local=$PWD/../.m2/repository
```

Expected: exit 0.

### Task 13: Enhance doctor-disk.sh

**Files:**
- Modify: `scripts/dev/doctor-disk.sh`

- [x] **Step 1: Show known Aura volumes with approximate size when possible**

Use `docker system df -v` with timeout and keep the script read-only.

- [x] **Step 2: Show stale stack candidates**

Compare `docker volume ls` Aura stack prefixes against `docker compose ls`
running projects. Print report-only cleanup hints; do not delete.

### Task 14: Add Targeted Cleanup Script

**Files:**
- Create: `scripts/dev/cleanup-stack.sh`
- Modify: `scripts/dev/README.md`
- Modify: `docs/guides/r2-isolated-stack-sop.md`

- [x] **Step 1: Implement slug-scoped cleanup**

The script should accept:

```text
--slug=<name>
--volumes
--images
--dry-run
```

It must run `docker compose down` for only `auraboot-<slug>` and print
optional targeted image cleanup commands. It must not run global
`docker system prune`.

### Task 15: Final Verification And Handoff

**Files:**
- All changed files

- [x] **Step 1: Run script syntax checks**

- [x] **Step 2: Run dev env smoke tests**

- [x] **Step 3: Run guard tests**

- [x] **Step 4: Run compose config**

- [x] **Step 5: Summarize exact branches and remaining manual stack tests**

### Task 16: Real Infra-Only Stack Smoke

**Files:**
- Runtime validation only

- [x] **Step 1: Start infra-only stack with storage**

Run:

```bash
scripts/dev/start-dev-infra.sh --slug=script-smoke --with-storage
```

Result:

```text
Postgres: localhost:5480
Redis: localhost:6526
MinIO: localhost:9049 / 9149
```

- [x] **Step 2: Verify env export and list output**

Run:

```bash
source scripts/dev/r2-env-export.sh script-smoke
scripts/dev/list-isolated.sh
```

Confirmed:

```text
AURA_ENV_SLUG=script-smoke
list-isolated shows MODE=infra and MINIO=9049/9149
```

- [x] **Step 3: Verify service health**

Validated:

```text
docker exec auraboot-script-smoke-postgres pg_isready -U auraboot -d aura_boot
docker exec auraboot-script-smoke-redis redis-cli ping
curl http://localhost:9049/minio/health/live
```

All returned healthy.

- [x] **Step 4: Purge test stack**

Run:

```bash
scripts/dev/stop-isolated.sh --slug=script-smoke --purge
```

Confirmed no `auraboot-script-smoke` containers, volumes, or
`.aura-stack/script-smoke.env` remain.

- [x] **Step 5: Full isolated stack decision**

Full isolated stack validation was executed in Task 17.

### Task 17: Real Full Isolated Stack Smoke

**Files:**
- Modify: `docker-compose.yml`
- Modify: `docker-compose.isolated.yml`
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `scripts/dev/cleanup-stack.sh`
- Runtime validation

- [x] **Step 1: Start full isolated stack**

Run:

```bash
scripts/dev/start-isolated.sh --slug=full-smoke --wait --skip-pull
```

First run built `auraboot-full-smoke-backend:latest` successfully and started
Postgres, Redis, backend, and isolated frontend. Backend reported healthy
inside Docker, but external actuator returned `503 {"status":"DOWN"}` because
Spring's `diskspace` health indicator saw `/app/.` as 0 bytes free inside the
Docker Desktop overlay filesystem.

- [x] **Step 2: Fix Docker health semantics**

Added `MANAGEMENT_HEALTH_DISKSPACE_ENABLED=false` to Docker backend
environment. Disk capacity is handled by `scripts/dev/doctor-disk.sh`; service
health should reflect DB/app readiness instead of Docker overlay free-space
false negatives.

- [x] **Step 3: Fix shared cache volume purge behavior**

Changed shared cache volumes to external named volumes in this intermediate
step:

```text
aura_gradle_cache
aura_m2_cache
aura_pnpm_store
aura_playwright_browsers
```

`scripts/dev/start-isolated.sh` now creates them before `docker compose up`.
This prevented `scripts/dev/stop-isolated.sh --purge` from deleting shared
warm caches while still removing per-stack runtime volumes. Task 21 later
moved these caches to host bind mounts for lower Docker VM disk usage.

- [x] **Step 4: Re-run full stack and verify services**

Second run:

```text
slug: full-smoke
project: auraboot-full-smoke
Postgres: localhost:5472
Backend: localhost:6483
Vite: http://localhost:5213
BFF: http://localhost:3540
Redis: localhost:6518
```

Results:

```text
backend /actuator/health: 200 {"status":"UP"}
frontend /: 302
BFF /health: 200, springBoot.status=healthy, backend.status=UP
Postgres pg_isready: accepting connections
Redis ping: PONG
Compose ps: 4 containers healthy
```

- [x] **Step 5: Validate disk impact**

`scripts/dev/doctor-disk.sh` showed full stack incremental runtime costs:

```text
auraboot-full-smoke_isolated_node_modules: ~724 MB
auraboot-full-smoke_postgres_data: ~94 MB
auraboot-full-smoke_isolated_web_admin_node_modules: ~11 MB
auraboot-full-smoke-backend image unique size: ~409 MB
aura_playwright_browsers shared cache: ~1.54 GB
```

Docker VM reached 100% during full stack verification on the current machine
(`31.4G` Docker filesystem). This confirms the original disk-risk analysis:
full isolated stacks are feasible but must be short-lived, and stale stack
volumes/images need targeted cleanup or Docker Desktop disk size must be
increased.

- [x] **Step 6: Purge test stack and image**

Run:

```bash
scripts/dev/stop-isolated.sh --slug=full-smoke --purge
scripts/dev/cleanup-stack.sh --slug=full-smoke --images --apply
```

Confirmed:

```text
no auraboot-full-smoke containers
no auraboot-full-smoke_* project volumes
.aura-stack/full-smoke.env removed
shared aura_* cache volumes preserved
auraboot-full-smoke-backend image removed
```

After cleanup Docker VM still had only ~852 MB free, because other running
containers, images, build cache, stale stack volumes, and the shared
Playwright cache remain. `doctor-disk.sh` reports stale candidates:

```text
auraboot-agent-hardening-verify
auraboot-agent-hardening-host
```

### Task 18: Add Docker VM Disk Preflight

**Files:**
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `scripts/dev/doctor-disk.sh`
- Modify: `scripts/dev/test-dev-env-scripts.sh`
- Modify: `scripts/dev/README.md`
- Modify: `docs/guides/r2-isolated-stack-sop.md`

- [x] **Step 1: Fail full stack startup before mutation when Docker VM is too full**

`start-isolated.sh` now checks Docker VM free space before writing
`.aura-stack/<slug>.env`, creating project resources, or running Compose.
Default threshold:

```text
AURA_MIN_DOCKER_FREE_MB=2048
```

Escape hatch:

```text
AURA_SKIP_DOCKER_DISK_CHECK=1
```

The error points operators to `scripts/dev/doctor-disk.sh` and targeted
`cleanup-stack.sh` commands.

- [x] **Step 2: Show Docker VM df in doctor-disk**

`doctor-disk.sh` now includes a read-only Docker VM filesystem section when
`redis:7-alpine` is already cached:

```text
== Docker VM Filesystem ==
Filesystem ... Available ... Mounted on
/dev/vda1 ... /data
```

- [x] **Step 3: Add regression smoke**

`scripts/dev/test-dev-env-scripts.sh` now includes a high-threshold preflight
scenario:

```text
AURA_MIN_DOCKER_FREE_MB=999999 scripts/dev/start-isolated.sh --slug=scriptcheck-disk --skip-pull
```

Expected result:

```text
exit 4
no .aura-stack/scriptcheck-disk.env
no compose project resources
error suggests scripts/dev/doctor-disk.sh
```

- [x] **Step 4: Verify**

Run:

```bash
bash scripts/dev/test-dev-env-scripts.sh
bash scripts/dev/test-gradle-guard.sh
bash scripts/lib/test-multi-worktree-guard.sh
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile storage config
```

Results:

```text
test-dev-env-scripts.sh: 17 passed, 0 failed
test-gradle-guard.sh: 2 passed, 0 failed
test-multi-worktree-guard.sh: 10 passed, 0 failed
compose config: pass
```

### Task 19: Clean Stale Isolated Stack Artifacts

**Files:**
- Runtime cleanup only

- [x] **Step 1: Review stale stack candidates**

`scripts/dev/doctor-disk.sh` reported:

```text
auraboot-agent-hardening-verify
auraboot-agent-hardening-host
```

Dry-run commands confirmed cleanup scope was limited to each matching compose
project's volumes and backend image:

```bash
scripts/dev/cleanup-stack.sh --slug=agent-hardening-verify --volumes --images
scripts/dev/cleanup-stack.sh --slug=agent-hardening-host --volumes --images
```

- [x] **Step 2: Apply targeted cleanup**

Run:

```bash
scripts/dev/cleanup-stack.sh --slug=agent-hardening-verify --volumes --images --apply
scripts/dev/cleanup-stack.sh --slug=agent-hardening-host --volumes --images --apply
```

Removed:

```text
auraboot-agent-hardening-verify_* volumes
auraboot-agent-hardening-host_* volumes
auraboot-agent-hardening-verify-backend:latest
auraboot-agent-hardening-host-backend:latest
```

- [x] **Step 3: Verify cleanup and free space**

Confirmed:

```text
no auraboot-agent-hardening-verify/host containers
no auraboot-agent-hardening-verify/host project volumes
no auraboot-agent-hardening-verify/host backend images
shared aura_* cache volumes preserved
doctor-disk stale candidates: none
Docker VM free space: 3.0G (was ~852M)
```

### Task 20: Deep Clean Old Verification Images And Build Cache

**Files:**
- Runtime cleanup only

- [x] **Step 1: Remove old, unused Aura/test verification images**

Removed unused images that were not attached to running containers:

```text
auraboot-perf-frontend:latest
auraboot-perf-backend:latest
auraboot-agent-sync2-e2e-backend:latest
quickstart-dashboard-smoke-backend:latest
quickstart-dashboard-smoke-frontend:latest
test_aura_boot_fresh_beta2-backend:latest
test_aura_boot_fresh_beta2-frontend:latest
```

Preserved running stack images and core shared base images:

```text
auraboot-mobile-e2e-final-backend:latest
pgvector/pgvector:pg16
redis:7-alpine
mcr.microsoft.com/playwright:v1.59.1-noble
eclipse-temurin:21-jdk
eclipse-temurin:21-jre-alpine
```

- [x] **Step 2: Prune stopped/dangling resources and BuildKit cache**

Run:

```bash
docker container prune -f
docker image prune -f
docker builder prune -f
```

Build cache reclaimed about `4.9G`.

- [x] **Step 3: Verify final disk state**

Final `doctor-disk.sh`:

```text
Docker VM free space: 9.4G
Docker VM usage: 69%
Stale Stack Candidates: none
Aura Docker images: only active mobile-e2e backend + core shared images
shared aura_* cache volumes preserved
```

### Task 21: Move Shared Dependency Caches To Host

**Files:**
- Modify: `docker-compose.isolated.yml`
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `scripts/dev/doctor-disk.sh`
- Modify: `scripts/dev/README.md`
- Modify: `docs/guides/r2-isolated-stack-sop.md`

- [x] **Step 1: Replace Docker named cache volumes with host bind mounts**

Changed isolated stack cache mounts from Docker external named volumes to
host-backed paths under `AURA_CACHE_ROOT`:

```text
${AURA_CACHE_ROOT}/gradle        -> /gradle-cache
${AURA_CACHE_ROOT}/m2            -> /m2-cache
${AURA_CACHE_ROOT}/pnpm-store    -> /pnpm-store
${AURA_CACHE_ROOT}/ms-playwright -> /ms-playwright
```

Default:

```text
AURA_CACHE_ROOT=~/.cache/auraboot
```

This aligns with the original lowest-Docker-disk proposal: runtime state stays
isolated in Docker, dependency caches live on the host and are shared by all
worktrees.

- [x] **Step 2: Create host cache directories at startup**

`start-isolated.sh` now creates the host cache directories before writing the
stack env and starting Compose. The resolved `AURA_CACHE_ROOT` is written to
`.aura-stack/<slug>.env` for traceability.

- [x] **Step 3: Report host cache sizes**

`doctor-disk.sh` now includes:

```text
== Host Shared Caches ==
cache root: ~/.cache/auraboot
```

- [x] **Step 4: Verify compose and scripts**

Run:

```bash
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile storage config
bash -n scripts/dev/start-isolated.sh scripts/dev/doctor-disk.sh
bash scripts/dev/test-dev-env-scripts.sh
```

Results:

```text
compose config: pass
resolved bind sources: /Users/ghj/.cache/auraboot/{gradle,m2,pnpm-store,ms-playwright}
test-dev-env-scripts.sh: 14 passed, 0 failed, disk preflight skipped because Docker currently has no cached redis image
doctor-disk: host cache root reported; Docker currently reports 0 images/containers/volumes/build-cache
```

### Task 22: Review Resolution And Platform-Scoped Host Caches

**Files:**
- Modify: `docker-compose.isolated.yml`
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `scripts/dev/doctor-disk.sh`
- Modify: `scripts/dev/README.md`
- Modify: `docs/guides/r2-isolated-stack-sop.md`
- Modify: `docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md`

- [x] **Step 1: Apply platform-specific cache namespace**

Review identified that host and container must not write the same pnpm store
or Playwright browser cache because host may be macOS while containers are
Linux. The container cache now defaults to:

```text
AURA_CONTAINER_CACHE_ROOT=~/.cache/auraboot/container-linux
```

Compose mounts:

```text
container-linux/gradle        -> /gradle-cache
container-linux/m2            -> /m2-cache
container-linux/pnpm-store    -> /pnpm-store
container-linux/ms-playwright -> /ms-playwright
```

- [x] **Step 2: Keep guard semantics conservative**

Documented review resolution: guard should allow operations because the
target is explicitly isolated, not merely because no active worktree was
detected. Active detection remains diagnostic, not an authorization source.

- [x] **Step 3: Promote next implementation P0**

Next P0 items:

```text
1. isolated-frontend -> node:22-bookworm-slim
2. optional playwright-runner profile
3. split Mode B into B1 smoke and B2 production-like verification
4. clarify Maven publish repo vs dependency cache limitation
5. make E2E artifacts slug/date-scoped and full stacks short-lived by default
```

- [x] **Step 4: Verify**

Run:

```bash
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile storage config
bash -n scripts/dev/start-isolated.sh scripts/dev/doctor-disk.sh
bash scripts/dev/test-dev-env-scripts.sh
```

### Task 23: Lightweight Frontend And Optional Playwright Runner

**Files:**
- Modify: `docker-compose.isolated.yml`
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `docs/guides/r2-isolated-stack-sop.md`
- Modify: `docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md`

- [x] **Step 1: Change isolated frontend default image**

`isolated-frontend` now defaults to:

```text
node:22-bookworm-slim
```

It runs only Vite + BFF. Playwright browsers are no longer part of the default
frontend service image.

- [x] **Step 2: Update frontend healthcheck**

Node slim images do not guarantee `wget`, so the frontend healthcheck now uses
Node 22's built-in `fetch` to check Vite and BFF readiness.

- [x] **Step 3: Add optional playwright-runner profile**

Added `playwright-runner` service under profile:

```text
playwright-runner
```

It uses:

```text
mcr.microsoft.com/playwright:v1.59.1-noble
```

and is only included when the profile is explicitly enabled. It uses the same
isolated network and env contract:

```text
PLAYWRIGHT_BASE_URL=http://isolated-frontend:5173
BACKEND_URL=http://backend:6443
BFF_URL=http://isolated-frontend:3500
PW_SKIP_WEBSERVER=1
```

- [x] **Step 4: Keep default pre-pull lightweight**

`start-isolated.sh` now pre-pulls `node:22-bookworm-slim` by default for the
frontend instead of the Playwright image.

- [x] **Step 5: Verify**

Run:

```bash
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache config
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile playwright-runner config
bash -n scripts/dev/start-isolated.sh scripts/dev/doctor-disk.sh
bash scripts/dev/test-dev-env-scripts.sh
```

Results:

```text
default isolated config: pass, no mcr.microsoft.com/playwright image
runner profile config: pass, includes playwright-runner image only under profile
test-dev-env-scripts.sh: 17 passed, 0 failed
node-smoke full stack: pass
```

Real stack verification:

```bash
scripts/dev/start-isolated.sh --slug=node-smoke --wait --skip-pull
```

Results:

```text
backend health: 200
frontend root: 302
BFF health: 200
Postgres: accepting connections
Redis: PONG
default image list: no mcr.microsoft.com/playwright image
Docker VM after stack start: 8.3G used / 28.7G available
```

The first attempt exposed a missing parent cache directory when
`~/.cache/auraboot` did not exist. `start-isolated.sh` now creates the cache
root before creating `container-linux/{gradle,m2,pnpm-store,ms-playwright}`.

Cleanup verification:

```bash
scripts/dev/stop-isolated.sh --slug=node-smoke --purge
scripts/dev/cleanup-stack.sh --slug=node-smoke --images --apply
```

No `auraboot-node-smoke` containers, volumes, env file, or backend image remain.

### Task 24: Verification Modes And E2E Artifact Isolation

**Files:**
- Modify: `scripts/dev/start-isolated.sh`
- Modify: `scripts/dev/start-dev-infra.sh`
- Modify: `scripts/dev/lib/env-loader.sh`
- Modify: `docker-compose.isolated.yml`
- Modify: `web-admin/playwright.config.ts`
- Modify: `web-admin/playwright.init.config.ts`
- Modify: `web-admin/playwright.seed.config.ts`
- Modify: `scripts/dev/test-dev-env-scripts.sh`
- Modify: `docs/guides/r2-isolated-stack-sop.md`
- Modify: `scripts/dev/README.md`
- Modify: `docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md`

- [x] **Step 1: Split Mode B contract**

Mode B is now documented as:

```text
B1 isolated service smoke:
  backend container + lightweight Vite/BFF container + host Playwright/curl smoke

B2 production-like pre-merge:
  bootJar backend image + production-like frontend surface when needed +
  optional playwright-runner profile for Linux browser parity
```

B1 is the default local full-stack check. B2 is explicit for packaging,
static serving, browser-parity, or release-confidence work.

- [x] **Step 2: Add slug/date-scoped E2E paths**

Both `start-isolated.sh` and `start-dev-infra.sh` now write:

```text
PW_E2E_RUN_ID
PW_E2E_RUN_ROOT
PW_ARTIFACT_DIR
PW_REPORT_DIR
PW_RESULTS_JSON
PW_STORAGE_DIR
```

Default shape:

```text
web-admin/test-results/runs/<slug>/<date>/
web-admin/tests/storage/<slug>/<date>/
```

`r2-env-export.sh` exports the same values through `env-loader.sh`.

- [x] **Step 3: Wire Playwright configs**

`web-admin/playwright.config.ts`, `playwright.init.config.ts`, and
`playwright.seed.config.ts` now read the `PW_*` paths while preserving the
existing host defaults when those vars are unset.

- [x] **Step 4: Wire optional container runner**

`playwright-runner` profile now receives the same `PW_*` artifact/storage
contract and creates those directories before running Playwright.

- [x] **Step 5: Clarify Maven limitation**

SOP now states that `maven.repo.local` carries both dependency resolution and
publish output. The current per-worktree helper prioritizes correctness; a
future file repository or composite build can split dependency cache reuse from
internal SNAPSHOT publish isolation more cleanly.

- [x] **Step 6: Verify**

Run:

```bash
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache config
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile playwright-runner config
bash -n scripts/dev/start-isolated.sh scripts/dev/start-dev-infra.sh scripts/dev/lib/env-loader.sh scripts/dev/test-dev-env-scripts.sh
bash scripts/dev/test-dev-env-scripts.sh
cd web-admin && PW_ARTIFACT_DIR=test-results/runs/verify/artifacts PW_REPORT_DIR=test-results/runs/verify/html-report PW_RESULTS_JSON=test-results/runs/verify/results.json pnpm exec playwright test -c playwright.noweb.config.ts --list
```

Results:

```text
default compose config: pass, no Playwright image
runner compose config: pass, Playwright image + PW_* env only under runner profile
bash syntax: pass
test-dev-env-scripts.sh: 19 passed, 0 failed
test-multi-worktree-guard.sh: 10 passed, 0 failed
Gradle verifyMultiWorktreeGuard: pass
start-isolated.sh --dry-run: prints slug/date artifact root, writes no env
start-dev-infra.sh --dry-run: prints slug/date artifact root, writes no env
```

Playwright `--list` was not executed in this worktree because
`web-admin/node_modules` is empty and `node_modules/.bin/playwright` is not
installed. The config-level wiring is covered by static references and compose
config; a real E2E run should install frontend dependencies first.

### Task 25: Case Verification And Runner Cleanup Fix

**Files:**
- Modify: `scripts/dev/stop-isolated.sh`
- Modify: `scripts/dev/cleanup-stack.sh`
- Modify: `scripts/dev/test-dev-env-scripts.sh`
- Modify: `docs/plans/2026-05/2026-05-12-docker-env-refactor-analysis.md`

- [x] **Step 1: Run a real isolated UI case**

Started a full isolated stack:

```bash
scripts/dev/start-isolated.sh --slug=case-login --wait --skip-pull
```

Verified:

```text
backend /actuator/health: 200
frontend /: 302
BFF /health: 200
PW_E2E_RUN_ROOT: test-results/runs/case-login/20260512T041417Z
```

Then opened `http://localhost:5207/login` in the browser and verified the
login form rendered with email, password, remember-me, and submit controls.

- [x] **Step 2: Measure disk before and after stack**

Before `case-login`:

```text
Docker VM: 8.0G used / 29.1G available
```

After full stack start:

```text
Docker VM: 9.2G used / 27.9G available
case-login node_modules volume: 723.8MB
case-login web-admin node_modules volume: 21.24MB
case-login Postgres volume: 95.05MB
case-login backend image: 623MB
```

After cleanup:

```text
Docker VM: 8.1G used / 28.9G available
Stale stack candidates: none
No auraboot-case-login containers, volumes, env file, backend image, or
Playwright image remain.
```

- [x] **Step 3: Attempt optional runner profile**

Attempted the optional runner:

```bash
PLAYWRIGHT_RUNNER_COMMAND='pnpm exec playwright test -c playwright.noweb.config.ts tests/e2e/auth/login.spec.ts --project=chromium --grep "should display login form when not authenticated" --no-deps --reporter=line --workers=1' \
docker compose -p auraboot-case-login ... --profile playwright-runner run --rm playwright-runner
```

The pull of `mcr.microsoft.com/playwright:v1.59.1-noble` was intentionally
stopped after several minutes because network throughput was too slow. This
validated the design choice: Playwright image cost is now explicit and
optional, not paid by default full stack startup.

- [x] **Step 4: Fix runner cleanup gap**

The aborted runner created `auraboot-case-login_runner_*` volumes. Existing
cleanup did not include the `playwright-runner` profile, so those volumes were
left as stale candidates.

Fixed:

```text
stop-isolated.sh: include --profile playwright-runner in compose down
cleanup-stack.sh: include --profile playwright-runner in compose down
```

Added a smoke assertion that `cleanup-stack.sh --dry-run` includes
`--profile playwright-runner`.

### Task 26: E2E Artifact Disk Diagnostics And Cleanup

**Files:**
- Create: `scripts/dev/cleanup-artifacts.sh`
- Modify: `scripts/dev/doctor-disk.sh`
- Modify: `scripts/dev/test-dev-env-scripts.sh`
- Modify: `scripts/dev/README.md`
- Modify: `docs/guides/r2-isolated-stack-sop.md`

- [x] **Step 1: Add targeted artifact cleanup**

Added `scripts/dev/cleanup-artifacts.sh`, dry-run by default. It only targets
directories created by the slug/date env contract:

```text
web-admin/test-results/runs/<slug>/<run-id>
web-admin/tests/storage/<slug>/<run-id>
```

It supports:

```bash
scripts/dev/cleanup-artifacts.sh --days=14
scripts/dev/cleanup-artifacts.sh --slug=<slug> --days=7 --apply
```

- [x] **Step 2: Extend doctor-disk**

`doctor-disk.sh` now reports:

```text
web-admin/test-results/runs
web-admin/tests/storage
top slug/date artifact directories
cleanup-artifacts.sh --days=14 dry-run summary
```

- [x] **Step 3: Add smoke coverage**

`test-dev-env-scripts.sh` now creates a scoped fake artifact/storage run,
verifies cleanup is dry-run by default, then applies scoped cleanup and
asserts only the fake dirs are removed.

- [x] **Step 4: Document commands**

Updated `scripts/dev/README.md` and the isolated stack SOP with the artifact
inspection/cleanup commands.

- [x] **Step 5: Verify**

Run:

```bash
bash -n scripts/dev/cleanup-artifacts.sh scripts/dev/doctor-disk.sh scripts/dev/test-dev-env-scripts.sh
bash scripts/dev/test-dev-env-scripts.sh
scripts/dev/doctor-disk.sh
```

Results:

```text
cleanup-artifacts.sh initially missed executable bit: fixed with chmod +x
cleanup-artifacts.sh initially used mapfile, unavailable in macOS bash: replaced with while/read
test-dev-env-scripts.sh: 28 passed, 0 failed
cleanup-artifacts.sh --days=14: dry-run, no matching artifact directories
doctor-disk.sh: reports E2E Artifacts section and artifact cleanup dry-run
```

### Task 27: Close The Six Follow-up Items

User asked to finish the six follow-up items after the case validation:

1. Branch closeout: core verification, diff review, PR-ready summary.
2. Mode A infra-only real validation.
3. B2 production-like definition and small validation.
4. Playwright runner optimization warning/precheck.
5. Maven publish long-term plan.
6. Cleanup strategy enhancement.

**Files:**
- Modify: `docker-compose.isolated.yml`
- Modify: `scripts/dev/start-isolated.sh`
- Create: `scripts/dev/start-production-like.sh`
- Create: `scripts/dev/run-playwright-runner.sh`
- Modify: `scripts/dev/stop-isolated.sh`
- Modify: `scripts/dev/cleanup-stack.sh`
- Modify: `scripts/dev/test-dev-env-scripts.sh`
- Modify: `scripts/dev/README.md`
- Modify: `docs/guides/r2-isolated-stack-sop.md`
- Create: `docs/plans/2026-05/2026-05-12-maven-publish-isolation-options.md`

- [x] **Step 1: Add B2 production-like frontend**

Added `isolated-prod-frontend` under a new explicit `production-like` profile.
It builds from `web-admin/Dockerfile`, publishes `PROD_FRONTEND_PORT`, depends
on backend health, and stays out of the default daily full-stack path.

`start-isolated.sh` now reserves and writes `PROD_FRONTEND_PORT`, and
`start-production-like.sh` starts the B2 service for an existing full isolated
stack.

- [x] **Step 2: Protect Playwright runner image cost**

Added `run-playwright-runner.sh`. It refuses to run when the Playwright image
is not cached unless the caller passes `--allow-pull`, and points to
`scripts/dev/doctor-disk.sh` before allowing a multi-GB pull.

- [x] **Step 3: Extend targeted cleanup to new profiles**

`stop-isolated.sh` and `cleanup-stack.sh` now include both optional profiles:

```text
playwright-runner
production-like
```

This prevents optional B2/runner volumes and containers from surviving a
targeted stack cleanup.

- [x] **Step 4: Document Maven long-term options**

Added `docs/plans/2026-05/2026-05-12-maven-publish-isolation-options.md`.
Decision: keep per-worktree `maven.repo.local` as the enforced baseline now,
track per-worktree file repository as P1, and composite build as P2.

- [x] **Step 5: Verify Mode A real infra-only case**

Started:

```bash
scripts/dev/start-dev-infra.sh --slug=mode-a-check --with-storage
```

Validated:

```text
Postgres: pg_isready accepting connections
Redis: PONG
MinIO: /minio/health/live -> 200
r2-env-export: slug=mode-a-check, PG/Redis/MinIO ports and slug-scoped artifacts exported
```

Cleaned with:

```bash
scripts/dev/stop-isolated.sh --slug=mode-a-check --purge
```

Post-clean checks found no `auraboot-mode-a-check` containers, no project
volumes, and no `.aura-stack/mode-a-check.env`.

- [x] **Step 6: Verify scripts and compose profiles**

Run:

```bash
bash -n scripts/dev/start-isolated.sh scripts/dev/start-production-like.sh scripts/dev/run-playwright-runner.sh scripts/dev/stop-isolated.sh scripts/dev/cleanup-stack.sh scripts/dev/test-dev-env-scripts.sh
bash scripts/dev/test-dev-env-scripts.sh
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache config --services
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile production-like config --services
docker compose -f docker-compose.yml -f docker-compose.isolated.yml --profile isolated --profile cache --profile playwright-runner config --services
```

Results:

```text
test-dev-env-scripts.sh: 36 passed, 0 failed
default isolated services: postgres, backend, isolated-frontend, redis
production-like adds: isolated-prod-frontend
playwright-runner profile adds: playwright-runner
default isolated config contains node:22-bookworm-slim and no Playwright image
production-like config publishes 3011:3000 in the config check
runner profile mounts container-linux/ms-playwright only under runner profile
```
