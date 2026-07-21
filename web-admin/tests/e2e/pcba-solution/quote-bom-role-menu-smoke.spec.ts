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
    requiredCodes: [
      MENU_CODE.customer,
      MENU_CODE.project,
      MENU_CODE.workbench,
      MENU_CODE.quote,
      MENU_CODE.priceLibrary,
    ],
    forbiddenCodes: [
      MENU_CODE.kingdeeSync,
      ...ORG_SYSTEM_ADMIN_CODES,
    ],
    deniedDirectPaths: ['/p/bom_material_master'],
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

type LeafMenu = { code: string; path: string; name: string };

/** Fetch /api/menu/user and keep only leaf nodes (group headers also carry paths). */
async function fetchLeafMenus(page: Page): Promise<LeafMenu[]> {
  const resp = await page.request.get('/api/menu/user', { timeout: 15_000 });
  const body = await resp.json().catch(() => ({} as Record<string, unknown>));
  expect(resp.ok(), `/api/menu/user HTTP ${resp.status()}`).toBe(true);
  const root = Array.isArray((body as any).data) ? (body as any).data : [];
  const leaves: LeafMenu[] = [];
  const seen = new Set<string>();
  const visit = (items: unknown[]) => {
    for (const item of items) {
      const menu = item as Record<string, unknown>;
      const children = (menu.children ?? menu.submenu) as unknown[] | undefined;
      if (Array.isArray(children) && children.length > 0) {
        visit(children);
        continue;
      }
      const path = String(menu.path ?? '');
      if (!path.startsWith('/') || seen.has(path)) continue;
      seen.add(path);
      leaves.push({ code: String(menu.code ?? ''), path, name: String(menu.name ?? '') });
    }
  };
  visit(root);
  return leaves;
}

async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('domcontentloaded');
  // networkidle is best-effort only (hence the catch): the shell keeps background
  // requests in flight, so on many pages it never settles and we simply burn the
  // whole timeout before continuing. Keep the budget small — a long timeout here
  // buys nothing and inflates every menu hop by that amount.
  await page.waitForLoadState('networkidle', { timeout: 3_000 }).catch(() => {});
}

/**
 * Click every given menu via the real sidebar and assert the page renders without
 * forbidden text. Returns human-readable problem strings (empty = all good).
 */
async function traverseMenus(
  page: Page,
  menus: LeafMenu[],
  setMenu: (m: string) => void,
): Promise<string[]> {
  const problems: string[] = [];
  // land once and let the SPA hydrate before driving sidebar clicks
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await settle(page);
  await ensureSidebarExpanded(page);
  for (const menu of menus) {
    const label = `${menu.code || '?'} ${menu.path}`;
    setMenu(label);
    const sidebar = page.getByTestId('sidebar');
    // This is the one hard assertion in the loop, and it is what fails when the
    // machine is loaded (observed: full-gate run rendered nothing within 15s while
    // the same spec passed 4/4 in isolation). Give it real headroom so a slow
    // render is not reported as a missing sidebar.
    await expect(sidebar).toBeVisible({ timeout: 30_000 });
    const link = sidebar.locator(`a[href="${menu.path}"]`).first();
    if ((await link.count()) === 0) {
      problems.push(`${label}: menu link not found in sidebar (API says visible)`);
      continue;
    }
    const targetPath = menu.path.split('?')[0];
    const reachedTarget = (timeout: number) =>
      page
        .waitForURL((url) => url.pathname.startsWith(targetPath), { timeout })
        .then(() => true)
        .catch(() => false);
    await link.scrollIntoViewIfNeeded().catch(() => {});
    await link.click();
    // The first click frequently does not navigate (the retry below is why this
    // spec passes at all). Since a retry follows, waiting the full budget on the
    // first attempt only wastes wall-clock — fail fast and re-click instead.
    let reached = await reachedTarget(5_000);
    if (!reached) {
      // one retry: SPA may still have been hydrating on the first click
      // eslint-disable-next-line no-console
      console.warn(`[menu-smoke] first click did not navigate, retrying: ${label}`);
      await link.click().catch(() => {});
      reached = await reachedTarget(15_000);
    }
    if (!reached) {
      problems.push(`${label}: navigation did not reach ${menu.path} (still at ${page.url()})`);
      continue;
    }
    await settle(page);
    // custom pages (/p/c/...) hydrate content after networkidle — poll instead of instant read
    let text = '';
    await expect
      .poll(
        async () => {
          const main = page.locator('main').first();
          const mainVisible = await main.isVisible().catch(() => false);
          const scope = mainVisible ? main : page.locator('body');
          text = (await scope.innerText().catch(() => '')).trim();
          return text.length;
        },
        { timeout: 15_000 },
      )
      .toBeGreaterThan(0)
      .catch(() => {});
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
        // 3. walk EVERY visible menu via the sidebar; assert page renders, no forbidden text
        // (soft: keep going so a single run reports the FULL gap inventory)
        const problems = await traverseMenus(page, await fetchLeafMenus(page), collector.setMenu);
        expect
          .soft(problems, `${contract.user.key} traversal problems:\n${problems.join('\n')}`)
          .toEqual([]);

        // 4. zero forbidden API responses across the whole session
        const hits = collector.hits.map((h) => `[${h.menu}] ${h.status} ${h.url}`);
        expect
          .soft(hits, `${contract.user.key} forbidden API hits:\n${hits.join('\n')}`)
          .toEqual([]);

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
      const byCode = new Map((await fetchLeafMenus(page)).map((m) => [m.code, m]));
      const missing = ADMIN_SWEEP_CODES.filter((code) => !byCode.has(code));
      expect(missing, `admin missing delivery menus: ${missing.join(', ')}`).toEqual([]);
      const targets = ADMIN_SWEEP_CODES.map((code) => byCode.get(code)!) as LeafMenu[];
      const problems = await traverseMenus(page, targets, collector.setMenu);
      expect(problems, `admin traversal problems:\n${problems.join('\n')}`).toEqual([]);
      const hits = collector.hits.map((h) => `[${h.menu}] ${h.status} ${h.url}`);
      expect(hits, `admin forbidden API hits:\n${hits.join('\n')}`).toEqual([]);
    });
  });
});
