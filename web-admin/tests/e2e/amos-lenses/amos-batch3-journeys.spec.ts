import { test, expect } from '../../fixtures';

/**
 * AMOS lens journeys — P2-1 batch 3: remaining lens pages.
 * cash/credit/group: writer-blocked metrics expressed as honest MISSING.
 * settlement/forecast: business-out lenses with M13/M06 cards.
 */

const LENSES = [
  { code: 'amos_cash_lens', heading: 'AMOS 资金预测', mrow: 'm22_minimum_cash_balance', state: 'MISSING' },
  { code: 'amos_credit_lens', heading: 'AMOS 授信' },
  { code: 'amos_group', heading: 'AMOS 集团合并', mrow: 'm24_consolidated_revenue', state: 'MISSING' },
  { code: 'amos_settlement_lens', heading: 'AMOS 结算' },
  { code: 'amos_forecast_lens', heading: 'AMOS 预测与订单变更', mrow: 'm06_undelivered_amount', state: 'OBSERVED' },
];

const consoleErrors: string[] = [];

function isProductError(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504 |Loading chunk|entry\.client|Importing a module script failed|HMR|[Vv]ite|websocket/i.test(text);
}

test.describe('AMOS lens journeys (P2-1 batch 3)', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test.beforeEach(async ({ page }) => {
    consoleErrors.length = 0;
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
  });

  for (const lens of LENSES) {
    test(`lens journey: ${lens.code}`, async ({ page }) => {
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }).first(),
        `${lens.heading} heading renders`,
      ).toBeVisible({ timeout: 30_000 });

      if (lens.mrow) {
        await expect(
          page.getByText(lens.mrow).first(),
          `${lens.mrow} canonical row surfaces on ${lens.code}`,
        ).toBeVisible({ timeout: 15_000 });
      }

      await page.screenshot({ path: `test-results/artifacts/amos-b3-${lens.code}.png` });

      const productErrors = consoleErrors.filter(isProductError);
      expect(productErrors, `0 product console errors on ${lens.code}`).toEqual([]);
    });

    test(`lens mobile: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }).first(),
      ).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `test-results/artifacts/amos-b3-${lens.code}-mobile.png` });
    });
  }
});
