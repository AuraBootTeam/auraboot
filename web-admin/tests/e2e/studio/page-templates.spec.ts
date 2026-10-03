/**
 * Page Templates — E2E Tests
 *
 * Full lifecycle: Save as Template → Browse Templates → Create from Template → Duplicate Page
 *
 * Navigation: page.goto() is used because Page Designer is a platform designer tool,
 * not a sidebar menu page (allowed per AGENTS.md exception for designer workbenches).
 * T5 drives the real sidebar page-configuration menu and DSL row command.
 *
 * Dimensions covered:
 * D2 (gallery renders after save), D4 (full form fill), D5 (template-name-input prefilled),
 * D6 (new page appears after create-from-template), D8 (duplicate command targets the exact source and preserves stored content),
 * D14 (dialog closes = operation feedback).
 * D1 is covered by T5; other designer journeys use the platform-tool exception.
 * Not applicable: D3/D9/D10 (no status machine),
 * D7 (no detail page), D11 (not a delete flow).
 *
 * @since 4.1.0
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers/index';
import { BASE_URL } from '../../helpers/environments';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Create a test page via API and return its pid and name.
 */
async function createTestPage(
  page: import('@playwright/test').Page,
  content: Partial<{ schemaVersion: number; blocks: unknown[]; dataSources: Record<string, unknown> }> = {},
): Promise<{ pid: string; name: string }> {
  const name = uniqueId('tmpl');
  const pageKey = `e2e_tmpl_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;

  const resp = await page.request.post('/api/pages', {
    timeout: 15_000,
    data: {
      name,
      pageKey,
      title: name,
      kind: 'list',
      modelCode: 'page_schema',
      schemaVersion: 4,
      layout: { type: 'stack' },
      blocks: [{ id: 'blk1', blockType: 'table', config: {} }],
      metaInfo: { componentCount: 1 },
      semver: '0.1.0',
      ...content,
    },
  });
  expect(resp.ok(), `Create page API failed: ${resp.status()}`).toBeTruthy();
  const body = await resp.json();
  expect(body.code).toBe('0');
  const pid = body.data?.pid;
  expect(pid, 'Page pid must be returned').toBeTruthy();
  return { pid, name };
}

/**
 * Open the "From Template" dialog and wait for the gallery to load.
 * Returns the dialog locator.
 */
async function openTemplateDialog(page: import('@playwright/test').Page) {
  const btn = page.getByTestId('toolbar-create-from-template');
  await expect(btn).toBeEnabled();
  await btn.click();
  const dialog = page.getByTestId('create-from-template-dialog');
  await expect(dialog).toBeVisible({ timeout: 10000 });
  return dialog;
}

async function openTemplateEditor(page: import('@playwright/test').Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15_000 });
  await expect(page.getByTestId('toolbar-create-from-template')).toBeEnabled();
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Page Templates', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(45_000);

  let pagePid: string;
  let pageName: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Referer: `${BASE_URL}/` }, storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const p = await ctx.newPage();
    const result = await createTestPage(p);
    pagePid = result.pid;
    pageName = result.name;
    await ctx.close();
  });

  // -------------------------------------------------------------------------
  // T1: Save as Template — toolbar button opens dialog, name is prefilled
  // -------------------------------------------------------------------------
  test('T1 — save page as template via toolbar button', async ({ page }) => {
    // Navigate directly to page designer (platform tool — page.goto() allowed)
    await page.goto(`/page-designer/${pagePid}`, { waitUntil: 'domcontentloaded' });

    // List pages render through ListConfigPanel rather than the block canvas.
    await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15000 });

    // The "Template" toolbar button should be visible because pageMeta is loaded
    const templateBtn = page.getByTestId('toolbar-save-as-template');
    await expect(templateBtn).toBeVisible({ timeout: 10000 });
    await templateBtn.click();

    // Dialog opens
    const dialog = page.getByTestId('save-as-template-dialog');
    await expect(dialog).toBeVisible({ timeout: 5000 });

    // Name input is pre-filled with "<currentName> Template"
    const nameInput = page.getByTestId('template-name-input');
    await expect(nameInput).toBeVisible();
    const prefilled = await nameInput.inputValue();
    expect(prefilled).toMatch(/Template|模板/);
    // The current page name should appear in the prefilled value
    expect(prefilled.toLowerCase()).toContain(pageName.toLowerCase().slice(0, 8));

    // Fill optional category
    const categoryInput = page.getByTestId('template-category-input');
    await expect(categoryInput).toBeVisible();
    await categoryInput.fill('E2E Test Category');

    // Click Save as Template
    const saveBtn = page.getByTestId('template-save-btn');
    await expect(saveBtn).toBeEnabled();
    await saveBtn.click();

    // Dialog must close on success (API call completes and dialog dismisses)
    await expect(dialog).not.toBeVisible({ timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // T2: Template gallery loads with search and kind filter
  // -------------------------------------------------------------------------
  test('T2 — template gallery shows search + kind filter + at least one card after save', async ({
    page,
  }) => {
    await openTemplateEditor(page, pagePid);

    const dialog = await openTemplateDialog(page);

    // Gallery mounts inside dialog
    const gallery = page.getByTestId('template-gallery');
    await expect(gallery).toBeVisible({ timeout: 15000 });

    // Search input and kind-filter select must be present
    await expect(page.getByTestId('template-search')).toBeVisible();
    await expect(page.getByTestId('template-kind-filter')).toBeVisible();

    // After T1 we should have at least 1 template — grid must be visible (not empty state)
    const grid = page.getByTestId('template-grid');
    await expect(grid).toBeVisible({ timeout: 10000 });

    // At least one template card should exist
    await expect(grid.getByTestId(`template-card-${pagePid}`)).toBeVisible({ timeout: 10000 });

    // Dismiss dialog via close button (custom div modal, Escape not guaranteed)
    await dialog.getByRole('button', { name: /Close dialog|\u5173\u95ed/ }).click();
    await expect(dialog).not.toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // T3: Search filters template cards
  // -------------------------------------------------------------------------
  test('T3 — search input filters visible template cards', async ({ page }) => {
    await openTemplateEditor(page, pagePid);
    await openTemplateDialog(page);

    // Gallery loads
    const gallery = page.getByTestId('template-gallery');
    await expect(gallery).toBeVisible({ timeout: 15000 });

    // Wait for grid or empty state
    await page.waitForSelector('[data-testid="template-grid"],[data-testid="template-empty"]', {
      timeout: 10000,
    });

    // Search for something that should NOT match any real template
    const searchInput = page.getByTestId('template-search');
    await searchInput.fill('ZZZ_UNLIKELY_MATCH_9999');

    // Empty state should appear since no template matches
    const emptyState = page.getByTestId('template-empty');
    await expect(emptyState).toBeVisible({ timeout: 5000 });

    // Clear search — grid should return (if there are templates)
    await searchInput.clear();
    const grid = page.getByTestId('template-grid');
    await expect(grid).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // T4: Create page from template — two-step flow
  // -------------------------------------------------------------------------
  test('T4 — create page from template via two-step dialog', async ({ page }) => {
    await openTemplateEditor(page, pagePid);
    const dialog = await openTemplateDialog(page);

    // Step 1: select template
    const gallery = page.getByTestId('template-gallery');
    await expect(gallery).toBeVisible({ timeout: 15000 });
    const grid = page.getByTestId('template-grid');
    await expect(grid).toBeVisible({ timeout: 10000 });

    // Click the first template card to advance to step 2
    const firstCard = grid.getByTestId(`template-card-${pagePid}`);
    await expect(firstCard).toBeVisible();
    await firstCard.click();

    // Step 2: configure new page — name and pageKey inputs appear
    const nameInput = page.getByTestId('new-page-name-input');
    await expect(nameInput).toBeVisible({ timeout: 5000 });
    const keyInput = page.getByTestId('new-page-key-input');
    await expect(keyInput).toBeVisible();

    // Name is pre-filled with "<templateName> Copy"
    const prefixName = await nameInput.inputValue();
    expect(prefixName).toMatch(/copy|\u526f\u672c/i);

    // Page key is auto-generated (non-empty)
    const keyValue = await keyInput.inputValue();
    expect(keyValue.length).toBeGreaterThan(0);

    // Override with unique values
    const newName = uniqueId('from_tmpl');
    const newKey = `e2e_ft_${Date.now().toString(36)}`;
    await nameInput.clear();
    await nameInput.fill(newName);
    await keyInput.clear();
    await keyInput.fill(newKey);

    // The create button inside the dialog footer (step=configure has testid "create-from-template-btn")
    // Use the one inside the dialog to avoid matching the route-level button
    const createBtn = dialog.getByTestId('create-from-template-btn');
    await expect(createBtn).toBeEnabled();

    // Wait for POST /api/pages response after clicking create
    const [navigationResp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/pages') && r.request().method() === 'POST',
        { timeout: 15000 },
      ),
      createBtn.click(),
    ]);
    const respBody = await navigationResp.json();
    expect(respBody.code).toBe('0');

    // Dialog should close
    await expect(dialog).not.toBeVisible({ timeout: 10000 });

    // Should navigate to the new page designer with the new pid
    const createdPid = respBody.data?.pid;
    expect(createdPid, 'Created page must return its actual pid').toBeTruthy();
    await expect(page).toHaveURL(`${BASE_URL}/page-designer/${createdPid}`);
    await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15000 });
    await page.reload();
    await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15000 });
    const persisted = await page.request.get(`/api/pages/${createdPid}`);
    expect(persisted.ok()).toBe(true);
    const persistedBody = await persisted.json();
    expect(persistedBody.code).toBe('0');
    expect(persistedBody.data).toMatchObject({ pid: createdPid, name: newName, pageKey: newKey,
      modelCode: 'page_schema', schemaVersion: 4, blocks: [{ id: 'blk1', blockType: 'table', config: {} }] });
  });

  // -------------------------------------------------------------------------
  // T5: Duplicate through the actual DSL page-manager menu and row command
  // -------------------------------------------------------------------------
  test('T5 - duplicate a legacy tree through the page configuration menu', async ({ page }, testInfo) => {
    const dataSources = { main: { model: 'page_schema' } };
    const source = await createTestPage(page, {
      schemaVersion: 3,
      dataSources,
      blocks: [{ id: 'copy_root', blockType: 'list', blocks: [
        { id: 'copy_table', blockType: 'table', dataSource: { ref: 'main' }, blocks: [
          { id: 'copy_table_name', blockType: 'column', field: 'name' },
        ] },
      ] }],
    });
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    const nav = page.locator('nav, aside, [role="navigation"]').first();
    await expect(nav).toBeVisible();
    await nav.getByRole('button', { name: /\u5143\u6570\u636e\u7ba1\u7406|Meta/i }).click();
    const menu = nav.locator('a[href="/p/page_schema"]');
    await expect(menu).toBeVisible();
    await menu.click();
    await expect(page).toHaveURL(`${BASE_URL}/p/page_schema`);
    const sourceRow = page.getByRole('row').filter({ hasText: source.name });
    await expect(sourceRow).toHaveCount(1);
    await expect(sourceRow).toBeVisible();
    await sourceRow.getByTestId('row-action-more').click();
    const duplicate = page.getByTestId('row-action-dropdown').getByTestId('row-action-duplicate');
    await expect(duplicate).toBeEnabled();
    const [response] = await Promise.all([
      page.waitForResponse(r => decodeURIComponent(r.url()).endsWith('/api/meta/commands/execute/pgm:duplicate_page_schema')
        && r.request().method() === 'POST'),
      duplicate.click(),
    ]);
    expect(response.request().postDataJSON()).toMatchObject({ targetRecordPid: source.pid });
    expect(response.ok()).toBe(true);
    const result = await response.json();
    expect(result.code).toBe('0');
    const copiedPid = result.data?.pid;
    expect(copiedPid).toBeTruthy();
    expect(copiedPid).not.toBe(source.pid);
    const copied = await page.request.get(`/api/pages/${copiedPid}`);
    expect(copied.ok()).toBe(true);
    const persisted = await copied.json();
    expect(persisted.code).toBe('0');
    expect(persisted.data).toMatchObject({ pid: copiedPid, name: `${source.name} (Copy)`,
      schemaVersion: 4, kind: 'list', modelCode: 'page_schema', dataSources,
      blocks: [{ id: 'copy_table', blockType: 'table', dataSource: 'main', columns: ['name'] }],
      extension: { designerRootId: 'copy_root' } });
    const copyRow = page.getByRole('row').filter({ hasText: `${source.name} (Copy)` });
    await expect(copyRow).toBeVisible();
    await page.reload();
    await expect(copyRow).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('duplicate-page-menu.png'), fullPage: true });
    const original = await page.request.get(`/api/pages/${source.pid}`);
    expect(original.ok()).toBe(true);
    expect((await original.json()).data).toMatchObject({ pid: source.pid, schemaVersion: 3, dataSources });
  });

  // -------------------------------------------------------------------------
  // T6: Save as Template — validation: empty name disables the save button
  // -------------------------------------------------------------------------
  test('T6 — save-as-template dialog disables save when name is empty', async ({ page }) => {
    await page.goto(`/page-designer/${pagePid}`, { waitUntil: 'domcontentloaded' });
    await expect(page.getByTestId('list-config-panel')).toBeVisible({ timeout: 15000 });

    const templateBtn = page.getByTestId('toolbar-save-as-template');
    await expect(templateBtn).toBeVisible({ timeout: 10000 });
    await templateBtn.click();

    const dialog = page.getByTestId('save-as-template-dialog');
    await expect(dialog).toBeVisible({ timeout: 5000 });

    const nameInput = page.getByTestId('template-name-input');
    await nameInput.clear();

    // Save button should be disabled when name is empty
    const saveBtn = page.getByTestId('template-save-btn');
    await expect(saveBtn).toBeDisabled();

    // Restore name and button becomes enabled
    await nameInput.fill('Restored Name');
    await expect(saveBtn).toBeEnabled();

    // Cancel without saving — click Cancel button scoped to the dialog
    await dialog.getByRole('button', { name: /Cancel|取消/i }).click();
    await expect(dialog).not.toBeVisible({ timeout: 5000 });
  });
});
