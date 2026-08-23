# CRM Contact T05 Testing Gate Acceptance Report

`allowed_claim: T05 filesystem/contract/unit pass; Web real-stack verification is environment-invalid and PAR-07 remains partial.`

## Scope and identity

- Cordys baseline: `v1.8.1 / ab96c96f524985ea84f112c7a6b03970711f921e`.
- Aura base: `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043`.
- Branch: `codex/crm-w1-contact-t05-20260823`.
- Source root: `/Users/ghj/work/auraboot/.worktrees/auraboot-crm-w1-contact-t05-20260823`.
- Runtime allocation: `crm-w1-contact-t05`, slot 5, development mode.
- Runtime preflight: `environment-invalid`; `dev.sh infra ensure crm-w1-contact-t05 --yes` failed because PostgreSQL at `127.0.0.1:5432` was not listening.
- Product claim boundary: this report covers T05 only. It does not claim W1 or Cordys full-product parity.

## Delivered behavior

- Contact list declares multiple selection, permission-governed bulk edit and selected export.
- Bulk delete uses `bulk_record_command` so each record traverses `crm:delete_contact`; it does not bypass lifecycle checks through the generic dynamic batch DELETE endpoint.
- A primary contact cannot be deleted until another primary is assigned.
- A contact linked to an opportunity cannot be deleted until the association is removed.
- Create/update rejects a missing account and duplicate non-blank email, phone, or mobile within the same account.
- Contact import supports insert and update, keyed by email, at both page and model policy layers.
- Existing primary-contact concurrency arbitration, disable demotion, enable and history-retention implementation remains intact.

## Denominator and coverage

The machine-readable denominator is [crm-contact-t05-coverage-manifest.json](../coverage/crm-contact-t05-coverage-manifest.json). It contains all 20 PAR-07 Cordys source surfaces and the required dimensions: `sourceId / action / field / state / permission / sideEffect`.

- Existing fresh-runtime pass: 4 rows (`get`, `add`, `enable`, `disable`), retained from the active parity SOT evidence.
- New hermetic evidence: account-required, duplicate channel, primary delete, opportunity-linked delete, allowed delete and DSL/import contracts.
- New Web evidence candidate: `crm-contact-management-parity.spec.ts`, collected by Playwright as exactly 1 test.
- `environment-invalid`: list, update, selected export, batch update and official Web route rows that require the unavailable runtime.
- `untested`: mobile route remains in the denominator. Chart, account-filtered list and metadata-only surfaces also remain untested where no executable evidence was produced.

No percentage is reported as product completion. Row counts are evidence states, not a Cordys parity percentage.

## Commands and results

| Command | Exit | Result |
| --- | ---: | --- |
| `node --test plugins/crm/tests/crm-contact-lifecycle-config.test.mjs plugins/crm/tests/crm-multimodel-import-config.test.mjs` | 0 | 7 pass, 0 fail |
| `./gradlew -p plugins/crm/backend test --tests com.auraboot.plugins.crm.handler.ContactPrimaryInvariantHandlerTest` | 0 | targeted handler suite pass |
| `./gradlew -p plugins/crm/backend test` | 0 | full CRM plugin backend suite pass |
| `node --test plugins/crm/tests/*.test.mjs` | 0 | 86 pass, 0 fail; release denominator mutation self-test included |
| `bash scripts/check-test-system.sh` | 0 | manifest generation self-tests, spec registration, command reachability and freshness pass; only baselined warnings |
| `PW_PROFILE=full PW_ROLE_PROJECTS=1 pnpm exec playwright test -c playwright.noweb.config.ts tests/e2e/crm/crm-contact-management-parity.spec.ts --project=chromium --no-deps --list` | 0 | exactly 1 target test collected |
| `/Users/ghj/work/auraboot/dev.sh infra ensure crm-w1-contact-t05 --yes` | non-zero | `environment-invalid`: PostgreSQL connection refused |
| `git diff --check` | 0 | clean |

## Truth audit

- Runner collection is non-empty and resolves the intended spec.
- The new Web journey begins from the official dashboard menu and uses real UI controls for row edit, bulk edit, selected export and mixed-result governed delete.
- Export assertions parse the downloaded workbook and verify both inclusion and exclusion facts.
- The runtime-dependent test was not reported as pass because it did not execute.
- No retry, skip, fixme, threshold, API fallback or caught assertion was added.
- A controlled mutation was not run for the new browser gate because the real stack could not start. Therefore the new Web spec is an evidence candidate, not a trusted golden result.
- Existing primary-contact concurrency evidence is not extrapolated to new list/import/export/batch behavior.

## Shared file edits

- `plugins/crm/config/models.json`
  - JSON pointer `/[code=crm_contact_common]/extension/importPolicy/modes`
  - JSON pointer `/[code=crm_contact_common]/extension/importPolicy/updateKeys`
- No whole-file regeneration or reordering was performed.

## Remaining verification

1. Restore the managed PostgreSQL infrastructure for `crm-w1-contact-t05` and verify runtime PID/cwd/ports/source root.
2. Import the branch-built CRM plugin into that runtime, reset/seed, then run the new contact management spec with one worker and retry 0.
3. Re-run the existing contact lifecycle, contact masking/export and multimodel import/error-report specs on the same fresh branch runtime.
4. Run a controlled mutation to prove the governed-delete or duplicate-channel browser assertion turns red, restore the code, then re-run green.
5. Keep `route:mobile:common:7` as `untested`; mobile is explicitly outside T05 execution scope.

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-08-23-crm-contact-t05-testing-gate-acceptance-report.md
claim_level: targeted filesystem/contract/unit
current_sot: enterprise docs/system-reference/competitive/crm-cordys-parity-sot.md (PAR-07 remains PARTIAL)
business_scope: PAR-07 Web contact list/update/delete/opportunity check/batch/import/export; preserve primary concurrency and lifecycle evidence
integration_tests: did_not_run (environment-invalid)
integration_coverage: account/duplicate/delete invariants covered hermetically; real-stack pending
e2e_specs: crm-contact-management-parity.spec.ts collected 1; lifecycle/masking/import specs retained as required reruns
feature_action_matrix: docs/coverage/crm-contact-t05-coverage-manifest.json (20/20 rows represented; untested/environment-invalid retained)
browser_evidence: did_not_run for branch delta; PostgreSQL preflight failed
backend_evidence: ContactPrimaryInvariantHandlerTest pass
artifact_evidence: selected workbook parser assertions authored; branch runtime download did_not_run
permission_negative: declared in DSL; branch real-stack role execution did_not_run
visual_feedback: browser screenshot assertion authored; did_not_run
skip_fixme_threshold_retry_audit: no new skip/fixme/threshold/retry/fallback
did_not_run: real-stack API/browser, screenshots, workbook download, mutation, mobile
remaining_blockers: managed PostgreSQL unavailable for allocated runtime
allowed_claim: T05 filesystem/contract/unit pass; Web real-stack verification environment-invalid; PAR-07 partial; mobile untested
```
