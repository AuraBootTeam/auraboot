/**
 * Finance — Bank Reconciliation E2E Tests
 *
 * Tests FB-001 ~ FB-007: Bank Account, Bank Statement, and Reconciliation lifecycle:
 * - FB-001 @smoke: Navigate to 银行账户 list → table visible, i18n column headers
 * - FB-002 @critical: Created bank account appears in list with active status
 * - FB-003 @critical: Deactivate bank account → verify inactive via API + list
 * - FB-004 @critical: Reactivate bank account → verify active via API
 * - FB-005 @smoke: Navigate to 银行对账单 list → table visible
 * - FB-006 @critical: Created bank statement appears in list (verify via queryFilteredList)
 * - FB-007 @smoke: Navigate to 对账记录 list → table visible
 *
 * Prerequisites: finance plugin must be imported and all models published.
 *
 * @since 9.0.0
 */

import { test, expect, type Page } from '../../fixtures';
import {
  uniqueId,
  executeCommandViaApi,
  queryFilteredList,
  findRowInPaginatedList,
  todayStr,
  dateOffsetStr,
} from '../helpers/index';

/** Navigate to a Finance list page via correct menu URL, setting up waitForResponse BEFORE goto. */
async function gotoFinancePage(page: Page, menuPath: string, modelCode: string): Promise<void> {
  const listResponse = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${modelCode}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await page.goto(`http://localhost:5173${menuPath}`);
  await listResponse;
  await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('FB');

const BANK_ACCOUNT_DATA = {
  fin_ba_name: `Test Bank Account ${UID}`,
  fin_ba_bank_name: `Test Bank ${UID}`,
  fin_ba_account_no: `ACC${UID.slice(-8)}`,
  fin_ba_currency: 'cny',
  fin_ba_balance: 100000,
  fin_ba_description: `E2E test bank account ${UID}`,
};

const BANK_STATEMENT_DATA = {
  fin_bs_period_start: dateOffsetStr(-30),
  fin_bs_period_end: todayStr(),
  fin_bs_opening_balance: 100000,
  fin_bs_closing_balance: 120000,
  fin_bs_total_debit: 30000,
  fin_bs_total_credit: 50000,
  fin_bs_remark: `E2E bank statement remark ${UID}`,
};

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Finance — Bank Reconciliation', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let bankAccountId: string;
  let bankStatementId: string;

  // -------------------------------------------------------------------------
  // Setup: Create bank account and bank statement via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Create bank account
    const accountResult = await executeCommandViaApi(
      page,
      'fin:create_bank_account',
      BANK_ACCOUNT_DATA,
      undefined,
      'create',
    );
    bankAccountId = accountResult.recordId;

    // Create bank statement linked to the bank account
    const statementResult = await executeCommandViaApi(
      page,
      'fin:create_bank_statement',
      {
        fin_bs_bank_account_id: bankAccountId,
        ...BANK_STATEMENT_DATA,
      },
      undefined,
      'create',
    );
    bankStatementId = statementResult.recordId;

    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // FB-001 @smoke: Navigate to 银行账户 list
  // -------------------------------------------------------------------------

  test('FB-001 @smoke: Navigate to 银行账户 list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Click Finance root button
    const finBtn = nav.getByRole('button', { name: 'Finance' }).or(
      nav.getByRole('button', { name: '财务' })
    ).first();
    await finBtn.evaluate((el: HTMLElement) => el.click());

    // Click 财务管理 sub-directory
    const finMgmtBtn = nav.getByRole('button', { name: '财务管理' });
    await finMgmtBtn.evaluate((el: HTMLElement) => el.click());

    // Click 银行对账 sub-directory
    const bankReconBtn = nav.getByRole('button', { name: '银行对账' });
    await bankReconBtn.evaluate((el: HTMLElement) => el.click());

    // Click 银行账户 leaf link
    const bankAccountLink = nav.getByRole('link', { name: '银行账户' });
    await bankAccountLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list API response
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/fin_bank_account/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    // Verify table is visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });

    // Verify i18n: column headers should be Chinese, not raw field codes
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });
    const headerText = await headerRow.textContent();

    // Should NOT contain raw field code prefixes
    expect(headerText).not.toContain('fin_ba_');
    expect(headerText).not.toContain('fin_ba_');
  });

  // -------------------------------------------------------------------------
  // FB-002 @critical: Created bank account appears in list with active status
  // -------------------------------------------------------------------------

  test('FB-002 @critical: Created bank account appears in list with active status', async ({ page }) => {
    expect(bankAccountId).toBeTruthy();

    // Verify via API
    const records = await queryFilteredList(
      page,
      'fin_bank_account',
      'fin_ba_name',
      BANK_ACCOUNT_DATA.fin_ba_name,
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    // Status should be active (set automatically by create command)
    expect(record.fin_ba_status).toBe('active');

    // Verify in UI — set up waitForResponse BEFORE navigation to avoid race
    await gotoFinancePage(page, '/finance/bank-recon/accounts', 'fin_bank_account');

    const row = await findRowInPaginatedList(page, BANK_ACCOUNT_DATA.fin_ba_name);
    await expect(row).toBeVisible({ timeout: 10_000 });

    // Status text: Chinese (启用/激活/有效) or English (active)
    const rowText = await row.textContent();
    const hasActiveStatus =
      rowText?.includes('active') ||
      rowText?.includes('启用') ||
      rowText?.includes('激活') ||
      rowText?.includes('有效') ||
      rowText?.includes('正常');
    expect(hasActiveStatus).toBe(true);
  });

  // -------------------------------------------------------------------------
  // FB-003 @critical: Deactivate bank account → verify inactive via API + list
  // -------------------------------------------------------------------------

  test('FB-003 @critical: Deactivate bank account → verify inactive', async ({ page }) => {
    expect(bankAccountId).toBeTruthy();

    // Deactivate via API command
    await executeCommandViaApi(
      page,
      'fin:deactivate_bank_account',
      {},
      bankAccountId,
      'state_transition',
    );

    // Verify status changed via API
    const records = await queryFilteredList(
      page,
      'fin_bank_account',
      'fin_ba_name',
      BANK_ACCOUNT_DATA.fin_ba_name,
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    expect(record.fin_ba_status).toBe('inactive');

    // Verify in UI list — set up waitForResponse BEFORE navigation
    await gotoFinancePage(page, '/finance/bank-recon/accounts', 'fin_bank_account');

    const row = await findRowInPaginatedList(page, BANK_ACCOUNT_DATA.fin_ba_name);
    await expect(row).toBeVisible({ timeout: 10_000 });

    // Status text can be Chinese (停用/未激活) or English (inactive)
    const rowText = await row.textContent();
    const hasInactiveStatus =
      rowText?.includes('inactive') ||
      rowText?.includes('停用') ||
      rowText?.includes('未激活') ||
      rowText?.includes('禁用');
    expect(hasInactiveStatus).toBe(true);
  });

  // -------------------------------------------------------------------------
  // FB-004 @critical: Reactivate bank account → verify active via API
  // -------------------------------------------------------------------------

  test('FB-004 @critical: Reactivate bank account → verify active', async ({ page }) => {
    expect(bankAccountId).toBeTruthy();

    // Reactivate via API command
    await executeCommandViaApi(
      page,
      'fin:activate_bank_account',
      {},
      bankAccountId,
      'state_transition',
    );

    // Verify status restored via API
    const records = await queryFilteredList(
      page,
      'fin_bank_account',
      'fin_ba_name',
      BANK_ACCOUNT_DATA.fin_ba_name,
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    expect(record.fin_ba_status).toBe('active');
  });

  // -------------------------------------------------------------------------
  // FB-005 @smoke: Navigate to 银行对账单 list
  // -------------------------------------------------------------------------

  test('FB-005 @smoke: Navigate to 银行对账单 list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Click Finance root button
    const finBtn = nav.getByRole('button', { name: 'Finance' }).or(
      nav.getByRole('button', { name: '财务' })
    ).first();
    await finBtn.evaluate((el: HTMLElement) => el.click());

    // Click 财务管理 sub-directory
    const finMgmtBtn = nav.getByRole('button', { name: '财务管理' });
    await finMgmtBtn.evaluate((el: HTMLElement) => el.click());

    // Click 银行对账 sub-directory
    const bankReconBtn = nav.getByRole('button', { name: '银行对账' });
    await bankReconBtn.evaluate((el: HTMLElement) => el.click());

    // Click 银行对账单 leaf link
    const bankStatementLink = nav.getByRole('link', { name: '银行对账单' });
    await bankStatementLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list API response
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/fin_bank_statement/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    // Verify table is visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  // -------------------------------------------------------------------------
  // FB-006 @critical: Created bank statement appears in list
  // -------------------------------------------------------------------------

  test('FB-006 @critical: Created bank statement appears in list', async ({ page }) => {
    expect(bankStatementId).toBeTruthy();

    // Verify via API using queryFilteredList on the remark field
    const records = await queryFilteredList(
      page,
      'fin_bank_statement',
      'fin_bs_remark',
      BANK_STATEMENT_DATA.fin_bs_remark,
    );
    expect(records.length).toBeGreaterThan(0);

    const record = records[0] as Record<string, unknown>;
    // Statement should start as draft
    expect(record.fin_bs_status).toBe('draft');
    // Opening balance should match
    expect(Number(record.fin_bs_opening_balance)).toBe(BANK_STATEMENT_DATA.fin_bs_opening_balance);
  });

  // -------------------------------------------------------------------------
  // FB-007 @smoke: Navigate to 对账记录 list
  // -------------------------------------------------------------------------

  test('FB-007 @smoke: Navigate to 对账记录 list via sidebar menu', async ({ page }) => {
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');

    // Click Finance root button
    const finBtn = nav.getByRole('button', { name: 'Finance' }).or(
      nav.getByRole('button', { name: '财务' })
    ).first();
    await finBtn.evaluate((el: HTMLElement) => el.click());

    // Click 财务管理 sub-directory
    const finMgmtBtn = nav.getByRole('button', { name: '财务管理' });
    await finMgmtBtn.evaluate((el: HTMLElement) => el.click());

    // Click 银行对账 sub-directory
    const bankReconBtn = nav.getByRole('button', { name: '银行对账' });
    await bankReconBtn.evaluate((el: HTMLElement) => el.click());

    // Click 对账记录 leaf link
    const reconciliationLink = nav.getByRole('link', { name: '对账记录' });
    await reconciliationLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list API response
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/fin_reconciliation/list') && r.status() === 200,
      { timeout: 15_000 },
    );

    // Verify table is visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });
});
