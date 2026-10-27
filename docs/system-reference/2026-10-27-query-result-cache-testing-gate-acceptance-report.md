---
type: system-reference
status: active
created: 2026-10-27
---

# Query result reuse verification

Allowed claim: **targeted Core policy and PostgreSQL pipeline-seam verification** at code/test producer `af3c6ed89915ff20d8bbe9dc355513857a41c80e`: 46 hermetic unit tests and 6 real PostgreSQL tests executed, with zero failures, errors or skips. Public HTTP, browser and richer consumer integration were not executed. They remain untested rows, rather than being inferred from backend results.

The durable contract is [query result reuse](./command-transaction-isolation.md#query-result-reuse). The [machine manifest](../e2e/evidence/query-result-cache-2026-10-27/acceptance-manifest.json) derives seven Core requirements from the changed configuration, phases and assertions, and retains two untested consumer requirements. The [generated report](../e2e/evidence/query-result-cache-2026-10-27/report.html), [native JUnit totals](../e2e/evidence/query-result-cache-2026-10-27/junit-summary.json) and [producer receipt](../e2e/evidence/query-result-cache-2026-10-27/producer-receipt.json) refer to the same scope.

## Contract and scope

The existing registered `query` command type may explicitly opt out of result reuse with `executionConfig.cacheResult: false`. The strict boolean survives plugin DTO import, consolidation and stored JSON. Both idempotency phases share the same runtime policy. Missing or true keeps the existing behavior; false on a mutation/custom/action type, alongside a required idempotency key, or alongside an explicit target write action is rejected. No handler-name, HTTP-method or client-payload inference is used.

The declaration grants no authority, changes no READ obligation and does not make the database transaction physically read-only. It does not suppress effects, audit, invariants or invocation authorization. HandlerPhase, PermitPlan and record-scope implementations are outside this increment. Query authors must not disguise writes as queries to evade the write idempotency contract.

## Executed evidence

| Layer | Collected / executed | Passed | Failed / errors / skipped | Evidence |
| --- | ---: | ---: | --- | --- |
| contract/hermetic/unit | 46 / 46 | 46 | 0 / 0 / 0 | [unit summaries](../e2e/evidence/query-result-cache-2026-10-27/junit-summary.json) and seven native suite XML files |
| service/real-stack/it | 6 / 6 | 6 | 0 / 0 / 0 | [PG summaries](../e2e/evidence/query-result-cache-2026-10-27/junit-summary.json) and two native suite XML files |
| API/real-stack/http | 0 / 0 | 0 | did_not_run | Consumer requirement Q08 |
| journey/real-stack/browser | 0 / 0 | 0 | did_not_run | Consumer requirement Q09 |

The unit selection was `CommandResultCachePhasesTest`, `CommandResultCachePolicyTest`, `CommandDefinitionDTOTest`, `ExecutionConfigValidatorTest`, `CommandIdempotencyIntentTest` and `CompletionPhase*Test`, through root `:test`, followed by `:jacocoTestReport` and `:jacocoUnitFullReport`. The full-production coverage report avoids the curated report's DTO/constant exclusions. The new shared policy covered 14/14 lines and 17/18 branches; its strict boolean decoder covered 6/6 lines and 4/4 branches. Complete phase/platform and integration coverage are not claimed. [Extracted counters](../e2e/evidence/query-result-cache-2026-10-27/coverage.json) retain the exact denominator.

The PG selection was `QueryResultCacheRevocationIT` and the existing five `IdempotencyServiceImplCoverageIT` methods through `:platform-app:test --rerun --no-build-cache`. It used the owned slot72 database `auraboot_72`, with the previously applied 157 migrations in `ab_flyway_schema_history`. No schema repair or migration was added. Unit and PG execution used the registered `maritime-parent-file-append` runtime and its isolated Gradle/Maven caches. Full logs remain in that runtime's `result-cache` evidence directory. One restoration attempt reused Gradle's task cache; it is explicitly excluded as new execution. The final forced PG run produced fresh XML timestamps and actual test execution.

The revocation test uses the real `IdempotencyService`, `CompletionPhase`, `DynamicDataService.getById`, Rule Center model READ grant and committed data-scope policy. Each execution creates its own unique tenant, member, role, physical model and result record owned by another actor. It first commits a cacheable query result and proves that the same key really replays. It then changes READ scope from ALL to SELF in PostgreSQL and proves a direct current record read is denied. The ordinary cached configuration remains a deliberate stale-result control. After DTO/consolidation/stored-JSON opt-out, the same key must reach the current record read and fail, leaving the old cached outcome untouched. After restoring ALL, a new-key uncached query returns the exact private field while the idempotency row count remains unchanged.

This composes the production cache phases with a test query phase that performs the production data read. Completion runs with the request's dry-run flag to avoid unrelated public event/audit effects; the test invokes the pipeline directly, so it does not invoke CommandExecutor's dry-run rollback wrapper, and its PG cache transactions commit. No cache, data service or permission verdict is mocked in this integration test. It does not cover the public executor's complete authorization/permit-plan stack or the richer consumer's specialized read accessor. Those boundaries require Root/consumer validation.

The existing PG regression additionally verifies canonical intent conflicts, separation of operations, exactly one concurrent claim owner, and retry after a rolled-back claim. Unit checks preserve absent/true write claim and completion behavior, strict malformed configuration refusal and pre-existing completion event semantics.

## Falsification and trust audit

At pre-fix test producer `9454dafdb31dca41c7a476e2f7e990068a931a64`, the new phase tests executed three cases with two actual failures: the lookup did not skip, and completion required an unclaimed intent. [Native pre-fix failure XML](../e2e/evidence/query-result-cache-2026-10-27/mutation-before.xml) is preserved.

After the first PG green, on the same already-used database, a single controlled mutation changed the shared policy's final false return to true. The real revocation test failed with `Expected AccessDeniedException to be thrown, but nothing was thrown`: the stale same-key outcome short-circuited the current READ. The [native mutation XML](../e2e/evidence/query-result-cache-2026-10-27/mutation-reuse.xml), [exact patch](../e2e/evidence/query-result-cache-2026-10-27/reuse-mutation.patch) and [mutation receipt](../e2e/evidence/query-result-cache-2026-10-27/mutation-receipt.json) retain source SHA, dirty fingerprint and content hashes. The controlled file was restored, the runtime source fingerprint returned to clean, and both final unit and forced PG selections passed. No mutation remains in delivered source.

The fixture checks exact returned data, actual cache availability, denial after a committed policy change, handler execution count and unchanged cache row count. It does not rely on records from earlier runs. No skips, test retries, swallowed assertion failures, weakened thresholds or mocked READ answers were used. The report generator passed its 37 tests and falsifiable self-test. Integration coverage remains `not_measured`; no platform-wide percentage or complete product acceptance is asserted.

## Final Evidence Pack

```text
acceptance_report: this document
claim_level: targeted Core backend policy and PostgreSQL pipeline seam
current_sot: command-transaction-isolation.md#query-result-reuse
business_scope: explicit query-only result-reuse opt-out; existing write idempotency preserved
integration_tests: 6 actual PostgreSQL executions, 0 failures/errors/skips
integration_coverage: not_measured
e2e_specs: no consumer UI spec executed
e2e_collection: 0 browser cases collected
e2e_execution: 0 browser cases executed
feature_action_matrix: 7 Core pass rows; 2 consumer untested rows
browser_evidence: absent; no UI claim
backend_evidence: native unit/PG XML, producer receipt, coverage counters
artifact_evidence: strict DTO/schema/stored-JSON assertions; no deployable candidate built
permission_negative: real committed ALL-to-SELF result-record READ revocation, same key with old cache
visual_feedback: not_in_scope
skip_fixme_threshold_retry_audit: no skips or retries; no threshold reduction
did_not_run: full public executor/HTTP, richer Root composition, consumer browser, complete platform regression
remaining_blockers: consumer-specific public pipeline and UI qualification remain untested
allowed_claim: targeted policy+PG seam verification; no main merge, deployment or full consumer acceptance
```

## SOT Updates

The reusable contract is in [query result reuse](./command-transaction-isolation.md#query-result-reuse); this report retains verification provenance and its limits.
