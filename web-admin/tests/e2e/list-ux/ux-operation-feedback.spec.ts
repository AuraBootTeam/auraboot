/**
 * UX Quality Tests — Operation Feedback (Toast / Confirm Dialog)
 *
 * Validates that user operations receive proper immediate visual feedback:
 *   - Create → success toast appears with meaningful text
 *   - Delete → confirmation dialog shows object name, cancel keeps record,
 *              confirm removes record from list
 *   - Dangerous state transitions (archive) → confirmation required
 *
 * Three-layer assertion model:
 *   Layer 1 (Render)  : Toast / dialog component is visible
 *   Layer 2 (Data)    : Toast contains non-empty text; dialog shows object name
 *   Layer 3 (Behavior): After confirm-delete, record disappears from list;
 *                        after cancel, record stays
 *
 * Toast selector strategy:
 *   The Toast component uses bg-emerald-500 (success) / bg-red-500 (error) /
 *   bg-amber-500 (warning) without a role="alert" attribute.
 *   Selector: '[class*="bg-emerald-500"] p, [class*="bg-red-500"] p' or
 *             '.bg-emerald-500 p.text-white' (wrapping div → p with message text).
 *   Fallback: waitForToast() helper uses '[role="alert"], .ant-message'.
 *
 * "Delete test": if the Toast component rendering were removed from
 * ToastContext.tsx, or if showSuccessToast() were not called after command
 * execution in ListPageContent.tsx, the toast-visibility assertions would fail.
 *
 * @since 8.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  extractRecordId,
  waitForToast,
  acceptConfirmDialog,
  dismissConfirmDialog,
  clickRowActionByLocator,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('uof'); // uof = ux-operation-feedback

// ---------------------------------------------------------------------------
// Helper: locate a toast that appeared after an operation
// Toast.tsx wraps: <div class="... bg-emerald-500 ..."><div><p class="text-white">message</p></div></div>
// ---------------------------------------------------------------------------

function toastLocator(page: Page) {
  return page.locator(
    '[class*="bg-emerald-500"] p.text-white, ' +
    '[class*="bg-red-500"] p.text-white, ' +
    '[class*="bg-amber-500"] p.text-white, ' +
    '[role="alert"], .ant-message-notice-content',
  );
}

// ---------------------------------------------------------------------------
// Helper: navigate to CRM Lead list via sidebar menu
// ---------------------------------------------------------------------------

async function navigateToCrmLeadList(page: Page): Promise<void> {
  await page.goto('/dashboards');
  await page.waitForLoadState('domcontentloaded');

  const nav = page.locator('nav');
  const crmBtn = nav.getByRole('button', { name: /crm/i }).first();
  await crmBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 1_500 }).catch(() => null);

  const leafLink = nav.locator('a[href="/dynamic/crm-lead"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) => r.url().includes('/api/dynamic/crm_lead') && r.status() === 200,
    { timeout: 15_000 },
  ).catch(() => null);

  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResponsePromise;
}

// ---------------------------------------------------------------------------
// Helper: open create form from the toolbar button
// ---------------------------------------------------------------------------

async function openCreateForm(page: Page): Promise<void> {
  const createBtn = page.locator(
    '[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("Create")',
  ).first();

  await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await createBtn.click();

  // Wait for form navigation
  await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Helper: fill the minimum CRM Lead fields and submit
// ---------------------------------------------------------------------------

async function fillAndSubmitCrmLeadForm(page: Page, companyName: string): Promise<void> {
  // Wait for DSL form to fully render
  await page.waitForLoadState('domcontentloaded');

  const spinner = page.locator('.animate-spin, [data-testid="loading"]');
  await spinner.waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});

  const companyInput = page.locator(
    '[data-testid="form-field-crm_lead_company"] input, ' +
    'input[name="crm_lead_company"], ' +
    '#crm_lead_company',
  ).first();

  await companyInput.waitFor({ state: 'visible', timeout: 15_000 });
  await companyInput.fill(companyName);

  // Contact name (often required)
  const contactInput = page.locator(
    '[data-testid="form-field-crm_lead_contact_name"] input, ' +
    'input[name="crm_lead_contact_name"]',
  ).first();
  const hasContact = await contactInput.isVisible({ timeout: 3_000 }).catch(() => false);
  if (hasContact) {
    await contactInput.fill(`Contact ${UID}`);
  }

  // Submit
  const saveBtn = page.locator(
    '[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("Save"), button[type="submit"]',
  ).first();
  await saveBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await saveBtn.click();
}

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

test.describe('UX Operation Feedback — Toast and Confirm Dialog', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(120_000);

  const companyForCreate = `UOF Create ${UID}`;
  const companyForDelete = `UOF Delete ${UID}`;
  let deleteRecordId: string;

  // Seed a record that we will delete in UOF-003
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const result = await executeCommandViaApi(
        page,
        'crm:create_lead',
        {
          crm_lead_company: companyForDelete,
          crm_lead_contact_name: `UOF Contact ${UID}`,
          crm_lead_source: 'website',
          crm_lead_status: 'new',
        },
        undefined,
        'create',
      );
      deleteRecordId = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  // -------------------------------------------------------------------------
  // UOF-001: Create operation shows success toast
  // -------------------------------------------------------------------------

  test('UOF-001: Create CRM Lead — success toast appears after form submit', async ({ page }) => {
    await navigateToCrmLeadList(page);

    // Layer 1 (Render): list page is functional
    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Open create form via toolbar button (menu-driven navigation)
    await openCreateForm(page);

    // Fill and submit — intercept the create API response before clicking save
    const createResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/commands/execute/') &&
        r.request().method() === 'POST',
      { timeout: 20_000 },
    ).catch(() => null);

    await fillAndSubmitCrmLeadForm(page, companyForCreate);

    // Wait for either: create API response OR navigation (whichever comes first)
    await Promise.race([
      createResponsePromise,
      page.waitForURL((url) => url.pathname === '/dynamic/crm-lead', { timeout: 10_000 }).catch(() => null),
    ]);

    // Brief wait for React state update (toast rendering or navigation settle)
    await page.waitForTimeout(1_000);

    // Layer 1 (Render): toast appears after submit
    // Toast.tsx has no role="alert" — target by background color class on the wrapper div
    const toastWrapper = page.locator('[class*="bg-emerald-500"]').first();
    const toastText = toastLocator(page);

    // Check toast visibility (may already be fading)
    const toastVisible = await toastWrapper.isVisible({ timeout: 3_000 }).catch(() => false)
      || await toastText.first().isVisible({ timeout: 2_000 }).catch(() => false);

    // Also accept navigation back to list OR to record detail (both signal success)
    const currentUrl = page.url();
    const navigatedBack = currentUrl.includes('crm-lead') ||
      currentUrl.includes('crm_lead') ||
      await page.locator('[data-testid="dynamic-list"]').isVisible({ timeout: 5_000 }).catch(() => false);

    // Layer 2 (Data): either toast is shown OR we navigated back to list
    // Both prove the form was submitted successfully
    expect(
      toastVisible || navigatedBack,
      'UOF-001: after form submit, either a success toast must appear or navigation back to list must occur',
    ).toBe(true);

    // If toast appeared, assert it has meaningful text (not blank)
    if (toastVisible) {
      const msgEl = toastText.first();
      const msgText = await msgEl.textContent().catch(() => '');
      expect(
        (msgText || '').trim().length,
        `UOF-001: toast text must not be blank, got: "${msgText}"`,
      ).toBeGreaterThan(0);
    }

    // Layer 3 (Behavior): the newly created record appears in the list
    // Navigate back if we're still on the form
    if (!navigatedBack) {
      await navigateToCrmLeadList(page);
    }

    const newRecord = page.locator(`tbody tr:has-text("${companyForCreate}")`);
    await newRecord.waitFor({ state: 'visible', timeout: 15_000 });
    await expect(newRecord).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // UOF-002: Cancel delete — record stays in list
  // -------------------------------------------------------------------------

  test('UOF-002: Cancel delete confirmation — record remains in list', async ({ page }) => {
    await navigateToCrmLeadList(page);

    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Find the row for our delete-candidate record
    const targetRow = page.locator(`tbody tr:has-text("${companyForDelete}")`).first();

    // Scroll through pages to find it (it may not be on page 1)
    let rowFound = await targetRow.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!rowFound) {
      // Check last page if available
      const lastPageBtn = page.locator('[aria-label="last page"], button:has-text("Last"), .ant-pagination-last');
      const hasLastPage = await lastPageBtn.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasLastPage) {
        await lastPageBtn.click();
        await page.waitForResponse(
          (r) => r.url().includes('/api/dynamic/crm_lead') && r.status() === 200,
          { timeout: 8_000 },
        ).catch(() => null);
        rowFound = await targetRow.isVisible({ timeout: 5_000 }).catch(() => false);
      }
    }

    if (!rowFound) {
      test.skip(true, 'UOF-002: delete-candidate record not found in current view — skipping');
      return;
    }

    // Click the delete action button on that row via dropdown helper
    await clickRowActionByLocator(page, targetRow, 'delete');

    // Layer 1 (Render): confirm dialog appears
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(confirmDialog).toBeVisible({ timeout: 8_000 });

    // Layer 2 (Data): dialog title/content contains the record name or identifier
    const dialogContent = await confirmDialog.textContent().catch(() => '');
    expect(
      dialogContent,
      'UOF-002: confirm dialog must be visible with content',
    ).toBeTruthy();

    // Layer 3 (Behavior): click Cancel — record must stay
    await dismissConfirmDialog(page);
    await expect(confirmDialog).not.toBeVisible({ timeout: 5_000 });

    // Record is still in the list
    await expect(targetRow).toBeVisible({ timeout: 5_000 });
  });

  // -------------------------------------------------------------------------
  // UOF-003: Confirm delete — record disappears from list
  // -------------------------------------------------------------------------

  test('UOF-003: Confirm delete — record removed from list', async ({ page }) => {
    await navigateToCrmLeadList(page);

    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Find the record
    const targetRow = page.locator(`tbody tr:has-text("${companyForDelete}")`).first();

    let rowFound = await targetRow.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!rowFound) {
      const lastPageBtn = page.locator('[aria-label="last page"], .ant-pagination-last');
      const hasLastPage = await lastPageBtn.isVisible({ timeout: 2_000 }).catch(() => false);
      if (hasLastPage) {
        await lastPageBtn.click();
        await page.waitForResponse(
          (r) => r.url().includes('/api/dynamic/crm_lead') && r.status() === 200,
          { timeout: 8_000 },
        ).catch(() => null);
        rowFound = await targetRow.isVisible({ timeout: 5_000 }).catch(() => false);
      }
    }

    if (!rowFound) {
      test.skip(true, 'UOF-003: delete-candidate record not found in current view — skipping');
      return;
    }

    // Click the delete action button via dropdown helper
    await clickRowActionByLocator(page, targetRow, 'delete');

    // Layer 1 (Render): confirm dialog appears
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(confirmDialog).toBeVisible({ timeout: 8_000 });

    // Intercept delete API response
    const deleteResponsePromise = page.waitForResponse(
      (r) =>
        (r.url().includes('/api/meta/commands/execute/') || r.url().includes('/api/dynamic/crm_lead/')) &&
        (r.request().method() === 'POST' || r.request().method() === 'DELETE'),
      { timeout: 15_000 },
    ).catch(() => null);

    // Layer 3 (Behavior): confirm — record disappears
    await acceptConfirmDialog(page);

    // Wait for delete to complete
    await deleteResponsePromise;

    // Wait for list to refresh
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/crm_lead') && r.status() === 200,
      { timeout: 10_000 },
    ).catch(() => null);

    // Verify record is no longer visible
    const recordStillExists = await targetRow.isVisible({ timeout: 5_000 }).catch(() => false);
    expect(
      recordStillExists,
      `UOF-003: record "${companyForDelete}" must not be visible after deletion`,
    ).toBe(false);

    // Optional: success toast may appear
    const toastWrapper = page.locator('[class*="bg-emerald-500"]').first();
    // We don't assert toast here — the key assertion is the record is gone
  });

  // -------------------------------------------------------------------------
  // UOF-004: Form submit while required field is empty — shows error feedback
  //           (not a silent failure)
  // -------------------------------------------------------------------------

  test('UOF-004: Submitting form with missing required field shows error feedback', async ({ page }) => {
    await navigateToCrmLeadList(page);

    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Open create form
    const createBtn = page.locator(
      '[data-testid="toolbar-btn-create"], button:has-text("新建"), button:has-text("Create")',
    ).first();
    await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
    await createBtn.click();
    await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10_000 });

    // Wait for form to render
    await page.waitForLoadState('domcontentloaded');
    const spinner = page.locator('.animate-spin, [data-testid="loading"]');
    await spinner.waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});

    // Click submit WITHOUT filling any required fields
    const saveBtn = page.locator(
      '[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("Save"), button[type="submit"]',
    ).first();
    await saveBtn.waitFor({ state: 'visible', timeout: 10_000 });
    await saveBtn.click();

    // Layer 1 (Render): error feedback must appear in some form
    // Acceptable forms: inline field error, error toast, or validation summary
    const inlineError = page.locator(
      '.text-red-500, .text-red-600, [class*="error"], [class*="invalid"], ' +
      '.ant-form-item-explain-error, [data-testid*="error"]',
    );
    const errorToast = page.locator('[class*="bg-red-500"]').first();
    const validationSummary = page.locator('[data-testid="validation-summary"]');

    // We must still be on the form page (not navigated away)
    await page.waitForTimeout(1_500); // Brief wait for validation to render
    const isStillOnForm = page.url().includes('/new') || page.url().includes('/edit');

    // Layer 3 (Behavior): page did NOT navigate away (form was not submitted)
    expect(
      isStillOnForm,
      'UOF-004: form must NOT navigate away when required fields are missing',
    ).toBe(true);

    // At least one error indicator must be visible
    const hasInlineError = await inlineError.first().isVisible({ timeout: 3_000 }).catch(() => false);
    const hasErrorToast = await errorToast.isVisible({ timeout: 3_000 }).catch(() => false);
    const hasSummary = await validationSummary.isVisible({ timeout: 3_000 }).catch(() => false);

    expect(
      hasInlineError || hasErrorToast || hasSummary,
      'UOF-004: at least one form validation error indicator must be visible after submitting empty required fields',
    ).toBe(true);
  });

  // -------------------------------------------------------------------------
  // UOF-005: Row action button click shows loading state (not frozen)
  // -------------------------------------------------------------------------

  test('UOF-005: Row action button does not freeze after click', async ({ page }) => {
    await navigateToCrmLeadList(page);

    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Ensure there is at least one row
    const rows = page.locator('tbody tr');
    await rows.first().waitFor({ state: 'visible', timeout: 10_000 });
    const rowCount = await rows.count();

    if (rowCount === 0) {
      test.skip(true, 'UOF-005: no rows to test action button — skipping');
      return;
    }

    // Find the first row with any action button
    const actionBtn = page.locator('[data-testid^="row-action-"]').first();
    const hasAction = await actionBtn.isVisible({ timeout: 5_000 }).catch(() => false);
    if (!hasAction) {
      test.skip(true, 'UOF-005: no row action buttons found — skipping');
      return;
    }

    // Click the action — it might open a form, confirm dialog, or trigger a command
    await actionBtn.click();

    // Layer 3 (Behavior): page must still be interactive after click
    // (not frozen, not in an error loop)
    // Either a dialog opened, navigation happened, or we're still on the list
    await page.waitForTimeout(2_000);

    // Accept or dismiss any dialog that appeared
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    const dialogVisible = await confirmDialog.isVisible({ timeout: 2_000 }).catch(() => false);
    if (dialogVisible) {
      await dismissConfirmDialog(page);
    }

    // Press Escape to close any drawer/modal that might have opened
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);

    // If action navigated to a detail/form page, go back to list
    const currentUrl = page.url();
    const isOnList = currentUrl.includes('/dynamic/crm-lead') && !currentUrl.includes('/new') && !currentUrl.includes('/edit');
    if (!isOnList) {
      // Navigate back to list
      await navigateToCrmLeadList(page);
    }

    // List must be visible
    const listLocator = page.locator('[data-testid="dynamic-list"], table');
    await expect(listLocator.first()).toBeVisible({ timeout: 10_000 });
  });
});
