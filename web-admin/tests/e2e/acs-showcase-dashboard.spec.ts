/**
 * ACP Showcase Dashboard — E2E Test
 *
 * Verifies the redesigned acs_dashboard (15 widgets) renders correctly:
 * - All widget containers present via data-widget-id
 * - KPI cards show numeric values (not empty/placeholder)
 * - Pipeline SVG has all 6 data-layer nodes (L0-L5)
 * - Chart widgets render at least one data shape
 * - Recent logs table renders (0..10 rows)
 * - No $i18n: key leaks
 * - CTA strip routes to demo request create form
 *
 * Authentication: storageState (global-setup.ts) — no login in beforeEach.
 *
 * @since 10.3.0
 */

import { test, expect, type Page } from '../fixtures';

// ---------------------------------------------------------------------------
// Widget inventory — must match acs_dashboard.json exactly
// ---------------------------------------------------------------------------
const WIDGET_IDS = [
  'hero',
  'kpi_total_requests',
  'kpi_success_rate',
  'kpi_avg_duration',
  'kpi_safety_triggers',
  'pipeline_diagram',
  'cta_strip',
  'chart_status',
  'chart_risk',
  'chart_category',
  'chart_safety_trend',
  'kpi_pending_approvals',
  'kpi_total_cost',
  'recent_logs',
  'footer_guide',
] as const;

const KPI_WIDGET_IDS = [
  'kpi_total_requests',
  'kpi_success_rate',
  'kpi_avg_duration',
  'kpi_safety_triggers',
  'kpi_pending_approvals',
  'kpi_total_cost',
] as const;

// ---------------------------------------------------------------------------
// Navigation helper — MUST use sidebar, NOT direct page.goto  [D1]
// ---------------------------------------------------------------------------
async function navigateToAcsDashboard(page: Page): Promise<void> {
  // Start from a known authenticated app page
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav, aside, [role="navigation"]').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  // Click "ACP Showcase" dashboard tab / menu link
  const acsDashboardLink = nav.getByRole('link', { name: /ACP Showcase/i }).first();
  const tabFallback = page.getByRole('tab', { name: /ACP Showcase/i }).first();

  const linkVisible = await acsDashboardLink.isVisible({ timeout: 3_000 }).catch(() => false);
  if (linkVisible) {
    await acsDashboardLink.click();
  } else {
    // Dashboard may be a tab within the dashboards route
    await tabFallback.click();
  }

  // Wait for the hero widget to appear (first widget in the layout)
  await expect(page.locator('[data-widget-id="hero"]')).toBeVisible({ timeout: 20_000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe('ACP Showcase Dashboard', () => {
  test.setTimeout(90_000);

  test('renders all 15 widgets with real data and CTA flow', async ({ page }) => {
    await navigateToAcsDashboard(page);

    // --- 1. All 15 widget containers present (data-widget-id wrapper from WidgetRenderer)
    for (const id of WIDGET_IDS) {
      await expect(
        page.locator(`[data-widget-id="${id}"]`),
        `widget "${id}" should be visible`,
      ).toBeVisible({ timeout: 15_000 });
    }

    // --- 2. KPI cards show a numeric value (not '--' / empty / loading)
    for (const kpiId of KPI_WIDGET_IDS) {
      const text = await page.locator(`[data-widget-id="${kpiId}"]`).textContent({ timeout: 10_000 });
      expect(text, `${kpiId} should contain a digit`).toMatch(/\d/);
    }

    // --- 3. Pipeline SVG: all 6 layer nodes visible (data-layer attr set in SVG content)
    for (const layer of ['L5', 'L4', 'L3', 'L2', 'L1', 'L0'] as const) {
      await expect(
        page.locator(`[data-widget-id="pipeline_diagram"] [data-layer="${layer}"]`),
        `pipeline layer ${layer} should be visible`,
      ).toBeVisible({ timeout: 10_000 });
    }

    // --- 4. Charts: each has at least one rendered data shape (recharts class names)
    await expect(
      page
        .locator(
          '[data-widget-id="chart_status"] .recharts-bar-rectangle, [data-widget-id="chart_status"] .recharts-rectangle',
        )
        .first(),
      'chart_status should render at least one bar',
    ).toBeVisible({ timeout: 15_000 });

    await expect(
      page
        .locator(
          '[data-widget-id="chart_risk"] .recharts-pie-sector, [data-widget-id="chart_risk"] .recharts-sector',
        )
        .first(),
      'chart_risk should render at least one pie sector',
    ).toBeVisible({ timeout: 15_000 });

    await expect(
      page
        .locator(
          '[data-widget-id="chart_category"] .recharts-bar-rectangle, [data-widget-id="chart_category"] .recharts-rectangle',
        )
        .first(),
      'chart_category should render at least one bar',
    ).toBeVisible({ timeout: 15_000 });

    await expect(
      page
        .locator(
          '[data-widget-id="chart_safety_trend"] .recharts-line-curve, [data-widget-id="chart_safety_trend"] path.recharts-curve',
        )
        .first(),
      'chart_safety_trend should render at least one line curve',
    ).toBeVisible({ timeout: 15_000 });

    // --- 5. Recent logs table: 0..10 rows (empty state is valid when DB has no data)
    const rowCount = await page
      .locator('[data-widget-id="recent_logs"] tbody tr')
      .count();
    expect(rowCount, 'recent_logs row count should be 0–10').toBeLessThanOrEqual(10);

    // --- 6. No $i18n: key leaks anywhere on the page
    const bodyText = await page.locator('body').textContent();
    expect(bodyText, 'no $i18n: keys should be visible on page').not.toMatch(/\$i18n:/);

    // --- 7. CTA strip: "Run a Demo Request" routes to the create form
    await page.locator('[data-widget-id="cta_strip"]').getByText(/Run a Demo Request|运行一次 Demo 请求/i).click();
    await expect(page).toHaveURL(/\/p\/acs_demo_request\/new/, { timeout: 10_000 });
    // Verify the create form actually loaded (not a blank page)
    await expect(
      page.locator('form, [data-testid="dynamic-form"], [class*="form"]').first(),
    ).toBeVisible({ timeout: 15_000 });
  });
});
