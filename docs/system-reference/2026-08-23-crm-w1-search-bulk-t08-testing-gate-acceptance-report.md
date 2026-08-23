---
type: system-reference
status: active
---

# CRM W1 search and bulk processing T08 acceptance report

Date: 2026-08-23

`allowed_claim`: filesystem/unit targeted pass for public-pid global-search navigation,
cooperative import cancellation state, and same-file explicit retry. PAR-23/24 W1 real-stack,
browser, artifact, permission, rollback, 10k, and 100k acceptance remains untested because both
workspace development runtime slots were occupied. Cordys full-product parity remains NOT MET.

## Stable platform contract

- Global record-search navigation accepts only a public `pid`. An internal `id` is never used as
  a browser identity or fallback.
- `POST /api/meta/excel/import/{modelCode}/cancel/{taskId}` requests cooperative cancellation of
  a running import owned by the current tenant and user and protected by
  `model.{modelCode}.import`.
- Cancellation is intentionally truthful, not transactional fiction: rows committed before the
  worker observes cancellation remain committed; no later row starts. Durable task status stores
  the full input denominator separately from processed/success/error counts.
- A failed or cancelled browser attempt retains the already validated source file and exposes an
  explicit retry action. Retry uses the normal import endpoint and policy; it is not a hidden
  backend retry or automatic self-healing path.

## Scope and denominator

The machine denominator is
[`docs/coverage/crm-w1-search-bulk-t08-20260823.json`](../coverage/crm-w1-search-bulk-t08-20260823.json).
It includes global/advanced/personal search, masking and permission negatives, bulk
transfer/edit/delete, selected/all-matching export content, import template/preflight/failure
report/cancel/retry/rollback, explicit 10,000/100,000 rows, and untested lead/contact/opportunity
object-specific rows. No uncovered object was removed from the denominator.

## Platform capability audit

The implementation reuses existing platform surfaces instead of copying behavior into CRM
objects:

- `CommandPalette.tsx` for global record search;
- list renderer selection model, all-matching banner, bulk capability resolver and bulk toolbar;
- dynamic export task/service with field masking and data-scope enforcement;
- `ExcelImportController`, `ExcelImportService`, persisted `ab_import_job`, validation engine,
  correction workbook service and `ImportModal`.

Existing source and historical specs are discovery evidence only. They are not promoted to this
run's pass rows unless their relevant runner was executed in this worktree.

## Verification executed

| Command | Result | Scope |
| --- | --- | --- |
| `./gradlew compileJava` | pass | Java compilation |
| targeted `ExcelImportControllerTest` and `ExcelImportServiceTest` Gradle command | pass, 37 tests | cancel API contract, cancelled restoration, terminal cancellation denial and existing import unit regression |
| `pnpm typecheck` | pass | changed Web TypeScript |
| targeted Vitest for `CommandPalette.test.ts` and `ImportModal.correction.test.tsx` | pass | public pid and retry UI contracts |
| `./dev.sh runtime list` | valid preflight | both allowed development slots occupied by T05 and T07 |

Integration coverage was not measured. The targeted backend suite generated JaCoCo output, but no
changed-scope line percentage was extracted; this is reported as `coverage_not_measured` rather
than inferred from suite pass count.

## Truth audit

- Runner collection was non-empty for the targeted unit suites.
- No skip/fixme, retry, threshold relaxation, `page.goto`, `page.request`, or `waitForTimeout` was
  introduced in the changed tests.
- The new pid-only test has a negative counterpart (`id` without `pid` returns `null`).
- The retry test observes the first attempt fail with HTTP 503 before the same file succeeds on the
  second explicit click.
- No fresh runtime mutation was possible. Therefore the browser/real-stack rows remain `untested`
  and no golden or W1 completion claim is allowed.

## Remaining verification for T10 or the next available runtime

1. Run from real menu/header entry with an admin and a low-privilege CRM persona.
2. Prove global/advanced/personal search data scope and field masking across account plus retained
   lead/contact/opportunity rows.
3. Drive bulk transfer/edit/delete and parse selected/all-matching export workbooks.
4. Cancel a running import and verify durable counts plus stability of DB writes after cancellation.
5. Retry failed/cancelled input and prove persistence/idempotency/rollback semantics.
6. Execute explicit 10k and 100k denominators with an owner-approved latency threshold; do not
   infer capacity from smaller fixtures.
7. Capture browser screenshots, API JSON, DB evidence, downloaded XLSX files and a mutation red/green
   proof before upgrading any manifest row to pass.

## Final Evidence Pack

```text
acceptance_report: docs/system-reference/2026-08-23-crm-w1-search-bulk-t08-testing-gate-acceptance-report.md
claim_level: targeted-tested
current_sot: delegated T08 contract; docs/coverage/crm-w1-search-bulk-t08-20260823.json
business_scope: PAR-23/24 W1 cross-object search, bulk operations, export and import lifecycle
integration_tests: did_not_run; no development runtime slot available
integration_coverage: coverage_not_measured
e2e_specs: did_not_run; retained as untested manifest rows
feature_action_matrix: docs/coverage/crm-w1-search-bulk-t08-20260823.json
browser_evidence: did_not_run
backend_evidence: ExcelImportControllerTest + ExcelImportServiceTest targeted pass (37 tests)
artifact_evidence: did_not_run; template/export/failure-report parsing remains untested for this run
permission_negative: pid-only hermetic negative pass; real role/data-scope/direct-API negatives did_not_run
visual_feedback: explicit retry component test pass; screenshots did_not_run
skip_fixme_threshold_retry_audit: no new skip/fixme/threshold/retry wrapper; explicit user retry only
did_not_run: real-stack IT, browser E2E, screenshots, XLSX artifacts, 10k, 100k, mutation on fresh runtime
remaining_blockers: workspace development runtime budget occupied by T05 and T07
allowed_claim: filesystem/unit targeted pass; PAR-23/24 W1 and Cordys full-product parity NOT MET
```
