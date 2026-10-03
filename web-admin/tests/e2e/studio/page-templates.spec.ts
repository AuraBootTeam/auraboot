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
  const name = `Approval workflow ${uniqueId('tmpl').split('_').at(-1)}`;
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
  test('T1 — save page as template via toolbar button', async ({ page }, testInfo) => {
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
    await page.screenshot({ path: testInfo.outputPath('T1-dialog.png'), fullPage: true });
    await saveBtn.click();

    // Dialog must close on success (API call completes and dialog dismisses)
    await expect(dialog).not.toBeVisible({ timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // T2: Template gallery loads with search and kind filter
  // -------------------------------------------------------------------------
  test('T2 — template gallery shows search + kind filter + at least one card after save', async ({
    page,
  }, testInfo) => {
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

    await page.screenshot({ path: testInfo.outputPath('T2-gallery.png'), fullPage: true });

    // Dismiss dialog via close button (custom div modal, Escape not guaranteed)
    await dialog.getByRole('button', { name: /Close dialog|\u5173\u95ed/ }).click();
    await expect(dialog).not.toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // T3: Search filters template cards
  // -------------------------------------------------------------------------
  test('T3 — search input filters visible template cards', async ({ page }, testInfo) => {
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
    await page.screenshot({ path: testInfo.outputPath('T3-empty.png'), fullPage: true });

    // Clear search — grid should return (if there are templates)
    await searchInput.clear();
    const grid = page.getByTestId('template-grid');
    await expect(grid).toBeVisible({ timeout: 5000 });
    await expect(grid.getByTestId(`template-card-${pagePid}`)).toBeVisible();
    await page.screenshot({ path: testInfo.outputPath('T3-clear.png'), fullPage: true });
  });

  // -------------------------------------------------------------------------
  // T4: Create page from template — two-step flow
  // -------------------------------------------------------------------------
  test('T4 — create page from template via two-step dialog', async ({ page }, testInfo) => {
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
    const newName = `Approval copy ${uniqueId('from_tmpl').split('_').at(-1)}`;
    const newKey = `e2e_ft_${Date.now().toString(36)}`;
    await nameInput.clear();
    await nameInput.fill(newName);
    await keyInput.clear();
    await keyInput.fill(newKey);

    // The create button inside the dialog footer (step=configure has testid "create-from-template-btn")
    // Use the one inside the dialog to avoid matching the route-level button
    const createBtn = dialog.getByTestId('create-from-template-btn');
    await expect(createBtn).toBeEnabled();
    await page.screenshot({ path: testInfo.outputPath('T4-create.png'), fullPage: true });

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
    for (const width of [1280, 900, 1920]) {
      await page.setViewportSize({ width, height: 720 });
      await expect.poll(() => page.evaluate(() => {
        const sidebar = document.getElementById('app-sidebar');
        const settled = !sidebar || !sidebar.getAnimations().some(animation => animation.playState === 'running');
        const sidebarBox = sidebar?.getBoundingClientRect();
        const expectedPosition = !sidebarBox || (innerWidth < 1024 ? sidebarBox.right <= 1 : sidebarBox.left >= -1);
        return settled && expectedPosition && scrollX === 0
          && document.documentElement.scrollWidth <= innerWidth + 1;
      })).toBe(true);
      const summary = page.getByTestId('list-designer-summary');
      await summary.scrollIntoViewIfNeeded();
      await expect.poll(async () => summary.evaluate(element => {
        const main = element.closest('main')!;
        const heading = element.querySelector('h1')!;
        return main.scrollWidth <= main.clientWidth + 1
          && heading.getBoundingClientRect().width >= 180
          && Array.from(element.querySelectorAll('.grid > div')).every(card =>
            card.getBoundingClientRect().right <= element.getBoundingClientRect().right + 1);
      })).toBe(true);
      const preview = page.getByTestId('list-preview-pane');
      await expect(preview).toBeVisible();
      const mainBox = await page.getByTestId('list-config-main').boundingBox();
      const previewBox = await preview.boundingBox();
      expect(mainBox).not.toBeNull();
      expect(previewBox).not.toBeNull();
      if (width === 1920) expect(previewBox!.x).toBeGreaterThan(mainBox!.x);
      else expect(previewBox!.y).toBeGreaterThanOrEqual(mainBox!.y + mainBox!.height - 1);
      await page.screenshot({ path: testInfo.outputPath(width === 1280 ? 'T4-result.png' : `T4-result-${width}.png`), fullPage: true });
    }
    await page.setViewportSize({ width: 1280, height: 720 });
    for (const [locale, label, heading] of [['en-US', 'English', 'Columns'], ['zh-CN', '简体中文', '列结构']]) {
      await page.getByTestId('lang-toggle').getByRole('button').click();
      await page.getByTestId('lang-dropdown').getByRole('button', { name: label }).click();
      await expect(page.getByTestId('list-designer-summary').getByRole('heading', { name: heading, exact: true })).toBeVisible();
      await page.reload();
      await expect(page.getByTestId('list-designer-summary').getByRole('heading', { name: heading, exact: true })).toBeVisible();
      const dictionary = await page.request.get(`/api/i18n/${locale}`);
      expect(dictionary.ok()).toBe(true);
      const dictionaryBody = await dictionary.json();
      const translations = dictionaryBody.data ?? dictionaryBody;
      expect(translations['list_designer.columns'] ?? translations.list_designer?.columns).toBe(heading);
      await page.screenshot({ path: testInfo.outputPath(`T4-language-${locale}.png`), fullPage: true });
    }
  });

  // -------------------------------------------------------------------------
  // T5: Duplicate through the actual DSL page-manager menu and row command
  // -------------------------------------------------------------------------
  test('T5 - duplicate a legacy tree through the page configuration menu', async ({ page, browser }, testInfo) => {
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
    await page.screenshot({ path: testInfo.outputPath('T5-menu.png'), fullPage: true });
    const [response] = await Promise.all([
      page.waitForResponse(r => decodeURIComponent(r.url()).endsWith('/api/meta/commands/execute/pgm:duplicate_page_schema')
        && r.request().method() === 'POST'),
      duplicate.click(),
    ]);
    expect(response.request().postDataJSON()).toMatchObject({ targetRecordPid: source.pid });
    expect(response.ok()).toBe(true);
    const result = await response.json();
    expect(result.code).toBe('0');
    expect(result.data).toMatchObject({ commandCode: 'pgm:duplicate_page_schema',
      phaseReached: 'completed', data: { handlerExecuted: true } });
    const copiedPid = result.data?.data?.pid;
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
    const original = await page.request.get(`/api/pages/${source.pid}`);
    expect(original.ok()).toBe(true);
    const originalBody = await original.json();
    expect(originalBody.code).toBe('0');
    expect(originalBody.data).toMatchObject({ pid: source.pid, schemaVersion: 3, dataSources });
    await page.screenshot({ path: testInfo.outputPath('duplicate-page-menu.png'), fullPage: true });

    // Provision an authenticated member in this tenant with only base read access.
    const roleCode = uniqueId('copy_denied').replace(/[^a-zA-Z0-9_]/g, '_').slice(0, 60);
    const role = await page.request.post('/api/roles', { data: {
      code: roleCode, name: 'Page copy restricted member', type: 'custom', status: 'ACTIVE',
    } });
    expect(role.ok()).toBe(true);
    const roleBody = await role.json();
    expect(roleBody.code).toBe('0');
    expect(roleBody.data.pid).toBeTruthy();
    const grants = await page.request.put(`/api/permission/capabilities?rolePid=${roleBody.data.pid}`, {
      data: ['sys.cap.member_base'],
    });
    expect(grants.ok()).toBe(true);
    const grantBody = await grants.json();
    expect(grantBody.code).toBe('0');
    const grantedCodes = grantBody.data.flatMap((group: { capabilities: Array<{ code: string; granted: boolean }> }) =>
      group.capabilities.filter(capability => capability.granted).map(capability => capability.code));
    expect(grantedCodes).toEqual(['sys.cap.member_base']);
    const email = `${roleCode}@e2e.local`;
    const password = `Copy!${roleCode}9a`;
    const provision = await page.request.post('/api/admin/users', { data: {
      email, displayName: 'Page copy restricted member', initialPassword: password,
      roleCodes: [roleCode], roleAssignmentMode: 'EXPLICIT', sendInviteEmail: false,
    } });
    expect(provision.ok()).toBe(true);
    const provisionBody = await provision.json();
    expect(provisionBody.code).toBe('0');
    expect(provisionBody.data.assignedRoles).toEqual([roleCode]);
    expect(provisionBody.data.mustChangePassword).toBe(false);
    expect(provisionBody.data.userPid).toBeTruthy();
    const copies = async () => {
      const response = await page.request.get('/api/pages', { params: { keyword: source.name, pageSize: 100 } });
      expect(response.ok()).toBe(true);
      const body = await response.json();
      expect(body.code).toBe('0');
      expect(body.data.records.map((record: { pid: string }) => record.pid)).toEqual(expect.arrayContaining([source.pid, copiedPid]));
      return body.data.records.map((record: { pid: string }) => record.pid).sort();
    };
    const beforeDenied = await copies();
    const restricted = await browser.newContext({ baseURL: BASE_URL, locale: 'zh-CN',
      storageState: { cookies: [], origins: [] }, extraHTTPHeaders: { Referer: `${BASE_URL}/` } });
    try {
      const restrictedPage = await restricted.newPage();
      await restrictedPage.goto('/login');
      await restrictedPage.locator('#identifier').fill(email);
      await restrictedPage.locator('#password').fill(password);
      await restrictedPage.locator('form').filter({ has: restrictedPage.locator('#identifier') }).locator('button[type="submit"]').click();
      await expect(restrictedPage).not.toHaveURL(/\/login(?:\?|$)/);
      if (restrictedPage.url().includes('/tenant-selection')) {
        await restrictedPage.getByTestId(`space-business-${provisionBody.data.tenantId}`).click();
        await expect(restrictedPage).not.toHaveURL(/tenant-selection/);
      }
      const profile = await restrictedPage.request.get('/api/user/profile');
      expect(profile.ok()).toBe(true);
      const profileBody = await profile.json();
      expect(profileBody.code).toBe('0');
      expect(profileBody.data).toMatchObject({ pid: provisionBody.data.userPid, email });
      await restrictedPage.goto('/');
      const menus = await restrictedPage.request.get('/api/menu/user');
      expect(menus.ok()).toBe(true);
      const menusBody = await menus.json();
      expect(menusBody.code).toBe('0');
      expect(JSON.stringify(menusBody.data)).not.toContain('page_schema_mgmt');
      await expect(restrictedPage.locator('a[href="/p/page_schema"]')).toHaveCount(0);
      const denied = await restrictedPage.request.post('/api/meta/commands/execute/pgm:duplicate_page_schema', {
        data: { targetRecordPid: source.pid },
      });
      expect(denied.status()).toBe(403);
      const deniedBody = await denied.json();
      expect(deniedBody.code).not.toBe('0');
      expect(deniedBody.message).toMatch(/permission|forbidden|denied|权限/i);
      expect(deniedBody.message).not.toMatch(/entitlement/i);
      expect(await copies()).toEqual(beforeDenied);
      const unchanged = await page.request.get(`/api/pages/${source.pid}`);
      expect(unchanged.ok()).toBe(true);
      const unchangedBody = await unchanged.json();
      expect(unchangedBody.code).toBe('0');
      expect(unchangedBody.data).toEqual(originalBody.data);
      await restrictedPage.screenshot({ path: testInfo.outputPath('T5-denied.png'), fullPage: true });
    } finally {
      await restricted.close();
    }

  });

  // -------------------------------------------------------------------------
  // T6: Save as Template — validation: empty name disables the save button
  // -------------------------------------------------------------------------
  test('T6 — save-as-template dialog disables save when name is empty', async ({ page }, testInfo) => {
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
    await page.screenshot({ path: testInfo.outputPath('T6-empty-name.png'), fullPage: true });

    // Restore name and button becomes enabled
    await nameInput.fill('Restored Name');
    await expect(saveBtn).toBeEnabled();

    // Cancel without saving — click Cancel button scoped to the dialog
    await dialog.getByRole('button', { name: /Cancel|取消/i }).click();
    await expect(dialog).not.toBeVisible({ timeout: 5000 });
  });
});
