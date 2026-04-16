import { test, expect } from '../../fixtures';
import {
  uniqueId,
  dateOffsetStr,
  executeCommandViaApi,
} from '../helpers';

/**
 * Workflow Demo E2E — SLA escalation
 *
 * Scenario: submit leave request → do not approve within 30s
 *           → SLA timer fires → escalation notification
 *
 * Requires BPM process to actually start (SmartEngine deployment).
 * Skips if process instance not created after submit.
 *
 * Coverage: D9 (SLA-driven state change), D14 (notification feedback)
 */

const UID = uniqueId('WD4');
const LEAVE_REASON = `E2E SLA test ${UID}`;
const START_DATE = dateOffsetStr(20);
const END_DATE = dateOffsetStr(21);
const ADMIN_USER_ID = '302959828878364672';

test.describe('Workflow Demo — SLA escalation', () => {
  test.describe.configure({ mode: 'serial' });

  let leaveRequestPid: string;
  let processInstanceId: string | null = null;

  // Create and submit leave request via API
  test('WD4-001 Create and submit leave request', async ({ page }) => {
    test.setTimeout(30_000);

    // Create draft
    const createResult = await executeCommandViaApi(
      page,
      'wd:create_leave_request',
      {
        wd_req_applicant: ADMIN_USER_ID,
        wd_req_type: 'sick',
        wd_req_start_date: START_DATE,
        wd_req_end_date: END_DATE,
        wd_req_days: 2,
        wd_req_reason: LEAVE_REASON,
      },
      undefined,
      'create',
    );
    leaveRequestPid = createResult?.recordId;
    expect(leaveRequestPid, 'draft created').toBeTruthy();

    // Submit
    const submitResp = await page.request.post(
      '/api/meta/commands/execute/wd:submit_leave_request',
      {
        data: {
          targetRecordId: leaveRequestPid,
          operationType: 'UPDATE',
          payload: {
            wd_req_type: 'sick',
            wd_req_days: 2,
            wd_req_attachments: [],
          },
        },
      },
    );
    expect(submitResp.status()).toBe(200);

    // Check if process instance was created
    const recordResp = await page.request.get(
      `/api/dynamic/wd_leave_request_list/list?pageNum=1&pageSize=1&sortField=created_at&sortOrder=desc`,
    );
    const recordBody = await recordResp.json();
    const record = recordBody?.data?.records?.[0];
    processInstanceId = record?.wd_req_process_instance ?? null;
  });

  // Wait for SLA deadline (30s) + check for escalation
  test('WD4-002 SLA escalation triggers after 30s', async ({ page }) => {
    test.setTimeout(60_000);

    if (!processInstanceId) {
      test.skip(true, 'BPM process not started — SmartEngine deployment missing, SLA cannot fire');
      return;
    }

    // Poll inbox/notifications for sla_escalated event (deadline=PT30S)
    const deadline = Date.now() + 45_000;
    let escalationFound = false;

    while (Date.now() < deadline) {
      const inboxResp = await page.request.get(
        '/api/inbox?pageNum=1&pageSize=50',
      );
      const inboxBody = await inboxResp.json();
      const items = inboxBody?.data?.records ?? [];

      for (const item of items) {
        const title = (item.title ?? '').toLowerCase();
        const subtitle = (item.subtitle ?? '').toLowerCase();
        const cardData = item.cardData ?? {};
        if (
          title.includes('sla') || title.includes('escalat') || title.includes('升级') ||
          subtitle.includes('sla') ||
          cardData.eventCode === 'sla_escalated' || cardData.eventCode === 'sla_warning'
        ) {
          escalationFound = true;
          break;
        }
      }

      if (escalationFound) break;

      // Also check notifications
      const notifResp = await page.request.get('/api/notifications?pageNum=1&pageSize=20');
      const notifBody = await notifResp.json();
      for (const n of (notifBody?.data?.records ?? [])) {
        const content = JSON.stringify(n).toLowerCase();
        if (content.includes('sla') || content.includes('escalat') || content.includes('升级')) {
          escalationFound = true;
          break;
        }
      }

      if (escalationFound) break;

      await page.waitForTimeout(3_000);
    }

    expect(escalationFound, 'SLA escalation notification should appear within 45s').toBeTruthy();
  });

  // Cleanup: approve task if still pending
  test('WD4-003 Approve pending task after SLA', async ({ page }) => {
    test.setTimeout(15_000);

    if (!processInstanceId) {
      test.skip(true, 'BPM process not started — no task to approve');
      return;
    }

    const inboxResp = await page.request.get(
      '/api/inbox?status=pending&itemType=approval&pageNum=1&pageSize=20',
    );
    const inboxBody = await inboxResp.json();
    const items = inboxBody?.data?.records ?? [];
    const pendingApproval = items.find(
      (item: Record<string, unknown>) =>
        (item.cardData as Record<string, unknown>)?.processKey
          ?.toString()
          .includes('wd_leave_approval'),
    );

    if (pendingApproval) {
      const approveResp = await page.request.post(
        `/api/inbox/${pendingApproval.id}/approval-action`,
        { data: { action: 'approve', comment: `E2E approved after SLA ${UID}` } },
      );
      expect(approveResp.status()).toBe(200);
    }
  });
});
