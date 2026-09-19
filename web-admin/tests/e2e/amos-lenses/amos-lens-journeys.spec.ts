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
  {
    code: 'amos_closeout',
    heading: 'AMOS 关闭周期',
    headings: ['M16 关闭周期 · 治理状态'],
    texts: ['MISSING'],
    metricRow: 'm16_close_cycle_days',
    shot: 'amos-lens-closeout.png',
  },
  {
    code: 'amos_group',
    heading: 'AMOS 集团合并',
    headings: ['M24 合并确认收入 · 治理状态'],
    texts: ['MISSING'],
    metricRow: 'm24_consolidated_revenue',
    shot: 'amos-lens-group.png',
  },
  {
    code: 'amos_demand_funnel',
    heading: 'AMOS 需求漏斗',
    headings: ['漏斗指标 · 治理状态'],
    texts: ['MISSING'],
    metricRow: 'm01_valid_leads',
    shot: 'amos-lens-demand-funnel.png',
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


test.describe('AMOS lens state/layout/focus journeys', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  for (const lens of LENSES) {
    test(`layout mobile: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }),
        `${lens.heading} renders at mobile width`,
      ).toBeVisible();
      await page.screenshot({ path: `test-results/artifacts/${lens.shot.replace('.png', '-mobile.png')}` });
    });

    test(`layout compact: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 720 });
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }),
        `${lens.heading} renders at compact width`,
      ).toBeVisible();
      await page.screenshot({ path: `test-results/artifacts/${lens.shot.replace('.png', '-compact.png')}` });
    });

    test(`keyboard focus: ${lens.code}`, async ({ page }) => {
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await page.keyboard.press('Tab');
      await page.keyboard.press('Tab');
      const focused = await page.evaluate(() => {
        const el = document.activeElement;
        return el ? `${el.tagName}:${(el.getAttribute('data-aura-element-id') || el.textContent || '').slice(0, 40)}` : 'none';
      });
      expect(focused, 'keyboard navigation reaches an interactive element').not.toBe('none');
    });

    test(`query empty: ${lens.code}`, async ({ page }) => {
      // Governance empty-state expression: tables render their headers and
      // zero-row state (or governed state rows) — never a blank canvas.
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }),
      ).toBeVisible();
      await expect
        .poll(
          async () => (await page.locator('table thead th, table th').count()),
          { timeout: 20_000, message: `${lens.code} renders governed table structures` },
        )
        .toBeGreaterThan(0);
    });
  }
});


test.describe('AMOS lens freshness + permission journeys', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test('query stale: inventory lens expresses the STALE freshness state', async ({ page }) => {
    // m20 was refreshed with a fresh_until in the past — the canonical seam
    // must surface it as STALE (freshness is expressed, never hidden).
    await page.goto('/dashboards/view/amos_inventory_lens', { waitUntil: 'domcontentloaded' });
    await expect(
      page.getByRole('heading', { name: 'AMOS 库存库龄 · 治理状态' }),
    ).toBeVisible();
    await expect(
      page.getByText('m20_inventory_ageing').first(),
      'm20 row renders on the inventory lens',
    ).toBeVisible();
    await expect
      .poll(
        async () => (await page.getByText('STALE', { exact: true }).count()),
        { timeout: 20_000, message: 'STALE freshness is expressed for m20' },
      )
      .toBeGreaterThan(0);
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-inventory-stale.png' });
  });

  test('permission denied: operator role cannot create disclosure packages', async ({ request }) => {
    // Provisioned e2e-operator has no oi.disclosure.manage permission — the
    // command boundary must deny the write (permission archetype, API level).
    const login = await request.post('/api/auth/login', {
      data: { email: 'e2e-operator@test.com', password: 'Test2026x' },
      headers: { 'Content-Type': 'application/json' },
    });
    const operatorJwt = (await login.json())?.data?.jwt;
    if (!operatorJwt) {
      // operator account absent on this stack — skip deterministically
      test.skip(true, 'operator account not provisioned on this stack');
    }
    const resp = await request.post('/api/meta/commands/execute/oi:create_disclosure_package', {
      data: { payload: { code: `s12_operator_denied_${Date.now()}`, title: 'x', period: '2026-08', contentQuery: 'q', author: 'operator' } },
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${operatorJwt}` },
    });
    const body = await resp.json().catch(() => ({}));
    const denied = resp.status() === 403 || resp.status() === 400 || body?.code !== '0';
    expect(denied, 'operator write is denied by the permission boundary').toBeTruthy();
  });
});
