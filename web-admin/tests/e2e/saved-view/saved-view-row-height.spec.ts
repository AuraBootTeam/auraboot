/**
 * E2E Test: Row Height Control (GAP-127)
 *
 * Tests the 4-level row height selector: short (32px), medium (44px),
 * tall (60px), extra-tall (80px). Verifies visual change, persistence
 * across page reload, and independent row height per view.
 */

import { test, expect, type Page } from '@playwright/test';
import { executeCommandViaApi, uniqueId } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { cleanupGeneratedSavedViews } from './helpers';

import { acquireSavedViewLock, releaseSavedViewLock } from './_saved-view-lock';

// Serialize e2et_order saved-view specs — they share the model's per-user view
// state (active view / created views) under the shared admin storageState.
test.beforeAll(async () => { await acquireSavedViewLock('saved-view-row-height'); });
test.afterAll(() => { releaseSavedViewLock('saved-view-row-height'); });

// API helpers — page.request uses storageState cookies from global auth setup
async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  rowHeight?: string,
  recordTitle?: string,
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode,
      pageKey: `${modelCode}_list`,
      viewType: 'table',
      scope: 'personal',
      viewConfig: {
        ...(rowHeight ? { rowHeight } : {}),
        ...(recordTitle ? { filters: [{ fieldCode: 'e2et_order_title', operator: 'eq', value: recordTitle }] } : {}),
      },
    },
  });
  if (!resp.ok()) {
    console.error(`createViewViaApi failed: ${resp.status()} ${await resp.text()}`);
    return '';
  }
  const body = await resp.json();
  return body.data?.pid ?? body.pid ?? '';
}

async function getViewViaApi(page: Page, pid: string): Promise<any> {
  const resp = await page.request.get(`/api/views/${pid}`);
  if (!resp.ok()) return null;
  const body = await resp.json();
  return body.data ?? body;
}

test.describe('Row Height Control (GAP-127)', () => {
  test.beforeEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: 'e2et_order', pageKey: 'e2et_order_list' });
  });

  test.afterEach(async ({ page }) => {
    await cleanupGeneratedSavedViews(page, { modelCode: 'e2et_order', pageKey: 'e2et_order_list' });
  });

  test('RH-001: row height selector shows 4 options (Short/Medium/Tall/Extra Tall)', async ({
    page,
  }) => {
    await page.goto('/p/e2et_order');
    // Wait for table toolbar to render (the row-height button lives there)
    const rowHeightBtn = page.getByTestId('row-height-btn');
    await expect(rowHeightBtn).toBeVisible({ timeout: 30000 });
    await rowHeightBtn.click();

    // Verify all 4 options are visible
    await expect(page.getByTestId('row-height-option-short')).toBeVisible();
    await expect(page.getByTestId('row-height-option-medium')).toBeVisible();
    await expect(page.getByTestId('row-height-option-tall')).toBeVisible();
    await expect(page.getByTestId('row-height-option-extra-tall')).toBeVisible();

    // Verify labels and pixel hints
    await expect(page.getByTestId('row-height-option-short')).toContainText('Short');
    await expect(page.getByTestId('row-height-option-short')).toContainText('32px');
    await expect(page.getByTestId('row-height-option-medium')).toContainText('Medium');
    await expect(page.getByTestId('row-height-option-tall')).toContainText('Tall');
    await expect(page.getByTestId('row-height-option-extra-tall')).toContainText('Extra Tall');
    await expect(page.getByTestId('row-height-option-extra-tall')).toContainText('80px');
  });

  test('RH-002: switching row height changes table row visual height', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const title = uniqueId('RH_Row');
    const recordPid = await order.createViaApi({ e2et_order_title: title });
    expect(recordPid).toBeTruthy();
    let viewPid = '';
    try {
      const viewName = uniqueId('RH_Table');
      viewPid = await createViewViaApi(page, 'e2et_order', viewName, 'medium', title);
      expect(viewPid).toBeTruthy();
      await page.goto(`/p/e2et_order?view=${viewPid}`);
      await expect(page.getByTestId('view-selector-trigger')).toHaveAttribute('data-current-view-name', viewName);
      const rowHeightBtn = page.getByTestId('row-height-btn');
      await expect(rowHeightBtn).toBeVisible({ timeout: 30000 });

      const firstRow = page.getByTestId('table-row-0');
      await expect(firstRow).toBeVisible({ timeout: 10000 });
      await expect(firstRow).toContainText(title);

      // Capture the initial row height so we can poll for the actual visual
      // change (the rowHeight change is propagated through SavedView async via
      // ensureViewAndUpdateConfig, so a fixed waitForTimeout is racy).
      const initialHeight = await firstRow.evaluate((el) => el.getBoundingClientRect().height);

      // Switch to "short" row height
      await rowHeightBtn.click();
      await page.getByTestId('row-height-option-short').click();
      // Poll until the row visibly shrinks (or timeout)
      await expect
        .poll(() => firstRow.evaluate((el) => el.getBoundingClientRect().height), {
          timeout: 10000,
          intervals: [200, 300, 500, 1000],
        })
        .toBeLessThan(initialHeight);

      const shortHeight = await firstRow.evaluate((el) => el.getBoundingClientRect().height);

      // Switch to "extra-tall"
      await rowHeightBtn.click();
      await page.getByTestId('row-height-option-extra-tall').click();
      // Poll until the row visibly grows past short
      await expect
        .poll(() => firstRow.evaluate((el) => el.getBoundingClientRect().height), {
          timeout: 10000,
          intervals: [200, 300, 500, 1000],
        })
        .toBeGreaterThan(shortHeight + 10);

      const tallHeight = await firstRow.evaluate((el) => el.getBoundingClientRect().height);
      expect(tallHeight).toBeGreaterThan(shortHeight);

      await test.info().attach('row-height-measurements', {
        body: Buffer.from(JSON.stringify({ recordPid, viewPid, title, initialHeight, shortHeight, tallHeight })),
        contentType: 'application/json',
      });
    } finally {
      try {
        const record = await order.fetchViaApi(recordPid);
        const version = Number(record.row_version ?? record.rowVersion);
        const deleted = await executeCommandViaApi(page, order.commandCode('delete'), {}, recordPid, 'delete',
          Number.isSafeInteger(version) ? { expectedVersion: version } : undefined);
        expect(deleted.code).toBe('0');
      } finally {
        if (viewPid) {
          const deleted = await page.request.delete(`/api/views/${viewPid}`);
          expect(deleted.ok()).toBe(true);
        }
      }
    }
  });

  test('RH-003: row height persists via API', async ({ page }) => {
    // Navigate first to establish auth context for page.request
    await page.goto('/');
    await page.getByTestId('sidebar').or(page.locator('nav')).first().waitFor({ timeout: 15000 });

    const viewName = `RH_Persist_${uniqueId()}`;
    const pid = await createViewViaApi(page, 'e2et_order', viewName, 'tall');
    expect(pid).toBeTruthy();

    const view = await getViewViaApi(page, pid);
    expect(view).toBeTruthy();
    expect(view.viewConfig?.rowHeight).toBe('tall');
  });

  test('RH-004: different views have independent row heights', async ({ page }) => {
    // Navigate first to establish auth context for page.request
    await page.goto('/');
    await page.getByTestId('sidebar').or(page.locator('nav')).first().waitFor({ timeout: 15000 });

    const viewAName = `RH_Short_${uniqueId()}`;
    const viewBName = `RH_Tall_${uniqueId()}`;

    const pidA = await createViewViaApi(page, 'e2et_order', viewAName, 'short');
    const pidB = await createViewViaApi(page, 'e2et_order', viewBName, 'extra-tall');

    expect(pidA).toBeTruthy();
    expect(pidB).toBeTruthy();

    const viewA = await getViewViaApi(page, pidA);
    const viewB = await getViewViaApi(page, pidB);

    expect(viewA.viewConfig?.rowHeight).toBe('short');
    expect(viewB.viewConfig?.rowHeight).toBe('extra-tall');
  });
});
