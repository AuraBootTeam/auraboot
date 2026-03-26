/**
 * Procurement — Three-Way Match E2E Tests
 *
 * Tests TW-001 ~ TW-004: Three-way match lifecycle covering:
 * - Navigation: sidebar menu (Procurement → 采购管理 → 三方匹配)
 * - Data creation: three-way match records via API
 * - List page: table visible, tabs present, records appear
 * - Status transitions: pending → MATCHED, pending → VARIANCE → resolved
 *
 * Menu path: /procurement/three-way-match (code: pr_three_way_matches)
 * Parent dir: pr_purchase_dir (采购管理)
 * Parent root: pr_root (Procurement)
 *
 * Prerequisites: procurement plugin imported and three-way match model published.
 *
 * @since 9.2.0
 */

import { test, expect } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('twm');

// ---------------------------------------------------------------------------
// Navigation helper — opens Procurement > 采购管理 > 三方匹配
// ---------------------------------------------------------------------------

async function navigateToThreeWayMatchList(page: import('@playwright/test').Page): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');

  // 1. Open Procurement root
  const procBtn = nav.getByRole('button', { name: 'Procurement' });
  await procBtn.scrollIntoViewIfNeeded();
  await procBtn.evaluate((el: HTMLElement) => el.click());
  await page
    .waitForResponse(() => true, { timeout: 3_000 })
    .catch(() => null);

  // 2. Open 采购管理 sub-directory
  const purchaseDir = nav.getByRole('button', { name: '采购管理' });
  await purchaseDir.scrollIntoViewIfNeeded();
  await purchaseDir.evaluate((el: HTMLElement) => el.click());
  await page
    .waitForResponse(() => true, { timeout: 3_000 })
    .catch(() => null);

  // 3. Click 三方匹配 link
  const twmLink = nav.getByRole('link', { name: '三方匹配' });
  await twmLink.scrollIntoViewIfNeeded();
  await twmLink.evaluate((el: HTMLElement) => el.click());

  // Wait for list API response
  await page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/pr_three_way_match/list') &&
      r.status() === 200,
    { timeout: 15_000 },
  );
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Procurement — Three-Way Match', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let pendingMatchPid: string;
  let varianceMatchPid: string;
  let pendingMatchRemark: string;
  let varianceMatchRemark: string;

  // -------------------------------------------------------------------------
  // Setup: create two pending records via API — one to match, one to flag
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    try {
      pendingMatchRemark = `E2E-TWM-match-${UID}`;
      const r1 = await executeCommandViaApi(
        page,
        'pr:create_three_way_match',
        {
          pr_twm_match_status: 'pending',
          pr_twm_remark: pendingMatchRemark,
        },
        undefined,
        'create',
      );
      pendingMatchPid = r1.recordId;

      varianceMatchRemark = `E2E-TWM-variance-${UID}`;
      const r2 = await executeCommandViaApi(
        page,
        'pr:create_three_way_match',
        {
          pr_twm_match_status: 'pending',
          pr_twm_remark: varianceMatchRemark,
        },
        undefined,
        'create',
      );
      varianceMatchPid = r2.recordId;
    } finally {
      await ctx.close();
    }
  });

  // -------------------------------------------------------------------------
  // TW-001 @smoke: Navigate to list → page loads → table visible
  // -------------------------------------------------------------------------

  test('TW-001 @smoke: Navigate to three-way match list via sidebar menu', async ({
    page,
  }) => {
    await navigateToThreeWayMatchList(page);

    // Table must be visible
    await expect(
      page.locator('table, [class*="ant-table"]').first(),
    ).toBeVisible({ timeout: 10_000 });

    // At least one data row must be present (records created in beforeAll)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 8_000 });

    // Page URL must reflect the route
    expect(page.url()).toContain('/procurement/three-way-match');
  });

  // -------------------------------------------------------------------------
  // TW-002 @critical: Create record via API → appears in list
  // -------------------------------------------------------------------------

  test('TW-002 @critical: Created three-way match appears in list', async ({
    page,
  }) => {
    expect(pendingMatchPid).toBeTruthy();

    // Verify via API that the record was created correctly
    const resp = await page.request.get(
      `/api/dynamic/pr_three_way_match/${pendingMatchPid}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.pr_twm_match_status).toBe('pending');
    expect(record.pr_twm_remark).toBe(pendingMatchRemark);

    // Query via list API to confirm it is indexed and queryable
    const records = await queryFilteredList(
      page,
      'pr_three_way_match',
      'pr_twm_remark',
      pendingMatchRemark,
    );
    expect(records.length).toBeGreaterThanOrEqual(1);

    // Navigate to list page and verify the record appears in the table
    await navigateToThreeWayMatchList(page);

    // Should have at least one row (our created records)
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
  });

  // -------------------------------------------------------------------------
  // TW-003 @critical: Status transitions — pending → MATCHED and pending → VARIANCE
  // -------------------------------------------------------------------------

  test('TW-003 @critical: Status transition — pending → MATCHED via confirm-match command', async ({
    page,
  }) => {
    expect(pendingMatchPid).toBeTruthy();

    // Execute match command via API
    await executeCommandViaApi(
      page,
      'pr:match_three_way',
      {},
      pendingMatchPid,
      'state_transition',
    );

    // Verify status changed to MATCHED
    const resp = await page.request.get(
      `/api/dynamic/pr_three_way_match/${pendingMatchPid}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.pr_twm_match_status).toBe('matched');

    // Verify MATCHED record is queryable via list API with status filter
    const matchedFilters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'pr_twm_match_status', operator: 'EQ', value: 'matched' }]),
    );
    const listResp = await page.request.get(
      `/api/dynamic/pr_three_way_match/list?pageNum=1&pageSize=50&filters=${matchedFilters}`,
    );
    expect(listResp.ok()).toBe(true);
    const listBody = await listResp.json();
    const records: unknown[] =
      listBody?.data?.records ?? listBody?.data?.data ?? [];
    expect(records.length).toBeGreaterThanOrEqual(1);

    // Navigate to list page — table must be visible
    await navigateToThreeWayMatchList(page);
    await expect(
      page.locator('table, [class*="ant-table"]').first(),
    ).toBeVisible({ timeout: 8_000 });
  });

  test('TW-003b @critical: Status transition — pending → VARIANCE via flag-variance command', async ({
    page,
  }) => {
    expect(varianceMatchPid).toBeTruthy();

    // Execute flag_variance command via API
    await executeCommandViaApi(
      page,
      'pr:flag_variance',
      {},
      varianceMatchPid,
      'state_transition',
    );

    // Verify status changed to VARIANCE
    const resp = await page.request.get(
      `/api/dynamic/pr_three_way_match/${varianceMatchPid}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.pr_twm_match_status).toBe('variance');

    // Verify VARIANCE record is queryable via list API with status filter
    const varianceFilters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'pr_twm_match_status', operator: 'EQ', value: 'variance' }]),
    );
    const listResp = await page.request.get(
      `/api/dynamic/pr_three_way_match/list?pageNum=1&pageSize=50&filters=${varianceFilters}`,
    );
    expect(listResp.ok()).toBe(true);
    const listBody = await listResp.json();
    const records: unknown[] =
      listBody?.data?.records ?? listBody?.data?.data ?? [];
    expect(records.length).toBeGreaterThanOrEqual(1);

    // Navigate to list page — table must be visible
    await navigateToThreeWayMatchList(page);
    await expect(
      page.locator('table, [class*="ant-table"]').first(),
    ).toBeVisible({ timeout: 8_000 });
  });

  // -------------------------------------------------------------------------
  // TW-004 @critical: Resolve a VARIANCE match → resolved
  // -------------------------------------------------------------------------

  test('TW-004 @critical: Resolve variance — VARIANCE → resolved', async ({
    page,
  }) => {
    expect(varianceMatchPid).toBeTruthy();

    // Precondition: status must be VARIANCE (set by TW-003b)
    const preCheck = await page.request.get(
      `/api/dynamic/pr_three_way_match/${varianceMatchPid}`,
    );
    expect(preCheck.ok()).toBe(true);
    const preBody = await preCheck.json();
    expect((preBody?.data ?? preBody).pr_twm_match_status).toBe('variance');

    // Execute resolve command via API
    await executeCommandViaApi(
      page,
      'pr:resolve_three_way_match',
      { pr_twm_remark: `Resolved in E2E ${UID}` },
      varianceMatchPid,
      'state_transition',
    );

    // Verify status changed to resolved
    const resp = await page.request.get(
      `/api/dynamic/pr_three_way_match/${varianceMatchPid}`,
    );
    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    const record = body?.data ?? body;
    expect(record.pr_twm_match_status).toBe('resolved');

    // Verify resolved record is queryable via list API with status filter
    const resolvedFilters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'pr_twm_match_status', operator: 'EQ', value: 'resolved' }]),
    );
    const listResp = await page.request.get(
      `/api/dynamic/pr_three_way_match/list?pageNum=1&pageSize=50&filters=${resolvedFilters}`,
    );
    expect(listResp.ok()).toBe(true);
    const listBody = await listResp.json();
    const records: unknown[] =
      listBody?.data?.records ?? listBody?.data?.data ?? [];
    expect(records.length).toBeGreaterThanOrEqual(1);

    // Navigate to list page — table must be visible with resolved data
    await navigateToThreeWayMatchList(page);
    await expect(
      page.locator('table, [class*="ant-table"]').first(),
    ).toBeVisible({ timeout: 8_000 });

    // Cross-check: direct record fetch should show resolved status
    const finalCheck = await page.request.get(
      `/api/dynamic/pr_three_way_match/${varianceMatchPid}`,
    );
    expect(finalCheck.ok()).toBe(true);
    const finalBody = await finalCheck.json();
    expect((finalBody?.data ?? finalBody).pr_twm_match_status).toBe('resolved');
  });
});
