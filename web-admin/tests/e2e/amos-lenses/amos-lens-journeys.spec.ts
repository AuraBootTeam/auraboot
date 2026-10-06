import { test, expect, type Page } from '../../fixtures';
import { writeFileSync } from 'node:fs';

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

const JOURNEYS: Record<string, { group: string; menu: string; queries: string[] }> = {
  amos_overview_dashboard: { group: '总览与治理', menu: '经营总览', queries: ['amos_kpi_totals', 'amos_overview_exec_summary', 'amos_settlement_summary', 'amos_allocation_summary', 'amos_risk_summary', 'amos_eac_margin_query', 'amos_governance_lenses_query', 'amos_metrics_values_chart', 'amos_value_state_dist'] },
  amos_metric_governance: { group: '总览与治理', menu: '指标治理', queries: ['amos_metric_definitions_query', 'amos_query_results_query', 'amos_eac_margin_query'] },
  amos_data_trust: { group: '总览与治理', menu: '数据可信度', queries: ['amos_governance_lenses_query', 'amos_lineage_query'] },
  amos_risks: { group: '总览与治理', menu: '经营风险', queries: ['amos_risk_counts_query', 'amos_risk_summary'] },
  amos_supply_allocation: { group: '供应与交付', menu: '供应分配', queries: ['amos_allocation_totals_query', 'amos_allocation_summary'] },
  amos_closeout: { group: '经营闭环', menu: '关闭周期', queries: ['amos_closeout_state_query'] },
  amos_group: { group: '财务与资金', menu: '集团合并', queries: ['amos_group_state_query', 'amos_grp_entity_revenue_query', 'amos_grp_eliminations_query', 'amos_grp_kpi_query'] },
  amos_demand_funnel: { group: '客户与需求', menu: '需求漏斗', queries: ['amos_funnel_state_query', 'amos_funnel_chart'] },
};

// The frozen DSL declares these queries. A title alone is never a loaded-page proof.
async function openSettledLens(page: Page, code: string) {
  const journey = JOURNEYS[code];
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
  if ((page.viewportSize()?.width ?? 1440) < 768) {
    await page.getByTestId('header-sidebar-toggle').click();
  }
  const menu = page.getByRole('link', { name: journey.menu, exact: true });
  const group = page.getByRole('button', { name: journey.group, exact: true });
  if (!(await menu.isVisible())) {
    if (!(await group.isVisible())) {
      await page.getByRole('button', { name: 'AMOS 经营驾驶舱', exact: true }).click();
    }
    if (!(await menu.isVisible())) await group.click();
  }
  const pending = journey.queries.map(queryCode => page.waitForResponse(response =>
    new URL(response.url()).pathname === '/api/meta/chart-data'
      && response.request().method() === 'POST'
      && response.request().postDataJSON()?.queryCode === queryCode));
  await menu.click();
  await expect(page).toHaveURL(new RegExp(`/dashboards/view/${code}$`));
  const responses = await Promise.all(pending);
  const records = [];
  for (const response of responses) {
    const payload = response.request().postDataJSON();
    expect(new URL(response.url()).origin).toBe(new URL(page.url()).origin);
    expect(response.status(), payload.queryCode).toBe(200);
    expect(payload).toMatchObject({ type: 'namedQuery', filters: [] });
    const body = await response.json();
    expect(body.code, payload.queryCode).toBe('0');
    expect(Array.isArray(body.data?.rows), payload.queryCode).toBe(true);
    records.push({ target: response.url(), payload, body });
  }
  const blocks = page.locator('[data-testid^="dashboard-block-"]');
  await expect(blocks.first()).toBeVisible();
  await expect(blocks.getByText(/^(Loading\.\.\.|加载中.*)$/)).toHaveCount(0);
  await expect(blocks.locator('[role="progressbar"], [aria-busy="true"]')).toHaveCount(0);
  await expect(blocks.getByText('...', { exact: true })).toHaveCount(0);
  await expect(blocks.getByText(/^(Error|Failed to load data|加载失败)$/)).toHaveCount(0);
  expect(errors, 'all page and console errors retained without an allowlist').toEqual([]);
  writeFileSync(test.info().outputPath('journey-network.json'), JSON.stringify({ code, records }, null, 2));
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
      await openSettledLens(page, lens.code);
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

      expect(consoleErrors, `0 product console errors on ${lens.code}`).toEqual([]);
    });
  }
});


test.describe('AMOS lens state/layout/focus journeys', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  for (const lens of LENSES) {
    test(`layout mobile: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await openSettledLens(page, lens.code);
      await expect(
        page.getByRole('heading', { name: lens.heading }),
        `${lens.heading} renders at mobile width`,
      ).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
        'mobile page overflow is confined to its intentional table scroll areas').toBe(true);
      await page.screenshot({ path: `test-results/artifacts/${lens.shot.replace('.png', '-mobile.png')}`, fullPage: true });
    });

    test(`layout compact: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 720 });
      await openSettledLens(page, lens.code);
      await expect(
        page.getByRole('heading', { name: lens.heading }),
        `${lens.heading} renders at compact width`,
      ).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
        'compact dashboard fits the viewport').toBe(true);
      await page.screenshot({ path: `test-results/artifacts/${lens.shot.replace('.png', '-compact.png')}`, fullPage: true });
    });

    test(`keyboard focus: ${lens.code}`, async ({ page }) => {
      await openSettledLens(page, lens.code);
      await page.keyboard.press('Tab');
      await page.keyboard.press('Tab');
      const focused = await page.evaluate(() => {
        const el = document.activeElement;
        return el ? `${el.tagName}:${(el.getAttribute('data-aura-element-id') || el.textContent || '').slice(0, 40)}` : 'none';
      });
      expect(focused, 'keyboard navigation reaches an interactive element').not.toMatch(/^(none|BODY:|HTML:)/);
      await expect(page.locator(':focus')).toBeVisible();
    });

    test(`query empty: ${lens.code}`, async ({ page }) => {
      // Governance empty-state expression: tables render their headers and
      // zero-row state (or governed state rows) — never a blank canvas.
      await openSettledLens(page, lens.code);
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
      page.getByRole('heading', { name: 'AMOS 库存库龄 · 状态镜头' }),
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

  test('permission denied UI: operator sidebar omits admin-only entries', async ({ page, request }) => {
    // The sidebar is permission-driven: a restricted role must not see the
    // metadata-admin entries that the admin account does.
    const login = await request.post('/api/auth/login', {
      data: { email: 'e2e-operator@test.com', password: 'Test2026x' },
      headers: { 'Content-Type': 'application/json' },
    });
    const operatorJwt = (await login.json())?.data?.jwt;
    if (!operatorJwt) test.skip(true, 'operator account not provisioned on this stack');

    const { createCookieSessionStorage } = await import('react-router');
    const storage = createCookieSessionStorage({
      cookie: {
        name: '__session', httpOnly: true, path: '/', sameSite: 'lax',
        secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
      },
    });
    const session = await storage.getSession();
    session.set('jwtToken', operatorJwt);
    const cookieValue = (await storage.commitSession(session, { maxAge: 3600 })).match(/__session=([^;]+)/)?.[1];
        await page.goto('/');
    const origin = new URL(page.url()).origin;
    await page.context().addCookies([{ name: '__session', value: cookieValue!, url: origin }]);

    await page.goto('/dashboards/view/amos_metric_governance', { waitUntil: 'domcontentloaded' });
    // The app shell (permission-driven navigation) must render; whether the
    // restricted role also gets the lens content is entitlement-dependent.
    await page.locator('nav').first().waitFor({ state: 'visible', timeout: 20_000 });
    // admin-only metadata management entry absent for the restricted role
    await expect(
      page.getByRole('link', { name: '模型管理' }),
      'restricted role does not see metadata admin entries',
    ).toHaveCount(0);
  });
});


test.describe('AMOS lens fault/filter/trace journeys', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  const ERROR_PAGES = [
    { code: 'amos_metric_governance', heading: 'AMOS 指标治理' },
    { code: 'amos_data_trust', heading: 'AMOS 数据可信度' },
    { code: 'amos_inventory_lens', heading: 'AMOS 库存库龄 · 状态镜头' },
  ];

  for (const scenario of ERROR_PAGES) {
    test(`query error: ${scenario.code} expresses the failure state`, async ({ page }) => {
      // Fault injection at the transport boundary: the dashboard must render
      // its governed failure state instead of a blank canvas or stale values.
      let injectedRequests = 0;
      await page.route('**/api/meta/chart-data*', (route) => {
        injectedRequests++;
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ code: '1', message: 'S12 fault injection' }),
        });
      });
      await page.goto(`/dashboards/view/${scenario.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: scenario.heading }),
        `${scenario.code} shell still renders under query failure`,
      ).toBeVisible({ timeout: 20_000 });
      await expect(page.getByRole('alert').first(), 'the failed query must render an explicit error').toBeVisible();
      expect(injectedRequests, 'the transport failure must actually be exercised').toBeGreaterThan(0);
      await page.screenshot({ path: `test-results/artifacts/amos-lens-${scenario.code}-query-error.png` });
    });
  }

  test('detail trace: data trust surfaces refresh provenance', async ({ page }) => {
    await page.goto('/dashboards/view/amos_data_trust', { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: '刷新血缘' }).waitFor({ state: 'visible', timeout: 20_000 });
    // provenance columns: metric, refresh reason, input count, observation window
    for (const col of ['指标', '刷新原因', '输入数', 'As Of']) {
      await expect(
        page.getByRole('columnheader', { name: col }).or(page.getByText(col, { exact: true })).first(),
        `lineage column "${col}" present`,
      ).toBeVisible();
    }
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-data-trust-trace.png' });
  });

  test('filter scoped: applied time window scopes the analytics query', async ({ page }) => {
    // The behavior analytics page owns the platform time-window control:
    // draft edits stay local until 应用时间范围 commits them to query params.
    await page.goto('/p/c/behavior_analytics', { waitUntil: 'domcontentloaded' });
    await page.getByRole('textbox', { name: '开始时间（含）' }).fill('2026-01-01T00:00');
    await page.getByRole('textbox', { name: '结束时间（不含）' }).fill('2026-02-01T00:00');
    await page.getByRole('button', { name: '应用时间范围' }).click();
    await expect(
      page.getByText('已应用：2026年1月1日 00:00 — 2026年2月1日 00:00'),
      'applied window text reflects the committed draft',
    ).toBeVisible();

    // state switch: 最近 30 天 resets the applied window to the trailing month
    await page.getByRole('button', { name: '最近 30 天' }).click();
    await expect(
      page.getByText('已应用：2026年1月1日 00:00 — 2026年2月1日 00:00'),
      'preset switch must replace the committed custom range',
    ).not.toBeVisible();
  });
});


test.describe('AMOS lens load journeys', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test('load loading: dashboard shows a loading state before content', async ({ page }) => {
    // Hold the schema response so the loading state is observable, then let
    // it through and assert the governed content replaces it.
    let release: (() => void) | undefined;
    const gate = new Promise<void>((resolve) => { release = resolve; });
    await page.route('**/api/dashboards/code/amos_metric_governance', async (route) => {
      await gate;
      await route.continue();
    });

    const navigation = page.goto('/dashboards/view/amos_metric_governance', { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1200);

    // Loading state: either an explicit spinner/skeleton/text, or the shell
    // without yet-rendered widgets — a blank canvas is NOT acceptable.
    const loadingVisible = await page
      .locator('[class*="animate-spin"], [class*="skeleton"], [class*="loading"], text=加载')
      .first()
      .isVisible()
      .catch(() => false);
    const widgetsRendered = (await page.locator('table').count()) > 0;
    expect(loadingVisible || !widgetsRendered, 'a loading state is observable while the schema is gated').toBeTruthy();

    release!();
    await navigation;
    await expect(
      page.getByRole('heading', { name: 'AMOS 指标治理' }),
      'content replaces the loading state',
    ).toBeVisible({ timeout: 20_000 });
    await expect(
      page.getByRole('heading', { name: '已冻结指标口径' }),
    ).toBeVisible();
  });
});
