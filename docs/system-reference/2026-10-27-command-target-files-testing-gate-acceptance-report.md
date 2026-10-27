---
type: system-reference
status: active
created: 2026-10-27
---

# Command target file capability verification

Allowed claim: **targeted Core backend verification**, at source
`0642b5fc6041030e0750c233912694b00bd01642`: 196 hermetic unit tests and 16 real PostgreSQL tests
executed successfully, with zero failures or skips. Mixed-role HTTP interception and the complete
browser maintenance journey were not executed here. They remain explicit untested consumer rows.

The durable contract is [original command target attachments](./command-transaction-isolation.md#original-command-target-attachments).
The machine denominator has [16 Core rows and two untested consumer rows](../e2e/evidence/command-target-files-2026-10-27/acceptance-manifest.json).
Its [rendered evidence report](../e2e/evidence/command-target-files-2026-10-27/report.html) is generated
from the same manifest and native JUnit evidence, not a separate coverage ledger.

## Executed scope

| Layer | Collected / executed | Passed | Failed | Skipped | Source and evidence |
| --- | ---: | ---: | ---: | ---: | --- |
| Hermetic contract/unit | 196 / 196 | 196 | 0 | 0 | Exact source above; [JUnit summary](../e2e/evidence/command-target-files-2026-10-27/junit-summary.json) |
| Service/real-stack/IT | 16 / 16 | 16 | 0 | 0 | Same source, owned runtime and PostgreSQL database; adjacent raw JUnit XML |
| Mixed-role HTTP interceptor | 0 / 0 | 0 | 0 | 0 | did_not_run; consumer row C01 |
| Browser journey | 0 / 0 | 0 | 0 | 0 | did_not_run; consumer row C02 |

The real-stack run used the single owned `maritime-parent-file-append` runtime, slot 72,
`auraboot_72`, PostgreSQL 17.6, and 157 explicitly applied successful Flyway migrations. The new
suite measures `current_database()` and checks it against the managed runtime's database before
creating fixtures. Core schema was migrated before application startup. Business fixture models
were materialized through the official metadata/schema services; no test repairs the schema with
`ALTER TABLE`. Unique fixture models, parent PIDs and upload PIDs prevent old rows satisfying new
assertions. The storage provider is an external dependency substitute; actual object-store I/O is
not claimed.

JaCoCo unit line measurements are [recorded separately](../e2e/evidence/command-target-files-2026-10-27/coverage.json):
the target capability factory and nested classes cover 112/115 lines (97.39%); the shared legacy
relation guard covers 37/38 (97.37%). Real-stack integration coverage is `coverage_not_measured`.
Line coverage does not replace the PostgreSQL concurrency and atomicity assertions.

The PG tests prove owned finalized unbound multipart staging, parent VARCHAR JSON-string projection,
append preservation/idempotency, duplicate concurrent append, retain/delete arbitration, delete-first
rejection, original-transaction rollback, caught-rejection rollback-only and physical
`REQUIRES_NEW` rejection. They also supply a broad server parent UPDATE permit without an exact
field writer and verify that legacy replacement, empty-array deletion, removal, append and both
`linkExisting` branches are denied. The prior relation, retention, parent JSON, evidence and row
version remain unchanged. Ordinary legacy file-field replacement remains successful; exact command
writers still cannot mutate an immutable file field. These are service/PG authorization assertions,
not a claim that the HTTP permission interceptor ran.

## Falsification

[Three controlled mutations](../e2e/evidence/command-target-files-2026-10-27/mutations.json) retain their
own original source identities and failure XML. They are not relabeled as final producer runs:

- Omitting retention made the real PG happy path fail because `retention_locked` remained false.
- Omitting the factory's original-frame check made a preceding real `HandlerPhase` Spring handler's
  nested command acquire a non-null capability; its denial assertion failed.
- Omitting the legacy mutation guard allowed replacement of a protected field's relation. The
  test failed because no denial was raised, and the [actual DB witness](../e2e/evidence/command-target-files-2026-10-27/legacy-bypass-db-witness.json)
  shows the unchanged parent JSON/evidence/version pointing at a different file from the active
  relation. The original file remained retained; the replacement file was not retained.

Production bytes were restored before the final source-frozen unit and PG runs. There are no test
retries, disabled cases, threshold changes or exception-to-success fallbacks. The race test accepts
only the expected deletion/retention denial; unrelated exceptions fail its future.

## Native component artifacts and integration boundary

The official `prepareLibraryCandidate` producer generated an isolated native Maven candidate at the
same exact source above, on `refs/heads/codex/maritime-parent-file-append`. Its
[producer receipt](../e2e/evidence/command-target-files-2026-10-27/producer-receipt.json) verifies published
bytes against original compiled JARs, the typed API signature in both JARs, and seven critical Core
class digests against the compiled test runtime classes.

| Coordinate | SHA-256 |
| --- | --- |
| `com.auraboot:auraboot-core:1.0.1-rc.3` | `7beae33db78ec1cb17d0d8edccf2f265bf1c5703d119c841324bc48fb1c8f0c7` |
| `com.auraboot:platform-plugin-api:1.0.1-rc.3` | `a9c5cd12925bb3ac246014b0cde746e27fcff3c5a20cbb8c41b9a5020e329ffc` |

This is a component candidate, not the complete consumer composition. Integrators must apply the
implementation delta to their existing richer Core/API source line, preserve its other capabilities,
and produce a combined candidate. Do not replace a richer API with this standalone baseline merely
because the Maven version matches. The consumer also needs its separately owned model-writer
empty-list repair and native registry integration fixes. No production deployment or remote Maven
publication occurred.

The frozen baseline is `93f062128449bc9f1515b314d261645bc0d59297`. The existing published-model
reimport hook repair was preserved through minimal dependency commits `3703229bb` and `78faa9870`
(from `18b4b21ca` and `49b5f8035`); it was not reimplemented. Implementation deltas are `3a79b3d2f`,
`fbc37b2ed` and `50d5767fe`; the complete verified test tree is the exact source above. Integrators
must reconcile the `HandlerPhase` context plumbing with their existing capability installs rather
than replace that file wholesale.

## Reproduction

Run the root unit Test task and application Test task as separate filtered invocations; Gradle's
`--tests` option applies to its selected task, not an earlier unfiltered Test task. Full commands and
per-case native results are retained with the owned runtime evidence round
`2026-10-27T00-13-27-451Z-8dd3c89d-7d0d-48d4-9fa4-f96067af6a84`. Use the exact source and managed
runtime environment, point `TEST_DATABASE_URL`/`TEST_DATABASE_USERNAME` at its owned database, and
run `:test` for the listed unit suites and `:platform-app:test` for `CommandTargetFileAppendIT`,
`FileRelationTenantScopeIT` and `FileRetentionDeletionRaceIT`. Use `--rerun` on each selected Test
task when requiring new native execution. The initial accidentally unfiltered run was canceled and
is excluded from every final count.

## SOT Updates

The stable API, caller transaction responsibility, staging upload and legacy relation protections
are maintained in the existing command transaction reference linked above.

## Final Evidence Pack

```text
acceptance_report: docs/system-reference/2026-10-27-command-target-files-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: command-transaction-isolation.md#original-command-target-attachments
business_scope: Core target-bound append/retain plus protected legacy relation writes
integration_tests: 16 executed, 16 passed, 0 failed/errors/skipped; real PostgreSQL
integration_coverage: coverage_not_measured; unit line measurements reported separately
e2e_specs: no owned UI change
e2e_collection: browser collected=0
e2e_execution: browser executed=0; did_not_run
feature_action_matrix: acceptance-manifest.json; 16 Core pass rows, 2 consumer untested rows
browser_evidence: did_not_run
backend_evidence: adjacent native JUnit XML and per-case summary
artifact_evidence: producer-receipt.json, compiled/native JAR and critical-class digest checks
permission_negative: exact writer/immutable/actor/target/tenant/lifetime/physical-transaction cases
visual_feedback: not applicable to this Core API slice
skip_fixme_threshold_retry_audit: no skipped cases, retries or reduced thresholds
additional_checks: OSS boundary; reset/init contracts; report generator self-tests
remaining_blockers: consumer composition, actual mixed-role HTTP and native browser journey remain external to this Core slice
allowed_claim: targeted Core backend behavior and native component production verified at the stated source
```
