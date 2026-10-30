---
type: system-reference
status: active
created: 2026-10-27
---

# Default CRUD regression

`pnpm test:crud` runs the original MW-01 model workbench journey and the restored customer-project case through the real browser, BFF and database. It has one worker, zero retries and no managed-server replacement. A profile collection contract keeps the original case identity and prevents an empty/missing profile from appearing green.

## Prerequisites

Use a task-owned `oss-golden-stack` runtime on the actual Core checkout with `core-meta` and `project-management` imported. Verify its source and artifacts before the final run. No schema DDL, account repair or permissions are silently patched by this suite.

Provide the runtime's `PLAYWRIGHT_BASE_URL`, `BACKEND_URL`, `BE_PORT`, `BFF_PORT`, `PG_DB`, `PW_SKIP_WEBSERVER=1`; use private `PW_STORAGE_DIR` and explicit `PW_ARTIFACT_DIR`, `PW_REPORT_DIR`, `PW_RESULTS_JSON`. The original `auth.setup.ts` obtains a real BFF session. Supply administrator credentials and `CRUD_READER_PASSWORD` through private environment variables, never tracked files or command logs.

`CRUD_CASE_FIXTURE` identifies a restored, isolated business case. Its JSON contains `modelCode`, `recordPid`, `name`, `customer`, `legacyRecordPid`, `readerEmail`, and a provenance receipt. The current example uses the `zcrud_engagement` project and related `zcrud_client` / `zcrud_line` business bindings. Verify the import/backup source and destination ownership before supplying this file. Missing fixtures or reader credentials fail, rather than skipping tests. This compatibility scenario is intentionally excluded from generic OSS scope, which has no restored project/account contract; it remains mandatory in `test:crud`.

The dedicated reader has read permissions only. The suite does not create that account or grant permissions. The project uses ¥680,000 budget, 72% progress and ¥365,000 child total; changing that approved case requires updating fixture assertions intentionally.

## Execution and meaning

From `web-admin`, run `pnpm test:crud` with those variables. Archive the JSON result, original screenshots, trace on failure, runtime verification and source identity together. `--list` checks collection only; it must never be reported as browser execution.

MW-01 prepares a unique published model and fields using normal model APIs, then clicks generate, required validation, create, edit from overflow, save, reload, detail and designer. It checks the precise mutation endpoints, submitted default-generation config and persisted values. Publishing creates the physical table; a draft schema preview alone is not a persistence test.

The restored-case spec also checks 1280/1440/1920 default list widths without column omission or sticky-action text overlap. It checks typed fields, child edit/reopen, reference-dialog validation/cancel/create/backfill without saving the parent, read-only detail/direct-edit and a real denied PUT with unchanged readback, and explicit legacy workspace sections. Restored-case success is not fresh default generation success. Each has separate scenario IDs and artifacts.

A new fixture model is created every run. Cleanup is best effort for those model/field definitions; generated pages or physical tables may remain according to the platform's normal deletion contract. Use an owned verification database, not a user's production or canonical demonstration database. Reference-create rows are also test-owned and retained in that isolated database for diagnosis.

## Boundaries

This focused gate does not replace the full Web/CI suite, designer drag/drop/export gates, or owner visual acceptance. The DR-04–DR-10 journey now drives real pointer field sorting, undo/redo, generated validation/component reflection, localized placeholder and section title edits, span changes, invalid/valid Schema columns, save/reload, download-content checks, rejected invalid import, valid import, publish and actual business edit/readback/restore. It prepares a fresh independent page through normal APIs and proves the original default page unchanged. This is a bounded CRUD designer journey, not a claim that every designer control is covered. UI screenshots require original-image review under the existing golden policy.
