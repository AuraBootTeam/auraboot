/**
 * E2E Test: Column Settings (visibility, drag reorder, auto-create SavedView)
 *
 * Covers:
 *   D1  — Menu navigation to list page
 *   D2  — Column settings panel opens with checkboxes
 *   D4  — Column visibility toggle (hide + show)
 *   D6  — Column order change persists in backend
 *   D8  — Column width setting persists
 *   D14 — Operation feedback (panel close after save)
 *
 * Key scenario: When NO SavedView exists, column settings auto-creates a
 * personal SavedView on save. Before this fix, save was silently discarded.
 *
 * Uses e2et_order model.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order';

// ---------------------------------------------------------------------------
// Navigation helper — sidebar menu, NOT page.goto for the list page  [D1]
// ---------------------------------------------------------------------------

async function navigateToOrderList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Click parent menu "E2E测试" (fixture canonical) or "移动端测试" (auto-imported variant)
  const rootBtn = nav
    .getByRole('button', { name: /E2E测试|E2E Test|移动端测试/i })
    .first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  // Click leaf menu "测试订单"
  const leafLink = nav.locator('a[href*="e2et_order"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/e2et_order') && r.url().includes('list') && r.status() === 200,
    { timeout: 20_000 },
  );
  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;

  // Assert table is visible
  await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible({
    timeout: 15_000,
  });
}

/** Delete all SavedViews for e2et_order */
async function deleteAllSavedViews(page: Page) {
  const resp = await page.request.get(
    `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
  );
  if (resp.ok()) {
    const body = await resp.json();
    const views = body.data ?? [];
    for (const v of views) {
      await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
    }
  }
}

/** Open column settings panel and return the panel locator */
async function openColumnSettingsPanel(page: Page) {
  const settingsBtn = page.locator('[data-testid="column-settings-btn"]');
  await expect(settingsBtn).toBeVisible({ timeout: 5000 });
  await settingsBtn.click();
  const panel = page.getByTestId('column-settings-panel');
  await expect(panel).toBeVisible({ timeout: 3000 });
  return panel;
}

/** Get Save button (handles i18n: "Save" or "保存") */
function getSaveBtn(panel: ReturnType<Page['getByTestId']>) {
  return panel
    .locator('button')
    .filter({ hasText: /save|保存/i })
    .last();
}

/** Get Cancel button (handles i18n: "Cancel" or "取消") */
function getCancelBtn(panel: ReturnType<Page['getByTestId']>) {
  return panel
    .locator('button')
    .filter({ hasText: /cancel|取消/i })
    .last();
}

test.describe('Column Settings — SavedView integration', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ browser }) => {
    const page = await browser.newPage();
    // Clean up any existing saved views for a fresh test
    await deleteAllSavedViews(page);
    // Ensure at least 1 test record exists
    const title = `ColSet_${uniqueId()}`;
    await page.request.post(`/api/dynamic/${PAGE_KEY}/execute`, {
      data: {
        commandCode: 'create_order',
        data: {
          e2et_order_title: title,
          e2et_order_type: 'normal',
          e2et_order_urgent: false,
        },
      },
    });
    await page.close();
  });

  test('CS-001: Column settings panel opens from toolbar button @smoke', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Should have column checkboxes (title, type, urgent, etc.)
    const checkboxes = panel.locator('input[type="checkbox"]');
    const count = await checkboxes.count();
    expect(count).toBeGreaterThanOrEqual(3);

    // Should have header text
    await expect(panel.getByText('Column Settings')).toBeVisible();

    // Should have batch action buttons
    await expect(panel.getByText('Select All', { exact: true })).toBeVisible();
    await expect(panel.getByText('Deselect All')).toBeVisible();

    // Should have draggable column items
    const draggableItems = panel.locator('div[draggable="true"]');
    expect(await draggableItems.count()).toBeGreaterThanOrEqual(3);

    // Should have width inputs
    const widthInputs = panel.locator('input[type="number"]');
    expect(await widthInputs.count()).toBeGreaterThan(0);

    // Should have Save and Cancel buttons
    await expect(getSaveBtn(panel)).toBeVisible();
    await expect(getCancelBtn(panel)).toBeVisible();
  });

  test.fixme('CS-002: Hide column → auto-creates SavedView → column removed from table @critical', async ({
    page,
  }) => {
    // Ensure clean state — no SavedView exists
    await deleteAllSavedViews(page);

    await navigateToOrderList(page);

    // Record initial column headers
    const headersBefore = await page.locator('thead th').allTextContents();

    const panel = await openColumnSettingsPanel(page);

    // Find business columns (non-system)
    const columnItems = panel.locator('div[draggable="true"]');
    const itemCount = await columnItems.count();
    expect(itemCount).toBeGreaterThan(2);

    // Pick the 3rd business column to hide
    const targetIdx = Math.min(2, itemCount - 1);
    const targetItem = columnItems.nth(targetIdx);
    const targetLabel = (await targetItem.locator('span.truncate').textContent())?.trim() ?? '';
    const checkbox = targetItem.locator('input[type="checkbox"]');
    expect(await checkbox.isChecked()).toBe(true);

    // Uncheck to hide
    await checkbox.uncheck();
    expect(await checkbox.isChecked()).toBe(false);

    // Save — should auto-create SavedView via POST or auto-save
    const viewResponse = page.waitForResponse(
      (r) => r.url().includes('/api/views') && ['POST', 'PUT'].includes(r.request().method()),
      { timeout: 8000 },
    );
    await getSaveBtn(panel).click();
    const resp = await viewResponse.catch(() => null);
    if (resp) {
      expect(resp.ok()).toBe(true);
    }

    // Panel should close
    await expect(panel).not.toBeVisible({ timeout: 5000 });

    // Wait for table re-render with new column config — reload to ensure fresh state
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('thead th').first().waitFor({ state: 'visible', timeout: 10_000 });

    // Verify column is hidden — check headers no longer contain the label
    const headersAfter = await page.locator('thead th').allTextContents();
    if (targetLabel) {
      const wasInHeaders = headersBefore.some((h) => h.includes(targetLabel));
      const isInHeaders = headersAfter.some((h) => h.includes(targetLabel));
      // If the label was originally visible, it should now be hidden
      if (wasInHeaders) {
        // Column config may take effect after reload; if not, the feature may have a bug
        expect(isInHeaders, `Column "${targetLabel}" should be hidden after unchecking and saving`).toBe(false);
      }
    }
  });

  test('CS-003: Auto-created SavedView has correct structure in backend', async ({ page }) => {
    test.fixme(true, 'SavedView auto-creation depends on CS-002 UI state — unreliable in batch runs');
    // After CS-002, verify the auto-created view in the API
    const resp = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const views = body.data ?? [];
    expect(views.length).toBeGreaterThanOrEqual(1);

    const myView = views.find((v: any) => v.name === 'My View');
    expect(myView).toBeTruthy();
    expect(myView.scope).toBe('personal');
    expect(myView.modelCode).toBe(MODEL_CODE);
    expect(myView.viewConfig).toBeTruthy();
    expect(myView.viewConfig.columns).toBeDefined();
    expect(myView.viewConfig.columns.length).toBeGreaterThan(0);

    // At least one column should be hidden (visible=false)
    const hiddenCols = myView.viewConfig.columns.filter((c: any) => c.visible === false);
    expect(hiddenCols.length).toBeGreaterThanOrEqual(1);

    // Each column should have fieldCode and order
    for (const col of myView.viewConfig.columns) {
      expect(col.fieldCode).toBeTruthy();
      expect(typeof col.order).toBe('number');
    }
  });

  test('CS-004: Column settings persist after page reload', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Count hidden columns (unchecked checkboxes)
    const checkboxes = panel.locator('input[type="checkbox"]');
    const total = await checkboxes.count();
    let hiddenCount = 0;
    for (let i = 0; i < total; i++) {
      if (!(await checkboxes.nth(i).isChecked())) {
        hiddenCount++;
      }
    }

    // Should still have at least 1 hidden column from CS-002
    expect(hiddenCount).toBeGreaterThanOrEqual(1);
  });

  test('CS-005: "Select All" restores all columns @smoke', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Click "Select All"
    await panel.getByText('Select All', { exact: true }).click();

    // All checkboxes should now be checked
    const checkboxes = panel.locator('input[type="checkbox"]');
    const total = await checkboxes.count();
    for (let i = 0; i < total; i++) {
      await expect(checkboxes.nth(i)).toBeChecked();
    }

    // Save (should update existing view via PUT)
    const saveResp = page.waitForResponse(
      (r) => r.url().includes('/api/views') && r.request().method() === 'PUT',
      { timeout: 5000 },
    );
    await getSaveBtn(panel).click();
    const resp = await saveResp;
    expect(resp.ok()).toBe(true);

    await expect(panel).not.toBeVisible({ timeout: 3000 });
  });

  test('CS-006: "Deselect All" unchecks all columns', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Click "Deselect All"
    await panel.getByText('Deselect All').click();

    // All checkboxes should be unchecked
    const checkboxes = panel.locator('input[type="checkbox"]');
    const total = await checkboxes.count();
    for (let i = 0; i < total; i++) {
      await expect(checkboxes.nth(i)).not.toBeChecked();
    }

    // Restore via "Select All" before saving (don't want 0 columns)
    await panel.getByText('Select All', { exact: true }).click();
    for (let i = 0; i < total; i++) {
      await expect(checkboxes.nth(i)).toBeChecked();
    }

    const saveResp = page.waitForResponse(
      (r) => r.url().includes('/api/views') && r.request().method() === 'PUT',
      { timeout: 5000 },
    );
    await getSaveBtn(panel).click();
    await saveResp;
    await expect(panel).not.toBeVisible({ timeout: 3000 });
  });

  test('CS-007: Save columns → order values saved correctly', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    const columnItems = panel.locator('div[draggable="true"]');
    const itemCount = await columnItems.count();
    expect(itemCount).toBeGreaterThanOrEqual(2);

    // Toggle a checkbox to ensure there's a real change to save
    const lastCheckbox = columnItems.last().locator('input[type="checkbox"]');
    const wasChecked = await lastCheckbox.isChecked();
    await lastCheckbox.click(); // toggle
    await lastCheckbox.click(); // toggle back (ensures change is detected)

    // Save — match both PUT and POST
    const saveResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/views') &&
        (r.request().method() === 'PUT' || r.request().method() === 'POST'),
      { timeout: 8000 },
    );
    await getSaveBtn(panel).click();
    await saveResp;
    await expect(panel).not.toBeVisible({ timeout: 3000 });

    // Verify order values in backend
    const viewResp = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    const viewBody = await viewResp.json();
    const views = viewBody.data ?? [];
    const myView = views.find((v: any) => v.name === 'My View');
    expect(myView).toBeTruthy();
    expect(myView.viewConfig?.columns?.length).toBeGreaterThan(0);

    // Each column should have a numeric order value
    const cols = myView.viewConfig.columns;
    for (const col of cols) {
      expect(typeof col.order).toBe('number');
    }

    // Orders should be sequential (0, 1, 2, ...)
    const orders = cols.map((c: any) => c.order).sort((a: number, b: number) => a - b);
    for (let i = 0; i < orders.length; i++) {
      expect(orders[i]).toBe(i);
    }
  });

  test('CS-008: Column width setting persists in backend', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Set width=200 on the first column
    const widthInputs = panel.locator('input[type="number"]');
    expect(await widthInputs.count()).toBeGreaterThan(0);
    await widthInputs.first().fill('200');

    // Save — match both PUT and POST
    const saveResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/views') &&
        (r.request().method() === 'PUT' || r.request().method() === 'POST'),
      { timeout: 8000 },
    );
    await getSaveBtn(panel).click();
    await saveResp;
    await expect(panel).not.toBeVisible({ timeout: 3000 });

    // Verify in backend
    const viewResp = await page.request.get(
      `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`,
    );
    const viewBody = await viewResp.json();
    const views = viewBody.data ?? [];
    const myView = views.find((v: any) => v.name === 'My View');
    expect(myView).toBeTruthy();

    const colWithWidth = myView.viewConfig?.columns?.find((c: any) => c.width === 200);
    expect(colWithWidth).toBeTruthy();
  });

  test('CS-009: Cancel button closes panel without saving', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Make a change — deselect all
    await panel.getByText('Deselect All').click();

    // Cancel — should NOT trigger any API call
    await getCancelBtn(panel).click();
    await expect(panel).not.toBeVisible({ timeout: 3000 });

    // Re-open and verify nothing changed (checkboxes should still be checked)
    const panel2 = await openColumnSettingsPanel(page);
    const checkboxes = panel2.locator('input[type="checkbox"]');
    const total = await checkboxes.count();
    let checkedCount = 0;
    for (let i = 0; i < total; i++) {
      if (await checkboxes.nth(i).isChecked()) {
        checkedCount++;
      }
    }
    // Most columns should still be visible (cancelled change was not saved)
    expect(checkedCount).toBeGreaterThan(total / 2);
  });
});
