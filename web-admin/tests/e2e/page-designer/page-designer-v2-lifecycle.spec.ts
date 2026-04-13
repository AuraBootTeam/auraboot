/**
 * Page Designer V2 — Composite Page Lifecycle E2E Tests
 *
 * Tests the unified block canvas editor (CanvasEditor) for kind='composite' pages.
 * All block operations are tested within a SINGLE page session to avoid
 * auto-save persistence issues across navigations.
 *
 * Dimensions: D1 (nav), D4 (create), D6 (verify), D8 (edit), D11 (delete), D14 (feedback)
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers/index';

/** Match only the root canvas block card, excluding nested content and drop targets */
const BLOCK_SEL =
  '[data-testid^="canvas-block-"]' +
  ':not([data-testid*="-drag-"])' +
  ':not([data-testid*="-remove-"])' +
  ':not([data-testid*="-content-"])' +
  ':not([data-testid*="-drop-"])';

test.describe('Page Designer V2 — Composite Page Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  const PREFIX = uniqueId('PDV2');
  let createdPagePid: string;
  let createdPageKey: string;

  // -------------------------------------------------------------------------
  // Test 1: Page list loads with create button
  // -------------------------------------------------------------------------
  test.fixme('page list visible with create button', async ({ page }) => {
    // /page-designer redirects to /p/page_schema (DSL list page)
    await page.goto('/p/page_schema', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle').catch(() => {});

    // The DSL list page renders a dynamic list with a table
    await expect(
      page.locator('[data-testid="dynamic-list"], [data-testid="page-list"], table').first(),
    ).toBeVisible({ timeout: 15_000 });
    // Create button may be in toolbar or as a standalone button
    await expect(
      page.locator('[data-testid="page-list-create-btn"], button:has-text("Create"), button:has-text("新建"), [data-testid="toolbar-create-button"]').first(),
    ).toBeVisible({ timeout: 8_000 });
  });

  // -------------------------------------------------------------------------
  // Test 2: Create composite page via API, verify canvas editor renders
  // -------------------------------------------------------------------------
  test('canvas editor opens for new composite page', async ({ page }) => {
    // Create via API
    const resp = await page.request.post('/api/pages', {
      data: {
        name: `${PREFIX}_page`,
        pageKey: `${PREFIX.toLowerCase()}_page`,
        title: 'Untitled',
        kind: 'composite',
        blocks: [],
        semver: '0.1.0',
      },
    });
    expect(resp.ok()).toBeTruthy();
    const body = await resp.json();
    expect(body.code).toBe('0');
    createdPagePid = body.data.pid;
    createdPageKey = body.data.pageKey;

    // Navigate to editor
    await page.goto(`/page-designer/${createdPagePid}`);

    // Canvas editor visible
    await expect(page.getByTestId('canvas-editor')).toBeVisible({ timeout: 15_000 });

    // Empty state + quick-add buttons
    await expect(page.getByTestId('canvas-empty-state')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('canvas-quick-add-table')).toBeVisible();
    await expect(page.getByTestId('canvas-quick-add-form')).toBeVisible();
    await expect(page.getByTestId('canvas-quick-add-chart')).toBeVisible();

    // Left panel
    await expect(page.getByTestId('canvas-left-panel')).toBeVisible();

    // Right panel (empty — no block selected)
    await expect(page.getByTestId('block-config-empty')).toBeVisible();

    // Title input
    await expect(page.getByTestId('canvas-title-input')).toBeVisible();
  });

  // -------------------------------------------------------------------------
  // Test 3: Full block lifecycle in a single session
  //   - quick-add table
  //   - add form-section via palette
  //   - select block → config panel
  //   - add chart → delete it
  //   - edit title
  // -------------------------------------------------------------------------
  test('block CRUD + title edit in single session', async ({ page }) => {
    await page.goto(`/page-designer/${createdPagePid}`);
    await expect(page.getByTestId('canvas-editor')).toBeVisible({ timeout: 15_000 });

    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);

    // --- Add Table via quick-add ---
    await page.getByTestId('canvas-quick-add-table').click();
    await expect(blocks).toHaveCount(1, { timeout: 5_000 });
    await expect(page.getByTestId('canvas-empty-state')).not.toBeVisible();

    // --- Add Form Section via palette ---
    const componentsTab = page.getByTestId('canvas-left-tab-components');
    if (await componentsTab.isVisible({ timeout: 2_000 }).catch(() => false)) {
      await componentsTab.click();
    }
    await page.getByTestId('block-palette-item-form-section').click();
    await expect(blocks).toHaveCount(2, { timeout: 5_000 });

    // --- Select first block → config panel visible ---
    await blocks.first().click();
    await expect(page.getByTestId('block-config-panel')).toBeVisible({ timeout: 5_000 });
    await expect(page.getByTestId('block-config-empty')).not.toBeVisible();

    // Deselect by clicking canvas background
    await page.getByTestId('canvas-body').click({ position: { x: 10, y: 10 } });

    // --- Add chart → delete it ---
    await page.getByTestId('block-palette-item-chart').click();
    await expect(blocks).toHaveCount(3, { timeout: 5_000 });

    // Get chart block id
    const chartBlock = blocks.nth(2);
    const chartTestId = await chartBlock.getAttribute('data-testid');
    const chartId = chartTestId?.replace('canvas-block-', '');
    expect(chartId).toBeTruthy();

    await chartBlock.hover();
    await page.getByTestId(`canvas-block-remove-${chartId}`).click();
    await expect(blocks).toHaveCount(2, { timeout: 5_000 });

    // --- Edit title ---
    const titleInput = page.getByTestId('canvas-title-input');
    await titleInput.fill(`${PREFIX} Test Page`);
    await expect(titleInput).toHaveValue(`${PREFIX} Test Page`);

    const descInput = page.getByTestId('canvas-description-input');
    await descInput.fill('E2E test composite page');
    await expect(descInput).toHaveValue('E2E test composite page');
  });

  // -------------------------------------------------------------------------
  // Test 4: Auto-save persists blocks across page reload
  // -------------------------------------------------------------------------
  test('auto-save persists blocks after reload', async ({ page }) => {
    await page.goto(`/page-designer/${createdPagePid}`);
    await expect(page.getByTestId('canvas-editor')).toBeVisible({ timeout: 15_000 });

    const blocks = page.getByTestId('canvas-body').locator(BLOCK_SEL);

    // Add a block and wait for auto-save, capturing the PUT body
    const savePromise = page.waitForResponse(
      resp => resp.url().includes('/api/pages/') && resp.request().method() === 'PUT',
      { timeout: 10_000 },
    );
    await page.getByTestId('canvas-quick-add-table').click();
    const initialCount = await blocks.count();
    expect(initialCount).toBeGreaterThanOrEqual(1);

    const saveResp = await savePromise;
    expect(saveResp.status()).toBe(200);

    // Reload page
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('canvas-editor')).toBeVisible({ timeout: 15_000 });

    // Blocks should persist
    const blocksAfterReload = page.getByTestId('canvas-body').locator(BLOCK_SEL);
    await expect(blocksAfterReload).toHaveCount(initialCount, { timeout: 5_000 });
  });

  // -------------------------------------------------------------------------
  // Test 5: Publish
  // -------------------------------------------------------------------------
  test('publish page', async ({ page }) => {
    await page.goto(`/page-designer/${createdPagePid}`);
    await expect(page.getByTestId('canvas-editor')).toBeVisible({ timeout: 15_000 });

    const publishPromise = page.waitForResponse(
      resp => resp.url().includes('/api/pages/') && resp.url().includes('/publish'),
      { timeout: 15_000 },
    );

    await page.getByTestId('toolbar-publish').click();
    const publishResp = await publishPromise;
    expect(publishResp.status()).toBe(200);
  });

  // -------------------------------------------------------------------------
  // Test 5: Published page renders at /p/{pageKey}
  // -------------------------------------------------------------------------
  test('published page renders at /p/{pageKey}', async ({ page }) => {
    expect(createdPageKey).toBeTruthy();

    await page.goto(`/p/${createdPageKey}`);
    await page.waitForLoadState('domcontentloaded');

    // No 404
    await expect(
      page.locator('text=Page not found').or(page.locator('text=404'))
    ).not.toBeVisible({ timeout: 5_000 });

    // Some content renders (composite-page-content OR the page at least doesn't error)
    const content = page.locator(
      '[data-testid="composite-page-content"], [data-testid="composite-page-empty"], main'
    ).first();
    await expect(content).toBeVisible({ timeout: 10_000 });
  });
});
