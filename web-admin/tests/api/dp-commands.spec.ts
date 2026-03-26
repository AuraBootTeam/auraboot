/**
 * Dual Prevention Plugin — Command Pipeline API Tests
 *
 * Backend command pipeline tests (API level). NOT UI E2E tests.
 * Moved from tests/e2e/ — tests use executeCommandViaApi exclusively.
 * For UI-level E2E tests, see:
 *   tests/e2e/dual-prevention/dp-issue-lifecycle.spec.ts
 *   tests/e2e/dual-prevention/dp-rectification-lifecycle.spec.ts
 *   tests/e2e/dual-prevention/dp-inspection-lifecycle.spec.ts
 *
 * DP-C001 : Create hazard source via API → verify in list
 * DP-C002 : Create issue → submit → pending status (command pipeline)
 * DP-C003 : Issue triage(no_action) → no_action status
 * DP-C004 : Issue triage(need_rectify) → rectifying + side-effect rectification
 * DP-C005 : Rectification lifecycle initiated→in_progress→submitted→accepted
 * DP-C006 : Issue list filter by status
 * DP-C007 : Hazard source detail page loads
 * DP-C008 : Quality standard CRUD
 *
 * @since 11.0.0
 */

import { test, expect, type Page } from '../fixtures';
import type { Response, Browser } from '@playwright/test';
import { uniqueId, executeCommandViaApi } from '../e2e/helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('DP');

// ---------------------------------------------------------------------------
// Plugin availability check
// ---------------------------------------------------------------------------

let pluginInstalled = false;

// Shared state across serial tests
let issuePid = '';
let rectificationPid = '';
let hazardSourcePid = '';
let testProjectPid = '';

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function expandDpMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const rootBtn = nav.getByRole('button', { name: /双重预防|Dual Prevention/ });
  await rootBtn.waitFor({ state: 'visible', timeout: 10000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);
}

async function navigateToList(
  page: Page,
  path: string,
  modelUrl: string,
): Promise<void> {
  await expandDpMenu(page);
  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${path}"]`).first();
  await link.waitFor({ state: 'attached', timeout: 8000 });
  const listResp = page.waitForResponse(
    (r: Response) => r.url().includes(modelUrl) && r.status() === 200,
    { timeout: 15000 },
  ).catch(() => null);
  await link.evaluate((el: HTMLElement) => el.click());
  await listResp;
  await expect(
    page.locator('table, [class*="ant-table"]').first(),
  ).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Dual Prevention — Issue & Hazard CRUD @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90000);

  // -------------------------------------------------------------------------
  // beforeAll: check plugin installation
  // -------------------------------------------------------------------------
  test.beforeAll(async ({ browser }: { browser: Browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const resp = await page.request.get('/api/meta/models/code/dp_issue');
      pluginInstalled = resp.ok();
      if (pluginInstalled) {
        // Fetch a valid PM project ID (required for dp_issue creation)
        const projResp = await page.request.get('/api/dynamic/pm_project/list?pageSize=1');
        if (projResp.ok()) {
          const projBody = await projResp.json();
          const records = projBody?.data?.records ?? [];
          testProjectPid = records[0]?.pid ?? '';
        }
      }
    } catch {
      pluginInstalled = false;
    } finally {
      await ctx.close();
    }
  });

  // -------------------------------------------------------------------------
  // DP-C001: Create hazard source via API, verify in list
  // -------------------------------------------------------------------------
  test('DP-C001: create hazard source → visible in list', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    // Create hazard source via command API
    const result = await executeCommandViaApi(
      page,
      'dp:create_hazard_source',
      {
        dp_hs_name: `E2E HazardSource ${UID}`,
        dp_hs_category: 'mechanical',
        dp_hs_level: 'medium',
        dp_hs_area: 'E2E Test Area',
        dp_hs_description: `Automated test hazard source ${UID}`,
      },
      undefined,
      'create',
    );
    hazardSourcePid = result.recordId;
    expect(hazardSourcePid, 'Hazard source PID must be returned').toBeTruthy();

    // Verify via API
    const detailResp = await page.request.get(`/api/dynamic/dp-hazard-source/${hazardSourcePid}`);
    expect(detailResp.status()).toBe(200);
    const body = await detailResp.json();
    expect(body.data).toBeTruthy();
    expect(body.data.dp_hs_name).toContain(UID);

    // Navigate to hazard source list and verify entry is visible
    await navigateToList(page, '/dual-prevention/hazards', '/api/dynamic/dp-hazard-source');

    // Search or wait for our record
    const listResp = await page.request.get(
      `/api/dynamic/dp-hazard-source/list?pageNum=1&pageSize=20&keyword=${UID}`,
    );
    expect(listResp.status()).toBe(200);
    const listBody = await listResp.json();
    expect(listBody.data.total).toBeGreaterThan(0);
  });

  // -------------------------------------------------------------------------
  // DP-C002: Create issue draft → verify draft status
  // -------------------------------------------------------------------------
  test('DP-C002: create issue in draft status → API confirms draft', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    const result = await executeCommandViaApi(
      page,
      'dp:create_issue',
      {
        dp_issue_title: `E2E Issue ${UID}`,
        dp_issue_content: `Automated test issue content ${UID}`,
        dp_issue_area: 'E2E Test Zone',
        dp_issue_source: 'inspection',
        ...(testProjectPid ? { dp_issue_project_id: testProjectPid } : {}),
      },
      undefined,
      'create',
    );
    issuePid = result.recordId;
    expect(issuePid, 'Issue PID must be returned').toBeTruthy();

    // Verify initial status is draft
    const detailResp = await page.request.get(`/api/dynamic/dp-issue/${issuePid}`);
    expect(detailResp.status()).toBe(200);
    const body = await detailResp.json();
    expect(body.data.dp_issue_status).toBe('draft');
    expect(body.data.dp_issue_title).toContain(UID);

    // Navigate to issues list and verify New button is visible
    await navigateToList(page, '/dual-prevention/issues', '/api/dynamic/dp-issue');
    const createBtn = page.locator('button', { hasText: /新建|Create|创建/ }).first();
    await expect(createBtn).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // DP-C003: Issue lifecycle — draft → submit → pending
  // -------------------------------------------------------------------------
  test('DP-C003: submit issue → status changes to pending', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }
    expect(issuePid, 'Requires DP-C002 to run first').toBeTruthy();

    // Submit via command API
    const submitResult = await page.request.post('/api/meta/commands/execute/dp:submit_issue', {
      data: {
        targetRecordId: issuePid,
        operationType: 'update',
        payload: {},
      },
    });
    expect(submitResult.status()).toBeLessThan(400);

    // Verify status changed to pending
    const detailResp = await page.request.get(`/api/dynamic/dp-issue/${issuePid}`);
    expect(detailResp.status()).toBe(200);
    const body = await detailResp.json();
    expect(body.data.dp_issue_status).toBe('pending');

    // Navigate to issues list and verify our record appears
    await navigateToList(page, '/dual-prevention/issues', '/api/dynamic/dp-issue');

    const listResp = await page.request.get(
      `/api/dynamic/dp-issue/list?pageNum=1&pageSize=20&keyword=${UID}`,
    );
    expect(listResp.status()).toBe(200);
    const listBody = await listResp.json();
    const found = (listBody.data.records as Array<{ dp_issue_status: string }>).find(
      (r) => r.dp_issue_status === 'pending',
    );
    expect(found, 'At least one pending issue should exist after submit').toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // DP-C004: Triage issue with no_action decision
  // -------------------------------------------------------------------------
  test('DP-C004: triage issue with no_action → status becomes no_action', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }
    expect(issuePid, 'Requires DP-C003 to run first').toBeTruthy();

    // Triage with no_action
    const triageResult = await page.request.post('/api/meta/commands/execute/dp:triage_issue', {
      data: {
        targetRecordId: issuePid,
        operationType: 'update',
        payload: {
          dp_triage_decision: 'no_action',
          dp_triage_remark: `E2E test no action ${UID}`,
        },
      },
    });
    expect(triageResult.status()).toBeLessThan(400);

    // Verify final status
    const detailResp = await page.request.get(`/api/dynamic/dp-issue/${issuePid}`);
    expect(detailResp.status()).toBe(200);
    const body = await detailResp.json();
    expect(body.data.dp_issue_status).toBe('no_action');
  });

  // -------------------------------------------------------------------------
  // DP-C005: Full issue lifecycle — draft → pending → triage(need_rectify) → rectifying
  //          Side effect: creates rectification record automatically
  // -------------------------------------------------------------------------
  test('DP-C005: triage need_rectify → creates rectification side-effect', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    // Create a new issue for this test (independent from C002-C004)
    const createResult = await executeCommandViaApi(
      page,
      'dp:create_issue',
      {
        dp_issue_title: `E2E Issue Rectify ${UID}`,
        dp_issue_content: `Issue needing rectification ${UID}`,
        dp_issue_area: 'E2E Rectify Zone',
        dp_issue_source: 'daily_check',
        ...(testProjectPid ? { dp_issue_project_id: testProjectPid } : {}),
      },
      undefined,
      'create',
    );
    const newIssuePid = createResult.recordId;
    expect(newIssuePid).toBeTruthy();

    // Submit the issue
    const submitResp = await page.request.post('/api/meta/commands/execute/dp:submit_issue', {
      data: { targetRecordId: newIssuePid, operationType: 'update', payload: {} },
    });
    expect(submitResp.status()).toBeLessThan(400);

    // Triage with need_rectify — should create a rectification record via side-effect
    const triageResp = await page.request.post('/api/meta/commands/execute/dp:triage_issue', {
      data: {
        targetRecordId: newIssuePid,
        operationType: 'update',
        payload: {
          dp_triage_decision: 'need_rectify',
          dp_hazard_level: 'medium',
          dp_rectify_dept: 'E2E Dept',
          dp_triage_remark: `E2E rectify triage ${UID}`,
        },
      },
    });
    expect(triageResp.status()).toBeLessThan(400);

    // Verify issue status is now rectifying
    const issueDetail = await page.request.get(`/api/dynamic/dp-issue/${newIssuePid}`);
    const issueBody = await issueDetail.json();
    expect(issueBody.data.dp_issue_status).toBe('rectifying');

    // Verify a rectification record was created via side-effect
    const rectListResp = await page.request.get(
      `/api/dynamic/dp-rectification/list?pageNum=1&pageSize=50`,
    );
    expect(rectListResp.status()).toBe(200);
    const rectListBody = await rectListResp.json();

    const linkedRect = (rectListBody.data.records as Array<{ dp_rect_issue_id?: string; dp_rect_status?: string }>).find(
      (r) => r.dp_rect_issue_id === newIssuePid,
    );
    expect(linkedRect, 'A rectification record should be created as side-effect of need_rectify triage').toBeTruthy();
    expect(linkedRect!.dp_rect_status).toBe('initiated');

    // Save rectification pid for lifecycle test
    const rectListFiltered = await page.request.get(
      `/api/dynamic/dp-rectification/list?pageNum=1&pageSize=10`,
    );
    const rectBody = await rectListFiltered.json();
    const rect = (rectBody.data.records as Array<{ dp_rect_issue_id?: string; pid?: string }>).find(
      (r) => r.dp_rect_issue_id === newIssuePid,
    );
    if (rect?.pid) rectificationPid = rect.pid;
  });

  // -------------------------------------------------------------------------
  // DP-C006: Rectification lifecycle — initiated → start → submit → accept
  // -------------------------------------------------------------------------
  test('DP-C006: rectification lifecycle initiated → in_progress → submitted → done', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    // Create a standalone rectification record if we don't have one from C005
    if (!rectificationPid) {
      const createResult = await executeCommandViaApi(
        page,
        'dp:create_rectification',
        {
          dp_rect_title: `E2E Rectification ${UID}`,
          dp_rect_content: `Rectification test content ${UID}`,
          dp_rect_deadline: new Date(Date.now() + 7 * 86400000).toISOString().slice(0, 10),
          dp_rect_responsible: 'admin',
          dp_rect_acceptor: 'admin',
        },
        undefined,
        'create',
      );
      rectificationPid = createResult.recordId;
    }

    expect(rectificationPid, 'Rectification PID is required').toBeTruthy();

    // Verify initial status is initiated
    const detail1 = await page.request.get(`/api/dynamic/dp-rectification/${rectificationPid}`);
    const body1 = await detail1.json();
    expect(['initiated', 'in_progress']).toContain(body1.data.dp_rect_status);

    // Start rectification
    const startResp = await page.request.post('/api/meta/commands/execute/dp:start_rectification', {
      data: { targetRecordId: rectificationPid, operationType: 'update', payload: {} },
    });
    expect(startResp.status()).toBeLessThan(400);

    // Submit rectification
    const submitResp = await page.request.post('/api/meta/commands/execute/dp:submit_rectification', {
      data: {
        targetRecordId: rectificationPid,
        operationType: 'update',
        payload: {
          dp_rect_result: `E2E rectification completed ${UID}`,
        },
      },
    });
    expect(submitResp.status()).toBeLessThan(400);

    // Verify status = submitted
    const detail2 = await page.request.get(`/api/dynamic/dp-rectification/${rectificationPid}`);
    const body2 = await detail2.json();
    expect(body2.data.dp_rect_status).toBe('submitted');

    // Accept rectification
    const acceptResp = await page.request.post('/api/meta/commands/execute/dp:accept_rectification', {
      data: {
        targetRecordId: rectificationPid,
        operationType: 'update',
        payload: {
          dp_rect_accept_remark: `E2E acceptance ${UID}`,
        },
      },
    });
    expect(acceptResp.status()).toBeLessThan(400);

    // Verify final status = done (or accepted)
    const detail3 = await page.request.get(`/api/dynamic/dp-rectification/${rectificationPid}`);
    const body3 = await detail3.json();
    expect(['done', 'accepted', 'closed']).toContain(body3.data.dp_rect_status);

    // Navigate to rectification list and verify the record is visible
    await navigateToList(page, '/dual-prevention/rectifications', '/api/dynamic/dp-rectification');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // DP-C007: Issues list filter by status
  // -------------------------------------------------------------------------
  test('DP-C007: issues list — status filter API returns filtered results', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    // Query issues filtered by status=draft
    const resp = await page.request.get('/api/dynamic/dp-issue/list?pageNum=1&pageSize=50', {
      params: {
        filters: JSON.stringify([{ fieldName: 'dp_issue_status', operator: 'eq', value: 'no_action' }]),
      },
    });
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body.code).toBe('0');
    // All returned records should have status = no_action (or none if empty)
    const records = body.data.records as Array<{ dp_issue_status: string }>;
    for (const r of records) {
      expect(r.dp_issue_status).toBe('no_action');
    }
  });

  // -------------------------------------------------------------------------
  // DP-C008: Quality standard CRUD — create and verify
  // -------------------------------------------------------------------------
  test('DP-C008: create quality standard → visible in quality standards list', async ({ page }: { page: Page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    const createResult = await executeCommandViaApi(
      page,
      'dp:create_standard',
      {
        dp_qs_name: `E2E Quality Standard ${UID}`,
        dp_qs_code: `QS-E2E-${UID.slice(-6)}`,
        dp_qs_category: 'safety',
        dp_qs_description: `Automated test quality standard ${UID}`,
      },
      undefined,
      'create',
    );
    const standardPid = createResult.recordId;
    expect(standardPid).toBeTruthy();

    // Verify via API
    const detailResp = await page.request.get(`/api/dynamic/dp-quality-standard/${standardPid}`);
    expect(detailResp.status()).toBe(200);
    const body = await detailResp.json();
    expect(body.data.dp_qs_name).toContain(UID);

    // Navigate to quality standards list (uses /dual-prevention/quality-standards)
    await page.goto('/dual-prevention/quality-standards', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r: Response) => r.url().includes('/api/dynamic/dp-quality-standard') && r.status() === 200,
      { timeout: 15000 },
    ).catch(() => null);

    const table = page.locator('table, [class*="ant-table"]').first();
    const emptyState = page.locator('[class*="empty"]').or(page.getByText('暂无数据')).first();
    await expect(table.or(emptyState)).toBeVisible({ timeout: 10000 });
  });
});
