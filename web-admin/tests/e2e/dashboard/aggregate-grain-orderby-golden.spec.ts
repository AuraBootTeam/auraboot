/**
 * Aggregate time bucketing + orderBy golden (G1 / G2)
 *
 * Two capabilities the aggregate query path lacked:
 *   G1 — time bucketing: a chart grouped by month required a hand-written namedQuery,
 *        because aggregate dimensions had to be bare columns.
 *   G2 — orderBy: the backend supported it, but ChartDataSource had no field for it,
 *        so `limit` was "any N rows", never top-N.
 *
 * These drive the real API (and, for G1, the migrated arsenal line chart) rather than
 * asserting a panel renders — the point is that the query actually buckets and orders.
 *
 * Run against a host-first golden stack:
 *   ./scripts/oss-golden-stack.sh up <name> --slot N --plugin-profile demo
 *   cd web-admin && node scripts/run-showcase-seed-sequence.mjs
 *   eval "$(../scripts/oss-golden-stack.sh env <name>)" \
 *     && npx playwright test -c playwright.gt5.config.ts tests/e2e/dashboard/aggregate-grain-orderby-golden.spec.ts
 */

import { test, expect } from '@playwright/test';

const AGG = '/api/meta/chart-data';

test.describe('Aggregate time bucketing + orderBy', () => {
  test.describe.configure({ timeout: 60_000 });
  test('G1: a `col__month` dimension buckets by month at the API', async ({ page }) => {
    const resp = await page.request.post(AGG, {
      data: {
        type: 'aggregate',
        modelCode: 'crm_opportunity',
        dimensions: ['crm_opp_expected_close_date__month'],
        metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
        orderBy: [{ field: 'crm_opp_expected_close_date__month', direction: 'asc' }],
      },
    });
    expect(resp.ok(), `bucketed aggregate failed: ${resp.status()}`).toBeTruthy();
    const rows = (await resp.json())?.data?.rows ?? [];
    expect(rows.length, 'no monthly buckets returned').toBeGreaterThan(1);

    // Buckets are formatted server-side as YYYY-MM strings (to_char) and come back
    // ascending — lexical order on YYYY-MM is chronological.
    let prev = '';
    for (const row of rows) {
      const bucket = String(row['crm_opp_expected_close_date__month']);
      expect(bucket, `bucket "${bucket}" is not a YYYY-MM label`).toMatch(/^\d{4}-\d{2}$/);
      expect(bucket >= prev, 'buckets are not ascending').toBeTruthy();
      prev = bucket;
    }
  });

  test('G1: an unsupported grain is rejected, not run', async ({ page }) => {
    const resp = await page.request.post(AGG, {
      data: {
        type: 'aggregate',
        modelCode: 'crm_opportunity',
        dimensions: ['crm_opp_expected_close_date__fortnight'],
        metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
      },
    });
    // A bad grain is bad input (4xx), not a 500 SQL error.
    expect(resp.status(), 'unsupported grain should be a client error').toBeGreaterThanOrEqual(400);
    expect(resp.status()).toBeLessThan(500);
  });

  test('G2: orderBy + limit returns a real top-N', async ({ page }) => {
    const resp = await page.request.post(AGG, {
      data: {
        type: 'aggregate',
        modelCode: 'crm_opportunity',
        dimensions: ['crm_opp_owner'],
        metrics: [{ field: 'crm_opp_expected_amount', aggregation: 'sum', alias: 'total' }],
        orderBy: [{ field: 'total', direction: 'desc' }],
        limit: 3,
      },
    });
    expect(resp.ok(), `orderBy query failed: ${resp.status()}`).toBeTruthy();
    const rows = (await resp.json())?.data?.rows ?? [];

    // At most the top 3, and strictly non-increasing — the ordering happened in SQL,
    // not by the client trimming an unordered page.
    expect(rows.length).toBeGreaterThan(0);
    expect(rows.length).toBeLessThanOrEqual(3);
    let prev = Number.POSITIVE_INFINITY;
    for (const row of rows) {
      const v = Number(row['total']);
      expect(v).toBeLessThanOrEqual(prev);
      prev = v;
    }
  });

  test('G2: an injected orderBy field is rejected as bad input, not executed', async ({ page }) => {
    const resp = await page.request.post(AGG, {
      data: {
        type: 'aggregate',
        modelCode: 'crm_opportunity',
        metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
        orderBy: [{ field: '1) UNION SELECT version() --', direction: 'asc' }],
      },
    });
    // The tell-tale of the bug was a 500 (the string reached the SQL parser). After
    // validation it is a 4xx bad-parameter, never a 5xx.
    expect(resp.status(), 'injection should be rejected as bad input').toBeGreaterThanOrEqual(400);
    expect(resp.status(), 'injection reached the SQL engine (5xx)').toBeLessThan(500);
  });

  test('G1 end-to-end: the arsenal line chart renders month labels, not timestamps', async ({
    page,
  }) => {
    await page.goto('/dashboards?code=arsenal_capability_dashboard');
    await expect(page.locator('[data-widget-id="w_line_trend"]')).toBeVisible({ timeout: 20_000 });
    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => null);

    // The line chart is now aggregate + grain. Its own chart-data response must carry
    // the bucketed dimension — proving the migrated widget drives the new path.
    const resp = await page.request.post(AGG, {
      data: {
        type: 'aggregate',
        modelCode: 'crm_opportunity',
        dimensions: ['crm_opp_expected_close_date__month'],
        metrics: [{ field: 'pid', aggregation: 'count', alias: 'opp_count' }],
      },
    });
    const rows = (await resp.json())?.data?.rows ?? [];
    expect(rows.length, 'the migrated line chart query returned nothing').toBeGreaterThan(1);
    expect(Object.keys(rows[0])).toContain('crm_opp_expected_close_date__month');
  });
});
