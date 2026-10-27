---
type: system-reference
status: active
created: 2026-10-27
---

# Command-created scalar file component verification

Allowed claim: bounded Core source-component verification. Source `db06c9f3db3b8c9841638cbfd2e63c4e12a6cbd0` executed 92 selected hermetic unit tests and 19 PostgreSQL tests (7 new creation cases and 12 existing parent-append regressions), with zero failures, errors or skips. This is not authenticated product command, browser, download or production qualification.

The durable [created-record file contract](./command-transaction-isolation.md#original-command-created-record-files), [machine manifest](../e2e/evidence/command-created-files-2026-10-27/acceptance-manifest.json), [generated report](../e2e/evidence/command-created-files-2026-10-27/report.html), [native JUnit ledger](../e2e/evidence/command-created-files-2026-10-27/junit-summary.json), [coverage counters](../e2e/evidence/command-created-files-2026-10-27/coverage.json) and [producer receipt](../e2e/evidence/command-created-files-2026-10-27/producer-receipt.json) preserve nine component rows and three untested consumer rows. Integration coverage is not measured; counts from previous capability slices are not added to this selection.

## Exact delta and environment

The delta starts after `31ac74ac5344666f4689ee922f7016944dcd941b`. Only two FileAccessor interface copies, DynamicDataAccessorImpl, CommandTargetFileAccessorFactory and FileMapper change in production. HandlerPhase, PermitPlan, ModelMutationGuard, other rich API surfaces and applied migrations are untouched. There is no new DDL or generic permission. The successful singleton create/tryCreate host hook privately records actual creation; lookup, bulk and batch methods cannot manufacture receipts.

Validation reused the original registered `maritime-parent-file-append` runtime, slot72 database `auraboot_72`, isolated Gradle/dependency state and owned evidence root. The new IT creates a fresh unique model/table pair per execution and a real FileService local-storage upload per case. A synthetic MetaContext actor and real authorized root-command frame drive actual Core PostgreSQL transactions. It does not qualify HTTP login, directory membership or A's public command admission. The runtime has no product manifest or admitted backend/Web/BFF stack, so `RUNTIME_MANIFEST_MISSING` remains an explicit limit. Existing database rows cannot satisfy this run's fresh fixture assertions.

## Executed assertions

The new PG happy path creates an immutable child with a formal VARCHAR-compatible scalar file field already containing the PID, calls the new SPI twice, and commits exactly one retained relation with row_version still1. A targetless create command completes its own child but cannot append to a parent. The old UPDATE-based link API still refuses that immutable child and rolls back creation.

The actual prior-transaction record, wrong file, nested-command creation and nonowner finalized file are denied. An explicit downstream domain failure rolls back the new business row, relation and retention together. Unit cases exercise fresh physical transaction IDs, rollback-only and unwritable physical connections, handler close, cross-thread/actor/tenant/nested-command use, formal field shape, exact CREATE/field writers, changed returned projection/version and occupied single slots. The 12 PG parent-append regressions cover the existing scope and retention/deletion/concurrent append semantics after the shared helper extraction.

The own branch's pre-existing ModelMutationGuard requires nonempty command-writer lists. Its immutable PG fixture therefore uses different UPDATE/DELETE writer commands and the exact allowed CREATE writer. Root's independently fixed empty-list deny-all interpretation is a required integration dependency; it was neither overwritten nor represented as tested here. The integrating host must preserve that guard while applying this delta. New field negatives assert the existing FieldWriterGuard and ModelMutationGuard exception types/messages; an earlier fixture compile error and overly narrow expected exception type were corrected without changing production guards or weakening refusal assertions. Earlier failed logs remain in owned raw evidence.

## Falsification and restoration

After the first seven PG cases and lifecycle unit selection passed, a controlled source mutation substituted an ordinary current-row lookup when no host creation receipt existed. The actual prior-transaction case failed with `Expected RuntimeException to be thrown, but nothing was thrown`: the mutant wrongly accepted an existing record. The [exact patch](../e2e/evidence/command-created-files-2026-10-27/lookup-mutation.patch), [native red XML](../e2e/evidence/command-created-files-2026-10-27/mutation-lookup.xml) and [receipt](../e2e/evidence/command-created-files-2026-10-27/mutation-receipt.json) bind source `72c48018ef056b9520011345e3c9f066ec476289`, dirty source hash and expected nonzero exit. Production source was restored byte-for-byte before the final producer selection. Only tests changed between that restoration and the final producer.

The final forced selections used `--rerun`, with `--no-build-cache` on PostgreSQL tests. No skip, automatic retry, swallowed failure, threshold reduction or cached test result counted as execution. The report generator's tests and falsifiable self-test were also executed. The three untested integration rows remain in the denominator.

## Native component candidate

Official `prepareLibraryCandidate` used publication roots `:,:platform-plugin-api`, fresh isolated output and version `1.0.1-rc.3` at the same final producer. The [native receipt](../e2e/evidence/command-created-files-2026-10-27/native-candidate-receipt.json) selects exact `artifact-version.jar` primary files and asserts equality with inventory compiledJar hashes. Five Core classes match the compilation exercised by tests; one shared API class matches the native API compilation.

| Primary publication | SHA256 |
| --- | --- |
| com.auraboot:auraboot-core:1.0.1-rc.3 | db5263a932e77202728fa19af667115119bc8ce781c8dd14865d58c6adc0727e |
| com.auraboot:platform-plugin-api:1.0.1-rc.3 | 3d9ff631223e39a026e8a3ddbb5f6e861647e67835f4dfdeab6c00f96a8d881b |

This candidate is not a wholesale replacement for Root's richer Core/API. Root must integrate the exact source delta and emit one combined candidate; two API JARs are not a compatibility solution. Remote publication, main merge, product admission and deployment were not run. Documentation/evidence commits after the producer do not relabel its test or artifact source identity.

## Consumer limits

A's original verify/reverify/grant paths have a root target; import prepare has none. Both fit only when successful singleton CREATE already persists the exact scalar file PID. Historical nonowner proof reuse must retain its independently checked original relationship; generated exports that create an empty file slot and later update it remain a separate workflow. This API does not authorize either path, replace a relation, grant general READ/UPDATE or change immutable model policy. Public download authorization across all active file relations also needs consumer qualification. No atomic external object-store or global IAM linearizability guarantee is added.

## Final Evidence Pack

```text
acceptance_report: this document
claim_level: bounded backend source component
current_sot: command-transaction-isolation.md#original-command-created-record-files
business_scope: exact singleton command CREATE with prefilled formal scalar file PID
unit_tests: 92 selected tests; 0 failures/errors/skips
integration_tests: 7 new PostgreSQL/local-storage + 12 parent-append PostgreSQL regressions; 0 failures/errors/skips
integration_coverage: not_measured
e2e_specs: 0 public HTTP/browser specs executed
feature_action_matrix: 9 component pass; 3 consumer integration untested
browser_evidence: none; no UI acceptance claim
backend_evidence: native unit/PG XML; real CREATE/relation/retention rollback; old-record mutation red and restoration
artifact_evidence: exact native primary JARs; five tested Core class matches and one native API class match
permission_negative: owner, CREATE/field writer, formal field, provenance, actor/thread/transaction/lifetime checks
visual_feedback: not_in_scope
skip_fixme_threshold_retry_audit: no skip, retry or weakened threshold counted
did_not_run: Root rich integration and [] policy composition; authenticated A public commands/browser/download; separate history/export workflows
remaining_blockers: three explicitly untested consumer rows; no product runtime admission
allowed_claim: bounded component/source candidate verification only
```

## SOT Updates

The reusable API, creation provenance, lifecycle, scalar field and SQL atomicity contract is in [the command consistency reference](./command-transaction-isolation.md#original-command-created-record-files), linked from the documentation map. This report preserves its verification provenance and explicit limits.
