---
type: system-reference
status: active
created: 2026-10-27
---

# Exact invocation target permit-plan verification

Allowed claim: scoped host regression and candidate artifacts at `c1866af4c63539fbeba26451d3adf3c477763ce6`. Browser E2E did_not_run (executed=0) for this repair; native resource consumer qualification remains pending.

A resource consumer exposed a distinction between READ authorization and the generic READ SQL filter: the shared target was actually readable and update-authorized, but plan assembly chose SELF, injecting the actor's creator predicate into an exact-target CAS. An explicit invocation authorizer was not recognized by the older handler-target path. The consumer retained the zero-row failure, original roles and unchanged facts; a shared READ is not treated as update permission.

The host repair adds exact successful invocation evidence and preserves TARGET rather than broad ALL. New tests execute the real invocation phase before assembly. Of ten new cases, the old implementation failed exactly the update and delete TARGET expectations, with SELF observed; eight refusal/changed-target cases passed. The repaired producer executed 145 selected hermetic cases with no failures/errors/skips, covering those ten plus existing invocation, target boundary, permit-plan, record-scope, handler and cache-phase regression. See [the machine proof](./evidence/invocation-target-plan-2026-10-27/proof.json).

This is a semantic before/after witness, not a broad UI or PostgreSQL claim. Function authorization, real target READ, domain callback and declared action remain required. Missing/failed callback, read action, different model/target/action and stale success after failed recheck cannot gain TARGET. Existing legacy target authorizers remain covered. No generic permission or ALL grade was added.

The boot was rebuilt and official preparation materialized a separate 21-entry candidate. Relative to the earlier file/query candidate, only the runtime changed; API and 19 other artifacts stayed exact. Embedded/standalone API equality was verified. The resource owner must now build/import and replay its exact shared-read/update-all role case plus denied/revoked/sibling-object regressions. Its old failed case is not relabeled by these host tests.

SOT Updates: [command transaction isolation](./command-transaction-isolation.md#invocation-authorized-target-scope) describes the durable witness and scope behavior.

```text
acceptance_report: this document
claim_level: targeted host regression
current_sot: command-transaction-isolation.md
business_scope: exact invocation target witness and row-scope preservation
integration_tests: consumer PostgreSQL/native pending
integration_coverage: coverage_not_measured
e2e_specs: none executed for repair
e2e_collection: 0 browser
e2e_execution: 0 browser
feature_action_matrix: consumer and full product incomplete
browser_evidence: did_not_run
backend_evidence: 10 old cases with 2 semantic failures; 145 repaired hermetic cases passed
artifact_evidence: exact separate 21-entry candidate
permission_negative: callback/function/READ/target-model-action refusal regressions
visual_feedback: did_not_run
skip_fixme_threshold_retry_audit: no skips/retries or relaxed thresholds
did_not_run: consumer native/HTTP/browser and cross-domain transaction
remaining_blockers: actual resource consumer qualification
allowed_claim: bounded host evidence only
```
