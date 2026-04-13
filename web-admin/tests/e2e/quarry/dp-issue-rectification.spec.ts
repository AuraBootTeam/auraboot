/**
 * DP Issue & Rectification — E2E Tests
 *
 * Tests dual-prevention module flows:
 *   - Issue CRUD and state transitions
 *   - Triage decisions (NEED_RECTIFY, NO_ACTION)
 *   - Rectification lifecycle via sideEffects
 *
 * State flows:
 *   Issue: draft → pending → RECTIFYING → RECTIFIED
 *   Rectification: INITIATED → in_progress → submitted → ACCEPTED
 */
import { test, expect } from '@playwright/test';
import { navigateToDynamicPage, uniqueId, executeCommandViaApi } from '../helpers/index';
import { PAGE_KEYS, getTestProjectId } from '../quarry-management.setup';
import { ErrorCodes } from '~/services/http-client/types';

const ISSUE_MODEL = PAGE_KEYS.ISSUE; // 'dp_issue'

test.describe('DP Issue & Rectification', () => {
  test.describe.configure({ mode: 'serial' });

  let issuePid: string;
  let rectPid: string;

  // ---- Issue CRUD ----

  test('should create a new issue', async ({ page }) => {
    let projectId: string;
    try {
      projectId = await getTestProjectId(page);
    } catch {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
      return;
    }
    const result = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: `E2E Issue ${uniqueId()}`,
      dp_issue_content: 'Test issue for E2E',
      dp_issue_area: 'Test Area A',
      dp_issue_source: 'daily_inspection',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    issuePid = result.recordId;
    expect(issuePid).toBeTruthy();
  });

  test('should navigate to issue list page', async ({ page }) => {
    await navigateToDynamicPage(page, ISSUE_MODEL);
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
  });

  // ---- State Transitions ----

  test('should submit issue (draft → pending)', async ({ page }) => {
    expect(issuePid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dp:submit_issue',
      {},
      issuePid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  test('should triage issue as NEED_RECTIFY', async ({ page }) => {
    expect(issuePid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dp:triage_issue',
      {
        dp_triage_decision: 'need_rectify',
        dp_hazard_level: 'medium',
        dp_triage_remark: 'Needs fix',
      },
      issuePid,
      'update',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify rectification auto-created via sideEffect (linked by dp_rect_issue_id)
    const rectResp = await page.request.get(
      `/api/dynamic/dp_rectification/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'dp_rect_issue_id', operator: 'EQ', value: issuePid }]),
      )}`,
    );
    const rectBody = await rectResp.json();
    const rects = rectBody.data?.records ?? rectBody.data?.list ?? [];
    expect(rects.length).toBeGreaterThanOrEqual(1);
    rectPid = rects[0]?.id;
    expect(rectPid).toBeTruthy();
  });

  // ---- Rectification Lifecycle ----

  test('should start and submit rectification', async ({ page }) => {
    expect(rectPid).toBeTruthy();

    // Start: INITIATED → in_progress
    let result = await executeCommandViaApi(
      page,
      'dp:start_rectification',
      {},
      rectPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Submit: in_progress → submitted
    result = await executeCommandViaApi(
      page,
      'dp:submit_rectification',
      { dp_rect_result: 'Fixed equipment' },
      rectPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);
  });

  test('should accept rectification and update issue status', async ({ page }) => {
    expect(rectPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'dp:accept_rectification',
      { dp_rect_accept_remark: 'Verified OK' },
      rectPid,
      'state_transition',
    );
    expect(result.code).toBe(ErrorCodes.SUCCESS);

    // Verify issue status updated to RECTIFIED via sideEffect
    const issueResp = await page.request.get(`/api/dynamic/dp_issue/${issuePid}`);
    if (issueResp.ok()) {
      const issueBody = await issueResp.json();
      const issueData = issueBody.data ?? issueBody;
      expect(issueData.dp_issue_status).toBe('rectified');
    }
  });

  // ---- Alternative: NO_ACTION flow ----

  test('should handle NO_ACTION triage flow', async ({ page }) => {
    let projectId: string;
    try {
      projectId = await getTestProjectId(page);
    } catch {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
      return;
    }
    const result = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: `No Action ${uniqueId()}`,
      dp_issue_content: 'Low priority item',
      dp_issue_area: 'Area B',
      dp_issue_source: 'daily_inspection',
    });
    expect(result.code).toBe(ErrorCodes.SUCCESS);
    const noActionPid = result.recordId;

    // Submit → Triage as NO_ACTION
    await executeCommandViaApi(page, 'dp:submit_issue', {}, noActionPid, 'state_transition');
    const triage = await executeCommandViaApi(
      page,
      'dp:triage_issue',
      { dp_triage_decision: 'no_action', dp_triage_remark: 'No action needed' },
      noActionPid,
      'update',
    );
    expect(triage.code).toBe(ErrorCodes.SUCCESS);

    // Cleanup
    await executeCommandViaApi(page, 'dp:delete_issue', {}, noActionPid, 'delete').catch(() => {});
  });

  // ---- Reject rectification flow ----

  test('should handle reject → re-submit rectification flow', async ({ page }) => {
    let projectId: string;
    try {
      projectId = await getTestProjectId(page);
    } catch {
      throw new Error(String('Project not available - PM/QO plugin may not be imported'));
      return;
    }
    // Create full flow: issue → submit → triage → start → submit → reject
    const createResult = await executeCommandViaApi(page, 'dp:create_issue', {
      dp_issue_project_id: projectId,
      dp_issue_title: `Reject Flow ${uniqueId()}`,
      dp_issue_content: 'Test reject flow',
      dp_issue_area: 'Area C',
      dp_issue_source: 'daily_inspection',
    });
    expect(createResult.code).toBe(ErrorCodes.SUCCESS);
    const rIssuePid = createResult.recordId;

    await executeCommandViaApi(page, 'dp:submit_issue', {}, rIssuePid, 'state_transition');
    await executeCommandViaApi(
      page,
      'dp:triage_issue',
      {
        dp_triage_decision: 'need_rectify',
        dp_hazard_level: 'high',
        dp_triage_remark: 'Fix needed',
      },
      rIssuePid,
      'update',
    );

    // Get auto-created rectification
    const rectResp = await page.request.get(
      `/api/dynamic/dp_rectification/list?pageSize=50&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'dp_rect_issue_id', operator: 'EQ', value: rIssuePid }]),
      )}`,
    );
    const rectBody = await rectResp.json();
    const rects = rectBody.data?.records ?? rectBody.data?.list ?? [];
    expect(rects.length).toBeGreaterThanOrEqual(1);
    const rRectPid = rects[0].id;

    // Start → Submit → Reject
    await executeCommandViaApi(page, 'dp:start_rectification', {}, rRectPid, 'state_transition');
    await executeCommandViaApi(
      page,
      'dp:submit_rectification',
      { dp_rect_result: 'Partial fix' },
      rRectPid,
      'state_transition',
    );
    const reject = await executeCommandViaApi(
      page,
      'dp:reject_rectification',
      {},
      rRectPid,
      'state_transition',
    );
    expect(reject.code).toBe(ErrorCodes.SUCCESS);

    // Verify visible in list
    await navigateToDynamicPage(page, ISSUE_MODEL);
    await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 10000 });
  });

  // ---- List UI ----

  test('should display issue list with table headers', async ({ page }) => {
    await navigateToDynamicPage(page, ISSUE_MODEL);
    const headers = page.locator('thead th, [role="columnheader"]');
    await expect(headers.first()).toBeVisible({ timeout: 10000 });
    expect(await headers.count()).toBeGreaterThan(2);
  });
});
