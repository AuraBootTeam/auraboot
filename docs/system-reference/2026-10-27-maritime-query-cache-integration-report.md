---
type: system-reference
status: active
created: 2026-10-27
---

# Query result reuse: combined host verification

Allowed claim: targeted component integration at `778e91aa60765b34a3e40ba5c0dc3c72d9f1b382`; browser E2E did_not_run (executed=0) for this candidate. No consumer rule lifecycle, maintenance UI or whole-project completion is claimed.

## Final behavior

A trusted query definition may explicitly set `executionConfig.cacheResult=false`. Both lookup/claim/replay and completion outcome persistence use the same strict policy. Missing/true retains previous idempotency behavior. False requires exact query type, conflicts with required idempotency keys and declared non-read target actions, and rejects null/string/number coercion at import and runtime. It does not grant READ, make a database transaction physically read-only, or silently change existing custom-type commands. See [the stable contract](./command-transaction-isolation.md#query-result-reuse).

## Independent execution

The root integration used the existing rich host/API, retained file target append, membership locks, record scopes, empty writer denial and live module metadata repairs. It did not substitute the contributor's standalone host.

| Selected surface | Executed | Passed | Failures/errors/skips |
|---|---:|---:|---:|
| Six exact hermetic suites | 64 | 64 | 0 |
| Real PostgreSQL query result revocation | 1 | 1 | 0 |
| Existing PostgreSQL idempotency intent/concurrency/rollback regression | 5 | 5 | 0 |

JUnit suites and exact producer are in the [machine proof](./evidence/maritime-query-cache-2026-10-27/proof.json). The first application selection included a nonexistent idempotency class pattern and executed only the real query test; it is counted as one. The five actual `IdempotencyServiceImplCoverageIT` methods were then independently selected and executed, without recounting the query case. No skip, retry or threshold relaxation was used.

The query test uses the real idempotency service, dynamic READ path, persisted data policy and physical transactions. It first proves old-result replay, commits an ALL-to-SELF READ policy change, verifies direct READ refusal, then proves that explicit no-reuse with the same request key executes current READ and refuses. Restoring the grant permits a fresh query without adding a cache row. Completion is invoked with its local dry-run field to isolate unrelated events/audit; this is a component pipeline seam, not the full HTTP executor or browser. Independent contributor mutation evidence remains attributed to M's source and is not relabeled as a root mutation. Coverage was not measured for the root scope.

## Candidate artifact boundary

The boot was rebuilt at the exact code producer. Official platform preparation materialized an independent 21-entry view and verified each digest plus embedded/standalone API equality. Relative to the root's existing Core4c/Web7573 view, only runtime and API entries changed; 19 stayed exact. A consumer whose prior Web producer differs must also admit those eight Web packages explicitly and rebuild its shell. The API bytes are attributed to their original identical file-capability producer b297, not renamed to this later host producer.

This candidate does not include the planned announcement sealed-content bridge. Consumer command metadata, full cold import, restrictive-role HTTP/browser, deployment and physical equipment remain separate acceptance tasks. The root product lock was not changed while its existing browser runtime was frozen.

SOT Updates: query metadata and the paired phases are documented in command transaction isolation; original target-file verification remains in its separate report.

```text
acceptance_report: this document
claim_level: targeted component integration
current_sot: command-transaction-isolation.md
business_scope: strict query cache opt-out and current READ after old-result revocation
integration_tests: 6 executed against owned PostgreSQL
integration_coverage: coverage_not_measured
e2e_specs: none for this candidate
e2e_collection: 0 browser
e2e_execution: 0 browser
feature_action_matrix: consumer and full product incomplete
browser_evidence: did_not_run
backend_evidence: 64 hermetic plus 1+5 PostgreSQL cases
artifact_evidence: independent exact 21-entry view; embedded API digest checked
permission_negative: actual persisted READ revocation in component seam
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: no skips/retries; mistaken unmatched pattern reported
did_not_run: consumer HTTP/browser and independent root mutation
remaining_blockers: consumer metadata adoption and native journeys
allowed_claim: bounded backend integration only
```
