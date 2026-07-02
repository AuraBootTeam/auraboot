import type { Browser, BrowserContext, Response } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import {
  fetchRoleSnapshot,
  openQuoteRolePage,
  QUOTE_ROLE_TEST_PASSWORD,
  type QuoteRoleUser,
  type RoleSnapshot,
} from './quote-e2e-helpers';

/**
 * Per-role menu-traversal smoke (DDR-2026-06-29 §8: "以每个普通业务角色身份真机登录 →
 * 打开每个菜单 → 零 forbidden").
 *
 * Motivation: the 2026-06-29/30 B-deployment incident — 394 forbidden hits across 11
 * permission codes discovered by real employees, because every deep golden ran as admin
 * ("管理员能用 ≠ 系统能用"). This spec logs in as FIXED smoke accounts (smoke_eng /
 * smoke_sales / smoke_proc), walks every sidebar menu the role can see, and asserts:
 *   1. menu contract: required menus present, admin-tier menus absent (by menu code);
 *   2. every visible menu opens without any 401/403 API response and without
 *      forbidden/unavailable UI text;
 *   3. direct-URL negatives for admin-tier surfaces.
 * Plus an admin sweep over the delivery-whitelist menus (MENU-P0-01/09 逐项点开).
 *
 * This is an access/menu smoke: list pages may be legitimately empty on a fresh stack,
 * so it asserts the page shell renders (table or empty state) rather than row counts —
 * data-depth assertions live in the golden specs.
 *
 * RUN (host-first local stack with quote/bom plugins + business roles provisioned):
 *   PLAYWRIGHT_BASE_URL=http://127.0.0.1:<web> BACKEND_URL=http://127.0.0.1:<be> \
 *   BE_PORT=<be> BFF_PORT=<bff> PW_SKIP_WEBSERVER=1 \
 *     node_modules/.bin/playwright test tests/e2e/pcba-solution/quote-bom-role-menu-smoke.spec.ts \
 *     --project=chromium --no-deps
 * Requires roles from aura-quote/scripts/quote-bom/business-roles.json to be reconciled
 * (qo_sales / qo_procurement / bom_engineering); admin creds via ADMIN_EMAIL/ADMIN_PASSWORD.
 */

const ADMIN_EMAIL = process.env.ADMIN_EMAIL || 'admin@auraboot.com';
const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || 'Test2026x';

// Fixed smoke accounts (stable fixtures, safe to re-run; NOT real employees).
const SMOKE_USERS: QuoteRoleUser[] = [
  {
    key: 'smoke_eng',
    email: 'smoke-eng@e2e.local',
    displayName: 'Smoke Engineering',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['bom_engineering'],
  },
  {
    key: 'smoke_sales',
    email: 'smoke-sales@e2e.local',
    displayName: 'Smoke Sales',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_sales'],
  },
  {
    key: 'smoke_proc',
    email: 'smoke-proc@e2e.local',
    displayName: 'Smoke Procurement',
    password: QUOTE_ROLE_TEST_PASSWORD,
    roleCodes: ['qo_procurement'],
  },
];

// Menu codes (stable identifiers from plugin menus.json + focus-menu whitelist).
const MENU_CODE = {
  customer: 'crm_accounts',
  project: 'bom_projects',
  workbench: 'bom_v2_workbench',
  kingdeeSync: 'bom_sync_kingdee_material_library',
  sourceFormatProfiles: 'bom_source_format_profiles',
  fieldCompositionRules: 'bom_field_composition_rules',
  quote: 'qo_quote_menu',
  priceLibrary: 'qo_purchase_price_library_menu',
};

const ORG_SYSTEM_ADMIN_CODES = [
  'org_management',
  'org_departments',
  'org_positions',
  'org_employees',
  'org_teams',
  'member_management',
  'permission_roles',
  'system_management',
  'enterprise_info',
  'account_security_policy',
  'llm_provider_settings',
  'system_preferences',
];

// Delivery whitelist leaves the admin sweep must reach (quote-bom-focus-menu.sh ALLOW_CODES).
const ADMIN_SWEEP_CODES = [
  MENU_CODE.customer,
  MENU_CODE.project,
  MENU_CODE.workbench,
  MENU_CODE.kingdeeSync,
  MENU_CODE.sourceFormatProfiles,
  MENU_CODE.fieldCompositionRules,
  MENU_CODE.quote,
  MENU_CODE.priceLibrary,
  'org_departments',
  'org_positions',
  'org_employees',
  'org_teams',
  'member_management',
  'permission_roles',
  'enterprise_info',
  'account_security_policy',
  'llm_provider_settings',
  'system_preferences',
];

type RoleContract = {
  user: QuoteRoleUser;
  roleCode: string;
  requiredCodes: string[];
  forbiddenCodes: string[];
  deniedDirectPaths: string[];
};

const CONTRACTS: RoleContract[] = [
  {
    user: SMOKE_USERS[0],
    roleCode: 'bom_engineering',
    requiredCodes: [MENU_CODE.customer, MENU_CODE.project, MENU_CODE.workbench],
    forbiddenCodes: [
      MENU_CODE.kingdeeSync,
      MENU_CODE.quote,
      MENU_CODE.priceLibrary,
      ...ORG_SYSTEM_ADMIN_CODES,
    ],
    deniedDirectPaths: ['/p/qo_quote_common', '/p/bom_material_master'],
  },
  {
    user: SMOKE_USERS[1],
    roleCode: 'qo_sales',
    requiredCodes: [
      MENU_CODE.customer,
      MENU_CODE.project,
      MENU_CODE.workbench,
      MENU_CODE.quote,
      MENU_CODE.priceLibrary,
    ],
    forbiddenCodes: [MENU_CODE.kingdeeSync, ...ORG_SYSTEM_ADMIN_CODES],
    deniedDirectPaths: ['/p/bom_material_master'],
  },
  {
    user: SMOKE_USERS[2],
    roleCode: 'qo_procurement',
    requiredCodes: [
      MENU_CODE.customer,
      MENU_CODE.project,
      MENU_CODE.workbench,
      MENU_CODE.quote,
      MENU_CODE.priceLibrary,
    ],
    forbiddenCodes: [MENU_CODE.kingdeeSync, ...ORG_SYSTEM_ADMIN_CODES],
    deniedDirectPaths: ['/p/bom_material_master'],
  },
];

const FORBIDDEN_TEXT =
  /Access forbidden|Access denied|Page Unavailable|Page not found|Menu configuration not found|权限不足|无权限|未授权|加载失败/i;

type ForbiddenHit = { menu: string; url: string; status: number };

function attachForbiddenCollector(page: Page): { hits: ForbiddenHit[]; setMenu: (m: string) => void } {
  const hits: ForbiddenHit[] = [];
  let currentMenu = '(login/home)';
  page.on('response', (resp: Response) => {
    const status = resp.status();
    if ((status === 401 || status === 403) && resp.url().includes('/api/')) {
      hits.push({ menu: currentMenu, url: resp.url(), status });
    }
  });
  return { hits, setMenu: (m: string) => { currentMenu = m; } };
}

function leafMenus(snapshot: RoleSnapshot): Array<{ code: string; path: string; name: string }> {
  const seen = new Set<string>();
  return snapshot.menus
    .filter((m) => m.path && m.path.startsWith('/'))
    .filter((m) => {
      if (seen.has(m.path)) return false;
      seen.add(m.path);
      return true;
    })
    .map((m) => ({ code: m.code, path: m.path, name: m.name }));
}

async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('domcontentloaded');
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
}

/**
 * Click every given menu via the real sidebar and assert the page renders without
 * forbidden text. Returns human-readable problem strings (empty = all good).
 */
async function traverseMenus(
  page: Page,
  menus: Array<{ code: string; path: string; name: string }>,
  setMenu: (m: string) => void,
): Promise<string[]> {
  const problems: string[] = [];
  for (const menu of menus) {
    const label = `${menu.code || '?'} ${menu.path}`;
    setMenu(label);
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    const sidebar = page.getByTestId('sidebar');
    await expect(sidebar).toBeVisible({ timeout: 15_000 });
    const link = sidebar.locator(`a[href="${menu.path}"]`).first();
    if ((await link.count()) === 0) {
      problems.push(`${label}: menu link not found in sidebar (API says visible)`);
      continue;
    }
    await link.click();
    const reached = await page
      .waitForURL((url) => url.pathname.startsWith(menu.path.split('?')[0]), { timeout: 20_000 })
      .then(() => true)
      .catch(() => false);
    if (!reached) {
      problems.push(`${label}: navigation did not reach ${menu.path} (still at ${page.url()})`);
      continue;
    }
    await settle(page);
    const main = page.locator('main').first();
    const mainVisible = await main.isVisible().catch(() => false);
    const scope = mainVisible ? main : page.locator('body');
    const text = (await scope.innerText().catch(() => '')).trim();
    if (text.length === 0) {
      problems.push(`${label}: content area rendered empty`);
      continue;
    }
    const match = text.match(FORBIDDEN_TEXT);
    if (match) {
      problems.push(`${label}: content shows "${match[0]}"`);
    }
  }
  return problems;
}

async function withAdminPage<T>(browser: Browser, run: (page: Page) => Promise<T>): Promise<T> {
  const context: BrowserContext = await browser.newContext({
    storageState: { cookies: [], origins: [] },
  });
  const page = await context.newPage();
  try {
    await loginViaUI(page, ADMIN_EMAIL, ADMIN_PASSWORD);
    return await run(page);
  } finally {
    await context.close();
  }
}

/** Idempotent: create the fixed smoke account, tolerating "already exists". */
async function ensureSmokeUser(adminPage: Page, user: QuoteRoleUser): Promise<void> {
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
  if (resp.ok()) {
    const body = await resp.json().catch(() => ({} as Record<string, unknown>));
    const assigned = Array.isArray((body as any).data?.assignedRoles)
      ? (body as any).data.assignedRoles.map(String)
      : [];
    for (const roleCode of user.roleCodes) {
      expect(assigned, `${user.key} should be assigned ${roleCode}`).toContain(roleCode);
    }
    return;
  }
  const text = await resp.text().catch(() => '');
  const alreadyExists = /已存在|exists|duplicate|重复|conflict/i.test(text) || resp.status() === 409;
  expect(
    alreadyExists,
    `ensure smoke user ${user.email} failed: HTTP ${resp.status()} ${text.slice(0, 400)}`,
  ).toBe(true);
}

async function expectUnavailableByDirectUrl(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('body')).toContainText(
    /Page Unavailable|Menu configuration not found|Access forbidden|Access denied|无权限|未授权|权限不足/i,
    { timeout: 10_000 },
  );
}

test.describe('Quote/BOM per-role menu smoke @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(300_000);

  let adminSnapshot: RoleSnapshot;

  test.beforeAll(async ({ browser }) => {
    await withAdminPage(browser, async (adminPage) => {
      for (const user of SMOKE_USERS) {
        await ensureSmokeUser(adminPage, user);
      }
      adminSnapshot = await fetchRoleSnapshot(adminPage);
    });
  });

  for (const contract of CONTRACTS) {
    test(`${contract.user.key} (${contract.roleCode}): menu contract + full traversal zero-forbidden`, async ({ browser }) => {
      const { context, page } = await openQuoteRolePage(browser, contract.user);
      const collector = attachForbiddenCollector(page);
      try {
        // 1. role really assigned
        const snapshot = await fetchRoleSnapshot(page);
        expect(snapshot.roleCodes, `${contract.user.key} has role`).toContain(contract.roleCode);

        // 2. menu contract by code: required present, admin-tier absent
        for (const code of contract.requiredCodes) {
          expect(snapshot.menuCodes, `${contract.user.key} must see menu ${code}`).toContain(code);
        }
        for (const code of contract.forbiddenCodes) {
          expect(snapshot.menuCodes, `${contract.user.key} must NOT see menu ${code}`).not.toContain(code);
        }
        // parity: rule-view menus (bom.rule.read) — if delivered (visible for admin), the
        // employee must see them too (menu↔capability coherence).
        for (const code of [MENU_CODE.sourceFormatProfiles, MENU_CODE.fieldCompositionRules]) {
          if (adminSnapshot.menuCodes.includes(code)) {
            expect(snapshot.menuCodes, `${contract.user.key} should see rule-view menu ${code}`).toContain(code);
          }
        }

        // 3. walk EVERY visible menu via the sidebar; assert page renders, no forbidden text
        const problems = await traverseMenus(page, leafMenus(snapshot), collector.setMenu);
        expect(problems, `${contract.user.key} traversal problems:\n${problems.join('\n')}`).toEqual([]);

        // 4. zero forbidden API responses across the whole session
        const hits = collector.hits.map((h) => `[${h.menu}] ${h.status} ${h.url}`);
        expect(hits, `${contract.user.key} forbidden API hits:\n${hits.join('\n')}`).toEqual([]);

        // 5. admin-tier surfaces stay denied by direct URL
        for (const path of contract.deniedDirectPaths) {
          await expectUnavailableByDirectUrl(page, path);
        }
      } finally {
        await context.close();
      }
    });
  }

  test('admin: delivery whitelist menus all reachable (MENU-P0-01/09)', async ({ browser }) => {
    await withAdminPage(browser, async (page) => {
      const collector = attachForbiddenCollector(page);
      const snapshot = await fetchRoleSnapshot(page);
      const byCode = new Map(leafMenus(snapshot).map((m) => [m.code, m]));
      const missing = ADMIN_SWEEP_CODES.filter((code) => !byCode.has(code));
      expect(missing, `admin missing delivery menus: ${missing.join(', ')}`).toEqual([]);
      const targets = ADMIN_SWEEP_CODES.map((code) => byCode.get(code)!) as Array<{
        code: string;
        path: string;
        name: string;
      }>;
      const problems = await traverseMenus(page, targets, collector.setMenu);
      expect(problems, `admin traversal problems:\n${problems.join('\n')}`).toEqual([]);
      const hits = collector.hits.map((h) => `[${h.menu}] ${h.status} ${h.url}`);
      expect(hits, `admin forbidden API hits:\n${hits.join('\n')}`).toEqual([]);
    });
  });
});
