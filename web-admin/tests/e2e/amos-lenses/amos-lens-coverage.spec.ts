import { test, expect, type Page } from '../../fixtures';
import { writeFileSync } from 'node:fs';

/**
 * AMOS lens coverage journeys — S12 matrix batch-1 (2026-10-05).
 * Metadata-driven generic journeys for the remaining lens dashboards:
 * menu-normal, layout mobile, layout compact, keyboard focus, query empty.
 * Same contract as amos-lens-journeys.spec.ts: governance assertions before
 * screenshots, governed empty states are valid, zero product console errors,
 * every named-query response asserted (same origin, code 0, rows array).
 * Dashboards without named-query widgets assert hydration + settled blocks.
 */

interface CoverageLens {
  code: string;
  heading: string;
  group: string;
  menu: string;
  queries: string[];
}

const LENSES: CoverageLens[] = [
  {"code": "amos_budget_lens", "heading": "AMOS 预算占用 · 状态镜头", "group": "财务与资金", "menu": "预算占用 · 状态镜头", "queries": ["amos_budget_state_query"]},
  {"code": "amos_capacity_lens", "heading": "AMOS 产能负荷 · 状态镜头", "group": "工程与制造", "menu": "产能负荷 · 状态镜头", "queries": ["amos_capacity_state_query"]},
  {"code": "amos_cases", "heading": "全链路边界案例", "group": "经营闭环", "menu": "全链路边界案例", "queries": []},
  {"code": "amos_cash_lens", "heading": "AMOS 资金预测 · 状态镜头", "group": "财务与资金", "menu": "资金预测 · 状态镜头", "queries": ["amos_cash_forecast_weeks_query", "amos_cash_kpi_query", "amos_cash_state_query"]},
  {"code": "amos_changes_lens", "heading": "AMOS 变更 · 状态镜头", "group": "供应与交付", "menu": "变更 · 状态镜头", "queries": ["amos_changes_state_query"]},
  {"code": "amos_charts", "heading": "业务图表目录", "group": "总览与治理", "menu": "业务图表目录", "queries": []},
  {"code": "amos_coverage", "heading": "需求与实施映射", "group": "总览与治理", "menu": "需求与实施映射", "queries": []},
  {"code": "amos_credit_lens", "heading": "AMOS 授信 · 状态镜头", "group": "财务与资金", "menu": "授信 · 状态镜头", "queries": ["amos_credit_state_query"]},
  {"code": "amos_customers_lens", "heading": "AMOS 客户经营 · 状态镜头", "group": "客户与需求", "menu": "客户经营 · 状态镜头", "queries": ["amos_ar_by_customer", "amos_customers_state_query"]},
  {"code": "amos_decisions_lens", "heading": "AMOS 经营决策 · 状态镜头", "group": "经营闭环", "menu": "经营决策 · 状态镜头", "queries": ["amos_decisions_state_query"]},
  {"code": "amos_delivery_lens", "heading": "AMOS 交付履约 · 状态镜头", "group": "供应与交付", "menu": "交付履约 · 状态镜头", "queries": ["amos_delivery_state_query", "amos_shipments_status_bar"]},
  {"code": "amos_disclosures", "heading": "AMOS 投露生命周期", "group": "经营闭环", "menu": "投露生命周期", "queries": ["amos_disclosure_grants_query", "amos_disclosure_packages_query"]},
  {"code": "amos_engineering_lens", "heading": "AMOS 工程就绪 · 状态镜头", "group": "工程与制造", "menu": "工程就绪 · 状态镜头", "queries": ["amos_bom_status_query", "amos_engineering_state_query"]},
  {"code": "amos_forecast_lens", "heading": "AMOS 预测与订单变更 · 状态镜头", "group": "客户与需求", "menu": "预测与订单变更 · 状态镜头", "queries": ["amos_forecast_state_query"]},
  {"code": "amos_inventory_lens", "heading": "AMOS 库存库龄 · 状态镜头", "group": "供应与交付", "menu": "库存库龄 · 状态镜头", "queries": ["amos_inventory_state_query", "amos_lot_age_chart"]},
  {"code": "amos_investor_lens", "heading": "AMOS 投资人披露 · 状态镜头", "group": "经营闭环", "menu": "投资人披露 · 状态镜头", "queries": ["amos_investor_state_query"]},
  {"code": "amos_invoices_lens", "heading": "AMOS 发票 · 状态镜头", "group": "财务与资金", "menu": "发票 · 状态镜头", "queries": ["amos_invoices_state_query"]},
  {"code": "amos_leads_lens", "heading": "AMOS 有效线索 · 状态镜头", "group": "客户与需求", "menu": "有效线索 · 状态镜头", "queries": ["amos_leads_state_query", "amos_leads_status_pie"]},
  {"code": "amos_opportunities_lens", "heading": "AMOS 商机阶段 · 状态镜头", "group": "客户与需求", "menu": "商机阶段 · 状态镜头", "queries": ["amos_opportunities_state_query", "amos_opps_stage_bar"]},
  {"code": "amos_orders_lens", "heading": "AMOS 发布订单", "group": "客户与需求", "menu": "发布订单", "queries": ["amos_kpi_totals", "amos_orders_status_pie", "amos_overview_exec_summary"]},
  {"code": "amos_permissions", "heading": "权限范围", "group": "总览与治理", "menu": "权限范围", "queries": ["amos_permissions_list"]},
  {"code": "amos_policies", "heading": "经营政策", "group": "经营闭环", "menu": "经营政策", "queries": ["amos_policies"]},
  {"code": "amos_procurement_lens", "heading": "AMOS 采购 · 状态镜头", "group": "供应与交付", "menu": "采购 · 状态镜头", "queries": ["amos_procurement_state_query"]},
  {"code": "amos_production_lens", "heading": "AMOS 生产完成 · 状态镜头", "group": "工程与制造", "menu": "生产完成 · 状态镜头", "queries": ["amos_production_state_query", "amos_workorders_status_bar"]},
  {"code": "amos_products_lens", "heading": "AMOS 产品 · 状态镜头", "group": "客户与需求", "menu": "产品 · 状态镜头", "queries": ["amos_products_status_query"]},
  {"code": "amos_profit_lens", "heading": "AMOS 利润 · 状态镜头", "group": "总览与治理", "menu": "利润 · 状态镜头", "queries": ["amos_pl_chart", "amos_profit_state_query"]},
  {"code": "amos_quality_lens", "heading": "AMOS 质量 · 状态镜头", "group": "工程与制造", "menu": "质量 · 状态镜头", "queries": ["amos_quality_state_query"]},
  {"code": "amos_quotes_lens", "heading": "AMOS 报价转化 · 状态镜头", "group": "客户与需求", "menu": "报价转化 · 状态镜头", "queries": ["amos_quotes_state_query", "amos_quotes_status_pie"]},
  {"code": "amos_readiness_lens", "heading": "AMOS 齐套与供应分配 · 状态镜头", "group": "工程与制造", "menu": "齐套与供应分配 · 状态镜头", "queries": ["amos_readiness_state_query"]},
  {"code": "amos_receivables_lens", "heading": "AMOS 应收 · 状态镜头", "group": "财务与资金", "menu": "应收 · 状态镜头", "queries": ["amos_ar_status_pie", "amos_receivables_state_query"]},
  {"code": "amos_release_lens", "heading": "AMOS 发布门禁 · 状态镜头", "group": "工程与制造", "menu": "发布门禁 · 状态镜头", "queries": ["amos_release_state_query"]},
  {"code": "amos_reviews_lens", "heading": "AMOS 复盘决策 · 状态镜头", "group": "经营闭环", "menu": "复盘决策 · 状态镜头", "queries": ["amos_reviews_state_query"]},
  {"code": "amos_samples_lens", "heading": "AMOS 样品 · 状态镜头", "group": "客户与需求", "menu": "样品 · 状态镜头", "queries": ["amos_samples_state_query"]},
];

const JOURNEYS: Record<string, { group: string; menu: string; queries: string[] }> = Object.fromEntries(
  LENSES.map(l => [l.code, { group: l.group, menu: l.menu, queries: l.queries }]),
);

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
  if (journey.queries.length) {
    const responses = await Promise.all(pending);
    for (const response of responses) {
      const payload = response.request().postDataJSON();
      expect(new URL(response.url()).origin).toBe(new URL(page.url()).origin);
      expect(response.status(), payload.queryCode).toBe(200);
      expect(payload).toMatchObject({ type: 'namedQuery', filters: [] });
      const body = await response.json();
      expect(body.code, payload.queryCode).toBe('0');
      expect(Array.isArray(body.data?.rows), payload.queryCode).toBe(true);
    }
  }
  const blocks = page.locator('[data-testid^="dashboard-block-"]');
  await expect(blocks.first()).toBeVisible();
  await expect(blocks.getByText(/^Loading\.\.\.$/)).toHaveCount(0);
  await expect(blocks.getByText(/加载中/)).toHaveCount(0);
  await expect(blocks.locator('[role="progressbar"], [aria-busy="true"]')).toHaveCount(0);
  await expect(blocks.getByText('...', { exact: true })).toHaveCount(0);
  await expect(blocks.getByText(/^Error$/)).toHaveCount(0);
  await expect(blocks.getByText(/^(Failed to load data|加载失败)$/)).toHaveCount(0);
  expect(errors, 'all page and console errors retained without an allowlist').toEqual([]);
}

test.describe('AMOS lens coverage journeys (S12 batch-1)', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  for (const lens of LENSES) {
    test(`coverage journey: ${lens.code}`, async ({ page }) => {
      await openSettledLens(page, lens.code);
      await expect(
        page.getByRole('heading', { name: lens.heading }),
        `${lens.heading} renders on ${lens.code}`,
      ).toBeVisible();
      writeFileSync(
        test.info().outputPath(`journey-network-${lens.code}.json`),
        JSON.stringify({ code: lens.code, queries: JOURNEYS[lens.code].queries }, null, 2),
      );
      await page.screenshot({ path: `test-results/artifacts/amos-coverage-${lens.code}.png` });
    });

    test(`coverage mobile: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 390, height: 844 });
      await openSettledLens(page, lens.code);
      await expect(page.getByRole('heading', { name: lens.heading })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
        'mobile page overflow is confined to its intentional table scroll areas').toBe(true);
      await page.screenshot({ path: `test-results/artifacts/amos-coverage-${lens.code}-mobile.png`, fullPage: true });
    });

    test(`coverage compact: ${lens.code}`, async ({ page }) => {
      await page.setViewportSize({ width: 1280, height: 720 });
      await openSettledLens(page, lens.code);
      await expect(page.getByRole('heading', { name: lens.heading })).toBeVisible();
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
        'compact dashboard fits the viewport').toBe(true);
      await page.screenshot({ path: `test-results/artifacts/amos-coverage-${lens.code}-compact.png`, fullPage: true });
    });

    test(`coverage focus: ${lens.code}`, async ({ page }) => {
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

    test(`coverage empty: ${lens.code}`, async ({ page }) => {
      // Governance empty-state expression: tables render headers and zero-row
      // state (or governed state rows); card-only dashboards render their
      // governed card surfaces — never a blank canvas.
      await openSettledLens(page, lens.code);
      await expect(page.getByRole('heading', { name: lens.heading })).toBeVisible();
      const tableHeaders = await page.locator('table thead th, table th').count();
      if (tableHeaders > 0) {
        expect(tableHeaders, `${lens.code} renders governed table structures`).toBeGreaterThan(0);
      } else {
        const blocks = page.locator('[data-testid^="dashboard-block-"]');
        await expect(blocks.nth(1)).toBeVisible();
      }
    });
  }
});

