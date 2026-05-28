# Plan — Docker warm-stack optimization + OSS suite r2 (28-fail batch)

**Date:** 2026-05-08
**Worktree:** `/Users/ghj/work/auraboot-wt/oss-suite-r2` (branch `fix/oss-suite-r2`)
**Repo state:** OSS main `4f7e82fa`; 11 active OSS worktrees + 6 enterprise worktrees → SOP §11 forces docker isolated stack for any work touching shared singletons (Postgres / m2 / :6443 / :5174 / :3501).

---

## Why this plan exists

Two separate but linked problems:

1. **Docker isolated stack cold-start is 10-20 min** → user reluctance to start one per worktree → host-mode shortcuts → cross-worktree contention (today's 28-fail batch ran on a stale host backend, surfacing 16+ unrelated cascades).
2. **28 fails from `oss-test.sh` full suite (chromium 26 + chromium-deep 2)** still uninvestigated since the earlier "先忽略" call.

Fix #1 first so #2 has a clean substrate to verify against. Without #1, we'd repeat the same false-cascade cycle.

---

## Goal

- Bring isolated stack cold-start to **≤ 3 min** (warm restart **≤ 60s**) without introducing new ways to silently share state across worktrees.
- Run today's 28 fails in the new isolated stack, triage, fix or backlog with confidence that "fail" = real fail (not stale-backend artefact).
- Push results to `fix/oss-suite-r2` branch (NOT main), per new git-workflow SOP.

## Non-goals

- Tier 2/3/4 from earlier strategy memo (CI base image, registry mirror, stack lease pool, schema-bake). Keep this plan to Tier-1 (5 changes, all reversible, no infra dependencies).
- Touching the running host backend / other worktrees' stacks.
- Fixing the unrelated permission-rename / dict-page-width / aura-pipeline workstreams that surfaced in working trees today.

---

## Constraints

- Every change must work **without** a CI rebuild step (Tier-1 only).
- No `docker volume rm` of existing stacks — current users may depend on them.
- Compose changes must be backwards-compatible with `start-isolated.sh` and `oss-reset-and-init.sh` defaults; opt-in via env var or new flag.
- Per `auraboot-enterprise/docs/agent-rules/git-workflow.md` (today's update): no autonomous `git checkout HEAD --` / `rm` of unrelated drift; verify-before-claiming-complete (run timing measurement, not just "should be faster").

---

## Phase 1 — Docker Tier-1 (warm-stack speed)

Each item is independently revertible via `git revert <commit>`.

### 1A. Gradle dependency cache as named volume

**Change:**
- `auraboot/platform/Dockerfile.dev` — set `ENV GRADLE_USER_HOME=/gradle-cache`
- `auraboot/docker-compose.isolated.yml` — under `backend.volumes:` add `- gradle_cache:/gradle-cache`
- Top-level `volumes:` declare `gradle_cache:` (named, not bind — cross-worktree shared, not host-fs-shared)

**Why:** Each isolated stack today re-downloads ~500 MB gradle deps on cold build. Named volume is shared across all isolated stacks (single download, all worktrees benefit) without leaking back to host `~/.gradle`.

**Trade-off:** First-ever build still slow (volume populates). All subsequent worktrees: backend image build drops 5-10 min → 30-90 s.

**Verification:**
- Cold: `time start-isolated.sh --slug=opt-test --rebuild` (record T1)
- Then: `start-isolated.sh --slug=opt-test-2 --rebuild` (T2 should be ≪ T1 due to volume cache)
- Confirm volume exists: `docker volume ls | grep gradle_cache`

### 1B. M2 cache as named volume

**Change:**
- Same pattern as 1A: `MAVEN_HOME` / `~/.m2/repository` → `m2_cache:/m2-cache` with `ENV MAVEN_USER_HOME=/m2-cache` (or symlink in entrypoint).

**Why:** `auraboot-core` JAR + plugin JARs + 3rd-party. Same multi-worktree benefit.

**Verification:** Same as 1A; check `docker volume ls | grep m2_cache`.

### 1C. `start-isolated.sh` default `--no-build`

**Change:**
- Flip default `COMPOSE_BUILD_FLAG="--build"` → `""`. Add explicit `--rebuild` flag for opt-in.
- Update `usage()` to reflect the flip.
- Document in `docs/plans/2026-05/2026-05-07-docker-per-worktree-isolation-design.md` §4.1.

**Why:** 80% of "bring stack back up" calls today don't need a rebuild but pay the rebuild cost. Force user to opt in.

**Trade-off:** Users who edited Dockerfile and forget `--rebuild` will see stale image. Surface this in `start-isolated.sh` output ("⚠ reusing existing image; pass --rebuild if you changed Dockerfile/build.gradle").

**Verification:**
- Without `--rebuild`: `docker compose ... up` should NOT trigger build (look for "Building backend" absent)
- With `--rebuild`: should rebuild

### 1D. Postgres schema-baked image

**Change:**
- New `auraboot/Dockerfile.postgres` extending `postgres:16-alpine`:
  ```dockerfile
  FROM postgres:16-alpine
  COPY platform/src/main/resources/database/schema.sql /docker-entrypoint-initdb.d/01-schema.sql
  ```
- New top-level `image: aura-postgres:schema` reference in compose; build step in `start-isolated.sh` checks if image exists and rebuilds when `schema.sql` SHA changes.
- Cache hash: `sha256sum platform/src/main/resources/database/schema.sql | cut -c1-8` → tag `aura-postgres:schema-<sha>`. Tags accumulate over time; `cleanup-old-postgres-images.sh` for periodic prune.

**Why:** First-boot of Postgres runs schema.sql one time (~30-60s). Building it into the image moves that cost to image-build (cached after first). Subsequent stack starts: container init = 2-5s.

**Trade-off:** schema.sql change forces image rebuild + all running stacks restart. Fine for dev cadence (~daily).

**Verification:**
- Build image: `docker build -t aura-postgres:schema-test -f Dockerfile.postgres .` (note time)
- First stack with image: `time start-isolated.sh --slug=schema-test`; postgres should be ready < 10s after `up -d`
- Compare to baseline cold (`docker volume rm <vol>` + cold start) — postgres init should drop 30-60s → 5s

### 1E. Buildx layer cache

**Change:**
- `start-isolated.sh` — when `--rebuild` is passed, prepend:
  ```bash
  docker buildx build \
    --cache-from type=local,src=/tmp/aura-buildx-cache \
    --cache-to type=local,dest=/tmp/aura-buildx-cache,mode=max \
    -f platform/Dockerfile.dev -t aura-backend:$SLUG platform/
  ```
- Cache dir: `/tmp/aura-buildx-cache` (host-shared across all worktrees; ephemeral, periodic cleanup OK).

**Why:** Layer-level cache reuse across worktrees. Even if one worktree's bootJar layer can't be reused, all the JDK / gradle / dependency layers can.

**Verification:**
- 1st `--rebuild`: T1 (cold)
- 2nd `--rebuild` from a different worktree: T2 (warm via cache); should be 2-3× faster.
- Cache size sanity: `du -sh /tmp/aura-buildx-cache` < 10 GB.

### Combined verification

After all 5 land:
| Scenario | Target time | Measure |
|---|---|---|
| Cold (no images, no volumes) | ≤ 5 min | `time start-isolated.sh --slug=cold-fresh --rebuild` after `docker system prune -af && docker volume prune -af` |
| Warm (volumes + image cached, --no-build default) | ≤ 90 s | `time start-isolated.sh --slug=warm-test` |
| Cross-worktree warm (different slug) | ≤ 60 s | `time start-isolated.sh --slug=warm-test-2` |

Targets are **hard**: if any miss, that's a Phase-1 regression — investigate.

---

## Phase 2 — Suite r2 (28-fail batch)

Once Phase 1 is in and a clean isolated stack is up:

### 2.1 Run baseline against fresh isolated stack
```bash
cd /Users/ghj/work/auraboot-wt/oss-suite-r2
COMPOSE_PROJECT_NAME=auraboot-r2 \
  scripts/dev/start-isolated.sh --slug=r2 --rebuild  # one-time after Phase 1 lands
yes y | IMPORT_TEST_FIXTURES=true ./scripts/oss-reset-and-init.sh  # against isolated stack
./scripts/oss-test.sh 2>&1 | tee /tmp/oss-r2-baseline.log
```

### 2.2 Triage 28 fails by signature

(Triage was started inline earlier — extract into per-cluster table.) Initial reading:

| # | Cluster | Specs | Confidence | Disposition |
|---|---|---|---|---|
| C1 | dict-management container width | 1 | high | spec already exists in main; product change `dict/index.tsx max-w-7xl → w-full` already in main per just-checked grep. Likely viewport / load-time issue, not missing product. |
| C2 | env-layering EL-003 lock toggle | 1 | medium | sibling of EL-001/EL-002 already fixed |
| C3 | acp lifecycle / form CRUD (sibling of CRUD-23) | 4 | medium | EXC-14, CRUD-16, ACP-41, LIFE-01 — likely state-after-mutation refresh |
| C4 | ai-learning-drafts / ai-memory-promotions | 5 | medium | LD-02/03/04, MP-01/02 — Mission Control review UI; could share root |
| C5 | ai-memory-promotions-real | 3 | high | MP-E2E-01/02/03 (sibling of E2E-03 already fixed) — same retract/approve flow |
| C6 | ai-modeling / ai-panel | 3 | medium | navigation / button visibility |
| C7 | auth (LN-004 / RP-002) | 2 | low | error-state assertions; possibly outdated copy |
| C8 | bpm designer | 3 | medium | D2-E05 / B4.3 / SVCH-2 — designer + ServiceTask HTTP |
| C9 | model-crud M-001 | 1 | medium | ENTITY model creation flow |
| C10 | showcase VAL-001 | 1 | high | already fixed once today; may need broader filter |
| C11 | query-builder QB-07/08 | 2 | medium | full UI flow + ⌘+Enter |
| C12 | automation AUTO-04 / AT-004 | 2 | high | AUTO-04 already touched today; AT-004 is delete-confirmation flow |

### 2.3 Per-cluster fix or backlog

For each cluster (in order of confidence + size):
1. Read 1 representative artifact / log section
2. Apply minimum fix (spec-only first, product code only if root cause is clearly product)
3. Verify isolated against fresh r2 stack
4. Push to `fix/oss-suite-r2` branch as separate commit per cluster

### 2.4 Final full-suite verification on r2 stack

After all clusters: rerun `./scripts/oss-test.sh` against r2 stack. Target: ≤ 3 fails (1× per "real product gap" cluster, backlogged).

### 2.5 Push branch + open PR description

`git push origin fix/oss-suite-r2` → user reviews and decides merge cadence.

---

## Risks & mitigations

| Risk | Mitigation |
|---|---|
| Tier-1 changes break `oss-reset-and-init.sh` host path | Phase 1 changes are scoped to `Dockerfile.dev` + `docker-compose.isolated.yml` + `start-isolated.sh` — host path untouched. Test host reset still works after each change. |
| Named-volume corruption (one worktree's gradle cache pollutes another's) | Gradle is content-addressed; same dep version → same artifact. Worst case: `docker volume rm gradle_cache` rebuilds clean. |
| Buildx cache grows unbounded | `/tmp/aura-buildx-cache` is in `/tmp`, ephemeral on reboot. Add `cleanup-buildx-cache.sh` to `scripts/dev/` for proactive prune. |
| Postgres schema image gets stale, devs miss schema.sql changes | `start-isolated.sh` SHA-checks schema.sql before reusing image; warns if SHA differs. |
| 28 fails turn out to be 5 cascading bugs from one root | Triage table (2.2) groups by signature. If one fix unblocks 10+, that's net positive — still measurable. |
| Push to main accidentally during Phase 2 | `fix/oss-suite-r2` branch was already pushed; default upstream is `origin/fix/oss-suite-r2`. `git push` without args goes there, not main. |

---

## Success criteria

| Criterion | Pass |
|---|---|
| Cold isolated stack | ≤ 5 min from `docker system prune -af` |
| Warm isolated stack (volumes preserved) | ≤ 90 s |
| Cross-worktree warm | ≤ 60 s |
| 28-fail batch | ≥ 22 fixed, ≤ 6 backlogged with rationale |
| No regressions in OSS smoke | smoke (172) still 100% on r2 stack |
| No host-mode workflow broken | `oss-reset-and-init.sh` (host) still works |
| All Phase 2 commits on feature branch, not main | `git log origin/main..fix/oss-suite-r2` shows the cluster commits; `git log main..origin/main` shows 0 |

---

## Out-of-scope items captured for later

- Tier-2 base image baked nightly (`aura-backend-base:<deps-sha>`)
- Tier-3 stack lease pool
- Plugin import parallelization
- Schema migrations vs schema-baked tradeoff
- Permission-rename initiative (Agent C drift) — separate ticket
