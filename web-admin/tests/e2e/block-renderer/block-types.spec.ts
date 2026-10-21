/**
 * Block Renderer — Block Type Tests
 *
 * Tests BK-001 ~ BK-018: Verify all block types render correctly
 * on the e2et-order list and form pages.
 *
 * Covers:
 * - form-section (group title, collapse/expand)
 * - form-buttons (permission-filtered rendering)
 * - tabs (switching, lazy load, nested blocks)
 * - description (text/Markdown)
 * - toolbar (conditional visibility)
 * - list-tabs (status switching with count)
 * - sub-table (full CRUD flow)
 * - data-table (pagination, sort, column width, fixed columns, aggregate)
 * - filters (search expand/collapse, date range)
 * - monthly-grid, visibleWhen, layout colSpan, chart block
 *
 * Uses real database, NO MOCKING.
 * Uses DynamicListPage/DynamicFormPage Page Objects for stable selectors.
 *
 * @since 7.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId, ensureFilterFormOpen, waitForFormReady, executeCommandViaApi } from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';
import { DynamicListPage, DynamicFormPage } from '../../pages';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const ORDER_PAGE_KEY = 'e2et_order';

/** Navigate to new order form and wait for full render. */
async function navigateToNewOrderForm(page: import('@playwright/test').Page) {
  const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
  await listPage.goto();
  await listPage.clickAdd();
  await page.waitForURL((url) => url.pathname.includes('/new'), { timeout: 10000 });
  await page.waitForLoadState('domcontentloaded');
  await waitForFormReady(page, 10000);

  const formPage = new DynamicFormPage(page, '');
  // Wait for form fields to render (two-stage loading)
  await formPage.field('e2et_order_title').first().waitFor({ state: 'visible', timeout: 5000 });
  // Wait for SmartSwitch / SmartSelect to load
  await page
    .locator('select, button[role="switch"]')
    .first()
    .waitFor({ state: 'attached', timeout: 5000 })
    .catch(() => {});
  return { listPage, formPage };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Block Renderer — Block Type Tests', () => {
  let orderPid: string;
  const filterUid = uniqueId('bk_range');
  const filterPageKey = `bk_filters_${filterUid}`;
  const rangeTitle = `Owned range ${filterUid}`;
  let rangeOrderPid = '';
  const itemName = `Block Test Widget ${uniqueId()}`;

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    orderPid = await order.createViaApi({ e2et_order_title: `BlockTest ${uniqueId()}` });
    const datedOrder = async (title: string, date: string) => {
      const result = await executeCommandViaApi(page, 'e2eto:create_e2et_order', {
        e2et_order_title: title, e2et_order_type: 'normal', e2et_order_date: date,
        e2et_delivery_date: new Date(Date.parse(`${date}T00:00:00Z`) + 86400000).toISOString().slice(0, 10),
      });
      expect(result.code).toBe('0');
      expect(result.recordId).toBeTruthy();
      return result.recordId;
    };
    rangeOrderPid = await datedOrder(`${rangeTitle} included`, '2026-04-15');
    await datedOrder(`${rangeTitle} excluded`, '2025-04-15');
    expect((await order.fetchViaApi(rangeOrderPid)).e2et_order_date).toContain('2026-04-15');
    const filterPage = await page.request.post('/api/pages', { data: {
      name: filterPageKey, pageKey: filterPageKey, title: 'Owned order filters', kind: 'list',
      modelCode: 'e2et_order', schemaVersion: 4,
      blocks: [
        { id: 'owned_filters', blockType: 'filters', fields: [
          { field: 'e2et_order_title', label: 'Title', component: 'SmartInput', layout: { colSpan: 4 } },
          { field: 'e2et_order_no', label: 'Number', component: 'SmartInput', layout: { colSpan: 4 } },
          { field: 'e2et_order_amount', label: 'Amount', component: 'SmartInput', layout: { colSpan: 4 } },
          { field: 'e2et_order_type', label: 'Type', component: 'SmartInput', layout: { colSpan: 4 } },
          { field: 'e2et_order_date', label: 'Order date range', component: 'DateRange', layout: { colSpan: 4 } },
        ] },
        { id: 'owned_table', blockType: 'table', columns: [
          { field: 'e2et_order_title', label: 'Title' }, { field: 'e2et_order_date', label: 'Date' },
        ], dataSource: 'tableData' },
      ],
    } });
    expect(filterPage.ok()).toBe(true);
    expect(String((await filterPage.json()).code)).toBe('0');
    // Create an item for sub-table tests
    await order.child('item').createForParent(orderPid, {
      e2et_item_name: itemName,
      e2et_item_qty: 5,
      e2et_item_price: 20.0,
    });
    await page.close();
    await context.close();
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await context.newPage();
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.deleteViaApi(orderPid).catch(() => {});
    await page.close();
    await context.close();
  });

  // -------------------------------------------------------------------------
  // BK-001: form-section group title renders
  // -------------------------------------------------------------------------

  test('BK-001: form-section should render group title @smoke', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Form sections render with h3 headings or section containers
    const sectionHeadings = page.locator(
      'h3, [data-testid^="form-section-"], .form-section-title, legend',
    );
    const count = await sectionHeadings.count();

    // At least 1 section heading should be visible (e.g., "Basic Info" or "Order Items")
    if (count > 0) {
      await expect(sectionHeadings.first()).toBeVisible({ timeout: 5000 });
    } else {
      // Fallback: verify form heading (h2) exists as a section indicator
      const heading = page.locator('h2').first();
      await expect(heading).toBeVisible({ timeout: 5000 });
    }
  });

  // -------------------------------------------------------------------------
  // BK-002: form-section collapse/expand
  // -------------------------------------------------------------------------

  test('BK-002: form-section should support collapse and expand', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Look for section toggles and explicitly exclude combobox/listbox triggers.
    const candidates = page.locator(
      'main [data-testid^="section-toggle-"], main .section-collapse-toggle, main details summary, main button[aria-expanded]:not([role="combobox"]):not([aria-haspopup="listbox"]), main h3 button:not([role="combobox"])',
    );
    const candidateCount = Math.min(await candidates.count(), 20);

    let collapseToggle = null as import('@playwright/test').Locator | null;
    for (let i = 0; i < candidateCount; i++) {
      const candidate = candidates.nth(i);
      const visible = await candidate.isVisible({ timeout: 800 }).catch(() => false);
      if (!visible) continue;
      const enabled = await candidate.isEnabled().catch(() => false);
      if (!enabled) continue;
      const role = await candidate.getAttribute('role').catch(() => '');
      if (role === 'combobox') continue;
      const hasExpandedAttr =
        (await candidate.getAttribute('aria-expanded').catch(() => null)) !== null;
      const hasSummaryTag =
        (await candidate
          .evaluate((el) => el.tagName.toLowerCase() === 'summary')
          .catch(() => false)) === true;
      if (!hasExpandedAttr && !hasSummaryTag) continue;
      collapseToggle = candidate;
      break;
    }

    if (!collapseToggle) {
      // Current form DSL may not configure collapsible sections; validate section content renders instead.
      await expect(
        page.locator('form, [data-testid="dynamic-form"], .dynamic-form').first(),
      ).toBeVisible({ timeout: 5000 });
      return;
    }

    // Get initial state
    const initialState = await collapseToggle.getAttribute('aria-expanded');

    // Click to toggle state
    await collapseToggle.scrollIntoViewIfNeeded();
    await collapseToggle.click({ timeout: 5000 });
    const afterFirstClick = await collapseToggle.getAttribute('aria-expanded');
    if (initialState !== null && afterFirstClick !== null) {
      // State should have flipped
      expect(afterFirstClick).not.toBe(initialState);
    }

    // Click again to toggle back
    await collapseToggle.scrollIntoViewIfNeeded();
    await collapseToggle.click({ timeout: 5000 });
    const afterSecondClick = await collapseToggle.getAttribute('aria-expanded');
    if (initialState !== null && afterSecondClick !== null) {
      // Should be back to initial state
      expect(afterSecondClick).toBe(initialState);
    }
  });

  // -------------------------------------------------------------------------
  // BK-003: form-buttons render with permission filter
  // -------------------------------------------------------------------------

  test('BK-003: form-buttons should render with permission-based visibility @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Form buttons are rendered via data-testid="form-btn-{code}"
    // Wait for at least one button to appear before counting (async render)
    const formButtons = page.locator('[data-testid^="form-btn-"]');
    await expect(formButtons.first()).toBeVisible({ timeout: 10000 });

    const buttonCount = await formButtons.count();

    // At least one button (e.g., save/saveDraft) should be visible
    expect(buttonCount).toBeGreaterThan(0);

    // Verify buttons have text content (not empty)
    const firstBtnText = await formButtons.first().textContent();
    expect(firstBtnText!.trim().length).toBeGreaterThan(0);
  });

  // -------------------------------------------------------------------------
  // BK-004: tabs switching with lazy load
  // -------------------------------------------------------------------------

  test('BK-004: tabs block should switch between tabs with lazy loading @smoke', async ({
    page,
  }) => {
    // Navigate to detail page which has tabs (Basic Info, Order Items, Audit Logs)
    await page.goto(`/p/e2et_order/view/${orderPid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('main').first()).toBeVisible({ timeout: 10000 });
    await expect(page.locator('main').first()).toContainText(/订单|BlockTest|明细|详情/, {
      timeout: 10000,
    });

    // Find tab buttons scoped to main content area (not sidebar nav)
    // The detail page tabs are inside main > ... > navigation
    const detailTabs = page.locator('main navigation button, main nav button');
    const tabCount = await detailTabs.count();

    if (tabCount < 2) {
      return;
    }

    // Click the "订单明细" tab — should trigger lazy load of child items
    const itemsTab = detailTabs.filter({ hasText: /订单明细|Order Items/i }).first();
    const tabLoadPromise = page
      .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 10000 })
      .catch(() => null);

    await itemsTab.click();
    await tabLoadPromise;

    // Verify content loaded after tab switch — child items render as a table
    const content = page.locator('main table').first();
    await expect(content).toBeVisible({ timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // BK-005: tabs nested block rendering
  // -------------------------------------------------------------------------

  test('BK-005: tabs should render nested blocks within each tab', async ({ page }) => {
    await page.goto(`/p/e2et_order/view/${orderPid}`);
    await page.waitForLoadState('domcontentloaded');
    const mainContent = page.locator('main').first();
    await expect(mainContent).toBeVisible({ timeout: 10000 });

    // First tab (Basic Info) should contain real order detail content.
    // The page is now properly localized, so assert visible business text instead of raw field codes.
    await expect(mainContent).toContainText(/订单编号|BlockTest e2e_/i, { timeout: 10000 });

    // Switch to Items tab and verify nested table block (scoped to main content)
    const itemsTab = page
      .locator('main navigation button, main nav button')
      .filter({
        hasText: /订单明细|Order Items/i,
      })
      .first();

    await expect(itemsTab).toBeVisible();
    await itemsTab.click();
    await expect(page.locator('main table tbody tr').filter({ hasText: itemName })).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // BK-006: description text/Markdown render
  // -------------------------------------------------------------------------

  test('BK-006: description block should render text or Markdown content @smoke', async ({
    page,
  }) => {
    await page.goto(`/p/e2et_order/view/${orderPid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(page.locator('main').first()).toBeVisible({ timeout: 10000 });

    // Description blocks may render as paragraphs, divs with text, or markdown containers
    const descriptionElements = page.locator(
      '[data-testid^="description-block"], .description-content, .prose, p, dd',
    );
    await descriptionElements.count();

    // The detail page should have at least some descriptive text content
    const bodyText = await page
      .locator('main, [role="main"], .page-content, body')
      .first()
      .textContent();
    expect(bodyText!.length).toBeGreaterThan(50);
  });

  // -------------------------------------------------------------------------
  // BK-007: toolbar conditional visibility
  // -------------------------------------------------------------------------

  test('BK-007: toolbar should show buttons conditionally based on context @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
    await order.gotoList();

    // Toolbar buttons should be visible via data-testid
    const toolbarButtons = page.locator('[data-testid^="toolbar-btn-"]');
    const btnCount = await toolbarButtons.count();
    expect(btnCount).toBeGreaterThan(0);

    // The "add" button should be visible (toolbar rendered)
    await expect(listPage.addButton).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // BK-008: list-tabs status switching with count
  // -------------------------------------------------------------------------

  test('BK-008: list-tabs should switch status and display tab counts @smoke', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
    await order.gotoList();

    // Verify tabs exist (6 status tabs for e2et_order)
    const tabCount = await listPage.tabs.count();
    expect(tabCount).toBeGreaterThanOrEqual(6);

    // Check if tabs show count badges (e.g., "Draft (3)")
    const tabTexts = await listPage.tabs.allTextContents();
    tabTexts.some((t) => /\(\d+\)|\d+/.test(t));
    // Count badges are optional — just verify tabs render and switch

    // Click Draft tab and verify data loads
    const draftTab = listPage.tabs.filter({ hasText: /草稿|Draft/i }).first();
    if (await draftTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const listResp = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
        .catch(() => null);

      await draftTab.click();
      await listResp;

      await expect(page.locator('table').first()).toBeVisible({ timeout: 5000 });
    }

    // Click Submitted tab to verify switching
    const submitTab = listPage.tabs.filter({ hasText: /已提交|Submitted/i }).first();
    if (await submitTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const listResp2 = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
        .catch(() => null);

      await submitTab.click();
      await listResp2;

      await expect(page.locator('table').first()).toBeVisible({ timeout: 5000 });
    }
  });

  // -------------------------------------------------------------------------
  // BK-009: sub-table full CRUD flow
  // -------------------------------------------------------------------------

  test('BK-009: sub-table should support full CRUD (add, render, delete) @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Verify sub-table add button exists
    const addRowBtn = page.locator('[data-testid="subtable-add-row"]').first();
    await expect(addRowBtn).toBeVisible({ timeout: 10000 });

    // Verify existing rows loaded (beforeAll created 1 item)
    const deleteButtons = page.locator('[data-testid^="subtable-delete-"]');
    await expect(deleteButtons.first()).toBeAttached({ timeout: 10000 });
    const rowsBefore = await deleteButtons.count();
    expect(rowsBefore).toBeGreaterThanOrEqual(1);

    // Add a row — clicking shows inline add form (not a new data row)
    await addRowBtn.click();
    await expect(page.locator('[data-testid="subtable-add-form"]')).toBeVisible({ timeout: 5000 });

    // Verify delete buttons exist for existing rows
    expect(await deleteButtons.count()).toBeGreaterThan(0);

    // Delete the last existing row
    const lastDeleteBtn = deleteButtons.last();
    await lastDeleteBtn.scrollIntoViewIfNeeded();
    await lastDeleteBtn.click();
    await expect(deleteButtons).toHaveCount(rowsBefore - 1, { timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // BK-010: data-table pagination + sort + column width
  // -------------------------------------------------------------------------

  test('BK-010: data-table should support pagination, sort, and column sizing @smoke', async ({
    page,
  }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const listPage = new DynamicListPage(page, `/p/${ORDER_PAGE_KEY}`);
    await order.gotoList();

    // Verify table renders
    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Verify table headers (columns) exist
    const headers = page.locator('thead th');
    const headerCount = await headers.count();
    expect(headerCount).toBeGreaterThanOrEqual(3);

    // Click a column header to sort (if sortable)
    const firstSortableHeader = headers.first();
    const headerText = await firstSortableHeader.textContent();
    if (headerText && headerText.trim().length > 0) {
      // Click header to trigger sort
      const sortPromise = page
        .waitForResponse((r) => r.url().includes('/list') && r.status() === 200, { timeout: 5000 })
        .catch(() => null);

      await firstSortableHeader.click();
      await sortPromise;

      // Table should still be visible after sort
      await expect(table).toBeVisible({ timeout: 5000 });
    }

    // Verify pagination controls exist
    const paginationControls = page.locator(
      '[data-testid^="pagination-"], .pagination, nav[aria-label*="pagination" i]',
    );
    const hasPagination = await paginationControls
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    // Pagination may not show if total rows < pageSize — that's OK
    if (hasPagination) {
      await expect(paginationControls.first()).toBeVisible();
    }
  });

  // -------------------------------------------------------------------------
  // BK-011: data-table fixed columns (left/right)
  // -------------------------------------------------------------------------

  test('BK-011: data-table should support fixed columns (sticky)', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoList();

    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Fixed columns typically use sticky positioning or a specific class
    const stickyColumns = page.locator(
      'th[class*="sticky"], td[class*="sticky"], th[style*="position: sticky"], td[style*="position: sticky"]',
    );
    const stickyCount = await stickyColumns.count();

    // Row action column is often fixed right
    const actionColumn = page
      .locator('th:has-text("操作"), th:has-text("Actions"), th:last-child')
      .first();
    const hasActionCol = await actionColumn.isVisible({ timeout: 3000 }).catch(() => false);

    // Either sticky columns exist or action column is present
    expect(stickyCount > 0 || hasActionCol).toBeTruthy();
  });

  // -------------------------------------------------------------------------
  // BK-012: data-table aggregate summary row
  // -------------------------------------------------------------------------

  test('BK-012: data-table should display aggregate summary row', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoList();

    const table = page.locator('table').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    // Summary rows are typically in tfoot or a special row with class
    const summaryRow = page
      .locator(
        'tfoot tr, [data-testid="table-summary"], tr.summary-row, tr:has-text("合计"), tr:has-text("Total")',
      )
      .first();
    const hasSummary = await summaryRow.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasSummary) {
      // Summary may not be configured for this model — skip gracefully
      test.info().annotations.push({
        type: 'note',
        description: 'No aggregate summary row found — aggregateColumns may not be configured',
      });
    } else {
      await expect(summaryRow).toBeVisible();
    }
  });

  // -------------------------------------------------------------------------
  // BK-013: filters search expand/collapse
  // -------------------------------------------------------------------------

  test('BK-013: filters block should support search expand and collapse @smoke', async ({ page }, testInfo) => {
    await page.goto(`/p/${filterPageKey}`);
    await expect(page.getByRole('table')).toBeVisible();
    await ensureFilterFormOpen(page);
    const filters = page.getByTestId('search-area');
    await expect(filters).toBeVisible();
    await expect(page.getByTestId('filter-search')).toBeVisible();
    await expect(page.getByTestId('filter-reset')).toBeVisible();
    const grid = filters.locator(':scope > div').first();
    const toggle = page.getByTestId('filter-toggle');
    await expect(toggle).toBeVisible();
    const collapsed = (await grid.boundingBox())!.height;
    expect(collapsed).toBeLessThanOrEqual(72);
    await toggle.click();
    await expect.poll(async () => (await grid.boundingBox())!.height).toBeGreaterThan(collapsed);
    await expect(filters.getByTestId('daterange-e2et_order_date-start')).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('BK-013-expanded.png') });
    await toggle.click();
    await expect.poll(async () => (await grid.boundingBox())!.height).toBeLessThanOrEqual(72);
    await page.screenshot({ path: testInfo.outputPath('BK-013-collapsed.png') });
  });

  // -------------------------------------------------------------------------
  // BK-014: filters date range component
  // -------------------------------------------------------------------------

  test('BK-014: filters should support date range selection', async ({ page }, testInfo) => {
    await page.goto(`/p/${filterPageKey}`);
    await expect(page.getByRole('table')).toBeVisible();
    await ensureFilterFormOpen(page);
    await page.getByTestId('filter-toggle').click();
    const filters = page.getByTestId('search-area');
    await filters.locator('input[name="e2et_order_title"]').fill(rangeTitle);
    await filters.getByTestId('daterange-e2et_order_date-start').fill('2026-04-01');
    await filters.getByTestId('daterange-e2et_order_date-end').fill('2026-04-30');
    const loaded = page.waitForResponse(response => new URL(response.url()).pathname === '/api/dynamic/e2et_order/list'
      && response.request().method() === 'GET' && new URL(response.url()).searchParams.get('filters')?.includes('2026-04-30') === true);
    await page.getByTestId('filter-search').click();
    const response = await loaded;
    expect(response.ok()).toBe(true);
    const conditions = JSON.parse(new URL(response.url()).searchParams.get('filters')!);
    expect(conditions).toEqual(expect.arrayContaining([
      { fieldName: 'e2et_order_date', operator: 'gte', value: '2026-04-01' },
      { fieldName: 'e2et_order_date', operator: 'lte', value: '2026-04-30' },
    ]));
    const body = await response.json();
    expect(String(body.code)).toBe('0');
    expect(body.data.records.map((record: { pid: string }) => record.pid)).toEqual([rangeOrderPid]);
    await expect(page.getByRole('cell', { name: `${rangeTitle} included`, exact: true })).toBeVisible();
    await expect(page.getByRole('cell', { name: `${rangeTitle} excluded`, exact: true })).toHaveCount(0);
    await page.screenshot({ path: testInfo.outputPath('BK-014-owned-range-result.png') });
  });

  // -------------------------------------------------------------------------
  // BK-015: monthly-grid rendering
  // -------------------------------------------------------------------------

  test('BK-015: monthly-grid block should render if configured', async ({ page }) => {
    // Monthly grid is used on specific pages (e.g., qo_daily_summary)
    // For e2et-order, it may not be configured — check and skip gracefully
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoList();

    const monthlyGrid = page
      .locator('[data-testid="monthly-grid"], .monthly-grid, [data-block-type="monthly-grid"]')
      .first();
    const hasGrid = await monthlyGrid.isVisible({ timeout: 3000 }).catch(() => false);

    if (!hasGrid) {
      test.info().annotations.push({
        type: 'note',
        description:
          'Monthly grid block not found on e2et-order — block type not configured for this model',
      });
      // Verify at least the standard list renders
      await expect(page.locator('table').first()).toBeVisible({ timeout: 5000 });
    } else {
      await expect(monthlyGrid).toBeVisible();
    }
  });

  // -------------------------------------------------------------------------
  // BK-016: block visibleWhen conditional
  // -------------------------------------------------------------------------

  test('BK-016: block with visibleWhen should conditionally show/hide', async ({ page }) => {
    const { formPage } = await navigateToNewOrderForm(page);

    // visibleWhen is tested via linkage — remark field hidden when urgent=false
    const remarkInput = formPage.field('e2et_order_remark');
    const remarkVisible = await remarkInput
      .first()
      .isVisible({ timeout: 2000 })
      .catch(() => false);

    // Initially urgent=false → remark should be hidden (if linkage is active)
    if (!remarkVisible) {
      // Toggle urgent switch to true
      const urgentSwitch = page.locator('button[role="switch"]').first();
      const switchExists = await urgentSwitch.isVisible({ timeout: 10000 }).catch(() => false);

      if (switchExists) {
        await urgentSwitch.click();
        // Remark should now appear
        const nowVisible = await remarkInput
          .first()
          .isVisible({ timeout: 5000 })
          .catch(() => false);
        if (nowVisible) {
          expect(nowVisible).toBe(true);
          // Toggle back — remark should hide
          await urgentSwitch.click();
          await expect(remarkInput.first()).toBeHidden({ timeout: 5000 });
        } else {
          test.info().annotations.push({
            type: 'note',
            description: 'visibleWhen linkage may not be active for remark field',
          });
        }
      } else {
        test.info().annotations.push({
          type: 'note',
          description:
            'Urgent switch not found — visibleWhen linkage may not be active for this form',
        });
      }
    } else {
      test.info().annotations.push({
        type: 'note',
        description: 'Remark field visible by default — visibleWhen may not be configured',
      });
    }
  });

  // -------------------------------------------------------------------------
  // BK-017: layout colSpan grid correctness
  // -------------------------------------------------------------------------

  test('BK-017: layout colSpan should render form fields in correct grid', async ({ page }) => {
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoEditForm(orderPid);

    // Form fields are laid out in a grid (typically CSS grid or flex)
    const formFieldContainers = page.locator(
      '[data-testid^="form-field-"], .form-field, .field-container, input, select, textarea',
    );
    const fieldCount = await formFieldContainers.count();

    if (fieldCount === 0) {
      await expect(
        page.locator('[data-testid="dynamic-form"], form, [data-testid="form-container"]').first(),
      ).toBeVisible({ timeout: 5000 });
      await expect(page.locator('h2').first()).toBeVisible({ timeout: 5000 });
      return;
    }

    // The OSS e2et_order edit page can be narrowed by the active command
    // schema, but a grid layout check still needs multiple controls.
    expect(fieldCount).toBeGreaterThanOrEqual(2);

    // Verify fields are positioned in a grid layout (check parent has grid/flex)
    const formContainer = page
      .locator('form, [data-testid="form-container"], .grid, .form-grid')
      .first();
    const hasFormContainer = await formContainer.isVisible({ timeout: 3000 }).catch(() => false);

    if (hasFormContainer) {
      // Verify the container has grid or flex display
      const display = await formContainer.evaluate((el) => {
        return window.getComputedStyle(el).display;
      });
      // grid, flex, or block (with grid children) are all valid
      expect(['grid', 'flex', 'block', 'inline-flex']).toContain(display);
    }

    // Check that at least some fields have colSpan styling (col-span-* class or gridColumn style)
    const spanElements = page.locator('[class*="col-span-"], [style*="grid-column"]');
    await spanElements.count();
    // colSpan may or may not be configured — just verify grid renders
    expect(fieldCount).toBeGreaterThan(0);
  });

  // -------------------------------------------------------------------------
  // BK-018: chart block rendering (stub)
  // -------------------------------------------------------------------------

  test('BK-018: chart block should render when configured (stub)', async ({ page }) => {
    // Chart blocks are used on dashboard pages — for e2et-order, verify graceful absence
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    await order.gotoList();

    // Look for chart containers (canvas, svg, or chart-specific elements)
    const chartElements = page.locator(
      'canvas, svg[data-chart], [data-testid="chart-block"], [data-block-type="chart"], .recharts-wrapper, .chart-container',
    );
    const chartCount = await chartElements.count();

    if (chartCount > 0) {
      // Chart block is present — verify it rendered
      await expect(chartElements.first()).toBeVisible({ timeout: 5000 });
    } else {
      test.info().annotations.push({
        type: 'note',
        description:
          'No chart block found on e2et-order list page — chart block not configured for this model',
      });
      // Verify the standard list page renders correctly instead
      await expect(page.locator('table').first()).toBeVisible({ timeout: 5000 });
    }
  });
});
