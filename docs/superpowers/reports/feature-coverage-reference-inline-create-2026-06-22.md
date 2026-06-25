---
type: run-log
status: active
created: 2026-06-22
---

# Reference Inline Create Coverage Report - 2026-06-22

## Scope

Worktree: `/Users/ghj/work/auraboot/auraboot-enterprise/.claude/worktrees/reference-inline-create`

Feature: reference field inline create for DSL form runtime.

Phase note: `createFields` remains a schema-reserved field per the implementation plan. Phase 1 renders the configured create page as a full quick-create form and does not filter fields at runtime.

## Feature / Action Matrix

| Requirement                            | User action or system behavior                                                                                                   | Evidence                                                                                        | Status |
| -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ------ |
| SmartSelect single-select create entry | User opens a reference select and clicks `+ new`; the sentinel triggers `onCreateNew` and does not emit a normal value change.   | `Select.create.test.tsx`                                                                        | PASS   |
| SmartSelect single-select search       | User types in the select search box; options filter by label/value while the create entry remains visible.                       | `Select.test.tsx`                                                                               | PASS   |
| SmartSelect multi-select create button | User opens a multi reference select and clicks the create action button.                                                         | `Select.create.test.tsx`                                                                        | PASS   |
| Keep newly created controlled value    | After a value is set from create, Radix empty value events do not clear the field.                                               | `Select.create.test.tsx`                                                                        | PASS   |
| Quick-create dialog uses DSL form      | Dialog mounts `useDslForm` with the create page key.                                                                             | `ReferenceCreateDialog.test.tsx`                                                                | PASS   |
| Default create page key fallback       | Dialog defaults to `${targetModel}_new` when `createPageKey` is omitted.                                                         | `ReferenceCreateDialog.test.tsx`                                                                | PASS   |
| Create command result normalization    | Command responses with direct and nested `pid` shapes resolve `{ value, label }`.                                                | `ReferenceCreateDialog.test.tsx`                                                                | PASS   |
| Failed create path                     | Failed command keeps the dialog open and does not select anything.                                                               | `ReferenceCreateDialog.test.tsx`; Playwright `RIC-002`                                          | PASS   |
| Runtime schema gate                    | `allowCreate`, `createCommand`, `createPermission`, and `createPageKey` wire create behavior only for eligible reference fields. | `RuntimeFieldRenderer.referenceCreate.test.tsx`; `ControlledFieldRenderer.test.tsx`             | PASS   |
| Permission gate                        | User without customer create/manage permission can open order form but cannot see/open inline create.                            | `RuntimeFieldRenderer.referenceCreate.test.tsx`; Playwright `RIC-003`; DB role permission proof | PASS   |
| Explicit data source reference create  | Reference fields backed by explicit `dataSource` can still inline-create when `allowCreate` and a target model are declared.     | `RuntimeFieldRenderer.referenceCreate.test.tsx`; `ControlledFieldRenderer.test.tsx`             | PASS   |
| Reference type detection               | `FieldConfig.type = reference` is recognized even without naming inference.                                                      | `RuntimeFieldRenderer.referenceCreate.test.tsx`                                                 | PASS   |
| Single-value backfill                  | Created record pid is written back to the reference field.                                                                       | `RuntimeFieldRenderer.referenceCreate.test.tsx`; Playwright `RIC-001`                           | PASS   |
| Multi-value append                     | Created pid appends to an existing array and does not duplicate an already selected pid.                                         | `RuntimeFieldRenderer.referenceCreate.test.tsx`                                                 | PASS   |
| Data source refresh                    | Target-model reference data source is reloaded after successful create.                                                          | `RuntimeFieldRenderer.referenceCreate.test.tsx`; `useFieldDataSource.test.tsx`                  | PASS   |
| Created option pinning                 | Created option stays selectable before/after reload even when the page does not return it.                                       | `RuntimeFieldRenderer.referenceCreate.test.tsx`                                                 | PASS   |
| Host-first browser success flow        | Browser clicks `+ new`, creates a customer, backend persists it, and the order customer field auto-selects it.                   | Playwright `RIC-001`; DB `mt_e2et_customer` rows with `RIC-C_*` and region `east`               | PASS   |
| Full target-model form in Phase 1      | Quick-create dialog renders the full `e2et_customer_form` field set; `createFields` filtering is not part of Phase 1.            | Playwright `RIC-004`                                                                            | PASS   |
| Host-first browser failure flow        | Browser submits an invalid quick-create form, dialog remains open, field stays unselected, and backend has no matching customer. | Playwright `RIC-002`                                                                            | PASS   |

## Runtime Data Evidence

Slot: `reference-inline-create-75`.

- `ab_meta_model`: `e2et_order` and `e2et_customer` are `published` and current.
- `ab_page_schema`: `e2et_order_form` and `e2et_customer_form` are `published`.
- `e2et_order_customer` field config in `e2et_order_form` includes:
  - `allowCreate: true`
  - `createCommand: e2et:create_customer`
  - `createPageKey: e2et_customer_form`
  - `createPermission: e2et.customer.manage`
- Latest browser-created customer records exist in `mt_e2et_customer` with `RIC-C_*` codes and `e2et_cust_region = east`.
- Latest permission-gate roles include `page.page.read`, order model create/read, customer read, and do not include `e2et.customer.manage` or `model.e2et_customer.create`.

## Verification Commands

- Preflight:
  - `curl http://127.0.0.1:6475/actuator/health` → `{"status":"UP"}`
  - Frontend `/login` on `5175` → HTTP 200.
  - BFF `/health` on `6175` → HTTP 200.
- `npx vitest run app/ui/smart/form/__tests__/Select.create.test.tsx app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx app/framework/meta/rendering/__tests__/ControlledFieldRenderer.test.tsx app/framework/meta/hooks/__tests__/useFieldDataSource.test.tsx`
  - Result: 5 files, 32 tests passed.
- `pnpm exec vitest run app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx app/framework/meta/rendering/__tests__/ControlledFieldRenderer.test.tsx app/ui/smart/form/__tests__/Select.test.tsx app/ui/smart/form/__tests__/Select.create.test.tsx`
  - Result: 4 files, 30 tests passed.
- `pnpm exec eslint app/ui/smart/form/Select.tsx app/ui/smart/form/__tests__/Select.test.tsx app/framework/meta/rendering/RuntimeFieldRenderer.tsx app/framework/meta/rendering/ControlledFieldRenderer.tsx app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx app/framework/meta/rendering/__tests__/ControlledFieldRenderer.test.tsx`
  - Result: passed.
- `pnpm exec tsc --noEmit --pretty false`
  - Result: passed.
- `npx tsc --noEmit -p tsconfig.json`
  - Result: passed.
- `npx prettier --check <changed frontend/test files>`
  - Result: passed.
- `node scripts/check-docs-governance.mjs --git --changed <changed docs>`
  - Result: passed.
- `git diff --check`
  - Result: passed.
- `PW_PROFILE=contract IMPORT_TEST_FIXTURES=true PW_WORKERS=1 npx playwright test tests/e2e/e2et-order/e2et-reference-inline-create.spec.ts --project=contract --no-deps --reporter=line`
  - Result: 4 tests passed.

## E2E Truth Audit

- No `test.skip`, `test.fixme`, or `skip(true)`.
- No `waitForTimeout`.
- No high per-action timeout literals after the cleanup pass.
- No threshold/baseline/retries assertions.
- No direct business `page.goto('/p/...')` in the spec.
- `page.request` / request helper calls: 6, limited to setup, permission proof, and backend persistence assertions.
- Browser click/fill interactions in the spec: 9. The critical create paths are browser driven.
