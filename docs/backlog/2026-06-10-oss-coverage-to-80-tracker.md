---
type: backlog
status: active
created: 2026-06-10
---

# OSS test coverage → 80% — strategy & tracker (2026-06-10)

Goal: raise OSS unit/integration test coverage toward **80%**, across backend `platform`,
backend infra subprojects (`platform-storage-*`, `platform-mq-*`, `platform-plugin-api`),
and frontend `web-admin`.

Scope decision (owner-confirmed 2026-06-10): **incremental, phased (口径 A)** — not a
blunt "80% line coverage over the entire codebase". We raise the *curated, testable*
denominator toward 80% and selectively bring high-value excluded domains back in. UI
controllers, AI orchestration, and presentation code stay covered by E2E / golden tests,
not by mock-heavy unit tests chasing a line number (that would violate AGENTS.md §1/§2.2).

## 1. Baselines (measured 2026-06-10, full suite, 0 connection failures)

| Module | Tests | Coverage baseline |
|---|---|---|
| Backend `platform` (curated, meta/service/impl excluded) | 1170 test files; ~5000 tests run | **LINE 80.1%** (33671/42043), BRANCH 34.5% — *artificially narrow: hid the command pipeline* |
| Backend `platform` (honest, 2026-06-11: meta/service/impl MEASURED) | same suite | **LINE 70.2%** (42826/61002), BRANCH 31.1% — orchestration core un-excluded; gate floor set to 0.68. Measured full-logic (only controllers + data-layer excluded) = 70.1%; meta/service/impl itself is 47%, pipeline phases 62%, agent/runtime 87%, aps/engine 92% — the low areas are controllers (E2E-covered: meta 18.8%, bpm 10.2%, email 2.7%) |
| Frontend `web-admin` | 285 spec files / 2099 tests, all pass | **LINE 19.08%** (13631/71413), Stmts 18.79%, Funcs 16.43%, Branches 16.44% |
| Frontend `web-admin` (after Phase 1, 2026-06-11) | 327 spec files / 2905 tests | **LINE 22.48%** (15904/70716) — +814 tests via #529/#531/#532/#533/#534 (services+hooks+server/stores); ratchet floor 22 |
| Frontend `web-admin` (after Phase 1 round 2, 2026-06-11) | 351 spec files / 3599 tests | **LINE 25.61%** (18112/70716), Stmts 25.13%, Funcs 22.35%, Branches 19.9% — +697 tests via #540/#541/#542/#543 (designer runtime engines + studio/plugin hooks + useTaskCenter/useDslForm); ratchet floor raised to 25 |
| Infra subprojects | 1–7 tests each | not yet measured (need Docker for testcontainers) |

**⚠️ Frontend vitest ceiling ≈ 30%.** Two rounds drove vitest line coverage 19.08% → 25.61% by exhausting the unit-testable *logic* layer (services, hooks, engines, stores, utils). The remaining ~74% of `web-admin` lines are React presentation components / routes / pages — covered by **Playwright E2E**, not vitest (unit-testing them = mock-heavy brittle anti-pattern, AGENTS.md §2.2/§10). **Reaching 80% line on the frontend requires E2E coverage collection (the `coverage:e2e` harness already exists) merged with vitest, OR redefining the frontend target as "vitest logic + E2E UI". This is an owner decision (tracked as task #14), not more component unit tests.**

**Headline finding — the goal is half-met and half-mis-stated:**
- **Backend curated LINE coverage is already 80.1%** — at the target. The risk is that the
  jacoco gate sat at 50%, so this 80% was *unprotected* (any PR could regress it silently).
  **Fixed here: gate raised 50% → 78% + per-package floors (§5).** That single change is the
  highest-ROI action for "整体覆盖率到 80%" — it makes 80% *enforced*, not accidental.
- The real backend gaps are **branch coverage (34.5%)** and the **jacoco-excluded domains**
  (`meta/service/impl` command pipeline, engines, controllers, AI) — unmeasured by design.
- The real *coverage* gap is the **frontend (19%)**.

> The jacoco report committed before this work (7.1% line) was a partial single-test run, not
> a real baseline — do not cite it. The 80.1% above is the full-suite number.

Backend measured-package distribution: **215 packages — 129 already ≥80%, 57 in 50–80%, 29 <50%.**
Largest below-80% opportunities (by missed lines): `plugin/service/impl` 67.1%/1697 missed,
`meta/service` 68.9%/211, `plugin/pf4j` 73.0%/198, `decision/service/impl` 62.4%/157,
`semantic/service` 48.3%/150, `currency/handler` 0.7%/136, `category/service/impl` 56.0%/88.

> 56 tests failed in this run — pre-existing on the `codex/crm-endgame-gaps` WIP branch
> (AssertionError / NPE) + Docker-down testcontainers + shared-DB concurrent-session flakiness.
> Only **1** context-load failure (so the cache-size fix in §2 is correctness-safe, not the
> cause). These need separate triage and are not part of the coverage baseline.

### Backend coverage measurement is gated on excludes
`jacocoTestCoverageVerification` enforces **LINE ≥ 50%** at BUNDLE level over a curated set.
`jacocoExcludes` removes (intentionally, "covered by E2E"): `controller/**`, `bpm/**`,
`finance/**`, `aps/**`, `mrp/**`, `agent/**`, `aurabot/**`, `ai/**`, `chatbi/**`,
`meta/service/impl/**`, plus data layer (`entity/dto/mapper/config/enums/exception/event`).

## 2. The test-infra unlock (critical finding — fixed here)

**Running the full backend `test` task pins PostgreSQL `max_connections` (=400) and breaks
concurrent sessions.** Root cause: the suite has dozens of distinct `@SpringBootTest`
context configurations; Spring's TestContext cache keeps each cached context **alive with
its own HikariCP pool**, and the accumulated pools exhaust the shared DB (observed: all 400
connections consumed → `FATAL: sorry, too many clients already` → cascade of IT failures).

**Fix applied** (`platform/build.gradle`, `test` task):
```groovy
systemProperty 'spring.test.context.cache.maxSize',
    (project.findProperty('springTestContextCacheMaxSize') ?: '8')
```
Bounds coexisting contexts to 8 (≤160 connections), evicting the LRU context closes its
pool. Verified: a full `:test` run then completed the IT phase with **0 connection
failures** (vs. the unbounded run which died mid-IT).

> Anti-pattern recorded: a *global* HikariCP env override
> (`SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=2`) is the WRONG fix — it trips
> `HikariConfig` validation in some contexts (`HikariConfig.java:1149`), and one failed
> context load poisons the Spring context cache → 2700+ cascade `Failed to load
> ApplicationContext`. Bound the **context cache**, do not globally rewrite the pool.

This matters because **reaching 80% requires running the integration tests reliably** — the
remaining coverage lives in the service layer, which is IT-covered. The infra fix is the
foundation, not an incidental.

## 3. Where the real gaps are (untested-class inventory, 2026-06-10)

The OSS backend is already well-covered at the *pure-unit* level. Cross-referencing every
`main` class against same-name tests + any test reference:

- `common/util`: **1** untested (`SpringContextUtil`); `plugin/validation`: **0**.
- `permission/service` / `auth/service` / `tenant/service`: healthy (1–3 untested each).
- **`meta/service/impl` (command-pipeline core): 64 of 109 classes have zero test
  reference** — and it is **excluded from jacoco entirely**. This is the single biggest
  real gap (CommandExecutorImpl, CommandServiceImpl, each pipeline Phase, DataPermissionEngineImpl,
  IdempotencyServiceImpl, …). It needs **real-stack integration tests**, not mocks.
- `meta/service` (84 untested of 179), `decision` (41/56), `eventpolicy` (real logic:
  `ActionPlanResolver`, `OutboxServiceImpl`, service impls — the rest is excluded entity/dto/mapper).

**Conclusion: the path to 80% is integration-testing the service layer + un-excluding and
IT-ing the orchestration core — NOT farming easy pure-function wins (those are mostly done).**

## 4. Test-type strategy by domain

| Domain | Test type | Coverage stance |
|---|---|---|
| `common/util`, `*/validation`, `decision`, pure pipeline phases, `meta/view/schema` | Unit (pure) | → 90%+ |
| `permission`, `auth`, `tenant` service | Real-stack IT (deny-path asserted) | → 80%+ |
| `meta/service/impl` (command pipeline) — **un-exclude** | Real-stack IT, no mocked bridges (§2.2 seam) | bring into gate, → 70%+ |
| `bpm/finance/aps/mrp/decision` engines | Pure-fn unit on state machines/calc + key-path IT | assert every transition/branch; not a line number |
| `controller/**`, `agent/aurabot/ai/chatbi`, FE interaction | E2E / golden (Playwright) | not measured by jacoco/vitest line% |
| Frontend hooks/util/renderers/registries/decision fns | Vitest unit | → 80% |
| Frontend presentation components | Playwright E2E | not chased via vitest |

## 5. Ratchet gate (no-regression, only-up)

**Backend — DONE (applied in `platform/build.gradle` 2026-06-10):** global `BUNDLE LINE`
floor raised **0.50 → 0.78** (locks the measured 80.1% with flaky margin) + per-package
floors at 0.84 for `permission/auth/tenant/rbac .service.impl`. Verified passing against the
real report (`:jacocoTestCoverageVerification` BUILD SUCCESSFUL). Next: raise global → 0.80
as sub-80% packages land; add a `BRANCH` floor once branch coverage is lifted from 34.5%.

**Backend — 2026-06-11 (dict-domain real-stack IT, #8/#9 phase 2):** added
`DictServiceImplIntegrationTest` (19 tests) + `DictCascadeServiceImplIntegrationTest`
(11 tests) exercising the dictionary services against the real DB (no mocked
mappers/bridges). Lift: `DictServiceImpl` 26.7%→**79.0%** line (52.7% branch),
`DictCascadeServiceImpl` 1.8%→**77.2%** line (59.3% branch) — ~+480 covered lines in
`meta/service/impl` (47.0%→~49.8%). Bundle LINE 70.2%→**71.5%**; **BUNDLE floor raised
0.68 → 0.69** (preserves the original ~2pt flaky margin), verified passing on the full
suite report. Next dict-domain targets if continued: `DictVersionServiceImpl` (36.7%),
then the larger gaps `DynamicDataServiceImpl` (34%), `NamedQueryServiceImpl` (47%).

Follow-up (same day): `DictVersionServiceImplIntegrationTest` (11 tests) lifted
`DictVersionServiceImpl` 36.7%→**74.3%** line (60.6% branch), completing the dict domain
(all 3 dict services now ~74–79%). Additive isolated IT (targeted-verified, green); gate
stays 0.69 (this adds margin). The next BUNDLE-floor bump should land together with the
next large class (`DynamicDataServiceImpl` 34% / `NamedQueryServiceImpl` 47%) after a
fresh full-suite run.

Follow-up (same day): `DynamicDataServiceImplCoverageIT` (8 tests) targeted the dynamic
CRUD core (`DynamicDataServiceImpl`, the biggest single gap by line count). **Honest
finding: biggest-by-line ≠ easiest-to-lift.** Adding the full 14-operator `list` sweep,
sort/keyword/pagination edges, and the previously-untested `getStats` / `getFieldOptions`
/ `validate` / `exportData` / not-found branches lifted it **34.1%→~39%** line (20%→26%
branch, +~80-100 lines) — modest, because the uncovered bulk lives in very-hard-to-test
paths (`executeCustomAction` ~450 LOC, `saveWithRelations`, relation methods needing
multi-model + action-definition setup). A big lift on this class is a separate, heavier
slice. Additive isolated IT; gate stays 0.69.

Follow-up (same day, 2 parallel sub-agents): `ReconciliationServiceIntegrationTest` (35
tests) lifted `ReconciliationService` **0.2%→78.9%** (+ surfaced 4 real product bugs —
`docs/backlog/2026-06-11-reconciliation-service-bugs.md`); `NamedQueryServiceImplIntegrationTest`
(87 tests) lifted `NamedQueryServiceImpl` **47%→84.7%**. Parallel-dispatch discipline:
shared-DB backend IT is safe to run 2-at-a-time *targeted* (~40 conns each, cap 2) but never
full-suite concurrently; main loop verified (git branch --contains + independent serial re-run).

**Ratchet bump (2026-06-11 consolidation full-suite run):** the 6 IT classes above lifted
`meta/service/impl` **47.0%→56.1%** (~+1437 covered lines) and the gate-denominator bundle
**70.2%→73.2%** (45356/61940). **BUNDLE LINE floor raised 0.69→0.71** (preserves the ~2pt
flaky margin; full suite has run-to-run flake — failures varied 37→46 across runs, all
pre-existing/unrelated to these IT, which were 0-failure in every run). Verified
`:jacocoTestCoverageVerification` BUILD SUCCESSFUL on the full report. Next high-ROI
meta/service/impl near-zero classes: `SecureQueryExecutorImpl` (16%), `QueryAuditServiceImpl`
(8.5%), `EdiService` (0.3%), `SchemaAccessProjectorImpl` (0.4%), `DictCascade`-style wins.

**Ratchet bump (2026-06-11 wave 2 — all six near-zero classes above, parallel sub-agents):**
real-stack IT for the remaining near-zero `meta/service/impl` classes (each a dedicated
worktree/branch, cherry-picked into one consolidation branch + verified together):

| Class | LINE before→after | Tests |
|---|---|---|
| EdiService | 0.3% → **96.3%** (315/327) | 35 |
| QueryAuditServiceImpl | 8.5% → **82.1%** (800/975) | 36 |
| SecureQueryExecutorImpl | 16% → **74.3%** (326/439) | 88 |
| SchemaAccessProjectorImpl | 0.4% → **57.2%** (139/243, structural ceiling) | 39 |
| FieldForkServiceImpl | 1% → **87.2%** (102/117) | 23 |
| FieldImpactAnalysisServiceImpl | 1% → **100%** (104/104) | 28 |

+249 tests, ~+1600 covered lines. `meta/service/impl` **56.1%→65.4%** (11420/17476);
gate-denominator bundle **73.2%→~75%** (raw report 46850/61885 = 75.7%; gate-curated read
`0.75`). **BUNDLE LINE floor raised 0.71→0.73** (same ~2pt flaky margin), verified
`:jacocoTestCoverageVerification` BUILD SUCCESSFUL on the full-suite report. Full suite had
43 pre-existing/unrelated failures (acp/crm/eventpolicy/rag-embedding/bpm — shared-DB
concurrent-session flake; **0** of the 6 new IT classes failed). **Two live jsonb-persistence
bugs were found + fixed in-band** (EDI controller writes 500'd / query-audit logging was
silently non-functional — both jsonb columns missing `JsonbStringTypeHandler`); three
behavioral findings (SecureQuery cache-read / timeout-context, SchemaProjector stub) are
characterized in `docs/backlog/2026-06-11-meta-impl-coverage-product-findings.md`. This
clears the handover's "near-zero backend classes" high-ROI list. Next: `DynamicDataServiceImpl`
deeper (executeCustomAction/saveWithRelations), frontend #14 (GA E2E V8 coverage), #3 (Docker
testcontainers).

**Frontend** — wired in `web-admin/vitest.config.ts` `coverage.thresholds` (lines 19 /
stmts 18 / funcs 16 / branches 16 today). Raise in lockstep with new tests.

## 6. How to run the baseline / coverage reliably

```bash
# Backend (root platform only; bounded context cache prevents connection exhaustion):
cd platform
./gradlew :cleanTest :test :jacocoTestReport --continue
#   open build/reports/jacoco/test/html/index.html
#   ( -PspringTestContextCacheMaxSize=N to tune )

# Frontend:
cd web-admin && pnpm install --frozen-lockfile && pnpm test:unit:coverage
```
Preconditions: shared Postgres on :5432 (`aura_boot`, ~570 tables), Redis on :6379. Docker
only needed for the ~9 testcontainers tests + infra subprojects.

## 7. Phase plan

- **Phase 0 (done/finishing):** baselines + infra unlock + ratchet wiring + this tracker.
- **Phase 1:** pure-unit gaps (`SpringContextUtil`, `decision`, `eventpolicy` runtime) +
  permission/auth/tenant IT to 80% per-package floor.
- **Phase 2:** un-exclude `meta/service/impl`, add real-stack IT for the command pipeline.
- **Phase 3:** engine domains (state-machine/calc unit + key-path IT); raise global floor.
- **Phase 4:** frontend hooks/util/renderers to 80%; subproject (storage/mq) IT under Docker.

Each phase lands as a feature branch + PR, raises the ratchet floor, never regresses.
