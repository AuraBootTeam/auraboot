# CRM multi-model Excel import true-stack evidence

- Date: 2026-08-13
- Adoption runtime: `crm-multimodel-import-20260813-s143`; backend `6543`, Vite `5243`, BFF `6243`, database `auraboot_143`
- Partial-recovery runtime: `crm-import-partial-async-20260813-s147`; backend `6547`, Vite `5247`, BFF `6247`, database `auraboot_147`
- Provider-lifecycle runtime: `crm-import-provider-lifecycle-20260813-s148`; backend `6548`, Vite `5248`, BFF `6248`, database `auraboot_148`
- Playwright specs: `crm-multimodel-import-cordys-parity.spec.ts`, `crm-import-provider-failure.golden.spec.ts`, and `crm-import-provider-lifecycle.golden.spec.ts`
- Final runs: adoption `9 passed`; recovery/lifecycle `10 + 1 + 1 passed`; Chromium, one worker, retry=0, trace enabled
- Large-row result: 2,000 Lead rows created in `58.1s`, below the fixed `180s` budget
- Full final-code Playwright traces: retained outside Git at `/Users/ghj/work/auraboot/.workspace/evidence/crm-multimodel-import-20260813-s143/artifacts-final/`; this directory keeps the reviewed screenshots and manifest without adding large trace ZIPs to clone history.
- Adoption manifest: `12 pass / 0 partial / 0 gap / 0 untested`
- Recovery/scale manifest: `14 pass / 1 deferred / 5 untested` out of 20 (`70%` pass)

The journey creates fresh account, duplicate-account, lead and opportunity fixtures, then
drives every import state through the real CRM menu and Excel modal:

1. Lead insert precheck, create-command defaults and persisted record;
2. Lead update by business code with blank-cell preservation;
3. Contact account resolution by business code and public PID;
4. missing and ambiguous account references rejected before any write;
5. Opportunity account/source-lead resolution by business codes and typed money input;
6. Opportunity update by business code with blank-cell preservation;
7. viewer UI entry hidden and direct validation API rejected with HTTP 403;
8. 2,000-row durable asynchronous import with public ULID and final record count;
9. asynchronous update with one success and one failure, durable row error, authorized correction download, corrected-row re-upload, and no replay of the successful row;
10. real MinIO `PutObject` denial with inline recovery, no false download action, and zero import-job residue;
11. real MinIO correction download followed by scheduled seven-day expiry, retained history, HTTP 404, and an explicit expired-report UI.

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
- The partial asynchronous mutation expected zero failures and turned red because the real UI
  reported one failed row. Restoring the correct expectation produced the final CMM-C10 run.
- CMM-C11-01 used a real MinIO identity that could list the test bucket but could not write it;
  the server activated `MinioStorageProvider` and the browser preserved actionable inline errors.
- CMM-C11-02 changed only the completed timestamp. It did not call the cleanup service; the
  real scheduled task removed the private object pointer while preserving terminal counts.
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
- `09-lead-async-partial-result.png` — one row updated and one row failed with a correction action.
- `11-lead-async-partial-recovered.png` — corrected failed row re-uploaded without replaying the successful row.
- `12-minio-upload-denied-inline-recovery.png` — provider upload denial retains inline errors and removes the false download action.
- `14-scheduler-expired-report.png` — retained import history with an explicit expired-report message and no download action.

The committed OOXML evidence is `10-lead-async-partial-correction-original.xlsx` and
`13-downloaded-correction-before-expiry.xlsx`. Full traces remain in the workspace evidence
directories named by `recovery-scale-manifest.json`; large trace ZIPs are not committed.

## Residual gaps

- Multi-node task ownership and shared-report access are explicitly deferred until a real
  multi-replica deployment requirement exists.
- User cancellation, explicit retry, and restart-during-running recovery remain untested.
- A separately budgeted 10k/100k high-volume benchmark remains untested; 2,000 rows is the current adoption
  budget, not proof of Cordys-scale parity.
- Independent non-developer product sign-off and mobile/enterprise-channel import equivalence.
