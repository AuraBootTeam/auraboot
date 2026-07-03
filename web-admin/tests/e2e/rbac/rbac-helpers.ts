/**
 * RBAC golden suite (Slice 4) — self-contained per-role helpers.
 *
 * The B-layer (browser) counterpart to the backend A-layer
 * (platform/.../integration/security/rbac/RbacEnforcementMatrixIT.java). Both read the SAME SOT
 * matrix (platform/src/test/resources/rbac/rbac-access-matrix.json) so the browser golden and the
 * enforcement IT can never drift.
 *
 * Deliberately self-contained (only reuses the canonical `loginViaUI`) — it does NOT import the heavy
 * pcba-solution/quote-e2e-helpers module so the OSS rbac suite carries no vertical coupling. The
 * snapshot logic mirrors quote-e2e-helpers.fetchRoleSnapshot verbatim.
 */
import type { Browser, BrowserContext, Page } from '@playwright/test';
import { expect } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loginViaUI } from '../../helpers/wd-fixtures';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** SOT matrix — shared with the backend A-layer. Repo-root relative: rbac → e2e → tests → web-admin → root. */
const MATRIX_PATH = path.resolve(
  __dirname,
  '../../../../platform/src/test/resources/rbac/rbac-access-matrix.json',
);

export const RBAC_TEST_PASSWORD = 'Test2026x';

export type RbacRoleEntry = { layer: string; assignment: string; allow: string[]; deny: string[] };

/** Load the platform-baseline role entries (tenant_admin + tenant_member) from the SOT matrix. */
export function loadPlatformBaseline(): Record<string, RbacRoleEntry> {
  const matrix = JSON.parse(fs.readFileSync(MATRIX_PATH, 'utf-8'));
  const roles = matrix?.deployments?.['platform-baseline']?.roles;
  if (!roles) throw new Error(`rbac-access-matrix.json missing deployments.platform-baseline.roles (${MATRIX_PATH})`);
  return roles;
}

export type RoleUser = { key: string; email: string; displayName: string; password: string; roleCodes: string[] };

export function makeRoleUser(key: string, roleCodes: string[]): RoleUser {
  const norm = key.replace(/[^a-z0-9]+/gi, '-').toLowerCase();
  return {
    key,
    email: `rbac-smoke-${norm}@e2e.local`,
    displayName: `RBAC smoke ${key}`.slice(0, 50),
    password: RBAC_TEST_PASSWORD,
    roleCodes,
  };
}

/**
 * Idempotent provisioning via POST /api/admin/users (needs an admin-authenticated page).
 * Probes login first so re-runs on a warm stack don't fail on "already exists".
 * NOTE: a role-less baseline member is created by passing roleCodes:['tenant_member'] — the API
 * filters the system baseline role out (assignedRoles:[]), leaving a genuine role-less tenant member
 * that resolves the L1 implicit baseline. We therefore do NOT assert assignedRoles here.
 */
export async function ensureRoleUser(adminPage: Page, user: RoleUser): Promise<void> {
  const probe = await adminPage.request.post('/api/auth/login', {
    data: { email: user.email, password: user.password },
  });
  if (probe.ok()) {
    const b = await probe.json().catch(() => ({}) as Record<string, unknown>);
    if ((b as any)?.code === '0' && (b as any)?.data?.jwt) return; // already provisioned
  }
  const resp = await adminPage.request.post('/api/admin/users', {
    data: {
      email: user.email,
      displayName: user.displayName,
      initialPassword: user.password,
      roleCodes: user.roleCodes,
      sendInviteEmail: false,
    },
    timeout: 20_000,
  });
  const body = await resp.json().catch(() => ({}) as Record<string, unknown>);
  expect(
    resp.ok(),
    `provision ${user.key} (${user.email}) HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 600)}`,
  ).toBe(true);
}

/** Open a fresh, empty-storage browser context logged in as the given account. */
export async function openAsRole(
  browser: Browser,
  email: string,
  password: string,
): Promise<{ context: BrowserContext; page: Page }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, email, password);
  return { context, page };
}

export type MenuSnapshotItem = { code: string; path: string; permissionCode: string; name: string };
export type RoleSnapshot = {
  roleCodes: string[];
  permissionCodes: string[];
  menus: MenuSnapshotItem[];
  menuCodes: string[];
  menuPaths: string[];
};

function extractPermissionCodes(permissions: Record<string, unknown>): string[] {
  const permissionCodes = permissions.permissionCodes;
  if (Array.isArray(permissionCodes)) return permissionCodes.map(String).sort();
  const permissionObjects = permissions.permissions;
  if (Array.isArray(permissionObjects)) {
    return permissionObjects
      .map((p) => String((p as Record<string, unknown>).code ?? ''))
      .filter(Boolean)
      .sort();
  }
  return [];
}

function flattenMenuData(items: unknown[]): MenuSnapshotItem[] {
  const result: MenuSnapshotItem[] = [];
  const visit = (menuItems: unknown[]) => {
    for (const item of menuItems) {
      const menu = item as Record<string, unknown>;
      result.push({
        code: String(menu.code ?? ''),
        path: String(menu.path ?? ''),
        permissionCode: String(menu.permissionCode ?? menu.permission_code ?? ''),
        name: String(menu.name ?? ''),
      });
      const children = menu.children ?? menu.submenu;
      if (Array.isArray(children)) visit(children);
    }
  };
  visit(items);
  return result;
}

/** GET /api/auth/me (roles + permission codes) + /api/menu/user (permission-filtered menu tree). */
export async function fetchRoleSnapshot(page: Page): Promise<RoleSnapshot> {
  const meResp = await page.request.get('/api/auth/me', { timeout: 15_000 });
  const meBody = await meResp.json().catch(() => ({}) as Record<string, unknown>);
  expect(meResp.ok(), `/api/auth/me HTTP ${meResp.status()}: ${JSON.stringify(meBody).slice(0, 600)}`).toBe(true);

  const permissions = ((meBody as any).data?.permissions ?? {}) as Record<string, unknown>;
  const roles = Array.isArray(permissions.roles) ? permissions.roles : [];
  const roleCodes = roles.map((r) => String((r as Record<string, unknown>).code ?? '')).filter(Boolean).sort();
  const permissionCodes = extractPermissionCodes(permissions);

  const menuResp = await page.request.get('/api/menu/user', { timeout: 15_000 });
  const menuBody = await menuResp.json().catch(() => ({}) as Record<string, unknown>);
  expect(menuResp.ok(), `/api/menu/user HTTP ${menuResp.status()}: ${JSON.stringify(menuBody).slice(0, 600)}`).toBe(true);
  const menuRoot = Array.isArray((menuBody as any).data) ? (menuBody as any).data : [];
  const menus = flattenMenuData(menuRoot);

  return {
    roleCodes,
    permissionCodes,
    menus,
    menuCodes: menus.map((m) => m.code).filter(Boolean).sort(),
    menuPaths: menus.map((m) => m.path).filter(Boolean).sort(),
  };
}

/** Collect any 401/403 responses on /api/** across a page session (assert empty after baseline nav). */
export function attachForbiddenCollector(page: Page): Array<{ url: string; status: number }> {
  const forbidden: Array<{ url: string; status: number }> = [];
  page.on('response', (resp) => {
    const s = resp.status();
    if ((s === 401 || s === 403) && resp.url().includes('/api/')) {
      forbidden.push({ url: resp.url(), status: s });
    }
  });
  return forbidden;
}
