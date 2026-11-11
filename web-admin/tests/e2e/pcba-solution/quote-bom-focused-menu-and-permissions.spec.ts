import type { APIRequestContext, Browser } from '@playwright/test';
import { test, expect, type Page } from '../../fixtures';
import { ensureSidebarExpanded, uniqueId } from '../helpers';
import { BASE_URL } from '../../helpers/environments';
import {
  ensureQuoteRoleUser,
  seedQuoteScaffold,
  reassignRecordOwnerByEmail,
  cleanupRows,
  type CreatedRows,
  expectCommandDenied,
  expectCommandNotDenied,
  fetchRoleSnapshot,
  makeQuoteRoleUser,
  openQuoteRolePage,
  type QuoteRoleUser,
  type RoleSnapshot,
  openPgClient,
} from './quote-e2e-helpers';

/**
 * Approved domain model: sales/procurement/engineering have BOM write + Quote write.
 * Only domain managers may maintain shared materials/rules or trigger live sync.
 * Navigation assignments remain independent from business grants.
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

const COMMAND_EXECUTE_PERMISSION = 'meta.command.execute';

const QUOTE_PERMISSIONS = ['qo.cap.read', 'qo.cap.write'];
const BOM_BUSINESS_PERMISSIONS = ['bom.cap.read', 'bom.cap.write'];
const BOM_ADMIN_PERMISSIONS = ['bom.cap.manage', 'qo.cap.manage'];

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
  expect(
    visiblePaths.length,
    'sidebar assertions require an accessible business entry route',
  ).toBeGreaterThan(0);
  await page.goto(visiblePaths[0]!, { waitUntil: 'domcontentloaded' });
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

function bomProjectPayload(seed: string, customerId = `ACC-${seed}`): Record<string, unknown> {
  return {
    bom_project_name: `E2E BOM Project ${seed}`,
    bom_project_customer_id: customerId,
    bom_project_quality_level: 'industrial',
    bom_pcba_code: `PCBA-${seed}`,
    bom_project_remark: `Quote/BOM role permission probe ${seed}`,
  };
}

async function createCustomRole(page: Page, roleCode: string): Promise<string> {
  const resp = await page.context().request.post('/api/roles', {
    data: {
      code: roleCode,
      name: `E2E No Business ${roleCode.slice(-12)}`,
      description: 'E2E negative role with no Quote/BOM business capabilities',
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

async function grantNoBusinessCapabilities(request: APIRequestContext, rolePid: string): Promise<void> {
  const resp = await request.put(
    `/api/permission/capabilities?rolePid=${encodeURIComponent(rolePid)}`,
    {
      data: [],
      timeout: 15_000,
    },
  );
  const body = await resp.json().catch(() => ({}));
  expect(
    resp.ok(),
    `clear business capabilities to ${rolePid} HTTP ${resp.status()}: ${JSON.stringify(body).slice(0, 800)}`,
  ).toBe(true);
}

test.describe('QuoteOps + BOM focused menu and permission matrix @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(180_000);

  const uid = uniqueId('qobom_role').replace(/_/g, '-');
  const noBusinessRoleCode = `e2e_no_business_${uid.replace(/[^a-z0-9]+/gi, '_')}`.slice(0, 60);
  const users = {} as Record<RoleKey, QuoteRoleUser>;
  const fixtures = {} as Record<Exclude<RoleKey, 'noBusinessRole'>, CreatedRows>;

  test.beforeAll(async ({ browser }) => {
    users.qoSales = makeQuoteRoleUser('qo_sales', uid, ['qo_sales']);
    users.qoProcurement = makeQuoteRoleUser('qo_procurement', uid, ['qo_procurement']);
    users.bomEngineering = makeQuoteRoleUser('bom_engineering', uid, ['bom_engineering']);
    users.noBusinessRole = makeQuoteRoleUser('no_business_role', uid, [noBusinessRoleCode]);

    const context = await browser.newContext({
      baseURL: BASE_URL, extraHTTPHeaders: { Origin: BASE_URL },
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      const noBusinessRolePid = await createCustomRole(page, noBusinessRoleCode);
      await grantNoBusinessCapabilities(page.context().request, noBusinessRolePid);
      for (const user of Object.values(users)) {
        await ensureQuoteRoleUser(page, user);
      }
      for (const key of ['qoSales', 'qoProcurement', 'bomEngineering'] as const) {
        const fixture = await seedQuoteScaffold(page, `RB-${key.slice(0, 3)}`, []);
        fixtures[key] = fixture;
        await reassignRecordOwnerByEmail(fixture.rows, users[key].email);
      }
    } finally {
      await context.close();
    }
  });

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({
      baseURL: BASE_URL, extraHTTPHeaders: { Origin: BASE_URL },
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      for (const fixture of Object.values(fixtures)) await cleanupRows(page, fixture);
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
          [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS, BOM_MATERIAL_LIBRARY_PATH, BOM_FORMAT_PROFILE_PATH],
          `${label} menu paths`,
        );
        expectExcludes(
          snapshot.menuPaths,
          [BOM_REVIEW_QUEUE_PATH],
          `${label} menu paths`,
        );
      });
    }

    await withRolePage(browser, users.bomEngineering, async (page) => {
      const snapshot = await fetchRoleSnapshot(page);
      expectIncludes(snapshot.roleCodes, ['bom_engineering'], 'bom_engineering roles');
      expectIncludes(
        snapshot.permissionCodes,
        [
          COMMAND_EXECUTE_PERMISSION,
          ...QUOTE_PERMISSIONS,
          ...BOM_BUSINESS_PERMISSIONS,
        ],
        'bom_engineering permissions',
      );
      expectExcludes(snapshot.permissionCodes, BOM_ADMIN_PERMISSIONS, 'bom_engineering permissions');
      expectIncludes(
        snapshot.menuPaths,
        [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS, BOM_MATERIAL_LIBRARY_PATH, BOM_FORMAT_PROFILE_PATH],
        'bom_engineering menu paths',
      );
      expectExcludes(
        snapshot.menuPaths,
        [BOM_REVIEW_QUEUE_PATH],
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

  test('sidebar and direct routes expose only the pages permitted for each role', async ({
    browser,
  }) => {
    for (const user of [users.qoSales, users.qoProcurement]) {
      await withRolePage(browser, user, async (page) => {
        await assertSidebarLinks(
          page,
          [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS, BOM_MATERIAL_LIBRARY_PATH, BOM_FORMAT_PROFILE_PATH],
          [
            BOM_REVIEW_QUEUE_PATH,
          ],
        );
        await page.goto(BOM_MATERIAL_LIBRARY_PATH, { waitUntil: 'domcontentloaded' });
        await expect(page.getByTestId('dynamic-page-list')).toBeVisible();
        await expect(page.getByTestId('toolbar-btn-sync_kingdee_incremental_now')).toHaveCount(0);
        await expect(page.getByTestId('toolbar-btn-sync_kingdee_full_reconcile_now')).toHaveCount(0);
      });
    }

    await withRolePage(browser, users.bomEngineering, async (page) => {
      await assertSidebarLinks(
        page,
        [...QUOTE_MENU_PATHS, ...BUSINESS_MENU_PATHS, BOM_MATERIAL_LIBRARY_PATH, BOM_FORMAT_PROFILE_PATH],
        [BOM_REVIEW_QUEUE_PATH],
      );
    });

    await withRolePage(browser, users.noBusinessRole, async (page) => {
      // The preceding role-snapshot test proves that this role has no Quote/BOM menu entry.
      // With no accessible business route there is no business shell/sidebar to inspect, so
      // enforce the user-visible boundary through direct-route denial instead.
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
        const fixture = fixtures[label === 'sales' ? 'qoSales' : 'qoProcurement'];
        const customerId = fixture.rows.find((row) => row.model === 'crm_account_common')!.pid;
        await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
        await expectCommandNotDenied(page, 'qo_quote_common:batch_source_prices', {}, fixture.quoteId, 'update');
        await expectCommandNotDenied(page, 'qo_quote_common:compute_process_fee', {}, fixture.quoteId, 'update');
        await expectCommandNotDenied(page, 'qo_quote_common:generate_document', {}, fixture.quoteId, 'update');
        await expectCommandNotDenied(page, 'qo_offline_material_price_common:import_excel', {});
        const project = await expectCommandNotDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-${label}`, customerId));
        const result = (project.body as any).data?.data ?? (project.body as any).data;
        const projectId = String(result?.recordId ?? result?.recordPid ?? result?.pid ?? '');
        expect(projectId, 'allowed project creation must persist a real record').toBeTruthy();
        fixture.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });
        await expectCommandDenied(page, BOM_MATERIAL_SYNC_COMMAND, BOM_MATERIAL_SYNC_DRY_RUN);
        await expectCommandDenied(page, 'bom:create_material', { bom_mm_material_code: `X-${uid}` });
        await expectCommandDenied(page, 'org:create_department', { org_dept_name: `X-${uid}-${label}` });
      });
    }

    await withRolePage(browser, users.bomEngineering, async (page) => {
      const fixture = fixtures.bomEngineering;
      const customerId = fixture.rows.find((row) => row.model === 'crm_account_common')!.pid;
      await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandNotDenied(page, 'qo_quote_common:batch_source_prices', {}, fixture.quoteId, 'update');
      await expectCommandNotDenied(page, 'qo_quote_common:compute_process_fee', {}, fixture.quoteId, 'update');
      await expectCommandNotDenied(page, 'qo_quote_common:generate_document', {}, fixture.quoteId, 'update');
      await expectCommandNotDenied(page, 'qo_offline_material_price_common:import_excel', {});
      const project = await expectCommandNotDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-eng`, customerId));
      const result = (project.body as any).data?.data ?? (project.body as any).data;
      const projectId = String(result?.recordId ?? result?.recordPid ?? result?.pid ?? '');
      expect(projectId, 'allowed project creation must persist a real record').toBeTruthy();
      fixture.rows.push({ model: 'req_requirement_set_pcba_bom', pid: projectId });
      await expectCommandNotDenied(page, 'qo_quote_common:import_corrected_bom', {}, fixture.quoteId, 'update');
      // Domain write does not grant shared-library management or live connector sync.
      await expectCommandDenied(page, 'bom:create_material', {
        bom_mm_material_code: `X-${uid}-eng`,
      });
      await expectCommandDenied(page, 'bom:create_validation_rule', {});
    });

    await withRolePage(browser, users.noBusinessRole, async (page) => {
      await expectCommandDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
      await expectCommandDenied(page, 'bom:create_project', bomProjectPayload(`${uid}-norole`));
      await expectCommandDenied(page, 'org:create_department', { org_dept_name: `X-${uid}-norole` });

      // X02-01: dynamic role assignment through the platform admin API must
      // flip authorization on the next request without a new login. Grant
      // qo_sales to the no-role member, the previously denied quote create is
      // accepted, and revoking the role returns it to denied.
      const { client } = await openPgClient();
      let memberPid = '';
      try {
        const row = await client.query(
          `select tm.pid from ab_tenant_member tm
           join ab_user u on u.id = tm.user_id
           where u.email = $1 order by tm.created_at desc limit 1`,
          [users.noBusinessRole.email],
        );
        memberPid = String(row.rows[0]?.pid ?? '');
      } finally {
        await client.end();
      }
      expect(memberPid, 'no-role member has a pid').toBeTruthy();

      const adminContext = await browser.newContext({
        baseURL: BASE_URL, extraHTTPHeaders: { Origin: BASE_URL },
        storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
      });
      const grant = await adminContext.request.post('/api/user-roles/assign-by-code', {
        data: { memberPid, roleCodes: ['qo_sales'] },
      });
      expect(grant.status(), 'role grant via admin API succeeds').toBe(200);
      await expectCommandNotDenied(page, 'qo_quote_common:create', {}, undefined, 'create');

      // assign-by-code treats an empty roleCodes list as a no-op, so the
      // revoke uses the platform remove-by-pid endpoint (service-layer change
      // publishes the member permission-cache eviction after commit).
      const { client: revokeClient } = await openPgClient();
      let qoSalesRolePid = '';
      try {
        qoSalesRolePid = String(await revokeClient.query(
          `select pid from ab_role where code = 'qo_sales' limit 1`,
        ).then((r) => r.rows[0]?.pid ?? ''));
      } finally {
        await revokeClient.end();
      }
      expect(qoSalesRolePid, 'qo_sales role pid resolvable').toBeTruthy();
      const revoke = await adminContext.request.delete('/api/user-roles/remove-by-pid', {
        data: { memberPid, rolePids: [qoSalesRolePid] },
      });
      expect(revoke.status(), 'role revoke via admin API succeeds').toBe(200);
      await adminContext.close();
      await expectCommandDenied(page, 'qo_quote_common:create', {}, undefined, 'create');
    });
  });
});
