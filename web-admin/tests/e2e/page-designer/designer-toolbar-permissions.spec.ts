/**
 * Designer Toolbar Permission Pre-check E2E Tests
 *
 * Verifies that the DesignerToolbar enforces permission-gating:
 * - Save button requires `page.page.manage`
 * - Publish button requires `page.page.manage`
 * - Import button requires `page.page.manage`
 * - Export button requires `page.page.manage`
 *
 * Backend PageSchemaController uses a single page.page.manage permission for all
 * mutation endpoints. There are no fine-grained keys in the RBAC registry, so the
 * frontend uses one unified permission check for all toolbar action buttons.
 *
 * Strategy:
 * 1. Admin user (has page.page.manage) — positive test: buttons are not permission-disabled.
 * 2. A real scoped viewer without a wildcard or page.page.manage — managed
 *    toolbar actions are disabled; a real backend write is denied without mutation.
 *
 * Dimensions: D1 (sidebar nav), D6 (toolbar renders), D9 (permission guard), D14 (RBAC)
 *
 * @since 3.3.0
 */

import { test, expect, type Page } from '../../fixtures';
import { BASE_URL } from '../../helpers/environments';
import { uniqueId } from '../helpers';
import { grantRolePermissionByCode, createAuthenticatedRoleContext, collectPermissionCodes } from '../../helpers/designer-role-fixtures';

// ── helpers ─────────────────────────────────────────────────────────────────

async function createFixturePage(page: Page): Promise<string> {
  const id = uniqueId('pd_toolbar');
  const pageKey = `pd_toolbar_${id}`;
  const resp = await page.request.post('/api/pages', {
    data: {
      pageKey,
      name: `PD toolbar ${id}`,
      title: `PD toolbar ${id}`,
      kind: 'form',
      modelCode: 'tenant',
      layout: { type: 'stack' },
      blocks: [
        {
          id: 'seed_form_section',
          blockType: 'form-section',
          title: { 'en-US': 'Seed Section' },
          fields: [],
        },
      ],
      semver: '0.1.0',
      extension: { e2e: true, scenario: 'designer-toolbar-permissions' },
    },
  });
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  expect(body.data?.pid).toBeTruthy();
  return body.data.pid;
}

/**
 * Navigate to the page designer for a given page pid and wait for the toolbar to appear.
 */
async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/page-designer/${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.getByTestId('toolbar-publish')).toBeVisible({ timeout: 15000 });
}

function appBaseUrl(baseURL?: string): string {
  return baseURL ?? BASE_URL;
}

async function expectCurrentUserPermission(
  page: Page,
  permissionCode: string,
  expected: boolean,
): Promise<void> {
  const resp = await page.request.get('/api/auth/me');
  expect(resp.ok(), await resp.text()).toBe(true);
  const body = await resp.json();
  expect(body.code).toBe('0');
  const permissions = body.data?.permissions ?? {};
  const codes = collectPermissionCodes(permissions);
  expect(codes.includes('*') || codes.includes(permissionCode)).toBe(expected);
  if (!expected) expect(codes).not.toContain('*');
}

async function expectPermissionDisabled(page: Page, testId: string): Promise<void> {
  const button = page.getByTestId(testId);
  await expect(button).toBeVisible();
  await expect(button).toBeDisabled();
  await expect(button).toHaveAttribute('aria-label', /permission|权限|管理/i);
}

// ── tests ────────────────────────────────────────────────────────────────────

test.describe('Designer toolbar permission pre-check', () => {
  test.setTimeout(60_000);

  // ── D6, D14: Admin has page.page.manage — Publish button is NOT permission-disabled
  test('admin user sees publish button enabled (has page.page.manage)', async ({ page }) => {
    const pid = await createFixturePage(page);
    await openDesigner(page, pid);
    await expectCurrentUserPermission(page, 'page.page.manage', true);

    const publishBtn = page.locator('[data-testid="toolbar-publish"]');
    await expect(publishBtn).toBeVisible();
    await expect(publishBtn).toBeEnabled();

    // Admin has page.page.manage → button is NOT disabled due to permissions.
    const ariaLabel = await publishBtn.getAttribute('aria-label');
    expect(ariaLabel).not.toContain('do not have permission');
    expect(ariaLabel).not.toContain('没有管理');
  });

  // ── D6, D14: Admin has page.page.manage — Save button is NOT permission-disabled
  test('admin user sees save button accessible (has page.page.manage)', async ({ page }) => {
    const pid = await createFixturePage(page);
    await openDesigner(page, pid);
    await expectCurrentUserPermission(page, 'page.page.manage', true);

    const saveBtn = page.locator('[data-testid="toolbar-save"]');
    await expect(saveBtn).toBeVisible();

    // Admin has page.page.manage → save button title must NOT indicate permission denial
    const ariaLabel = await saveBtn.getAttribute('aria-label');
    expect(ariaLabel).not.toContain('do not have permission');
    expect(ariaLabel).not.toContain('没有管理');
  });

  // ── D9, D14: A real scoped identity proves UI and backend denial.
  test('managed toolbar buttons are disabled when page.page.manage permission is absent', async ({
    page, browser, baseURL,
  }, info) => {
    const pid = await createFixturePage(page);
    const beforeResponse = await page.request.get(`/api/pages/${pid}`);
    expect(beforeResponse.status()).toBe(200);
    const before = await beforeResponse.json();
    expect(String(before.code)).toBe('0');
    for (const permission of ['meta.designer.read', 'page.page.read']) {
      await grantRolePermissionByCode(page, 'e2et_viewer', permission);
    }
    const context = await createAuthenticatedRoleContext(browser, appBaseUrl(baseURL), 'viewer');
    try {
      const viewer = await context.newPage();
      await expectCurrentUserPermission(viewer, 'page.page.manage', false);
      await openDesigner(viewer, pid);
      for (const id of ['toolbar-save', 'toolbar-publish', 'toolbar-import', 'toolbar-export']) {
        await expectPermissionDisabled(viewer, id);
      }
      const denied = await viewer.request.put(`/api/pages/${pid}`, {
        data: { ...before.data, title: 'Forbidden scoped viewer update' },
      });
      expect(denied.status()).toBe(403);
      const afterResponse = await page.request.get(`/api/pages/${pid}`);
      expect(afterResponse.status()).toBe(200);
      const after = await afterResponse.json();
      expect(String(after.code)).toBe('0');
      expect(after.data.title).toEqual(before.data.title);
      expect(after.data.blocks).toEqual(before.data.blocks);
      await viewer.screenshot({ path: info.outputPath('designer-scoped-toolbar-denied.png'), fullPage: true });
    } finally {
      await context.close();
    }
  });

  // ── D6: Import and Export buttons have data-testid and are visible
  test('import and export buttons are visible in the toolbar', async ({ page }) => {
    const pid = await createFixturePage(page);
    await openDesigner(page, pid);
    await expectCurrentUserPermission(page, 'page.page.manage', true);

    await expect(page.locator('[data-testid="toolbar-import"]')).toBeVisible();
    await expect(page.locator('[data-testid="toolbar-export"]')).toBeVisible();
    await expect(page.locator('[data-testid="toolbar-import"]')).toBeEnabled();
    await expect(page.locator('[data-testid="toolbar-export"]')).toBeEnabled();
  });
});
