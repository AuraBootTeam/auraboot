# CRM Contact T05 Testing Gate Acceptance Report

`allowed_claim: T05 targeted Web contact management and retained lifecycle evidence pass on the allocated fresh runtime; PAR-07 remains partial outside this denominator and mobile remains untested.`

## Scope and identity

- Cordys baseline: `v1.8.1 / ab96c96f524985ea84f112c7a6b03970711f921e`.
- Aura base: `8ffc13e32dc3ab6a9030a139ca465e4c9b78f043`.
- Branch/worktree: `codex/crm-w1-contact-t05-20260823` at `/Users/ghj/work/auraboot/.worktrees/auraboot-crm-w1-contact-t05-20260823`.
- Runtime: `crm-w1-contact-t05`, slot 5, DB `auraboot_5`, backend/Web/BFF `6405/5105/6105`.
- Source proof: backend PID `38623`, cwd `<worktree>/platform`; listeners were backend `38623`, Web child `39091`, BFF child `39109`.
- This report is T05-only. It does not claim W1, PAR-07 outside this denominator, or full Cordys parity.

## Runtime and artifact proof

- Managed infra succeeded after PostgreSQL restoration; the official reset initialized fresh `auraboot_5` with 77 Flyway migrations.
- The first import failed closed because CRM handlers were not loaded. The branch hybrid JAR was staged before restart; the second CRM import and reference-integrity sweep passed.
- CRM JAR SHA-256: `fc6888788a5db6a88b2f31d3df510889e2c57a7696d02ce431cfdeb0fe952c0c`; staged runtime JAR matched.
- Reset later stopped at unrelated marketplace seed data exceeding `varchar(500)`, after bootstrap, three listeners and CRM import had succeeded. This did not invalidate the tested CRM runtime.

## Delivered and verified

- Official-menu contact list/search and list-origin edit.
- Missing-account and case-insensitive duplicate-channel rejection.
- Permission-gated bulk edit of the explicitly editable title column.
- Selected export with OOXML parsed for two inclusions and primary-contact exclusion.
- Command-owned bulk delete: one secondary deleted; primary and opportunity-linked contacts retained with governed reasons.
- Contact import template/business keys, failed-precheck zero writes, error-workbook content, correction upload and successful import.
- Existing primary promotion, disable/enable with retained history, and two-session single-winner evidence rerun green.

The browser run exposed and fixed two real contract defects: the title column had not opted into strict bulk editing, and delete ran as a chained secondary after generic deletion. The contact handler now owns the command and the bulk action no longer pre-deletes the row.

## Denominator

The 20-row authority is [crm-contact-t05-coverage-manifest.json](../coverage/crm-contact-t05-coverage-manifest.json); every row carries `sourceId / action / field / state / permission / sideEffect`.

- 13 `pass`, 3 `partial`, 4 `untested` as represented by the manifest.
- Mobile remains `untested`.
- No percentage or targeted pass is extrapolated to the whole product.

## Commands and results

| Command | Result |
| --- | --- |
| managed infra + official reset/import | fresh DB, 77 migrations, bootstrap/login/listeners, CRM import and reference sweep pass; later marketplace seed failure recorded |
| managed CRM backend `clean test jar` | pass |
| `crm-contact-management-parity.spec.ts`, Chromium, 1 worker, retry 0 | **1/1 pass, 8.1s** |
| `crm-contact-followup-lifecycle-parity.spec.ts`, Chromium, 1 worker, retry 0 | **1/1 pass, 11.0s** |
| `crm-multimodel-import-cordys-parity.spec.ts`, Chromium, 1 worker, retry 0 | **9/10 pass, 1.2m**; contact CMM-03/04/05 pass |
| prior CRM config suite | 86/86 pass |
| `git diff --check` | pass |

The sole multimodel failure was CMM-10 asynchronous Lead update: the result showed `1 updated / 1 failed` and correction actions but omitted inline row-error text. It is outside T05 and was formally handed to T08; it is not counted as pass.

## Evidence and trust

- `/Users/ghj/work/auraboot/.workspace/evidence/crm-w1-contact-t05/contact-parity-results/`: passed receipt, final screenshot, parsed `selected-contacts.xlsx`.
- `/Users/ghj/work/auraboot/.workspace/evidence/crm-w1-contact-t05/lifecycle-results/`: passed receipt, history/concurrency screenshots and JSON receipt.
- `/Users/ghj/work/auraboot/.workspace/evidence/crm-w1-contact-t05/import-results/`: truthfully records CMM-10 failure; contact CMM-04 had already asserted error-workbook rows and recovery.
- No retry, skip, fixme, threshold relaxation, caught assertion or API fallback was added.

## Shared JSON pointers

- `plugins/crm/config/models.json`
  - `/[code=crm_contact_common]/extension/importPolicy/modes`
  - `/[code=crm_contact_common]/extension/importPolicy/updateKeys`
- `plugins/crm/config/pages/crm_contact_common_list.json`
  - `/blocks/[id=crm_contact_table]/table/bulkActions/[code=bulk_delete_contacts]/action/operationType` (removed)
  - `/blocks/[id=crm_contact_table]/columns/[field=crm_ct_title]/editable`
- `docs/coverage/crm-contact-t05-coverage-manifest.json`: only affected T05 row verdict/evidence metadata.

No shared JSON file was regenerated or reordered. Mobile, chart, account-filtered list, form-config HTTP surface, export-all masking breadth and Web empty/error breadth are not promoted by these targeted passes.
