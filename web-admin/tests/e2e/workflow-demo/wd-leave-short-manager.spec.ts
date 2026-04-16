import { test, expect } from '../../fixtures';
import {
  uniqueId,
  dateOffsetStr,
  executeCommandViaApi,
} from '../helpers';

/**
 * Workflow Demo E2E — Short sick leave → Manager approval → Approved
 *
 * Record identification: PID from API → detail API for code/id.
 * D1 covered by sidebar → list navigation.
 * Detail pages accessed via /p/wd_leave_request/view/{pid} (list may paginate).
 */

const UID = uniqueId('WD1');
const LEAVE_REASON = `E2E sick leave ${UID}`;
const START_DATE = dateOffsetStr(3);
const END_DATE = dateOffsetStr(4);
const ADMIN_USER_ID = '302959828878364672';

test.describe('Workflow Demo — Short sick leave → Manager approve', () => {
  test.describe.configure({ mode: 'serial' });

  let leaveRequestPid: string;
  let leaveRequestCode: string;
  let processInstanceId: string | null = null;

  /** Sidebar → list page (D1) */
  async function navigateToList(page: import('@playwright/test').Page) {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    const nav = page.locator('nav').first();
    await expect(nav).toBeVisible({ timeout: 5_000 });
    const rootBtn = nav.getByRole('button', { name: /请假|Leave Demo/i }).first();
    await expect(rootBtn).toBeVisible({ timeout: 5_000 });
    await rootBtn.evaluate((el: HTMLElement) => el.click());
    const leafLink = nav.locator('a[href="/p/wd_leave_request"]').first();
    await expect(leafLink).toBeVisible({ timeout: 3_000 });
    const listResp = page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/wd_leave_request') && r.url().includes('list') && r.status() === 200,
      { timeout: 15_000 },
    );
    await leafLink.evaluate((el: HTMLElement) => el.click());
    await listResp;
  }

  /** Detail page via URL (record may not be on list page 1) */
  async function goToDetail(page: import('@playwright/test').Page) {
    await page.goto(`/p/wd_leave_request/view/${leaveRequestPid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="form-field-wd_req_code"]').first())
      .toBeVisible({ timeout: 10_000 });
  }

  async function fetchRecord(page: import('@playwright/test').Page) {
    const resp = await page.request.get(`/api/dynamic/wd_leave_request_detail/${leaveRequestPid}`);
    expect(resp.status()).toBe(200);
    return (await resp.json())?.data;
  }

  // Setup
  test('WD1-001 Create draft via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'wd:create_leave_request', {
      wd_req_applicant: ADMIN_USER_ID,
      wd_req_type: 'sick',
      wd_req_start_date: START_DATE,
      wd_req_end_date: END_DATE,
      wd_req_days: 2,
      wd_req_reason: LEAVE_REASON,
    }, undefined, 'create');
    leaveRequestPid = result?.recordId;
    expect(leaveRequestPid).toBeTruthy();

    const record = await fetchRecord(page);
    leaveRequestCode = record?.wd_req_code;
    expect(leaveRequestCode).toMatch(/^WDLR-/);
  });

  // D1 + D2 — Menu navigation, list renders with data
  test('WD1-002 Sidebar menu → list page renders', async ({ page }) => {
    test.setTimeout(30_000);
    await navigateToList(page);
    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 5_000 });
    // Verify table has rows (at least headers + 1 data row)
    const rows = page.locator('table tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 5_000 });
  });

  // D7 — Detail page field values
  test('WD1-003 Detail page shows correct field values', async ({ page }) => {
    test.setTimeout(30_000);
    await goToDetail(page);

    const main = page.locator('main').first();
    await expect(main.locator('[data-testid="form-field-wd_req_status"]').first())
      .toContainText(/draft|草稿/i, { timeout: 5_000 });
    await expect(main.locator('[data-testid="form-field-wd_req_type"]').first())
      .toContainText(/sick|病假/i);
    await expect(main.locator('[data-testid="form-field-wd_req_days"]').first())
      .toContainText('2');
    await expect(main.locator('[data-testid="form-field-wd_req_reason"]').first())
      .toContainText(LEAVE_REASON);
  });

  // D9 — Submit from detail page
  test('WD1-004 Submit leave request', async ({ page }) => {
    test.setTimeout(30_000);
    await goToDetail(page);

    const submitBtn = page.locator('[data-testid="toolbar-btn-submit"]');
    await expect(submitBtn).toBeVisible({ timeout: 5_000 });

    const cmdResp = page.waitForResponse(
      (r) => r.url().includes('/api/meta/commands/execute/') && r.status() === 200,
      { timeout: 10_000 },
    );
    await submitBtn.click();

    const dialog = page.locator('[data-testid="confirm-dialog"]');
    if (await dialog.isVisible().catch(() => false)) {
      await page.locator('[data-testid="confirm-ok"]').click();
    }

    const resp = await cmdResp;
    const body = await resp.json();
    expect(body?.code).toBe('0');
  });

  // D7 — Verify submitted
  test('WD1-005 Status is submitted', async ({ page }) => {
    test.setTimeout(30_000);
    const record = await fetchRecord(page);
    expect(record?.wd_req_status).toBe('submitted');
    processInstanceId = record?.wd_req_process_instance ?? null;

    await goToDetail(page);
    const main = page.locator('main').first();
    await expect(main.locator('[data-testid="form-field-wd_req_status"]').first())
      .toContainText(/submitted|已提交/i, { timeout: 5_000 });
  });

  // D9 — Approve (conditional on BPM)
  test('WD1-006 Approve via inbox', async ({ page }) => {
    test.setTimeout(45_000);
    if (!processInstanceId) {
      test.skip(true, 'BPM process not started — se_deployment_instance empty');
      return;
    }

    const inboxResp = await page.request.get('/api/inbox?status=pending&itemType=approval&pageNum=1&pageSize=50');
    const items = (await inboxResp.json())?.data?.records ?? [];
    const item = items.find((i: any) => i.cardData?.processKey?.includes('wd_leave_approval'));
    expect(item, 'pending approval should exist').toBeTruthy();

    const approveResp = await page.request.post(`/api/inbox/${item.id}/approval-action`, {
      data: { action: 'approve', comment: `E2E approved ${UID}` },
    });
    expect(approveResp.status()).toBe(200);

    await goToDetail(page);
    const main = page.locator('main').first();
    await expect(main.locator('[data-testid="form-field-wd_req_status"]').first())
      .toContainText(/approved|已通过/i, { timeout: 5_000 });
  });
});
