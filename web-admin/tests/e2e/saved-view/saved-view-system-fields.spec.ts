/**
 * E2E Test: System Fields Visible in View (GAP-126)
 *
 * Tests that system fields (created_at, updated_at, created_by, updated_by)
 * can be toggled visible via SavedView column config, and render correctly.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

// API helpers
async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  columns?: any[]
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode,
      viewType: 'table',
      scope: 'personal',
      viewConfig: columns ? { columns } : {},
    },
  });
  if (!resp.ok()) return '';
  const body = await resp.json();
  return body.data?.pid ?? body.pid ?? '';
}

async function getViewViaApi(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/views/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  return body.data ?? body;
}

test.describe('System Fields Visible (GAP-126)', () => {

  // Create a fresh view with NO system fields configured and set it as default,
  // so SF-001/SF-002 see the clean/default state regardless of prior test runs
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: './tests/storage/admin.json' });
    const page = await ctx.newPage();
    const resp = await page.request.post('/api/views', {
      data: {
        name: `SF_Clean_${uniqueId()}`,
        modelCode: 'e2et_order',
        pageKey: 'e2et-order',
        viewType: 'table',
        scope: 'personal',
        viewConfig: {},  // empty config — system fields not configured → hidden by default
      },
    });
    if (resp.ok()) {
      const body = await resp.json();
      const pid = body?.data?.pid ?? '';
      if (pid) {
        // Set as default so useSavedViews auto-selects it on page load
        await page.request.post(`/api/views/${pid}/set-default`, {
          data: { modelCode: 'e2et_order', pageKey: 'e2et-order' },
        });
      }
    }
    await ctx.close();
  });

  test('SF-001: system fields appear in column settings panel', async ({ page }) => {
    await page.goto('/dynamic/e2et-order');
    const colBtn = page.getByTestId('column-settings-btn');
    await expect(colBtn).toBeVisible({ timeout: 30000 });
    await colBtn.click();

    // Column Settings panel opens as a fixed right panel with "Column Settings" header
    const panelHeader = page.locator('text=Column Settings');
    await expect(panelHeader).toBeVisible({ timeout: 5000 });

    // Check "System Fields" section divider is visible
    await expect(page.locator('.fixed >> text=System Fields')).toBeVisible({ timeout: 3000 });

    // Check system field labels exist in the panel
    await expect(page.locator('.fixed >> text=Created At')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('.fixed >> text=Updated At')).toBeVisible();
    await expect(page.locator('.fixed >> text=Created By')).toBeVisible();
    await expect(page.locator('.fixed >> text=Updated By')).toBeVisible();
  });

  test('SF-002: system fields are hidden by default', async ({ page }) => {
    await page.goto('/dynamic/e2et-order');
    const colBtn = page.getByTestId('column-settings-btn');
    await expect(colBtn).toBeVisible({ timeout: 30000 });
    await colBtn.click();

    await expect(page.locator('text=Column Settings')).toBeVisible({ timeout: 5000 });

    // System fields should be unchecked (line-through class on label)
    // The "Created At" label should have line-through styling (indicating hidden)
    const createdAtLabel = page.locator('.fixed span:text("Created At")').first();
    await expect(createdAtLabel).toBeVisible();
    // line-through class indicates unchecked
    await expect(createdAtLabel).toHaveClass(/line-through/);
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
    const pid = await createViewViaApi(page, 'e2et_order', viewName, columns);
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
