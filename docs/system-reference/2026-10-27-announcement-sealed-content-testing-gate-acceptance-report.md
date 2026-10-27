---
type: system-reference
status: active
created: 2026-10-27
---

# Sealed announcement content component verification

Allowed claim: **bounded Core source-component verification**, with 179 host hermetic tests, 4 shared-API tests and 10 actual PostgreSQL/local-storage tests executed successfully, with zero failures/errors/skips. This is not D027 product acceptance, a real BPM approval qualification, public HTTP/browser acceptance or a drop-in replacement for the richer consumer API.

The durable contract is [sealed content proofs](./announcement-management.md#sealed-content-proofs-for-original-business-commands). The [machine manifest](../e2e/evidence/announcement-sealed-content-2026-10-27/acceptance-manifest.json) contains 12 tested component requirements and four explicitly untested integration/qualification requirements. Its [generated report](../e2e/evidence/announcement-sealed-content-2026-10-27/report.html), [native JUnit ledger](../e2e/evidence/announcement-sealed-content-2026-10-27/junit-summary.json), [coverage counters](../e2e/evidence/announcement-sealed-content-2026-10-27/coverage.json) and [producer receipt](../e2e/evidence/announcement-sealed-content-2026-10-27/producer-receipt.json) share that scope.

## Source, dependency and schema identities

Host/API unit evidence is bound to `6c3947f759b4e8888205b5a9b1da4480c89288fc`. Final PG and native candidate evidence is bound to `4ec7b97131e7a52408be436e41b9fe2439e3726c`. Between those commits, production Java and API source are byte-identical; only the PG lock oracle and the officially generated schema snapshot changed. The lock oracle now checks actual PostgreSQL SQLSTATE `55P03`; this environment reports it as `UncategorizedSQLException`, so assuming a Spring exception subclass was an evaluator error, not absence of the root lock. Its original failure is retained in owned runtime evidence.

Only four pre-existing membership-lock sources were extracted from approved dependency `4575ba72211e697c668662ae816faf1c086d4113`; their byte-exact source hashes are in the receipt. No rich branch was absorbed. HandlerPhase, PermitPlan, model ownership and the recipient acknowledgement implementation were not edited. Root owns the minimal HandlerPhase issuance hook and richer API composition.

Forward migration `V20261027100000__announcement_sealed_content.sql` was unoccupied, then applied through the official Flyway script to the existing owned slot72 `auraboot_72` database. It is now fixed: 158 migrations are applied. The official snapshot generator rebuilt a fresh generator-owned database, produced the 66-line additive schema delta, and removed that temporary database; it did not dump business fixtures or reset the retained test database. The migration and generated snapshot hashes are recorded in the producer receipt.

## Executed ledger

| Layer | Collected / executed | Passed | Failed / errors / skipped | Producer |
| --- | ---: | ---: | --- | --- |
| host contract/hermetic/unit | 179 / 179 | 179 | 0 / 0 / 0 | `6c3947f759b4e8888205b5a9b1da4480c89288fc` |
| shared API contract/hermetic/unit | 4 / 4 | 4 | 0 / 0 / 0 | same unit producer |
| service/real-stack/it | 10 / 10 | 10 | 0 / 0 / 0 | `4ec7b97131e7a52408be436e41b9fe2439e3726c` |
| public HTTP / actual BPM engine / P mapping | 0 / 0 | 0 | did_not_run | no acceptance inferred |
| browser journey | 0 / 0 | 0 | did_not_run | no UI claim |

The registered Workspace Gradle runner used the existing `maritime-parent-file-append` runtime and isolated caches. Root `:test` selected `*Announcement*Test` and `*PermissionSnapshotCacheTest`; API `:platform-plugin-api:test` selected `*AnnouncementDocumentCanonicalizerTest`. Existing announcement tests were included with the new service, factory, writer-order and snapshot/document vector tests. Full-production JaCoCo and the API report were generated from those executions, avoiding curated DTO/constant exclusions. Main service line coverage was 111/116; main factory 39/40; shared canonicalizer 42/43; `withFreshReads` 6/6 lines and 4/4 branches. Nested classes and exact branch counters are included in the machine evidence. Integration coverage and full-platform coverage are not measured, and branch completeness is not claimed.

The real-stack selection was `:platform-app:test --rerun --tests '*AnnouncementSealedContentIT' --no-build-cache`, with fresh native XML timestamps. It used PostgreSQL 17.6 and the real local storage implementation in a test-owned temporary directory. Neither storage, membership locking, publisher access, permission loading, approval proof verification nor the seal mapper was mocked in this integration class. Full logs and the retained fixture database/storage remain in the owned runtime evidence rather than being destroyed before review.

### What the PostgreSQL fixtures prove

Each test creates unique tenant/user/member/role/permission, real uploaded bytes, an official announcement root/version through the real mappers, and controlled persisted submitted-approval/terminal-result contract fixtures. The actual `AnnouncementApprovalProofService` validates those exact neutral bindings. **Those terminal fixtures are not an approval performed by a real BPM engine and are not production-admitted approval evidence.** The integration denominator explicitly retains the real official submit/prepare/BPM/release-worker journey as untested.

The tests prove same-transaction fresh-version seal insertion, immutable evidence and historical no-backfill refusal; publisher-owner proof without recipient eligibility; denial for an owner without publish permission; exact raw content/title and observed byte identity; same-locator byte tampering rejection both before a proof and after a successful proof at commit; and rollback of a same-transaction withdrawal after successful proof acquisition. A concurrent root writer receives the exact `55P03` lock refusal while the proof transaction holds the root, then acquires the lock after commit.

The fresh-read regression removes a publish grant in the caller transaction and confirms beforeCommit denial and rollback, while another thread retains the correct original shared authorization snapshot. A second test adds a new grant without committing: the fresh scope sees it through the existing loaders, another thread does not, and rollback does not poison normal cached results. Hermetic tests additionally verify all component cache layers, contributor-preserving loader reuse, nested exception cleanup, non-inheritance to a worker and an unaffected other-tenant cached reader. No global cache invalidation or second permission evaluator was introduced. Independent contributor caches and original transaction-isolation visibility remain outside that scope's guarantee.

## Falsification

The first real PG selection at `eadfc515b868e9948ccc17ee727b09b666cbda44` executed six cases with one failure: user-snapshot eviction left a stale role-permission grant, so same-transaction revocation incorrectly committed. [Native failure XML](../e2e/evidence/announcement-sealed-content-2026-10-27/mutation-cached-grant.xml) and its [receipt](../e2e/evidence/announcement-sealed-content-2026-10-27/cached-grant-receipt.json) are retained. The fix uses scoped uncached reads through existing loaders; tests were not made green by adding fixture eviction.

After nine PG cases passed, a single controlled source mutation removed factory beforeCommit revalidation. On the same already-used owned database, the real withdrawal case failed: `Expected RuntimeException to be thrown, but nothing was thrown`. The [exact patch](../e2e/evidence/announcement-sealed-content-2026-10-27/omit-commit-mutation.patch), [native XML](../e2e/evidence/announcement-sealed-content-2026-10-27/mutation-commit.xml) and [source/fingerprint receipt](../e2e/evidence/announcement-sealed-content-2026-10-27/mutation-commit-receipt.json) retain the red evidence. The controlled file was restored, the final lock test was added/qualified, and the final ten-case PG selection executed successfully. No mutation remains in delivered source.

No skip, test retry, swallowed assertion, weakened threshold, fabricated generation, historical hash rewrite, current-hash backfill or cached task result was counted as real execution. The report generator's 37 tests and falsifiable self-test passed. The four untested integration/qualification rows remain in the denominator.

## Native component candidate

Official `prepareLibraryCandidate` used publication roots `:,:platform-plugin-api`, fresh isolated output and library version `1.0.1-rc.3`, at source `4ec7b97131e7a52408be436e41b9fe2439e3726c`. The normalized [native receipt](../e2e/evidence/announcement-sealed-content-2026-10-27/native-candidate-receipt.json) records exact primary-JAR selection against `compiledJar`, inventory hash and five class hashes matching the test compilation:

| Primary publication | SHA256 |
| --- | --- |
| `com.auraboot:auraboot-core:1.0.1-rc.3` | `3c81a902e90925c87cd0051480b2dfda2158ea79668756eb9ff71cf6affb283f` |
| `com.auraboot:platform-plugin-api:1.0.1-rc.3` | `66eda7cbd4431b824049d0878d0baff90234fa6aa0fad9ab0e7c6937c678afc0` |

The first draft receipt selected the Core test-api attachment by a loose filename predicate. That metadata selection was corrected before publication: exact `artifact-version.jar` must equal the inventory's primary compiled JAR, and the five-class match count is asserted. Only the corrected primary hashes above are valid. The original native producer output did not change.

This candidate includes the new API types and shared codec but does not contain all separately owned rich Core/API capabilities. It must not replace the richer API wholesale, and two API JARs must not be combined to imply production compatibility. Root must integrate the precise source delta, wire the protected handler scope, regenerate its migration-union snapshot if needed and produce one combined candidate. `remotePublication=did_not_run` and `releaseQualification=false`; no application image, main merge, deployment or product admission was performed.

## Explicit guarantee limits

The source identity is seal-time observed content, not a provider object generation. Re-reading at capture/release/proof/commit can reject observed drift but cannot prevent an external storage writer after the final check or make external storage atomic with PostgreSQL. SQL locks cover the relevant platform row mutation paths. No global linearizable IAM revocation contract is inferred beyond the original transaction's isolation/visibility. These are architectural limits, not hidden green rows.

## Final Evidence Pack

```text
acceptance_report: this document
claim_level: bounded backend source component
current_sot: announcement-management.md#sealed-content-proofs-for-original-business-commands
business_scope: official fresh-version seal evidence; publisher exact proof; shared document encoding; scoped fresh permission reads
integration_tests: 10 PostgreSQL/local-storage tests executed; 0 failures/errors/skips
integration_coverage: not_measured
e2e_specs: public HTTP, actual BPM, P mapping and browser not executed
e2e_collection: 0 browser cases collected
e2e_execution: 0 browser cases executed
feature_action_matrix: 12 component pass; 4 integration/qualification untested
browser_evidence: none; no UI acceptance claim
backend_evidence: native unit/API/PG XML; migration/producer receipts; coverage counters
artifact_evidence: original native candidate inventory; corrected primary JAR hashes; five tested class matches
permission_negative: same-TX grant revocation; uncommitted grant isolation; actor/tx/thread/lifetime gates
visual_feedback: not_in_scope
skip_fixme_threshold_retry_audit: no skips, retries or weakened thresholds counted
did_not_run: Root rich issuance, full actual BPM/public executor, P mapping, browser, external atomic-storage/IAM guarantees
remaining_blockers: four explicit integration/qualification rows; consumer admission belongs to Root/P
allowed_claim: bounded component and candidate verification only; no D027/product/production qualification
```

## SOT Updates

Reusable API, canonical algorithm, seal provenance, permission-cache and guarantee boundaries are in [the announcement reference](./announcement-management.md#sealed-content-proofs-for-original-business-commands). This report retains verification provenance and limits.
