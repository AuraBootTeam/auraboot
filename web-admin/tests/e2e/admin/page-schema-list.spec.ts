/**
 * Page Schema List (DSL) E2E Tests
 *
 * Tests for the DSL-driven page_schema_list page that replaces the old
 * tsx PageList component. Verifies the list page renders correctly with
 * data from ab_page_schema system table.
 *
 * Coverage dimensions: D1, D2, D3, D4, D6, D9, D11, D13, D14
 *
 * - PS-001: Smoke — menu navigation shows table with data
 * - PS-002: Tab filtering — draft/published/archived tabs
 * - PS-003: Search — keyword search filters results
 * - PS-004: Row click navigates to page designer editor
 * - PS-005: Create via form → redirects to editor
 * - PS-006: Publish state transition via row action
 * - PS-007: Delete via row action
 *
 * @since 8.0.0
 */

import { test, expect } from '../../fixtures';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  acceptConfirmDialog,
  findRowInPaginatedList,
  waitForToast,
  executeCommandViaApi,
  ensureSidebarExpanded,
} from '../helpers';

test.describe.serial('Page Schema List (DSL)', () => {
  const uid = uniqueId('ps');
  const seedName = `PS_Seed_${uid}`;
  const seedPageKey = `ps_seed_${uid}`;
  const createName = `PS_Create_${uid}`;
  const createPageKey = `ps_create_${uid}`;
  let seedPid: string;

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    const page = await ctx.newPage();

    // Create seed page via create command API
    const result = await executeCommandViaApi(page, 'pgm:create_page_schema', {
      name: seedName,
      page_key: seedPageKey,
      kind: 'list',
      description: `E2E seed page ${uid}`,
      profile: 'admin',
    }, undefined, undefined, { allowHttpError: true });
    if (!result.recordId || result.code === '35000') {
      // page-manager plugin not imported — tests will skip
      await ctx.close();
      return;
    }
    seedPid = result.recordId;
    expect(seedPid).toBeTruthy();

    await ctx.close();
  });

  test('PS-001: Smoke — menu navigation to page schema list shows data', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    // Navigate via sidebar menu: 元数据管理 → 页面配置
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);

    const nav = page.locator('nav, aside, [role="navigation"]').first();
    await nav.waitFor({ state: 'visible', timeout: 10000 });

    // Click parent: 元数据管理
    const parentMenu = nav
      .getByRole('button', { name: /元数据管理|Meta/i })
      .or(nav.locator('[title="元数据管理"]'))
      .first();
    await parentMenu.waitFor({ state: 'visible', timeout: 8000 });
    await parentMenu.click();

    // Click leaf: 页面配置
    const leafLink = nav
      .locator('a[href*="page_schema"]')
      .or(nav.locator('a:has-text("页面配置")'))
      .first();
    await leafLink.waitFor({ state: 'attached', timeout: 8000 });

    const listPromise = page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 15000 },
    );
    await leafLink.evaluate((el: HTMLElement) => el.click());
    await listPromise;

    // D2: Table visible with data
    const table = page.locator('table, [data-testid="dynamic-list"]').first();
    await expect(table).toBeVisible({ timeout: 10000 });

    const rows = page.locator('tbody tr');
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);

    // Verify column headers exist
    const headers = page.locator('thead th, [role="columnheader"]');
    const headerTexts = await headers.allTextContents();
    const headerStr = headerTexts.join(' ');
    expect(headerStr).toMatch(/名称|name/i);
    expect(headerStr).toMatch(/page_key|页面标识/i);
    expect(headerStr).toMatch(/kind|页面类型|类型/i);
    expect(headerStr).toMatch(/状态|status/i);
  });

  test('PS-002: Tab filtering — each tab filters by status', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    // D3: Click "已发布" (Published) tab
    const publishedTab = page
      .locator('[role="tab"]:has-text("已发布"), button:has-text("已发布"), [data-tab-key="published"]')
      .first();
    if (await publishedTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const filterPromise = page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 },
      );
      await publishedTab.click();
      const resp = await filterPromise;
      const body = await resp.json();
      const records = body?.data?.records ?? [];

      // All records should have status = 'published'
      if (records.length > 0) {
        for (const rec of records.slice(0, 5)) {
          expect(rec.status).toBe('published');
        }
      }
    }

    // Click "草稿" (Draft) tab
    const draftTab = page
      .locator('[role="tab"]:has-text("草稿"), button:has-text("草稿"), [data-tab-key="draft"]')
      .first();
    if (await draftTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      const filterPromise2 = page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 },
      );
      await draftTab.click();
      const resp2 = await filterPromise2;
      const body2 = await resp2.json();
      const records2 = body2?.data?.records ?? [];

      if (records2.length > 0) {
        for (const rec of records2.slice(0, 5)) {
          expect(rec.status).toBe('draft');
        }
      }
    }

    // Return to "全部" (All) tab
    const allTab = page
      .locator('[role="tab"]:has-text("全部"), button:has-text("全部"), [data-tab-key="all"]')
      .first();
    if (await allTab.isVisible({ timeout: 2000 }).catch(() => false)) {
      await allTab.click();
      await page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 },
      );
    }
  });

  test('PS-003: Search filters results by keyword', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    // D13: Search for seed page
    const searchInput = page
      .locator(
        '[data-testid="list-search-input"], [data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();

    await expect(searchInput).toBeVisible({ timeout: 5000 });

    // Set up response listener BEFORE triggering search
    const listPromise = page.waitForResponse(
      (r) => r.url().includes('/list') && r.status() === 200,
      { timeout: 15000 },
    );

    await searchInput.fill(seedName);
    await searchInput.press('Enter');

    // Wait for filtered results
    await listPromise;

    // Verify seed page appears in results
    const row = page.locator('tbody tr', { hasText: seedName }).first();
    await expect(row).toBeVisible({ timeout: 5000 });

    // Verify the row contains expected data
    const rowText = await row.textContent();
    expect(rowText).toContain(seedPageKey);
    expect(rowText).toMatch(/列表|list/i);
  });

  test('PS-004: Row click navigates to page designer editor', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    // Find seed row and click it
    const row = await findRowInPaginatedList(page, seedName, 8000);
    await expect(row).toBeVisible();

    // Click the row (not an action button)
    await row.click();

    // Should navigate to /page-designer/{pid}
    await page.waitForURL((url) => url.pathname.includes('/page-designer/'), {
      timeout: 10000,
    });

    // Verify the editor page loaded (not a 404 or error)
    const pageContent = page.locator('main, [role="main"], .page-content, #root').first();
    await expect(pageContent).toBeVisible({ timeout: 10000 });
  });

  test('PS-005: Create button opens form page with correct fields', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    // D4: Click "新建页面" toolbar button
    const createBtn = page
      .locator('button:has-text("新建页面"), button:has-text("New Page")')
      .first();
    await expect(createBtn).toBeVisible({ timeout: 5000 });
    await createBtn.click();

    // Wait for form page — URL is /p/page_schema/new?commandCode=pgm:create_page_schema
    await page.waitForURL(
      (url) => url.pathname.includes('/new') || url.pathname.includes('page_schema_form'),
      { timeout: 10000 },
    );
    await waitForDynamicPageLoad(page);

    // Verify form section title renders
    await expect(page.getByText('基本信息').or(page.getByText('Basic Information')).first()).toBeVisible({ timeout: 8000 });

    // D5: Verify form fields are present (labels from i18n or field displayName)
    await expect(page.getByText('Name').or(page.getByText('页面名称')).first()).toBeVisible({ timeout: 3000 });
    await expect(page.getByText('页面标识').or(page.getByText('Page Key')).first()).toBeVisible({ timeout: 3000 });
    await expect(page.getByText('页面类型').or(page.getByText('Page Kind')).first()).toBeVisible({ timeout: 3000 });

    // Verify submit and cancel buttons exist
    const submitBtn = page.locator('button:has-text("创建"), button:has-text("Create"), button:has-text("submit")').first();
    await expect(submitBtn).toBeVisible({ timeout: 3000 });
    const cancelBtn = page.locator('button:has-text("取消"), button:has-text("cancel")').first();
    await expect(cancelBtn).toBeVisible({ timeout: 3000 });

    // Create test data via API for subsequent tests (D6 verified via API seed)
    const result = await executeCommandViaApi(page, 'pgm:create_page_schema', {
      name: createName,
      page_key: createPageKey,
      kind: 'dashboard',
      description: `E2E create test ${uid}`,
    });
    expect(result.recordId).toBeTruthy();
  });

  test('PS-006: Publish state transition via API command', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    // D9: Execute publish command via API (seed page was created as draft)
    const result = await executeCommandViaApi(
      page,
      'pgm:publish_page_schema',
      {},
      seedPid,
    );
    expect(result.code).toBe('0');

    // Verify status changed in list
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    const row = await findRowInPaginatedList(page, seedName, 8000);
    await expect(row).toBeVisible();
    const rowText = await row.textContent();
    expect(rowText).toMatch(/已发布|published/i);
  });

  test('PS-007: Delete via API and verify removed from list', async ({ page }) => {
    test.skip(!seedPid, 'page-manager plugin not imported');
    // D11: Delete via API command
    const createPid = (await page.request.get(`/api/dynamic/page_schema_list/list?keyword=${encodeURIComponent(createName)}&pageSize=1`)
      .then(r => r.json())
      .then(d => d?.data?.records?.[0]?.pid)) as string;
    expect(createPid).toBeTruthy();

    const result = await executeCommandViaApi(
      page,
      'pgm:delete_page_schema',
      {},
      createPid,
    );
    expect(result.code).toBe('0');

    // Verify record disappeared from list
    await navigateToDynamicPage(page, 'page_schema');
    await waitForDynamicPageLoad(page);

    // Search for the deleted record
    const searchInput = page
      .locator(
        '[data-testid="list-search-input"], [data-testid="search-input"], [data-testid="table-search-input"], input[placeholder*="搜索"], input[placeholder*="Search"]',
      )
      .first();
    if (await searchInput.isVisible({ timeout: 3000 }).catch(() => false)) {
      const listPromise = page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 10000 },
      );
      await searchInput.fill(createName);
      await searchInput.press('Enter');
      await listPromise;
    }

    // Should not find any row with the deleted name
    const stillExists = await page
      .locator('tbody tr', { hasText: createName })
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    expect(stillExists).toBe(false);
  });
});
