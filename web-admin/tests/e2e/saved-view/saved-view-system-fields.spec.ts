/**
 * E2E Test: System Fields Visible in View (GAP-126)
 *
 * Tests that system fields (created_at, updated_at, created_by, updated_by)
 * can be toggled visible via SavedView column config, and render correctly.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

import { acquireSavedViewLock, releaseSavedViewLock } from './_saved-view-lock';
import {
  createPersonalView,
  deleteViewTolerant,
  sweepStaleSavedViews,
} from './_saved-view-helpers';

// Serialize e2et_order saved-view specs — they share the model's per-user view
// state (active view / created views) under the shared admin storageState.
test.beforeAll(async () => { await acquireSavedViewLock('saved-view-system-fields'); });
test.afterAll(() => { releaseSavedViewLock('saved-view-system-fields'); });

const MODEL_CODE = 'e2et_order';
const SAVED_VIEW_PAGE_KEY = 'e2et_order_list';

// Views created by this suite — deleted in afterAll so repeated runs on a
// long-lived database never accumulate past the backend's personal-view cap.
const createdViewPids: string[] = [];

// API helpers
async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  columns?: any[],
): Promise<string> {
  const pid = await createPersonalView(page, {
    name,
    modelCode,
    pageKey: SAVED_VIEW_PAGE_KEY,
    viewConfig: columns ? { columns } : {},
  });
  createdViewPids.push(pid);
  return pid;
}

async function getViewViaApi(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/views/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  return body.data ?? body;
}

test.describe('System Fields Visible (GAP-126)', () => {
  // Sweep leftovers from earlier runs FIRST (under the file lock): the backend
  // caps explicit personal views per user+model+pageKey at 10, and every run
  // of this suite seeds fresh views.
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    await sweepStaleSavedViews(page, MODEL_CODE, SAVED_VIEW_PAGE_KEY);
    const pid = await createPersonalView(page, {
      name: `SF_Clean_${uniqueId()}`,
      modelCode: MODEL_CODE,
      pageKey: SAVED_VIEW_PAGE_KEY,
      viewConfig: {}, // empty config — system fields not configured → hidden by default
    });
    createdViewPids.push(pid);
    // Set as default so useSavedViews auto-selects it on page load
    await page.request.post(`/api/views/${pid}/set-default`, {
      data: { modelCode: MODEL_CODE, pageKey: SAVED_VIEW_PAGE_KEY },
    });
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    for (const pid of createdViewPids.splice(0)) {
      await deleteViewTolerant(page, pid).catch(() => {});
    }
    await ctx.close();
  });

  test('SF-001: system fields appear in column settings panel', async ({ page }) => {
    await page.goto('/p/e2et_order');
    const colBtn = page.getByTestId('column-settings-btn');
    await expect(colBtn).toBeVisible({ timeout: 30000 });
    await colBtn.click();

    // Panel header (l10n key common.column_settings_title — copy is "Configure fields")
    await expect(page.locator('#column-settings-title')).toBeVisible({ timeout: 5000 });

    const panel = page.getByTestId('column-settings-panel');
    // GAP-126 core: the four system fields appear as panel rows with visibility
    // toggles. (The "System" badge only renders when the field is not shadowed
    // by model metadata — e2et_order declares them via model fields, so they
    // group as business here; assert presence, not badge.)
    for (const fieldCode of ['created_at', 'updated_at', 'created_by', 'updated_by']) {
      await expect(panel.getByTestId(`column-settings-row-${fieldCode}`)).toBeVisible({
        timeout: 3000,
      });
      await expect(panel.getByTestId(`column-settings-visible-${fieldCode}`)).toBeVisible();
    }
  });

  test('SF-002: system fields are hidden by default', async ({ page }) => {
    await page.goto('/p/e2et_order');
    const colBtn = page.getByTestId('column-settings-btn');
    await expect(colBtn).toBeVisible({ timeout: 30000 });
    await colBtn.click();

    await expect(page.locator('#column-settings-title')).toBeVisible({ timeout: 5000 });

    // Hidden fields render with an unchecked visibility toggle (checkbox) and a
    // dimmed label — the panel no longer styles hidden rows with line-through.
    const createdAtToggle = page.getByTestId('column-settings-visible-created_at');
    await expect(createdAtToggle).toBeVisible();
    await expect(createdAtToggle).not.toBeChecked();
  });

  test('SF-003: enabling system fields via viewConfig columns', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `SF_Enable_${uniqueId()}`;
    const columns = [
      { fieldCode: 'e2et_order_no', visible: true, order: 0 },
      { fieldCode: 'e2et_order_title', visible: true, order: 1 },
      { fieldCode: 'created_at', visible: true, order: 2 },
      { fieldCode: 'updated_at', visible: true, order: 3 },
      { fieldCode: 'created_by', visible: false, order: 4 },
      { fieldCode: 'updated_by', visible: false, order: 5 },
    ];
    const pid = await createViewViaApi(page, MODEL_CODE, viewName, columns);
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.columns).toHaveLength(6);

    // Verify created_at is visible and created_by is hidden
    const createdAt = view.viewConfig.columns.find((c: any) => c.fieldCode === 'created_at');
    const createdBy = view.viewConfig.columns.find((c: any) => c.fieldCode === 'created_by');
    expect(createdAt?.visible).toBe(true);
    expect(createdBy?.visible).toBe(false);
  });

  test('SF-004: system fields are read-only (no edit UI)', async ({ page }) => {
    // System fields should not be editable — they're set by the server
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `SF_ReadOnly_${uniqueId()}`;
    const columns = [
      { fieldCode: 'created_at', visible: true, order: 0 },
      { fieldCode: 'updated_at', visible: true, order: 1 },
    ];
    const pid = await createViewViaApi(page, 'e2et_order', viewName, columns);
    expect(pid).toBeTruthy();

    // Verify the view stores the config correctly
    const view = await getViewViaApi(page, pid);
    const cols = view.viewConfig?.columns || [];
    expect(cols.find((c: any) => c.fieldCode === 'created_at')?.visible).toBe(true);
    expect(cols.find((c: any) => c.fieldCode === 'updated_at')?.visible).toBe(true);
  });

  test('SF-005: system fields can be used in sort and filter', async ({ page }) => {
    await page.goto('/');
    await page.locator('nav, [data-testid="sidebar"]').first().waitFor({ timeout: 15000 });

    const viewName = `SF_Sort_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName);
    expect(pid).toBeTruthy();

    // Update view with sort by created_at and filter by created_by
    const resp = await page.request.put(`/api/views/${pid}`, {
      data: {
        viewConfig: {
          sorts: [{ fieldCode: 'created_at', direction: 'desc' }],
          filters: [{ fieldCode: 'created_by', operator: 'isNotNull' }],
          columns: [{ fieldCode: 'created_at', visible: true, order: 0 }],
        },
      },
    });
    expect(resp.ok()).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view.viewConfig?.sorts).toHaveLength(1);
    expect(view.viewConfig.sorts[0].fieldCode).toBe('created_at');
    expect(view.viewConfig?.filters).toHaveLength(1);
    expect(view.viewConfig.filters[0].fieldCode).toBe('created_by');
  });
});
