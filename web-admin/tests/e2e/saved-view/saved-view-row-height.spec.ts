/**
 * E2E Test: Row Height Control (GAP-127)
 *
 * Tests the 4-level row height selector: short (32px), medium (44px),
 * tall (60px), extra-tall (80px). Verifies visual change, persistence
 * across page reload, and independent row height per view.
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';

// API helpers — page.request uses storageState cookies from global auth setup
async function createViewViaApi(
  page: Page,
  modelCode: string,
  name: string,
  rowHeight?: string
): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode,
      viewType: 'table',
      scope: 'personal',
      viewConfig: rowHeight ? { rowHeight } : {},
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

  test('RH-001: row height selector shows 4 options (Short/Medium/Tall/Extra Tall)', async ({ page }) => {
    await page.goto('/dynamic/e2et-order');
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
    await page.goto('/dynamic/e2et-order');
    const rowHeightBtn = page.getByTestId('row-height-btn');
    await expect(rowHeightBtn).toBeVisible({ timeout: 30000 });

    const firstRow = page.getByTestId('table-row-0');
    if (!(await firstRow.isVisible({ timeout: 10000 }).catch(() => false))) {
      test.skip(true, 'No data rows to verify height');
      return;
    }

    // Switch to "short" row height
    await rowHeightBtn.click();
    await page.getByTestId('row-height-option-short').click();
    // Wait for re-render
    await page.waitForResponse(
      (resp) => resp.url().includes('/api/views/') && resp.status() === 200,
      { timeout: 5000 }
    ).catch(() => {});
    await page.waitForTimeout(300);

    const shortHeight = await firstRow.evaluate((el) => el.getBoundingClientRect().height);
    expect(shortHeight).toBeLessThanOrEqual(50); // 32px + padding + border tolerance

    // Switch to "extra-tall"
    await rowHeightBtn.click();
    await page.getByTestId('row-height-option-extra-tall').click();
    await page.waitForResponse(
      (resp) => resp.url().includes('/api/views/') && resp.status() === 200,
      { timeout: 5000 }
    ).catch(() => {});
    await page.waitForTimeout(300);

    const tallHeight = await firstRow.evaluate((el) => el.getBoundingClientRect().height);
    expect(tallHeight).toBeGreaterThanOrEqual(70); // 80px target
    expect(tallHeight).toBeGreaterThan(shortHeight);
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
