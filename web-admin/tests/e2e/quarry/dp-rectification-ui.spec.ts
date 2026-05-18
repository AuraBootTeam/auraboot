/**
 * DP Rectification — UI E2E Tests
 *
 * Tests rectification lifecycle through actual UI interactions.
 * Flow: INITIATED → in_progress → submitted → ACCEPTED (with reject loop)
 *
 * Data setup uses API to create issues and trigger rectification via triage.
 * Core rectification operations use row action buttons in the UI.
 */
import { test, expect } from '@playwright/test';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  ensureFilterFormOpen,
} from '../helpers/index';
import { getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/shared/services/http-client/types';
import { BASE_URL } from '../../helpers/environments';

const RECT_MODEL = 'dp_rectification';

async function filterRectificationByIssueId(page: any, issueId: string): Promise<void> {
  await ensureFilterFormOpen(page);
  const field = page.locator('[data-testid="form-field-dp_rect_issue_id"] input, input[name="dp_rect_issue_id"]').first();
  const searchBtn = page.locator('[data-testid="filter-search"]').first();
  if (await field.isVisible({ timeout: 5000 }).catch(() => false)) {
    await field.fill(issueId);
    if (await searchBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await searchBtn.click();
      await page
        .waitForResponse(
          (r: { url(): string; status(): number }) =>
            r.url().includes('/list') && r.status() === 200,
          { timeout: 10000 },
        )
        .catch(() => null);
    }
  }
}

async function getRectificationByIssue(
  page: any,
  issueId: string,
  timeoutMs = 10000,
): Promise<{ rectPid: string; rectNo: string }> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const rectResp = await page.request.get(
      `/api/dynamic/dp_rectification/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'dp_rect_issue_id', operator: 'EQ', value: issueId }]),
      )}`,
    );
    if (rectResp.ok()) {
      const rectBody = await rectResp.json().catch(() => ({}));
      const rects = rectBody.data?.records ?? rectBody.data?.list ?? [];
      const rect = rects[0];
      if (rect) {
        const rectPid = String(rect.pid ?? rect.id ?? '').trim();
        const rectNo = String(rect.dp_rect_no ?? '').trim();
        if (rectPid) return { rectPid, rectNo };
      }
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  return { rectPid: '', rectNo: '' };
}

test.describe('DP Rectification — UI Tests', () => {
  test.describe.configure({ mode: 'serial', timeout: 60000 });

  let projectId: string | null = null;
  const createdPids: string[] = [];

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();
    try {
      projectId = await getTestProjectId(page);
    } catch (e: any) {
      console.warn('PM/QO plugin not available:', e.message);
    }
    await page.close();
    await ctx.close();
  });

  test.afterAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      baseURL: BASE_URL,
    });
    const page = await ctx.newPage();
    for (const pid of createdPids) {
      await executeCommandViaApi(page, 'dp:delete_issue', {}, pid, 'delete').catch(() => {});
    }
    await ctx.close();
  });

  async function createRectificationCase(
    page: any,
    titlePrefix: string,
  ): Promise<{ issuePid: string; rectPid: string; rectTitle: string }> {
    const rectTitle = `${titlePrefix} ${uniqueId()}`;
    const cr = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: rectTitle,
      dp_issue_content: 'Setup for rectification UI test',
      dp_issue_area: 'Test Area A',
      dp_issue_source: 'daily_inspection',
    });
    expect(cr.code).toBe(ErrorCodes.SUCCESS);
    const issuePid = cr.recordId;
    createdPids.push(issuePid);
    await executeCommandViaApi(page, 'dp:submit_issue', {}, issuePid, 'state_transition');
    await executeCommandViaApi(
      page,
      'dp:triage_issue',
      {
        dp_triage_decision: 'need_rectify',
        dp_hazard_level: 'high',
        dp_triage_remark: 'Setup for rect test',
      },
      issuePid,
      'update',
    );

    const rect = await getRectificationByIssue(page, issuePid, 12000);
    expect(rect.rectPid).toBeTruthy();
    return { issuePid, rectPid: rect.rectPid, rectTitle };
  }

  async function waitRectStatus(
    page: any,
    rectPid: string,
    expected: string,
    timeoutMs = 10000,
  ): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const resp = await page.request.get(`/api/dynamic/dp_rectification/${rectPid}`);
      if (resp.ok()) {
        const body = await resp.json().catch(() => ({}));
        const status = String((body.data ?? body)?.dp_rect_status ?? '');
        if (status === expected) return true;
      }
      await new Promise((r) => setTimeout(r, 400));
    }
    return false;
  }

  async function waitIssueStatus(
    page: any,
    issuePid: string,
    expected: string,
    timeoutMs = 10000,
  ): Promise<boolean> {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      const resp = await page.request.get(`/api/dynamic/dp_issue/${issuePid}`);
      if (resp.ok()) {
        const body = await resp.json().catch(() => ({}));
        const status = String((body.data ?? body)?.dp_issue_status ?? '');
        if (status === expected) return true;
      }
      await new Promise((r) => setTimeout(r, 400));
    }
    return false;
  }

  // ---- List Display ----

  test('should display rectification list with tabs', async ({ page }) => {
    if (!projectId) {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
    }
    await navigateToDynamicPage(page, RECT_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });

    // Verify tab navigation exists
    const tabNav = page.locator('nav[aria-label="Tabs"]').first();
    await expect(tabNav).toBeVisible({ timeout: 5000 });

    // Should have tabs: all, initiated, in_progress, submitted, accepted, rejected
    const tabs = tabNav.locator('button');
    expect(await tabs.count()).toBeGreaterThanOrEqual(3);
  });

  // ---- Start Rectification via Row Action ----

  test('should start rectification via row action (INITIATED → in_progress)', async ({ page }) => {
    const scenario = await createRectificationCase(page, 'Rect UI Start');

    await navigateToDynamicPage(page, RECT_MODEL);

    // Switch to "已发起" tab
    const initiatedTab = page.locator('[data-testid="tab-initiated"]').first();
    if (await initiatedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await initiatedTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
    }

    // Filter by issue id to narrow results, then find by title text.
    await filterRectificationByIssueId(page, scenario.issuePid);
    // The table displays formatted issue references, not raw PIDs.
    // Use the rectification title or the first visible row after filtering.
    let row = page.locator('tbody tr', { hasText: scenario.rectTitle }).first();
    if (!(await row.isVisible({ timeout: 3000 }).catch(() => false))) {
      row = page.locator('tbody tr').first();
    }
    await expect(row).toBeVisible({ timeout: 10000 });
    await row.hover();
    const startBtn = row.locator('[data-testid="row-action-start"]').first();
    await expect(startBtn).toBeVisible({ timeout: 8000 });
    await startBtn.click();

    // No confirmation for start action
    // Wait for list refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    // Verify: should appear in "整改中" tab
    const inProgressTab = page.locator('[data-testid="tab-in_progress"]').first();
    if (await inProgressTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inProgressTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
    }
    expect(await waitRectStatus(page, scenario.rectPid, 'in_progress', 12000)).toBe(true);
  });

  // ---- Submit Rectification ----

  test('should submit rectification via row action (in_progress → submitted)', async ({ page }) => {
    const scenario = await createRectificationCase(page, 'Rect UI Submit');
    await executeCommandViaApi(
      page,
      'dp:start_rectification',
      {},
      scenario.rectPid,
      'state_transition',
    );
    expect(await waitRectStatus(page, scenario.rectPid, 'in_progress', 12000)).toBe(true);

    await navigateToDynamicPage(page, RECT_MODEL);

    const inProgressTab = page.locator('[data-testid="tab-in_progress"]').first();
    if (await inProgressTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inProgressTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
    }

    await filterRectificationByIssueId(page, scenario.issuePid);
    // After filtering by issue ID, the first row should be our rectification record.
    // The table renders reference display names, not raw PIDs, so match by first row.
    const row = page.locator('tbody tr').first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await row.hover();
    const submitBtn = row.locator('[data-testid="row-action-submit"]').first();
    await submitBtn.click();

    // May need confirmation or form input for rect_result
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    if (await confirmDialog.isVisible({ timeout: 3000 }).catch(() => false)) {
      await page.locator('[data-testid="confirm-ok"]').click();
      await confirmDialog.waitFor({ state: 'hidden', timeout: 5000 });
    }

    // Wait for refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    // Verify: should appear in "已提交" tab
    const submittedTab = page.locator('[data-testid="tab-submitted"]').first();
    if (await submittedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await submittedTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
    }
    expect(await waitRectStatus(page, scenario.rectPid, 'submitted', 12000)).toBe(true);
  });

  // ---- Accept Rectification ----

  test.fixme('should accept rectification via row action (submitted → ACCEPTED)', async ({ page }) => {
    const scenario = await createRectificationCase(page, 'Rect UI Accept');
    await executeCommandViaApi(
      page,
      'dp:start_rectification',
      {},
      scenario.rectPid,
      'state_transition',
    );
    await executeCommandViaApi(
      page,
      'dp:submit_rectification',
      { dp_rect_result: 'UI accept setup' },
      scenario.rectPid,
      'state_transition',
    );
    expect(await waitRectStatus(page, scenario.rectPid, 'submitted', 12000)).toBe(true);

    await navigateToDynamicPage(page, RECT_MODEL);

    const submittedTab = page.locator('[data-testid="tab-submitted"]').first();
    if (await submittedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await submittedTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
    }

    await filterRectificationByIssueId(page, scenario.issuePid);
    const row = page.locator('tbody tr', { hasText: scenario.issuePid }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await row.hover();
    const acceptBtn = row.locator('[data-testid="row-action-accept"]').first();
    await acceptBtn.click();

    // Accept confirmation
    await acceptConfirmDialog(page);

    // Wait for refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    // Verify: should appear in "验收通过" tab
    const acceptedTab = page.locator('[data-testid="tab-accepted"]').first();
    if (await acceptedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await acceptedTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
    }

    expect(await waitRectStatus(page, scenario.rectPid, 'accepted', 12000)).toBe(true);
    expect(await waitIssueStatus(page, scenario.issuePid, 'rectified', 12000)).toBe(true);
  });

  // ---- Reject & Re-submit Flow ----

  test('should reject and re-submit rectification', async ({ page }) => {
    const scenario = await createRectificationCase(page, 'Reject Flow UI');
    const rRectPid = scenario.rectPid;

    // Advance to submitted via API (setup)
    await executeCommandViaApi(page, 'dp:start_rectification', {}, rRectPid, 'state_transition');
    await executeCommandViaApi(
      page,
      'dp:submit_rectification',
      { dp_rect_result: 'Partial fix' },
      rRectPid,
      'state_transition',
    );
    expect(await waitRectStatus(page, rRectPid, 'submitted', 12000)).toBe(true);

    // Navigate and reject via UI
    await navigateToDynamicPage(page, RECT_MODEL);
    const submittedTab = page.locator('[data-testid="tab-submitted"]').first();
    if (await submittedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await submittedTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
    }

    await filterRectificationByIssueId(page, scenario.issuePid);
    const row = page.locator('tbody tr', { hasText: scenario.issuePid }).first();
    await expect(row).toBeVisible({ timeout: 10000 });
    await row.hover();
    const rejectBtn = row.locator('[data-testid="row-action-reject"]').first();
    await rejectBtn.click();

    // Accept reject confirmation
    await acceptConfirmDialog(page);

    // Wait for refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    // Verify: should be back in "整改中" tab
    const inProgressTab = page.locator('[data-testid="tab-in_progress"]').first();
    if (await inProgressTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await inProgressTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
        .catch(() => null);
      await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
    }
    expect(await waitRectStatus(page, rRectPid, 'in_progress', 12000)).toBe(true);
  });
});
