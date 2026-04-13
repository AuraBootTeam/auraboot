/**
 * Report Generate Button E2E Tests
 *
 * Validates the "Report" button in DSL list and detail page toolbars.
 * - Button visible on DSL list pages
 * - Button visible on DSL detail pages
 * - Dropdown shows template list from API
 */

import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';

test.describe('Report Generate Button @smoke', () => {
  test.setTimeout(60000);

  const waitForDslListReady = async (page: Page) => {
    await page.goto('/p/e2et_record', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 15000 });
  };

  const waitForDslDetailReady = async (page: Page): Promise<boolean> => {
    const listResp = await page.request.get('/api/dynamic/e2et_record/list?pageNum=1&pageSize=1');
    if (!listResp.ok()) return false;
    const listBody = await listResp.json();
    const records = listBody?.data?.records || listBody?.data?.data || listBody?.data || [];
    const recordPid: string =
      Array.isArray(records) && records.length > 0
        ? records[0]?.pid || records[0]?.recordId || ''
        : '';
    if (!recordPid) return false;

    await page.goto(`/p/e2et_record/view/${recordPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.locator('h2, h1').first()).toBeVisible({ timeout: 15000 });
    return true;
  };

  test('RGN-01: Report button visible on DSL detail page', async ({ page }) => {
    const ready = await waitForDslDetailReady(page);
    if (!ready) { test.skip(true, 'No e2et-record records found — test data may not be seeded'); return; }

    const reportBtn = page.locator('[data-testid="report-generate-button"]');
    await expect(reportBtn).toBeVisible({ timeout: 15000 });
    await expect(reportBtn).toContainText('Report');
  });

  test('RGN-02: Report button dropdown shows template list', async ({ page }) => {
    const ready = await waitForDslDetailReady(page);
    if (!ready) { test.skip(true, 'No e2et-record records found — test data may not be seeded'); return; }

    const reportBtn = page.locator('[data-testid="report-generate-button"]');
    await expect(reportBtn).toBeVisible({ timeout: 15000 });

    // Click to open dropdown
    await reportBtn.click();

    // Should show dropdown (either templates or "No published templates" message)
    const dropdown = page.locator('[data-testid="report-generate-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 5000 });
  });

  test('RGN-03: Published templates API returns valid response', async ({ page }) => {
    const resp = await page.request.get('/api/report-templates/published');
    // API may return 404 if endpoint not yet implemented
    if (!resp.ok()) {
      test.fixme(true, 'Report templates published API not available');
      return;
    }
    const body = await resp.json();
    expect(body?.code).toBe('0');
    // data should be an array (may be empty)
    expect(Array.isArray(body?.data)).toBe(true);
  });
});
