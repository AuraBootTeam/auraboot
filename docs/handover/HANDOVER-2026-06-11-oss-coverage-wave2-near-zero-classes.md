---
type: handover
status: active
created: 2026-06-11
---

# HANDOVER — OSS coverage wave 2: six near-zero meta/service/impl classes (2026-06-11)

Continues `HANDOVER-2026-06-11-oss-coverage-meta-service-impl.md`. This wave cleared the
handover's "near-zero backend classes (high ROI)" list.

## State — merged to main (#581 → `342d889ff`)
Real-stack IT for all six near-zero `meta/service/impl` classes. Built by six parallel
sub-agents (one worktree/branch each), cherry-picked into one consolidation branch,
**independently re-verified together** (249 tests, 0 failures, no cross-test pollution),
then one full-suite run + gate bump. All session worktrees/branches removed; canonical
`auraboot` on `main`.

| Class | LINE before→after | Tests |
|---|---|---|
| EdiService | 0.3% → **96.3%** (315/327) | 35 |
| QueryAuditServiceImpl | 8.5% → **82.1%** (800/975) | 36 |
| SecureQueryExecutorImpl | 16% → **74.3%** (326/439) | 88 |
| SchemaAccessProjectorImpl | 0.4% → **57.2%** (139/243, structural ceiling) | 39 |
| FieldForkServiceImpl | 1% → **87.2%** (102/117) | 23 |
| FieldImpactAnalysisServiceImpl | 1% → **100%** (104/104) | 28 |

`meta/service/impl` **56.1% → 65.4%** (11420/17476); gate-denominator bundle **73.2% → ~75%**
(full-suite report 46850/61885 = 75.7%; gate-curated `0.75`). **jacoco BUNDLE LINE floor
0.71 → 0.73** (same ~2pt flaky margin), `:jacocoTestCoverageVerification` BUILD SUCCESSFUL.

## Product findings (real-stack IT earns its keep)
**Fixed in-band** (mechanical jsonb-typeHandler omissions — both were live runtime bugs):
- **EDI** entities `authConfig`/`mappingTemplate`/`validationRules`/`parsedData` were jsonb
  columns whose `@TableField` lacked `JsonbStringTypeHandler` → the live `EdiController`
  create/update/send/receive endpoints 500 on any non-null jsonb payload.
- **QueryAuditLog.conditions** (the only jsonb column on `ab_query_audit_log`) — same omission;
  every `@Async` audit-log insert threw and was swallowed by `catch(Exception)`, so query
  audit logging was **silently non-functional at runtime**.

**Characterized, NOT fixed** — `docs/backlog/2026-06-11-meta-impl-coverage-product-findings.md`:
1. `SecureQueryExecutorImpl.getQueryCache` — `@Cacheable` method returns `null`; the `secureQuery`
   cache disallows nulls → `IllegalArgumentException` on every cache miss (cache read unusable).
2. `SecureQueryExecutorImpl.executeWithTimeout` — `CompletableFuture.supplyAsync` on the common
   ForkJoinPool loses `MetaContext`/security ThreadLocals → any positive `timeoutMs` throws.
3. `SchemaAccessProjectorImpl.parseSchemaContent` — unimplemented stub returning `{}` →
   `filterSchemaFields`/`calculateFieldPermissions` field filtering is dead (no-op); + ~10
   uncalled private helpers. (This is the 57.2% structural ceiling.)

## Next slices (each its own session; tracker `docs/backlog/2026-06-10-oss-coverage-to-80-tracker.md`)
- **`DynamicDataServiceImpl` deeper** (executeCustomAction ~450 LOC / saveWithRelations /
  relations) — needs multi-model + action/relation fixtures; heavier slice.
- **Fix the 3 characterized findings** above (separate fix PRs, not coverage PRs).
- **Frontend #14** — vitest ~30% ceiling; to 80% needs Playwright E2E V8 coverage merged
  (`coverage:e2e` harness exists). Needs the GA E2E stack up — a dedicated infra session.
- **#3 infra subprojects** (storage/mq) — Docker testcontainers.

## How to run (reliable) + lessons this wave
```bash
cd platform
# one class (dev loop, safe on shared :5432, ~1.5 min):
./gradlew :test --tests 'com.auraboot.framework.meta.service.impl.<Class>IntegrationTest' :jacocoTestReport --continue
# full suite for the gate (one consolidation only):
./gradlew :cleanTest :test :jacocoTestReport --continue
#   ↑ if the suite has failures (it does — pre-existing flake), BUILD FAILED leaves a PARTIAL
#     jacoco report. REGENERATE from the .exec before reading coverage:
./gradlew :jacocoTestReport -x test          # then re-read jacocoTestReport.xml
./gradlew :jacocoTestCoverageVerification -x test -x jacocoTestReport
```
Lessons (fold into canon if they recur):
- **A `@TableField` String mapped to a jsonb column MUST declare `typeHandler =
  JsonbStringTypeHandler.class`** (every sibling does). Without it, insert/update of a non-null
  value throws `column "..." is of type jsonb but expression is of type character varying` — and
  the host DB URL has no `stringtype=unspecified`, so it fails in production, not just tests.
  Coverage IT surfaced two live instances (EDI, QueryAuditLog). When seeding such columns in an
  IT, prefer null unless testing that field.
- **After a failed full-suite run, the jacoco report is partial** (read 9% once); always
  `:jacocoTestReport -x test` to regenerate from the `.exec` before trusting the number.
- **Get the exact gate ratio** without a glob re-implementation: temporarily set the BUNDLE
  floor to 0.99 and run `:jacocoTestCoverageVerification` — the failure message prints the real
  `lines covered ratio is 0.XX`. Restore.
- Parallel sub-agents for independent coverage classes (each its own worktree/branch) +
  cherry-pick into one consolidation branch + a combined targeted re-run (independent
  verify-don't-trust) + one full-suite gate bump = clean throughput. 6 classes, 0 fakes.
- Full suite had 43 pre-existing/unrelated failures (acp/crm/eventpolicy/rag-embedding/bpm —
  shared-DB concurrent-session flake); none were the 6 new classes. Classify env-flaky, not
  regression (none touch the changed entities).
