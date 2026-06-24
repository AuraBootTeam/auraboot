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

const QUOTE_MENU_PATH = '/p/qo_quote_common';
const PRICE_LIBRARY_MENU_PATH = '/p/qo_offline_material_price_common';
const BOM_PROJECTS_PATH = '/p/req_requirement_set_pcba_bom';
const BOM_WORKBENCH_PATH = '/p/bom_conversion_task_pcba_workbench';
const BOM_REVIEW_QUEUE_PATH = '/p/bom_review_queue';
const BOM_MATERIAL_LIBRARY_PATH = '/p/bom_material_master';
const BOM_FORMAT_PROFILE_PATH = '/p/bom_source_format_profile';
const BOM_FIELD_COMPOSITION_RULE_PATH = '/p/bom_field_composition_rule';
const RBAC_TEAM_PATH = '/organization/teams';
const RBAC_USER_PATH = '/p/tenant_member';
const RBAC_PERMISSION_PATH = '/enterprise/permissions';
const BOM_MATERIAL_SYNC_COMMAND = 'bom:sync_material_incremental_now';
const BOM_MATERIAL_SYNC_DRY_RUN = { dryRun: true };

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

const BOM_PERMISSIONS = [
  'bom.project.read',
  'bom.project.manage',
  'bom.convert.execute',
  'bom.library.read',
  'bom.library.manage',
  'bom.rule.read',
  'bom.rule.manage',
];

const COMMAND_EXECUTE_PERMISSION = 'meta.command.execute';

type RoleKey = 'qoSales' | 'qoQuoter' | 'bomOperator' | 'bomAdmin' | 'platformViewer';

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

test.describe('QuoteOps + BOM focused menu and permission matrix @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(120_000);

  const uid = uniqueId('qobom_role').replace(/_/g, '-');
  const users = {} as Record<RoleKey, QuoteRoleUser>;

  test.beforeAll(async ({ browser }) => {
    users.qoSales = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    users.qoQuoter = makeQuoteRoleUser('qo_quoter', uid, ['qo_quoter']);
    users.bomOperator = makeQuoteRoleUser('bom_operator', uid, ['bom_operator']);
    users.bomAdmin = makeQuoteRoleUser('bom_admin', uid, ['bom_admin']);
    users.platformViewer = makeQuoteRoleUser('platform_viewer', uid, ['viewer']);

    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      for (const user of Object.values(users)) {
        await ensureQuoteRoleUser(page, user);
      }
    } finally {
      await context.close();
    }
  });

  test('admin sidebar restores minimal permission management menu in focused runtime', async ({
    page,
  }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);

    const sidebar = page.getByTestId('sidebar');
    await expect(sidebar).toBeVisible({ timeout: 15_000 });
    await expect(sidebar.getByText('权限管理', { exact: true }).first()).toBeVisible({
      timeout: 10_000,
    });
    await expect(sidebar.locator(`a[href="${RBAC_TEAM_PATH}"]`)).toBeVisible({
      timeout: 10_000,
    });
    await expect(sidebar.locator(`a[href="${RBAC_USER_PATH}"]`)).toBeVisible({
      timeout: 10_000,
    });
    await expect(sidebar.getByText('用户', { exact: true }).first()).toBeVisible();
    await expect(sidebar.getByText('角色', { exact: true }).first()).toBeVisible();
    await expect(sidebar.getByText('权限/授权关系', { exact: true }).first()).toBeVisible();
    await expect(sidebar.locator(`a[href="${RBAC_PERMISSION_PATH}"]`)).toHaveCount(2);
  });

  test('role snapshots expose only current QuoteOps and BOM permissions', async ({ browser }) => {
    await withRolePage(browser, users.qoSales, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['qo_sales'], 'qo_sales roles');
      expectIncludes(
        snapshot.permissionCodes,
        [COMMAND_EXECUTE_PERMISSION, 'qo.quote.read', 'qo.quote.create', 'qo.rfq.upload'],
        'qo_sales permissions',
      );
      expectExcludes(
        snapshot.permissionCodes,
        [
          'qo.quote.manage',
          'qo.bom.import',
          'qo.price.manage',
          'qo.process_fee.manage',
          'qo.document.generate',
          ...BOM_PERMISSIONS,
        ],
        'qo_sales permissions',
      );
      expectIncludes(snapshot.menuPaths, [QUOTE_MENU_PATH], 'qo_sales menu paths');
      expectExcludes(snapshot.menuPaths, [PRICE_LIBRARY_MENU_PATH], 'qo_sales menu paths');
      expectNoBomMenus(snapshot, 'qo_sales');
    });

    await withRolePage(browser, users.qoQuoter, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['qo_quoter'], 'qo_quoter roles');
      expectIncludes(
        snapshot.permissionCodes,
        [COMMAND_EXECUTE_PERMISSION, ...QUOTE_PERMISSIONS],
        'qo_quoter permissions',
      );
      expectExcludes(snapshot.permissionCodes, BOM_PERMISSIONS, 'qo_quoter permissions');
      expectIncludes(
        snapshot.menuPaths,
        [QUOTE_MENU_PATH, PRICE_LIBRARY_MENU_PATH],
        'qo_quoter menu paths',
      );
      expectNoBomMenus(snapshot, 'qo_quoter');
    });

    await withRolePage(browser, users.bomOperator, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['bom_operator'], 'bom_operator roles');
      expectIncludes(
        snapshot.permissionCodes,
        [
          COMMAND_EXECUTE_PERMISSION,
          'bom.project.read',
          'bom.project.manage',
          'bom.convert.execute',
          'bom.library.read',
          'bom.rule.read',
        ],
        'bom_operator permissions',
      );
      expectExcludes(
        snapshot.permissionCodes,
        ['bom.library.manage', 'bom.rule.manage', ...QUOTE_PERMISSIONS],
        'bom_operator permissions',
      );
      expectIncludes(
        snapshot.menuPaths,
        [
          BOM_PROJECTS_PATH,
          BOM_WORKBENCH_PATH,
          BOM_FORMAT_PROFILE_PATH,
          BOM_FIELD_COMPOSITION_RULE_PATH,
        ],
        'bom_operator menu paths',
      );
      expectExcludes(
        snapshot.menuPaths,
        [BOM_REVIEW_QUEUE_PATH, BOM_MATERIAL_LIBRARY_PATH],
        'bom_operator menu paths',
      );
      expectNoQuoteMenus(snapshot, 'bom_operator');
    });

    await withRolePage(browser, users.bomAdmin, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['bom_admin'], 'bom_admin roles');
      expectIncludes(
        snapshot.permissionCodes,
        [COMMAND_EXECUTE_PERMISSION, ...BOM_PERMISSIONS],
        'bom_admin permissions',
      );
      expectExcludes(snapshot.permissionCodes, QUOTE_PERMISSIONS, 'bom_admin permissions');
      expectIncludes(
        snapshot.menuPaths,
        [
          BOM_PROJECTS_PATH,
          BOM_WORKBENCH_PATH,
          BOM_MATERIAL_LIBRARY_PATH,
          BOM_FORMAT_PROFILE_PATH,
          BOM_FIELD_COMPOSITION_RULE_PATH,
        ],
        'bom_admin menu paths',
      );
      expectExcludes(snapshot.menuPaths, [BOM_REVIEW_QUEUE_PATH], 'bom_admin menu paths');
      expectNoQuoteMenus(snapshot, 'bom_admin');
    });

    await withRolePage(browser, users.platformViewer, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['viewer'], 'platform_viewer roles');
      expectExcludes(
        snapshot.permissionCodes,
        [COMMAND_EXECUTE_PERMISSION, ...QUOTE_PERMISSIONS, ...BOM_PERMISSIONS],
        'platform_viewer permissions',
      );
      expectNoQuoteMenus(snapshot, 'platform_viewer');
      expectNoBomMenus(snapshot, 'platform_viewer');
    });
  });

  test('sidebar shows only the pages permitted for each role', async ({ browser }) => {
    await withRolePage(browser, users.qoSales, async (page) => {
      await assertSidebarLinks(
        page,
        [QUOTE_MENU_PATH],
        [PRICE_LIBRARY_MENU_PATH, BOM_PROJECTS_PATH, BOM_WORKBENCH_PATH],
      );
      await expectUnavailableByDirectUrl(page, PRICE_LIBRARY_MENU_PATH);
    });

    await withRolePage(browser, users.qoQuoter, async (page) => {
      await assertSidebarLinks(
        page,
        [QUOTE_MENU_PATH, PRICE_LIBRARY_MENU_PATH],
        [BOM_PROJECTS_PATH, BOM_WORKBENCH_PATH],
      );
      await expectUnavailableByDirectUrl(page, BOM_WORKBENCH_PATH);
    });

    await withRolePage(browser, users.bomOperator, async (page) => {
      await assertSidebarLinks(
        page,
        [
          BOM_PROJECTS_PATH,
          BOM_WORKBENCH_PATH,
          BOM_FORMAT_PROFILE_PATH,
          BOM_FIELD_COMPOSITION_RULE_PATH,
        ],
        [
          QUOTE_MENU_PATH,
          PRICE_LIBRARY_MENU_PATH,
          BOM_REVIEW_QUEUE_PATH,
          BOM_MATERIAL_LIBRARY_PATH,
        ],
      );
      await expectUnavailableByDirectUrl(page, QUOTE_MENU_PATH);
    });

    await withRolePage(browser, users.bomAdmin, async (page) => {
      await assertSidebarLinks(
        page,
        [
          BOM_PROJECTS_PATH,
          BOM_WORKBENCH_PATH,
          BOM_MATERIAL_LIBRARY_PATH,
          BOM_FORMAT_PROFILE_PATH,
          BOM_FIELD_COMPOSITION_RULE_PATH,
        ],
        [QUOTE_MENU_PATH, PRICE_LIBRARY_MENU_PATH, BOM_REVIEW_QUEUE_PATH],
      );
      await expectUnavailableByDirectUrl(page, QUOTE_MENU_PATH);
    });

    await withRolePage(browser, users.platformViewer, async (page) => {
      await assertSidebarLinks(
        page,
        [],
        [QUOTE_MENU_PATH, PRICE_LIBRARY_MENU_PATH, BOM_PROJECTS_PATH, BOM_WORKBENCH_PATH],
      );
      await expectUnavailableByDirectUrl(page, QUOTE_MENU_PATH);
      await expectUnavailableByDirectUrl(page, BOM_WORKBENCH_PATH);
    });
  });

  test('backend command permissions reject cross-role operations without 500', async ({
    browser,
  }) => {
    await withRolePage(browser, users.qoSales, async (page) => {
      await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandDenied(page, 'qo_quote_common:import_corrected_bom', {}, 'quote-id', 'update');
      await expectCommandDenied(page, 'qo_quote_common:batch_source_prices', {}, 'quote-id', 'update');
      await expectCommandDenied(page, 'qo_quote_common:compute_process_fee', {}, 'quote-id', 'update');
      await expectCommandDenied(page, 'qo_quote_common:generate_document', {}, 'quote-id', 'update');
      await expectCommandDenied(page, 'qo_offline_material_price_common:import_excel', {});
      await expectCommandDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-sales`));
    });

    await withRolePage(browser, users.qoQuoter, async (page) => {
      await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandNotDenied(page, 'qo_offline_material_price_common:import_excel', {});
      await expectCommandDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-quoter`));
      await expectCommandDenied(page, BOM_MATERIAL_SYNC_COMMAND, BOM_MATERIAL_SYNC_DRY_RUN);
    });

    await withRolePage(browser, users.bomOperator, async (page) => {
      await expectCommandNotDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-operator`));
      await expectCommandDenied(page, BOM_MATERIAL_SYNC_COMMAND, BOM_MATERIAL_SYNC_DRY_RUN);
      await expectCommandDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
    });

    await withRolePage(browser, users.bomAdmin, async (page) => {
      await expectCommandNotDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-admin`));
      await expectCommandNotDenied(page, BOM_MATERIAL_SYNC_COMMAND, BOM_MATERIAL_SYNC_DRY_RUN);
      await expectCommandDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
    });

    await withRolePage(browser, users.platformViewer, async (page) => {
      await expectCommandDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-viewer`));
    });
  });
});
