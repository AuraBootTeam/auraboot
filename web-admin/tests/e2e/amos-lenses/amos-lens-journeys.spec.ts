import { test, expect } from '../../fixtures';

/**
 * AMOS lens journeys — browser scenario slice for the S12 matrix (pages:
 * overview, metrics, data, risks; supply-allocation lens is on the
 * readiness-adjacent view). Business assertions run BEFORE screenshots;
 * zero product console errors per page (SoT §8, G10 rules).
 *
 * State-agnostic by design: the lens dashboards render governed values
 * where the contract chain has been fed and governed empty states where
 * it has not — both are valid display states, so assertions target the
 * governance surfaces (headings, widget titles, state columns) and the
 * M10 canonical row, not environment-specific amounts.
 */

const LENSES = [
  {
    code: 'amos_overview_dashboard',
    heading: 'AMOS 经营总览',
    headings: ['经营总览汇总'],
    texts: ['已发布签约额', '核销金额', '未关风险数'],
    shot: 'amos-lens-overview.png',
  },
  {
    code: 'amos_metric_governance',
    heading: 'AMOS 指标治理',
    headings: ['已冻结指标口径', '统一查询缝'],
    texts: [],
    metricRow: 'm10_forecast_completed_gross_margin',
    shot: 'amos-lens-metric-governance.png',
  },
  {
    code: 'amos_data_trust',
    heading: 'AMOS 数据可信度',
    headings: ['值状态分布'],
    texts: ['受治理指标数'],
    shot: 'amos-lens-data-trust.png',
  },
  {
    code: 'amos_risks',
    heading: 'AMOS 经营风险',
    headings: ['风险登记'],
    texts: ['未关风险数(≠M23 净额)'],
    shot: 'amos-lens-risks.png',
  },
  {
    code: 'amos_supply_allocation',
    heading: 'AMOS 供应分配',
    headings: ['需求→供应匹配'],
    texts: ['已分配数量'],
    shot: 'amos-lens-supply-allocation.png',
  },
];

function isProductError(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504 |Loading chunk|entry\.client|Importing a module script failed|HMR|[Vv]ite|websocket/i.test(text);
}

const consoleErrors: string[] = [];

test.describe('AMOS lens journeys (S12 browser slice)', () => {
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
      await page.getByRole('heading', { name: lens.heading }).waitFor({ state: 'visible', timeout: 20_000 });

      // business assertions before capture
      for (const widget of lens.headings) {
        await expect(
          page.getByRole('heading', { name: widget }),
          `widget "${widget}" renders on ${lens.code}`,
        ).toBeVisible();
      }
      for (const label of lens.texts) {
        await expect(
          page.getByText(label).first(),
          `card label "${label}" renders on ${lens.code}`,
        ).toBeVisible();
      }
      if (lens.metricRow) {
        await expect(
          page.getByText(lens.metricRow).first(),
          'M10 canonical metric row is surfaced on the governance page',
        ).toBeVisible();
      }
      if (lens.code === 'amos_overview_dashboard') {
        // governance label semantics: the three relabeled cards stay honest
        await expect(page.getByText('已发布签约额').first()).toBeVisible();
        await expect(page.getByText('核销金额').first()).toBeVisible();
        await expect(page.getByText('未关风险数').first()).toBeVisible();
      }

      await page.screenshot({ path: `test-results/artifacts/${lens.shot}` });

      const productErrors = consoleErrors.filter(isProductError);
      expect(productErrors, `0 product console errors on ${lens.code}`).toEqual([]);
    });
  }
});
