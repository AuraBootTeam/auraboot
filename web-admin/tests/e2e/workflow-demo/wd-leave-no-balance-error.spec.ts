import { test, expect, type Page } from '../../fixtures';
import { loginAs, createLeaveApplicant } from '../../helpers/wd-fixtures';

/**
 * The leave form must tell an applicant with no balance on file *why* the submit was
 * refused. This drives the real form at /p/wd_leave_request/new and reads the toast the
 * user actually sees — the toast used to render the raw reason code
 * `annual_leave_insufficient`, which was both untranslated and simply wrong (the
 * applicant had no balance record at all).
 */

test.setTimeout(60_000);

function dateOffsetStr(offsetDays: number): string {
  const date = new Date();
  date.setDate(date.getDate() + offsetDays);
  return date.toISOString().slice(0, 10);
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

async function pickSmartSelect(page: Page, name: string, label: RegExp): Promise<void> {
  const trigger = page.locator(`[data-testid="select-trigger-${name}"]`).first();
  await expect(trigger).toBeVisible({ timeout: 5_000 });
  await trigger.click();
  const option = page.getByRole('option', { name: label }).first();
  await expect(option).toBeVisible({ timeout: 5_000 });
  await option.click();
}

async function pickApplicant(page: Page, userId: string, searchText: string): Promise<void> {
  const field = page.locator('[data-testid="form-field-wd_req_applicant"]').first();
  await field.scrollIntoViewIfNeeded();
  await field.locator('[data-testid="member-picker-add"]').first().click();
  const popup = field.locator('[data-testid="member-picker-popup"]').first();
  await expect(popup).toBeVisible({ timeout: 5_000 });
  await popup.locator('[data-testid="member-picker-search-input"]').first().fill(searchText);
  const option = popup.locator(`[data-testid="member-picker-option-${userId}"]`).first();
  await expect(option).toBeVisible({ timeout: 10_000 });
  await option.click();
  await expect(
    field.locator(`[data-testid="member-picker-selected-${userId}"]`).first(),
  ).toBeVisible({ timeout: 5_000 });
  await page.keyboard.press('Escape').catch(() => null);
}

test.describe('workflow-demo — leave form, applicant without a balance', () => {
  test('submit is refused with a readable reason, not a raw rule code', async ({
    browser,
    request,
  }) => {
    const adminToken = await loginAs(request, 'admin@auraboot.com', 'Test2026x');
    // A brand-new user has no wd_leave_balance row — the state every demo env starts in.
    const applicant = await createLeaveApplicant(request, adminToken, 'ui_no_balance');

    const context = await browser.newContext();
    const page = await context.newPage();

    try {
      await page.goto('/p/wd_leave_request/new', { waitUntil: 'domcontentloaded' });
      await expect(page.locator('[data-testid="form-btn-submit"]').first()).toBeVisible({
        timeout: 15_000,
      });

      const start = dateOffsetStr(30);
      const end = dateOffsetStr(34);
      await pickApplicant(page, applicant.userId, applicant.displayName.split(' ')[0]);
      await pickSmartSelect(page, 'wd_req_type', /年假|Annual/i);
      await fillDatePicker(page, 'wd_req_start_date', start);
      await pickSmartSelect(page, 'wd_req_start_slot', /上午|Morning/i);
      await fillDatePicker(page, 'wd_req_end_date', end);
      await pickSmartSelect(page, 'wd_req_end_slot', /下午|Afternoon/i);
      await page
        .locator('[data-testid="form-field-wd_req_reason"] textarea')
        .first()
        .fill('golden: applicant has no leave balance on file');

      const submitResp = page.waitForResponse(
        (resp) =>
          resp.url().includes('/api/meta/commands/execute/wd:create_and_submit_leave_request') &&
          resp.request().method() === 'POST',
        { timeout: 20_000 },
      );
      await page.locator('[data-testid="form-btn-submit"]').first().click();
      const body = await (await submitResp).json();

      expect(body?.code, `submit should be refused: ${JSON.stringify(body)}`).not.toBe('0');
      expect(String(body?.context?.messageKey ?? '')).toContain('annual_balance_not_found');

      // The toast text comes from the i18n catalog, so assert in whichever locale the
      // browser negotiated — what must never appear is a raw reason code or a message key.
      const toast = page.locator('[data-testid="toast-stack"]');
      await expect(toast).toContainText(/年假余额|annual leave balance/i, { timeout: 10_000 });
      await expect(toast).not.toContainText('annual_balance_not_found');
      await expect(toast).not.toContainText('$i18n:');
      await expect(toast).not.toContainText(/^Business error$/);

      await page.screenshot({
        path: 'test-results/wd-leave-no-balance-toast.png',
        fullPage: true,
      });
    } finally {
      await context.close();
    }
  });
});
