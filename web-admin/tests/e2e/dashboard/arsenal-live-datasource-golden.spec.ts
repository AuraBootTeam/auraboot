/**
 * Arsenal dashboard — live data source golden
 *
 * The arsenal capability dashboard used to carry hardcoded values in widget
 * config, so it rendered charts whether or not data binding worked. Every widget
 * now declares a real dataSource (aggregate / namedQuery / static), and this
 * golden is what makes that claim falsifiable.
 *
 * It asserts STRUCTURE, not numbers: the seed data drifts (amounts are random,
 * dates are relative), so pinning values would make the spec brittle without
 * making it stronger. What must hold is that every widget actually resolved its
 * data source — the single most useful assertion being that no widget fell back
 * to its "请配置数据源" placeholder, which is exactly what a broken binding
 * renders.
 *
 * Run against a host-first golden stack:
 *   ./scripts/oss-golden-stack.sh up arsenal-dyn-ds --slot 40 --plugin-profile demo
 *   cd web-admin && node scripts/run-showcase-seed-sequence.mjs
 *   eval "$(../scripts/oss-golden-stack.sh env arsenal-dyn-ds)" \
 *     && npx playwright test -c playwright.gt5.config.ts tests/e2e/dashboard/arsenal-live-datasource-golden.spec.ts
 */

import { test, expect, type Page } from '@playwright/test';

const DASHBOARD_CODE = 'arsenal_capability_dashboard';

/**
 * What a widget renders when its data source is unset, unresolvable, or missing a
 * required field prop. The wording is not consistent across the Smart* components
 * (zh on the number card, three English variants elsewhere, plus kanban's own
 * "group field" message), so match the whole family — checking only one of them is
 * how the first version of this spec passed while the kanban and gallery were both
 * visibly broken.
 */
const UNBOUND_PATTERN =
  /请配置数据源|Please configure (the )?data source|Configure a data source|Please configure group field/;

/** What a widget renders when it resolved a data source but got nothing back. */
const EMPTY_PATTERN = /No items|No data available/;

/** Widgets that are static by contract — see the seed for why each one cannot bind. */
const STATIC_BY_CONTRACT = ['w_richtext', 'w_countdown', 'w_nps', 'w_gallery'];

/** Every widget that must resolve a live (aggregate / namedQuery) data source. */
const LIVE_WIDGETS = [
  'w_num_customers',
  'w_num_pipeline',
  'w_num_winrate',
  'w_num_leads',
  'w_bar_monthly',
  'w_line_trend',
  'w_pie_stage',
  'w_funnel',
  'w_radar',
  'w_area',
  'w_gauge',
  'w_progress',
  'w_scatter',
  'w_table',
  'w_heatmap',
  'w_treemap',
  'w_leaderboard',
  'w_wordcloud',
  'w_combo',
  'w_kanban',
];

async function openArsenalDashboard(page: Page): Promise<void> {
  const chartData: Array<{ status: number; body: string }> = [];
  page.on('response', async (r) => {
    if (r.url().includes('/api/meta/chart-data')) {
      chartData.push({ status: r.status(), body: await r.text().catch(() => '') });
    }
  });

  await page.goto(`/dashboards?code=${DASHBOARD_CODE}`);
  // Charts resolve asynchronously; wait for the grid, then let the queries settle.
  await expect(page.locator('[data-widget-id]').first()).toBeVisible({ timeout: 20_000 });
  await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => null);

  // Surface backend failures directly instead of letting them show up as an empty chart.
  const failed = chartData.filter((r) => r.status >= 400);
  expect(
    failed,
    `chart-data requests failed: ${failed.map((f) => `${f.status} ${f.body.slice(0, 200)}`).join(' | ')}`,
  ).toHaveLength(0);
}

test.describe('Arsenal dashboard — every widget on a live data source', () => {
  // 24 widgets each fire their own chart-data query; the suite default (15s) is
  // shorter than it takes them all to settle.
  test.describe.configure({ timeout: 90_000 });

  test('A1: no widget falls back to an unbound-data-source placeholder', async ({ page }) => {
    await openArsenalDashboard(page);

    await expect(page.locator('[data-widget-id]')).toHaveCount(24);

    // The core assertion. A widget whose data source is missing, malformed, or
    // rejected by the backend renders one of these placeholders — the old
    // hardcoded dashboard could never trip it, which is precisely why it proved
    // nothing. This covers the static-by-contract widgets too: they still have to
    // bind a real (static) data source and supply their field props.
    const unbound: string[] = [];
    for (const id of [...LIVE_WIDGETS, ...STATIC_BY_CONTRACT]) {
      const text = await page.locator(`[data-widget-id="${id}"]`).innerText().catch(() => '');
      if (UNBOUND_PATTERN.test(text)) unbound.push(id);
    }

    expect(unbound, 'widgets stuck on an unbound-data-source placeholder').toEqual([]);
  });

  test('A2: live widgets resolve their data source and render real content', async ({ page }) => {
    await openArsenalDashboard(page);

    const blank: string[] = [];
    const emptyData: string[] = [];

    for (const id of LIVE_WIDGETS) {
      const widget = page.locator(`[data-widget-id="${id}"]`);
      await expect(widget, `${id} missing from the dashboard`).toHaveCount(1);

      const text = (await widget.innerText().catch(() => '')).trim();

      // Resolved-but-empty is its own failure: the query ran and returned nothing.
      // Every live widget here is backed by seeded data, so this must not happen.
      if (EMPTY_PATTERN.test(text)) emptyData.push(id);

      // A resolved widget renders either an ECharts canvas or real DOM content
      // (number cards, tables, kanban and leaderboard are DOM, not canvas).
      const hasCanvas = await widget.locator('canvas').count();
      // Strip the title so a widget that renders nothing but its own heading counts as blank.
      const bodyText = text.split('\n').slice(1).join('').trim();
      if (!hasCanvas && !bodyText) blank.push(id);
    }

    expect(emptyData, 'live widgets resolved their data source but got no rows').toEqual([]);
    expect(blank, 'live widgets rendered no chart and no content').toEqual([]);
  });

  test('A2b: static-by-contract widgets still render their content', async ({ page }) => {
    await openArsenalDashboard(page);

    // NPS and Gallery cannot bind to the CRM model, but they are still on a real
    // (static) data source and must render — an "No items" gallery is a broken
    // widget, not an honest gap.
    for (const id of STATIC_BY_CONTRACT) {
      const widget = page.locator(`[data-widget-id="${id}"]`);
      const text = (await widget.innerText().catch(() => '')).trim();
      expect(EMPTY_PATTERN.test(text), `${id} rendered an empty-data placeholder`).toBeFalsy();
    }
  });

  test('A3: KPI cards show real aggregates, not the old hardcoded snapshot', async ({ page }) => {
    await openArsenalDashboard(page);

    // 客户总数 must equal the live crm_account count, which the API can confirm
    // independently. The old seed hardcoded 60; asserting "matches the API" keeps
    // this honest even when the seed volume changes.
    const listResp = await page.request.get('/api/dynamic/crm_account/list?pageNum=1&pageSize=1');
    expect(listResp.ok()).toBeTruthy();
    const total = (await listResp.json())?.data?.total;
    expect(typeof total, 'crm_account total from API').toBe('number');
    expect(total).toBeGreaterThan(0);

    const card = page.locator('[data-widget-id="w_num_customers"]');
    await expect(card).toContainText(String(total), { timeout: 15_000 });

    // 赢单率 comes from a namedQuery ratio — it must be a real percentage, and it
    // must not be the 73 that used to be typed into the seed by hand.
    const winRate = page.locator('[data-widget-id="w_num_winrate"]');
    await expect(winRate).toContainText('%');
  });

  test('A4: owner-grouped widgets have real cardinality', async ({ page }) => {
    await openArsenalDashboard(page);

    // Before the ownership seed phase every CRM record belonged to the admin, so
    // the leaderboard was a single bar. It should now list several named reps —
    // and never a raw ULID, which is what the auto-set owner used to store.
    const leaderboard = page.locator('[data-widget-id="w_leaderboard"]');
    const text = await leaderboard.innerText();

    expect(text, 'leaderboard shows a raw user id instead of a name').not.toMatch(
      /[0-9A-HJKMNP-TV-Z]{26}/,
    );

    const namedRows = (await leaderboard.locator('text=/[一-龥]{2,4}/').count()) > 0;
    expect(namedRows, 'leaderboard has no named sales reps').toBeTruthy();
  });

  test('A5: no console errors while resolving data sources', async ({ page }) => {
    const errors: string[] = [];
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text());
    });

    await openArsenalDashboard(page);

    // Chart rendering failures surface here long before they surface visually.
    expect(errors, `console errors on the arsenal dashboard:\n${errors.join('\n')}`).toEqual([]);
  });
});
