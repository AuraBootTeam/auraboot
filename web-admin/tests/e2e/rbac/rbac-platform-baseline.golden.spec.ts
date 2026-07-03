/**
 * RBAC golden suite — Slice 4 (B layer): platform-baseline per-role browser golden.
 *
 * The browser counterpart to the backend A-layer
 * (platform/.../integration/security/rbac/RbacEnforcementMatrixIT.java). The A-layer proves per-code
 * ENFORCEMENT through the resolution API; this B-layer proves the DERIVED surface a real user sees —
 * the permission set + menu the frontend resolves per role, and that a baseline member is actually
 * admitted into the app (the L1 incident: role-less / baseline members used to get a blank / 403 app).
 *
 * Both layers read the SAME SOT matrix (rbac-access-matrix.json) via {@code loadPlatformBaseline},
 * so the golden and the enforcement IT can never drift.
 *
 * Roles (platform-baseline, operator/viewer retired in OSS #1167):
 *   - tenant_admin  — the bootstrap admin; wildcard; must resolve every matrix code and see admin-tier menus.
 *   - tenant_member — a role-LESS baseline member; resolves only the L1 read codes, sees NO admin-tier
 *                     menu, and is still admitted into the app (renders, not bounced to /login).
 */
import { test, expect } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import {
  loadPlatformBaseline,
  makeRoleUser,
  ensureRoleUser,
  openAsRole,
  fetchRoleSnapshot,
  type RoleSnapshot,
} from './rbac-helpers';

const baseline = loadPlatformBaseline();
const MEMBER_ALLOW = baseline.tenant_member.allow;
const MEMBER_DENY = baseline.tenant_member.deny;

// Menu codes that are admin-tier: a baseline member must never see these in its resolved menu.
const ADMIN_TIER_MENU_CODES = [
  'org_management',
  'system_management',
  'permission_management',
  'permission_roles',
  'member_management',
];

const memberUser = makeRoleUser('tenant_member', ['tenant_member']);

test.describe('RBAC platform-baseline per-role golden (Slice 4)', () => {
  // Real login + SPA render across two role contexts needs headroom beyond the 15s default.
  test.describe.configure({ timeout: 90_000 });

  let adminSnap: RoleSnapshot;

  test.beforeAll(async ({ browser }) => {
    // Provision the baseline member (idempotent) and capture the admin's resolved surface once.
    const { context, page } = await openAsRole(
      browser,
      DEFAULT_TEST_ACCOUNT.email,
      DEFAULT_TEST_ACCOUNT.password,
    );
    try {
      await ensureRoleUser(page, memberUser);
      adminSnap = await fetchRoleSnapshot(page);
    } finally {
      await context.close();
    }
  });

  test('tenant_admin resolves the full matrix surface and sees admin-tier menus', async ({ browser }) => {
    expect(adminSnap.roleCodes, 'admin role').toContain('tenant_admin');

    // Wildcard admin must resolve EVERY matrix code — the member's allow codes AND the codes a member
    // is denied (assignment / dashboard). This mirrors RbacEnforcementMatrixIT's tenant_admin cell.
    for (const code of [...MEMBER_ALLOW, ...MEMBER_DENY]) {
      expect(adminSnap.permissionCodes, `admin must resolve '${code}'`).toContain(code);
    }

    // Admin sees a rich menu that INCLUDES admin-tier management surfaces.
    expect(adminSnap.menuCodes.length, 'admin menu must be non-empty').toBeGreaterThan(0);
    const adminTierVisible = ADMIN_TIER_MENU_CODES.filter((c) => adminSnap.menuCodes.includes(c));
    expect(adminTierVisible.length, `admin must see admin-tier menus, saw ${JSON.stringify(adminTierVisible)}`).toBeGreaterThan(0);

    // Real browser: admin lands in the app (not bounced to /login) and the app shell renders.
    const { context, page } = await openAsRole(
      browser,
      DEFAULT_TEST_ACCOUNT.email,
      DEFAULT_TEST_ACCOUNT.password,
    );
    try {
      await page.goto('/', { waitUntil: 'domcontentloaded' });
      // App shell (header banner) renders — proves admitted + not a blank / 403 error page.
      await expect(page.getByRole('banner')).toBeVisible({ timeout: 30_000 });
      expect(page.url(), 'admin must not be bounced to /login').not.toMatch(/\/login/);
    } finally {
      await context.close();
    }
  });

  test('tenant_member resolves only the L1 baseline, sees no admin-tier menu, and is admitted to the app', async ({ browser }) => {
    const { context, page } = await openAsRole(browser, memberUser.email, memberUser.password);
    try {
      const snap = await fetchRoleSnapshot(page);

      // Role identity: a genuine tenant_member baseline (not admin).
      expect(snap.roleCodes, 'member role').toEqual(['tenant_member']);

      // Resolves every baseline allow code and NONE of the deny codes (matrix-driven).
      for (const code of MEMBER_ALLOW) {
        expect(snap.permissionCodes, `member must resolve allow '${code}'`).toContain(code);
      }
      for (const code of MEMBER_DENY) {
        expect(snap.permissionCodes, `member must NOT resolve deny '${code}'`).not.toContain(code);
      }

      // Menu derivation: member's menu is a strict subset of admin's, with NO admin-tier node.
      expect(snap.menuCodes.length, 'member menu must be smaller than admin menu').toBeLessThan(
        adminSnap.menuCodes.length,
      );
      for (const code of snap.menuCodes) {
        expect(adminSnap.menuCodes, `member menu '${code}' must exist within admin's menu`).toContain(code);
      }
      for (const adminTier of ADMIN_TIER_MENU_CODES) {
        expect(snap.menuCodes, `member must NOT see admin-tier menu '${adminTier}'`).not.toContain(adminTier);
      }

      // Real browser (L1 incident guard): the baseline member is ADMITTED to the app — logged in,
      // not bounced back to /login, and the app shell (header banner) renders rather than a blanket
      // 403 / crash. A 403 error page would not carry the full app chrome.
      await page.goto('/', { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('banner')).toBeVisible({ timeout: 30_000 });
      expect(page.url(), 'member must not be bounced to /login').not.toMatch(/\/login/);
    } finally {
      await context.close();
    }
  });
});
