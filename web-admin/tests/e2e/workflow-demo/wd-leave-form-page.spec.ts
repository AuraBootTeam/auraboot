import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect, type Page } from '../../fixtures';
import { createLeaveApplicant, loginAs, loginViaUI } from '../../helpers/wd-fixtures';

const LICENSE_FILE = fileURLToPath(new URL('../../../../LICENSE.txt', import.meta.url));

test.setTimeout(60_000);

function dateOffsetStr(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

async function fillDatePicker(page: Page, name: string, isoDate: string): Promise<void> {
  const input = page.locator(`[data-testid="date-picker-input-${name}"]`).first();
  await expect(input).toBeVisible({ timeout: 5_000 });
  await input.click();
  await input.fill('');
  await input.fill(isoDate);
  await expect(input).toHaveValue(isoDate);
  await page.keyboard.press('Escape').catch(() => null);
}

async function pickSmartSelect(page: Page, name: string, label: RegExp | string): Promise<void> {
  const trigger = page.locator(`[data-testid="select-trigger-${name}"]`).first();
  await expect(trigger).toBeVisible({ timeout: 5_000 });
  await trigger.click();
  const option = page.getByRole('option', { name: label }).first();
  await expect(option).toBeVisible({ timeout: 5_000 });
  await option.click();
}

async function pickMemberInField(
  page: Page,
  fieldCode: string,
  userId: string,
  searchText: string,
): Promise<void> {
  const field = page.locator(`[data-testid="form-field-${fieldCode}"]`).first();
  await field.scrollIntoViewIfNeeded();
  const trigger = field.locator('[data-testid="member-picker-trigger"]').first();
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  await trigger.locator('[data-testid="member-picker-add"]').first().click();
  const popup = field.locator('[data-testid="member-picker-popup"]').first();
  await expect(popup).toBeVisible({ timeout: 5_000 });
  const searchInput = popup.locator('[data-testid="member-picker-search-input"]').first();
  await searchInput.fill(searchText);
  const option = popup.locator(`[data-testid="member-picker-option-${userId}"]`).first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  await option.click();
  await expect(
    field.locator(`[data-testid="member-picker-selected-${userId}"]`).first(),
  ).toBeVisible({ timeout: 5_000 });
  await page.keyboard.press('Escape').catch(() => null);
}

async function createApplicantSession(browser: any, request: any, prefix: string) {
  const adminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
  const applicant = await createLeaveApplicant(request, adminToken, prefix);
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, applicant.email, 'Test2026x');
  return { applicant, context, page };
}

test.describe('workflow-demo — leave request form page', () => {
  test('validation UX + create draft + detail roundtrip', async ({ browser, request }) => {
    const { applicant, context, page } = await createApplicantSession(
      browser,
      request,
      'wd_form_page',
    );

    const ccAdminToken = await loginAs(request, 'admin@example.com', 'Test2026x');
    const meResp = await request.get('http://localhost:6443/api/auth/me', {
      headers: { Authorization: `Bearer ${ccAdminToken}` },
    });
    expect(meResp.ok()).toBeTruthy();
    const meBody = await meResp.json();
    const adminUserId = String(meBody?.data?.user?.pid ?? meBody?.data?.user?.id ?? '');
    expect(adminUserId).toBeTruthy();

    await test.step('open new form and verify empty-submit inline required errors', async () => {
      await page.goto('/p/wd_leave_request/new', { waitUntil: 'domcontentloaded' });
      await expect(page).toHaveURL(/\/p\/wd_leave_request\/new/);

      const submitBtn = page.locator('[data-testid="form-btn-submit"]').first();
      await expect(submitBtn).toBeVisible({ timeout: 5_000 });
      await submitBtn.click();

      await expect(page.locator('[data-testid="form-error-summary"]')).toHaveCount(0);
      await expect(page.locator('[data-testid="form-field-wd_req_applicant"]').first()).toContainText(
        /请选择申请人|Applicant is required/i,
      );
      await expect(page.locator('[data-testid="form-field-wd_req_type"]').first()).toContainText(
        /请选择请假类型|Leave Type is required/i,
      );
      await expect(
        page.locator('[data-testid="form-field-wd_req_start_date"]').first(),
      ).toContainText(/请选择开始日期|Start Date is required/i);
      await expect(page.locator('[data-testid="form-field-wd_req_start_slot"]').first()).toContainText(
        /请选择开始时段|Start Session is required/i,
      );
      await expect(page.locator('[data-testid="form-field-wd_req_end_date"]').first()).toContainText(
        /请选择结束日期|End Date is required/i,
      );
      await expect(page.locator('[data-testid="form-field-wd_req_end_slot"]').first()).toContainText(
        /请选择结束时段|End Session is required/i,
      );
    });

    await test.step('fill basic fields and verify cross-field summary error', async () => {
      await pickMemberInField(page, 'wd_req_applicant', applicant.userId, applicant.displayName);
      await pickSmartSelect(page, 'wd_req_type', /年假|Annual/i);

      const startDate = dateOffsetStr(12);
      const endDate = dateOffsetStr(10);
      await fillDatePicker(page, 'wd_req_start_date', startDate);
      await pickSmartSelect(page, 'wd_req_start_slot', /上午|Morning/i);
      await fillDatePicker(page, 'wd_req_end_date', endDate);
      await pickSmartSelect(page, 'wd_req_end_slot', /下午|Afternoon/i);

      await page.locator('[data-testid="form-btn-submit"]').first().click();

      const summary = page.locator('[data-testid="form-error-summary"]').first();
      await expect(summary).toBeVisible({ timeout: 5_000 });
      await expect(summary).toContainText('结束日期不能早于开始日期');
    });

    await test.step('complete valid half-day form, upload attachment, create draft', async () => {
      const sameDay = dateOffsetStr(14);
      const reason = `wd leave form page ${Date.now()}`;

      await fillDatePicker(page, 'wd_req_start_date', sameDay);
      await pickSmartSelect(page, 'wd_req_start_slot', /上午|Morning/i);
      await fillDatePicker(page, 'wd_req_end_date', sameDay);
      await pickSmartSelect(page, 'wd_req_end_slot', /上午|Morning/i);

      const daysInput = page.locator('[data-testid="form-field-wd_req_days"] input').first();
      await expect(daysInput).toHaveAttribute('readonly', '');
      await expect(daysInput).toHaveValue('0.5');

      const reasonTextarea = page.locator('[data-testid="form-field-wd_req_reason"] textarea').first();
      await reasonTextarea.fill(reason);

      await pickMemberInField(page, 'wd_req_cc_users', adminUserId, 'admin');
      await page.keyboard.press('Escape').catch(() => null);
      await reasonTextarea.click();

      await page.locator('[data-testid="upload-input-wd_req_attachments"]').setInputFiles(LICENSE_FILE);
      const attachmentField = page.locator('[data-testid="form-field-wd_req_attachments"]').first();
      await expect(attachmentField).toContainText(path.basename(LICENSE_FILE), { timeout: 10_000 });
      await expect(attachmentField.locator('[data-testid="btn-remove-file"]').first()).toBeVisible({
        timeout: 10_000,
      });

      const createRespPromise = page.waitForResponse(
        (resp: import('@playwright/test').Response) =>
          resp.url().includes('/api/meta/commands/execute/wd:create_leave_request') &&
          resp.request().method() === 'POST',
        { timeout: 20_000 },
      );
      await page.locator('[data-testid="form-btn-submit"]').first().click();
      const createResp = await createRespPromise;
      expect(createResp.status()).toBeLessThan(400);
      const createBody = await createResp.json();
      expect(String(createBody?.code)).toBe('0');

      const recordId = String(createBody?.data?.data?.recordId ?? '');
      expect(recordId).toBeTruthy();

      await page.waitForURL(/\/p\/wd_leave_request(?:$|\?|\/$)/, { timeout: 15_000 });
      await expect(page.locator('[data-testid="form-error-summary"]')).toHaveCount(0);

      const detailResp = await page.request.get(`/api/dynamic/wd_leave_request_detail/${recordId}`);
      expect(detailResp.ok()).toBeTruthy();
      const detailBody = await detailResp.json();
      const record = detailBody?.data as Record<string, unknown> | undefined;
      expect(record).toBeTruthy();
      expect(record?.wd_req_applicant).toBe(applicant.userId);
      expect(record?.wd_req_type).toBe('annual');
      expect(String(record?.wd_req_days)).toBe('0.5');
      expect(record?.wd_req_reason).toBe(reason);
      expect(String(record?.wd_req_status)).toBe('draft');
      expect(String(record?.wd_req_cc_users || '')).toContain(adminUserId);
      expect(String(record?.wd_req_attachments || '')).toContain(path.basename(LICENSE_FILE));

      await page.goto(`/p/wd_leave_request/view/${recordId}`, { waitUntil: 'domcontentloaded' });
      const main = page.locator('main').first();
      await expect(main.locator('[data-testid="form-field-wd_req_status"]').first()).toContainText(
        /草稿|Draft/i,
      );
      await expect(main.locator('[data-testid="form-field-wd_req_days"]').first()).toContainText('0.5');
      await expect(main.locator('[data-testid="form-field-wd_req_reason"]').first()).toContainText(reason);
      await expect(main.locator('[data-testid="form-field-wd_req_attachments"]').first()).toContainText(
        path.basename(LICENSE_FILE),
      );
      await expect(main.locator('[data-testid="form-field-wd_req_cc_users"]').first()).toContainText(
        /Admin User|管理员/i,
      );

      await expect(page.getByRole('tab', { name: /流程图|Workflow Diagram/i }).first()).toBeVisible();
      await expect(page.getByRole('tab', { name: /流程轨迹|Activity Timeline/i }).first()).toBeVisible();
      await expect(page.getByRole('tab', { name: /变更历史|Field History/i }).first()).toBeVisible();

      await page.getByRole('tab', { name: /流程图|Workflow Diagram/i }).first().click();
      await expect(page.locator('[data-testid="bpm-panel"]').first()).toBeVisible({ timeout: 10_000 });

      await page.getByRole('tab', { name: /流程轨迹|Activity Timeline/i }).first().click();
      await expect(page.locator('main').first()).toContainText(/活动记录|activities|暂无活动记录|No activities/i);

      await page.getByRole('tab', { name: /变更历史|Field History/i }).first().click();
      await expect(page.locator('main').first()).toContainText(/变更历史|加载变更历史|暂无变更记录|No change history/i);

      await page.goto('/p/wd_leave_request', { waitUntil: 'domcontentloaded' });
      const listRow = page.locator('[data-testid="table-row-0"]').first();
      await expect(listRow).toBeVisible({ timeout: 10_000 });

      const navigationPromise = page.waitForURL(new RegExp(`/p/wd_leave_request/view/${recordId}$`), {
        timeout: 15_000,
      });
      await listRow.click();
      await navigationPromise;
      await expect(page.locator('[data-testid="record-preview-drawer"]')).toHaveCount(0);
      await expect(page.locator('main').first()).toContainText(reason);
    });

    await context.close();
  });
});
