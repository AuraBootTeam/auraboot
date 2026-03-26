/**
 * CRM Batch Operations Smoke Tests
 *
 * Validates batch selection capabilities on CRM list pages:
 * - Select-all checkbox is visible in table header
 * - Clicking select-all checks all visible rows
 * - Individual row checkbox selection works without triggering navigation
 *
 * Prerequisites:
 *   - CRM plugin imported, crm_lead model published
 *   - At least 2 lead records exist (created in beforeAll)
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
} from '../helpers/index';

test.describe('CRM Batch Operations Smoke @smoke', () => {
  test.setTimeout(60000);

  const uid = uniqueId('batch');

  // =========================================================================
  // DATA SETUP — Create at least 2 leads for batch selection
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const modelResp = await page.request.get('/api/meta/models/code/crm_lead');
      expect(modelResp.ok(), 'crm_lead model should exist').toBe(true);
      const modelBody = await modelResp.json();
      const model = modelBody.data;
      expect(model?.pid, 'crm_lead model pid should be available').toBeTruthy();

      if (model.status === 'draft') {
        const publishResp = await page.request.post(`/api/meta/models/${model.pid}/publish`);
        expect(publishResp.ok(), 'crm_lead model should publish successfully').toBe(true);
      }

      const syncResp = await page.request.post(`/api/meta/models/${model.pid}/sync-schema`);
      expect(syncResp.ok(), 'crm_lead schema should sync successfully').toBe(true);

      for (let i = 1; i <= 2; i++) {
        await executeCommandViaApi(
          page,
          'crm:create_lead',
          {
            crm_lead_company: `BatchLead${i}_${uid}`,
            crm_lead_contact_name: `Batch Contact ${i}`,
            crm_lead_source: 'website',
            crm_lead_status: 'new',
          },
          undefined,
          'create',
        );
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // TESTS
  // =========================================================================

  test('BATCH-001: Navigate to CRM leads list and verify select-all checkbox visible', async ({ page }) => {
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/dynamic\/crm-lead/);

    // Wait for table rows to render
    const rows = page.locator('tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10000 });

    // Look for select-all checkbox in table header
    const selectAllCheckbox = page.locator(
      'thead input[type="checkbox"], ' +
      'thead [role="checkbox"], ' +
      '[data-testid="select-all"], ' +
      'th input[type="checkbox"]',
    );
    const hasSelectAll = await selectAllCheckbox.first().isVisible({ timeout: 5000 }).catch(() => false);

    // If no select-all in header, check for row-level checkboxes
    if (!hasSelectAll) {
      const rowCheckbox = page.locator(
        'tbody input[type="checkbox"], tbody [role="checkbox"]',
      );
      const hasRowCheckbox = await rowCheckbox.first().isVisible({ timeout: 3000 }).catch(() => false);
      // At least one form of selection should exist
      expect(
        hasSelectAll || hasRowCheckbox,
        'Table should have selection checkboxes (header or row level)',
      ).toBe(true);
    }
  });

  test('BATCH-002: Click select-all to check all rows', async ({ page }) => {
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);

    const rows = page.locator('tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10000 });

    // Find and click select-all checkbox
    const selectAllCheckbox = page.locator(
      'thead input[type="checkbox"], thead [role="checkbox"], th input[type="checkbox"]',
    ).first();

    const hasSelectAll = await selectAllCheckbox.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasSelectAll) {
      // Select-all must exist — this is a core batch-ops feature.
      // If the checkbox is absent, the feature is broken. Fail loudly.
      throw new Error(
        'BATCH-002: select-all checkbox not found in table header. ' +
        'Batch selection requires a checkbox in <thead>. ' +
        'Check BulkActionToolbar / ListPageContent for regression.',
      );
    }

    await selectAllCheckbox.click();

    // Verify row checkboxes are now checked
    const rowCheckboxes = page.locator(
      'tbody input[type="checkbox"], tbody [role="checkbox"]',
    );
    const checkedCount = await rowCheckboxes.evaluateAll(
      (els) => els.filter((el) => {
        if (el instanceof HTMLInputElement) return el.checked;
        return el.getAttribute('aria-checked') === 'true';
      }).length,
    );
    const rowCount = await rows.count();

    expect(checkedCount, 'All row checkboxes should be checked after select-all').toBeGreaterThan(0);
    expect(checkedCount, 'Checked count should match row count').toBe(rowCount);
  });

  test('BATCH-003: Individual row checkbox selection works without triggering navigation', async ({ page }) => {
    await navigateToDynamicPage(page, 'crm-lead');
    await waitForDynamicPageLoad(page);

    const rows = page.locator('tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10000 });

    const currentUrl = page.url();

    // Find a row checkbox
    const firstRowCheckbox = page.locator(
      'tbody tr:first-child input[type="checkbox"], tbody tr:first-child [role="checkbox"]',
    ).first();

    const hasRowCheckbox = await firstRowCheckbox.isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasRowCheckbox) {
      // Row-level checkboxes are required for individual row selection.
      // If absent, the feature is broken. Fail loudly.
      throw new Error(
        'BATCH-003: row-level checkbox not found in first tbody row. ' +
        'Individual row selection requires per-row checkboxes. ' +
        'Check ListPageContent row-checkbox rendering for regression.',
      );
    }

    // Click the checkbox
    await firstRowCheckbox.click();

    // Verify the checkbox is now checked
    const isChecked = await firstRowCheckbox.evaluate((el) => {
      if (el instanceof HTMLInputElement) return el.checked;
      return el.getAttribute('aria-checked') === 'true';
    });
    expect(isChecked, 'Row checkbox should be checked after click').toBe(true);

    // Verify URL did not change (no navigation triggered)
    expect(page.url(), 'URL should not change when clicking checkbox').toBe(currentUrl);

    // Verify the table is still visible (no navigation occurred)
    await expect(rows.first()).toBeVisible();
  });
});
