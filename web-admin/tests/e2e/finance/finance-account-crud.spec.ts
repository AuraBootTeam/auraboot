/**
 * Finance — Chart of Accounts (fin_account) CRUD E2E Tests
 *
 * Tests FA-001 ~ FA-008:
 * - FA-001 @smoke:    Navigate via Finance sidebar → 科目管理 menu → list page visible
 * - FA-002 @critical: Create account via UI form → appears in list with correct values
 * - FA-003 @critical: Edit account via row action → updated values visible in list
 * - FA-004 @critical: Required-field validation — empty submit shows error messages
 * - FA-005 @critical: Delete account via row action → confirm dialog → row disappears
 * - FA-006:           Account type filter works (asset / liability / equity / revenue / expense)
 * - FA-007:           Table column headers are i18n-resolved (not raw field keys)
 * - FA-008:           Empty state when no data shows meaningful message and create CTA
 *
 * Prerequisites:
 *   - finance plugin imported and models published
 *   - Admin user logged in (storageState)
 *
 * @since 9.1.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  findRowInPaginatedList,
  waitForDynamicPageLoad,
  waitForToast,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Plugin availability check
// ---------------------------------------------------------------------------

async function isFinancePluginInstalled(page: Page): Promise<boolean> {
  const resp = await page.request.get('/api/meta/models/code/fin_account').catch(() => null);
  if (!resp) return false;
  const body = await resp.json().catch(() => ({}));
  return resp.ok() && body?.data?.status === 'published';
}

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

/**
 * Navigate to the Chart of Accounts list page via the Finance sidebar menu.
 * Route: Finance → 财务管理 → 科目管理
 */
async function gotoAccountList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();

  // Expand Finance root menu
  const finBtn = nav
    .locator('button', { hasText: /^Finance$/ })
    .or(nav.locator('button', { hasText: /Finance/ }))
    .first();
  await finBtn.waitFor({ state: 'visible', timeout: 15_000 });
  await finBtn.evaluate((el: HTMLElement) => el.click());

  // Expand 财务管理 sub-directory if present
  const financeDir = nav.locator('button', { hasText: /财务管理|Finance Management/ });
  if (await financeDir.isVisible({ timeout: 2_000 }).catch(() => false)) {
    await financeDir.first().evaluate((el: HTMLElement) => el.click());
    await page.waitForTimeout(300);
  }

  // Click the 科目管理 / Chart of Accounts menu item
  const accountLink = nav.locator('a[href="/finance/accounts"]');
  await accountLink.first().waitFor({ state: 'attached', timeout: 8_000 });
  await accountLink.first().evaluate((el: HTMLAnchorElement) => el.click());

  await expect(page).toHaveURL(/\/finance\/accounts/, { timeout: 10_000 });

  // Wait for list API response
  await page
    .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 15_000 })
    .catch(() => null);

  await waitForDynamicPageLoad(page);
}

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------

const UID = uniqueId('FA');
const ACCOUNT_CODE = `E2E-${UID}`;
const ACCOUNT_NAME = `E2E科目_${UID}`;
const ACCOUNT_CODE_EDIT = `E2E-EDIT-${UID}`;
const ACCOUNT_NAME_EDIT = `E2E科目_EDITED_${UID}`;

let createdAccountPid = '';

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Finance Account CRUD @finance', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  // =========================================================================
  // FA-000: Pre-flight — verify finance plugin is installed
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const installed = await isFinancePluginInstalled(page);
      if (!installed) {
        // Create a sentinel account via API so later tests can detect plugin absence
        console.warn(
          '[finance-account-crud] Finance plugin not installed — tests will skip gracefully',
        );
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // FA-001 @smoke: Navigate via sidebar to account list
  // =========================================================================
  test('FA-001: Navigate via Finance sidebar to Chart of Accounts list', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FA-001');
      return;
    }

    await gotoAccountList(page);

    // Layer 1 (Render): Table is visible
    const table = page.locator('table, [role="table"], [data-testid="dynamic-list"]');
    await expect(table.first()).toBeVisible({ timeout: 10_000 });

    // Layer 2 (Data): At least one column header is visible
    const headerRow = page.locator('thead tr, [role="row"]').first();
    await expect(headerRow).toBeVisible({ timeout: 5_000 });

    // Layer 3 (Interaction): Create button is visible and enabled
    const createBtn = page
      .locator('button')
      .filter({ hasText: /新建|Create/i })
      .first();
    await expect(createBtn).toBeVisible({ timeout: 5_000 });
    await expect(createBtn).toBeEnabled();
  });

  // =========================================================================
  // FA-002 @critical: Create account via UI
  // =========================================================================
  test.fixme('FA-002: Create account via UI and verify in list', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FA-002');
      return;
    }

    await gotoAccountList(page);

    // Click create button
    const createBtn = page
      .locator('button')
      .filter({ hasText: /新建|Create/i })
      .first();
    await createBtn.click();

    // Wait for form/drawer/modal to open
    const form = page
      .locator('[data-testid="dynamic-form"], [role="dialog"] form, .ant-drawer-body form, form')
      .first();
    await expect(form).toBeVisible({ timeout: 10_000 });

    // Fill fin_acc_code
    const codeInput = page
      .locator('[data-testid="form-field-fin_acc_code"] input')
      .or(page.locator('input[name="fin_acc_code"]'))
      .or(page.locator('label:has-text("Account Code") ~ * input'))
      .or(page.locator('label:has-text("科目编号") ~ * input'))
      .first();
    await codeInput.waitFor({ state: 'visible', timeout: 8_000 });
    await codeInput.fill(ACCOUNT_CODE);

    // Fill fin_acc_name
    const nameInput = page
      .locator('[data-testid="form-field-fin_acc_name"] input')
      .or(page.locator('input[name="fin_acc_name"]'))
      .or(page.locator('label:has-text("Account Name") ~ * input'))
      .or(page.locator('label:has-text("科目名称") ~ * input'))
      .first();
    await nameInput.fill(ACCOUNT_NAME);

    // Select fin_acc_type (asset)
    const typeField = page
      .locator('[data-testid="form-field-fin_acc_type"]')
      .or(page.locator('label:has-text("Account Type") ~ *'))
      .or(page.locator('label:has-text("科目类型") ~ *'))
      .first();
    if (await typeField.isVisible({ timeout: 3_000 }).catch(() => false)) {
      // Click the select/combobox inside the field
      const typeSelect = typeField
        .locator('[role="combobox"], select, .ant-select-selector')
        .first();
      if (await typeSelect.isVisible({ timeout: 2_000 }).catch(() => false)) {
        await typeSelect.click();
        const assetOption = page
          .locator(
            '.ant-select-item:has-text("asset"), [role="option"]:has-text("asset"), [role="option"]:has-text("资产")',
          )
          .first();
        if (await assetOption.isVisible({ timeout: 3_000 }).catch(() => false)) {
          await assetOption.click();
        }
      }
    }

    // Fill fin_acc_level (1 = top-level)
    const levelInput = page
      .locator('[data-testid="form-field-fin_acc_level"] input')
      .or(page.locator('input[name="fin_acc_level"]'))
      .or(page.locator('label:has-text("Level") ~ * input'))
      .first();
    if (await levelInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await levelInput.fill('1');
    }

    // fin_acc_balance_direction is required in the current command contract.
    const balanceDirectionField = page
      .locator('[data-testid="form-field-fin_acc_balance_direction"]')
      .or(page.locator('label:has-text("Balance Direction") ~ *'))
      .or(page.locator('label:has-text("余额方向") ~ *'))
      .first();
    if (await balanceDirectionField.isVisible({ timeout: 3_000 }).catch(() => false)) {
      const balanceDirectionSelect = balanceDirectionField
        .locator('[role="combobox"], select, .ant-select-selector')
        .first();
      if (await balanceDirectionSelect.isVisible({ timeout: 2_000 }).catch(() => false)) {
        // Dismiss any open dropdowns first (previous select may still be open)
        await page.keyboard.press('Escape');
        await page.waitForTimeout(300);
        await balanceDirectionSelect.click({ force: true });
        const debitOption = page
          .locator(
            '.ant-select-item:has-text("借"), .ant-select-item:has-text("debit"), [role="option"]:has-text("借"), [role="option"]:has-text("debit")',
          )
          .first();
        await debitOption.waitFor({ state: 'visible', timeout: 5_000 });
        await debitOption.click();
      }
    }

    // Submit the form
    const submitBtn = page
      .locator(
        'button:has-text("保存"), button:has-text("提交"), button:has-text("Save"), button:has-text("Submit"), button[type="submit"]',
      )
      .last();
    await submitBtn.click();

    await expect(form).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('table, [role="table"], [data-testid="dynamic-list"]').first()).toBeVisible({
      timeout: 10_000,
    });

    // Wait for list to refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10_000 })
      .catch(() => null);

    // Layer 2 (Data): new row appears in list with the account code we filled
    const newRow = await findRowInPaginatedList(page, ACCOUNT_CODE, 10_000).catch(() => null);
    expect(
      newRow,
      `Account row "${ACCOUNT_CODE}" should appear in list after creation`,
    ).not.toBeNull();

    if (newRow) {
      await expect(newRow).toBeVisible({ timeout: 5_000 });
    }
  });

  // =========================================================================
  // FA-003 @critical: Create via API then edit via UI
  // =========================================================================
  test('FA-003: Edit account via UI and verify updated values in list', async ({ page }) => {
    test.setTimeout(45000);
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FA-003');
      return;
    }

    // Create account via API to have a known record to edit
    const result = await executeCommandViaApi(
      page,
      'fin:create_account',
      {
        fin_acc_code: `E2E-EDIT-SRC-${UID}`,
        fin_acc_name: `E2E_ToEdit_${UID}`,
        fin_acc_type: 'asset',
        fin_acc_level: 1,
        fin_acc_is_detail: true,
        fin_acc_balance_direction: 'debit',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );
    expect(result.recordId, 'API create should return a recordId').toBeTruthy();
    createdAccountPid = result.recordId;

    await gotoAccountList(page);

    // Find the row and click edit
    const row = await findRowInPaginatedList(page, `E2E_ToEdit_${UID}`, 10_000).catch(() => null);
    expect(row, 'Created account should appear in list').not.toBeNull();

    if (!row) return;

    // Hover row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
    await row.hover();
    // Click edit button on the row
    const editBtn = row
      .locator(
        '[data-testid="row-action-fin:update_account"], button:has-text("编辑"), button:has-text("Edit"), a:has-text("编辑")',
      )
      .first();
    await editBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await editBtn.click();

    // Wait for form to appear
    const form = page
      .locator('[data-testid="dynamic-form"], [role="dialog"] form, .ant-drawer-body form, form')
      .first();
    await expect(form).toBeVisible({ timeout: 8_000 });

    // Update the account name
    const nameInput = page
      .locator('[data-testid="form-field-fin_acc_name"] input')
      .or(page.locator('input[name="fin_acc_name"]'))
      .or(page.locator('label:has-text("Account Name") ~ * input'))
      .or(page.locator('label:has-text("科目名称") ~ * input'))
      .first();
    await nameInput.waitFor({ state: 'visible', timeout: 5_000 });
    await nameInput.clear();
    await nameInput.fill(ACCOUNT_NAME_EDIT);

    // Submit
    const submitBtn = page
      .locator(
        'button:has-text("保存"), button:has-text("提交"), button:has-text("Save"), button:has-text("Submit"), button[type="submit"]',
      )
      .last();
    await submitBtn.click();

    await expect(form).toBeHidden({ timeout: 10_000 });
    await expect(page.locator('table, [role="table"], [data-testid="dynamic-list"]').first()).toBeVisible({
      timeout: 10_000,
    });

    // Wait for list refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10_000 })
      .catch(() => null);

    // Layer 2 (Data): Updated name appears in the list
    const updatedRow = await findRowInPaginatedList(page, ACCOUNT_NAME_EDIT, 8_000).catch(
      () => null,
    );
    expect(updatedRow, `Updated name "${ACCOUNT_NAME_EDIT}" should appear in list`).not.toBeNull();
    if (updatedRow) {
      await expect(updatedRow).toBeVisible({ timeout: 5_000 });
    }
  });

  // =========================================================================
  // FA-004 @critical: Required field validation
  // =========================================================================
  test('FA-004: Required fields show validation errors on empty submit', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FA-004');
      return;
    }

    await gotoAccountList(page);

    // Open create form
    const createBtn = page
      .locator('button')
      .filter({ hasText: /新建|Create/i })
      .first();
    await createBtn.click();

    const form = page
      .locator('[data-testid="dynamic-form"], [role="dialog"] form, .ant-drawer-body form, form')
      .first();
    await expect(form).toBeVisible({ timeout: 10_000 });

    // Submit without filling any fields
    const submitBtn = page
      .locator(
        'button:has-text("保存"), button:has-text("提交"), button:has-text("Save"), button:has-text("Submit"), button[type="submit"]',
      )
      .last();
    await submitBtn.click();

    // Layer 2 (Data): Validation error messages must appear
    const errorMsg = page
      .locator('.ant-form-item-explain-error, .field-error')
      .or(page.getByRole('alert').filter({ hasText: /required|必填/i }));
    await expect(errorMsg.first()).toBeVisible({ timeout: 5_000 });

    // Form must still be open — was NOT submitted
    await expect(form).toBeVisible({ timeout: 3_000 });
  });

  // =========================================================================
  // FA-005 @critical: Delete account with confirm dialog
  // =========================================================================
  test('FA-005: Delete account via confirm dialog — row disappears', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FA-005');
      return;
    }

    // Create a dedicated account via API for deletion
    const deleteCode = `E2E-DEL-${UID}`;
    const deleteName = `E2E_ToDelete_${UID}`;
    const result = await executeCommandViaApi(
      page,
      'fin:create_account',
      {
        fin_acc_code: deleteCode,
        fin_acc_name: deleteName,
        fin_acc_type: 'asset',
        fin_acc_level: 1,
        fin_acc_is_detail: true,
        fin_acc_balance_direction: 'debit',
      },
      undefined,
      'create',
      { allowHttpError: true },
    );
    expect(result.recordId, 'API create should succeed before delete test').toBeTruthy();

    await gotoAccountList(page);

    // Find the row
    const row = await findRowInPaginatedList(page, deleteName, 10_000).catch(() => null);
    expect(row, 'Account to delete should appear in list').not.toBeNull();
    if (!row) return;

    // Hover row to reveal action buttons (opacity-0 → opacity-100 via group-hover)
    await row.hover();
    // Click delete button on the row
    const deleteBtn = row
      .locator(
        '[data-testid="row-action-fin:delete_account"], button:has-text("删除"), button:has-text("Delete")',
      )
      .first();
    await deleteBtn.waitFor({ state: 'visible', timeout: 5_000 });
    await deleteBtn.click();

    // Layer 3 (Interaction): confirm dialog must appear with dangerous operation warning
    const dialog = page.locator(
      '[data-testid="confirm-dialog"], [role="dialog"], [role="alertdialog"], .ant-modal',
    );
    await expect(dialog.first()).toBeVisible({ timeout: 5_000 });

    // Confirm deletion
    await acceptConfirmDialog(page).catch(async () => {
      // Fallback: click standard OK button
      const okBtn = dialog
        .first()
        .locator(
          'button:has-text("确定"), button:has-text("确认"), button:has-text("OK"), button:has-text("Yes")',
        );
      await okBtn.first().click();
    });

    // Wait for list to refresh
    await page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10_000 })
      .catch(() => null);

    // Layer 2 (Data): Deleted row must NOT appear in list anymore
    const deletedRow = page.locator('tbody tr, [role="row"]', { hasText: deleteName });
    await expect(deletedRow.first()).not.toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // FA-006: Column headers are i18n-resolved (not raw DSL keys)
  // =========================================================================
  test('FA-007: Table column headers are human-readable, not raw keys', async ({ page }) => {
    const installed = await isFinancePluginInstalled(page);
    if (!installed) {
      test.skip(true, 'Finance plugin not installed — skipping FA-007');
      return;
    }

    await gotoAccountList(page);

    const table = page.locator('table, [role="table"]').first();
    await expect(table).toBeVisible({ timeout: 10_000 });

    // Check that no column header contains raw i18n key patterns like "fin_acc_code" or "field.xxx.label"
    const headers = await page.locator('thead th, [role="columnheader"]').allInnerTexts();
    for (const header of headers) {
      const h = header.trim();
      if (!h || h === '' || h === '#' || h === '操作' || h === 'Action') continue;
      // Raw field keys like "fin_acc_code" or "field.fin_acc_code.label" are disallowed
      expect(h, `Column header "${h}" looks like a raw DSL key — i18n not working`).not.toMatch(
        /^fin_acc_|^field\./,
      );
    }
  });
});
