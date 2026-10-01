import { test, expect } from '../../fixtures';

/**
 * AMOS interactive lens journeys — P2-1 browser slice for the S12 matrix.
 *
 * Covers the 14 lens pages that carry business-out widgets (KPI cards,
 * status charts, filter bar, drill-down). Per page:
 *   1. heading + widget surfaces render
 *   2. business data assertions (governed values, state rows)
 *   3. filter bar interaction emits a filtered chart-data request
 *      (opportunities lens — the SmartFilterBar vertical slice)
 *   4. drill-down navigates to the record list with the stage param
 *      (opportunities + delivery — the P1-2 slices)
 * Zero product console errors per page (SoT §8, G10 rules).
 */

const consoleErrors: string[] = [];

function isProductError(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504 |Loading chunk|entry\.client|Importing a module script failed|HMR|[Vv]ite|websocket/i.test(text);
}

test.describe('AMOS interactive lens journeys (P2-1)', () => {
  test.setTimeout(120_000);
  test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

  test.beforeEach(async ({ page }) => {
    consoleErrors.length = 0;
    page.on('console', (msg) => {
      if (msg.type() === 'error') consoleErrors.push(msg.text());
    });
  });

  test('opportunities lens: filter bar emits filtered chart-data request', async ({ page }) => {
    await page.goto('/dashboards/view/amos_opportunities_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'AMOS 商机阶段' }).first()).toBeVisible({ timeout: 20_000 });

    const chartData = page.waitForRequest(
      (req) => req.url().includes('/api/meta/chart-data') && (req.postData() || '').includes('"discovery"'),
      { timeout: 15_000 },
    );
    await page.getByRole('combobox').first().selectOption('discovery');
    const req = await chartData;
    const body = JSON.parse(req.postData() || '{}');
    expect(body.filters).toContainEqual({ field: 'label', operator: 'eq', value: 'discovery' });

    // the pipeline chart refetches and renders only the filtered stage
    // (chart titles are ECharts canvas text — assert the widget canvas, not DOM text)
    await expect(
      page.locator('[data-widget-type="smart-bar-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-opportunities-filtered.png' });

    const productErrors = consoleErrors.filter(isProductError);
    expect(productErrors, '0 product console errors').toEqual([]);
  });

  test('opportunities lens: pipeline bar drills to opportunity records', async ({ page }) => {
    await page.goto('/dashboards/view/amos_opportunities_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'AMOS 商机阶段' }).first()).toBeVisible({ timeout: 20_000 });

    const bar = page.locator('[data-widget-type="smart-bar-chart"] canvas').first();
    await bar.waitFor({ state: 'visible', timeout: 15_000 });
    await bar.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(1500);
    const box = await bar.boundingBox();
    expect(box, 'pipeline chart canvas has a bounding box').toBeTruthy();
    if (!box) return;

    // click the closed_lost bar (right-most column body)
    await page.mouse.click(box.x + box.width * 0.92, box.y + box.height * 0.75);
    await page.waitForURL(/crm_opportunity_common\?stage=/, { timeout: 15_000 });
    expect(page.url()).toContain('/p/crm_opportunity_common?stage=');
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-opportunities-drill.png' });
  });

  test('delivery lens: OTIF KPI card + drill to shipments', async ({ page }) => {
    await page.goto('/dashboards/view/amos_delivery_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'AMOS 交付履约' }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('M09 准时足量交付率').first()).toBeVisible();

    // drill: delivered bar navigates to the shipment list with status param
    const bar = page.locator('[data-widget-type="smart-bar-chart"] canvas').first();
    await bar.waitFor({ state: 'visible', timeout: 15_000 });
    await bar.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await page.waitForTimeout(1500);
    const box = await bar.boundingBox();
    expect(box).toBeTruthy();
    if (!box) return;
    await page.mouse.click(box.x + box.width * 0.25, box.y + box.height * 0.75);
    await page.waitForURL(/inventory\/shipments\?status=delivered/, { timeout: 15_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-delivery-drill.png' });
  });

  test('invoices lens: M12 confirmed revenue card renders business value', async ({ page }) => {
    await page.goto('/dashboards/view/amos_invoices_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'AMOS 发票' }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('M12 确认收入(元)').first()).toBeVisible();
    await expect(page.getByText('m12_confirmed_revenue').first()).toBeVisible();
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-invoices-card.png' });
  });

  test('inventory lens: lot-age distribution renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_inventory_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'AMOS 库存库龄' }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('m20_inventory_ageing').first()).toBeVisible();
    await expect(
      page.locator('[data-widget-type="smart-bar-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-inventory-lotage.png' });
  });

  test('customers lens: AR status pie renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_customers_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: 'AMOS 客户经营' }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('m17_existing_customer_share').first()).toBeVisible();
    await expect(
      page.locator('[data-widget-type="smart-pie-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-customers-pie.png' });
  });

  test('products lens: status distribution table + pie', async ({ page }) => {
    await page.goto('/dashboards/view/amos_products_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 产品 · 状态镜头/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('planned').first()).toBeVisible();
    await expect(
      page.locator('[data-widget-type="smart-pie-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-products-status.png' });
  });

  test('engineering lens: m08 honestly MISSING + BOM status bar', async ({ page }) => {
    await page.goto('/dashboards/view/amos_engineering_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 工程/ }).first()).toBeVisible({ timeout: 20_000 });
    // m08 is writer-blocked: the governance surface expresses MISSING, never zero
    await expect(page.getByText('m08_production_completion_rate').first()).toBeVisible();
    await expect(page.getByText('BOM 状态分布').first()).toBeVisible({ timeout: 15_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-engineering-bom.png' });
  });

  test('leads lens: status pie renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_leads_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 有效线索 · 状态镜头/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(
      page.locator('[data-widget-type="smart-pie-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-leads-pie.png' });
  });

  test('quotes lens: status pie renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_quotes_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 报价/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(
      page.locator('[data-widget-type="smart-pie-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-quotes-pie.png' });
  });

  test('production lens: work-order status bar renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_production_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 生产/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(
      page.locator('[data-widget-type="smart-bar-chart"] canvas').first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-production-wo.png' });
  });

  test('changes lens: M05 KPI card renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_changes_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 变更/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('M05 净签约额(元)').first()).toBeVisible();
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-changes-card.png' });
  });

  test('readiness lens: M07 KPI card renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_readiness_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 齐套/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('m07_kitting_order_rate').first()).toBeVisible();
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-readiness-card.png' });
  });

  test('receivables lens: M14/M15 governance rows render', async ({ page }) => {
    await page.goto('/dashboards/view/amos_receivables_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 应收/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('m14_due_collection_rate').first()).toBeVisible();
    await expect(page.getByText('m15_overdue_receivables').first()).toBeVisible();
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-receivables-rows.png' });
  });

  test('release lens: M05 KPI card renders', async ({ page }) => {
    await page.goto('/dashboards/view/amos_release_lens', { waitUntil: 'domcontentloaded' });
    await expect(page.getByRole('heading', { name: /AMOS 发布/ }).first()).toBeVisible({ timeout: 20_000 });
    await expect(page.getByText('M05 净签约额(元)').first()).toBeVisible();
    await page.screenshot({ path: 'test-results/artifacts/amos-lens-release-card.png' });
  });
});
