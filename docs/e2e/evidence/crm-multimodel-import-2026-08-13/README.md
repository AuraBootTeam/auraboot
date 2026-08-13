# CRM multi-model Excel import true-stack evidence

- Date: 2026-08-13
- Runtime: `crm-multimodel-import-20260813-s143`
- Ports: backend `6543`, Vite `5243`, BFF `6243`
- Database: `auraboot_143`
- Playwright spec: `web-admin/tests/e2e/crm/crm-multimodel-import-cordys-parity.spec.ts`
- Final verdict: `9 passed` in `1.9m`, Chromium, one worker, retry=0, trace enabled
- Large-row result: 2,000 Lead rows created in `58.1s`, below the fixed `180s` budget
- Full final-code Playwright traces: retained outside Git at `/Users/ghj/work/auraboot/.workspace/evidence/crm-multimodel-import-20260813-s143/artifacts-final/`; this directory keeps the reviewed screenshots and manifest without adding large trace ZIPs to clone history.
- Focused manifest: `12 pass / 0 partial / 0 gap / 0 untested`

The journey creates fresh account, duplicate-account, lead and opportunity fixtures, then
drives every import state through the real CRM menu and Excel modal:

1. Lead insert precheck, create-command defaults and persisted record;
2. Lead update by business code with blank-cell preservation;
3. Contact account resolution by business code and public PID;
4. missing and ambiguous account references rejected before any write;
5. Opportunity account/source-lead resolution by business codes and typed money input;
6. Opportunity update by business code with blank-cell preservation;
7. viewer UI entry hidden and direct validation API rejected with HTTP 403;
8. 2,000-row durable asynchronous import with public ULID and final record count.

The test uses live APIs only for fixture creation and final persistence assertions. Import
templates, upload, precheck, mode selection, submit, progress and result are all driven through
the visible Web UI. No retry or test skip is configured. Development-stage data is newly
created; no data migration is required.

## Trust and red-to-green evidence

- First product red: an empty numeric Lead cell reached PostgreSQL as `""`; the UI exposed a
  raw SQL/MyBatis error. Blank cells are now omitted and infrastructure details stay server-side.
- Second product red: Opportunity money passed precheck but reached the command as a string.
  Import now converts nonblank values through the declared metadata type before execution.
- The same CMM-06 browser journey then passed without weakened assertions, followed by the
  complete `9/9` run.
- Missing and ambiguous reference cases assert zero records both before and after precheck.
- Screenshots were reviewed at original resolution; no raw SQL, stack trace, PID or internal
  field code appears in the tested user states.

## Screenshot manifest

- `01-lead-insert-precheck.png` — Lead insert precheck, command-default columns left blank.
- `02-contact-code-precheck.png` — Contact account resolved from the unique account code.
- `03-contact-missing-blocked.png` — missing account blocked with localized zero-write error.
- `04-contact-ambiguous-blocked.png` — duplicate account name requires code or PID.
- `05-opportunity-result.png` — Opportunity import completed with two business references.
- `06-viewer-no-import.png` — viewer menu has export/configuration but no import entry.
- `07-lead-2000-result.png` — durable asynchronous result shows 2,000 created, 0 failed.

## Residual gaps

- Error-report workbook download and row-level correction/re-upload workflow.
- Cancellation/retry controls and restart-during-running recovery beyond explicit failure.
- A separately budgeted 10k+/100k high-volume benchmark; 2,000 rows is the current adoption
  budget, not proof of Cordys-scale parity.
- Independent non-developer product sign-off and mobile/enterprise-channel import equivalence.
