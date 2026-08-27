---
type: system-reference
status: active
created: 2026-08-24
---

# Reliable integration runtime v1 testing-gate acceptance report

AMOS-T01 is accepted for its owned Core runtime and public contract surface: **11 pass, 0 fail,
0 skipped, 3 untested**. It is not accepted as a complete assembled Procurement-to-Inventory
journey because the real T02 producer/adapter and T10 composition are not on this branch.

The executable denominator and machine evidence are in the
[`acceptance manifest`](../e2e/evidence/amos-t01-reliable-integration-2026-08-24/acceptance-manifest.json)
and its adjacent JUnit/JaCoCo artifacts. The tests cover source transaction atomicity and fail-closed
behavior, crash/restart, duplicate delivery, ordering, poison/DLQ, lease takeover, attributed replay,
reconciliation, counters, and health alerts against isolated PostgreSQL.

The runtime package reached 121 covered and 9 missed lines (93.1%); the changed
`IntegrationEventEnvelope` reached 39 covered and 7 missed lines (84.8%). Mutations that reversed the
ordering fence and restored `Map.copyOf(payload)` caused the real-stack lifecycle test and the exact
T04 fixture contract test to fail, respectively, before the final restored green run. No test was
skipped or retried.

## Final Evidence Pack

```text
acceptance_report: docs/system-reference/2026-08-24-reliable-integration-runtime-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: docs/system-reference/reliable-integration-runtime-v1.md; T04 PR #296 head 2280ce79 release-v2.json
business_scope: Core reliable envelope/runtime plus the T04-to-runtime fixture contract; no real T05 consumer composition
integration_tests: 9 pass, 0 fail, 0 error, 0 skipped
integration_coverage: runtime package 121/9 lines (93.1%); IntegrationEventEnvelope 39/7 lines (84.8%)
e2e_specs: not applicable; no UI surface changed
feature_action_matrix: acceptance-manifest.json, 11 pass / 3 untested
browser_evidence: did not run; no browser surface changed
backend_evidence: JUnit XML, real PostgreSQL lifecycle, Flyway validation, schema drift gate
artifact_evidence: T04 exact fixture SHA-256 e66272dce5cf36f64e1d8df70584c98ffdaf3af76cc553857d431ea59711ccdf
permission_negative: not applicable; no permission surface changed
visual_feedback: not applicable; no UI surface changed
skip_fixme_threshold_retry_audit: 0 skipped; no retry/threshold masking
did_not_run: real T02 producer/Inventory adapter; real T05 consumer; T10 exact composition
remaining_blockers: T02/T05 lane composition and T10 assembled rerun
allowed_claim: targeted Core runtime and exact T04 fixture contract pass; assembled business journey remains partial
```

## Residual dependencies

- T02 must bind the versioned fixture to the real Procurement source transaction and Inventory
  receipt command, then prove the business records and the runtime receipt commit together.
- T05 must validate its real consumer against the now hash-pinned T04 fixture contract.
- T10 must compose exact PR heads, regenerate the shared schema snapshot, and rerun the migration,
  contract, and lifecycle gates. This lane does not edit the central AMOS ledger.
