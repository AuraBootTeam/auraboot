# Eight OSS defect regression results

The eight reviewed defects have targeted executable regression coverage. Validation ran on commit `1d79315dbababeefaae5f5478d7836dd2f3f367a` with an isolated PostgreSQL database, real Spring services and local file storage. The browser used the production backend JAR, Vite and BFF through the verified runtime `oss-review-eight`.

| Execution | Passed | Failed | Skipped |
|---|---:|---:|---:|
| Core unit tests | 85 | 0 | 0 |
| Agent unit tests | 24 | 0 | 0 |
| PostgreSQL integration tests | 18 | 0 | 0 |
| Attachment browser journey | 1 | 0 | 0 |
| Browser bootstrap and admin authentication | 13 | 0 | 3 |

The three setup skips are the existing optional operator/viewer authentication and fixture checks, disabled by `PW_ROLE_PROJECTS`. They are outside this admin attachment scenario. No business regression was skipped. These counts describe the selected suites, not repository-wide coverage.

## Defect and evidence mapping

| ID | Resulting behavior | Principal evidence |
|---|---|---|
| R1 | Schedule calculations use model/field authorization and the ordinary data service row scope. | `OssReviewRegressionIT`: model denial, hidden-field denial and another member's excluded task; `CriticalPathServiceTest`. |
| R2 | Single and batch member removal revoke sessions; the JWT filter independently checks active membership. | Signed JWT and persisted session replay before/after removal; inactive membership with an otherwise valid session; lifecycle/filter unit suites. |
| R3 | Watch creation requires readable records; delivery checks the recipient's current membership, roles and row authority. | Actual notification rows before and after official permission revocation, resolver exclusion and successful unwatch. |
| R4 | Each asynchronous batch query runs with the caller's captured identity and environment. | Actual named query against PostgreSQL on the worker; worker-thread identity and failure isolation unit assertions. |
| R5 | File queries use the upload lifecycle's `success` status. | Actual upload appears in the current tenant list and is absent for another tenant; browser list readback. |
| R6 | Metadata returns the canonical file-service URL. | Actual local-storage bytes and headers; browser preview link downloads exact bytes, then deletion returns 404. |
| R7 | Expired RUNNING executions become COMPENSATION_REQUIRED and reject late completion. | Persisted lease expiration, no provider redispatch, late-completion fencing and repeated-sweep stability in `DurableToolExecutionRecoveryIT`. |
| R8 | Terminal turn metadata has configurable time/count bounds, and terminal handles release accumulated output. | Deterministic cache ticker/capacity assertions; active turns survive cleanup and late output is ignored. |

See [the machine-readable acceptance manifest](acceptance-manifest.json), [runner totals and evidence hashes](results-summary.json), [runtime identity](runtime-identity.json), [browser results](playwright-results.json), and the sanitized native JUnit XML files in [junit/](junit/). XML keeps suite/testcase assertions and counts; console output was removed to avoid publishing machine paths or operational data. Original runner logs and XML are retained in the managed evidence round; their hashes are recorded.

## Reproduction

Allocate an isolated runtime, apply the OSS migrations, and export its database values as `TEST_DATABASE_URL`, `TEST_DATABASE_USERNAME` and `TEST_DATABASE_PASSWORD`. From the OSS checkout, run these tasks through the workspace's managed Gradle command:

```text
:cleanTest :platform-agent:cleanTest :platform-app:cleanIntegrationTest
:test
  --tests "*CriticalPathServiceTest"
  --tests "*BatchQueryControllerTest"
  --tests "*TenantMemberApplicationServiceImplTest"
  --tests "*JwtAuthenticationFilterTest"
  --tests "*FileUploadControllerTest"
:platform-agent:test
  --tests "*TurnRegistryTest"
  --tests "*DurableToolExecutionRecoveryServiceTest"
  --tests "*JdbcDurableToolExecutionLedgerTest"
:platform-app:integrationTest
  --tests "*OssReviewRegressionIT"
  --tests "*DurableToolExecutionRecoveryIT"
--no-daemon --no-build-cache
```

Start the host stack with `scripts/oss-golden-stack.sh up <runtime> --slot <owned-slot> --system-mode single --agent-mode with-agent --plugin-profile core --no-warm`. Verify artifacts before running. Source that runtime's `env` output, set an isolated `PW_STORAGE_DIR`, then run from `web-admin`:

```sh
PW_PROFILE=fast PW_WORKERS=1 pnpm exec playwright test \
  -c playwright.gt5.config.ts \
  tests/e2e/page-designer/upload-attachment-artifact.spec.ts \
  --project=chromium
```

The spec fixes `zh-CN` locale, uses API setup for its own published page/menu, and drives upload, preview, download and removal through the actual UI. It checks list and metadata API readback as intermediate assertions. The three state screenshots and their scoped visual review are indexed in [the visual review](visual/README.md).

## Falsifiability and limits

Temporarily removing the eight protections produced 13 failing assertions and a nonzero Gradle exit. [Mutation results](mutation-results.json) identify the failing tests; [restoration hashes](mutation-restored-sha256.json) record byte-for-byte restoration. All eight defect categories were detected. The final committed-source run above executed all three selected Gradle test tasks with the build cache disabled and passed after restoration.

R7 uses the existing real PostgreSQL ledger/context/ACL integration fixture, while external provider dispatch and action recording remain controlled test doubles. It proves durable recovery and absence of automatic redispatch, not a live paid provider operation. R8 is in-memory lifecycle logic and is deliberately verified hermetically; a database cannot strengthen its clock/capacity assertions. R2 exercises the actual signed-token filter with servlet request/response objects, rather than a browser membership administration journey. No repository-wide test or coverage percentage is claimed.

The runtime processes were stopped after verification. Its database and original evidence remain retained. No PR merge or deployment is implied by this report.
