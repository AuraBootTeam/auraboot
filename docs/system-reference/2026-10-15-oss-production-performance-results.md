---
type: system-reference
status: active
---

# OSS production performance evidence: 2026-10-15

JWT authentication now reads one fresh user snapshot instead of four user queries. The measured page-key list path falls from **15 to 12 synchronous MyBatis calls (20%)**. The production readiness measurement is corrected, and the browser → BFF/SSR → Java → MyBatis trace chain is verified. Latency remains mixed: this evidence does **not** establish a stable page-1 P95 improvement or a production SLO.

`allowed_claim: targeted-tested` — production-build baseline and redundant-query reduction verified; scoped correctness regression passed; visual review partial; distributed cache consistency, deployment and capacity untested.

## Frozen sources and protocol

- Original application: merged first-round `b14cf4870de3ef4df8537365163054622c75941b`.
- Before: `19610df1bf7473695ca89a038052c70a13045719`, original application plus the corrected measurement/production launcher. Earlier harness-only attempts are retained as incomplete or environment-invalid, not counted as baseline.
- After: `5e1cc2e3b93c0a3cce93dfc17ebcbef094d6cea1`. Only JWT snapshot loading and its tests change application behavior. Later evidence/documentation changes do not alter the measured application.
- Workspace dependency: clean detached `54f320f083a26a6f860abe137de9eaa8ce1b0265`.
- One stable runtime, `oss-performance-production`, slot 283; backend 6683, Web 5383, BFF 6383, database `auraboot_283`. Native macOS ARM, PostgreSQL, Java bootJar, production client/SSR build, no Redis host. No Docker, deployed image, TLS or production deployment was tested.
- The existing deployment BFF serves compiled client/SSR with `NODE_ENV=production`; a streaming loopback ingress preserves the local three-listener runtime contract. The `dev:bff` script name does not imply a development server: it starts the same non-watching TypeScript BFF entry used by deployment. Browser samples reject Vite `/@vite/client` and `/@fs/` requests.
- Controlled 1,000-row showcase fixture; page size 20; admin; zh-CN; 1440×1000; concurrency 1. API samples require exactly 1,000 total and 20 records. CRUD creates a unique record, edits, reads back exact name/quantity, deletes, and requires 404.
- Each of four phases (before/after × tracing off/on) uses the same sequence: 200 API samples per endpoint plus five warmups as preconditioning; 20 warm home navigations plus one warmup as preconditioning; then **three batches** of 100 API samples per endpoint plus five warmups and 30 warm home navigations plus one warmup; then 30 cold-context navigations plus one warmup; then 30 CRUD journeys plus three warmups. Preconditioning is retained but excluded from the comparison.
- Tables pool all 300 API samples per endpoint and all 90 warm home navigations, using nearest rank. Cold context means a new browser context, not a cold OS/database/server. Tracing-on uses Java/browser sample 1 and Node always-on with the same verified native collector. Tracing-off omits browser/Node exporter configuration and disables Java tracing.
- Runs were serial without this task's builds/tests during formal samples. The shared host had other workloads, no CPU isolation and no randomized before/after ordering. Batch variability is reported, not hidden. No performance threshold, retries or favorable rerun selection was used.

[Source/artifact ledger](assets/oss-production-performance-2026-10-15/source-ledger.json) binds phase commits, build/bootJar identities and raw collector hashes. All planned raw samples, including preconditioning, are in the four phase directories. The [SHA-256 artifact index](assets/oss-production-performance-2026-10-15/sha256.json) binds the portable evidence bytes. The [combined summary](assets/oss-production-performance-2026-10-15/measurements-summary.json) is derived from those samples.

## Home readiness and the earlier P50 concern

The previous driver measured when Playwright observed the final locator state. The new driver records the readiness timestamp **inside the browser**, using MutationObserver + animation frame; delaying the runner cannot inflate that stored timestamp. The [negative-control log](assets/oss-production-performance-2026-10-15/oss-home-readiness-mutation-red.txt) and restored green harness demonstrate that missing widgets and observer-time substitution are caught.

The controlled marker requires the empty inbox result, shortcut rows and both numeric-zero statistics. It records structure and each widget separately, and independently verifies critical API HTTP 200 / success payloads. It is DOM-ready evidence, not a paint timestamp, LCP, every-widget readiness or real-network UX score. A CDP body-observation race in an earlier incomplete run was fixed without request retries: all observed critical statuses must succeed, and every critical endpoint still needs at least one successfully read payload.

The historical 542.89 ms development-mode P50 cannot be compared causally with this production baseline: **both serving mode and measurement semantics changed**. This round establishes a new matched before/after baseline.

| Tracing / workload | Before P50 / P95 ms | After P50 / P95 ms | Samples per side |
| --- | ---: | ---: | ---: |
| Off, warm home | 110.70 / 127.40 | 108.90 / 127.60 | 90 |
| Off, cold browser context | 181.60 / 194.90 | 175.10 / 195.30 | 30 |
| On, warm home | 116.30 / 133.90 | 110.60 / 127.30 | 90 |
| On, cold browser context | 189.50 / 211.60 | 182.60 / 194.10 | 30 |

Routine tracing-off home P50 is close (110.70 → 108.90 ms); P95 is effectively unchanged (127.40 → 127.60 ms). Small differences are descriptive, not evidence of a broad latency win. Raw browser marks, runner read times, navigation timings and resource paths are retained per navigation.

## API and persisted CRUD

Routine production mode, tracing off:

| Endpoint | Before P50 / P95 ms | After P50 / P95 ms | MyBatis calls before → after |
| --- | ---: | ---: | ---: |
| Menu | 5.03 / 8.96 | 4.69 / 6.87 | 9 → 6 |
| Auth me | 4.06 / 7.86 | 3.70 / 5.24 | 11 → 8 |
| Workbench configuration | 3.25 / 6.80 | 2.96 / 4.86 | 9 → 6 |
| Workbench stats | 2.91 / 6.12 | 2.49 / 4.41 | 7 → 4 |
| List page 1 | 6.24 / 9.99 | 6.30 / 9.67 | 15 → 12 |
| List page 2 | 6.01 / 8.37 | 5.74 / 8.56 | 15 → 12 |
| List keyword PERF | 6.59 / 11.32 | 6.65 / 10.07 | 15 → 12 |

| Operation | Before P50 / P95 ms | After P50 / P95 ms | MyBatis calls before → after |
| --- | ---: | ---: | ---: |
| create | 9.71 / 12.06 | 10.15 / 14.66 | 21 → 18 |
| update | 14.17 / 17.72 | 15.79 / 21.55 | 36 → 33 |
| detail | 6.52 / 8.19 | 6.15 / 8.63 | 18 → 15 |
| delete | 11.32 / 14.24 | 12.08 / 16.95 | 30 → 27 |

Every operation consistently removes three authentication calls. The counter measures synchronous instrumented MyBatis executor calls; it is not every physical PostgreSQL statement or asynchronous audit operation.

Page-1 P95 per planned batch (100 samples each):

| Tracing | Batch | Before P95 ms | After P95 ms |
| --- | ---: | ---: | ---: |
| off | 1 | 13.76 | 11.92 |
| off | 2 | 7.19 | 7.35 |
| off | 3 | 6.93 | 8.14 |
| on | 1 | 10.99 | 12.89 |
| on | 2 | 7.17 | 6.10 |
| on | 3 | 9.61 | 7.22 |

Tracing-off pooled page-1 P95 is 9.99 → 9.67 ms; tracing-on pooled P95 is **10.08 → 10.92 ms**. Some batches improve and others regress. SQL count falls deterministically, but these local timings cannot establish that the tail-latency regression is solved.

## SQL, cache-bypass and slow-request diagnosis

[Before selected list traces](assets/oss-production-performance-2026-10-15/baseline-on-list-traces.json) and [after selected list traces](assets/oss-production-performance-2026-10-15/after-on-list-traces.json) retain the median, P95 and maximum request from **each** tracing-on batch, with actual mapper counts and parent IDs. No bind values or raw SQL text are exported.

| Query group | Before → after | Interpretation |
| --- | ---: | --- |
| User authentication | 4 → 1 | JWT already identifies a PID; email and username probes were redundant, followed by a second PID read. The fresh snapshot now supplies identity, enabled state and security version together |
| Session | 1 → 1 | Live revocation check retained |
| Member role | 1 → 1 | Existing request context resolution retained |
| Policy and user data domain | 2 → 2 | Row/domain authorization remains live |
| Model/page/category/contribution/environment | 5 → 5 | Distinct resolution operations, described below |
| Data page + count | 2 → 2 | Tenant, policy and count semantics retained |

The five metadata-related reads on this page-key path are an exact-model lookup for the normalized page key, page-schema resolution, category enrichment, active page contributions, and environment lookup. `showcase_all_fields_list` is a page key; the assembled business model is `showcase_all_fields`. A missing exact model is not cached as a positive model definition. The category enrichment still reads its model row directly. This is a remaining candidate for reusing an appropriate cache boundary, not evidence that the full assembled model is rebuilt every time. Caching the composed page/category blindly would require matching current/release semantics and invalidation of model creation, publication and contribution changes.

The real-database projection test warms `getModelFields`, clears mapper invocation history, then repeats the call and requires **no** model/field/binding mapper interaction. Live Caffeine metrics also show model-definition hits. Thus the assembled-model cache works on the exercised path; not every metadata SQL is a bypass of it.

The [current EXPLAIN ANALYZE](assets/oss-production-performance-2026-10-15/explain-list.json) uses `idx_mt_showcase_all_fields_tenant_updated`, returns 20 rows and executes in **0.082 ms** on this fixture. No index was added. Selected trace spans show a spread of mapper and non-mapper time rather than a stable expensive first-page data query. Five endpoint warmups alone did not remove all batch variation, which is why explicit preconditioning and three batches are retained. Startup/incomplete runs are not comparable to the formal steady-state dataset.

A separate [JFR diagnostic summary](assets/oss-production-performance-2026-10-15/jfr-diagnostic.json) observed GC and compilation, including a 19.23 ms pause. That profiler run is excluded from the comparison; it does **not** prove that GC/JIT or connection-pool waiting caused any formal percentile. The [metrics/deny snapshot](assets/oss-production-performance-2026-10-15/metrics-and-deny.json) is an operational point observation, not a continuous no-wait guarantee. Shared-host scheduling and startup effects remain possible contributors, not established root causes.

## Production trace continuity

The [before home trace](assets/oss-production-performance-2026-10-15/baseline-on-home-trace.json) contains 301 spans / 300 observed parent edges; the [after home trace](assets/oss-production-performance-2026-10-15/after-on-home-trace.json) contains 250 / 249. Each includes an actual parent-ID path from BFF/SSR document → browser `ui.page` → browser HTTP client → BFF HTTP → Java HTTP/security → MyBatis. Production BFF and SSR share `auraboot-bff`, so the captured services are `auraboot-web`, `auraboot-bff`, `auraboot-platform`.

The native collector archive checksum and verified extracted binary are recorded in [collector integrity](assets/oss-production-performance-2026-10-15/collector-integrity.json). Before and after exports use separate files; their raw hashes are retained. Only selected safe span attributes are committed. Full raw exports/JFR stay in the registered evidence storage.

## Active and passive invalidation, Redis optional

The stable policy is in [the cache contract](oss-web-performance-and-tracing.md#active-and-passive-invalidation). This round adds **no cross-request user cache and no list-result cache**.

- User security state: one fresh PID read per request. Real signed JWT + PostgreSQL + real filter/session tests exercise allow → disabled deny → re-enabled allow → security-version deny → renewed allow → revoked-session deny.
- Permissions: existing after-commit events evict catalog/role/user snapshots and bump the data-access epoch. Real grant/revoke tests include cached negative entries and cached positive authorization; the new transaction test proves rollback preserves the valid grant and committed revocation invalidates it. Tenant-specific keys and concurrent miss collapse have unit coverage.
- Passive protection: production managers are checked for 5-minute / 5,000-entry permission bounds and 30-minute / 10,000-entry model bounds. A controlled Caffeine ticker proves a cached missing permission remains negative before expiry and reloads exactly at expiry, without sleeps or an event.
- Metadata: real database tests cover definition save/delete/version rollback, repeated projection cache hits, field-version changes, command update/publish and binding removal. These are sequential mutation tests. Metadata transaction rollback/repopulation races, model release publication across processes and distributed invalidation were **not** established by this round.
- These caches/events are local to one process. Redis being present does not automatically broadcast their eviction events. Cross-node revoke/publication guarantees need an explicit transport/version design and separate validation. Redis is optional for the tested single-node path; TTL is a bounded fallback, not the primary authorization-revocation mechanism.

## Verification ledger and claim limits

[Verification inventory](assets/oss-production-performance-2026-10-15/verification.json) includes exact Java suite/test names, runner counts and raw-log/XML digests. [UI review](assets/oss-production-performance-2026-10-15/ui-review.json) binds every existing PERF-01…13 scenario to its screenshot and independent functional/visual verdict.

| Layer / command scope | Collected | Executed | Passed | Failed | Skipped | Did not run | Evidence |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| Java hermetic `:test`: JWT filter/details, permission cache/listener, cache configuration | 62 | 62 | 62 | 0 | 0 | 0 | [log](assets/oss-production-performance-2026-10-15/backend-unit-final-green.txt) |
| Java real PostgreSQL `:test`: permission and metadata/command invalidation | 25 | 25 | 25 | 0 | 0 | 0 | [log](assets/oss-production-performance-2026-10-15/backend-real-stack.txt) |
| Java real PostgreSQL `:test`: signed JWT freshness/session transitions | 1 | 1 | 1 | 0 | 0 | 0 | [log](assets/oss-production-performance-2026-10-15/fresh-user-real-stack-legacy-context.txt) |
| Node production/readiness/identity/resume/source harness group | 73 | 73 | 73 | 0 | 0 | 0 | [log](assets/oss-production-performance-2026-10-15/harness-complete.txt) |
| Node runtime lifecycle/identity/secret-fixture group | 64 | 64 | 64 | 0 | 0 | 0 | [log](assets/oss-production-performance-2026-10-15/harness-lifecycle-final.txt) |
| Canonical auth refresh (setup, not browser journey) | 4 | 4 | 4 | 0 | 0 | 0 | [log](assets/oss-production-performance-2026-10-15/auth-refresh.txt) |
| Browser production PERF journey + anonymous home | 2 | 2 | 2 | 0 | 0 | 0 | [runner](assets/oss-production-performance-2026-10-15/ui-results.json), [log](assets/oss-production-performance-2026-10-15/ui.txt) |

The browser journey uses the actual sidebar/list toolbar/row overflow menu; API calls are fixture setup or independent response/persistence assertions, not UI-write fallbacks. Its 13 scenarios include home data readiness, nonempty menu list, filtered empty, page 2 identities, required-name rejection without a command, create/detail/edit/save/reload, open delete confirmation, deletion after reload, anonymous login, and open row actions. No test retry, skip, fixme or latency pass threshold was added. One test covers multiple scenarios; 13 is not the number of executed tests.

All 13 final original screenshots were reviewed (`agent-vision`). Functional and interaction regression: targeted pass. Visual: **partial** — VIS-01, already present in the first-round evidence, uses first-record onboarding copy for a filtered empty result (PERF-03/11). Home shows the controlled loaded empty inbox/shortcuts/zero cards. No general UI golden, accessibility or every-widget claim is made.

Negative controls: reintroducing the JWT email lookup makes the one-query/no-alias test fail; restored source passes. The readiness test rejects a missing widget and proves a delayed runner leaves the stored mark unchanged; an observer-time mutation fails. Earlier failures are retained: missing production key and mismatched canonical signed-cookie key were environment-invalid; the CDP body race was an incomplete measurement; selecting Gradle `test` instead of `:test` failed in a subproject with no matching tests. The new real-stack auth test is colocated with existing `BaseIntegrationTest` consumers: the additive `integrationTest` classpath exposed an unrelated appearance fixture `@Configuration`; that failed context was not counted as product evidence.

Production builds include TypeScript checking and the production React-runtime check. Public-doc and repository-governance results are recorded separately below; compile/build success is not substituted for functional assertions.

### Documentation checks

The three changed Markdown documents pass scoped governance with 0 errors/warnings. Repository-wide public-doc checking still exits 1 for 11 pre-existing local-path matches in three unchanged files. Repository-wide strict governance exits 1 for two pre-existing warnings (missing frontmatter and an old active process artifact); the local process plan was closed outside the public source tree. [Base/head/worktree blob proof](assets/oss-production-performance-2026-10-15/preexisting-doc-gates.json) establishes that these offending files are unchanged from the merged application base. These broad checks remain **failed**, not waived into a pass. No new local-machine path or internal-process document is committed.

### Final Evidence Pack

| Field | Value |
| --- | --- |
| acceptance_report | This report and the artifact index |
| claim_level | targeted-tested |
| current_sot | OSS Web performance and tracing contract |
| historical_or_superseded_rules | First-round dev timings retained as historical; not a matched production comparator |
| business_scope | Production readiness harness; JWT redundant SQL; menu/list/CRUD regression; existing local cache invalidation |
| integration_tests | 26 real-stack cases across five suites; 62 hermetic cases separately |
| integration_coverage | Named cases in verification inventory; coverage percentage not measured |
| e2e_specs | Existing `oss-performance.spec.ts`, unchanged functional scope |
| e2e_collection | 2 browser tests; 13 bound scenarios |
| e2e_execution | 2 executed/passed; 0 failed/skipped/did-not-run; retries 0 |
| feature_action_matrix | PERF-01…13 linked to UI review; broader UI and deployment scopes excluded |
| browser_evidence | Runner JSON/log and 13 original screenshots |
| backend_evidence | Test names, signed-token transitions, cache mutation/expiry tests, selected traces |
| artifact_evidence | Phase source/build/bootJar ledger, raw samples and SHA-256 index |
| permission_negative | Disabled/security-version/session deny; revoked grant deny; anonymous menu/list 401; tenant cache-key separation |
| visual_feedback | Required error, successful save, row menu, open confirmation, final absence; visual partial VIS-01 |
| skip_fixme_threshold_retry_audit | No new skip/fixme/retry/latency threshold; partial runs excluded explicitly |
| did_not_run | Full repository regression, deployed image/TLS, Web Vitals, concurrency/large-cardinality capacity, Redis-on, multi-node invalidation, metadata rollback/repopulation race |
| remaining_blockers | No blocker to reviewing the scoped query-reduction PR; latency improvement and global golden are not established |
| allowed_claim | Corrected production baseline and SQL reduction with scoped correctness evidence; latency mixed |
