---
type: backlog
status: active
created: 2026-06-13
---

# Pre-existing bug: dynamic-model form submit throws "Action not registered: save"

> Discovered during the Prompt-to-App pages/menus golden (real browser, host-first). **Not introduced by Prompt-to-App** — the shipped `asset-management` plugin form fails identically, so dynamic-model form *create via the UI* is broken platform-wide in this build.

## Symptom (real browser, reproducible)

Open any dynamic-model **form** page (`/p/<model>/new`), fill the required fields, click 提交 (submit):

```
[executeRegistryAction] Action not registered: save
```

The record is **not** created. Confirmed on:
- `/p/tasset_category/new` (shipped `asset-management` plugin) — **fails**
- `/p/sales_lead/new` (Prompt-to-App synthesized, byte-identical form-buttons) — **fails**

The list/form pages **render correctly** (toolbar, columns, humanized labels, required `*` marking, submit/cancel buttons) — only the submit dispatch is broken.

## Evidence it is platform-wide, not plugin-specific

The synthesized form-buttons are byte-identical to the shipped convention
(`asset-management/config/pages/tasset_category_form.json`):

```json
{ "code": "submit", "action": "save", "commandCode": "tasset:create_category", "primary": true, "label": "$i18n:common.button.submit" }
```

`commandCode` **survives** import and is present in the served page schema
(`GET /api/pages/key/<model>_form` returns it on the submit button).

## Root-cause investigation (frontend)

The submit dispatch path is `FormButtonsBlockRenderer`/`FormPageContent.handleFormAction`
(`web-admin/app/framework/meta/rendering/pages/FormPageContent.tsx`):

- `normalizeAction` (`utils/normalizeAction.ts:41`) returns `{type:'command', command:commandCode}`
  when `commandCode` is present, which **should** route to the direct command path
  (`handleFormAction` line ~1114: `if (effectiveCommandCode) { POST /api/meta/commands/execute/... }`).
- `shouldBypassFormSubmit` returns `false` for a `code:"submit"` button (only `cancel`/`back`/`close`
  bypass), so it should **not** delegate to the generic `handleAction`.
- Yet the runtime reaches `executeRegistryAction`, whose `code === 'submit' ? 'save'` mapping
  (`hooks/executeRegistryAction.ts:75`) looks up an action that is **registered nowhere**
  (`ActionRegistry.ts` has `new`/`edit`/`view`/`delete`/`cancel`/… but no `save`).

So at runtime the submit button must be losing its `commandCode` (or `effectiveCommandCode`
resolving empty) before line 1114 — the prime suspect is the "new mode override form button
commandCodes" block (`FormPageContent.tsx` ~line 973) or the `effectiveButtonBlock` processing.

## Backend CRUD is fine

The command pipeline itself works — `POST /api/meta/commands/execute/<plugin>:create_<model>`
with `{payload, operationType:"create"}` persists the row (verified for `visit_log` / `sales_lead`
this session). Only the **browser form → command** wiring is broken.

## Fix direction (needs its own focused slice + golden)

Make `handleFormAction` resolve `effectiveCommandCode` from the (already-present) button
`commandCode` for a `submit`/`save` button before any fallback to the generic action registry,
or register a `save` action that submits via the form's command. Verify with a real-browser
golden on a shipped dynamic form (create → row appears) **and** a synthesized one.

This is a platform-wide form-dispatch change — high blast radius (every form/button) — so it
must be done deliberately with broad form-type coverage (create/edit, command/CRUD, with/without
commandCode), not bundled into an unrelated fix.
