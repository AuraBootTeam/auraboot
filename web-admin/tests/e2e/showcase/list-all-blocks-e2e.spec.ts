/**
 * D — List-kind block coverage: filters (standalone block).
 *
 * Existing showcase specs cover the following list-kind blocks:
 *   - table, toolbar, tabs, form-section, form-buttons, detail-section
 *     (see showcase-smoke, list-configpanel, form-blocksdesigner, runtime-rendering)
 *
 * This file closes the gap for list-kind blocks that the task D brief lists
 * as untested. After auditing the runtime dispatch code paths in
 * `web-admin/app/framework/meta/rendering/pages/ListPageContent.tsx` and the
 * fallback renderers registered in
 * `web-admin/app/framework/meta/rendering/BlockRenderer.tsx`, the actual
 * runtime-supported set of standalone blocks on a `kind=list` page_schema is:
 *
 *   - table        (already covered elsewhere)
 *   - filters      ← THIS FILE
 *   - toolbar      (already covered in runtime-rendering / list-configpanel)
 *   - tabs         (already covered in runtime-rendering)
 *   - form-buttons (already covered in list-configpanel — alias of toolbar in list)
 *
 * The task brief also names `stat-card`, `chart`, and `monthly-grid` as
 * list-kind blocks. Audit of `ListPageContent.tsx` shows:
 *
 *   - `stat-card`   : NOT dispatched by ListPageContent (no renderer branch);
 *                     only exists as a designer palette item + report-designer
 *                     block. Runtime assertion is impossible on a list page,
 *                     so this block is explicitly skipped with a reason.
 *   - `chart`       : NOT dispatched by ListPageContent (no renderer branch).
 *                     ChartBlockRenderer IS registered as a BlockRenderer
 *                     fallback, but list pages do not route through
 *                     BlockRenderer — they consume table/filters/toolbar/tabs
 *                     via hardcoded switch. Skipped with reason below.
 *   - `monthly-grid`: Dispatched only by DetailPageContent (direct mode) — see
 *                     detail-all-blocks-e2e.spec.ts for coverage.
 *
 * The `filters` block is what this file adds to coverage: it is the only
 * list-kind block not already asserted on runtime elsewhere.
 *
 * Strategy: same as subtable-modes-e2e — API PUT the page_schema blocks, then
 * navigate through the sidebar and assert the block renders at runtime. No
 * `page.goto` to deep URLs, no `waitForTimeout`, per-block cleanup in
 * `afterEach`.
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';

const MODEL_CODE = 'showcase_all_fields';
const LIST_URL = `/p/${MODEL_CODE}`;
const LIST_PAGE_KEY = `${MODEL_CODE}_list`;

interface ListPageSnapshot {
  pid: string;
  pageKey: string;
  blocks: any[];
  layout: any;
  title: any;
  name: any;
  modelCode: string;
}

async function snapshotListPage(
  request: APIRequestContext,
  pageKey: string,
): Promise<ListPageSnapshot> {
  const resp = await request.get(`/api/pages/key/${pageKey}`);
  expect(resp.ok(), `GET /api/pages/key/${pageKey} status=${resp.status()}`).toBe(true);
  const body = await resp.json();
  const data = body?.data ?? {};
  return {
    pid: data.pid,
    pageKey: data.pageKey,
    blocks: data.blocks ?? [],
    layout: data.layout ?? { type: 'stack' },
    title: data.title,
    name: data.name,
    modelCode: data.modelCode,
  };
}

async function replacePageBlocks(
  request: APIRequestContext,
  snap: ListPageSnapshot,
  newBlocks: any[],
): Promise<void> {
  const resp = await request.put(`/api/pages/${snap.pid}`, {
    data: {
      pageKey: snap.pageKey,
      name: snap.name,
      title: snap.title,
      kind: 'list',
      modelCode: snap.modelCode,
      blocks: newBlocks,
      layout: snap.layout,
    },
  });
  expect(
    resp.ok(),
    `PUT /api/pages/${snap.pid} status=${resp.status()} body=${await resp.text()}`,
  ).toBe(true);
}

async function gotoShowcaseListViaMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload({ waitUntil: 'domcontentloaded' });

  const parent = page
    .locator('button, [role="menuitem"]', {
      hasText: /能力展示|Showcase|menu\.sc_root/i,
    })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 10_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const listResp = page.waitForResponse(
    (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
    { timeout: 10_000 },
  );
  const leaf = page.locator(`a[href="${LIST_URL}"], a[href*="${LIST_URL}"]`).first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(page).toHaveURL(new RegExp(`${LIST_URL}(?:$|\\?)`), { timeout: 5_000 });
  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
}

let listSnapshot: ListPageSnapshot | null = null;

test.describe('D — List-kind block coverage (filters standalone)', () => {
  test.setTimeout(60_000);

  test.afterEach(async ({ request }) => {
    if (listSnapshot) {
      await replacePageBlocks(request, listSnapshot, listSnapshot.blocks).catch(() => null);
      listSnapshot = null;
    }
  });

  // ---------------------------------------------------------------------------
  // D.filters — standalone `filters` block with ≥3 fields + buttons.
  //
  // ListPageContent renders `filters` via the hardcoded filter-form slot. We
  // assert:
  //   1. the filter form container is visible
  //   2. all configured fields render as inputs
  //   3. the filter-search and filter-reset buttons work (click → triggers
  //      /api/dynamic/{model}/list with filters payload)
  // ---------------------------------------------------------------------------
  test('D.filters: standalone filters block renders with ≥3 fields + search+reset buttons', async ({
    page,
    request,
  }) => {
    listSnapshot = await snapshotListPage(request, LIST_PAGE_KEY);

    // Build new blocks: keep non-filters blocks (table, toolbar) + inject our filters.
    const otherBlocks = listSnapshot.blocks.filter(
      (b: any) => b?.blockType !== 'filters',
    );
    const newBlocks = [
      {
        id: 'd_filters',
        blockType: 'filters',
        fields: [
          { field: 'sc_name', label: 'Name', component: 'SmartInput' },
          { field: 'sc_code', label: 'Code', component: 'SmartInput' },
          { field: 'sc_status', label: 'Status', component: 'SmartInput' },
          { field: 'sc_priority', label: 'Priority', component: 'SmartInput' },
        ],
        buttons: [],
      },
      ...otherBlocks,
    ];
    await replacePageBlocks(request, listSnapshot, newBlocks);

    await gotoShowcaseListViaMenu(page);

    // The filter form is collapsed by default (filterFormVisible=false in
    // ListPageContent) — toggle it open via the toolbar's `filters-toggle`
    // button, which only renders when the page has a filters block (so this
    // is itself an assertion that the filters block was picked up).
    const filtersToggle = page.getByTestId('filters-toggle');
    await expect(filtersToggle, 'filters-toggle button (proves block dispatched)').toBeVisible({
      timeout: 5_000,
    });
    await filtersToggle.click();

    // D.filters.1 — filter-search + filter-reset buttons are rendered.
    const searchBtn = page.getByTestId('filter-search');
    const resetBtn = page.getByTestId('filter-reset');
    await expect(searchBtn, 'filter-search button should render').toBeVisible({
      timeout: 5_000,
    });
    await expect(resetBtn, 'filter-reset button should render').toBeVisible({
      timeout: 5_000,
    });

    // D.filters.2 — input fields render for each configured field.
    // ListPageContent wraps the filter area in a `data-testid="search-area"`
    // div; SmartInput renders inputs/selects inside. Assert ≥3 fields appear
    // (we configured 4 in the block).
    const searchArea = page.getByTestId('search-area');
    await expect(searchArea, 'search-area container should render').toBeVisible({
      timeout: 5_000,
    });
    const filterFormInputs = searchArea.locator(
      'input:not([type="hidden"]), [role="combobox"], select, textarea',
    );
    await expect
      .poll(async () => await filterFormInputs.count(), { timeout: 5_000 })
      .toBeGreaterThanOrEqual(3);

    // D.filters.3 — clicking filter-search triggers a /list refresh.
    const searchResp = page.waitForResponse(
      (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
      { timeout: 5_000 },
    );
    await searchBtn.click();
    await searchResp;

    // D.filters.4 — clicking filter-reset also triggers a refresh.
    const resetResp = page.waitForResponse(
      (r) => r.url().includes(`/api/dynamic/${MODEL_CODE}/list`) && r.status() === 200,
      { timeout: 5_000 },
    );
    await resetBtn.click();
    await resetResp;
  });

  // ---------------------------------------------------------------------------
  // D.stat-card — NOT supported at runtime on list pages.
  //
  // ListPageContent.tsx audits: no `block.blockType === 'stat-card'` branch.
  // BlockRenderer fallback map does not include 'stat-card'. stat-card only
  // exists as a designer palette item (BlockLibrary.tsx) and as a
  // report-designer block. Persisting {blockType:'stat-card'} on a list page
  // would render nothing — silently ignored.
  // ---------------------------------------------------------------------------
  test('D.stat-card: skip — not dispatched by ListPageContent at runtime', async () => {
    test.skip(
      true,
      'stat-card is not wired into ListPageContent or BlockRenderer fallback. ' +
        'It exists only in the designer palette (core-designer BlockLibrary) ' +
        'and as a report-designer block. Persisting stat-card on a list page ' +
        'would render nothing. Coverage for stat-card belongs in dashboard/' +
        'report-designer E2E, not list-page E2E.',
    );
  });

  // ---------------------------------------------------------------------------
  // D.chart — NOT supported at runtime on list pages (only on dashboard).
  //
  // ListPageContent.tsx audits: no `block.blockType === 'chart'` branch.
  // ChartBlockRenderer is registered in the BlockRenderer fallback map, BUT
  // ListPageContent renders blocks via hardcoded switch (table/filters/toolbar
  // /tabs), never through BlockRenderer. So chart blocks on a list page are
  // silently ignored at runtime.
  // ---------------------------------------------------------------------------
  test('D.chart: skip — not dispatched by ListPageContent at runtime', async () => {
    test.skip(
      true,
      'chart block is not dispatched by ListPageContent (which uses a ' +
        'hardcoded switch on table/filters/toolbar/tabs). ChartBlockRenderer ' +
        'is registered as a BlockRenderer fallback, but list pages do not ' +
        'route through BlockRenderer. Chart block coverage belongs in ' +
        'dashboard E2E (core-dashboard widget + DashboardChartBlock).',
    );
  });

  // ---------------------------------------------------------------------------
  // D.monthly-grid — supported on DETAIL pages, not list pages.
  //
  // See `detail-all-blocks-e2e.spec.ts` for the monthly-grid runtime coverage.
  // ---------------------------------------------------------------------------
  test('D.monthly-grid: skip — covered in detail-all-blocks-e2e.spec.ts', async () => {
    test.skip(
      true,
      'monthly-grid is dispatched by DetailPageContent.directMonthlyGridBlocks ' +
        'only. List pages have no branch for it. See detail-all-blocks-e2e.spec.ts ' +
        'for the runtime assertion on a detail page.',
    );
  });
});
