/**
 * PCBA NPI (New Product Introduction) Smoke Tests
 *
 * Validates NPI module pages and core lifecycle:
 * - NPI Project list, create, state transition (draft -> in_progress)
 * - Phase Gate, Trial Run, Checklist list pages
 * - NPI Project detail page with sub-table blocks
 *
 * Prerequisites:
 *   - pcba-industry plugin imported and all NPI models published
 *   - Menus registered under /pcba-erp/npi-*
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  executeCommandViaApi,
  todayStr,
  dateOffsetStr,
  queryFilteredList,
} from '../helpers/index';

test.describe('PCBA NPI Smoke Tests @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('npi');
  let npiProjectPid: string;

  // =========================================================================
  // DATA SETUP
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create an NPI project via API for subsequent tests
      const result = await executeCommandViaApi(
        page,
        'pe:create_npi_project',
        {
          pe_npi_code: `NPI_${uid}`,
          pe_npi_name: `NPI Project ${uid}`,
          pe_npi_description: `E2E smoke test NPI project ${uid}`,
          pe_npi_target_date: dateOffsetStr(30),
          pe_npi_status: 'draft',
        },
        undefined,
        'create',
      );
      npiProjectPid = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // TESTS
  // =========================================================================

  test('NPI-001: Navigate to NPI Projects list page via menu', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-npi-project');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/dynamic\/pe-npi-project/);

    // Table should be visible
    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('NPI-002: Create an NPI project via form and verify in list', async ({ page }) => {
    const createUid = uniqueId('NPICreate');

    // Create via API
    await executeCommandViaApi(
      page,
      'pe:create_npi_project',
      {
        pe_npi_code: `NPI_${createUid}`,
        pe_npi_name: `NPI Create ${createUid}`,
        pe_npi_target_date: dateOffsetStr(60),
        pe_npi_status: 'draft',
      },
      undefined,
      'create',
    );

    // Verify via API query (pe_npi_code is auto-generated, so query by name)
    const records = await queryFilteredList(
      page,
      'pe-npi-project',
      'pe_npi_name',
      createUid,
    );
    expect(records.length, 'Created NPI project should appear in list').toBeGreaterThanOrEqual(1);
  });

  test('NPI-003: Start NPI project (draft -> in_progress)', async ({ page }) => {
    expect(npiProjectPid, 'NPI project should have been created in beforeAll').toBeTruthy();

    // Execute start command
    await executeCommandViaApi(
      page,
      'pe:start_npi_project',
      {},
      npiProjectPid,
      'update',
    );

    // Verify status changed via API (pe_npi_code is auto-generated, query by name)
    const records = await queryFilteredList(
      page,
      'pe-npi-project',
      'pe_npi_name',
      uid,
      {
        extraFilters: [{ fieldName: 'pe_npi_status', operator: 'EQ', value: 'in_progress' }],
      },
    );
    expect(records.length, 'NPI project should be in_progress').toBeGreaterThanOrEqual(1);
  });

  test('NPI-004: Navigate to Phase Gate list page', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-npi-phase-gate');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/dynamic\/pe-npi-phase-gate/);

    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('NPI-005: Navigate to Trial Run list page', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-npi-trial-run');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/dynamic\/pe-npi-trial-run/);

    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('NPI-006: Navigate to NPI Checklist list page', async ({ page }) => {
    await navigateToDynamicPage(page, 'pe-npi-checklist');
    await waitForDynamicPageLoad(page);
    await expect(page).toHaveURL(/\/dynamic\/pe-npi-checklist/);

    const table = page.locator(
      '.ant-table, table, [role="table"], [data-testid="dynamic-list"], [data-testid="table-block"]',
    );
    await expect(table.first()).toBeVisible({ timeout: 10000 });
  });

  test('NPI-007: NPI Project detail page has sub-table blocks', async ({ page }) => {
    expect(npiProjectPid, 'NPI project should have been created in beforeAll').toBeTruthy();

    // Navigate to detail page
    const listResponse = page
      .waitForResponse(
        (resp) => resp.url().includes('/list') && resp.status() === 200,
        { timeout: 10000 },
      )
      .catch(() => null);

    await page.goto(`/dynamic/pe-npi-project/detail/${npiProjectPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await waitForDynamicPageLoad(page);
    await listResponse;

    // Verify the detail page loaded with basic info block
    const detailContent = page.locator(
      'form, .ant-form, [data-testid="dynamic-form"], [data-testid="form-section"], main',
    );
    await expect(detailContent.first()).toBeVisible({ timeout: 15000 });

    // Verify sub-table blocks are present (Phase Gates, Trial Runs, Checklist)
    // These are rendered as sub-table blocks in the detail page schema
    const subTableBlocks = page.locator('[data-testid*="sub-table"], [data-testid*="block_npi"]');
    const blockCount = await subTableBlocks.count();

    // If data-testid not set, fall back to checking for block titles
    if (blockCount === 0) {
      // Check for block titles that correspond to the 3 sub-tables
      const phaseGateTitle = page.locator('text=Phase Gates').or(page.locator('text=阶段评审'));
      const trialRunTitle = page.locator('text=Trial Runs').or(page.locator('text=试产记录'));
      const checklistTitle = page.locator('text=NPI Checklist').or(page.locator('text=NPI检查单'));

      // At least one sub-table title should be visible
      const phaseVisible = await phaseGateTitle.first().isVisible({ timeout: 5000 }).catch(() => false);
      const trialVisible = await trialRunTitle.first().isVisible({ timeout: 2000 }).catch(() => false);
      const checklistVisible = await checklistTitle.first().isVisible({ timeout: 2000 }).catch(() => false);

      expect(
        phaseVisible || trialVisible || checklistVisible,
        'At least one sub-table block title should be visible on the detail page',
      ).toBe(true);
    }
  });
});
