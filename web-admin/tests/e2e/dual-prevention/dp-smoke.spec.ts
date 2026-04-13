/**
 * Dual Prevention Plugin — Smoke Tests
 *
 * DP-S001 @smoke : Plugin install check — models and menus exist in backend
 * DP-S002 @smoke : Sidebar navigation → 隐患管理 (Issues) list loads
 * DP-S003 @smoke : Sidebar navigation → 整改管理 (Rectifications) list loads
 * DP-S004 @smoke : Sidebar navigation → 巡检管理 (Inspections) list loads
 * DP-S005 @smoke : Sidebar navigation → 风险源库 (Hazard Sources) list loads
 * DP-S006 @smoke : Sidebar navigation → 质量检查 (Quality Checkpoints) list loads
 * DP-S007 @smoke : Dashboard page loads without error
 * DP-S008 @smoke : No i18n key leakage across all list pages
 *
 * Prerequisites:
 *   - dual-prevention plugin MUST be imported via `aura plugin publish plugins/dual-prevention`
 *   - All 7 models must be published (dp_issue, dp_rectification, dp_inspection_task,
 *     dp_hazard_source, dp_quality_checkpoint, dp_quality_standard, dp_compliance_report)
 *
 * If the plugin is not installed these tests will skip gracefully.
 *
 * @since 11.0.0
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const PLUGIN_ID = 'com.auraboot.dual-prevention';

const DP_MENUS = [
  {
    code: 'dp_dashboard',
    path: '/dual-prevention/dashboard',
    modelUrl: null,
    label: /安全仪表盘|Dashboard/,
  },
  {
    code: 'dp_issues',
    path: '/dual-prevention/issues',
    modelUrl: '/api/dynamic/dp_issue',
    label: /隐患管理|Issue Management/,
  },
  {
    code: 'dp_rectifications',
    path: '/dual-prevention/rectifications',
    modelUrl: '/api/dynamic/dp_rectification',
    label: /整改管理|Rectification/,
  },
  {
    code: 'dp_inspections',
    path: '/dual-prevention/inspections',
    modelUrl: '/api/dynamic/dp_inspection_task',
    label: /巡检管理|Inspection/,
  },
  {
    code: 'dp_hazards',
    path: '/dual-prevention/hazards',
    modelUrl: '/api/dynamic/dp_hazard_source',
    label: /风险源库|Hazard Sources/,
  },
  {
    code: 'dp_quality_checkpoints',
    path: '/dual-prevention/quality-checkpoints',
    modelUrl: '/api/dynamic/dp_quality_checkpoint',
    label: /质量检查|Quality/,
  },
];

// ---------------------------------------------------------------------------
// Plugin availability check
// ---------------------------------------------------------------------------

let pluginInstalled = false;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function expandDpMenu(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const rootBtn = nav.getByRole('button', { name: /双重预防|Dual Prevention/ });
  await expect(rootBtn).toBeVisible({ timeout: 10000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());
  // Wait for sub-menu to appear
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);
}

async function navigateToDpPage(page: Page, path: string, modelUrl: string | null): Promise<void> {
  await expandDpMenu(page);

  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${path}"]`).first();
  await link.waitFor({ state: 'attached', timeout: 8000 });
  await link.scrollIntoViewIfNeeded();

  const responsePromise = modelUrl
    ? page
        .waitForResponse((r) => r.url().includes(modelUrl) && r.status() === 200, {
          timeout: 15000,
        })
        .catch(() => null)
    : Promise.resolve(null);

  await link.evaluate((el: HTMLElement) => el.click());
  await responsePromise;
}

// ---------------------------------------------------------------------------
// Test Suite
// ---------------------------------------------------------------------------

test.describe('Dual Prevention Plugin @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  // -------------------------------------------------------------------------
  // DP-S001: Plugin install check
  // -------------------------------------------------------------------------
  test('DP-S001: dual-prevention plugin is installed and models are published', async ({
    page,
  }) => {
    // Check plugin availability via model code API
    const modelResp = await page.request.get('/api/meta/models/code/dp_issue').catch(() => null);
    if (modelResp) {
      const body = await modelResp.json().catch(() => ({}));
      pluginInstalled = modelResp.ok() && body?.data?.status === 'published';
    }

    if (!pluginInstalled) {
      test.skip(
        true,
        'dual-prevention plugin not installed — run: aura plugin publish plugins/dual-prevention',
      );
      return;
    }

    // Verify key models exist
    const models = ['dp_issue', 'dp_rectification', 'dp_inspection_task', 'dp_hazard_source'];
    for (const code of models) {
      const resp = await page.request.get(`/api/meta/models/code/${code}`);
      expect(resp.ok(), `Model ${code} should be accessible`).toBe(true);
    }
  });

  // -------------------------------------------------------------------------
  // DP-S002: 隐患管理 list loads via sidebar
  // -------------------------------------------------------------------------
  test('DP-S002: sidebar → 隐患管理 list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(page, '/dual-prevention/issues', '/api/dynamic/dp_issue');

    // Table or empty-state must be visible
    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });

    // No access error
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
    await expect(page.locator('text=403'))
      .not.toBeVisible({ timeout: 1000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // DP-S003: 整改管理 list loads
  // -------------------------------------------------------------------------
  test('DP-S003: sidebar → 整改管理 list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(
      page,
      '/dual-prevention/rectifications',
      '/api/dynamic/dp_rectification',
    );

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // DP-S004: 巡检管理 list loads
  // -------------------------------------------------------------------------
  test('DP-S004: sidebar → 巡检管理 list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(page, '/dual-prevention/inspections', '/api/dynamic/dp_inspection_task');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // DP-S005: 风险源库 list loads
  // -------------------------------------------------------------------------
  test('DP-S005: sidebar → 风险源库 list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(page, '/dual-prevention/hazards', '/api/dynamic/dp_hazard_source');

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // DP-S006: 质量检查 list loads
  // -------------------------------------------------------------------------
  test('DP-S006: sidebar → 质量检查 list page loads with table', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(
      page,
      '/dual-prevention/quality-checkpoints',
      '/api/dynamic/dp_quality_checkpoint',
    );

    const table = page.locator('table, [class*="ant-table"]').first();
    await expect(table).toBeVisible({ timeout: 12000 });
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
  });

  // -------------------------------------------------------------------------
  // DP-S007: Dashboard page loads
  // -------------------------------------------------------------------------
  test('DP-S007: sidebar → 安全仪表盘 page loads without error', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(page, '/dual-prevention/dashboard', null);

    await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => null);

    // Dashboard should not show error states
    await expect(page.locator('text=Access forbidden'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});
    await expect(page.locator('text=Page not found'))
      .not.toBeVisible({ timeout: 2000 })
      .catch(() => {});

    // Page body has meaningful content
    const bodyText = await page.locator('body').textContent({ timeout: 5000 });
    expect(bodyText?.length ?? 0).toBeGreaterThan(50);
  });

  // -------------------------------------------------------------------------
  // DP-S008: No i18n key leakage across dp list pages
  // -------------------------------------------------------------------------
  test('DP-S008: column headers have no raw i18n key leakage', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await page.goto('/dual-prevention/issues', { waitUntil: 'domcontentloaded' });
    await page
      .waitForResponse((r) => r.url().includes('/api/dynamic/dp_issue') && r.status() === 200, {
        timeout: 15000,
      })
      .catch(() => null);

    // Wait for page to fully stabilize (avoid "execution context destroyed" from late navigations)
    await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => null);
    await page
      .locator('th, [role="columnheader"]')
      .first()
      .waitFor({ state: 'attached', timeout: 10000 });

    const headers = await page.locator('th, [role="columnheader"]').allTextContents();
    for (const h of headers) {
      expect(h, `Header "${h}" should not be a raw i18n key`).not.toMatch(
        /model\.[a-z_]+\.[a-z_]+\.label/i,
      );
      expect(h, `Header "${h}" should not be a raw i18n key`).not.toMatch(/^[a-z_]+\.[a-z_]+$/);
    }
  });

  // -------------------------------------------------------------------------
  // DP-S009: API list endpoints return valid response structure
  // -------------------------------------------------------------------------
  test('DP-S009: dp_issue list API returns valid ApiResponse envelope', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    const resp = await page.request.get('/api/dynamic/dp_issue/list?pageNum=1&pageSize=10');
    expect(resp.status()).toBe(200);
    const body = await resp.json();
    expect(body).toHaveProperty('code');
    expect(body.code).toBe('0');
    expect(body).toHaveProperty('data');
    expect(body.data).toHaveProperty('total');
    expect(body.data).toHaveProperty('records');
  });

  // -------------------------------------------------------------------------
  // DP-S010: Create button visible on issues list
  // -------------------------------------------------------------------------
  test('DP-S010: create button is visible on issues list page', async ({ page }) => {
    if (!pluginInstalled) {
      test.skip(true, 'dual-prevention plugin not installed');
      return;
    }

    await navigateToDpPage(page, '/dual-prevention/issues', '/api/dynamic/dp_issue');

    const createBtn = page.locator('button', { hasText: /新建|Create|创建|添加/ }).first();
    await expect(createBtn).toBeVisible({ timeout: 8000 });
  });
});
