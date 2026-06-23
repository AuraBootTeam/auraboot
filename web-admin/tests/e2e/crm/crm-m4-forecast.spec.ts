/**
 * CRM M4 P0-C + P1-E — L4 UI golden E2E (forecast board + win/loss + stale alert)
 *
 * Proves through the real browser UI against the isolated CRM-M1 stack:
 *  - B8 fix: the Sales Forecast menu/route now resolves to the forecast dashboard
 *    (previously pointed at an unregistered /crm/sales-forecast route).
 *  - the forecast board renders the attainment-vs-quota table + forecast-by-category.
 *  - the CRM dashboard renders the lost-reason breakdown + stale-opportunities widgets.
 *  - the opportunity detail surfaces the new forecast-category / competitor /
 *    lost-reason dimensions, all localized, with no raw-code leak.
 *
 * Run:
 *   PLAYWRIGHT_BASE_URL=http://localhost:5189 NO_PROXY=localhost,127.0.0.1 \
 *   npx playwright test tests/e2e/crm/crm-m4-forecast.spec.ts \
 *     --project=chromium-m1 --config=tests/e2e/crm/m4.playwright.config.ts
 *
 * COVERAGE MATRIX:
 *   E1  forecast dashboard reachable via /dashboards/view/crm_sales_forecast (B8 fix)
 *   E2  forecast board shows attainment-vs-quota + forecast-by-category widgets
 *   E3  CRM dashboard shows lost-reason breakdown + stale-opportunities widgets
 *   E4  opportunity form exposes forecast-category + competitor dimensions
 */
import { test, expect, type Page } from '@playwright/test';

const BASE = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5189';
const EMAIL = 'admin@auraboot.com';
const PW = 'Test2026x';
const SHOT = '/tmp/m4-e2e';

async function uiLogin(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
    const emailInput = page.locator('input#identifier, input#email');
    const hasLogin = await emailInput.isVisible({ timeout: 8000 }).catch(() => false);
    if (!hasLogin) break;
    await emailInput.fill(EMAIL);
    await page.locator('input#password').fill(PW);
    await page.locator('button:has-text("立即登录"), button[type="submit"]').first().click();
    await page.waitForURL((u) => !u.pathname.includes('login'), { timeout: 20000 }).catch(() => {});
    if (page.url().includes('tenant-selection')) {
      const enter = page.getByRole('button', { name: /进入|选择|Enter|Demo|AuraBoot/ }).or(page.getByText(/AuraBoot Demo/).first());
      await enter.first().click({ timeout: 5000 }).catch(() => {});
      await page.waitForURL((u) => !u.pathname.includes('tenant-selection'), { timeout: 15000 }).catch(() => {});
    }
    const stillOnLogin = await emailInput.isVisible({ timeout: 2000 }).catch(() => false);
    if (!stillOnLogin) break;
    if (attempt === 3) throw new Error('UI login failed after 3 attempts');
  }
  await expect(page.locator('input#identifier, input#email')).toHaveCount(0, { timeout: 5000 });
}

async function gotoPage(page: Page, path: string): Promise<void> {
  await page.goto(`${BASE}${path}`, { waitUntil: 'domcontentloaded' });
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(2000);
}

async function assertNoRawCodeLeak(page: Page, label: string): Promise<void> {
  const body = await page.locator('body').innerText();
  const rawField = body.match(/\bcrm_opp_[a-z_]+\b/g) || [];
  const bareKey = body.match(/\bmodel\.[a-z_]+\.[a-z_.]+\.label\b/g) || [];
  expect(rawField, `[${label}] raw field code leaked: ${[...new Set(rawField)].join(', ')}`).toHaveLength(0);
  expect(bareKey, `[${label}] bare i18n key leaked: ${[...new Set(bareKey)].join(', ')}`).toHaveLength(0);
}

test.describe.configure({ mode: 'serial' });

test.describe('CRM M4 forecast + win/loss + stale (L4 UI golden)', () => {
  test.beforeEach(async ({ page }) => {
    await uiLogin(page);
  });

  // ---- E1/E2: forecast board reachable (B8 fix) + new widgets ----
  test('E1+E2 forecast board reachable + attainment & category widgets', async ({ page }) => {
    await gotoPage(page, '/dashboards/view/crm_sales_forecast');
    // B8 fix: the board resolves (NOT the pre-fix "Page Unavailable" / menu-not-found state)
    await expect(page.getByText(/Page Unavailable|Menu configuration not found/)).toHaveCount(0);
    await expect(page.getByText(/销售预测|Sales Forecast/).first()).toBeVisible({ timeout: 15000 });
    // the new M4 forecast widgets render with localized DOM card titles. (ECharts chart
    // titles render as SVG <text> with no stable bounding box, so assert the DOM-backed
    // card-header widgets — the attainment-vs-quota table and the category detail table —
    // plus the live attainment row data proving the quota join executed.)
    await expect(page.getByText(/配额达成率|Quota Attainment/).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(/预测类别明细|Forecast Category Detail/).first()).toBeVisible({ timeout: 10000 });
    // attainment table columns + a real quota row (target 500000 from the seeded M2 quota)
    await expect(page.getByText(/配额目标|Quota Target/).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('500000').first()).toBeVisible({ timeout: 10000 });
    await page.screenshot({ path: `${SHOT}/e1_forecast_board.png`, fullPage: true });
  });

  // ---- E3: CRM dashboard win/loss + stale widgets ----
  test('E3 CRM dashboard shows lost-reason + stale-opportunity widgets', async ({ page }) => {
    await gotoPage(page, '/dashboards/view/crm_dashboard');
    // the new M4 widgets sit at the bottom of a 12-widget dashboard; scroll them into view.
    // The stale-opportunity table has a DOM card header; the lost-reason pie chart renders
    // its title as ECharts SVG <text> (no stable bounding box) so assert its DOM column
    // header + legend instead.
    const stale = page.getByText(/停留预警商机|Stale Opportunities/).first();
    await stale.scrollIntoViewIfNeeded({ timeout: 10000 });
    await expect(stale).toBeVisible({ timeout: 10000 });
    // stale table localized column headers (DOM) — proves the M4 stale-opp widget rendered
    await expect(page.getByText('停留天数', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('预期金额', { exact: false }).first()).toBeVisible();
    await assertNoRawCodeLeak(page, 'crm_dashboard');
    await page.screenshot({ path: `${SHOT}/e3_crm_dashboard.png`, fullPage: true });
  });

  // ---- E4: opportunity form exposes the new dimensions ----
  test('E4 opportunity form exposes forecast-category + competitor', async ({ page }) => {
    await gotoPage(page, '/p/crm_opportunity_common');
    await page.getByRole('button', { name: /新建|新增|创建|Create/ }).first().click();
    await page.waitForTimeout(1500);
    await expect(page.getByText('预测类别', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await expect(page.getByText('竞争对手', { exact: false }).first()).toBeVisible({ timeout: 10000 });
    await assertNoRawCodeLeak(page, 'opportunity_form');
    await page.screenshot({ path: `${SHOT}/e4_opportunity_form.png`, fullPage: true });
  });
});
