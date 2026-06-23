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

import {
  test,
  expect,
  type APIResponse,
  type Browser,
  type Page,
  type Response as PlaywrightResponse,
} from '@playwright/test';
import { BASE_URL } from '../../helpers/environments';
import { uniqueId } from '../helpers';

const MODEL_CODE = 'e2et_order';
const SAVED_VIEW_PAGE_KEY = 'e2et_order_list';
const ADMIN_STORAGE_STATE = process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json';

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

async function expectApiJson(response: APIResponse | PlaywrightResponse, label: string) {
  const bodyText = await response.text();
  expect(
    response.ok(),
    `${label} failed: ${response.status()} ${response.statusText()} ${bodyText}`,
  ).toBe(true);
  return bodyText ? JSON.parse(bodyText) : {};
}

async function withAdminPage<T>(browser: Browser, action: (page: Page) => Promise<T>): Promise<T> {
  const context = await browser.newContext({
    baseURL: BASE_URL,
    storageState: ADMIN_STORAGE_STATE,
  });
  const page = await context.newPage();
  try {
    return await action(page);
  } finally {
    await context.close();
  }
}

async function listSavedViews(page: Page) {
  const resp = await page.request.get(
    `/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${SAVED_VIEW_PAGE_KEY}`,
  );
  const body = await expectApiJson(resp, 'list saved views');
  return Array.isArray(body.data) ? body.data : [];
}

/** Delete all SavedViews for e2et_order. */
async function deleteAllSavedViews(page: Page) {
  const views = await listSavedViews(page);
  for (const v of views) {
    await page.request.delete(`/api/views/${v.pid}`).catch(() => {});
  }
}

async function expectNoSavedViews(page: Page) {
  const views = await listSavedViews(page);
  expect(
    views,
    `SavedView cleanup left ${views.length} accessible view(s): ${views
      .map((v: any) => `${v.scope}:${v.name}`)
      .join(', ')}`,
  ).toHaveLength(0);
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

async function getSavedViewBySaveResponse(
  page: Page,
  resp: Awaited<ReturnType<Page['waitForResponse']>>,
) {
  const body = await expectApiJson(resp, 'save saved view');
  const saved = body?.data ?? body;
  const pid = saved?.pid;
  if (pid) {
    const detailResp = await page.request.get(`/api/views/${pid}`);
    const detailBody = await expectApiJson(detailResp, 'get saved view detail');
    return detailBody.data ?? detailBody;
  }

  const views = await listSavedViews(page);
  return views.find((v: any) => Array.isArray(v.viewConfig?.columns));
}

async function findImplicitPersonalColumnView(page: Page) {
  const views = await listSavedViews(page);
  return views.find(
    (v: any) =>
      v.scope === 'personal' &&
      v.name === 'Default View' &&
      v.modelCode === MODEL_CODE &&
      v.pageKey === SAVED_VIEW_PAGE_KEY &&
      Array.isArray(v.viewConfig?.columns),
  );
}

function compactText(text: string) {
  return text.replace(/\s+/g, '').trim();
}

function headersContainLabel(headers: string[], label: string) {
  const needle = compactText(label);
  return Boolean(needle) && headers.some((header) => compactText(header).includes(needle));
}

async function hideFirstVisibleColumnAndSave(page: Page) {
  await deleteAllSavedViews(page);
  await expectNoSavedViews(page);

  await navigateToOrderList(page);

  const headersBefore = await page.locator('thead th').allTextContents();
  const panel = await openColumnSettingsPanel(page);

  const columnItems = panel.locator('div[draggable="true"]');
  const itemCount = await columnItems.count();
  expect(itemCount).toBeGreaterThan(2);

  let targetIdx = -1;
  let targetLabel = '';
  for (let i = 0; i < itemCount; i++) {
    const item = columnItems.nth(i);
    const checkbox = item.locator('input[type="checkbox"]');
    const label = (await item.locator('span.truncate').textContent())?.trim() ?? '';
    if ((await checkbox.isChecked()) && headersContainLabel(headersBefore, label)) {
      targetIdx = i;
      targetLabel = label;
      break;
    }
  }

  expect(
    targetIdx,
    `No visible configurable column found in headers: ${headersBefore.join(' | ')}`,
  ).toBeGreaterThanOrEqual(0);

  const targetItem = columnItems.nth(targetIdx);
  const rowTestId = await targetItem.getAttribute('data-testid');
  const targetFieldCode = rowTestId?.replace('column-settings-row-', '') ?? '';
  const checkbox = targetItem.locator('input[type="checkbox"]');
  expect(await checkbox.isChecked()).toBe(true);

  await checkbox.uncheck();
  await expect(checkbox).not.toBeChecked();

  const saveResponse = page.waitForResponse(
    (r) => r.url().includes('/api/views/auto-save') && r.request().method() === 'POST',
    { timeout: 8000 },
  );
  await getSaveBtn(panel).click();
  const resp = await saveResponse;
  const savedView = await getSavedViewBySaveResponse(page, resp);

  await expect(panel).not.toBeVisible({ timeout: 5000 });

  return { headersBefore, targetFieldCode, targetLabel, savedView };
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

function getPanelButton(panel: ReturnType<Page['getByTestId']>, name: string) {
  return panel.getByRole('button', { name, exact: true });
}

test.describe('Column Settings — SavedView integration', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ browser }) => {
    // Clean up any existing saved views for a fresh test
    await withAdminPage(browser, async (page) => {
      await deleteAllSavedViews(page);
      // Ensure at least 1 test record exists
      const title = `ColSet_${uniqueId()}`;
      const createResp = await page.request.post(`/api/dynamic/${MODEL_CODE}/create`, {
        data: {
          e2et_order_title: title,
          e2et_order_type: 'normal',
          e2et_order_status: 'draft',
          e2et_order_urgent: false,
        },
      });
      const createBody = await expectApiJson(createResp, 'create column settings test record');
      expect(createBody.code).toBe('0');
    });
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
    await expect(getPanelButton(panel, 'Select All')).toBeVisible();
    await expect(getPanelButton(panel, 'Deselect All')).toBeVisible();

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

  test('CS-002: Hide column → auto-creates SavedView → column removed from table @critical', async ({
    page,
  }) => {
    const { headersBefore, targetFieldCode, targetLabel, savedView } =
      await hideFirstVisibleColumnAndSave(page);
    expect(savedView?.scope).toBe('personal');
    expect(savedView?.name).toBe('Default View');
    expect(savedView?.modelCode).toBe(MODEL_CODE);
    expect(savedView?.pageKey).toBe(SAVED_VIEW_PAGE_KEY);
    expect(headersContainLabel(headersBefore, targetLabel)).toBe(true);
    const savedTargetColumn = savedView?.viewConfig?.columns?.find(
      (c: any) => c.fieldCode === targetFieldCode,
    );
    expect(savedTargetColumn?.visible).toBe(false);

    // Wait for table re-render with new column config — reload to ensure fresh state
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('thead th').first().waitFor({ state: 'visible', timeout: 10_000 });

    // Verify column is hidden — check headers no longer contain the label
    const headersAfter = await page.locator('thead th').allTextContents();
    if (targetLabel) {
      const isInHeaders = headersContainLabel(headersAfter, targetLabel);
      expect(isInHeaders, `Column "${targetLabel}" should be hidden after unchecking and saving`).toBe(
        false,
      );
    }
  });

  test('CS-003: Auto-created SavedView has correct structure in backend', async ({ page }) => {
    const myView =
      (await findImplicitPersonalColumnView(page)) ??
      (await hideFirstVisibleColumnAndSave(page)).savedView;
    expect(myView).toBeTruthy();
    expect(myView.name).toBe('Default View');
    expect(myView.scope).toBe('personal');
    expect(myView.modelCode).toBe(MODEL_CODE);
    expect(myView.pageKey).toBe(SAVED_VIEW_PAGE_KEY);
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
    await getPanelButton(panel, 'Select All').click();

    // All checkboxes should now be checked
    const checkboxes = panel.locator('input[type="checkbox"]');
    const total = await checkboxes.count();
    for (let i = 0; i < total; i++) {
      await expect(checkboxes.nth(i)).toBeChecked();
    }

    // Save (may create or update a SavedView depending on prior suite state)
    const saveResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/views') &&
        ['POST', 'PUT'].includes(r.request().method()),
      { timeout: 5000 },
    ).catch(() => null);
    await getSaveBtn(panel).click();
    const resp = await saveResp;
    if (resp) expect(resp.ok()).toBe(true);

    await expect(panel).not.toBeVisible({ timeout: 3000 });
  });

  test('CS-006: "Deselect All" unchecks all columns', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Click "Deselect All"
    await getPanelButton(panel, 'Deselect All').click();

    // All checkboxes should be unchecked
    const checkboxes = panel.locator('input[type="checkbox"]');
    const total = await checkboxes.count();
    for (let i = 0; i < total; i++) {
      await expect(checkboxes.nth(i)).not.toBeChecked();
    }

    // Restore via "Select All" before saving (don't want 0 columns)
    await getPanelButton(panel, 'Select All').click();
    for (let i = 0; i < total; i++) {
      await expect(checkboxes.nth(i)).toBeChecked();
    }

    const saveResp = page.waitForResponse(
      (r) =>
        r.url().includes('/api/views') &&
        ['POST', 'PUT'].includes(r.request().method()),
      { timeout: 5000 },
    ).catch(() => null);
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
    const resp = await saveResp;
    await expect(panel).not.toBeVisible({ timeout: 3000 });

    // Verify order values in backend
    const myView = await getSavedViewBySaveResponse(page, resp);
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
    const resp = await saveResp;
    await expect(panel).not.toBeVisible({ timeout: 3000 });

    // Verify in backend
    const myView = await getSavedViewBySaveResponse(page, resp);
    expect(myView).toBeTruthy();

    const colWithWidth = myView.viewConfig?.columns?.find((c: any) => c.width === 200);
    expect(colWithWidth).toBeTruthy();
  });

  test('CS-009: Cancel button closes panel without saving', async ({ page }) => {
    await navigateToOrderList(page);

    const panel = await openColumnSettingsPanel(page);

    // Make a change — deselect all
    await getPanelButton(panel, 'Deselect All').click();

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
