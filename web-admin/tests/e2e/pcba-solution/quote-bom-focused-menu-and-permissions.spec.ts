import type { Browser } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';
import {
  ensureQuoteRoleUser,
  expectCommandDenied,
  expectCommandNotDenied,
  fetchRoleSnapshot,
  makeQuoteRoleUser,
  openQuoteRolePage,
  type QuoteRoleUser,
  type RoleSnapshot,
} from './quote-e2e-helpers';

/**
 * Focused-runtime menu + permission matrix against the CURRENT owner role model
 * (business-roles.json, owner-confirmed 2026-06-28, applied to source in #199):
 *
 *   | role            | BOM 转化 (view rules/library) | 报价全套 | 物料/规则写 + 金蝶同步 |
 *   | qo_sales        | ✓                             | ✓        | ✗ (admin-tier)        |
 *   | qo_procurement  | ✓ (= sales)                   | ✓        | ✗                     |
 *   | bom_engineering | ✓ (+ intake upload)           | ✓        | ✗                     |
 *   | no business role| ✗                             | ✗        | ✗                     |
 *
 * The retired 5-role model (qo_quoter / bom_operator / bom_admin) no longer exists on
 * delivery stacks — creating users against those codes silently yields assignedRoles=[].
 */
const QUOTE_MENU_PATH = '/p/qo_quote_common';
const PRICE_LIBRARY_MENU_PATH = '/p/qo_offline_material_price_common';
const BOM_PROJECTS_PATH = '/p/req_requirement_set_pcba_bom';
const BOM_WORKBENCH_PATH = '/p/bom_conversion_task_pcba_workbench';
const BOM_REVIEW_QUEUE_PATH = '/p/bom_review_queue';
const BOM_MATERIAL_LIBRARY_PATH = '/p/bom_material_master';
const BOM_FORMAT_PROFILE_PATH = '/p/bom_source_format_profile';
const ORG_TEAMS_PATH = '/organization/teams';
const MEMBER_MANAGEMENT_PATH = '/p/tenant_member';
const PERMISSION_ROLES_PATH = '/enterprise/permissions';
const BOM_MATERIAL_SYNC_COMMAND = 'bom:sync_material_incremental_now';
const BOM_MATERIAL_SYNC_DRY_RUN = { dryRun: true };

const PLATFORM_BASE_CAPABILITY = 'sys.cap.member_base';
const COMMAND_EXECUTE_PERMISSION = 'meta.command.execute';

// full quote-business permission set (all granted to sales/procurement via qo.cap.*)
const QUOTE_PERMISSIONS = [
  'qo.quote.read',
  'qo.quote.create',
  'qo.quote.manage',
  'qo.rfq.upload',
  'qo.bom.import',
  'qo.price.manage',
  'qo.process_fee.manage',
  'qo.document.generate',
];

// BOM read/execute surface every business role holds (bom.cap.project/convert/*_view)
const BOM_BUSINESS_PERMISSIONS = [
  'bom.project.read',
  'bom.project.manage',
  'bom.convert.execute',
  'bom.library.read',
  'bom.rule.read',
];

// admin-tier writes no business role may hold
const BOM_ADMIN_PERMISSIONS = ['bom.library.manage', 'bom.rule.manage'];

const BUSINESS_MENU_PATHS = [
  BOM_PROJECTS_PATH,
  BOM_WORKBENCH_PATH,
];
const QUOTE_MENU_PATHS = [QUOTE_MENU_PATH, PRICE_LIBRARY_MENU_PATH];

type RoleKey = 'qoSales' | 'qoProcurement' | 'bomEngineering' | 'noBusinessRole';

function expectIncludes(actual: string[], expected: string[], label: string): void {
  for (const value of expected) {
    expect(actual, `${label} should include ${value}`).toContain(value);
  }
}

function expectExcludes(actual: string[], expected: string[], label: string): void {
  for (const value of expected) {
    expect(actual, `${label} should not include ${value}`).not.toContain(value);
  }
}

function expectNoBomMenus(snapshot: RoleSnapshot, label: string): void {
  expect(
    snapshot.menuPaths.filter((path) => path === '/bom' || path.startsWith('/p/bom_')),
    `${label} should not expose BOM menus`,
  ).toEqual([]);
}

function expectNoQuoteMenus(snapshot: RoleSnapshot, label: string): void {
  expect(
    snapshot.menuPaths.filter((path) => path === '/quoteops' || path.startsWith('/p/qo_')),
    `${label} should not expose QuoteOps menus`,
  ).toEqual([]);
}

async function withRolePage<T>(
  browser: Browser,
  user: QuoteRoleUser,
  run: (page: Page) => Promise<T>,
): Promise<T> {
  const { context, page } = await openQuoteRolePage(browser, user);
  try {
    return await run(page);
  } finally {
    await context.close();
  }
}

async function assertSidebarLinks(
  page: Page,
  visiblePaths: string[],
  hiddenPaths: string[],
): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const sidebar = page.getByTestId('sidebar');
  await expect(sidebar).toBeVisible({ timeout: 15_000 });

  for (const path of visiblePaths) {
    await expect(sidebar.locator(`a[href="${path}"]`), `${path} should be visible`).toBeVisible({
      timeout: 10_000,
    });
  }
  for (const path of hiddenPaths) {
    await expect(sidebar.locator(`a[href="${path}"]`), `${path} should be hidden`).toHaveCount(0);
  }
}

async function expectUnavailableByDirectUrl(page: Page, path: string): Promise<void> {
  await page.goto(path, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('body')).toContainText(
    /Page Unavailable|Menu configuration not found|Access forbidden|Access denied|无权限|未授权/i,
    { timeout: 10_000 },
  );
}

function bomProjectPayload(seed: string): Record<string, unknown> {
  return {
    bom_project_name: `E2E BOM Project ${seed}`,
    bom_project_customer_id: `ACC-${seed}`,
    bom_project_quality_level: 'industrial',
    bom_pcba_code: `PCBA-${seed}`,
    bom_project_remark: `Quote/BOM role permission probe ${seed}`,
  };
}

async function createCustomRole(page: Page, roleCode: string): Promise<string> {
  const resp = await page.request.post('/api/roles', {
    data: {
      code: roleCode,
      name: `E2E No Business ${roleCode.slice(-12)}`,
      description: 'E2E negative role with platform base only, no Quote/BOM business capabilities',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
    },
    timeout: 15_000,
  });
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `create custom no-business role ${roleCode} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
  const rolePid = String((body as any).data?.pid ?? '');
  expect(rolePid, `custom no-business role ${roleCode} should expose pid`).toBeTruthy();
  return rolePid;
}

async function grantPlatformBaseOnly(page: Page, rolePid: string): Promise<void> {
  const resp = await page.request.put(
    `/api/permission/capabilities?rolePid=${encodeURIComponent(rolePid)}`,
    {
      data: [PLATFORM_BASE_CAPABILITY],
      timeout: 15_000,
    },
  );
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `grant platform base capability to ${rolePid} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

test.describe('QuoteOps + BOM focused menu and permission matrix @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  const uid = uniqueId('qobom_role').replace(/_/g, '-');
  const noBusinessRoleCode = `e2e_no_business_${uid.replace(/[^a-z0-9]+/gi, '_')}`.slice(0, 60);
  const users = {} as Record<RoleKey, QuoteRoleUser>;

  test.beforeAll(async ({ browser }) => {
    users.qoSales = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    users.qoProcurement = makeQuoteRoleUser('qo_procurement', uid, ['qo_procurement']);
    users.bomEngineering = makeQuoteRoleUser('bom_engineering', uid, ['bom_engineering']);
    users.noBusinessRole = makeQuoteRoleUser('no_business_role', uid, [noBusinessRoleCode]);

    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      const noBusinessRolePid = await createCustomRole(page, noBusinessRoleCode);
      await grantPlatformBaseOnly(page, noBusinessRolePid);
      for (const user of Object.values(users)) {
        await ensureQuoteRoleUser(page, user);
      }
    } finally {
      await context.close();
    }
  });

  test('admin sidebar exposes the delivery org-management group in the focused runtime', async ({
    page,
  }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);

    const sidebar = page.getByTestId('sidebar');
    await expect(sidebar).toBeVisible({ timeout: 15_000 });
    await expect(sidebar.getByText('组织管理', { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(sidebar.locator(`a[href="${ORG_TEAMS_PATH}"]`)).toBeVisible({ timeout: 10_000 });
    await expect(sidebar.locator(`a[href="${MEMBER_MANAGEMENT_PATH}"]`)).toBeVisible({
      timeout: 10_000,
    });
    await expect(sidebar.locator(`a[href="${PERMISSION_ROLES_PATH}"]`).first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(sidebar.getByText('账号', { exact: true }).first()).toBeVisible();
    await expect(sidebar.getByText('角色', { exact: true }).first()).toBeVisible();
  });

  test('role snapshots expose only current QuoteOps and BOM permissions', async ({ browser }) => {
    for (const [label, user] of [
      ['qo_sales', users.qoSales],
      ['qo_procurement', users.qoProcurement],
    ] as const) {
      await withRolePage(browser, user, async (page) => {
        const snapshot = await fetchRoleSnapshot(page);
        expectIncludes(snapshot.roleCodes, [user.roleCodes[0]], `${label} roles`);
        expectIncludes(
          snapshot.permissionCodes,
          [COMMAND_EXECUTE_PERMISSION, ...QUOTE_PERMISSIONS, ...BOM_BUSINESS_PERMISSIONS],
          `${label} permissions`,
        );
        expectExcludes(snapshot.permissionCodes, BOM_ADMIN_PERMISSIONS, `${label} permissions`);
        expectIncludes(
          snapshot.menuPaths,
          [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS],
          `${label} menu paths`,
        );
        expectExcludes(
          snapshot.menuPaths,
          [BOM_REVIEW_QUEUE_PATH, BOM_MATERIAL_LIBRARY_PATH],
          `${label} menu paths`,
        );
      });
    }

    await withRolePage(browser, users.bomEngineering, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['bom_engineering'], 'bom_engineering roles');
      expectIncludes(
        snapshot.permissionCodes,
        [COMMAND_EXECUTE_PERMISSION, ...QUOTE_PERMISSIONS, ...BOM_BUSINESS_PERMISSIONS],
        'bom_engineering permissions',
      );
      expectExcludes(snapshot.permissionCodes, BOM_ADMIN_PERMISSIONS, 'bom_engineering permissions');
      expectIncludes(
        snapshot.menuPaths,
        [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS],
        'bom_engineering menu paths',
      );
      expectExcludes(
        snapshot.menuPaths,
        [
          BOM_REVIEW_QUEUE_PATH,
          BOM_MATERIAL_LIBRARY_PATH,
          BOM_FORMAT_PROFILE_PATH,
        ],
        'bom_engineering menu paths',
      );
    });

    await withRolePage(browser, users.noBusinessRole, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, [noBusinessRoleCode], 'no_business_role roles');
      expectExcludes(
        snapshot.permissionCodes,
        [
          COMMAND_EXECUTE_PERMISSION,
          ...QUOTE_PERMISSIONS,
          ...BOM_BUSINESS_PERMISSIONS,
          ...BOM_ADMIN_PERMISSIONS,
        ],
        'no_business_role permissions',
      );
      expectNoQuoteMenus(snapshot, 'no_business_role');
      expectNoBomMenus(snapshot, 'no_business_role');
    });
  });

  test('sidebar shows only the pages permitted for each role', async ({ browser }) => {
    for (const user of [users.qoSales, users.qoProcurement]) {
      await withRolePage(browser, user, async (page) => {
        await assertSidebarLinks(
          page,
          [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS],
          [
            BOM_REVIEW_QUEUE_PATH,
            BOM_MATERIAL_LIBRARY_PATH,
            BOM_FORMAT_PROFILE_PATH,
          ],
        );
        await expectUnavailableByDirectUrl(page, BOM_MATERIAL_LIBRARY_PATH);
      });
    }

    await withRolePage(browser, users.bomEngineering, async (page) => {
      await assertSidebarLinks(page, [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS], [
        BOM_REVIEW_QUEUE_PATH,
        BOM_MATERIAL_LIBRARY_PATH,
        BOM_FORMAT_PROFILE_PATH,
      ]);
      await expectUnavailableByDirectUrl(page, BOM_MATERIAL_LIBRARY_PATH);
    });

    await withRolePage(browser, users.noBusinessRole, async (page) => {
      await assertSidebarLinks(
        page,
        [],
        [...QUOTE_MENU_PATHS, BOM_PROJECTS_PATH, BOM_WORKBENCH_PATH],
      );
      await expectUnavailableByDirectUrl(page, QUOTE_MENU_PATH);
      await expectUnavailableByDirectUrl(page, BOM_WORKBENCH_PATH);
    });
  });

  test('backend command permissions reject cross-role operations without 500', async ({
    browser,
  }) => {
    for (const [label, user] of [
      ['sales', users.qoSales],
      ['procurement', users.qoProcurement],
    ] as const) {
      await withRolePage(browser, user, async (page) => {
        await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
        await expectCommandNotDenied(page, 'qo_quote_common:batch_source_prices', {}, 'quote-id', 'update');
        await expectCommandNotDenied(page, 'qo_quote_common:compute_process_fee', {}, 'quote-id', 'update');
        await expectCommandNotDenied(page, 'qo_quote_common:generate_document', {}, 'quote-id', 'update');
        await expectCommandNotDenied(page, 'qo_offline_material_price_common:import_excel', {});
        await expectCommandNotDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-${label}`));
        await expectCommandDenied(page, BOM_MATERIAL_SYNC_COMMAND, BOM_MATERIAL_SYNC_DRY_RUN);
        await expectCommandDenied(page, 'bom:create_material', { bom_mm_material_code: `X-${uid}` });
      });
    }

    await withRolePage(browser, users.bomEngineering, async (page) => {
      await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandNotDenied(page, 'qo_quote_common:batch_source_prices', {}, 'quote-id', 'update');
      await expectCommandNotDenied(page, 'qo_quote_common:compute_process_fee', {}, 'quote-id', 'update');
      await expectCommandNotDenied(page, 'qo_quote_common:generate_document', {}, 'quote-id', 'update');
      await expectCommandNotDenied(page, 'qo_offline_material_price_common:import_excel', {});
      await expectCommandNotDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-eng`));
      await expectCommandNotDenied(page, 'qo_quote_common:import_corrected_bom', {}, 'quote-id', 'update');
      await expectCommandDenied(page, BOM_MATERIAL_SYNC_COMMAND, BOM_MATERIAL_SYNC_DRY_RUN);
      await expectCommandDenied(page, 'bom:create_material', { bom_mm_material_code: `X-${uid}-eng` });
    });

    await withRolePage(browser, users.noBusinessRole, async (page) => {
      await expectCommandDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-norole`));
    });
  });
});
