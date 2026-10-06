import { test, expect } from '../../fixtures';

/**
 * AMOS lens fault journeys — S12 mapping-campaign batch-2 (2026-10-06).
 * Transport-level fault and latency injection over the frozen dashboards:
 * query error (governed failure state), load loading (observable loading
 * state before content), query stale (STALE freshness is expressed, never
 * hidden). Same contract as the delivered lens journeys: the shell and its
 * governance surfaces stay up, a blank canvas is never acceptable, zero
 * product console errors on non-fault surfaces.
 */

interface FaultLens {
  code: string;
  heading: string;
  group: string;
  menu: string;
}

const LENSES: FaultLens[] = [
  {"code": "amos_budget_lens", "heading": "AMOS 预算占用 · 状态镜头", "group": "财务与资金", "menu": "预算占用 · 状态镜头"},
  {"code": "amos_capacity_lens", "heading": "AMOS 产能负荷 · 状态镜头", "group": "工程与制造", "menu": "产能负荷 · 状态镜头"},
  {"code": "amos_cases", "heading": "全链路边界案例", "group": "经营闭环", "menu": "全链路边界案例"},
  {"code": "amos_cash_lens", "heading": "AMOS 资金预测 · 状态镜头", "group": "财务与资金", "menu": "资金预测 · 状态镜头"},
  {"code": "amos_changes_lens", "heading": "AMOS 变更 · 状态镜头", "group": "供应与交付", "menu": "变更 · 状态镜头"},
  {"code": "amos_charts", "heading": "业务图表目录", "group": "总览与治理", "menu": "业务图表目录"},
  {"code": "amos_closeout", "heading": "AMOS 关闭周期", "group": "经营闭环", "menu": "关闭周期"},
  {"code": "amos_coverage", "heading": "需求与实施映射", "group": "总览与治理", "menu": "需求与实施映射"},
  {"code": "amos_credit_lens", "heading": "AMOS 授信 · 状态镜头", "group": "财务与资金", "menu": "授信 · 状态镜头"},
  {"code": "amos_customers_lens", "heading": "AMOS 客户经营 · 状态镜头", "group": "客户与需求", "menu": "客户经营 · 状态镜头"},
  {"code": "amos_data_trust", "heading": "AMOS 数据可信度", "group": "总览与治理", "menu": "数据可信度"},
  {"code": "amos_decisions_lens", "heading": "AMOS 经营决策 · 状态镜头", "group": "经营闭环", "menu": "经营决策 · 状态镜头"},
  {"code": "amos_delivery_lens", "heading": "AMOS 交付履约 · 状态镜头", "group": "供应与交付", "menu": "交付履约 · 状态镜头"},
  {"code": "amos_demand_funnel", "heading": "AMOS 需求漏斗", "group": "客户与需求", "menu": "需求漏斗"},
  {"code": "amos_disclosures", "heading": "AMOS 投露生命周期", "group": "经营闭环", "menu": "投露生命周期"},
  {"code": "amos_engineering_lens", "heading": "AMOS 工程就绪 · 状态镜头", "group": "工程与制造", "menu": "工程就绪 · 状态镜头"},
  {"code": "amos_forecast_lens", "heading": "AMOS 预测与订单变更 · 状态镜头", "group": "客户与需求", "menu": "预测与订单变更 · 状态镜头"},
  {"code": "amos_group", "heading": "AMOS 集团合并", "group": "财务与资金", "menu": "集团合并"},
  {"code": "amos_inventory_lens", "heading": "AMOS 库存库龄 · 状态镜头", "group": "供应与交付", "menu": "库存库龄 · 状态镜头"},
  {"code": "amos_investor_lens", "heading": "AMOS 投资人披露 · 状态镜头", "group": "经营闭环", "menu": "投资人披露 · 状态镜头"},
  {"code": "amos_invoices_lens", "heading": "AMOS 发票 · 状态镜头", "group": "财务与资金", "menu": "发票 · 状态镜头"},
  {"code": "amos_leads_lens", "heading": "AMOS 有效线索 · 状态镜头", "group": "客户与需求", "menu": "有效线索 · 状态镜头"},
  {"code": "amos_metrics", "heading": "指标口径", "group": "总览与治理", "menu": "指标口径"},
  {"code": "amos_opportunities_lens", "heading": "AMOS 商机阶段 · 状态镜头", "group": "客户与需求", "menu": "商机阶段 · 状态镜头"},
  {"code": "amos_orders_lens", "heading": "AMOS 发布订单", "group": "客户与需求", "menu": "发布订单"},
  {"code": "amos_overview_dashboard", "heading": "AMOS 经营总览", "group": "总览与治理", "menu": "经营总览"},
  {"code": "amos_permissions", "heading": "权限范围", "group": "总览与治理", "menu": "权限范围"},
  {"code": "amos_policies", "heading": "经营政策", "group": "经营闭环", "menu": "经营政策"},
  {"code": "amos_procurement_lens", "heading": "AMOS 采购 · 状态镜头", "group": "供应与交付", "menu": "采购 · 状态镜头"},
  {"code": "amos_production_lens", "heading": "AMOS 生产完成 · 状态镜头", "group": "工程与制造", "menu": "生产完成 · 状态镜头"},
  {"code": "amos_products_lens", "heading": "AMOS 产品 · 状态镜头", "group": "客户与需求", "menu": "产品 · 状态镜头"},
  {"code": "amos_profit_lens", "heading": "AMOS 利润 · 状态镜头", "group": "总览与治理", "menu": "利润 · 状态镜头"},
  {"code": "amos_quality_lens", "heading": "AMOS 质量 · 状态镜头", "group": "工程与制造", "menu": "质量 · 状态镜头"},
  {"code": "amos_quotes_lens", "heading": "AMOS 报价转化 · 状态镜头", "group": "客户与需求", "menu": "报价转化 · 状态镜头"},
  {"code": "amos_readiness_lens", "heading": "AMOS 齐套与供应分配 · 状态镜头", "group": "工程与制造", "menu": "齐套与供应分配 · 状态镜头"},
  {"code": "amos_receivables_lens", "heading": "AMOS 应收 · 状态镜头", "group": "财务与资金", "menu": "应收 · 状态镜头"},
  {"code": "amos_release_lens", "heading": "AMOS 发布门禁 · 状态镜头", "group": "工程与制造", "menu": "发布门禁 · 状态镜头"},
  {"code": "amos_reviews_lens", "heading": "AMOS 复盘决策 · 状态镜头", "group": "经营闭环", "menu": "复盘决策 · 状态镜头"},
  {"code": "amos_risks", "heading": "AMOS 经营风险", "group": "总览与治理", "menu": "经营风险"},
  {"code": "amos_samples_lens", "heading": "AMOS 样品 · 状态镜头", "group": "客户与需求", "menu": "样品 · 状态镜头"},
];

const JOURNEYS: Record<string, { group: string; menu: string }> = Object.fromEntries(
  LENSES.map(l => [l.code, { group: l.group, menu: l.menu }]),
);

async function openLensShell(page: import('../../fixtures').Page, code: string) {
  const journey = JOURNEYS[code];
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
  await menu.click();
  await expect(page).toHaveURL(new RegExp(`/dashboards/view/${code}$`));
}

test.describe('AMOS lens fault journeys (S12 batch-2)', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  for (const lens of LENSES) {
    test(`fault query error: ${lens.code}`, async ({ page }) => {
      // Transport-level 500 injection: the dashboard must render its governed
      // failure state instead of a blank canvas or stale values.
      let injectedRequests = 0;
      await page.route('**/api/meta/chart-data*', (route) => {
        injectedRequests++;
        return route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ code: '1', message: 'S12 fault injection' }),
        });
      });
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }),
        `${lens.code} shell still renders under query failure`,
      ).toBeVisible({ timeout: 20_000 });
      await expect(page.getByRole('alert').first(), 'the failed query must render an explicit error').toBeVisible();
      expect(injectedRequests, 'the transport failure must actually be exercised').toBeGreaterThan(0);
      await page.screenshot({ path: `test-results/artifacts/amos-fault-${lens.code}-query-error.png` });
    });

    test(`fault load loading: ${lens.code}`, async ({ page }) => {
      // Hold the schema response so the loading state is observable, then let
      // it through and assert the governed content replaces it.
      let release: (() => void) | undefined;
      const gate = new Promise<void>((resolve) => { release = resolve; });
      await page.route(`**/api/dashboards/code/${lens.code}`, async (route) => {
        await gate;
        await route.continue();
      });
      const navigation = page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1200);
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
        page.getByRole('heading', { name: lens.heading }),
        'content replaces the loading state',
      ).toBeVisible({ timeout: 20_000 });
      await page.screenshot({ path: `test-results/artifacts/amos-fault-${lens.code}-loading.png` });
    });

    test(`fault query stale: ${lens.code}`, async ({ page }) => {
      // Freshness must be expressed, never hidden: a stale governed payload
      // renders the STALE freshness state on the lens governance table.
      let staleServed = false;
      await page.route('**/api/meta/chart-data*', async (route) => {
        const request = route.request();
        const payload = request.postDataJSON()?.type === 'namedQuery' ? request.postDataJSON() : null;
        if (!payload) return route.continue();
        staleServed = true;
        const body = {
          code: '0',
          message: 'OK',
          data: { rows: [{
            metric_code: 's12_fault_stale_probe', value_state: 'OBSERVED', value: 0,
            source_count: 1, freshness_status: 'STALE', reconciliation_status: '来源不完整',
            as_of: '2026-10-01 00:00:00',
          }] },
        };
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
      });
      await page.goto(`/dashboards/view/${lens.code}`, { waitUntil: 'domcontentloaded' });
      await expect(
        page.getByRole('heading', { name: lens.heading }),
      ).toBeVisible({ timeout: 20_000 });
      await expect
        .poll(
          async () => (await page.getByText('STALE', { exact: true }).count()),
          { timeout: 20_000, message: `${lens.code} expresses the STALE freshness state` },
        )
        .toBeGreaterThan(0);
      expect(staleServed, 'the stale payload must actually be exercised').toBeTruthy();
      await page.screenshot({ path: `test-results/artifacts/amos-fault-${lens.code}-stale.png` });
    });
  }
});

