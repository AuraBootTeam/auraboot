import { test, expect } from '@playwright/test';
import {
  loginAs,
  createLeaveApplicant,
  setLeaveBalance,
} from '../../helpers/wd-fixtures';
import { BACKEND_URL } from '../../helpers/environments';

test.describe('workflow-demo — leave validation rule', { tag: ['@bpm-regression'] }, () => {
  test('annual leave exceeding balance is rejected before process start', async ({ request }) => {
    const adminToken = await loginAs(request, 'admin@auraboot.com', 'Test2026x');
    const applicant = await createLeaveApplicant(request, adminToken, 'r_rule_annual');
    await setLeaveBalance(request, adminToken, applicant.userId, 2);

    const createResp = await request.post(
      `${BACKEND_URL}/api/meta/commands/execute/wd:create_leave_request`,
      {
        data: {
          payload: {
            wd_req_applicant: applicant.userId,
            wd_req_type: 'annual',
            wd_req_start_date: today(),
            wd_req_start_slot: 'AM',
            wd_req_end_date: addDays(63),
            wd_req_end_slot: 'PM',
            wd_req_days: 64,
            wd_req_reason: 'Rule validation regression: annual leave exceeds balance',
          },
        },
        headers: authHeaders(applicant.token),
      },
    );
    if (!createResp.ok()) {
      throw new Error(`create draft must return 2xx: ${await createResp.text()}`);
    }

    const createBody = await createResp.json();
    expect(createBody?.code, `create draft failed: ${JSON.stringify(createBody)}`).toBe('0');
    const recordId = createBody?.data?.data?.recordId;
    expect(typeof recordId, `recordId missing: ${JSON.stringify(createBody)}`).toBe('string');

    const submitResp = await request.post(
      `${BACKEND_URL}/api/meta/commands/execute/wd:submit_leave_request`,
      {
        data: { targetRecordId: recordId },
        headers: authHeaders(applicant.token),
      },
    );
    const submitBody = await submitResp.json();
    expect(JSON.stringify(submitBody)).toContain('annual_leave_insufficient');
    expect(submitBody?.code).not.toBe('0');

    const detailResp = await request.get(
      `${BACKEND_URL}/api/dynamic/wd_leave_request_detail/${recordId}`,
      { headers: authHeaders(applicant.token) },
    );
    if (!detailResp.ok()) {
      throw new Error(`detail fetch must return 2xx: ${await detailResp.text()}`);
    }
    const detail = (await detailResp.json())?.data as Record<string, unknown>;
    expect(detail.wd_req_status).toBe('draft');
    expect(detail.wd_req_process_instance ?? '').toBe('');
  });

  test('one-step create and submit also runs annual leave validation', async ({ request }) => {
    const adminToken = await loginAs(request, 'admin@auraboot.com', 'Test2026x');
    const applicant = await createLeaveApplicant(request, adminToken, 'r_rule_one_step');
    await setLeaveBalance(request, adminToken, applicant.userId, 1);

    const resp = await request.post(
      `${BACKEND_URL}/api/meta/commands/execute/wd:create_and_submit_leave_request`,
      {
        data: {
          payload: {
            wd_req_applicant: applicant.userId,
            wd_req_type: 'annual',
            wd_req_start_date: today(),
            wd_req_start_slot: 'AM',
            wd_req_end_date: addDays(9),
            wd_req_end_slot: 'PM',
            wd_req_days: 10,
            wd_req_reason: 'Rule validation regression: one-step submit exceeds balance',
          },
        },
        headers: authHeaders(applicant.token),
      },
    );
    const body = await resp.json();
    expect(JSON.stringify(body)).toContain('annual_leave_insufficient');
    expect(body?.code).not.toBe('0');
  });
});

function authHeaders(token: string): Record<string, string> {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`,
  };
}

function today(): string {
  return new Date().toISOString().slice(0, 10);
}

function addDays(days: number): string {
  const date = new Date();
  date.setDate(date.getDate() + days);
  return date.toISOString().slice(0, 10);
}
