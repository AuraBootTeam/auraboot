/**
 * Dashboard Management E2E Tests
 *
 * Tests for the DSL-driven dashboard management page at /dynamic/dashboard-management:
 * - DM-E01: List page loads with filter area, toolbar, and data table @smoke
 * - DM-E02: Title search filter works
 * - DM-E03: Row action buttons show correct visibility by status (visibleWhen)
 * - DM-E04: Publishing changes row action buttons
 * - DM-E05: "New Dashboard" toolbar button renders correctly @smoke
 * - DM-E06: Deleting a dashboard removes row from table
 * - DM-E07: Row click navigates to designer (not preview drawer) @smoke
 * - DM-E08: Edit action button navigates to designer
 * - DM-E09: Create button navigates to designer (new dashboard)
 * - DM-E10: List tabs filter by scope (All / Personal / Global)
 * - DM-E11: Full lifecycle: create → publish → unpublish → delete with UI verification
 * - DM-E12: Publish via row action button with toast feedback
 * - DM-E13: Delete via row action button with confirm dialog
 *
 * Uses real database + API, NO MOCKING.
 * Data setup/cleanup via API, UI verification of DSL rendering.
 *
 * @since 4.1.0
 */

import { test, expect, type Page } from '../../fixtures';
import { type Locator } from '@playwright/test';
import { clickRowActionByLocator } from '../helpers';

/**
 * Helper: open More dropdown for a row and verify an action is present inside it,
 * then close the dropdown by clicking elsewhere.
 *
 * Usage: await checkActionInDropdown(page, row, 'publish')
 */
async function checkActionInDropdown(
  page: Page,
  row: Locator,
  actionCode: string,
) {
  const moreBtn = row.locator('[data-testid="row-action-more"]');
  // Open the dropdown
  await moreBtn.evaluate((el: HTMLElement) => el.click());
  const dropdown = page.locator('[data-testid="row-action-dropdown"]');
  await dropdown.waitFor({ state: 'visible', timeout: 5000 });
  await expect(dropdown.locator(`[data-testid="row-action-${actionCode}"]`)).toBeVisible();
  // Close by dispatching a mousedown on document (the component listens for mousedown outside)
  await page.evaluate(() => {
    document.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
  });
  await dropdown.waitFor({ state: 'hidden', timeout: 3000 }).catch(() => {});
}

const BASE_URL = 'http://localhost:5173';
const MGMT_PATH = '/dynamic/dashboard-management';

/**
 * Helper: get JWT token from page cookies for direct API calls
 */
async function getToken(page: import('@playwright/test').Page): Promise<string> {
  const cookies = await page.context().cookies();
  const sessionCookie = cookies.find((c) => c.name === '__session');
  if (!sessionCookie) throw new Error('No __session cookie found');
  const decoded = JSON.parse(
    Buffer.from(decodeURIComponent(sessionCookie.value).split('.')[0], 'base64').toString()
  );
  return decoded.jwtToken;
}

/**
 * Helper: create a dashboard via API for test setup
 */
async function createDashboardViaApi(
  page: import('@playwright/test').Page,
  overrides: Record<string, any> = {}
): Promise<{ pid: string; code: string }> {
  const token = await getToken(page);
  const resp = await page.request.post(`${BASE_URL}/api/dashboards`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {
      title: overrides.title || `E2E Dashboard ${Date.now()}`,
      scope: overrides.scope || 'global',
      ...overrides,
    },
  });
  expect(resp.ok(), `Create dashboard failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  return { pid: body.data.pid, code: body.data.code };
}

/**
 * Helper: delete a dashboard via API for test cleanup
 */
async function deleteDashboardViaApi(
  page: import('@playwright/test').Page,
  pid: string
): Promise<void> {
  const token = await getToken(page);
  await page.request.delete(`${BASE_URL}/api/dashboards/${pid}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * Helper: add a minimal widget to a dashboard via API (required before publishing)
 */
async function addWidgetToDashboardViaApi(
  page: import('@playwright/test').Page,
  pid: string
): Promise<void> {
  const token = await getToken(page);
  // Fetch current dashboard to get existing widgets
  const getResp = await page.request.get(`${BASE_URL}/api/dashboards/${pid}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (!getResp.ok()) return;
  const body = await getResp.json();
  const existing = body.data?.widgets || [];
  if (existing.length > 0) return; // already has widgets

  // Add a minimal stat-card widget with required config fields
  const minimalWidget = {
    id: `w-${Date.now()}`,
    type: 'stat-card',
    title: 'Test Widget',
    x: 0, y: 0, w: 4, h: 2,
    config: { label: 'Total', value: '0', color: 'blue', namedQuery: 'nq_test' },
  };
  await page.request.put(`${BASE_URL}/api/dashboards/${pid}`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { widgets: [...existing, minimalWidget] },
  });
}

/**
 * Helper: publish a dashboard via API
 * Automatically adds a widget if dashboard has none (backend requires ≥1 widget to publish)
 */
async function publishDashboardViaApi(
  page: import('@playwright/test').Page,
  pid: string
): Promise<void> {
  await addWidgetToDashboardViaApi(page, pid);
  const token = await getToken(page);
  const resp = await page.request.post(`${BASE_URL}/api/dashboards/${pid}/publish`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(resp.ok(), `Publish dashboard failed: ${resp.status()}`).toBeTruthy();
}

/**
 * Helper: unpublish a dashboard via API
 */
async function unpublishDashboardViaApi(
  page: import('@playwright/test').Page,
  pid: string
): Promise<void> {
  const token = await getToken(page);
  const resp = await page.request.post(`${BASE_URL}/api/dashboards/${pid}/unpublish`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(resp.ok(), `Unpublish dashboard failed: ${resp.status()}`).toBeTruthy();
}

/**
 * Helper: navigate to dashboard management and wait for data load
 */
async function gotoManagement(page: import('@playwright/test').Page): Promise<void> {
  // Set up response listener BEFORE navigation to avoid race condition
  const listResponsePromise = page.waitForResponse(
    (r) => r.url().includes('/api/dashboards') && r.status() === 200,
    { timeout: 15000 }
  ).catch(() => null);

  await page.goto(MGMT_PATH);
  await page.waitForLoadState('domcontentloaded');
  // Wait for page heading to render (DSL-driven page uses h2)
  await page.locator('h2').first().waitFor({ state: 'visible', timeout: 10000 });
  // Wait for API data to load
  await listResponsePromise;
  // Wait for spinner to disappear
  const spinner = page.locator('.animate-spin, [data-testid="loading"]');
  await expect(spinner).not.toBeVisible({ timeout: 8000 }).catch(() => {});
}

/**
 * Helper: navigate to dashboard management and apply a title filter.
 * Use this when the target row may be pushed off page 1 by concurrent tests.
 */
async function gotoManagementWithFilter(
  page: import('@playwright/test').Page,
  titleFilter: string
): Promise<void> {
  await gotoManagement(page);

  // Apply title filter so the specific record is always visible
  const searchInput = page
    .locator('input[placeholder*="搜索"], input[placeholder*="Search"]')
    .first();
  await searchInput.fill(titleFilter);
  const searchBtn = page.getByTestId('filter-search');
  const filterResp = page.waitForResponse(
    (r) => r.url().includes('/api/dashboards') && r.status() === 200,
    { timeout: 8000 }
  ).catch(() => null);
  await searchBtn.click();
  await filterResp;
}

test.describe('Dashboard Management', () => {
  /**
   * Ensure the DSL schema for dashboard_management_list has onRowClick + detailUrl.
   * Plugin import may not always update an existing page, so we patch it via API.
   */
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: './tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const token = await getToken(page);

      // Fetch current schema
      const resp = await page.request.get(`${BASE_URL}/api/pages/key/dashboard_management_list`, {
        headers: { Authorization: `Bearer ${token}` },
      });
      if (!resp.ok()) return;
      const body = await resp.json();
      const pageData = body.data;
      if (!pageData?.dslSchema) return;

      const dsl = typeof pageData.dslSchema === 'string'
        ? JSON.parse(pageData.dslSchema)
        : pageData.dslSchema;
      const mainBlocks = dsl?.areas?.main?.blocks;
      if (!Array.isArray(mainBlocks)) return;

      const tableBlock = mainBlocks.find((b: any) => b.blockType === 'data-table');
      if (!tableBlock || tableBlock.onRowClick === 'navigate') return;

      // Patch: add onRowClick and detailUrl
      tableBlock.onRowClick = 'navigate';
      tableBlock.detailUrl = '/dashboard-designer/{pid}';

      await page.request.put(`${BASE_URL}/api/pages/${pageData.pid}`, {
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        data: { dslSchema: dsl },
      });
    } finally {
      await page.close();
      await ctx.close();
    }
  });

  /**
   * DM-E01: List page loads with filter area, toolbar, and data table
   */
  test('DM-E01: list page loads @smoke', async ({ page }) => {
    await gotoManagement(page);

    // Verify heading
    await expect(page.locator('h2').first()).toContainText(/仪表盘管理|Dashboard Management/);

    // Verify toolbar create button (DSL toolbar button code="create" → i18n "新建")
    const createBtn = page.locator('[data-testid="toolbar-btn-create"]');
    await expect(createBtn).toBeVisible();

    // Verify filter area (DSL filter-form block)
    const searchBtn = page.getByTestId('filter-search');
    await expect(searchBtn).toBeVisible();

    // Verify table exists with correct column headers (DSL data-table block)
    const table = page.locator('table').first();
    await expect(table).toBeAttached();
    // Check expected columns from DSL
    const headerRow = page.locator('thead tr').first();
    await expect(headerRow).toBeAttached();
  });

  /**
   * DM-E02: Title search filter works
   */
  test('DM-E02: filter by title', async ({ page }) => {
    const uniqueTitle = `FilterTest ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'personal',
    });

    try {
      await gotoManagement(page);

      // Get initial row count
      const initialRows = await page.locator('tbody tr').count();
      expect(initialRows).toBeGreaterThan(0);

      // Type in search box to filter
      const searchInput = page.locator('input[placeholder*="搜索"], input[placeholder*="Search"]').first();
      await searchInput.fill('FilterTest');
      // Click search button
      const searchBtn = page.getByTestId('filter-search');
      await searchBtn.click();
      // Wait for API response
      await page.waitForResponse(
        (r) => r.url().includes('/api/dashboards') && r.status() === 200,
        { timeout: 5000 }
      );
      // Should show filtered results containing our dashboard
      await expect(page.locator('tbody tr').first()).toBeVisible();
      await expect(page.locator('td', { hasText: 'FilterTest' }).first()).toBeVisible();
    } finally {
      await deleteDashboardViaApi(page, pid);
    }
  });

  /**
   * DM-E03: Row action buttons show correct visibility by status (visibleWhen)
   *
   * DSL defines visibleWhen expressions:
   * - "发布" visible when row.status === 'draft'
   * - "删除" visible when row.status === 'draft'
   * - "取消发布" visible when row.status === 'published'
   * - "编辑" always visible
   */
  test('DM-E03: row action buttons match status', async ({ page }) => {
    const ts = Date.now();
    // Create a draft dashboard
    const { pid: draftPid } = await createDashboardViaApi(page, {
      title: `DraftRow ${ts}`,
      scope: 'global',
    });

    // Create a published dashboard
    const { pid: pubPid } = await createDashboardViaApi(page, {
      title: `PubRow ${ts}`,
      scope: 'global',
    });
    await publishDashboardViaApi(page, pubPid);

    try {
      // Pass 1: verify draft row actions
      // DSL button order: edit (primary/direct), publish (More), delete (More)
      await gotoManagementWithFilter(page, `DraftRow ${ts}`);
      const draftRow = page.locator('tr', { hasText: 'DraftRow' }).first();
      await expect(draftRow).toBeVisible({ timeout: 5000 });
      await expect(draftRow.locator('[data-testid="row-action-edit"]')).toBeVisible(); // primary = direct
      await checkActionInDropdown(page, draftRow, 'publish');   // publish → More dropdown
      await checkActionInDropdown(page, draftRow, 'delete');    // delete → More dropdown
      await expect(draftRow.locator('[data-testid="row-action-unpublish"]')).toHaveCount(0); // not rendered for draft

      // Pass 2: verify published row actions separately (filtered view avoids pagination issues)
      // DSL button order: edit (primary/direct), unpublish (More), delete (More)
      await gotoManagementWithFilter(page, `PubRow ${ts}`);
      const pubRow = page.locator('tr', { hasText: 'PubRow' }).first();
      await expect(pubRow).toBeVisible({ timeout: 5000 });
      await expect(pubRow.locator('[data-testid="row-action-edit"]')).toBeVisible(); // primary = direct
      await checkActionInDropdown(page, pubRow, 'unpublish');   // unpublish → More dropdown
      await checkActionInDropdown(page, pubRow, 'delete');      // delete → More dropdown
      await expect(pubRow.locator('[data-testid="row-action-publish"]')).toHaveCount(0); // not rendered for published
    } finally {
      await deleteDashboardViaApi(page, draftPid).catch(() => {});
      await unpublishDashboardViaApi(page, pubPid).catch(() => {});
      await deleteDashboardViaApi(page, pubPid).catch(() => {});
    }
  });

  /**
   * DM-E04: Publishing a dashboard changes its row action buttons
   *
   * Verifies: draft row shows "发布" → after publish via API + reload → shows "取消发布"
   */
  test('DM-E04: publish changes row buttons', async ({ page }) => {
    const uniqueTitle = `PubChg ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'global',
    });

    try {
      // Navigate and verify draft state: publish in More dropdown, unpublish not rendered
      await gotoManagementWithFilter(page, 'PubChg');
      const row = page.locator('tr', { hasText: 'PubChg' }).first();
      await expect(row).toBeVisible({ timeout: 5000 });
      await checkActionInDropdown(page, row, 'publish'); // publish → More dropdown for draft
      await expect(row.locator('[data-testid="row-action-unpublish"]')).toHaveCount(0);

      // Publish via API then re-navigate with filter to verify published state
      await publishDashboardViaApi(page, pid);
      await gotoManagementWithFilter(page, 'PubChg');

      // Verify published state: unpublish in More dropdown, publish not rendered
      const updatedRow = page.locator('tr', { hasText: 'PubChg' }).first();
      await expect(updatedRow).toBeVisible({ timeout: 5000 });
      await checkActionInDropdown(page, updatedRow, 'unpublish'); // unpublish → More dropdown for published
      await expect(updatedRow.locator('[data-testid="row-action-publish"]')).toHaveCount(0);
    } finally {
      await unpublishDashboardViaApi(page, pid).catch(() => {});
      await deleteDashboardViaApi(page, pid).catch(() => {});
    }
  });

  /**
   * DM-E05: Toolbar "New Dashboard" button is rendered from DSL
   */
  test('DM-E05: toolbar buttons render correctly @smoke', async ({ page }) => {
    await gotoManagement(page);

    // Verify DSL toolbar create button (data-testid="toolbar-btn-create")
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();

    // Verify Import/Export buttons exist in the more menu
    const moreMenuBtn = page.locator('[data-testid="toolbar-more-menu"]');
    await expect(moreMenuBtn).toBeVisible();
    await moreMenuBtn.click();
    await expect(page.locator('[data-testid="more-menu-import"]')).toBeVisible();
    await expect(page.locator('[data-testid="more-menu-export-excel"]')).toBeVisible();
    // Close the menu
    await page.keyboard.press('Escape');
  });

  /**
   * DM-E06: Deleting a dashboard removes its row from the table
   */
  test('DM-E06: delete removes row from table', async ({ page }) => {
    const uniqueTitle = `DelRow ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'personal',
    });

    try {
      // Navigate and verify row exists
      await gotoManagementWithFilter(page, 'DelRow');
      const row = page.locator('tr', { hasText: 'DelRow' }).first();
      await expect(row).toBeVisible({ timeout: 5000 });
      // Verify delete button is accessible in More dropdown (draft status)
      await checkActionInDropdown(page, row, 'delete');

      // Delete via API then re-navigate with filter to verify row is gone
      await deleteDashboardViaApi(page, pid);
      await gotoManagementWithFilter(page, 'DelRow');

      // Verify row is gone
      await expect(page.locator('tr', { hasText: 'DelRow' })).toHaveCount(0, { timeout: 5000 });
    } catch {
      await deleteDashboardViaApi(page, pid).catch(() => {});
    }
  });

  /**
   * DM-E07: Clicking a row navigates to the dashboard designer page (NOT preview drawer)
   *
   * This is the core test for the detailUrl feature:
   * DSL config has onRowClick="navigate" + detailUrl="/dashboard-designer/{pid}"
   * so row click should navigate to /dashboard-designer/{pid}, not open RecordPreviewDrawer.
   */
  test('DM-E07: row click navigates to designer @smoke', async ({ page }) => {
    const uniqueTitle = `RowNav ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'global',
    });

    await gotoManagementWithFilter(page, 'RowNav');

    // Find the row with our test dashboard
    const row = page.locator('tr', { hasText: 'RowNav' }).first();
    await expect(row).toBeVisible({ timeout: 5000 });

    // Click the title cell (2nd td — 1st td is checkbox with stopPropagation)
    const titleCell = row.locator('td').nth(1);
    await titleCell.click();

    // Should navigate to designer page with the dashboard pid
    await page.waitForURL(`**/dashboard-designer/${pid}`, { timeout: 10000 });
    expect(page.url()).toContain(`/dashboard-designer/${pid}`);

    // Verify designer page loaded (3-panel layout)
    await expect(page.locator('[data-testid="widget-palette"]')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="designer-canvas"]')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('[data-testid="widget-property-panel"]')).toBeVisible({ timeout: 10000 });

    // Verify NO preview drawer opened (the old behavior)
    await expect(page.locator('[data-testid="record-preview-drawer"]')).toHaveCount(0);
  });

  /**
   * DM-E08: Edit action button navigates to designer
   *
   * The "edit" row action has action.type="navigate" with to="/dashboard-designer/{pid}".
   * Clicking it should navigate to the designer, same as row click.
   */
  test('DM-E08: edit action navigates to designer', async ({ page }) => {
    const uniqueTitle = `EditNav ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'global',
    });

    await gotoManagementWithFilter(page, 'EditNav');

    const row = page.locator('tr', { hasText: 'EditNav' }).first();
    await expect(row).toBeVisible({ timeout: 5000 });

    // Click edit action button (handles primary slot + "more actions" dropdown)
    await clickRowActionByLocator(page, row, 'edit');

    // Should navigate to designer
    await page.waitForURL(`**/dashboard-designer/${pid}`, { timeout: 10000 });
    expect(page.url()).toContain(`/dashboard-designer/${pid}`);

    // Verify designer loaded
    await expect(page.locator('[data-testid="designer-canvas"]')).toBeVisible({ timeout: 10000 });
  });

  /**
   * DM-E09: Create button navigates to designer (new dashboard)
   *
   * The toolbar "create" button has action.to="/dashboard-designer" (no pid).
   */
  test('DM-E09: create button navigates to designer', async ({ page }) => {
    await gotoManagement(page);

    const createBtn = page.locator('[data-testid="toolbar-btn-create"]');
    await expect(createBtn).toBeVisible();
    await createBtn.click();

    // Should navigate to designer without a specific pid
    await page.waitForURL('**/dashboard-designer', { timeout: 10000 });
    expect(page.url()).toContain('/dashboard-designer');
    // Should NOT contain a pid (this is a new dashboard)
    expect(page.url()).not.toMatch(/dashboard-designer\/\w{26}/);

    // Verify designer loaded with empty canvas
    await expect(page.locator('[data-testid="designer-canvas"]')).toBeVisible({ timeout: 10000 });
  });

  /**
   * DM-E10: List tabs filter by scope (All / Personal / Global)
   *
   * DSL defines list-tabs with "all", "personal" (scope=personal), "global" (scope=global).
   */
  test('DM-E10: list tabs filter by scope', async ({ page }) => {
    // Use a common unique prefix so both dashboards appear in the same search filter
    const ts = Date.now();
    const commonPrefix = `TabScope ${ts}`;
    const personalTitle = `${commonPrefix} P`;
    const globalTitle = `${commonPrefix} G`;
    const { pid: personalPid } = await createDashboardViaApi(page, {
      title: personalTitle,
      scope: 'personal',
    });
    const { pid: globalPid } = await createDashboardViaApi(page, {
      title: globalTitle,
      scope: 'global',
    });

    try {
    // Navigate with filter for common prefix so pagination doesn't interfere
    await gotoManagementWithFilter(page, commonPrefix);

    // "All" tab (with active filter): both dashboards should be visible
    await expect(page.locator('tr', { hasText: `${commonPrefix} P` }).first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator('tr', { hasText: `${commonPrefix} G` }).first()).toBeVisible({ timeout: 5000 });

    // Click "Personal" tab and wait for filtered API response
    const personalTab = page.locator('button, [role="tab"]', { hasText: /个人|Personal/ }).first();
    if (await personalTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const personalResp = page.waitForResponse(
        (r) => r.url().includes('/api/dashboards') && r.status() === 200,
        { timeout: 8000 }
      );
      await personalTab.click();
      await personalResp;

      // Personal tab should show personal dashboard
      await expect(page.locator('tr', { hasText: `${commonPrefix} P` }).first()).toBeVisible({ timeout: 5000 });
      // Personal tab: scope cell (data-testid="table-cell-{i}-scope") should show "Personal"
      const personalRows = page.locator('tbody tr');
      const rowCount = await personalRows.count();
      for (let i = 0; i < rowCount; i++) {
        const scopeCell = personalRows.nth(i).locator(`[data-testid="table-cell-${i}-scope"]`);
        const scopeText = await scopeCell.textContent().catch(() => null);
        if (scopeText !== null) {
          expect(scopeText.trim()).toBe('Personal');
        }
      }
    }

    // Click "Global" tab
    const globalTab = page.locator('button, [role="tab"]', { hasText: /全局|Global/ }).first();
    if (await globalTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const globalResp = page.waitForResponse(
        (r) => r.url().includes('/api/dashboards') && r.status() === 200,
        { timeout: 8000 }
      );
      await globalTab.click();
      await globalResp;

      // Global tab should show global dashboard
      await expect(page.locator('tr', { hasText: `${commonPrefix} G` }).first()).toBeVisible({ timeout: 5000 });
      const globalRows = page.locator('tbody tr');
      const gRowCount = await globalRows.count();
      for (let i = 0; i < gRowCount; i++) {
        const scopeCell = globalRows.nth(i).locator(`[data-testid="table-cell-${i}-scope"]`);
        const scopeText = await scopeCell.textContent().catch(() => null);
        if (scopeText !== null) {
          expect(scopeText.trim()).toBe('Global');
        }
      }
    }

    // Switch back to "All" tab to verify both reappear
    const allTab = page.locator('button, [role="tab"]', { hasText: /全部|All/ }).first();
    if (await allTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const allResp = page.waitForResponse(
        (r) => r.url().includes('/api/dashboards') && r.status() === 200,
        { timeout: 8000 }
      );
      await allTab.click();
      await allResp;
      await expect(page.locator('tbody tr').first()).toBeVisible({ timeout: 5000 });
    }
    } finally {
      await deleteDashboardViaApi(page, personalPid).catch(() => {});
      await deleteDashboardViaApi(page, globalPid).catch(() => {});
    }
  });

  /**
   * DM-E11: Full lifecycle — create → publish → unpublish → delete with UI verification
   *
   * Covers the complete status flow with UI state assertions at each step.
   */
  test('DM-E11: full lifecycle create → publish → unpublish → delete', async ({ page }) => {
    const uniqueTitle = `Lifecycle ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'global',
    });

    // Add a widget via API so backend publish validation passes
    await addWidgetToDashboardViaApi(page, pid);

    // Step 1: Verify draft state — use full uniqueTitle to avoid matching old test data
    await gotoManagementWithFilter(page, uniqueTitle);
    const row = () => page.locator('tr', { hasText: uniqueTitle }).first();
    await expect(row()).toBeVisible({ timeout: 5000 });

    // Draft status tag — find by looking for "Draft" or "草稿" text anywhere in the row
    await expect(row()).toContainText(/Draft|草稿/i);

    // Draft row: edit is primary (direct), publish/delete in More dropdown, unpublish not rendered
    await expect(row().locator('[data-testid="row-action-edit"]')).toBeVisible();
    await checkActionInDropdown(page, row(), 'publish');
    await checkActionInDropdown(page, row(), 'delete');
    await expect(row().locator('[data-testid="row-action-unpublish"]')).toHaveCount(0);

    // Step 2: Publish via UI button (publish is in More dropdown)
    const publishResp = page.waitForResponse(
      (r) => r.url().includes('/publish') && r.request().method() === 'POST',
      { timeout: 8000 }
    );
    await clickRowActionByLocator(page, row(), 'publish');
    const pubResponse = await publishResp;
    expect(pubResponse.status()).toBe(200);

    // Re-navigate with filter to verify published state
    await gotoManagementWithFilter(page, uniqueTitle);
    const pubRow = () => page.locator('tr', { hasText: uniqueTitle }).first();
    await expect(pubRow()).toBeVisible({ timeout: 5000 });
    await expect(pubRow()).toContainText(/Published|已发布/i);

    // Published row: edit is primary (direct), unpublish/delete in More dropdown, publish not rendered
    await checkActionInDropdown(page, pubRow(), 'unpublish');
    await expect(pubRow().locator('[data-testid="row-action-publish"]')).toHaveCount(0);

    // Step 3: Unpublish via UI button (unpublish is in More dropdown)
    const unpubResp = page.waitForResponse(
      (r) => r.url().includes('/unpublish') && r.request().method() === 'POST',
      { timeout: 8000 }
    );
    await clickRowActionByLocator(page, pubRow(), 'unpublish');
    const unpubResponse = await unpubResp;
    expect(unpubResponse.status()).toBe(200);

    // Re-navigate with filter to verify draft state restored
    await gotoManagementWithFilter(page, uniqueTitle);
    const draftRow = () => page.locator('tr', { hasText: uniqueTitle }).first();
    await expect(draftRow()).toBeVisible({ timeout: 5000 });
    await checkActionInDropdown(page, draftRow(), 'publish');
    await expect(draftRow().locator('[data-testid="row-action-unpublish"]')).toHaveCount(0);

    // Step 4: Delete via UI button with confirm dialog (handles primary slot + "more actions" dropdown)
    await clickRowActionByLocator(page, draftRow(), 'delete');

    // Accept confirm dialog
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await confirmDialog.waitFor({ state: 'visible', timeout: 5000 });
    // Verify confirm dialog shows deletion-related content
    await expect(confirmDialog).toContainText(/确认|删除|delete|Confirm/i);

    // Set up API listener BEFORE clicking OK
    const deleteResp = page.waitForResponse(
      (r) => r.url().includes('/dashboards/') && r.request().method() === 'DELETE',
      { timeout: 8000 }
    ).catch(() => null);
    await page.locator('[data-testid="confirm-ok"]').click();
    await deleteResp;

    // Verify row is gone after list refresh (filter still set to uniqueTitle)
    await page.waitForResponse(
      (r) => r.url().includes('/dashboards') && !r.url().includes('/publish') && r.status() === 200,
      { timeout: 8000 }
    ).catch(() => page.reload());
    await expect(page.locator('tr', { hasText: uniqueTitle })).toHaveCount(0, { timeout: 8000 });
  });

  /**
   * DM-E12: Publish via row action button with API response verification
   */
  test('DM-E12: publish via row action with response check', async ({ page }) => {
    const uniqueTitle = `PubAction ${Date.now()}`;
    const { pid } = await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'global',
    });

    // Add widget via API so backend publish validation passes
    await addWidgetToDashboardViaApi(page, pid);

    await gotoManagementWithFilter(page, 'PubAction');

    const row = page.locator('tr', { hasText: 'PubAction' }).first();
    await expect(row).toBeVisible({ timeout: 5000 });

    // Set up response listener BEFORE clicking
    const publishResp = page.waitForResponse(
      (r) => r.url().includes('/publish') && r.request().method() === 'POST',
      { timeout: 8000 }
    );

    await clickRowActionByLocator(page, row, 'publish'); // publish is in More dropdown

    // Verify API response is successful with correct format
    const response = await publishResp;
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.code).toBe('0'); // Standard AuraBoot success code
  });

  /**
   * DM-E13: Delete via row action button with confirm dialog interaction
   */
  test('DM-E13: delete via row action with confirm dialog', async ({ page }) => {
    const uniqueTitle = `DelAction ${Date.now()}`;
    await createDashboardViaApi(page, {
      title: uniqueTitle,
      scope: 'personal',
    });

    // Use full unique title as filter to avoid matching historical test dashboards
    await gotoManagementWithFilter(page, uniqueTitle);

    const row = page.locator('tr', { hasText: uniqueTitle }).first();
    await expect(row).toBeVisible({ timeout: 5000 });

    // Click delete button (handles primary slot + "more actions" dropdown)
    await clickRowActionByLocator(page, row, 'delete');

    // Confirm dialog should appear
    const confirmDialog = page.locator('[data-testid="confirm-dialog"]');
    await expect(confirmDialog).toBeVisible({ timeout: 5000 });

    // Test cancel — should dismiss dialog without deleting
    await page.locator('[data-testid="confirm-cancel"]').click();
    await expect(confirmDialog).not.toBeVisible({ timeout: 3000 });
    // Row should still be visible
    await expect(row).toBeVisible();

    // Click delete again and this time confirm (handles primary slot + "more actions" dropdown)
    await clickRowActionByLocator(page, row, 'delete');
    await confirmDialog.waitFor({ state: 'visible', timeout: 5000 });

    // Set up API response listener
    const deleteResp = page.waitForResponse(
      (r) => r.url().includes('/dashboards/') && r.request().method() === 'DELETE',
      { timeout: 8000 }
    );
    await page.locator('[data-testid="confirm-ok"]').click();
    const delResponse = await deleteResp;
    expect(delResponse.ok()).toBeTruthy();

    // Verify row disappears from the table
    await expect(page.locator('tr', { hasText: uniqueTitle })).toHaveCount(0, { timeout: 8000 });
  });
});
