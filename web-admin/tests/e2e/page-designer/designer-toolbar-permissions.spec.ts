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
 * 2. Permission-stripped loader data — remove `page.page.manage` from the initial
 *    SSR loader payload, then assert the managed toolbar buttons carry the
 *    `disabled` attribute and permission-denial label.
 *
 * Dimensions: D1 (sidebar nav), D6 (toolbar renders), D9 (permission guard), D14 (RBAC)
 *
 * @since 3.3.0
 */

import { test, expect, type Page } from '../../fixtures';
import { BASE_URL } from '../../helpers/environments';
import { uniqueId } from '../helpers';

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
  const objectCodes = Array.isArray(permissions.permissions)
    ? permissions.permissions
        .map((permission: { code?: unknown }) => permission.code)
        .filter((code: unknown): code is string => typeof code === 'string' && code.length > 0)
    : [];
  const codes = [
    ...(Array.isArray(permissions.permissionCodes) ? permissions.permissionCodes : []),
    ...objectCodes,
  ].filter((code: unknown): code is string => typeof code === 'string' && code.length > 0);
  expect(codes.includes(permissionCode)).toBe(expected);
}

async function expectPermissionDisabled(page: Page, testId: string): Promise<void> {
  const button = page.getByTestId(testId);
  await expect(button).toBeVisible();
  await expect(button).toBeDisabled();
  await expect(button).toHaveAttribute('aria-label', /permission|权限|管理/i);
}

async function stripManagePermissionFromInitialDocument(
  page: Page,
  pid: string,
): Promise<() => number> {
  let strippedCount = 0;
  await page.route(`**/page-designer/${pid}**`, async (route) => {
    const response = await route.fetch();
    const contentType = response.headers()['content-type'] ?? '';
    if (!contentType.includes('text/html')) {
      await route.fulfill({ response });
      return;
    }

    const html = await response.text();
    const stripped = html.replaceAll('page.page.manage', 'page.page.manage__removed_for_e2e');
    strippedCount += (html.match(/page\.page\.manage/g) ?? []).length;
    const headers = Object.fromEntries(
      Object.entries(response.headers()).filter(
        ([key]) => !['content-encoding', 'content-length'].includes(key.toLowerCase()),
      ),
    );
    await route.fulfill({
      status: response.status(),
      headers: { ...headers, 'content-type': contentType },
      body: stripped,
    });
  });
  return () => strippedCount;
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

  // ── D9, D14: Permission-stripped loader data disables managed toolbar actions.
  test('managed toolbar buttons are disabled when page.page.manage permission is absent', async ({
    page,
    baseURL,
  }) => {
    const pid = await createFixturePage(page);
    await expectCurrentUserPermission(page, 'page.page.manage', true);
    const strippedCount = await stripManagePermissionFromInitialDocument(page, pid);
    await page.goto(appBaseUrl(baseURL), { waitUntil: 'domcontentloaded' });
    await openDesigner(page, pid);
    expect(strippedCount(), 'SSR loader HTML must include page.page.manage before stripping').toBeGreaterThan(0);
    await expectPermissionDisabled(page, 'toolbar-save');
    await expectPermissionDisabled(page, 'toolbar-publish');
    await expectPermissionDisabled(page, 'toolbar-import');
    await expectPermissionDisabled(page, 'toolbar-export');
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
