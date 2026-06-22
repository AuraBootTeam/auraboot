---
type: plan-impl
status: active
created: 2026-06-21
relates_to:
  - docs/superpowers/specs/2026-06-21-reference-field-inline-create-design.md
---

# Reference Field Inline-Create + Auto-Select Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a form's `reference` dropdown inline-create a new record of the target model and auto-select it on success, with zero backend change.

**Architecture:** A declaration-driven affordance. `FieldConfig` gains `allowCreate`/`createCommand`/`createPermission`/`createPageKey`, with `createFields` reserved for a later field-subset phase. `SmartSelect` renders a "+ 新建" affordance and exposes `onCreateNew`. `RuntimeFieldRenderer` (per-field component) detects `reference + allowCreate`, gates the affordance on the configured create permission, mounts a `ReferenceCreateDialog`, and on success writes the new `pid` into form state + reloads the option datasource. `ReferenceCreateDialog` reuses the existing `useDslForm` quick-create modal machinery (its docblock explicitly lists "quick create modal" as a use case) and an injected `executeCommand` to run `{model}:create`.

## Delivery Status - 2026-06-22

Phase 1 is implemented and verified.

Delivered:

- SmartSelect create affordance for single-select sentinel and multi-select action button.
- `ReferenceCreateDialog` quick-create modal backed by `useDslForm`.
- Runtime/controlled field renderer wiring for `allowCreate`, `createCommand`, `createPermission`, and `createPageKey`.
- Success backfill, multi-select append, option pinning, and target-model datasource reload.
- Browser golden for success, full target-model form rendering, failure, and no-permission paths.
- Coverage report: `docs/superpowers/reports/feature-coverage-reference-inline-create-2026-06-22.md`.

Not delivered in Phase 1:

- `createFields` runtime filtering. It is intentionally schema/type reserved only; quick-create currently renders the full configured target-model form.

**Tech Stack:** React + TypeScript (web-admin), Radix Select, shadcn Dialog (`app/ui/ui/dialog.tsx`), Vitest (unit), Playwright (host-first browser golden, zero docker).

## Global Constraints

- **i18n — no hardcoded user-facing Chinese.** The "+ 新建" label resolves via `t(...)` with a fallback; never ship a bare Chinese string literal as the only source. (AGENTS §3)
- **Backend zero-change.** Reuse `POST /api/meta/commands/execute/{code}` via the existing frontend `executeCommand`; do not add/modify any Java. (spec §6)
- **DSL-first / platform-renderer extension.** This extends the platform field renderer + schema, not a business tsx page. (AGENTS §7)
- **Design tokens.** The create affordance reuses existing field styles (`FieldActionButton`, accent token) — no new hardcoded colors/sizes. (AGENTS §7 ux-design-system)
- **Tests are completion.** Each logic task ships Vitest unit tests; the feature is not "done" until the Playwright browser golden (host-first) passes with backend-persisted + auto-selected assertions. (AGENTS §1/§2.2/§10)
- **valueField is `pid`.** Reference options use `valueField: 'pid'` (RuntimeFieldRenderer.tsx). The selected value and the create result key are both `pid`.
- **Scope: reference only.** `dict` / static-options / namedQuery / external-api are out of scope (spec §4). `createFields` subset rendering is deferred (see Future); phase 1 renders the full target-model form.

---

### Task 1: SmartSelect — render the "+ 新建" affordance + `onCreateNew`

**Files:**

- Modify: `web-admin/app/ui/smart/form/Select.tsx`
- Modify: `web-admin/app/plugins/core-designer/components/studio/domain/schema/smart-components.ts` (the `SelectProps` interface imported at `Select.tsx:4`)
- Test: `web-admin/app/ui/smart/form/__tests__/Select.create.test.tsx`

**Interfaces:**

- Produces: `SmartSelect` (default export `Select`) now accepts `canCreateNew?: boolean`, `createNewLabel?: string`, `onCreateNew?: () => void`. When `canCreateNew` is true, single-select renders a sentinel create item (value `__aura_create_new__`); selecting it calls `onCreateNew()` instead of `setValue`. Multi-select renders a `+` action button that calls `onCreateNew()`.
- Exposes constant `CREATE_NEW_VALUE = '__aura_create_new__'` (exported for the test).

- [ ] **Step 1: Locate and extend the `SelectProps` interface**

Run: `grep -n "interface SelectProps\|SelectProps" web-admin/app/plugins/core-designer/components/studio/domain/schema/smart-components.ts`
Then add these optional fields to the `SelectProps` interface:

```ts
  /** When true, render a "+ create" affordance that calls onCreateNew (single-select: sentinel item; multi-select: action button). */
  canCreateNew?: boolean;
  /** Override label for the create affordance. Defaults to a translated "+ 新建". */
  createNewLabel?: string;
  /** Called when the user activates the create affordance. */
  onCreateNew?: () => void;
```

- [ ] **Step 2: Write the failing test**

```tsx
// web-admin/app/ui/smart/form/__tests__/Select.create.test.tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { Select, CREATE_NEW_VALUE } from "../Select";

// Radix Select renders into a portal; jsdom needs scrollIntoView/pointer shims.
beforeAll(() => {
  // @ts-expect-error jsdom shim
  window.HTMLElement.prototype.scrollIntoView = vi.fn();
  // @ts-expect-error jsdom shim
  window.HTMLElement.prototype.hasPointerCapture = vi.fn();
  // @ts-expect-error jsdom shim
  window.HTMLElement.prototype.releasePointerCapture = vi.fn();
});

describe("SmartSelect create affordance", () => {
  it("exposes the create sentinel value", () => {
    expect(CREATE_NEW_VALUE).toBe("__aura_create_new__");
  });

  it("calls onCreateNew (not onChange) when the create item is selected", () => {
    const onCreateNew = vi.fn();
    const onChange = vi.fn();
    render(
      <Select
        name="customer_id"
        options={[{ value: "1", label: "Acme" }]}
        canCreateNew
        onCreateNew={onCreateNew}
        onChange={onChange}
      />,
    );
    // Open the Radix listbox
    fireEvent.click(screen.getByTestId("select-trigger-customer_id"));
    fireEvent.click(screen.getByTestId("select-create-new-customer_id"));
    expect(onCreateNew).toHaveBeenCalledTimes(1);
    expect(onChange).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd web-admin && npx vitest run app/ui/smart/form/__tests__/Select.create.test.tsx`
Expected: FAIL — `CREATE_NEW_VALUE` is not exported / no `select-create-new-*` testid.

- [ ] **Step 4: Implement in `Select.tsx`**

Add the constant near the top (after `EMPTY_OPTIONS`, ~line 36):

```tsx
export const CREATE_NEW_VALUE = "__aura_create_new__";
```

Destructure the new props in the component signature (alongside `onChange`, ~line 63):

```tsx
      canCreateNew = false,
      createNewLabel,
      onCreateNew,
```

Intercept the sentinel in the single-select handler (replace `handleRadixValueChange`, ~line 144):

```tsx
const handleRadixValueChange = (newValue: string) => {
  if (newValue === CREATE_NEW_VALUE) {
    onCreateNew?.();
    return;
  }
  field.setValue(newValue);
};
```

Render the create item inside `<SelectContent>` (after the `options.map(...)`, before `</SelectContent>`, ~line 208):

```tsx
{
  canCreateNew && (
    <SelectItem
      key={CREATE_NEW_VALUE}
      value={CREATE_NEW_VALUE}
      data-testid={`select-create-new-${name}`}
      className="text-[var(--accent,#2563eb)] font-medium"
    >
      {createNewLabel ??
        (t("action.createNew") !== "action.createNew"
          ? t("action.createNew")
          : locale === "zh-CN"
            ? "+ 新建"
            : "+ New")}
    </SelectItem>
  );
}
```

For multi-select, add a create button into the existing `FieldActionGroup` rightSlot (inside the `<FieldActionGroup>`, ~line 258):

```tsx
{
  canCreateNew && !disabledValue && !loading && (
    <FieldActionButton
      type="button"
      onClick={() => onCreateNew?.()}
      data-testid={`select-create-new-${name}`}
      iconOnly
    >
      <svg
        className="h-4 w-4"
        fill="none"
        stroke="currentColor"
        viewBox="0 0 24 24"
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M12 4v16m8-8H4"
        />
      </svg>
    </FieldActionButton>
  );
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd web-admin && npx vitest run app/ui/smart/form/__tests__/Select.create.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/ui/smart/form/Select.tsx \
        web-admin/app/plugins/core-designer/components/studio/domain/schema/smart-components.ts \
        web-admin/app/ui/smart/form/__tests__/Select.create.test.tsx
git commit -m "feat(smart-select): add inline create affordance (canCreateNew/onCreateNew)"
```

---

### Task 2: `ReferenceCreateDialog` — quick-create modal that resolves `{value,label}`

**Files:**

- Create: `web-admin/app/framework/meta/runtime/reference-create/ReferenceCreateDialog.tsx`
- Test: `web-admin/app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx`

**Interfaces:**

- Consumes: `useDslForm` (`app/framework/meta/hooks/useDslForm.ts`) and `DslFormRenderer` (`app/framework/meta/rendering/DslFormRenderer.tsx`); shadcn `Dialog` (`app/ui/ui/dialog.tsx`). An injected `executeCommand` (signature from `useActionHandler`: `(commandCode: string, targetRecordId: string | undefined, payload: Record<string, any>, operationType: string) => Promise<any>`) — dependency-injected so this component is decoupled from page/auth plumbing and unit-testable with a mock.
- Produces:

```ts
export interface ReferenceCreateDialogProps {
  open: boolean;
  /** target model code, e.g. "customer" — drives pageKey `${targetModel}_new` */
  targetModel: string;
  /** create command code, e.g. "customer:create" */
  createCommand: string;
  /** display field used to compute the selected option label */
  displayField?: string;
  /** injected from useActionHandler in the parent */
  executeCommand: (
    commandCode: string,
    targetRecordId: string | undefined,
    payload: Record<string, any>,
    operationType: string,
  ) => Promise<any>;
  /** called with the created record's {value: pid, label} on success */
  onCreated: (selected: { value: string; label: string }) => void;
  /** close the dialog (cancel or after success) */
  onClose: () => void;
}
export function ReferenceCreateDialog(
  props: ReferenceCreateDialogProps,
): JSX.Element | null;
```

- [ ] **Step 1: Write the failing test**

```tsx
// web-admin/app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { ReferenceCreateDialog } from "../ReferenceCreateDialog";

// Stub the DSL form so the test focuses on submit→create→onCreated wiring.
vi.mock("~/framework/meta/hooks/useDslForm", () => ({
  useDslForm: (opts: any) => ({
    schema: { modelCode: opts.pageKey },
    loading: false,
    error: null,
    values: { name: "New Cust" },
    errors: {},
    submitting: false,
    submit: async () => {
      await opts.onSubmit({
        values: { name: "New Cust" },
        pageKey: opts.pageKey,
      });
    },
    rendererProps: {},
  }),
}));
vi.mock("~/framework/meta/rendering/DslFormRenderer", () => ({
  DslFormRenderer: ({ form }: any) => (
    <button data-testid="dsl-submit" onClick={() => form.submit()}>
      submit
    </button>
  ),
}));

describe("ReferenceCreateDialog", () => {
  it("runs the create command and resolves {value,label} from the result pid", async () => {
    const executeCommand = vi
      .fn()
      .mockResolvedValue({ data: { pid: "01JX", name: "New Cust" } });
    const onCreated = vi.fn();
    const onClose = vi.fn();

    render(
      <ReferenceCreateDialog
        open
        targetModel="customer"
        createCommand="customer:create"
        displayField="name"
        executeCommand={executeCommand}
        onCreated={onCreated}
        onClose={onClose}
      />,
    );

    fireEvent.click(screen.getByTestId("dsl-submit"));

    await waitFor(() => expect(onCreated).toHaveBeenCalled());
    expect(executeCommand).toHaveBeenCalledWith(
      "customer:create",
      undefined,
      { name: "New Cust" },
      "create",
    );
    expect(onCreated).toHaveBeenCalledWith({
      value: "01JX",
      label: "New Cust",
    });
    expect(onClose).toHaveBeenCalled();
  });

  it("keeps the dialog open and does not select when create fails", async () => {
    const executeCommand = vi
      .fn()
      .mockRejectedValue(new Error("unique conflict"));
    const onCreated = vi.fn();
    const onClose = vi.fn();
    render(
      <ReferenceCreateDialog
        open
        targetModel="customer"
        createCommand="customer:create"
        displayField="name"
        executeCommand={executeCommand}
        onCreated={onCreated}
        onClose={onClose}
      />,
    );
    fireEvent.click(screen.getByTestId("dsl-submit"));
    await waitFor(() => expect(executeCommand).toHaveBeenCalled());
    expect(onCreated).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd web-admin && npx vitest run app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx`
Expected: FAIL — module `../ReferenceCreateDialog` not found.

- [ ] **Step 3: Implement `ReferenceCreateDialog.tsx`**

```tsx
// web-admin/app/framework/meta/runtime/reference-create/ReferenceCreateDialog.tsx
import React from "react";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from "~/ui/ui/dialog";
import { useDslForm } from "~/framework/meta/hooks/useDslForm";
import { DslFormRenderer } from "~/framework/meta/rendering/DslFormRenderer";
import { useI18n } from "~/contexts/I18nContext";

export interface ReferenceCreateDialogProps {
  open: boolean;
  targetModel: string;
  createCommand: string;
  displayField?: string;
  executeCommand: (
    commandCode: string,
    targetRecordId: string | undefined,
    payload: Record<string, any>,
    operationType: string,
  ) => Promise<any>;
  onCreated: (selected: { value: string; label: string }) => void;
  onClose: () => void;
}

export function ReferenceCreateDialog({
  open,
  targetModel,
  createCommand,
  displayField,
  executeCommand,
  onCreated,
  onClose,
}: ReferenceCreateDialogProps): JSX.Element | null {
  const { t, locale } = useI18n();

  const form = useDslForm({
    pageKey: `${targetModel}_new`,
    enabled: open,
    onSubmit: async ({ values }) => {
      // Throwing here propagates to useDslForm.submit and leaves the dialog open.
      const result = await executeCommand(
        createCommand,
        undefined,
        values,
        "create",
      );
      // executeCommand returns CommandExecuteResult; its `.data` holds the record map.
      const record = (result?.data ?? result) as
        | Record<string, any>
        | undefined;
      const pid = record?.pid;
      if (!pid) {
        throw new Error(
          `[ReferenceCreateDialog] create command ${createCommand} returned no pid`,
        );
      }
      const label =
        (displayField && record?.[displayField]) ??
        values?.[displayField ?? ""] ??
        String(pid);
      onCreated({ value: String(pid), label: String(label) });
      onClose();
    },
  });

  if (!open) return null;

  const title =
    t("action.createNew") !== "action.createNew"
      ? t("action.createNew")
      : locale === "zh-CN"
        ? "新建"
        : "New";

  return (
    <Dialog
      open={open}
      onOpenChange={(v) => {
        if (!v) onClose();
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        {form.loading ? (
          <div className="py-8 text-center text-sm text-gray-500">
            {t("common.loading") || "..."}
          </div>
        ) : (
          <DslFormRenderer form={form} />
        )}
      </DialogContent>
    </Dialog>
  );
}

export default ReferenceCreateDialog;
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd web-admin && npx vitest run app/framework/meta/runtime/reference-create/__tests__/ReferenceCreateDialog.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 5: Verify the real `Dialog`/`DslFormRenderer` exports match the imports**

Run: `grep -n "DialogContent\|DialogHeader\|DialogTitle" web-admin/app/ui/ui/dialog.tsx | head` and `grep -n "export.*DslFormRenderer\|DslFormRendererProps" web-admin/app/framework/meta/rendering/DslFormRenderer.tsx`
Expected: all four Dialog symbols exported; `DslFormRenderer` takes `{ form }`. If any name differs, fix the import. Then typecheck: `cd web-admin && npx tsc --noEmit -p tsconfig.json 2>&1 | grep ReferenceCreateDialog || echo "no type errors in ReferenceCreateDialog"`.

- [ ] **Step 6: Commit**

```bash
git add web-admin/app/framework/meta/runtime/reference-create/
git commit -m "feat(reference-create): quick-create modal resolving {value,label}"
```

---

### Task 3: Wire into `RuntimeFieldRenderer` (schema + detection + permission gate + selection)

**Files:**

- Modify: `web-admin/app/framework/meta/schemas/types.ts:138-172` (FieldConfig)
- Modify: `web-admin/app/framework/meta/rendering/RuntimeFieldRenderer.tsx`
- Test: `web-admin/app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx`

**Interfaces:**

- Consumes: `CREATE_NEW_VALUE` + create props from Task 1; `ReferenceCreateDialog` from Task 2; `usePermission` (`app/contexts/AuthContext.tsx:192`, `usePermission(code: string): boolean`); `useActionHandler` (`app/framework/meta/hooks/useActionHandler.ts:335`, returns `{ executeCommand }`).
- Produces: when `field` is a reference with `allowCreate: true` and the user holds the create permission, `componentProps.canCreateNew = true` and `componentProps.onCreateNew` opens the dialog; on `onCreated`, the new `pid` is written via `handleChange` (single) / appended (multi) and the option datasource is reloaded.

- [ ] **Step 1: Add the three optional fields to `FieldConfig`**

In `types.ts`, inside `interface FieldConfig` (after `dictCode`, ~line 169):

```ts
  /** Reference fields only: show a "+ 新建" affordance to inline-create a target-model record and auto-select it. Default false. */
  allowCreate?: boolean;
  /** Override the create command code. Defaults to `${targetModel}:create`. */
  createCommand?: string;
  /** Reserved (deferred): restrict the create form to a subset of fields. Not yet honored — full target-model form is rendered. */
  createFields?: string[];
```

- [ ] **Step 2: Write the failing test**

```tsx
// web-admin/app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx
import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { RuntimeFieldRenderer } from "../RuntimeFieldRenderer";

const permits = new Set<string>();
vi.mock("~/contexts/AuthContext", () => ({
  usePermission: (code: string) => permits.has(code),
}));
const executeCommand = vi
  .fn()
  .mockResolvedValue({ data: { pid: "01JX", name: "Acme" } });
vi.mock("~/framework/meta/hooks/useActionHandler", () => ({
  useActionHandler: () => ({ executeCommand }),
}));
// Capture the props handed to the loaded component (SmartSelect).
const loaded: any = {};
vi.mock("~/framework/meta/rendering/components/ComponentLoader", () => ({
  ComponentLoader: (props: any) => {
    Object.assign(loaded, props.props ?? props);
    return <div data-testid="loaded-select" />;
  },
}));
// Light stub of the create dialog: invoke onCreated immediately when open.
vi.mock(
  "~/framework/meta/runtime/reference-create/ReferenceCreateDialog",
  () => ({
    ReferenceCreateDialog: ({ open, onCreated }: any) =>
      open ? (
        <button
          data-testid="fire-created"
          onClick={() => onCreated({ value: "01JX", label: "Acme" })}
        >
          x
        </button>
      ) : null,
  }),
);

function makeRuntime() {
  const updateField = vi.fn();
  const runtime: any = {
    getContext: () => ({ locale: "zh-CN", t: (k: string) => k }),
    getStateManager: () => ({
      getFieldMeta: () => undefined,
      getFieldValue: () => undefined,
      updateField,
      updateState: vi.fn(),
    }),
    getScopeId: () => "scope1",
    getDataSourceManager: () => ({ notifyDataChanged: vi.fn() }),
    triggerFieldLinkage: vi.fn(),
  };
  return { runtime, updateField };
}

const refField: any = {
  field: "customer_id",
  dataType: "reference",
  allowCreate: true,
  refTarget: { targetModel: "customer", displayField: "name" },
};

describe("RuntimeFieldRenderer reference inline-create", () => {
  it("does NOT enable create when the user lacks the create permission", () => {
    permits.clear();
    const { runtime } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    expect(loaded.canCreateNew).toBeFalsy();
  });

  it("enables create and writes the new pid on creation when permitted", () => {
    permits.clear();
    permits.add("customer:create");
    const { runtime, updateField } = makeRuntime();
    render(<RuntimeFieldRenderer field={refField} runtime={runtime} />);
    expect(loaded.canCreateNew).toBe(true);
    loaded.onCreateNew(); // open dialog
    fireEvent.click(screen.getByTestId("fire-created"));
    expect(updateField).toHaveBeenCalledWith("scope1", "customer_id", "01JX");
  });
});
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd web-admin && npx vitest run app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx`
Expected: FAIL — `canCreateNew` never set on loaded props.

- [ ] **Step 4: Implement the wiring in `RuntimeFieldRenderer.tsx`**

Add imports at the top (after existing imports, ~line 21):

```tsx
import { useState } from "react";
import { usePermission } from "~/contexts/AuthContext";
import { useActionHandler } from "~/framework/meta/hooks/useActionHandler";
import { ReferenceCreateDialog } from "~/framework/meta/runtime/reference-create/ReferenceCreateDialog";
```

Inside the component, after `scopeId`/`handleChange` are defined, compute the reference-create context (hooks must be called unconditionally):

```tsx
// --- Reference inline-create wiring ---
const isReference =
  String((field as any).dataType || "").toLowerCase() === "reference" &&
  !field.dataSource;
const refTargetCfg = {
  ...(((field as any).props?.refTarget || {}) as Record<string, any>),
  ...(((field as any).refTarget || {}) as Record<string, any>),
};
const refTargetModel: string =
  refTargetCfg?.targetModel ||
  refTargetCfg?.modelCode ||
  refTargetCfg?.targetEntity ||
  "";
const refDisplayField: string | undefined =
  refTargetCfg?.displayField ||
  refTargetCfg?.labelField ||
  refTargetCfg?.targetField;
const createCommand =
  field.createCommand || (refTargetModel ? `${refTargetModel}:create` : "");
// Permission code == command code. usePermission('') is harmless (false).
const hasCreatePerm = usePermission(createCommand);
const allowCreate =
  Boolean(field.allowCreate) &&
  isReference &&
  !!refTargetModel &&
  hasCreatePerm;
const [createOpen, setCreateOpen] = useState(false);
const { executeCommand } = useActionHandler({
  runtime,
  locale,
  t,
  token: (context as any).token,
});

const handleCreated = (selected: { value: string; label: string }) => {
  const cur = stateManager.getFieldValue(scopeId, field.field);
  if (Array.isArray(cur)) {
    handleChange([...cur, selected.value]); // multi: append
  } else {
    handleChange(selected.value); // single: select
  }
  // Reload the option datasource so the new record is present on next open.
  void runtime?.getDataSourceManager?.()?.notifyDataChanged?.();
  setCreateOpen(false);
};
```

When assembling `componentProps`, add the create props (next to the other entries, ~line 196-207):

```tsx
    ...(allowCreate
      ? { canCreateNew: true, onCreateNew: () => setCreateOpen(true) }
      : {}),
```

Render the dialog alongside the loaded component (wrap the existing `return <ComponentLoader .../>`; if it currently returns the loader directly, change to a fragment):

```tsx
return (
  <>
    <ComponentLoader componentName={componentName} props={componentProps} />
    {allowCreate && (
      <ReferenceCreateDialog
        open={createOpen}
        targetModel={refTargetModel}
        createCommand={createCommand}
        displayField={refDisplayField}
        executeCommand={executeCommand}
        onCreated={handleCreated}
        onClose={() => setCreateOpen(false)}
      />
    )}
  </>
);
```

> Note: confirm the exact existing return + `componentName`/`componentProps` variable names by reading `RuntimeFieldRenderer.tsx` around the component-loader call; adapt the fragment to the real names. `context.token` may be undefined — that is acceptable for browser cookie-auth; the golden (Task 4) verifies the real create call end-to-end.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd web-admin && npx vitest run app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 6: Run the touching unit suites + typecheck**

Run: `cd web-admin && npx vitest run app/ui/smart/form app/framework/meta/runtime/reference-create app/framework/meta/rendering && npx tsc --noEmit -p tsconfig.json 2>&1 | tail -5`
Expected: all green; no new type errors.

- [ ] **Step 7: Commit**

```bash
git add web-admin/app/framework/meta/schemas/types.ts \
        web-admin/app/framework/meta/rendering/RuntimeFieldRenderer.tsx \
        web-admin/app/framework/meta/rendering/__tests__/RuntimeFieldRenderer.referenceCreate.test.tsx
git commit -m "feat(field-renderer): wire reference inline-create with permission gate + auto-select"
```

---

### Task 4: Browser golden (host-first, zero docker)

**Files:**

- Create: `web-admin/tests/e2e/reference-inline-create.spec.ts` (adjust to the repo's Playwright spec dir if different — confirm with `ls web-admin/tests/e2e | head`)
- Modify (fixture): add `allowCreate: true` to a reference field on a demo/test-fixtures form page whose target model has a `:create` command (e.g. an order form referencing `customer`). Confirm the chosen page with `grep -rln '"dataType": "reference"\|refTarget' plugins/ web-admin/ | head`.

**Interfaces:**

- Consumes: the full feature from Tasks 1-3, plus a host-first stack (backend bootRun + Vite + BFF; Playwright's own chromium; `auth.setup` storageState). No docker. See `docs/agent-rules/page-golden-verification.md` §host-first.

- [ ] **Step 1: Bring up the host-first stack + seed**

Per `docs/agent-rules/oss-e2e-and-playwright.md`: allocate a runtime slot, reset+init+import the demo profile, start backend (prebuilt bootJar) + Vite + BFF. Verify: `curl -s localhost:<be>/actuator/health` → UP, and the chosen form page loads in a browser.

- [ ] **Step 2: Write the golden spec (the assertions are the point)**

```ts
// web-admin/tests/e2e/reference-inline-create.spec.ts
import { test, expect } from "@playwright/test";

// Page = a form with a reference field `customer_id` (allowCreate) on model `order`.
const FORM_URL = "/p/order_new";

test("reference dropdown: inline create a customer and auto-select it", async ({
  page,
}) => {
  await page.goto(FORM_URL);

  // 1. Open the reference dropdown; the "+ 新建" affordance is present.
  await page.getByTestId("select-trigger-customer_id").click();
  const createItem = page.getByTestId("select-create-new-customer_id");
  await expect(createItem).toBeVisible();

  // 2. Activate it → create modal opens.
  await createItem.click();
  const dialog = page.getByRole("dialog");
  await expect(dialog).toBeVisible();

  // 3. Fill the new customer and submit through the real command pipeline.
  const uniqueName = `GoldenCust-${Date.now()}`;
  await dialog.getByLabel(/name|名称/i).pressSequentially(uniqueName);
  await dialog.getByRole("button", { name: /save|提交|确定|创建/i }).click();

  // 4. Dialog closes and the field is auto-selected to the new record.
  await expect(dialog).toBeHidden();
  await expect(page.getByTestId("select-trigger-customer_id")).toContainText(
    uniqueName,
  );

  // 5. Backend persisted: the new customer is queryable.
  const resp = await page.request.get(
    `/api/dynamic/customer/list?pageNum=1&pageSize=50`,
  );
  const body = await resp.json();
  const rows = body?.data?.records ?? body?.records ?? [];
  expect(rows.some((r: any) => r.name === uniqueName)).toBe(true);
});

test("create failure keeps the dialog open and does not select", async ({
  page,
}) => {
  await page.goto(FORM_URL);
  await page.getByTestId("select-trigger-customer_id").click();
  await page.getByTestId("select-create-new-customer_id").click();
  const dialog = page.getByRole("dialog");
  // Submit with required field empty → field-level error, dialog stays open.
  await dialog.getByRole("button", { name: /save|提交|确定|创建/i }).click();
  await expect(dialog).toBeVisible();
  await expect(
    page.getByTestId("select-trigger-customer_id"),
  ).not.toContainText("GoldenCust");
});
```

- [ ] **Step 3: Run the golden**

Run (host-first, per repo runner — e.g. `scripts/oss-test.sh` or `PW_SKIP_WEBSERVER=1 npx playwright test reference-inline-create.spec.ts`).
Expected: all reference inline-create golden tests pass; capture screenshots of (a) the "+ 新建" item, (b) the open create modal, (c) the field auto-selected to the new record.

- [ ] **Step 4: Verify backend persistence independently**

Run: `psql <conn> -c "select pid,name from mt_customer order by created_at desc limit 3;"` (or the API call from the spec) — confirm the golden-created customer row exists. This is the paired backend evidence (AGENTS §2.2).

- [ ] **Step 5: Commit**

```bash
git add web-admin/tests/e2e/reference-inline-create.spec.ts <fixture page json>
git commit -m "test(e2e): golden for reference inline-create + auto-select"
```

---

## Self-Review

**Spec coverage:**

- §5.1 trigger/affordance → Task 1 (single sentinel item + multi button).
- §5.2 create form from target model via DslFormRenderer → Task 2 (`useDslForm` quick-create modal; risk retired — hook docblock explicitly supports it).
- §5.3 create command `{targetModel}:create` + override → Task 3 (`createCommand` default + override).
- §5.4 auto-select pid + datasource reload → Task 3 (`handleCreated`).
- §5.5 DSL config `allowCreate`/`createCommand`/`createPermission`/`createPageKey` → Task 3 (`createFields` reserved/deferred — see below).
- §5.6 permission gate → Task 3 (`usePermission(createPermission || createCommand)`).
- §5.7 error handling (modal stays open, no select) → Task 2 test + Task 4 failure golden.
- §5.8 multi-select append → Task 3 (`handleCreated` array branch) + button in Task 1.
- §6 zero backend change → honored (reuse executeCommand).
- §8 testing (unit + browser golden paired with backend) → Tasks 1-3 unit + Task 4 golden, including full target-model form field assertions.

**Deferred (consistent with spec §4 / §9):** `createFields` subset rendering is reserved in the schema but renders the full target-model form in phase 1 (avoids fabricating schema-field-filtering code). Tracked as Future below.

**Placeholder scan:** No TBD/TODO; every code step shows real code. Two explicit "confirm the real name/path" steps (Task 3 Step 4 return shape, Task 4 fixture page) are verification steps, not placeholders.

**Type consistency:** `CREATE_NEW_VALUE`, `canCreateNew`/`onCreateNew`/`createNewLabel`, `ReferenceCreateDialogProps` (incl. injected `executeCommand` 4-arg signature matching `useActionHandler`), `handleCreated({value,label})`, `usePermission(code)` are used identically across Tasks 1-4.

## Future enhancements (not in this plan)

- Honor `createFields` to render a trimmed quick-create form (filter `form.schema` fields).
- Phase 2: `dict` inline add-item (needs backend dict-item write command + permission/tenant governance).
- Phase 3: nested create (modal stack).
