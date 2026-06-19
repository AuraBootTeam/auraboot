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
| Frontend `web-admin` (wave 4 consolidation, 2026-06-19) | 423 spec files / 4354 tests | **LINE 30.28%** (23248/76754), Stmts 29.73%, Funcs 27.47%, Branches 24.64% — the floors had lagged actual by ~5pt (later rounds raised coverage but left the floors at round-2 values); ratchet raised to **28/27/25/22** (lines/stmts/funcs/branches) to lock in achieved coverage, + ActionRegistry handler tests (navigate/new/search/reset/setState + error branches, 18.6%→26.6%). Logic layer near-exhausted (~5 `.ts` modules left); 80% line needs the E2E coverage merge (#14). |
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

**Wave 3 (2026-06-11 — four more near-zero standalone classes, parallel sub-agents):**
real-stack IT for four niche/standalone near-zero classes (target-selected statically since a
fresh full-suite baseline was unreliable that hour — a concurrent enterprise `bootRun` on the
shared DB produced 2554 env-flaky failures; targeted single-class runs stayed robust via
per-tenant isolation, re-verified together = 109 tests, 0 failures):

| Class | LINE before→after | Tests |
|---|---|---|
| OtDeviceService | ~0% → **92.1%** (197/214) | 37 |
| FieldChangeAuditService | 0% → **100%** (45/45) | 26 |
| ExportTaskService | 0% → **77.8%** (130/167) | 16 |
| DefaultCurrencyConversionHandler | 0.7% → **87.6%** (120/137; SPI branch is OSS-unreachable) | 30 |

+109 tests. **A third live jsonb-persistence bug found + fixed in-band** (`OtDevice.connectionConfig`/
`dataMapping` + `OtDataLog.rawData`/`parsedData` missing `JsonbStringTypeHandler` → `registerDevice`/
`processDeviceData` 500'd in production — same class as wave 2's EDI + QueryAuditLog). **The
BUNDLE floor stays 0.73 this wave (NOT bumped):** the gate bump needs a clean full-suite, which
the noisy shared DB could not give that hour. Tests only ever raise coverage, so 0.73 still
passes; the bump to lock in wave 3 should land in the next clean full-suite consolidation. The
recurring jsonb-typeHandler omission (3 entities in 2 days) is worth a one-shot audit of all
`@TableField` String fields mapped to jsonb columns + promoting the rule to canonical.

**Wave 4 (2026-06-18 — "覆盖率收尾固化" follow-up):** (1) jsonb guardrail re-verified clean
on current `origin/main` — `scripts/check-jsonb-typehandler.sh` reports **38 String→jsonb
fields protected, 0 defects** (the wave-3 "2 latent" pair is dispositioned: KbChunk fixed,
InvariantEvaluationLog confirmed false-positive). (2) `DynamicDataServiceImplCoverageIT`
extended with `importData` (CSV/JSON/field-map/file-not-found/per-row-error),
`executeCustomAction` (count/truncate/unsupported), and the relation methods'
no-relations-defined reject path — all **previously 0% covered** (grep-verified zero test
callers), all green on a current-schema DB. Behavioral finding F4: `importData` does not
auto-generate the PK (unlike `create()`), so imports of new records without an explicit
`pid` fail every row — low-priority backlog.

**(3) BUNDLE floor BUMPED 0.73 → 0.75 (second pass — done).** A clean consolidated
measurement needs a DB that is BOTH at the current Flyway baseline AND reset+bootstrapped.
Built it (path B): snapshot the bootstrapped shared `aura_boot`, drop the 32 old-gen billing
tables, recreate the 11 current ones from `aura_boot_base`'s DDL + apply the
`2026-06-10-billing-resource-catalog.sql` seed → bootstrap seed + current schema (billing
13/13 green). Separately, the full-suite jacoco read a bogus ~5% because the runtime CGLIB
**classdump mis-attributes coverage across the suite's many `@SpringBootTest` contexts**
(proxy class IDs diverge per evicted/recreated context) — fixed by switching to JaCoCo
**offline instrumentation** (instrument `build/classes` at build time → coverage on stable
on-disk IDs; the slice that read 0.25% under classdump now reads 50.3%, behavior-neutral).
Clean full run then measured **BUNDLE LINE 0.7735** (51452/66519); security service packages
0.85–0.99 (all > 0.84). Floor raised 0.73→0.75 (~2.3pt margin); `jacocoTestCoverageVerification`
passes. (Earlier intermediate states for the record: snapshot=stale billing→51 fails;
fresh baseline=no seed→75 fails — neither was used for the bump.) Full evidence:
`docs/retro/2026-06-18-oss-coverage-gate-consolidation-testing-gate-acceptance-report.md`.

**Frontend** — wired in `web-admin/vitest.config.ts` `coverage.thresholds`. Floors raised
in lockstep: 19/18/16/16 → 22 → 25 → **28/27/25/22** (lines/stmts/funcs/branches, 2026-06-19
wave-4 consolidation, locking in the measured 30.28/29.73/27.47/24.64). The vitest logic layer
is near-exhausted (~5 `.ts` modules left); further line gains need the E2E coverage merge (#14).

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

## 8. Session progress log — 2026-06-18/19 (waves 5–14)

JaCoCo measurement was first fixed (#838 offline instrumentation: the on-the-fly agent +
CGLIB classdump read a bogus ~5% bundle because proxy class IDs diverge across the many
evicted/recreated `@SpringBootTest` contexts; offline instruments `build/classes` at build
time → coverage on stable on-disk IDs). Backend BUNDLE floor then ratcheted **0.73 → 0.75 →
0.76**; frontend vitest floors consolidated to **28/27/25/22**.

Real-stack IT waves (all merged, each measured against a clean `aura_boot_clean` snapshot via
the path-B recipe + offline jacoco):

| wave | PR | class | before→after | note |
|---|---|---|---|---|
| 5 | #847 | DataDomainServiceImpl | 7%→96% | **2 prod bugs fixed**: missing `userDataDomainIds` cache region + tenant interceptor injecting `dt.tenant_id` into a recursive CTE (`ignoreTable("domain_tree")`) |
| 6 | #848 | FieldMaskServiceImpl | 22%→84% | **prod bug**: missing `fieldMaskConfig` cache region; + systemic cache-name audit (every `@Cacheable`/`@CacheEvict` name now in the fixed `CaffeineCacheManager` allowlist) |
| 7 | #849 | VirtualFieldEngine.evaluate, ApiConnectorServiceImpl | — | SpEL evaluate paths + connector CRUD |
| 8 | #850 | JdbcConnectorServiceImpl | →44% | connector + endpoint CRUD (invoke() needs a live pool, out of scope) |
| 9 | #852 | SodService | 39%→77% | rule CRUD + validation + checkSod(pass) |
| 10 | #854 | DrtDefinitionServiceImpl | 42%→97% | decision-definition CRUD |
| 11 | #856 | CloudConfigServiceImpl | 47%→92% | cloud-config CRUD |
| 12 | #857 | FieldValidationServiceImpl | pure validators | code/dataType/refTarget |
| 13 | #858 | FieldBindingContextServiceImpl | 1%→73% | **model+field harness** (reusable) |
| 14 | #859 | RelationSyncServiceImpl | 8%→17% | non-relation branches only |
| consolidation | #851 | — | bundle 0.7769 → floor 0.76 | — |

**Current state:** bundle ≈ **0.781**, floor 0.76, security packages 0.85–0.99.

**Remaining gap to 0.80 (~+1250 covered lines) is dominated by hard-to-test classes** that need
heavy fixtures (genuinely multi-session, diminishing per-wave returns — RelationSync's bulk, the
inverse-sync path, needs bidirectional reference metadata; the cheap branches alone gave only ~17
lines):

- `PluginPackageServiceImpl` (631) — plugin zip/jar parse + PF4J install; needs real package fixtures.
- `saas/bootstrap/BootstrapRepairService` (250) + `BootstrapEngineService` (132) — startup repair/seed state.
- `im/websocket/ImWebSocketHandler` (198) — websocket session harness.
- `meta/service/impl/pipeline/phases/AssertPhase` (185) — command-pipeline phase context.
- `meta/service/impl/ActivityEventListener` (144) — needs the activity events published + asserted.
- `RelationSyncServiceImpl` inverse-sync (~175 remaining) — bidirectional reference metadata.
- external: `email/service/GmailApiClient` (117), `iot/tsport/.../TDengineTimeSeriesPort` (112).

The next floor bump (0.76 → 0.77) needs bundle ≥ ~0.79 for the conventional ~2pt flaky margin,
so it should follow the next batch of these heavier waves rather than land per-wave.

**Reusable assets:** worktree `auraboot-cov6` + `aura_boot_clean`/`aura_boot_base` DBs (kept),
the path-B clean-DB recipe, offline-jacoco gradle wiring, and the model+field IT harness (see
`FieldBindingContextServiceImplCoverageIT` / `RelationSyncServiceImplCoverageIT`).

## 9. Session progress log — 2026-06-19 (waves 9–23, floor -> 0.77)

Continued from §8. Waves 9–23 (#852–#870), all merged, each real-stack IT against `aura_boot_clean`:

| wave(s) | classes | result |
|---|---|---|
| 9–12 | SodService 39→77, DrtDefinition 42→97, CloudConfig 47→92, FieldValidation pure validators | clean CRUD/pure |
| 13 | FieldBindingContextServiceImpl | 1→73% (model+field harness, reusable) |
| 14 | RelationSyncServiceImpl | 8→17% (non-relation branches only — see §10) |
| 15 | ActivityEventListener | 7→58% (**@Async unwrapped via `AopTestUtils.getTargetObject`** so onCommandCompleted runs sync in-thread) |
| 16 | AsyncTaskServiceImpl | 38→58% (read/cancel/delete over a seeded task) |
| 17 | AssertPhase | 18→28% (SpEL assert + precondition via hand-built CommandPipelineContext) |
| 18 | VirtualFieldEngine graph | →82% (validateDependencyGraph cycle/acyclic + getComputationOrder via virtual-field harness) |
| 19 | DynamicDataServiceImpl typed coercion + Excel export | convertFieldValue + exportAsExcel(0%); notes update() doesn't string→type coerce like create() |
| 20 | CategoryServiceImpl CRUD + AuditTrailService | AuditTrail 52→86% |
| 21 | DataPermissionEngine no-policy + PostExecutionPhase dry-run | branch coverage |
| consolidation #868 | — | bundle 0.7869 → **floor 0.76→0.77** (1.69pt margin; security pkgs 0.85–0.99) |
| 22 | InvariantDefinitionServiceImpl | 1→89% (CRUD; note: type/severity/scope valid sets are lowercase despite uppercase error text) |
| 23 | SemanticQueryService | 1→24% (empty/cross-model/unknown-model rejects; happy path needs ab_semantic_model harness) |

**Current state:** floor **0.77**, bundle ≈ **0.787**, security packages 0.85–0.99, 26 PRs this run.

## 10. Blocker for the 0.80 target: the biggest gap is unreachable code

The single largest remaining line gap — `DynamicDataServiceImpl`'s relation/sub-table cluster
(~336 lines: getRelationData / saveWithRelations / createRelations / removeRelations / reference
enrichment) — is **unreachable**: `MetaModelServiceImpl.loadModelRelations` is a TODO stub returning
an empty list, so `model.getRelations()` is always empty and the relation happy paths never run.
See [`2026-06-19-dynamicdata-relations-unwired-coverage-finding.md`](2026-06-19-dynamicdata-relations-unwired-coverage-finding.md).

Consequence: ~336 "missed" lines cannot be closed by tests. Reaching 0.80 from 0.787 therefore needs
either (a) implementing `loadModelRelations` (a product change, then those lines become testable), or
(b) excluding the unreachable relation methods from the jacoco denominator with that finding as
justification — **plus** continuing the heavy-fixture waves (PluginPackage 631 / Bootstrap 382 /
ImWebSocket 198), each a multi-hour harness. The quick/medium/model-harness service wins are now
exhausted; the remaining addressable surface is heavy-harness or cheap-branch-only (~17–30 lines/wave).
