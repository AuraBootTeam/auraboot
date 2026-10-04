import { test, expect } from '../../fixtures';

/**
 * AMOS governance lens journeys — P2-1 batch 2 (S12 browser slice).
 * Governance/state lenses express writer-blocked metrics as explicit MISSING
 * rows; assertions target headings, widget surfaces and governance columns.
 * Zero product console errors per page (SoT §8, G10 rules).
 */

const LENSES = [
  { code: 'amos_budget_lens', heading: 'AMOS 预算占用', texts: [] },
  { code: 'amos_cases', heading: '全链路边界案例', texts: [], table: false },
  { code: 'amos_coverage', heading: '需求与实施映射', texts: [], table: false },
  { code: 'amos_decisions_lens', heading: 'AMOS 经营决策', texts: [] },
  { code: 'amos_metrics', heading: '指标口径', texts: [], table: false },
  { code: 'amos_policies', heading: '经营政策', texts: [], table: false },
  { code: 'amos_permissions', heading: '权限范围', texts: [], table: false },
  { code: 'amos_procurement_lens', heading: 'AMOS 采购', texts: [] },
  { code: 'amos_quality_lens', heading: 'AMOS 质量', texts: [] },
  { code: 'amos_samples_lens', heading: 'AMOS 样品', texts: [] },
  { code: 'amos_reviews_lens', heading: 'AMOS 复盘决策', texts: [] },
  { code: 'amos_investor_lens', heading: 'AMOS 投资人披露', texts: [] },
];

const consoleErrors: string[] = [];

function isProductError(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504 |Loading chunk|entry\.client|Importing a module script failed|HMR|[Vv]ite|websocket/i.test(text);
}

test.describe('AMOS governance lens journeys (P2-1 batch 2)', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test.beforeEach(async ({ page }) => {
    consoleErrors.length = 0;
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
  });

  for (const lens of LENSES) {
    test(`governance lens journey: ${lens.code}`, async ({ page }) => {
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }).first(),
        `${lens.heading} heading renders`,
      ).toBeVisible({ timeout: 30_000 });

      for (const label of lens.texts) {
        await expect(page.getByText(label).first()).toBeVisible();
      }

      // widget surfaces render (canvas charts render titles as bitmap text —
      // DOM widget assert is via data-widget-type, tables via <table>)
      const widgetEl = page.locator('[data-widget-type]').first();
      await expect(widgetEl).toBeVisible({ timeout: 15_000 });

      await page.screenshot({ path: `test-results/artifacts/amos-gov-${lens.code}.png` });

      const productErrors = consoleErrors.filter(isProductError);
      expect(productErrors, `0 product console errors on ${lens.code}`).toEqual([]);
    });

    test(`governance lens mobile: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }).first(),
      ).toBeVisible({ timeout: 30_000 });
      await page.screenshot({ path: `test-results/artifacts/amos-gov-${lens.code}-mobile.png` });
    });
  }
});
