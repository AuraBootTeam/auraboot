/**
 * Dashboard Designer — configuring a data source from the UI
 *
 * The arsenal dashboard proves the platform *can* bind widgets to live data, but its
 * widgets are hand-authored JSON. This proves a user can do it from the designer,
 * which is a different claim and was, until this spec existed, false: the field
 * picker fetched `/api/meta/models/code/{code}/fields`, an endpoint that does not
 * exist. The 404 was swallowed into an empty list, so every model reported
 * "No fields available" and no dimension or metric could be selected at all.
 *
 * A test that only asserted "the panel renders" would have passed throughout. This
 * one drives the actual authoring path: pick a model, pick a dimension, pick a
 * metric — then check the chart resolves against real data.
 *
 * Run against a host-first golden stack:
 *   ./scripts/oss-golden-stack.sh up <name> --slot N --plugin-profile demo
 *   cd web-admin && node scripts/run-showcase-seed-sequence.mjs
 *   eval "$(../scripts/oss-golden-stack.sh env <name>)" \
 *     && npx playwright test -c playwright.gt5.config.ts tests/e2e/dashboard/designer-datasource-config-golden.spec.ts
 */

import { test, expect, type Page } from '@playwright/test';

const DASHBOARD_CODE = 'arsenal_capability_dashboard';

/**
 * Open the arsenal dashboard in the designer and select a widget backed by an aggregate
 * source, leaving its field picker populated.
 *
 * The click is retried: widgets are draggable, and a click landing mid-hydration gets
 * swallowed (the designer page object carries the same retry for palette clicks). The
 * loop settles on the real post-condition — the field picker has fields — rather than
 * on "the panel appeared", which is true even when nothing was selected.
 */
async function openDesignerOnWidget(page: Page, widgetId: string): Promise<void> {
  const dashResp = await page.request.get(`/api/dashboards/code/${DASHBOARD_CODE}`);
  expect(dashResp.ok(), 'arsenal dashboard must be seeded').toBeTruthy();
  const pid = (await dashResp.json())?.data?.pid;
  expect(pid, 'dashboard pid').toBeTruthy();

  await page.goto(`/dashboard-designer/${pid}`);
  const widget = page.locator(`[data-widget-id="${widgetId}"]`);
  await expect(widget).toBeVisible({ timeout: 30_000 });

  const panel = page.locator('[data-testid="widget-property-panel"]');
  const anyField = panel.getByText('阶段', { exact: true }).first();

  for (let attempt = 1; attempt <= 4; attempt += 1) {
    await widget.click({ force: attempt > 1 });
    try {
      await expect(panel).toBeVisible({ timeout: 10_000 });
      await expect(anyField).toBeVisible({ timeout: 10_000 });
      return;
    } catch {
      if (attempt === 4) throw new Error(`widget ${widgetId} never opened a populated field picker`);
      await page.waitForTimeout(1_000);
    }
  }
}

test.describe('Dashboard Designer — data source configuration', () => {
  test.describe.configure({ timeout: 90_000 });

  test('D1: the field picker lists the model fields (it used to 404 into an empty list)', async ({
    page,
  }) => {
    const fieldRequests: number[] = [];
    page.on('response', (r) => {
      if (r.url().includes('/field-meta')) fieldRequests.push(r.status());
    });

    await openDesignerOnWidget(page, 'w_pie_stage');

    const panel = page.locator('[data-testid="widget-property-panel"]');

    // Fields load asynchronously — wait for one before reading the panel, or the
    // assertions race the fetch and pass/fail on nothing.
    await expect(
      panel.getByText('阶段', { exact: true }).first(),
      'the field picker never listed the model fields',
    ).toBeVisible({ timeout: 20_000 });

    // Assert with locators, not innerText: the panel re-renders as the fetch lands and
    // a string snapshot races it (an earlier version of this test read back "饼图" and
    // "failed" on a panel that was, in fact, correct).
    //
    // The exact symptom of the bug: a model is bound, yet the picker claims there is
    // nothing to pick.
    await expect(
      panel.getByText('No fields available'),
      'field picker still reports no fields',
    ).toHaveCount(0);
    await expect(panel.getByText(/加载字段失败/), 'field lookup failed').toHaveCount(0);

    // Fields are listed by their display names, not their codes. (Asserting the
    // *absence* of a code anywhere in the panel is not done here: the panel briefly
    // renders the previously-selected widget's config while switching, so such a
    // check races the transition and says nothing about steady state.)
    await expect(panel.getByText('预期金额', { exact: true }).first()).toBeVisible();
    await expect(panel.getByText('负责人', { exact: true }).first()).toBeVisible();

    // And the lookup must have actually succeeded — a swallowed 404 is what hid this.
    expect(fieldRequests.length, 'no field-meta request was made').toBeGreaterThan(0);
    expect(fieldRequests.filter((s) => s >= 400), 'field-meta returned an error').toEqual([]);
  });

  test('D2: a data source configured in the designer resolves against real data', async ({
    page,
  }) => {
    await openDesignerOnWidget(page, 'w_pie_stage');

    const panel = page.locator('[data-testid="widget-property-panel"]');

    // Re-point the pie at a different dimension purely through the UI — the whole
    // point is that a user can author this without hand-writing JSON.
    // `负责人` appears in both the dimension picker and the metric picker; the first
    // is the dimension list (the metric editor renders below it).
    const dimension = panel.getByText('负责人', { exact: true }).first();
    await expect(dimension, 'owner field is not offered as a dimension').toBeVisible({
      timeout: 20_000,
    });

    // Match the response for THIS widget's query: every widget on the board refires on
    // re-render, so the first chart-data response back is usually somebody else's.
    const chartData = page.waitForResponse(
      (r) =>
        r.url().includes('/api/meta/chart-data') &&
        r.status() === 200 &&
        (r.request().postData() ?? '').includes('crm_opp_owner'),
      { timeout: 20_000 },
    );
    await dimension.click();
    const response = await chartData;

    // The query the designer built must come back with rows — a config that "saves"
    // but resolves to nothing is the failure this asserts against.
    const body = await response.json();
    const rows = body?.data?.rows ?? [];
    expect(rows.length, 'the designer-authored query returned no rows').toBeGreaterThan(0);

    // And it must have grouped by the field that was just clicked.
    expect(Object.keys(rows[0]), 'the query did not group by the selected field').toContain(
      'crm_opp_owner',
    );
  });

  // ---- G5: api data source + named-query parameter editor ------------------
  // The `api` type was consumed by the runtime (useChartData / SmartTableChart /
  // SmartNumberCard do a GET to `url` with `params`) but had no Designer UI, and the
  // named-query `parameters` map had no editor. These drive both authoring paths and
  // assert the authored config reaches the runtime as a real request — the seam this
  // feature adds. Endpoint content is intentionally not asserted (seed-independent):
  // the claim is "what the user typed is what the runtime fetches".

  // A real GET endpoint that returns 200 on the golden stack, used purely so the
  // authored request resolves rather than 404s.
  const API_PROBE_URL = `/api/dashboards/code/${DASHBOARD_CODE}`;

  test('D3: an api data source authored in the designer is fetched with the typed url + params', async ({
    page,
  }) => {
    await openDesignerOnWidget(page, 'w_pie_stage');
    const panel = page.locator('[data-testid="widget-property-panel"]');

    // Switch the widget to the api data source type through the real select.
    await panel.locator('[data-testid="dashboard-datasource-type-select"]').selectOption('api');

    // The api-only UI must appear; aggregate/limit controls must not.
    await expect(panel.locator('[data-testid="dashboard-datasource-api-url"]')).toBeVisible();
    await expect(panel.locator('[data-testid="dashboard-datasource-api-params"]')).toBeVisible();
    await expect(panel.getByText('返回行数限制')).toHaveCount(0);

    // Author url + one query param.
    await panel.locator('[data-testid="dashboard-datasource-api-url"]').fill(API_PROBE_URL);
    await panel.locator('[data-testid="dashboard-datasource-api-params-add"]').click();
    await panel.locator('[data-testid="dashboard-datasource-api-params-key"]').fill('probe');
    await panel.locator('[data-testid="dashboard-datasource-api-params-value"]').fill('g5');

    // The runtime must issue a GET to exactly that url carrying the typed param —
    // proving the authored api config is what the widget fetches (not a dead dropdown).
    const apiReq = page.waitForRequest(
      (r) =>
        r.method() === 'GET' &&
        r.url().includes(API_PROBE_URL) &&
        r.url().includes('probe=g5'),
      { timeout: 20_000 },
    );
    // Nudge a re-render so the widget's fetch effect runs with the new config.
    await panel.locator('[data-testid="dashboard-datasource-api-url"]').blur();
    const request = await apiReq;
    expect(request.url(), 'the api widget did not fetch the authored url+params').toContain(
      'probe=g5',
    );
  });

  test('D4: the named-query parameter editor persists parameters into the widget config', async ({
    page,
  }) => {
    await openDesignerOnWidget(page, 'w_pie_stage');
    const panel = page.locator('[data-testid="widget-property-panel"]');

    await panel
      .locator('[data-testid="dashboard-datasource-type-select"]')
      .selectOption('namedQuery');

    // The parameter editor must render for a named-query source (it used to be a
    // forced empty `{}` with no way to fill it).
    const paramEditor = panel.locator('[data-testid="dashboard-datasource-namedquery-params"]');
    await expect(paramEditor).toBeVisible();

    await paramEditor.locator('[data-testid="dashboard-datasource-namedquery-params-add"]').click();
    await paramEditor
      .locator('[data-testid="dashboard-datasource-namedquery-params-key"]')
      .fill('region');
    await paramEditor
      .locator('[data-testid="dashboard-datasource-namedquery-params-value"]')
      .fill('east');

    // The typed value must survive as the input's value (the editor is controlled by
    // the widget config; a broken write path would reset it to empty).
    await expect(
      paramEditor.locator('[data-testid="dashboard-datasource-namedquery-params-value"]'),
    ).toHaveValue('east');
    await expect(
      paramEditor.locator('[data-testid="dashboard-datasource-namedquery-params-key"]'),
    ).toHaveValue('region');
  });
});
