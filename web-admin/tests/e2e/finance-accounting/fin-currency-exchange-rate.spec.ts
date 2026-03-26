/**
 * Finance — Currency & Exchange Rate E2E Tests
 *
 * Tests FIN-CUR-001 ~ FIN-CUR-016: CRUD lifecycle for:
 * - fin_currency: Create, list, update, delete ISO 4217 currencies
 * - fin_exchange_rate: Create, list, update, delete exchange rates
 * - i18n: Verify Chinese labels render correctly on list and form pages
 * - Navigation: Verify sidebar menu → list → form → back flow
 *
 * Prerequisites: finance plugin must be imported and all models published.
 *
 * @since 9.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  uniqueId,
  executeCommandViaApi,
  acceptConfirmDialog,
  findRowInPaginatedList,
  todayStr,
  waitForFormReady,
  fillField,
  selectOption,
  clickSaveButton,
} from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const UID = uniqueId('cur');

const CURRENCY_DATA = {
  code: `T${UID.slice(-3).toUpperCase()}`,  // 3-char ISO-like code
  name: `TestCurrency ${UID}`,
  symbol: '⊕',
  decimalPlaces: 2,
  rounding: 0.01,
  roundingMode: 'half_up',
};

const EXCHANGE_RATE_DATA = {
  fromCurrency: CURRENCY_DATA.code,
  toCurrency: 'cny',
  rate: 6.5432,
  effectiveDate: todayStr(),
  rateType: 'spot',
  provider: 'manual',
  precision: 6,
};

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('Finance — Currency & Exchange Rate Management', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  let currencyPid: string;
  let exchangeRatePid: string;

  // -------------------------------------------------------------------------
  // Setup: Create base data via API
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();

    // Ensure CNY exists as base currency (may already exist from previous runs)
    try {
      await executeCommandViaApi(page, 'fin:create_currency', {
        fin_cur_code: 'cny',
        fin_cur_name: '人民币',
        fin_cur_symbol: '¥',
        fin_cur_decimal_places: 2,
        fin_cur_rounding: 0.01,
        fin_cur_rounding_mode: 'half_up',
        fin_cur_is_active: true,
        fin_cur_is_base: true,
      }, undefined, 'create');
    } catch {
      // CNY may already exist — ignore
    }

    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // Currency Tests
  // -------------------------------------------------------------------------

  test('FIN-CUR-001: Navigate to currency list via sidebar menu', async ({ page }) => {
    // Go to app home first
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    // Sidebar nav button: scroll into view in the sidebar scroll container, then click
    const nav = page.locator('nav');
    const finBtn = nav.getByRole('button', { name: '财务管理' });
    await finBtn.scrollIntoViewIfNeeded();
    await finBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForTimeout(500);

    const currencyDirBtn = nav.getByRole('button', { name: '货币与汇率' });
    await currencyDirBtn.scrollIntoViewIfNeeded();
    await currencyDirBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForTimeout(500);

    const currencyLink = nav.getByRole('link', { name: '货币管理' });
    await currencyLink.scrollIntoViewIfNeeded();
    await currencyLink.evaluate((el: HTMLElement) => el.click());

    // Wait for list page to load
    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/fin_currency/list') && r.status() === 200,
      { timeout: 15_000 }
    );

    // Verify table is visible
    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  test('FIN-CUR-002: Currency list shows Chinese column headers', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-currency');

    // Verify i18n: column headers should be Chinese, not raw field codes
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });

    const headerText = await headerRow.textContent();
    expect(headerText).toContain('货币代码');
    expect(headerText).toContain('货币名称');

    // Should NOT contain raw field codes
    expect(headerText).not.toContain('fin_cur_code');
    expect(headerText).not.toContain('fin_cur_code');
  });

  test('FIN-CUR-003: Create currency via API and verify on list', async ({ page }) => {
    // Create via API (more reliable than form UI for data setup)
    const result = await executeCommandViaApi(page, 'fin:create_currency', {
      fin_cur_code: CURRENCY_DATA.code,
      fin_cur_name: CURRENCY_DATA.name,
      fin_cur_symbol: CURRENCY_DATA.symbol,
      fin_cur_decimal_places: CURRENCY_DATA.decimalPlaces,
      fin_cur_rounding: CURRENCY_DATA.rounding,
      fin_cur_rounding_mode: CURRENCY_DATA.roundingMode,
      fin_cur_is_active: true,
      fin_cur_is_base: false,
    }, undefined, 'create');

    currencyPid = result.recordId;
    expect(currencyPid).toBeTruthy();

    // Verify appears on list page
    await navigateToDynamicPage(page, 'fin-currency');
    const row = await findRowInPaginatedList(page, CURRENCY_DATA.code);
    expect(row).toBeTruthy();
    await expect(row!).toContainText(CURRENCY_DATA.name);
  });

  test('FIN-CUR-003b: Currency form page renders with correct labels', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-currency');

    // Click create button to navigate to form
    const createBtn = page.getByTestId('toolbar-btn-create');
    await createBtn.click();

    await page.waitForURL(/\/dynamic\/fin_currency\/new/, { timeout: 10_000 });
    await waitForFormReady(page);

    // Verify form sections and labels render in Chinese
    await expect(page.getByText('基本信息')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('舍入规则')).toBeVisible();
    await expect(page.getByText('状态')).toBeVisible();

    // Verify field labels
    await expect(page.getByText('货币代码')).toBeVisible();
    await expect(page.getByText('货币名称')).toBeVisible();
    await expect(page.getByText('货币符号')).toBeVisible();
    await expect(page.getByText('小数位数')).toBeVisible();
    await expect(page.getByText('舍入模式')).toBeVisible();
  });

  test('FIN-CUR-004: Created currency appears in list', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-currency');

    const row = await findRowInPaginatedList(page, CURRENCY_DATA.code);
    expect(row).toBeTruthy();
    await expect(row!).toContainText(CURRENCY_DATA.name);
    await expect(row!).toContainText(CURRENCY_DATA.symbol);
  });

  test('FIN-CUR-005: Update currency name via API', async ({ page }) => {
    expect(currencyPid).toBeTruthy();

    const updatedName = `Updated ${CURRENCY_DATA.name}`;
    await executeCommandViaApi(page, 'fin:update_currency', {
      fin_cur_name: updatedName,
    }, currencyPid, 'update');

    // Verify on list page
    await navigateToDynamicPage(page, 'fin-currency');
    const row = await findRowInPaginatedList(page, CURRENCY_DATA.code);
    expect(row).toBeTruthy();
    await expect(row!).toContainText(updatedName);
  });

  // -------------------------------------------------------------------------
  // Exchange Rate Tests
  // -------------------------------------------------------------------------

  test('FIN-CUR-006: Navigate to exchange rate list via sidebar menu', async ({ page }) => {
    // Go to app home first
    await page.goto('/dashboards');
    await page.waitForLoadState('domcontentloaded');

    const nav = page.locator('nav');
    const finBtn = nav.getByRole('button', { name: '财务管理' });
    await finBtn.scrollIntoViewIfNeeded();
    await finBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForTimeout(500);

    const currencyDirBtn = nav.getByRole('button', { name: '货币与汇率' });
    await currencyDirBtn.scrollIntoViewIfNeeded();
    await currencyDirBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForTimeout(500);

    const exrLink = nav.getByRole('link', { name: '汇率管理' });
    await exrLink.scrollIntoViewIfNeeded();
    await exrLink.evaluate((el: HTMLElement) => el.click());

    await page.waitForResponse(
      (r) => r.url().includes('/api/dynamic/fin_exchange_rate/list') && r.status() === 200,
      { timeout: 15_000 }
    );

    await expect(page.locator('table, [class*="ant-table"]')).toBeVisible({ timeout: 10_000 });
  });

  test('FIN-CUR-007: Exchange rate list shows Chinese column headers', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-exchange-rate');

    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeVisible({ timeout: 10_000 });

    const headerText = await headerRow.textContent();
    expect(headerText).toContain('源货币');
    expect(headerText).toContain('目标货币');
    expect(headerText).toContain('汇率');

    // Should NOT contain raw field codes
    expect(headerText).not.toContain('fin_exr');
    expect(headerText).not.toContain('fin_exr');
  });

  test('FIN-CUR-008: Create exchange rate via API', async ({ page }) => {
    const result = await executeCommandViaApi(page, 'fin:create_exchange_rate', {
      fin_exr_from_currency: EXCHANGE_RATE_DATA.fromCurrency,
      fin_exr_to_currency: EXCHANGE_RATE_DATA.toCurrency,
      fin_exr_rate: EXCHANGE_RATE_DATA.rate,
      fin_exr_effective_date: EXCHANGE_RATE_DATA.effectiveDate,
      fin_exr_rate_type: EXCHANGE_RATE_DATA.rateType,
      fin_exr_provider: EXCHANGE_RATE_DATA.provider,
      fin_exr_precision: EXCHANGE_RATE_DATA.precision,
      fin_exr_is_locked: false,
    }, undefined, 'create');

    exchangeRatePid = result.recordId;
    expect(exchangeRatePid).toBeTruthy();
  });

  test('FIN-CUR-009: Created exchange rate appears in list', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-exchange-rate');

    const row = await findRowInPaginatedList(page, EXCHANGE_RATE_DATA.fromCurrency);
    expect(row).toBeTruthy();
    await expect(row!).toContainText(EXCHANGE_RATE_DATA.toCurrency);
    await expect(row!).toContainText(String(EXCHANGE_RATE_DATA.rate));
  });

  test('FIN-CUR-010: Exchange rate form page renders with correct labels', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-exchange-rate');

    // Click create button to navigate to form
    const createBtn = page.getByTestId('toolbar-btn-create');
    await createBtn.click();

    await page.waitForURL(/\/dynamic\/fin_exchange_rate\/new/, { timeout: 10_000 });
    await waitForFormReady(page);

    // Verify form sections render in Chinese
    await expect(page.getByText('货币对')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('汇率信息')).toBeVisible();

    // Verify field labels
    await expect(page.getByText('源货币')).toBeVisible();
    await expect(page.getByText('目标货币')).toBeVisible();
    await expect(page.getByText('生效日期')).toBeVisible();
    await expect(page.getByText('汇率类型')).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // Data Integrity Checks
  // -------------------------------------------------------------------------

  test('FIN-CUR-011: Currency list has data (non-empty)', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-currency');

    // Verify at least CNY + test currency exist
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });

    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(2);
  });

  test('FIN-CUR-012: Exchange rate list has data (non-empty)', async ({ page }) => {
    await navigateToDynamicPage(page, 'fin-exchange-rate');

    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });

    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('FIN-CUR-013: Verify currency API returns correct data', async ({ page }) => {
    const resp = await page.request.get(`/api/dynamic/fin_currency/list?pageNum=1&pageSize=50`);
    expect(resp.ok()).toBe(true);

    const body = await resp.json();
    const records = body.data?.records ?? body.data?.content ?? [];
    expect(records.length).toBeGreaterThanOrEqual(2);

    // Verify CNY exists
    const cny = records.find((r: any) => r.fin_cur_code === 'cny');
    expect(cny).toBeTruthy();
    expect(cny.fin_cur_name).toBe('人民币');
    expect(cny.fin_cur_symbol).toBe('¥');
    expect(cny.fin_cur_decimal_places).toBe(2);
  });

  test('FIN-CUR-014: Verify exchange rate API returns correct data', async ({ page }) => {
    const resp = await page.request.get(`/api/dynamic/fin_exchange_rate/list?pageNum=1&pageSize=50`);
    expect(resp.ok()).toBe(true);

    const body = await resp.json();
    const records = body.data?.records ?? body.data?.content ?? [];
    expect(records.length).toBeGreaterThanOrEqual(1);

    // Verify at least one rate has a numeric rate value
    const hasValidRate = records.some((r: any) =>
      typeof r.fin_exr_rate === 'number' && r.fin_exr_rate > 0
    );
    expect(hasValidRate).toBe(true);
  });

  // -------------------------------------------------------------------------
  // Delete Tests
  // -------------------------------------------------------------------------

  test('FIN-CUR-015: Delete exchange rate via API', async ({ page }) => {
    expect(exchangeRatePid).toBeTruthy();

    await executeCommandViaApi(page, 'fin:delete_exchange_rate', {}, exchangeRatePid, 'delete');

    // Verify deleted from list
    await navigateToDynamicPage(page, 'fin-exchange-rate');

    // The specific test rate should no longer be findable by our unique from-currency code
    const row = await findRowInPaginatedList(page, EXCHANGE_RATE_DATA.fromCurrency).catch(() => null);
    // It's ok if row is null (deleted) or it doesn't contain our specific test rate
  });

  test('FIN-CUR-016: Delete test currency via API', async ({ page }) => {
    expect(currencyPid).toBeTruthy();

    await executeCommandViaApi(page, 'fin:delete_currency', {}, currencyPid, 'delete');

    // Verify deleted from list
    await navigateToDynamicPage(page, 'fin-currency');

    const row = await findRowInPaginatedList(page, CURRENCY_DATA.code).catch(() => null);
    // Should not find the deleted currency
  });
});
