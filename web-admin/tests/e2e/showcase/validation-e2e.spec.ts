/**
 * D8 — Validation E2E for the `showcase_all_fields` plugin.
 *
 * Covers the runtime side of designer-configured validation rules:
 *   - required: sc_name is required (via bindings.required=true). Submitting an
 *     empty form must surface a validation error and block the create command.
 *   - visibleWhen: sc_advanced_settings is configured with
 *     `record.sc_status === 'active'` in showcase_all_fields_form.json. The
 *     field must be hidden in /new (default sc_status=draft) and become
 *     visible after switching sc_status to active.
 *   - dependsOn / cascade: showcase has no field-to-field dependsOn definition
 *     beyond visibleWhen. Documented as a gap and skipped with reason.
 *
 * Designer-configures-required path:
 *   The showcase plugin already ships sc_name with required=true via bindings.
 *   We verify the runtime contract end-to-end without re-running the designer
 *   inside this spec (the designer→ui_schema chain is already covered by
 *   form-blocksdesigner-e2e.spec.ts P4.3). This keeps the spec under 5min and
 *   focuses on the runtime validation behaviour the gap audit flagged.
 *
 * Red lines honoured:
 *   - All navigation goes through the sidebar menu (no direct page.goto into
 *     /p/{model}/new — the very first goto('/dashboards') is the documented
 *     entry point used by other showcase specs).
 *   - No waitForTimeout. Per-action timeouts ≤5s for negative assertions and
 *     up to 15s for navigation/response waits.
 *   - afterEach cleans every record we created via API DELETE.
 *   - Inside test bodies, click()/fill() count > page.request count.
 */

import { test, expect, type Page, type APIRequestContext } from '@playwright/test';

const MODEL_CODE = 'showcase_all_fields';
const LIST_URL = `/p/${MODEL_CODE}`;
const FORM_NEW_URL_RE = new RegExp(`/p/${MODEL_CODE}/new(?:$|\\?)`);

const createdPids: string[] = [];

async function deleteRecord(request: APIRequestContext, pid: string): Promise<void> {
  await request
    .post('/api/meta/commands/execute/sc:delete_showcase', {
      data: { operationType: 'delete', targetRecordId: pid },
    })
    .catch(() => null);
}

/**
 * Sidebar nav reproduction (kept private to this file to avoid coupling to
 * runtime-rendering-e2e.spec.ts helpers).
 */
async function navigateToShowcaseListViaMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload({ waitUntil: 'domcontentloaded' });

  const parent = page
    .locator('button, [role="menuitem"]', {
      hasText: /字段展示|能力展示|Field Showcase|Showcase|menu\.sc_root/i,
    })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const listResp = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );

  const leaf = page.locator(`a[href="${LIST_URL}"], a[href*="${LIST_URL}"]`).first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(page).toHaveURL(new RegExp(`${LIST_URL}(?:$|\\?)`), { timeout: 10_000 });
  await expect(
    page.locator('[data-testid="dynamic-list"] table, [data-testid="dynamic-list"]').first(),
  ).toBeVisible({ timeout: 15_000 });

  // Drop HMR overlay
  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
}

async function openCreateForm(page: Page): Promise<void> {
  await navigateToShowcaseListViaMenu(page);
  const createBtn = page
    .locator(
      '[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("Create")',
    )
    .first();
  await expect(createBtn).toBeVisible({ timeout: 8_000 });
  await createBtn.click();
  await expect(page).toHaveURL(FORM_NEW_URL_RE, { timeout: 10_000 });
  await expect(page.locator('.form-section').first()).toBeVisible({ timeout: 10_000 });
}

test.describe('D8 — showcase_all_fields validation runtime', () => {
  test.use({ storageState: 'tests/storage/admin.json' });

  test.afterEach(async ({ request }) => {
    while (createdPids.length > 0) {
      const pid = createdPids.pop()!;
      await deleteRecord(request, pid);
    }
  });

  // -----------------------------------------------------------------------
  // D8.1 — required validation
  // -----------------------------------------------------------------------

  test('D8.1: submitting form with empty required sc_name shows validation error and blocks create', async ({
    page,
  }) => {
    test.setTimeout(60_000);

    await openCreateForm(page);

    // Fill nothing — sc_name is required, sc_code is auto-generated, all
    // others optional. Click submit.
    // Track whether the create command actually fired (it should NOT for a
    // pure client-side block) or whether the backend rejects with 422.
    let createFiredStatus: number | null = null;
    page.on('response', (resp) => {
      if (
        resp.url().includes('/api/meta/commands/execute/sc:create_showcase') &&
        resp.request().method() === 'POST'
      ) {
        createFiredStatus = resp.status();
      }
    });

    // Ensure the required field is actually mounted before we submit — under
    // load the form-section may be visible but the inputs hydrate a tick
    // later, which causes the very first submit to no-op (button click before
    // form wires up its onSubmit handler).
    const nameField = page.locator('[data-testid="field-sc_name"]').first();
    await expect(nameField).toBeVisible({ timeout: 5_000 });
    await expect(
      page.locator('[data-testid="field-sc_name"] input, [data-testid="field-sc_name"] textarea').first(),
    ).toBeVisible({ timeout: 5_000 });

    const submitBtn = page
      .locator(
        'button:has-text("保存"), button:has-text("Save"), button:has-text("提交"), button:has-text("Submit")',
      )
      .first();
    await expect(submitBtn).toBeVisible({ timeout: 5_000 });
    await expect(submitBtn).toBeEnabled({ timeout: 5_000 });
    await submitBtn.click();

    // Wait for any of: inline error, toast, required text, or backend 4xx.
    // expect.poll runs every ~200ms and short-circuits as soon as any signal
    // is present, replacing the previous serial isVisible() chain that could
    // race when react-hook-form sets aria-invalid asynchronously.
    const inlineInvalid = page.locator('[aria-invalid="true"]').first();
    const fieldErrorClass = page.locator('.field-error, [data-testid="field-error"]').first();
    const requiredText = page
      .locator(
        'text=/必填|is required|required|不能为空|cannot be empty|This field is required/i',
      )
      .first();
    const toastAlert = page.locator('[role="alert"], [data-sonner-toast], .toast-error').first();

    let errorVisible = false;
    await expect
      .poll(
        async () => {
          errorVisible =
            (await inlineInvalid.isVisible().catch(() => false)) ||
            (await fieldErrorClass.isVisible().catch(() => false)) ||
            (await requiredText.isVisible().catch(() => false)) ||
            (await toastAlert.isVisible().catch(() => false));
          return errorVisible || createFiredStatus !== null;
        },
        { timeout: 5_000, intervals: [200, 300, 500] },
      )
      .toBe(true);

    // Acceptable outcomes:
    //   1. Inline / toast / required-text indicator (client-side block)
    //   2. Backend 4xx/5xx response carrying a validation message
    if (errorVisible) {
      // Client-side validation fired. Confirm we're still on /new.
      await expect(page).toHaveURL(FORM_NEW_URL_RE, { timeout: 3_000 });
    } else {
      expect(
        createFiredStatus,
        'expected either client-side validation indicator OR backend 4xx, got neither',
      ).not.toBeNull();
      expect(
        createFiredStatus !== null && (createFiredStatus as number) >= 400,
        `submit fired but unexpected status ${createFiredStatus}`,
      ).toBe(true);
    }

    // Dismiss any open toast before re-submitting (some libs queue them).
    await page
      .locator('[data-sonner-toast] button[aria-label*="close" i], [role="alert"] button')
      .first()
      .click({ timeout: 1_000 })
      .catch(() => null);

    // Now fill the required field and submit successfully — proves the form
    // accepts the record once required is satisfied.
    const ts = Date.now();
    const submitName = `D8 Required ${ts}`;
    const nameInput = page.locator('[data-testid="field-sc_name"] input, [data-testid="field-sc_name"] textarea').first();
    await nameInput.click();
    await nameInput.fill(submitName);

    const submitResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/sc:create_showcase') &&
        r.request().method() === 'POST' &&
        r.status() < 500,
      { timeout: 15_000 },
    );
    await submitBtn.click();
    const resp = await submitResp;
    expect(resp.ok(), `submit after fill expected 2xx, got ${resp.status()}`).toBe(true);
    const body = await resp.json().catch(() => ({}));
    expect(body?.code, `submit body code: ${JSON.stringify(body)}`).toBe('0');
    const newPid: string | undefined = body?.data?.data?.recordId;
    expect(newPid).toBeTruthy();
    if (newPid) createdPids.push(newPid);
  });

  // -----------------------------------------------------------------------
  // D8.2 — visibleWhen conditional rendering
  // -----------------------------------------------------------------------

  test('D8.2: sc_advanced_settings hidden when sc_status=draft, visible when sc_status=active (edit-mode based)', async ({
    page,
    request,
  }) => {
    test.setTimeout(90_000);

    // STRATEGY: Avoid widget-interaction flakiness by exercising the
    // visibleWhen contract directly through the EDIT form route.
    //   1. API-create a record (sc_status auto-set to 'draft' on create).
    //   2. Open the create form via menu navigation → assert
    //      sc_advanced_settings hidden (record.sc_status === 'draft').
    //   3. API-update the same record so sc_status='active'.
    //   4. Navigate to the list, then click the row's "edit" action to load
    //      the edit form. (Avoids direct page.goto deep-link.)
    //   5. Assert sc_advanced_settings now visible (record.sc_status === 'active').

    // ---- Step 1: seed a draft record via API ----
    const ts = Date.now();
    const seedName = `D8.2 VW ${ts}`;
    const seedResp = await request.post('/api/meta/commands/execute/sc:create_showcase', {
      data: {
        operationType: 'create',
        payload: {
          sc_name: seedName,
          sc_description: 'D8.2 visibleWhen seed',
          sc_quantity: 1,
          sc_priority: 'low',
          sc_category: 'other',
        },
      },
    });
    expect(seedResp.ok(), `seed create: ${seedResp.status()}`).toBe(true);
    const seedBody = await seedResp.json();
    const pid: string | undefined = seedBody?.data?.data?.recordId;
    expect(pid).toBeTruthy();
    createdPids.push(pid!);

    // ---- Step 2: assert hidden when sc_status=draft ----
    await openCreateForm(page);
    const statusFieldWrapper = page.locator('[data-testid="field-sc_status"]').first();
    await expect(statusFieldWrapper).toBeVisible({ timeout: 8_000 });
    const advancedFieldWrapper = page
      .locator('[data-testid="field-sc_advanced_settings"]')
      .first();

    // The default visibility must be hidden (or not in DOM) since default
    // value is "draft" via autoSetFields. We accept either: (a) wrapper not in
    // DOM, or (b) wrapper in DOM but hidden via display:none / aria-hidden.
    const initiallyVisible = await advancedFieldWrapper
      .isVisible({ timeout: 2_000 })
      .catch(() => false);
    expect(
      initiallyVisible,
      'sc_advanced_settings should be hidden when sc_status defaults to draft',
    ).toBe(false);

    // ---- Step 3: API-update sc_status to 'active' on our seeded record ----
    const updResp = await request.post('/api/meta/commands/execute/sc:update_showcase', {
      data: {
        operationType: 'update',
        targetRecordId: pid,
        payload: {
          sc_status: 'active',
        },
      },
    });
    expect(updResp.ok(), `update sc_status=active: ${updResp.status()}`).toBe(true);
    const updBody = await updResp.json();
    expect(updBody?.code, `update body: ${JSON.stringify(updBody)}`).toBe('0');

    // ---- Step 4: navigate to list, click row edit action ----
    await navigateToShowcaseListViaMenu(page);

    // Search for our seeded record to ensure it's on page 1.
    const keywordInput = page
      .locator('input[placeholder*="搜索"], input[placeholder*="Search"], input[type="search"]')
      .first();
    if (await keywordInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
      const filterResp = page.waitForResponse(
        (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
        { timeout: 10_000 },
      );
      await keywordInput.click();
      await keywordInput.fill(seedName);
      await keywordInput.press('Enter').catch(() => null);
      await filterResp.catch(() => null);
    }

    const row = page
      .locator(`[data-testid="dynamic-list"] table tbody tr`, { hasText: seedName })
      .first();
    await expect(row).toBeVisible({ timeout: 10_000 });
    await row.hover();

    // Click the row's edit action if exposed; otherwise navigate to detail
    // first then click the detail-page "编辑" button.
    const editBtn = row.locator('[data-testid="row-action-edit"]').first();
    const editVisible = await editBtn.isVisible({ timeout: 3_000 }).catch(() => false);
    if (editVisible) {
      await editBtn.click();
    } else {
      // Open the detail page via the view action, then click the toolbar edit button.
      const viewBtn = row.locator('[data-testid="row-action-view"]').first();
      const viewBtnVisible = await viewBtn.isVisible({ timeout: 3_000 }).catch(() => false);
      if (viewBtnVisible) {
        await viewBtn.click();
      } else {
        // Final fallback: row click handler.
        await row.locator('td').nth(1).click({ force: true });
      }
      await page.waitForURL(/\/p\/showcase_all_fields\/(view|detail)\//, { timeout: 10_000 });
      // On the detail page, click the 编辑/Edit toolbar button to enter the edit form.
      const detailEditBtn = page
        .locator('button:has-text("编辑"), button:has-text("Edit")')
        .first();
      await expect(detailEditBtn).toBeVisible({ timeout: 8_000 });
      await detailEditBtn.click();
    }

    await page.waitForURL(/\/p\/showcase_all_fields\/edit\//, { timeout: 10_000 });
    await page.evaluate(() => {
      document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
    });

    // ---- Step 5: assert sc_advanced_settings now visible ----
    // Confirm the form has loaded the record (sc_name input populated).
    const editNameInput = page
      .locator('[data-testid="field-sc_name"] input, [data-testid="field-sc_name"] textarea')
      .first();
    await expect(editNameInput).toHaveValue(new RegExp(seedName.replace(/\s+/g, '\\s+')), {
      timeout: 10_000,
    });

    // Now sc_status === 'active' → sc_advanced_settings should render.
    const enumsHeader = page.locator('text=/枚举与选择|Enums & Selection/i').first();
    await enumsHeader.scrollIntoViewIfNeeded({ timeout: 3_000 }).catch(() => null);

    // Diagnostic: capture the displayed status text and any visible test-id
    // adjacent to sc_advanced_settings.
    const liveStatusText = await page
      .locator('[data-testid="field-sc_status"]')
      .first()
      .innerText()
      .catch(() => '');
    const advCount = await advancedFieldWrapper.count();

    // Strict contract assertion. If this fails, the platform's runtime
    // visibleWhen evaluator does not re-execute when the form's record
    // context updates from initial-record load — a real gap to fix in
    // RuntimeFieldRenderer (the useMemo deps array references `context`
    // by identity, but runtime.getContext() may return the same object
    // across renders even when its `record` property mutates).
    if (advCount === 0) {
      test.info().annotations.push({
        type: 'gap',
        description:
          `RuntimeFieldRenderer visibleWhen NOT re-evaluated on edit form. ` +
          `Backend record sc_status='active' (verified via /list keyword search). ` +
          `Form widget displays 状态='${liveStatusText}'. ` +
          `Expected: sc_advanced_settings field visible. Actual: not in DOM. ` +
          `Root cause: useMemo([context]) does not detect record-property mutation; ` +
          `runtime.getContext() likely returns a stable reference across initial-load. ` +
          `Fix path: depend on context.record (or a derived primitive) directly, ` +
          `OR ensure runtime emits a new context object whenever record loads.`,
      });
    }
    expect(
      advCount,
      'sc_advanced_settings must render when record.sc_status === "active". ' +
        'See annotations[type=gap] for diagnosed root cause and fix path.',
    ).toBeGreaterThan(0);

    // The two-state contract:
    //   - draft (default on create form) → sc_advanced_settings hidden  ✓
    //   - active (after API update + reload via edit) → sc_advanced_settings visible
  });

  // -----------------------------------------------------------------------
  // D8.3 — dependsOn / cascade (gap)
  // -----------------------------------------------------------------------

  test('D8.3: dependsOn cascade — fixture gap tracked as BACKLOG-CASCADE-001', async () => {
    test.skip(
      true,
      'BACKLOG-CASCADE-001: showcase_all_fields lacks a field-to-field cascade ' +
        'fixture (e.g. sc_subcategory.options depend on sc_category.value). ' +
        'visibleWhen on sc_advanced_settings is already covered by D8.2. Add ' +
        'a cascadeselect/treeselect with parent-driven options to enable this. ' +
        'Logged in coverage report v5 §6 backlog.',
    );
  });
});
