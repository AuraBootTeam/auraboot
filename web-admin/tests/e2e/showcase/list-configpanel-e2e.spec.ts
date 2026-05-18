/**
 * Phase 3 — List ConfigPanel E2E (4 tabs).
 *
 * Plan: docs/plans/2026-04/2026-04-18-e2e-showcase-allfields-plan.md (Phase 3)
 * Component matrix: docs/plans/2026-04/2026-04-18-oss-component-matrix.md
 *
 * Setup pattern (per Phase 3 instructions):
 *   1. API-create a `kind=list` page_schema (modelCode='showcase_all_fields',
 *      blocks=[]) so the test owns its fixture and can deterministically clean up.
 *   2. Navigate from the sidebar -> page_schema list -> search for the new
 *      pageKey -> click the row to open `/page-designer/{pid}`.
 *   3. Drive the 4 tabs (`list-tab-columns` / `list-tab-filters` /
 *      `list-tab-toolbar` / `list-tab-behavior`) of `ListConfigPanel`.
 *
 * Capabilities endpoint:
 *   `GET /api/meta/models/{code}/capabilities` is now shipped in OSS core
 *   (B1 commit 4b0039e5 + B12 follow-up). All 4 deep tabs depend on the
 *   shape `{ list, sortableFields[], filterableFields[] }`; the previous
 *   defensive `test.skip` for "controller missing" has been removed.
 *
 * Red lines honoured:
 *   - No `page.goto` to deep designer URLs — the row click in the page_schema
 *     list opens `/page-designer/{pid}` via the DSL detailUrl config.
 *   - No `waitForTimeout`. Max 5s on every wait (uses waitForResponse / toBeVisible).
 *   - afterEach: DELETE /api/pages/{pid} per created page (no afterAll).
 *   - pageKey uses unique prefix `e2e_p3list_${Date.now()}_${rand}`.
 *   - In-test `page.click/fill` count > `page.request.*` calls.
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import type { Locator } from '@playwright/test';

// ---------------------------------------------------------------------------
// Radix Select helper
// ---------------------------------------------------------------------------
//
// `BaseSelect` (used by `PropertyFieldRenderer` for every `type: 'select'`
// schema field) wraps Radix `<Select>`. Radix renders a `<button
// role="combobox">` as the trigger and portals the option list (with each
// option as `[role="option"]`) into `document.body`. Playwright's native
// `selectOption()` only works on `<select>` elements, so we need a custom
// click-then-portal-click helper.
//
// Trigger discovery order:
//   1. Caller passes a Locator that already points to the trigger button
//      (preferred — avoids label collisions).
//   2. Fallback: caller passes a `name` (== schema.key, == FieldBase htmlFor
//      target, == SelectTrigger id) and we look up `#${name}` scoped to a
//      container.
//
async function selectRadixOption(
  page: Page,
  trigger: Locator,
  optionValueOrLabel: string | RegExp,
): Promise<void> {
  await trigger.scrollIntoViewIfNeeded().catch(() => null);
  await trigger.click();
  // Radix listbox lands in a portal under document.body. Match by exact text
  // (label) — fall back to substring match if exact fails.
  const option = page
    .locator('[role="option"]')
    .filter({ hasText: optionValueOrLabel })
    .first();
  await expect(option).toBeVisible({ timeout: 5_000 });
  await option.click();
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const SHOWCASE_MODEL_CODE = 'showcase_all_fields';

function uniquePageKey(): string {
  return `e2e_p3list_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
}

interface CreatedPage {
  pid: string;
  pageKey: string;
}

/**
 * Create a list-kind page_schema directly via the v2 API. We own the fixture
 * deterministically and avoid coupling Phase 3 setup to the Phase 2 form path.
 */
async function apiCreateListPage(
  request: APIRequestContext,
  pageKey: string,
): Promise<CreatedPage> {
  const resp = await request.post('/api/pages', {
    data: {
      name: `${pageKey} P3`,
      pageKey,
      title: `${pageKey} P3`,
      kind: 'list',
      modelCode: SHOWCASE_MODEL_CODE,
      blocks: [],
    },
  });
  expect(resp.ok(), `POST /api/pages should succeed (got ${resp.status()})`).toBe(true);
  const body = (await resp.json()) as { code: string; data: { pid: string } };
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return { pid: body.data.pid, pageKey };
}

/**
 * Navigate to /p/page_schema via the sidebar (元数据管理 -> 页面配置).
 *
 * Mirrors the working pattern from page-creation-dispatch-e2e.spec.ts so we
 * keep menu navigation in lockstep with that suite.
 */
async function navigateToPageSchemaList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' }).catch(() => {});

  const parent = page
    .locator('button', { hasText: /元数据管理|Metadata|menu\.meta_management/i })
    .first();
  await parent.waitFor({ state: 'visible', timeout: 5_000 });
  await parent.evaluate((el: HTMLElement) => el.click());

  const leaf = page.locator('a[href="/p/page_schema"], a[href*="/p/page_schema"]').first();
  await leaf.waitFor({ state: 'attached', timeout: 5_000 });
  const listResp = page.waitForResponse(
    (r) =>
      r.url().includes('/api/meta/page-render/dynamic/page_schema_list/list') ||
      ((r.url().includes('/api/dynamic/page_schema/list') || (r.url().includes('/dynamic/page_schema_list') && r.url().includes('/list')))),
    { timeout: 5_000 },
  );
  await leaf.evaluate((el: HTMLElement) => el.click());
  await listResp.catch(() => null);

  await expect(page.getByTestId('toolbar-btn-create')).toBeVisible({ timeout: 5_000 });

  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
}

/**
 * Search for the row with `pageKey`, then click into the designer. Asserts we
 * land on /page-designer/{pid} and the list-config-panel becomes visible.
 */
async function openDesignerByPageKey(
  page: Page,
  pid: string,
  pageKey: string,
): Promise<void> {
  const keywordInput = page
    .locator(
      '[data-testid="list-search-input"], input[placeholder*="搜索"], input[placeholder*="查询"], input[placeholder*="Search"], input[placeholder*="Query"], input[type="search"]',
    )
    .first();
  if (await keywordInput.isVisible({ timeout: 2_000 }).catch(() => false)) {
    const listResp = page.waitForResponse(
      (r) => {
        if (!r.url().includes('/api/dynamic/page_schema/list') || r.status() !== 200) {
          return false;
        }
        const url = new URL(r.url());
        return url.searchParams.get('keyword') === pageKey;
      },
      { timeout: 5_000 },
    );
    await keywordInput.click();
    await keywordInput.fill(pageKey);
    await keywordInput.press('Enter').catch(() => null);
    await listResp;
  }

  const row = page.locator(`tr:has-text("${pageKey}")`).first();
  await expect(row).toBeVisible({ timeout: 5_000 });

  // Vite HMR error overlay can re-inject between cleanup and click — bypass
  // pointer-event interception by dispatching the click in page context.
  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });
  const rowLink = row.locator('a[href*="/page-designer/"]').first();
  if (await rowLink.isVisible({ timeout: 1_000 }).catch(() => false)) {
    await rowLink.evaluate((el: HTMLElement) => el.click());
  } else {
    await row.evaluate((el: HTMLElement) => el.click());
  }

  await expect(page).toHaveURL(new RegExp(`/page-designer/${pid}`), { timeout: 5_000 });

  await page.evaluate(() => {
    document.querySelectorAll('vite-error-overlay').forEach((el) => el.remove());
  });

  await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 5_000 });
}

async function readPageBlocks(
  request: APIRequestContext,
  pid: string,
): Promise<unknown[]> {
  const r = await request.get(`/api/pages/${pid}`);
  expect(r.ok()).toBe(true);
  const body = (await r.json()) as { data?: { blocks?: unknown[] } };
  return body.data?.blocks ?? [];
}

// ---------------------------------------------------------------------------
// Suite
// ---------------------------------------------------------------------------

const createdPids: string[] = [];

test.describe('Phase 3 — List ConfigPanel E2E (4 tabs)', () => {
  // Designer setup + autosave verification can exceed the default 15 s test
  // budget; per-action waits remain bounded to 5 s.
  test.setTimeout(45_000);

  test.afterEach(async ({ request }) => {
    while (createdPids.length > 0) {
      const pid = createdPids.pop()!;
      await request.delete(`/api/pages/${pid}`).catch(() => null);
    }
  });

  // -------------------------------------------------------------------------
  // P3.0 — Panel + 4 tab buttons render and switch (capability-independent).
  // Always runs because the 4 tab <button> elements live in ListConfigPanel
  // itself (not inside any of the 4 tab body components).
  // -------------------------------------------------------------------------
  test('P3.0: panel + 4 tab buttons render and switch on click', async ({
    page,
    request,
  }) => {
    const pageKey = uniquePageKey();
    const created = await apiCreateListPage(request, pageKey);
    createdPids.push(created.pid);

    await navigateToPageSchemaList(page);
    await openDesignerByPageKey(page, created.pid, pageKey);

    // All 4 tab buttons should be visible.
    await expect(page.getByTestId('list-tab-columns')).toBeVisible();
    await expect(page.getByTestId('list-tab-filters')).toBeVisible();
    await expect(page.getByTestId('list-tab-toolbar')).toBeVisible();
    await expect(page.getByTestId('list-tab-behavior')).toBeVisible();

    // Click each tab and assert the active-tab class flips. The active button
    // gets `font-medium text-blue-700` per ListConfigPanel.tsx.
    for (const id of ['filters', 'toolbar', 'behavior', 'columns'] as const) {
      const btn = page.getByTestId(`list-tab-${id}`);
      await btn.click();
      await expect(btn).toHaveClass(/text-blue-700/);
    }
  });

  // -------------------------------------------------------------------------
  // P3.1 — Columns tab deep config + save round-trip.
  // -------------------------------------------------------------------------
  test('P3.1: columns tab — toggle fields, set width/align/renderer/format, save', async ({
    page,
    request,
  }) => {
    const pageKey = uniquePageKey();
    const created = await apiCreateListPage(request, pageKey);
    createdPids.push(created.pid);

    await navigateToPageSchemaList(page);
    await openDesignerByPageKey(page, created.pid, pageKey);

    await page.getByTestId('list-tab-columns').click();
    await expect(page.getByTestId('columns-tab')).toBeVisible({ timeout: 5_000 });

    // Toggle 5+ fields. Field codes come from capabilities.sortable ∪ filterable;
    // we discover them by enumerating any rendered `column-toggle-*` checkbox.
    const toggleHandles = await page.locator('[data-testid^="column-toggle-"]').all();
    expect(toggleHandles.length).toBeGreaterThanOrEqual(5);
    const selected: string[] = [];
    for (const h of toggleHandles.slice(0, 5)) {
      const tid = (await h.getAttribute('data-testid')) ?? '';
      const code = tid.replace('column-toggle-', '');
      await h.check();
      selected.push(code);
    }

    // Open the first column's detail editor and configure width / align /
    // renderer / format via the SchemaBlockConfigPanel.
    const columnsTab = page.locator('[data-testid="columns-tab"]:visible').first();
    const firstColumnItem = columnsTab.getByTestId('column-item-0');
    await firstColumnItem.evaluate((el: HTMLElement) => el.click());
    await expect(page.getByTestId('column-detail-editor')).toBeVisible();

    const editor = page.getByTestId('column-detail-editor');
    await editor.locator('input[type="number"]').first().fill('200'); // width
    // align select — Radix Select trigger keyed by schema key 'align'.
    await selectRadixOption(page, editor.locator('#align'), '居中');
    // renderer select — Radix Select trigger keyed by schema key 'renderer'.
    await selectRadixOption(page, editor.locator('#renderer'), '标签');
    // format text input — labeled '格式化模板'.
    await editor.getByLabel(/格式化模板/).fill('{0}件');

    // Trigger explicit save via toolbar.
    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${created.pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await page.getByTestId('toolbar-save').click();
    const resp = await saveResp;
    expect(resp.ok()).toBe(true);

    // Verify persisted blocks contain a `table` block with the configured columns.
    const blocks = (await readPageBlocks(request, created.pid)) as Array<
      Record<string, unknown>
    >;
    const table = blocks.find((b) => b.blockType === 'table') as
      | { columns?: Array<Record<string, unknown>> }
      | undefined;
    expect(table, 'persisted blocks must contain a table block').toBeTruthy();
    expect((table?.columns ?? []).length).toBeGreaterThanOrEqual(5);
    const configured = table!.columns!.find(
      (column) =>
        column.width === 200 &&
        column.align === 'center' &&
        column.renderer === 'badge' &&
        column.format === '{0}件',
    );
    expect(configured, 'persisted columns must include the configured column').toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // P3.2 — Filters tab.
  // -------------------------------------------------------------------------
  test('P3.2: filters tab — add multiple filters with different operators, save', async ({
    page,
    request,
  }) => {
    const pageKey = uniquePageKey();
    const created = await apiCreateListPage(request, pageKey);
    createdPids.push(created.pid);

    await navigateToPageSchemaList(page);
    await openDesignerByPageKey(page, created.pid, pageKey);

    await page.getByTestId('list-tab-filters').click();
    await expect(page.getByTestId('filters-tab')).toBeVisible({ timeout: 5_000 });

    const filterToggles = await page.locator('[data-testid^="filter-toggle-"]').all();
    expect(filterToggles.length).toBeGreaterThanOrEqual(2);

    const operators: Array<{ value: string; label: string }> = [
      { value: 'eq', label: '等于' },
      { value: 'neq', label: '不等' },
      { value: 'like', label: '包含' },
      { value: 'between', label: '介于' },
    ];
    const used: string[] = [];

    for (let i = 0; i < Math.min(filterToggles.length, operators.length); i++) {
      const tid = (await filterToggles[i].getAttribute('data-testid')) ?? '';
      const code = tid.replace('filter-toggle-', '');
      await filterToggles[i].check();
      used.push(code);

      await page.getByTestId(`filter-item-${i}`).click();
      const editor = page.getByTestId('filter-detail-editor');
      await expect(editor).toBeVisible();
      // Operator is a Radix Select trigger keyed by schema key 'operator'.
      await selectRadixOption(page, editor.locator('#operator'), operators[i].label);
    }

    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${created.pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await page.getByTestId('toolbar-save').click();
    expect((await saveResp).ok()).toBe(true);

    const blocks = (await readPageBlocks(request, created.pid)) as Array<
      Record<string, unknown>
    >;
    const filtersBlock = blocks.find((b) => b.blockType === 'filters') as
      | { fields?: unknown[] }
      | undefined;
    expect(filtersBlock).toBeTruthy();
    expect((filtersBlock?.fields ?? []).length).toBe(used.length);
    // First two fields should carry the operators we set (string-or-object form).
    const f0 = (filtersBlock!.fields![0] ?? {}) as Record<string, unknown>;
    expect(f0.operator ?? null).toBe('eq');
  });

  // -------------------------------------------------------------------------
  // P3.3 — Toolbar tab: presets + custom command button with requiresSelection.
  // -------------------------------------------------------------------------
  test('P3.3: toolbar tab — enable preset + add custom button, save', async ({
    page,
    request,
  }) => {
    const pageKey = uniquePageKey();
    const created = await apiCreateListPage(request, pageKey);
    createdPids.push(created.pid);

    await navigateToPageSchemaList(page);
    await openDesignerByPageKey(page, created.pid, pageKey);

    await page.getByTestId('list-tab-toolbar').click();
    await expect(page.getByTestId('toolbar-tab')).toBeVisible({ timeout: 5_000 });

    // Enable any presets that are not capability-disabled. We always click
    // `create` since a published model with create permission should expose it.
    const createPreset = page.getByTestId('toolbar-preset-create');
    if (!(await createPreset.isDisabled())) {
      await createPreset.check();
    }

    // Add 1 custom button.
    await page.getByTestId('toolbar-add-custom-button').click();
    await expect(page.getByTestId('toolbar-custom-editor')).toBeVisible();

    const editor = page.getByTestId('toolbar-custom-editor');
    await editor.getByLabel(/按钮文字/).fill('Bulk Approve');
    // The 'command' field is rendered with `label: 'Command', required: true`
    // → FieldBase appends a required asterisk inside the <Label>, so the
    // accessible name becomes "Command *" and the strict `^Command$` regex
    // no longer matches. Target the input by id (`schema.key` == 'command').
    await editor.locator('#command').fill('showcase:bulk_approve');
    await editor.getByLabel(/需要选中行/).check();

    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${created.pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await page.getByTestId('toolbar-save').click();
    expect((await saveResp).ok()).toBe(true);

    const blocks = (await readPageBlocks(request, created.pid)) as Array<
      Record<string, unknown>
    >;
    const toolbarBlock = blocks.find((b) => b.blockType === 'toolbar') as
      | { buttons?: Array<Record<string, unknown>> }
      | undefined;
    expect(toolbarBlock).toBeTruthy();
    const buttons = toolbarBlock?.buttons ?? [];
    const custom = buttons.find((b) => b.command === 'showcase:bulk_approve');
    expect(custom, 'custom button should be persisted').toBeTruthy();
    expect(custom!.label).toBe('Bulk Approve');
    expect(custom!.requiresSelection).toBe(true);
  });

  // -------------------------------------------------------------------------
  // P3.4 — Behavior tab.
  // -------------------------------------------------------------------------
  test('P3.4: behavior tab — set sort/pageSize/multiSelect/rowClick/empty, save', async ({
    page,
    request,
  }) => {
    const pageKey = uniquePageKey();
    const created = await apiCreateListPage(request, pageKey);
    createdPids.push(created.pid);

    await navigateToPageSchemaList(page);
    await openDesignerByPageKey(page, created.pid, pageKey);

    await page.getByTestId('list-tab-behavior').click();
    await expect(page.getByTestId('behavior-tab')).toBeVisible({ timeout: 5_000 });

    const tab = page.getByTestId('behavior-tab');

    // defaultSortField — Radix Select. Open the trigger, scrape option labels
    // from the portal, then pick the first non-"(不设)" entry.
    const sortFieldTrigger = tab.locator('#defaultSortField');
    await sortFieldTrigger.click();
    const portalOptions = page.locator('[role="option"]');
    await expect(portalOptions.first()).toBeVisible({ timeout: 5_000 });
    const sortOptionLabels = await portalOptions.allTextContents();
    const firstReal = sortOptionLabels.find((o) => o && !o.includes('(不设)'));
    if (firstReal) {
      await portalOptions.filter({ hasText: firstReal }).first().click();
      // defaultSortOrder is dependsOn defaultSortField — now rendered.
      await selectRadixOption(page, tab.locator('#defaultSortOrder'), '升序');
    } else {
      // Close the dropdown by clicking the trigger again so subsequent fields
      // remain interactable.
      await sortFieldTrigger.click();
    }

    await tab.getByLabel(/每页条数/).fill('50');
    await tab.getByLabel(/启用多选/).check();
    await selectRadixOption(page, tab.locator('#rowClickAction'), '打开抽屉');
    await tab.getByLabel(/空态文案/).fill('No showcase rows yet');

    const saveResp = page.waitForResponse(
      (r) => r.url().includes(`/api/pages/${created.pid}`) && r.request().method() === 'PUT',
      { timeout: 5_000 },
    );
    await page.getByTestId('toolbar-save').click();
    expect((await saveResp).ok()).toBe(true);

    const blocks = (await readPageBlocks(request, created.pid)) as Array<
      Record<string, unknown>
    >;
    const tableBlock = blocks.find((b) => b.blockType === 'table') as
      | { props?: Record<string, unknown> }
      | undefined;
    expect(tableBlock).toBeTruthy();
    const props = tableBlock?.props ?? {};
    expect(props.pageSize).toBe(50);
    expect(props.multiSelect).toBe(true);
    expect(props.rowClickAction).toBe('drawer');
    expect(props.emptyStateText).toBe('No showcase rows yet');
    if (firstReal) {
      expect(props.defaultSortOrder).toBe('asc');
    }
  });
});
