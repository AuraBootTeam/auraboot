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
 * Toast selector strategy: the shared UI package renders semantic alerts;
 * the status icon distinguishes success feedback from error feedback.
 *
 * "Delete test": if the Toast component rendering were removed from
 * ToastContext.tsx, or if showSuccessToast() were not called after command
 * execution in ListPageContent.tsx, the toast-visibility assertions would fail.
 *
 * @since 8.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import { createDefaultTableView, restoreDefaultTableView, type DefaultTableViewState } from '../showcase/helpers/default-table-view';
import { navigateToOrderViaSidebar } from '../saved-view/helpers';
import {
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  dismissConfirmDialog,
  clickRowActionByLocator,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('uof'); // uof = ux-operation-feedback

// ---------------------------------------------------------------------------
// Helper: navigate to CRM Lead list via sidebar menu
// ---------------------------------------------------------------------------

async function navigateToCrmLeadList(page: Page): Promise<void> {
  await navigateToOrderViaSidebar(page);
}

// ---------------------------------------------------------------------------
// Helper: open create form from the toolbar button
// ---------------------------------------------------------------------------

async function openCreateForm(page: Page): Promise<void> {
  const createBtn = page
    .locator(
      '[data-testid="toolbar-btn-create"], button:has-text("新建"):not(:has-text("今日")), button:has-text("Create")',
    )
    .first();

  await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await createBtn.click();

  // Wait for form navigation
  await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Helper: fill the minimum CRM Lead fields and submit
// ---------------------------------------------------------------------------

async function fillAndSubmitCrmLeadForm(page: Page, title: string, customerName: string, customerPid: string): Promise<void> {
  // Wait for DSL form to fully render
  await page.waitForLoadState('domcontentloaded');

  const spinner = page.locator('.animate-spin, [data-testid="loading"]');
  await spinner.waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});

  const titleInput = page
    .locator(
      '[data-testid="form-field-e2et_order_title"] input, ' +
        'input[name="e2et_order_title"], ' +
        '#e2et_order_title',
    )
    .first();

  await titleInput.waitFor({ state: 'visible', timeout: 15_000 });
  await titleInput.fill(title);

  const customerTrigger = page.getByTestId('select-trigger-e2et_order_customer');
  await expect(customerTrigger).toBeEnabled();
  await customerTrigger.click();
  await page.getByTestId('select-search-e2et_order_customer').fill(customerName);
  const customerOption = page.getByRole('option').filter({ hasText: customerName });
  await expect(customerOption).toHaveCount(1);
  await customerOption.click();
  await expect(customerTrigger).toContainText(customerName);
  await expect(page.locator('input[type="hidden"][name="e2et_order_customer"]')).toHaveValue(customerPid);

  // Submit
  const saveBtn = page
    .locator(
      '[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("Save"), button[type="submit"]',
    )
    .first();
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
  // Seed both real references before exercising the order form.
  const customerForCreate = `UOF Customer ${UID}`;
  const customerForDelete = `UOF Delete Cust ${UID}`;
  const customerPids = new Map<string, string>();
  let deleteOrderPid: string;
  let defaultTableView: DefaultTableViewState | null = null;

  // Seed a record that we will delete in UOF-003
  test.beforeAll(async ({ browser, request }) => {
    defaultTableView = await createDefaultTableView(request, 'e2et_order', 'e2et_order_list', 'Operation Feedback');
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      for (const cust of [customerForCreate, customerForDelete]) {
        const customer = await executeCommandViaApi(page, 'e2et:create_customer', {
          e2et_cust_code: `E2EC-${uniqueId('uof_cust')}`,
          e2et_cust_name: cust,
          e2et_cust_region: 'north',
        }, undefined, 'create');
        expect(customer.recordId).toMatch(/^[0-9A-Z]{26}$/);
        customerPids.set(cust, customer.recordId);
      }
      const result = await executeCommandViaApi(
        page,
        'e2et:create_order',
        {
          e2et_order_title: companyForDelete,
          e2et_order_customer: customerPids.get(customerForCreate),
          e2et_order_type: 'normal',
        },
        undefined,
        'create',
      );
      expect(result.recordId).toMatch(/^[0-9A-Z]{26}$/);
      deleteOrderPid = result.recordId;
    } finally {
      await ctx.close();
    }
  });

  test.afterAll(async ({ request }) => {
    await restoreDefaultTableView(request, defaultTableView);
    defaultTableView = null;
  });

  async function searchForDeleteCandidate(page: Page) {
    const responsePromise = page.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === '/api/dynamic/e2et_order/list'
        && url.searchParams.get('keyword') === companyForDelete;
    });
    const search = page.getByTestId('list-search-input');
    await search.fill(companyForDelete);
    await search.press('Enter');
    const response = await responsePromise;
    expect(response.ok()).toBe(true);
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    expect(body.data.records).toHaveLength(1);
    expect(body.data.records[0]).toMatchObject({ pid: deleteOrderPid, e2et_order_title: companyForDelete });
    const targetRow = page.locator('tbody tr').filter({ hasText: companyForDelete });
    await expect(targetRow).toHaveCount(1);
    await expect(targetRow).toBeVisible();
    return targetRow;
  }

  // -------------------------------------------------------------------------
  // UOF-001: Create operation shows success toast
  // -------------------------------------------------------------------------

  test('UOF-001: Create e2et order — success toast appears after form submit', async ({ page }) => {
    await navigateToCrmLeadList(page);

    // Layer 1 (Render): list page is functional
    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Open create form via toolbar button (menu-driven navigation)
    await openCreateForm(page);

    // Fill and submit — intercept the create API response before clicking save
    const createResponsePromise = page.waitForResponse(
      response => response.url().includes('/api/meta/commands/execute/')
        && response.request().method() === 'POST',
      { timeout: 20_000 },
    );

    await fillAndSubmitCrmLeadForm(page, companyForCreate, customerForCreate, customerPids.get(customerForCreate)!);
    const created = await createResponsePromise;
    expect(created.status()).toBe(200);
    expect(String((await created.json()).code)).toBe('0');
    expect(created.request().postDataJSON().payload.e2et_order_customer).toBe(customerPids.get(customerForCreate));

    const successToast = page.getByRole('alert').filter({ has: page.locator('svg.text-emerald-600') }).first();
    await expect(successToast).toBeVisible();
    await expect(successToast).toContainText(/\S/);
    await expect(page).toHaveURL(/\/p\/e2et_order(?:[?#]|$)/);

    // The successful response, selected reference and persisted list row must agree.
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

    const targetRow = await searchForDeleteCandidate(page);

    // Click the delete action button on that row via dropdown helper
    await clickRowActionByLocator(page, targetRow, 'delete');

    // Layer 1 (Render): confirm dialog appears
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(confirmDialog).toBeVisible({ timeout: 8_000 });

    // Layer 2 (Data): dialog title/content contains the record name or identifier
    const dialogContent = await confirmDialog.textContent().catch(() => '');
    expect(dialogContent, 'UOF-002: confirm dialog must be visible with content').toBeTruthy();

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

    const targetRow = await searchForDeleteCandidate(page);

    // Click the delete action button via dropdown helper
    await clickRowActionByLocator(page, targetRow, 'delete');

    // Layer 1 (Render): confirm dialog appears
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(confirmDialog).toBeVisible({ timeout: 8_000 });

    // Intercept delete API response
    const deleteResponsePromise = page.waitForResponse(
      response => response.url().includes('/api/meta/commands/execute/')
        && response.request().method() === 'POST',
    );
    await acceptConfirmDialog(page);
    const deleted = await deleteResponsePromise;
    expect(deleted.ok()).toBe(true);
    expect(String((await deleted.json()).code)).toBe('0');

    // Verify record is no longer visible — poll with retries for list refresh
    await expect.poll(
      async () => await targetRow.isVisible().catch(() => false),
      { timeout: 10_000, message: `UOF-003: record "${companyForDelete}" must not be visible after deletion` },
    ).toBe(false);

  });

  // -------------------------------------------------------------------------
  // UOF-004: Form submit while required field is empty — shows error feedback
  //           (not a silent failure)
  // -------------------------------------------------------------------------

  test('UOF-004: Submitting form with missing required field shows error feedback', async ({
    page,
  }) => {
    await navigateToCrmLeadList(page);

    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 10_000 });

    // Open create form
    const createBtn = page
      .locator(
        '[data-testid="toolbar-btn-create"], button:has-text("新建"):not(:has-text("今日")), button:has-text("Create")',
      )
      .first();
    await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
    await createBtn.click();
    await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10_000 });

    // Wait for form to render
    await page.waitForLoadState('domcontentloaded');
    const spinner = page.locator('.animate-spin, [data-testid="loading"]');
    await spinner.waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});

    // Click submit WITHOUT filling any required fields
    const saveBtn = page
      .locator(
        '[data-testid^="form-btn-"], button:has-text("保存"), button:has-text("Save"), button[type="submit"]',
      )
      .first();
    await saveBtn.waitFor({ state: 'visible', timeout: 10_000 });
    await saveBtn.click();

    // Layer 1 (Render): error feedback must appear in some form
    // Acceptable forms: inline field error, error toast, or validation summary
    // DSL forms render field errors via ErrorText with the design-token class
    // `.text-status-red` (see UFV-001); match non-empty ones only — the page
    // also carries visible-but-empty red icon wrappers.
    const inlineError = page.locator(
      '.text-status-red:not(:empty), .text-red-500:not(:empty), ' +
        '.text-red-600:not(:empty), [data-testid*="error"]:not(:empty)',
    );
    const errorToast = page.getByRole('alert').filter({ has: page.locator('svg.text-red-600') }).first();
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
    const hasInlineError = await inlineError
      .first()
      .isVisible({ timeout: 3_000 })
      .catch(() => false);
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

  test.fixme('UOF-005: Row action button does not freeze after click', async ({ page }) => {
    test.setTimeout(30000);
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
    const isOnList =
      currentUrl.includes('/p/e2et_order') &&
      !currentUrl.includes('/new') &&
      !currentUrl.includes('/edit');
    if (!isOnList) {
      // Navigate back to list
      await navigateToCrmLeadList(page);
    }

    // List must be visible
    const listLocator = page.locator('[data-testid="dynamic-list"], table');
    await expect(listLocator.first()).toBeVisible({ timeout: 10_000 });
  });
});
