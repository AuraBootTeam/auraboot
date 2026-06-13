---
type: handover
status: active
created: 2026-06-11
---

# Session Handover — OSS coverage wave 3 + defect fixes (2026-06-11)

Continues the OSS coverage → 80% line (prior:
`HANDOVER-2026-06-11-oss-coverage-wave2-near-zero-classes.md`). This session: wave 3
(4 more near-zero classes), a repo-wide jsonb-typeHandler audit, and fixing the
behavioral/jsonb defects the coverage waves surfaced.

## Session Summary
Cleared the remaining near-zero `meta/service/impl` standalone classes with real-stack IT,
audited the recurring jsonb-typeHandler omission repo-wide, then fixed the genuinely-fixable
surfaced defects (3 real fixes + 2 corrections). **5 PRs merged this session.**

## Tasks Completed (all merged to `main`)
- **#581 / #582** (wave 2): real-stack IT for 6 near-zero classes (Edi/QueryAudit/SecureQuery/
  SchemaProj/FieldFork/FieldImpact); gate floor 0.71→0.73; 2 live jsonb fixes; handover.
- **#585** (wave 3): real-stack IT for **OtDeviceService 0→92%, FieldChangeAuditService 0→100%,
  ExportTaskService 0→78%, DefaultCurrencyConversionHandler 0.7→88%** (+109 tests). 3rd live
  jsonb fix (OtDevice/OtDataLog). **Gate floor NOT bumped** (env was noisy — see Pitfalls).
- **#587**: repo-wide jsonb-typeHandler audit (33 candidates → 6 true → reported 2 remaining).
- **#591**: fixed 3 coverage-surfaced defects + regression ITs (see below) + corrected 2
  mis-reported findings.

## Key Decisions
| Decision | Chosen | Rationale |
|---|---|---|
| Wave 3 target selection | static "niche/standalone, no same-name test" heuristic, agents report real baseline | a fresh full-suite baseline was unreliable (concurrent enterprise `bootRun` → 2554 env-flaky failures); niche standalone services are safe near-zero bets |
| Wave 3 gate bump | **deferred** (floor stays 0.73) | gate bump needs a clean full-suite; tests only raise coverage so 0.73 still passes — bump in the next clean consolidation |
| `getQueryCache` fix | rewrite to direct `CacheManager` get/put/evict (drop `@Cacheable`) | `@Cacheable` + self-invocation made caching a silent no-op AND threw externally (allowNullValues=false) |
| `executeWithTimeout` fix | `withMetaContext` capture/restore into the worker | tenant-isolation-sensitive; supplyAsync on ForkJoinPool dropped MetaContext |
| `parseSchemaContent` (F3) | **do NOT implement** | `SchemaAccessProjector` has 0 OSS callers — implementing a stub = building unused code (config/architecture discipline); flagged for removal |
| `InvariantEvaluationLog` | **false positive, not fixed** | its mapper inserts with explicit `#{...}::jsonb` cast (custom mapper, not BaseMapper) → no typeHandler needed |

## Files Changed (merged via PRs; canonical is clean on `main`)
### #591 (last PR — the defect fixes)
- `platform/.../meta/service/impl/SecureQueryExecutorImpl.java` — CacheManager-based cache get/put/evict; `withMetaContext` for timeout context propagation.
- `platform/.../rag/entity/KbChunk.java` — `JsonbStringTypeHandler` on `metadata`.
- `platform/.../meta/service/impl/SecureQueryExecutorImplIntegrationTest.java` — rewrote the 3 old cache "characterization-of-bug" tests to assert correct behavior + added timeout regression test (90 tests, 0 fail).
- `platform/.../rag/entity/KbChunkMetadataJsonbIntegrationTest.java` — new round-trip IT.
- `docs/backlog/2026-06-11-meta-impl-coverage-product-findings.md` — F1/F2 marked fixed, F3 dead, InvariantEvaluationLog false-positive + audit lesson.

## Pitfalls & Workarounds
1. **Full-suite jacoco report read 9% after a failed run** — `:test` BUILD FAILED leaves a PARTIAL jacoco report. Always `./gradlew :jacocoTestReport -x test` to regenerate from the `.exec` before trusting numbers (real was 75.7%).
2. **2554 env-flaky full-suite failures** — a concurrent enterprise `bootRun` on the shared `:5432` corrupts shared state for the full suite (NOT relation-missing — assertion failures). **Targeted single-class IT stayed 100% robust** (per-tenant isolation). Classify env-flaky, don't thrash; defer anything needing a clean full-suite.
3. **jsonb audit over-reported** — name+column-type matching flags false positives: an entity is safe if its **mapper** casts `#{x}::jsonb` explicitly (custom mapper) instead of relying on the `@TableField` typeHandler. Only **BaseMapper auto-insert/update** of a String→jsonb field without the handler is the real bug. Verify the mapper, not just the column type (§15).
4. **jsonb read-back reorders keys AND normalizes whitespace** — `{"a":1,"b":2}` comes back `{"b":2,"a":1}` (space after colon too). Assert semantically (contains key/value) or parse, never exact-string.
5. **Exact gate ratio** — temporarily set the BUNDLE floor to `0.99`, run `:jacocoTestCoverageVerification`, read `lines covered ratio is 0.XX` from the failure, restore.

## Lessons Learned
- Coverage IT is a bug-finder: **4 live jsonb-typeHandler bugs** found across the waves (EDI controller 500 / query-audit silently broken / OtDevice register+data 500 / KbChunk latent). A parallel session shipped the systemic fix (**#592** — shared `JsonbColumns` helper + lint) that my audit (#587) recommended.
- Before "fixing" a surfaced defect, verify it's **wired** — `SecureQueryExecutor` and `SchemaAccessProjector` have ~no OSS callers; don't implement stubs for dead services.
- Parallel sub-agents (own worktree each) → cherry-pick into one consolidation branch → combined targeted re-run (independent verify-don't-trust) → one PR is a clean, robust throughput pattern under a noisy shared DB.

## Current State
### Git
- Canonical `auraboot` on `main`, clean except untracked files from **another session** (`web-admin/playwright.andon.config.ts`, `web-admin/tests/e2e/workbench/pe-andon-workbench.golden.spec.ts`) — **do NOT touch**.
- `origin/main` @ `4257194aa` (includes all 5 of this session's PRs + the parallel #592).
- All session worktrees/branches removed; disk back to ~72Gi.

### Infra
- Shared host Postgres `:5432` (`aura_boot`), Redis `:6379`. **A concurrent enterprise `bootRun` may still be running** — full-suite runs are unreliable until it stops; targeted runs are fine.

## Next Steps (each its own session; all heavier / different-infra)
1. **clean-env gate bump** — when `:5432` is quiet, one clean `:cleanTest :test :jacocoTestReport`, regenerate report, raise BUNDLE floor 0.73 → ~0.75 to lock in wave 3.
2. **`DynamicDataServiceImpl` deeper** — executeCustomAction (~450 LOC) / saveWithRelations / relations; needs multi-model + action/relation fixtures (heavier slice).
3. **Remove dead `SchemaAccessProjector`** — confirmed 0 OSS callers (service + 10 uncalled helpers); or a real impl if a caller is wired.
4. **Frontend #14** — vitest ~30% ceiling; to 80% needs Playwright E2E V8 coverage (needs GA E2E stack — dedicated infra session).
5. **#3 infra subprojects** (storage/mq) — Docker testcontainers.

## Context for Next Session
- Tracker (authoritative, all waves): `docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md`.
- Findings + audit + resolutions: `docs/backlog/2026-06-11-meta-impl-coverage-product-findings.md`.
- Run recipe (reliable, targeted): `cd platform && ./gradlew :test --tests '...<Class>IntegrationTest' :jacocoTestReport --continue`.
- Concurrency check before any full-suite: `pgrep -fl bootRun` (if an enterprise bootRun is up, expect env-flaky full-suite failures).
