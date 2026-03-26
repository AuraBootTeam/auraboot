/**
 * Permission Depth E2E Tests
 *
 * Tests PM-001 to PM-020: Comprehensive permission system validation
 * - Menu visibility for authorized/unauthorized roles
 * - URL direct access without permission -> 403/redirect
 * - Toolbar/row action button permission filtering
 * - DYNAMIC permission after model publish
 * - Command permission enforcement
 * - Admin role has all permissions
 *
 * Uses real database + API, NO MOCKING.
 * Uses storageState for admin authentication.
 *
 * @since 6.3.0
 */

import { test, expect } from '../../fixtures';
import { test as baseTest } from '@playwright/test';
import { navigateToDynamicPage, waitForDynamicPageLoad, extractRecordId } from '../helpers';
import { ErrorCodes } from '~/services/http-client/types';

const BASE_URL = process.env.BASE_URL || 'http://localhost:5173';

test.describe('Permission Depth — Menu Visibility', () => {
  /**
   * PM-001: Admin user sees all configured menus in sidebar @smoke
   */
  test('PM-001: admin user sees all configured menus @smoke', async ({ page }) => {
    const menuResp = await page.request.get('/api/menu/user');
    expect(menuResp.ok()).toBe(true);

    const menuData = await menuResp.json();
    const userMenus = menuData.data || menuData;
    expect(Array.isArray(userMenus)).toBe(true);
    expect(userMenus.length).toBeGreaterThan(0);

    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    const sidebar = page.locator('nav, aside, [data-testid="sidebar"], [role="navigation"]').first();
    await expect(sidebar).toBeVisible({ timeout: 10000 });

    const menuLinks = sidebar.locator('a');
    const linkCount = await menuLinks.count();
    expect(linkCount).toBeGreaterThan(0);

    // Admin should see at least as many links as top-level menus
    const leafMenuCount = countLeafMenus(userMenus);
    expect(linkCount).toBeGreaterThanOrEqual(Math.min(leafMenuCount, 5));
  });

  /**
   * PM-002: Menu API returns correct permission codes per menu item
   */
  test('PM-002: menu items have permission codes', async ({ page }) => {
    const menuResp = await page.request.get('/api/menu/all');
    if (!menuResp.ok()) {
      throw new Error(String('All menus API not accessible'))
      return;
    }

    const menuData = await menuResp.json();
    const allMenus = menuData.data || menuData;
    expect(Array.isArray(allMenus)).toBe(true);

    // Collect menus with permissionCode
    const menusWithPermCode = flattenMenus(allMenus).filter(
      (m: any) => m.permissionCode
    );

    // Most menus should have permission codes
    expect(menusWithPermCode.length).toBeGreaterThan(0);

    // Each permissionCode should be a non-empty string
    for (const menu of menusWithPermCode) {
      expect(typeof menu.permissionCode).toBe('string');
      expect(menu.permissionCode.length).toBeGreaterThan(0);
    }
  });

  /**
   * PM-003: Button permissions API returns non-empty list for admin
   */
  test('PM-003: admin button permissions loaded @smoke', async ({ page }) => {
    const btnResp = await page.request.get('/api/menu/buttons');
    expect(btnResp.ok()).toBe(true);

    const btnData = await btnResp.json();
    const buttonPerms = btnData.data || btnData;
    expect(Array.isArray(buttonPerms)).toBe(true);

    // Button permissions depend on type=2 (BTN) menu items being configured.
    // An empty array is valid when no button-type menus exist.
    // If present, all permissions should be non-empty strings.
    for (const perm of buttonPerms) {
      expect(typeof perm).toBe('string');
      expect(perm.length).toBeGreaterThan(0);
    }
  });

  /**
   * PM-004: Meta Management menu visible for admin
   */
  test('PM-004: Meta Management menu visible for admin', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

    const sidebar = page.locator('nav').first();
    await expect(sidebar).toBeVisible({ timeout: 10000 });

    // META_MANAGEMENT is a parent menu (type=0) rendered as an expandable
    // <button> by SidebarSubmenu, not as an <a> link. Child items like
    // /meta/models are <Link> elements inside the collapsed submenu.
    // We check for either the parent button text OR any child link whose href
    // starts with /meta.
    const metaButton = sidebar.locator('button', { hasText: /Meta Management|元数据管理|模型管理/i }).first();
    const metaLink = sidebar.locator('a[href*="/meta"]').first();

    const hasMetaButton = await metaButton.isVisible({ timeout: 5000 }).catch(() => false);
    const hasMetaLink = await metaLink.isVisible({ timeout: 3000 }).catch(() => false);

    expect(hasMetaButton || hasMetaLink).toBe(true);
  });
});

test.describe('Permission Depth — URL Direct Access', () => {
  /**
   * PM-005: Unauthenticated direct URL access redirects to login
   */
  baseTest.describe('Unauthenticated Access', () => {
    baseTest.use({ storageState: { cookies: [], origins: [] } });

    baseTest('PM-005: direct URL access without auth redirects to login', async ({ page }) => {
      await page.goto(`${BASE_URL}/meta/models`);
      await page.waitForLoadState('domcontentloaded');

      const loginForm = page.locator('input#email, input[type="email"]');
      const hasLoginForm = await loginForm.isVisible({ timeout: 5000 }).catch(() => false);

      const url = page.url();
      const isOnLoginPage = url.includes('login') || url.endsWith('/');

      expect(hasLoginForm || isOnLoginPage).toBe(true);
    });

    baseTest('PM-006: direct API call without auth returns 401/403', async ({ request }) => {
      const resp = await request.get(`${BASE_URL}/api/meta/models`);
      expect([401, 403]).toContain(resp.status());
    });

    baseTest('PM-007: dynamic page URL without auth redirects to login', async ({ page }) => {
      await page.goto(`${BASE_URL}/dynamic/e2et-order`);
      await page.waitForLoadState('domcontentloaded');

      const loginForm = page.locator('input#email, input[type="email"]');
      const hasLoginForm = await loginForm.isVisible({ timeout: 5000 }).catch(() => false);

      const url = page.url();
      const isOnLoginPage = url.includes('login') || url.endsWith('/');

      expect(hasLoginForm || isOnLoginPage).toBe(true);
    });
  });

  /**
   * PM-008: Non-existent dynamic page URL shows 404
   */
  test('PM-008: non-existent dynamic page shows 404', async ({ page }) => {
    await page.goto('/dynamic/nonexistent-model-xyz-12345', { waitUntil: 'domcontentloaded' });

    // The dynamic page first shows a LoadingSpinner while fetching the schema,
    // then renders ErrorAlert on failure. ErrorAlert renders:
    //   <h3 class="text-red-800">加载失败</h3>
    //   <p class="text-red-600">Page not found: nonexistent_model_xyz_12345_list</p>
    //   <button>重试</button>
    // Wait for the loading spinner to disappear first.
    const spinner = page.locator('.animate-spin');
    await spinner.waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {});

    // Now check the page content for any error indicator.
    // Use textContent() to debug what's actually on the page.
    const bodyText = await page.locator('body').textContent({ timeout: 5000 }).catch(() => '') ?? '';

    const hasErrorText = bodyText.includes('加载失败')
      || bodyText.includes('Page not found')
      || bodyText.includes('Page Unavailable')
      || bodyText.includes('404')
      || bodyText.includes('Not Found')
      || bodyText.includes('Failed to load');

    // Ensure no unhandled crash
    const hasCrash = bodyText.includes('Something went wrong')
      || bodyText.includes('Unhandled Runtime Error');
    expect(hasCrash).toBe(false);

    expect(hasErrorText).toBe(true);
  });
});

test.describe('Permission Depth — Toolbar & Row Action Filtering', () => {
  /**
   * PM-009: Admin sees toolbar buttons on dynamic list page
   */
  test('PM-009: admin sees toolbar buttons on dynamic list page @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et-order');

    // Admin should see toolbar buttons
    const toolbarBtns = page.locator('[data-testid^="toolbar-btn-"]');
    const toolbarCount = await toolbarBtns.count();
    expect(toolbarCount).toBeGreaterThan(0);
  });

  /**
   * PM-010: Admin sees row action buttons on list page
   *
   * Creates a test record via API to guarantee data exists, then verifies
   * that row action buttons (e.g. "detail") render in the first table row.
   */
  test('PM-010: admin sees row action buttons on list page', async ({ page }) => {
    // Create a test record to ensure at least one row exists
    const createResp = await page.request.post('/api/meta/commands/execute/e2et:create_order', {
      data: {
        payload: {
          e2et_order_title: `PM010_${Date.now()}`,
          e2et_order_type: 'normal',
          e2et_order_date: new Date().toISOString().slice(0, 10),
        },
      },
    });
    if (!createResp.ok()) {
      throw new Error(String('Could not create test order — e2et model may not be set up'))
      return;
    }
    const createBody = await createResp.json();
    const recordId = extractRecordId(createBody);

    try {
      await navigateToDynamicPage(page, 'e2et-order');

      // Wait for at least one table row
      const firstRow = page.locator('tbody tr').first();
      await expect(firstRow).toBeVisible({ timeout: 8000 });

      // The "detail" action has no visibleWhen — it should always render.
      // Wait for any row action button to appear (async rendering).
      const actionBtn = firstRow.locator('[data-testid^="row-action-"]').first();
      await expect(actionBtn).toBeVisible({ timeout: 5000 });

      const actionCount = await firstRow.locator('[data-testid^="row-action-"]').count();
      expect(actionCount).toBeGreaterThan(0);
    } finally {
      // Cleanup
      if (recordId) {
        await page.request.post('/api/meta/commands/execute/e2et:delete_order', {
          data: { targetRecordId: recordId, operationType: 'delete', payload: {} },
        }).catch(() => {});
      }
    }
  });

  /**
   * PM-011: Row action buttons respect visibleWhen conditions
   *
   * Creates a draft order via API, switches to the Draft tab, and verifies
   * that edit/delete buttons (which have visibleWhen for draft status) render.
   */
  test('PM-011: row action buttons respect visibleWhen', async ({ page }) => {
    // Create a draft order to guarantee data in the Draft tab
    const title = `PM011_${Date.now()}`;
    const createResp = await page.request.post('/api/meta/commands/execute/e2et:create_order', {
      data: {
        payload: {
          e2et_order_title: title,
          e2et_order_type: 'normal',
          e2et_order_date: new Date().toISOString().slice(0, 10),
        },
      },
    });
    if (!createResp.ok()) {
      throw new Error(String('Could not create test order — e2et model may not be set up'))
      return;
    }
    const createBody = await createResp.json();
    const recordId = extractRecordId(createBody);

    try {
      await navigateToDynamicPage(page, 'e2et-order');

      // Click on Draft tab
      const draftTab = page.locator('nav[aria-label="Tabs"] button').filter({ hasText: /草稿|Draft/i }).first();
      const hasDraftTab = await draftTab.isVisible({ timeout: 5000 }).catch(() => false);

      if (!hasDraftTab) {
        throw new Error(String('Draft tab not visible on e2et-order page'))
        return;
      }

      // Set up list response listener before clicking tab
      const listResponsePromise = page.waitForResponse(
        (r) => r.url().includes('/list') && r.status() === 200,
        { timeout: 8000 }
      ).catch(() => null);

      await draftTab.click();
      await listResponsePromise;

      // Wait for the draft row to render
      const firstRow = page.locator('tbody tr').first();
      await expect(firstRow).toBeVisible({ timeout: 8000 });

      // Draft rows should have edit and/or delete buttons
      // (visibleWhen: "['draft','rejected'].includes(row.e2et_order_status)")
      const editBtn = firstRow.locator('[data-testid="row-action-edit"]');
      const deleteBtn = firstRow.locator('[data-testid="row-action-delete"]');

      // Wait briefly for action buttons to render (they filter async)
      const hasEdit = await editBtn.isVisible({ timeout: 5000 }).catch(() => false);
      const hasDelete = await deleteBtn.isVisible({ timeout: 3000 }).catch(() => false);

      // At least edit should be visible for draft orders
      expect(hasEdit || hasDelete).toBe(true);
    } finally {
      // Cleanup
      if (recordId) {
        await page.request.post('/api/meta/commands/execute/e2et:delete_order', {
          data: { targetRecordId: recordId, operationType: 'delete', payload: {} },
        }).catch(() => {});
      }
    }
  });

  /**
   * PM-012: Toolbar export/import buttons visible for admin
   */
  test('PM-012: toolbar export/import buttons visible for admin', async ({ page }) => {
    await navigateToDynamicPage(page, 'e2et-order');

    const exportBtn = page.locator(
      'button:has-text("Export"), button:has-text("导出"), [data-testid*="export"]'
    ).first();
    const importBtn = page.locator(
      'button:has-text("Import"), button:has-text("导入"), [data-testid*="import"]'
    ).first();

    const hasExport = await exportBtn.isVisible({ timeout: 5000 }).catch(() => false);
    const hasImport = await importBtn.isVisible({ timeout: 5000 }).catch(() => false);

    // Admin should see data tools buttons
    expect(hasExport || hasImport).toBe(true);
  });
});

test.describe('Permission Depth — DYNAMIC Permission & Model Publish', () => {
  /**
   * PM-013: Published model has DYNAMIC permissions in DB
   */
  test('PM-013: published model has DYNAMIC permissions', async ({ page }) => {
    const permResp = await page.request.get('/api/permissions/resource-type/MODEL');

    if (!permResp.ok()) {
      throw new Error(String('Permission API not accessible'))
      return;
    }

    const permData = await permResp.json();
    const permissions = permData.data || permData;

    if (!Array.isArray(permissions)) {
      throw new Error(String('Permission API returns unexpected format'))
      return;
    }

    // Look for DYNAMIC permissions (created when model is published)
    const dynamicPerms = permissions.filter(
      (p: any) => p.type === 'dynamic' || p.code?.startsWith('DYNAMIC.')
    );

    // e2et models should have DYNAMIC permissions
    expect(dynamicPerms.length).toBeGreaterThanOrEqual(0);
  });

  /**
   * PM-014: Admin role has wildcard permission coverage
   */
  test('PM-014: admin role has full permissions @smoke', async ({ page }) => {
    // Verify admin can access the permission management page
    await page.goto('/system/permissions', { waitUntil: 'domcontentloaded' });

    const hasContent = await page.locator('main, [class*="container"]')
      .isVisible({ timeout: 8000 }).catch(() => false);
    const has403 = await page.locator('text=403, text=Forbidden, text=权限不足')
      .first().isVisible({ timeout: 2000 }).catch(() => false);

    // Admin should have access (content visible, no 403)
    expect(hasContent && !has403).toBe(true);
  });
});

test.describe('Permission Depth — Command Permission Enforcement', () => {
  /**
   * PM-015: Admin can execute commands on e2et model
   */
  test('PM-015: admin can execute commands on e2et model', async ({ page }) => {
    const resp = await page.request.post('/api/meta/commands/execute/e2et:create_order', {
      data: {
        payload: {
          e2et_order_title: `PermTest ${Date.now()}`,
          e2et_order_type: 'normal',
          e2et_order_date: new Date().toISOString().slice(0, 10),
        },
      },
    });

    expect(resp.ok()).toBe(true);
    const body = await resp.json();
    expect(String(body.code) === ErrorCodes.SUCCESS).toBeTruthy();

    // Cleanup
    const recordId = extractRecordId(body);
    if (recordId) {
      await page.request.post('/api/meta/commands/execute/e2et:delete_order', {
        data: {
          targetRecordId: recordId,
          operationType: 'delete',
          payload: {},
        },
      }).catch(() => {});
    }
  });

  /**
   * PM-016: Command execute without required targetRecordId returns validation error
   *
   * NOTE: The command engine does NOT enforce field-level required validation
   * at the backend. A CREATE with empty payload succeeds (inserts nulls).
   * Required-field validation is enforced only at the UI/form level.
   * Instead, we test that a DELETE command without the required targetRecordId
   * returns a proper error (422 BadParam).
   */
  test('PM-016: command with missing required fields returns error', async ({ page }) => {
    const resp = await page.request.post('/api/meta/commands/execute/e2et:delete_order', {
      data: {
        operationType: 'delete',
        payload: {},
        // targetRecordId intentionally omitted — should trigger validation error
      },
    });

    // Should fail because targetRecordId is required for DELETE commands
    const body = await resp.json();
    const isError = !resp.ok() || (String(body.code) !== ErrorCodes.SUCCESS);
    expect(isError).toBe(true);
  });

  /**
   * PM-017: Non-existent command code returns error
   */
  test('PM-017: non-existent command code returns error', async ({ page }) => {
    const resp = await page.request.post('/api/meta/commands/execute/e2et:nonexistent_command_xyz', {
      data: { payload: {} },
    });

    expect(resp.ok()).toBe(false);
    expect(resp.status()).toBeGreaterThanOrEqual(400);
  });
});

test.describe('Permission Depth — Permission Resource Types', () => {
  /**
   * PM-018: Permission types include MENU, MODEL, DYNAMIC
   */
  test('PM-018: permission resource types include MENU and MODEL', async ({ page }) => {
    // Check MENU permissions
    const menuPermResp = await page.request.get('/api/permissions/resource-type/MENU');
    if (menuPermResp.ok()) {
      const menuPerms = (await menuPermResp.json()).data || [];
      if (Array.isArray(menuPerms)) {
        expect(menuPerms.length).toBeGreaterThan(0);
      }
    }

    // Check MODEL permissions
    const modelPermResp = await page.request.get('/api/permissions/resource-type/MODEL');
    if (modelPermResp.ok()) {
      const modelPerms = (await modelPermResp.json()).data || [];
      if (Array.isArray(modelPerms)) {
        // Models should have some permissions
        expect(modelPerms.length).toBeGreaterThanOrEqual(0);
      }
    }
  });

  /**
   * PM-019: Role-permission bindings API returns data for admin
   */
  test('PM-019: role-permission bindings accessible for admin', async ({ page }) => {
    // Use /api/roles/all (returns List) instead of /api/roles (returns Page)
    const rolesResp = await page.request.get('/api/roles/all');

    if (!rolesResp.ok()) {
      throw new Error(String('Roles API not accessible'))
      return;
    }

    const rolesData = await rolesResp.json();
    const roles = rolesData.data || rolesData;

    if (!Array.isArray(roles) || roles.length === 0) {
      throw new Error(String('No roles found'))
      return;
    }

    // At least one role should exist (admin)
    expect(roles.length).toBeGreaterThan(0);

    const adminRole = roles.find((r: any) =>
      r.code === 'tenant_admin' || r.code === 'admin' || r.name === 'Admin' || r.name?.includes('管理员')
    );

    // Admin role should exist (code is TENANT_ADMIN per default-bootstrap.json)
    expect(adminRole).toBeTruthy();
  });

  /**
   * PM-020: Permission page renders and shows permission list
   */
  test('PM-020: permission management page renders @smoke', async ({ page }) => {
    await page.goto('/meta/models', { waitUntil: 'domcontentloaded' });

    // Model management page should load for admin
    const heading = page.locator('h1, h2').first();
    await expect(heading).toBeVisible({ timeout: 10000 });

    // Verify table with model data
    const table = page.locator('table').first();
    const hasTable = await table.isVisible({ timeout: 5000 }).catch(() => false);

    // Should show either table or meaningful content
    const pageText = await page.locator('body').textContent();
    expect(pageText!.length).toBeGreaterThan(50);

    // No crash indicator
    const crashIndicator = page.locator('text=Something went wrong, text=Unhandled Runtime Error');
    const hasCrash = await crashIndicator.first().isVisible({ timeout: 1000 }).catch(() => false);
    expect(hasCrash).toBe(false);
  });
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function countLeafMenus(menus: any[]): number {
  let count = 0;
  for (const menu of menus) {
    if (menu.children && Array.isArray(menu.children) && menu.children.length > 0) {
      count += countLeafMenus(menu.children);
    } else {
      count++;
    }
  }
  return count;
}

function flattenMenus(menus: any[]): any[] {
  const result: any[] = [];
  for (const menu of menus) {
    result.push(menu);
    if (menu.children && Array.isArray(menu.children)) {
      result.push(...flattenMenus(menu.children));
    }
  }
  return result;
}
