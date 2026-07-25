/**
 * Data-plane review — query builder end-to-end.
 *
 * Renders-without-error is not the question; "can a user actually get data out of it"
 * is. This picks a model, runs the query, and asserts the header's run summary reports
 * a row count that MATCHES what the API returns for the same model.
 *
 * Falsifiability: the header must read "no model" before the run, and the post-run
 * count is compared against an independently fetched API total — a builder that
 * silently no-ops, or one that reports a number unrelated to the data, both fail.
 */
import { expect, test } from '@playwright/test';

const OUT = 'test-results/dataplane-review';

test.describe('Data-plane review — query builder', () => {
  test.setTimeout(120_000);

  test('pick model → run query → summary row count matches the API', async ({ page, request }) => {
    // Environment prerequisite (🟢 per AGENTS §2 skip taxonomy — an env gate, NOT a
    // product gap being skip-wrapped): /query-builder is menu-gated by the core-meta
    // plugin. Without it the shell renders "Page Unavailable" and any failure here
    // would be a false red about the environment, not about the query builder.
    const menuResp = await request.get('/api/menu/user');
    const menuJson = menuResp.ok() ? await menuResp.json() : null;
    const hasQueryBuilder = JSON.stringify(menuJson ?? {}).includes('/query-builder');
    test.skip(
      !hasQueryBuilder,
      'core-meta plugin not imported — /query-builder has no menu config in this stack',
    );

    // Ground truth from the API for the same model.
    const apiResp = await request.get('/api/dynamic/e2et_order/list?pageNum=1&pageSize=100');
    expect(apiResp.ok(), `dynamic list: ${apiResp.status()}`).toBe(true);
    const apiBody = await apiResp.json();
    const apiTotal: number = apiBody?.data?.total ?? apiBody?.data?.records?.length ?? 0;
    expect(apiTotal, 'precondition: the model must hold rows').toBeGreaterThan(0);

    await page.goto('/query-builder', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {});

    // Baseline: the header breadcrumb must say no model is picked, so the post-run
    // assertion cannot pass on stale state.
    const shell = page.locator('main').first();
    await expect(shell, 'query builder starts with no model picked').toContainText('no model');

    await page.getByText('e2et_order', { exact: true }).first().click();
    await page.waitForTimeout(500);
    await page.screenshot({ path: `${OUT}/qb-model-picked.png` });

    const runBtn = page.getByRole('button', { name: /run query/i });
    await expect(runBtn, 'Run query must be enabled once a model is picked').toBeEnabled();
    await runBtn.click();
    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {});
    await page.waitForTimeout(1000);
    await page.screenshot({ path: `${OUT}/qb-after-run.png` });

    // Header renders "<model> · N fields · N filters · N rows / N ms".
    const statsText = (await shell.innerText()).replace(/\s+/g, ' ');
    const rowsReported = statsText.match(/(\d+) rows? \/ \d+ ms/)?.[1];

    // eslint-disable-next-line no-console
    console.log(`[dp66][qb] apiTotal=${apiTotal} rowsReported=${rowsReported}`);

    expect(rowsReported, 'header must report a run summary after Run query').toBeTruthy();
    expect(Number(rowsReported), 'grid row count must match the API total').toBe(apiTotal);
  });
});
