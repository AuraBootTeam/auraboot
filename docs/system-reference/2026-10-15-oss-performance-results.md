---
type: system-reference
status: active
---

# OSS performance evidence: 2026-10-15

This historical report uses the development server and the earlier runner-observed home timing. Use the [next-round production report](2026-10-15-oss-production-performance-results.md) for the corrected browser-side readiness contract and production client/SSR baseline. Do not compare their absolute home timings as an application speedup.

The measured change reduces redundant work: list MyBatis calls fall from **18 to 15**, and the initial browser workbench-configuration request falls from **1 to 0**. Latency is mixed; the data does **not** establish a general API speedup or a production performance SLO.

## Source and measurement conditions

- Original product base: `3dd3cdff692d58cc0ad7ab35c9507bcf7947bdfe`.
- B0 original product plus measurement harness: `6ce0bef207d25a4821306b0e566c4b214efc7487` (new optional SDKs not installed in the running process).
- B1 instrumented but unoptimized control: `700d77b54e539b1c2ad5eec3f420d72fc331b3eb`.
- API/CRUD candidate: `e73498c87b03ae702537876325fa895143936f6a`.
- Final home/UI candidate: `0e89bbbe3a0f43b73fb166fc6217faf50064e3e5`; the delta from the API candidate changes only tests and the home measurement driver, not production code. The following documentation/evidence commit does not change the tested implementation.
- Frozen Workspace dependency: `5c687fd22d84ae339090a1f1d6765fe7c4cb8d61`.
- macOS ARM host-first Java bootJar + Vite dev + BFF + local PostgreSQL. Main runtime `oss-performance`, slot 281; immutable before-optimization control slot 282. No Docker or production deployment.
- Controlled showcase fixture: exactly 1,000 rows, page size 20. Admin role, zh-CN, 1440×1000. No configured Redis host in B1/candidate; all tracing comparisons use Java/browser sample 1 and Node always-on, with one native collector and matching SDK dependencies.
- API: 40 serial samples after five warmups per endpoint. CRUD: 20 serial journeys after three warmups. Home: ten navigations after one warmup, warm browser cache. Percentiles use nearest rank; all raw samples are retained.
- Reads were collected before optimization and then after it on the stable main runtime. The additional immutable control runtime was used for CRUD and the corrected data-ready home baseline. CRUD control had demo + the same 1,000 showcase rows, but not the complete warmed E2E role/menu fixture at that earlier measurement; treat latency as descriptive, not a controlled causal estimate. For home it was subsequently fully warmed before sampling.
- Samples ran without concurrent task builds or test workloads. Separate sequential runs and normal host/background activity can still affect small local timing differences. No repeated selection of a favorable after-run was used.

## API observations

| Endpoint | B0 P50 / P95 ms | B1 P50 / P95 ms | After P50 / P95 ms | MyBatis calls B1 → after |
| --- | ---: | ---: | ---: | ---: |
| Menu | 8.36 / 9.43 | 7.54 / 15.50 | 7.95 / 9.56 | 9 → 9 |
| Auth me | 6.94 / 8.76 | 5.72 / 6.53 | 6.34 / 10.39 | 11 → 11 |
| Workbench configuration | 6.14 / 8.32 | 5.38 / 7.09 | 6.50 / 8.86 | 9 → 9 |
| Workbench stats | 4.19 / 6.43 | 4.61 / 5.58 | 6.55 / 9.57 | 7 → 7 |
| List page 1 | 11.10 / 13.34 | 11.16 / 15.96 | 12.73 / 21.80 | 18 → 15 |
| List page 2 | 10.39 / 14.50 | 13.22 / 15.64 | 11.24 / 15.27 | 18 → 15 |
| List keyword PERF | 10.93 / 14.31 | 9.70 / 13.23 | 13.40 / 15.85 | 18 → 15 |

Raw: [B0](assets/oss-performance-2026-10-15/baseline-api.json), [B1](assets/oss-performance-2026-10-15/control-api.json), [after](assets/oss-performance-2026-10-15/after-api.json).

Menu SQL is unchanged for the actual fixture containing permission declarations. The no-declaration fast path is covered by targeted evaluator tests; no measured menu SQL reduction is claimed. List calls fall 16.7% in all three read workloads. Page-1 P95 is worse after the change, while page-2 P95 is close; these data cannot justify a blanket latency win.

`X-SQL-Count` is a per-request synchronous MyBatis execution counter. It is not a count of every physical SQL statement, asynchronous audit write, or JdbcTemplate operation. B1's enabled MyBatis spans add instrumentation work; B0 and B1 are separated to avoid attributing instrumentation overhead to optimization.

## Persisted CRUD observations

| Operation | Before P50 / P95 ms | After P50 / P95 ms | MyBatis calls before → after |
| --- | ---: | ---: | ---: |
| create | 17.80 / 23.58 | 16.19 / 23.54 | 23 → 21 |
| update | 24.21 / 29.00 | 24.40 / 27.15 | 40 → 36 |
| detail | 14.22 / 17.69 | 11.13 / 13.65 | 22 → 18 |
| delete | 20.03 / 23.82 | 20.39 / 25.75 | 32 → 30 |

Raw: [before](assets/oss-performance-2026-10-15/control-crud.json), [after](assets/oss-performance-2026-10-15/after-crud.json). Each journey asserts edited name/quantity from a real detail read, deletes the record and requires HTTP 404. UI correctness is independently exercised through the actual toolbar and row-overflow actions, including reload after update/delete. No benchmark record is reused as proof of its own creation.

## Home readiness

| Marker | Before P50 / P95 ms | After P50 / P95 ms |
| --- | ---: | ---: |
| Workbench structure visible | 498.07 / 639.30 | 213.94 / 346.17 |
| Inbox + shortcuts + numeric stats ready | 504.67 / 851.31 | 542.89 / 683.32 |
| Browser configuration calls per navigation | 1 | 0 |

Raw: [before](assets/oss-performance-2026-10-15/control-home.json), [after](assets/oss-performance-2026-10-15/after-home.json). Data-ready P95 is 19.7% lower; P50 is 7.6% higher. This ten-sample dev run is not proof of stable production tail-latency improvement. Structure appears earlier, but that must not be confused with business data readiness.

The first screenshot review caught a skeleton-only home capture. That capture was rejected, the assertion was strengthened to real widget results, and **both** before and after sources were measured using the new driver. The [final home screenshot](assets/oss-performance-2026-10-15/home-ready.png) shows loaded widgets. Earlier structure-only measurements are retained externally as diagnostics and are excluded from the data-ready comparison.

## SQL plan, cache and trace evidence

The [EXPLAIN ANALYZE sample](assets/oss-performance-2026-10-15/explain-list.json) uses `idx_mt_showcase_all_fields_tenant_updated` for the tenant-scoped descending list. It returns 20 rows with an execution time of 0.073 ms on this 1,000-row fixture. No new index was justified or added. This does not establish a plan at much larger cardinality or for every filter.

[Live configuration](assets/oss-performance-2026-10-15/live-config.json) records an empty Redis host and tracing sample 1 for both stacks. [Operational observations](assets/oss-performance-2026-10-15/cache-and-deny.json) include Caffeine get/put/eviction/size, Hikari acquire/pending/usage and MyBatis metrics, plus anonymous menu/list HTTP 401. A no-Redis single-node run does not prove distributed invalidation.

The [selected exported trace](assets/oss-performance-2026-10-15/trace-chain.json) has trace ID `f8a2dc8ed530762d70ea28bafd0d54b1`, four services, 345 spans, 344 observed parent edges and 166 MyBatis spans with observed parents. Its `connectedExample` is an actual parent-ID path from SSR document to browser fetch, SSR proxy, BFF, Java HTTP/security and MyBatis. The committed span projection omits arbitrary resource/HTTP attributes, raw SQL, payloads and credentials; it retains enough identity/timing to inspect the chain. The collector's large raw export stays outside Git.

## Verification and claim boundaries

[Verification inventory](assets/oss-performance-2026-10-15/verification.json) includes raw-log SHA-256 digests and exact backend suite counts. [Artifact index](assets/oss-performance-2026-10-15/sha256.json) binds the compact evidence bytes.

| Check | Observed result | Evidence |
| --- | --- | --- |
| Targeted frontend logic | 63 tests, 4 files passed | [log](assets/oss-performance-2026-10-15/final-frontend-tests.txt) |
| Targeted backend permission/menu/list/SQL logic | 38 tests, 5 suites; 0 failure/error/skip | [log](assets/oss-performance-2026-10-15/optimization-backend-tests.txt) |
| Launcher identity/resume tests | 60 passed in optimization run; separate tracing run 52 passed, overlapping coverage | [optimization](assets/oss-performance-2026-10-15/optimization-launcher-tests.txt), [tracing](assets/oss-performance-2026-10-15/tracing-launcher-tests.txt) |
| Final real-stack browser slice | 2 passed, 0 skipped, retries 0; 13 captured scenarios | [Playwright result](assets/oss-performance-2026-10-15/browser-results.json), [matrix](assets/oss-performance-2026-10-15/manifest.json) |
| Production client + SSR build/typecheck | Passed; single React production runtime forbiddenCount=0 | [build](assets/oss-performance-2026-10-15/production-build.txt), [typecheck](assets/oss-performance-2026-10-15/final-typecheck.txt) |
| Negative controls | Old lazy-role/evaluator path, serial root reads and cloned-Request memo assertion failed as expected | [backend red](assets/oss-performance-2026-10-15/optimization-red-tests.txt), [serial red](assets/oss-performance-2026-10-15/root-parallel-mutation-red.txt), [memo red](assets/oss-performance-2026-10-15/cloned-request-mutation-red.txt) |

The backend/frontend logic suites are hermetic unit/contract evidence, not real-database integration tests. Real-stack HTTP workloads and the browser journey provide the database/request integration evidence. The existing registration checker passes but covers the quoteops registry, so it is not offered as proof that this new OSS spec was collected: actual final Playwright execution is the collection proof.

The browser path is login state → home → real sidebar menu → list → empty search → page 2 → new → rejected empty name → save → detail → row overflow → edit → save/reload → delete dialog → delete/reload. An explicitly empty browser storage state checks anonymous home. There is no API fallback for UI CRUD, no unconditional sleep, no retries or skip/fixme in this spec. Mutation tests intentionally select a narrow test, so their excluded tests are not counted as a passing full suite. Failed development rounds remain externally retained and are not summed into the final pass count.

All 13 final original screenshots were opened individually. See [visual findings](assets/oss-performance-2026-10-15/screenshot-review.md). Existing filtered-empty wording and recent-visit technical paths remain partial visual findings. Save toasts briefly persist across rapid transitions. No complete UI-golden or whole-OSS coverage claim is made.

Not run: production serving latency, cold network/mobile/low-end devices, LCP/INP/CLS, concurrent/soak capacity, large-cardinality SQL, multi-node Redis/invalidation, collector-outage load, the complete OSS E2E suite, remote Linux CI or release-image gates. No production deployment or merge. GitHub Actions are not used by this project. The local report generator referenced by broader governance was unavailable; this is a raw manifest/Markdown evidence report, not a generated certified-golden report.

The scoped documentation-governance check passed with 0 errors/warnings. The repository-wide `check-oss-no-internal-docs.sh` returned exit 1 for 11 existing local-path matches in three unchanged files; [base/head blob proof](assets/oss-performance-2026-10-15/preexisting-doc-gate.json) records that baseline debt. The broad `check-docs-governance.mjs --strict --git` also returned exit 1 for two existing warnings (missing frontmatter and stale active process document), with unchanged base/head blobs recorded in the same proof. The three new Markdown files pass the explicit scoped check. No newly added file has a matching path. This is an outstanding repository gate failure, not a passing check; unrelated historical files were not changed. Portable log projections remove machine paths, terminal color codes and trailing whitespace; raw-log digests identify the originals.

## Evidence pack fields

| Field | Value |
| --- | --- |
| acceptance_report | This report |
| claim_level | Verified implementation slice + recorded performance diagnostics; PR review ready |
| current_sot | [OSS performance/tracing contract](oss-web-performance-and-tracing.md) |
| business_scope | Home/menu/showcase search/page2/create/detail/update/delete; see [13-scenario source](../../scripts/performance/acceptance.json) |
| integration_tests | Real HTTP API/CRUD and browser drivers; hermetic suites classified separately above |
| integration_coverage | Listed slice only; no aggregate product percentage |
| e2e_specs | `web-admin/tests/e2e/showcase/oss-performance.spec.ts` |
| e2e_collection / execution | Final Playwright result: exactly 2 tests executed |
| feature_action_matrix | [Manifest](assets/oss-performance-2026-10-15/manifest.json), including partial/untested rows |
| browser_evidence | 13 final PNGs, Playwright result, home raw samples |
| backend_evidence | Raw API/CRUD, SQL plan, metrics and suite inventory |
| artifact_evidence | SHA-256 file index, tested source commits and verified runtime identities |
| permission_negative | Anonymous home redirect and menu/list 401; targeted grant/revoke tests |
| visual_feedback | Separate original-image review; 3 partial visual states |
| skip_fixme_threshold_retry_audit | No skips/retries/fallback in final slice; latency recorded, no invented pass threshold |
| did_not_run | Explicit list above |
| remaining_blockers | No blocker to scoped PR review; production capacity and complete visual acceptance remain unproven |
| allowed_claim | Fewer redundant calls, tracing parent-chain verified, optional Redis works for this slice, persisted CRUD correct |

## Reproduction

Use the [contract and workload commands](oss-web-performance-and-tracing.md#reproduce-measurements). Pin control/candidate sources and all runtime inputs; compare raw samples rather than only summary percentiles. The complete local raw history is retained under the registered `oss-performance` evidence root; the portable projection in this directory is sufficient to review the reported measurements without access to that machine.
