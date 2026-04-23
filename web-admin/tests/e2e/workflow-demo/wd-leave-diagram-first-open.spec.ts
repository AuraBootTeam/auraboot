import { test, expect, type APIRequestContext, type Page } from '../../fixtures';
import { loginAs, loginViaUI } from '../../helpers/wd-fixtures';

test.setTimeout(120_000);

function dateOffsetStr(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

async function executeCommand(
  request: APIRequestContext,
  token: string,
  commandCode: string,
  data: Record<string, unknown>,
) {
  const resp = await request.post(`/api/meta/commands/execute/${commandCode}`, {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    data,
  });
  expect(resp.ok(), `${commandCode} must return 2xx`).toBeTruthy();
  const body = await resp.json();
  expect(String(body?.code), `${commandCode} must succeed`).toBe('0');
  return body;
}

async function createSubmittedLeave(
  request: APIRequestContext,
  token: string,
): Promise<{ recordId: string; code: string }> {
  const meResp = await request.get('/api/auth/me', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(meResp.ok()).toBeTruthy();
  const meBody = await meResp.json();
  const userId = String(meBody?.data?.user?.id ?? '');
  expect(userId).toBeTruthy();

  const startDate = dateOffsetStr(7);
  const endDate = dateOffsetStr(8);
  const reason = `diagram first open ${Date.now()}`;

  const createBody = await executeCommand(request, token, 'wd:create_leave_request', {
    payload: {
      wd_req_applicant: userId,
      wd_req_type: 'annual',
      wd_req_start_date: startDate,
      wd_req_start_slot: 'AM',
      wd_req_end_date: endDate,
      wd_req_end_slot: 'PM',
      wd_req_days: 2,
      wd_req_reason: reason,
    },
  });
  const recordId = String(createBody?.data?.data?.recordId ?? '');
  expect(recordId).toBeTruthy();

  await executeCommand(request, token, 'wd:submit_leave_request', {
    targetRecordId: recordId,
    payload: {
      wd_req_applicant: userId,
      wd_req_type: 'annual',
      wd_req_days: 2,
      wd_req_start_slot: 'AM',
      wd_req_end_slot: 'PM',
    },
  });

  const detailResp = await request.get(`/api/dynamic/wd_leave_request_detail/${recordId}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(detailResp.ok()).toBeTruthy();
  const detailBody = await detailResp.json();
  return {
    recordId,
    code: String(detailBody?.data?.wd_req_code ?? ''),
  };
}

async function navigateToLeaveRequestList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const rootBtn = nav.getByRole('button', { name: /请假|Leave Demo/i }).first();
  await expect(rootBtn).toBeVisible({ timeout: 5_000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav.locator('a[href="/p/wd_leave_request"]').first();
  await expect(leafLink).toBeVisible({ timeout: 3_000 });

  const listResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/wd_leave_request') &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 15_000 },
  );

  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResp;
  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

test.describe('workflow-demo — workflow diagram first open', () => {
  test('first opening workflow diagram tab renders at readable scale', async ({
    page,
    request,
  }) => {
    const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
    const submitted = await createSubmittedLeave(request, adminToken);

    await loginViaUI(page, 'admin@example.com', 'Test2026x');
    await navigateToLeaveRequestList(page);

    const row = page.locator('table tbody tr').filter({ hasText: submitted.code }).first();
    await expect(row).toBeVisible({ timeout: 10_000 });

    const detailUrl = new RegExp(`/p/wd_leave_request/view/${submitted.recordId}$`);
    await Promise.all([
      page.waitForURL(detailUrl, { timeout: 15_000 }),
      row.click(),
    ]);

    const workflowTab = page.getByRole('tab', { name: /流程图|Workflow Diagram/i }).first();
    await expect(workflowTab).toBeVisible({ timeout: 10_000 });
    await workflowTab.click();
    await expect(workflowTab).toHaveAttribute('aria-selected', 'true');

    const currentNode = page.locator('[data-testid="bpm-diagram-node-task_manager_approve"]').first();
    await expect(currentNode).toBeVisible({ timeout: 10_000 });

    await expect
      .poll(
        async () => {
          const box = await currentNode.boundingBox();
          return box?.width ?? 0;
        },
        {
          timeout: 10_000,
          message: 'workflow diagram node should not stay at tiny first-open scale',
        },
      )
      .toBeGreaterThan(50);
  });
});
