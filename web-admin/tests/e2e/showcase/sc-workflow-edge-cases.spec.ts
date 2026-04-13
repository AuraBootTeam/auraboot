/**
 * Workflow Showcase — sc_request Edge Cases & Invalid Transitions
 *
 * Tests error paths: invalid state transitions, operations on wrong statuses,
 * and boundary conditions for the request workflow state machine.
 *
 * All tests use API commands since these verify backend validation rules
 * that directly affect UI button visibility and error feedback.
 *
 * State machine: draft -> submitted -> [on_hold <-> submitted] -> cancelled
 *                                                               -> archived
 *
 * Available commands: sc:create_request, sc:update_request, sc:delete_request,
 *   sc:submit_request, sc:hold_request, sc:resume_request, sc:cancel_request,
 *   sc:archive_request
 *
 * Dimensions covered:
 * D10 Invalid Transitions — operations rejected on wrong status
 * D9  State Transitions   — hold/resume round-trip via API
 *
 * @since 1.0.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId, executeCommandViaApi } from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const UID = uniqueId('EDGE');

// Record PIDs populated in beforeAll
let draftPid: string;
let submittedPid: string;
let cancelledPid: string;

test.describe('Workflow Showcase — sc_request Edge Cases', () => {
  // sc_* models and commands are all in draft status (not published).
  // sc:create_request fails with "Command is not published". Showcase plugin needs republishing.
  test.fixme(true, 'Showcase plugin sc_* models/commands not published — reimport needed');

  test.use({ storageState: 'tests/storage/admin.json' });
  test.setTimeout(60_000);

  // =========================================================================
  // beforeAll: Create records in various states via API
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Get current user PID
      const meResp = await page.request.get('/api/auth/me');
      const meBody = await meResp.json();
      const userPid = (meBody as any)?.data?.user?.pid ?? '';

      // 1. Create a draft record (stays draft)
      const draftResult = await executeCommandViaApi(
        page,
        'sc:create_request',
        {
          sc_req_title: `Draft Edge ${UID}`,
          sc_req_priority: 'low',
          sc_req_category: 'general',
          sc_req_requester: userPid,
        },
        undefined,
        'create',
      );
      draftPid = draftResult.recordId;
      expect(draftPid, 'Should create draft record').toBeTruthy();

      // 2. Create a submitted record (draft -> submitted)
      const subResult = await executeCommandViaApi(
        page,
        'sc:create_request',
        {
          sc_req_title: `Submitted Edge ${UID}`,
          sc_req_priority: 'medium',
          sc_req_category: 'technical',
          sc_req_requester: userPid,
        },
        undefined,
        'create',
      );
      submittedPid = subResult.recordId;
      expect(submittedPid, 'Should create record for submit').toBeTruthy();

      // Submit it
      const submitResult = await executeCommandViaApi(
        page,
        'sc:submit_request',
        {},
        submittedPid,
      );
      expect(submitResult.code).toBe('0');

      // 3. Create a cancelled record (draft -> submitted -> cancelled)
      const cancelResult = await executeCommandViaApi(
        page,
        'sc:create_request',
        {
          sc_req_title: `Cancelled Edge ${UID}`,
          sc_req_priority: 'high',
          sc_req_category: 'general',
          sc_req_requester: userPid,
        },
        undefined,
        'create',
      );
      cancelledPid = cancelResult.recordId;
      expect(cancelledPid, 'Should create record for cancel').toBeTruthy();

      // Submit then cancel it
      await executeCommandViaApi(page, 'sc:submit_request', {}, cancelledPid);
      const cancelCmd = await executeCommandViaApi(
        page,
        'sc:cancel_request',
        {},
        cancelledPid,
      );
      expect(cancelCmd.code).toBe('0');

      // Verify final states via list API
      const verifyResp = await page.request.get(
        `/api/dynamic/sc_request/list?pageNum=1&pageSize=20&keyword=${encodeURIComponent(UID)}`,
      );
      const verifyBody = await verifyResp.json();
      const records = (verifyBody as any)?.data?.records ?? [];
      const draft = records.find((r: any) => r.pid === draftPid);
      const submitted = records.find((r: any) => r.pid === submittedPid);
      const cancelled = records.find((r: any) => r.pid === cancelledPid);
      expect(draft?.sc_req_status, 'Draft should be draft').toBe('draft');
      expect(submitted?.sc_req_status, 'Submitted should be submitted').toBe('submitted');
      expect(cancelled?.sc_req_status, 'Cancelled should be cancelled').toBe('cancelled');
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // D10: Cannot delete a non-draft (submitted) record
  // =========================================================================
  test('EDGE-001 — Cannot delete a submitted request via API', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'sc:delete_request',
      {},
      submittedPid,
      undefined,
      { allowHttpError: true },
    );
    // Command should return an error code (not '0')
    expect(
      result.code,
      'Delete on submitted record should fail',
    ).not.toBe('0');
  });

  // =========================================================================
  // D10: Cannot update a non-draft (submitted) record
  // =========================================================================
  test('EDGE-002 — Cannot update a submitted request via API', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'sc:update_request',
      { sc_req_title: `Should-not-update ${UID}` },
      submittedPid,
      'update',
      { allowHttpError: true },
    );
    // Command should return an error code
    expect(
      result.code,
      'Update on submitted record should fail',
    ).not.toBe('0');

    // Verify title was NOT changed
    const verifyResp = await page.request.get(
      `/api/dynamic/sc_request/${submittedPid}`,
    );
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_req_title).not.toContain('Should-not-update');
  });

  // =========================================================================
  // D9: Hold a submitted request (submitted -> on_hold)
  // =========================================================================
  test('EDGE-003 — Hold request: submitted -> on_hold', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'sc:hold_request',
      {},
      submittedPid,
    );
    expect(result.code, 'Hold command should succeed').toBe('0');

    // Verify status changed
    const verifyResp = await page.request.get(
      `/api/dynamic/sc_request/${submittedPid}`,
    );
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_req_status).toBe('on_hold');
  });

  // =========================================================================
  // D9: Resume a held request (on_hold -> submitted)
  // =========================================================================
  test('EDGE-004 — Resume request: on_hold -> submitted', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'sc:resume_request',
      {},
      submittedPid,
    );
    expect(result.code, 'Resume command should succeed').toBe('0');

    // Verify status changed back
    const verifyResp = await page.request.get(
      `/api/dynamic/sc_request/${submittedPid}`,
    );
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_req_status).toBe('submitted');
  });

  // =========================================================================
  // D10: Cannot hold a cancelled request
  // =========================================================================
  test('EDGE-005 — Cannot hold a cancelled request', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'sc:hold_request',
      {},
      cancelledPid,
      undefined,
      { allowHttpError: true },
    );
    expect(
      result.code,
      'Hold on cancelled record should fail',
    ).not.toBe('0');

    // Verify status unchanged
    const verifyResp = await page.request.get(
      `/api/dynamic/sc_request/${cancelledPid}`,
    );
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_req_status).toBe('cancelled');
  });

  // =========================================================================
  // D10: Cannot submit a cancelled request
  // =========================================================================
  test('EDGE-006 — Cannot submit a cancelled request', async ({ page }) => {
    const result = await executeCommandViaApi(
      page,
      'sc:submit_request',
      {},
      cancelledPid,
      undefined,
      { allowHttpError: true },
    );
    expect(
      result.code,
      'Submit on cancelled record should fail',
    ).not.toBe('0');

    // Verify status unchanged
    const verifyResp = await page.request.get(
      `/api/dynamic/sc_request/${cancelledPid}`,
    );
    const verifyBody = await verifyResp.json();
    expect((verifyBody as any)?.data?.sc_req_status).toBe('cancelled');
  });
});
