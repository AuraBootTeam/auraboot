import { test, expect, type Browser, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Client as PgClient } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { openSavedViewManagePanel, waitForDynamicPageLoad } from '../helpers';
import { loginViaUI } from '../../helpers/wd-fixtures';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { E2ET_ORDER_CONFIG } from '../../helpers/configs/e2et-order.config';

const MODEL_CODE = 'e2et_order';
const PAGE_KEY = 'e2et_order_list';
const OWNERSHIP_PAGE_KEY = 'e2et_record_list';
const PERSONA = {
  email: 'e2e-contextual-personalization@test.com',
  roleCode: 'e2e_contextual_personalization',
  roleName: 'Contextual personalization persona',
};
const SCREENSHOT_DIR = resolve(
  process.env.CONTEXTUAL_AUTHORING_SCREENSHOT_DIR ?? 'test-results/contextual-authoring',
);

type ApiEnvelope<T> = { code?: number | string; data?: T; desc?: string; message?: string };
type SavedView = {
  pid: string;
  name: string;
  scope: 'personal' | 'team' | 'role' | 'global';
  ownerId?: string | null;
  teamId?: string | null;
  roleId?: string | null;
  viewConfig: {
    density?: string;
    rowHeight?: string;
    columns?: Array<{ fieldCode: string; visible?: boolean; width?: number; order?: number }>;
    toolbarActions?: Array<{ code: string; visible?: boolean; pinned?: boolean; order?: number }>;
    meta?: {
      overlayStatus?: string;
      overlayReasonCodes?: string[];
      overlayStalePaths?: string[];
    };
  };
};
type Role = { pid: string; code: string };
type Team = { pid: string; code: string };
type Identity = {
  tenantId: string;
  userId: string;
  userPid: string;
  memberId: string;
  memberPid: string;
};
type LayerFixture = {
  prefix: string;
  teamPid: string;
  rolePid: string;
  admin: Identity;
  persona: Identity;
  views: Record<'global' | 'team' | 'role' | 'adminPersonal' | 'personaPersonal', SavedView>;
};
type AuthoringSession = {
  sessionPid: string;
  changeSetPid: string;
  ownership?: {
    ownershipScope: string;
    sourceOwnershipScope: string;
    tenantOverride: boolean;
    sourceMutable: boolean;
    restoreTarget: string;
  };
};

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring personalization and promotion PC golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test('PC-AUTH-047 @critical — four-layer personalization is isolated, replayed, repairable and ownership-explicit [AR-001] [AR-036] [AR-037] [AR-038]', async ({
    browser,
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await loginToBusinessTenant(page);
    await ensurePersonaAndAdminPermissions(page);
    const persona = await openPersona(browser);
    const fixture = await createLayerFixture(page, persona);
    const order = new ModelTestHelper(page, E2ET_ORDER_CONFIG);
    const orderPid = await order.createViaApi({
      e2et_order_title: `${fixture.prefix} row-height proof`,
    });

    try {
      const adminDefault = await getDefaultView(page);
      expect(columnWidth(adminDefault, 'e2et_order_no')).toBe(180);
      expect(adminDefault.viewConfig.density).toBe('comfortable');
      expect(adminDefault.viewConfig.rowHeight).toBe('extra-tall');

      const personaDefault = await getDefaultView(persona);
      expect(columnWidth(personaDefault, 'e2et_order_no')).toBe(160);
      expect(personaDefault.viewConfig.density).toBe('compact');
      expect(personaDefault.viewConfig.rowHeight).toBe('tall');
      expect(personaDefault.ownerId).toBe(fixture.persona.userPid);
      expect(adminDefault.ownerId).toBe(fixture.admin.userPid);

      const personaAccessible = await getAccessibleViews(persona);
      expect(personaAccessible.map((view) => view.pid)).toContain(
        fixture.views.personaPersonal.pid,
      );
      expect(personaAccessible.map((view) => view.pid)).not.toContain(
        fixture.views.adminPersonal.pid,
      );

      await persona.goto('/p/e2et_order', { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(persona, 15_000);
      const trigger = persona.getByTestId('view-selector-trigger');
      await expect(trigger).toHaveAttribute(
        'data-current-view-name',
        fixture.views.personaPersonal.name,
      );
      const renderedOrderNumberWidth = await persona
        .getByTestId('table-header-e2et_order_no')
        .evaluate((element) => element.getBoundingClientRect().width);
      expect(renderedOrderNumberWidth).toBeGreaterThanOrEqual(160);
      const firstRow = persona.getByTestId('table-row-0');
      await expect(firstRow).toHaveCSS('height', '60px');

      const rejectedUnknown = await persona.request.put(
        `/api/views/${fixture.views.personaPersonal.pid}`,
        { data: { viewConfig: { columns: [{ fieldCode: 'field_removed_before_save' }] } } },
      );
      expect(rejectedUnknown.status()).toBe(422);
      expect(await rejectedUnknown.text()).toContain('view.overlay.unknown-field');
      expect((await loadStoredViewConfig(fixture.views.personaPersonal.pid)).columns).not.toEqual(
        expect.arrayContaining([
          expect.objectContaining({ fieldCode: 'field_removed_before_save' }),
        ]),
      );

      const mandatory = await markMandatoryElements();
      try {
        const rejectedMandatory = await persona.request.put(
          `/api/views/${fixture.views.personaPersonal.pid}`,
          {
            data: {
              viewConfig: {
                columns: [{ fieldCode: mandatory.field, visible: false }],
                toolbarActions: [{ code: mandatory.action, visible: false }],
              },
            },
          },
        );
        expect(rejectedMandatory.status()).toBe(422);
        expect(await rejectedMandatory.text()).toContain('view.overlay.mandatory-cannot-hide');

        await injectLegacyStaleReferences(fixture.views.personaPersonal.pid);
        const replayed = await getDefaultView(persona);
        expect(replayed.viewConfig.meta?.overlayStatus).toBe('STALE');
        expect(replayed.viewConfig.meta?.overlayReasonCodes).toEqual(
          expect.arrayContaining(['FIELD_REMOVED', 'ACTION_REMOVED']),
        );
        expect(replayed.viewConfig.columns?.map((column) => column.fieldCode)).not.toContain(
          'legacy_removed_field',
        );
        expect(replayed.viewConfig.toolbarActions?.map((action) => action.code)).not.toContain(
          'legacy_removed_action',
        );

        await persona.reload({ waitUntil: 'domcontentloaded' });
        await waitForDynamicPageLoad(persona, 15_000);
        await expect(persona.getByTestId('saved-view-overlay-stale')).toBeVisible();
        await mkdir(SCREENSHOT_DIR, { recursive: true });
        await persona.screenshot({
          path: resolve(SCREENSHOT_DIR, 'pc-auth-047-personal-overlay-stale.png'),
          fullPage: true,
        });

        const repairResponse = persona.waitForResponse(
          (response) =>
            response.request().method() === 'PUT' &&
            new URL(response.url()).pathname === `/api/views/${fixture.views.personaPersonal.pid}`,
        );
        await persona.getByTestId('saved-view-overlay-repair').click();
        expect((await repairResponse).status()).toBe(200);
        await expect(persona.getByTestId('saved-view-overlay-stale')).toHaveCount(0);
      } finally {
        await restoreMandatoryElements(mandatory.blocks);
      }

      const panel = await openSavedViewManagePanel(persona);
      await panel
        .getByTestId(`saved-view-action-delete-${fixture.views.personaPersonal.pid}`)
        .click();
      await persona.getByTestId('confirm-ok').click();
      await expect(
        panel.getByTestId(`saved-view-row-${fixture.views.personaPersonal.pid}`),
      ).toHaveCount(0);
      const roleFallback = await getDefaultView(persona);
      expect(roleFallback.scope).toBe('role');
      expect(columnWidth(roleFallback, 'e2et_order_no')).toBe(140);
      expect(roleFallback.viewConfig.rowHeight).toBe('medium');

      await persona.request.delete(`/api/views/${fixture.views.role.pid}`);
      const teamFallback = await getDefaultView(persona);
      expect(teamFallback.scope).toBe('team');
      expect(columnWidth(teamFallback, 'e2et_order_no')).toBe(120);
      expect(teamFallback.viewConfig.density).toBe('compact');

      await persona.request.delete(`/api/views/${fixture.views.team.pid}`);
      const tenantFallback = await getDefaultView(persona);
      expect(tenantFallback.scope).toBe('global');
      expect(columnWidth(tenantFallback, 'e2et_order_no')).toBe(100);

      const applicationPage = await apiData<{ pid: string }>(
        await page.request.get(`/api/pages/key/${OWNERSHIP_PAGE_KEY}`),
        'load application-owned page',
      );
      const openResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === '/api/authoring/sessions',
      );
      await page.goto('/p/e2et_record', { waitUntil: 'domcontentloaded' });
      await waitForDynamicPageLoad(page, 15_000);
      await page.getByTestId('contextual-authoring-enter').click();
      const opened = await apiData<AuthoringSession>(await openResponse, 'open ownership session');
      expect(opened.ownership).toMatchObject({
        ownershipScope: 'TENANT',
        sourceOwnershipScope: 'APPLICATION',
        tenantOverride: true,
        sourceMutable: false,
        restoreTarget: 'APPLICATION',
      });
      expect(applicationPage.pid).toBeTruthy();
      await page.getByTestId('authoring-inspector-open').click();
      await page
        .getByTestId('authoring-inspector')
        .getByRole('button', { name: '高级设置', exact: true })
        .click();
      await page
        .getByRole('dialog', { name: '进入应用设计中心' })
        .getByRole('button', { name: '继续到应用设计中心' })
        .click();
      await page.getByTestId('studio-governance-open').click();
      const notice = page.getByTestId('authoring-ownership-notice');
      await expect(notice).toContainText('APPLICATION → TENANT');
      await expect(notice).toContainText('共享源不可在此修改');
      await expect(notice).toContainText('APPLICATION 层');
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-047-ownership-notice.png'),
        fullPage: true,
      });

      await assertNoPersonalizationAuthoringSideEffects(fixture.prefix);
    } finally {
      await order.deleteViaApi(orderPid);
      await cleanupLayerFixture(fixture.prefix);
      await persona.context().close();
    }
  });

  test('PC-AUTH-048 @critical — rebase, backport, keep and overwrite execute distinct governed outcomes [AR-039]', async ({
    browser,
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await loginToBusinessTenant(page);
    await ensurePersonaAndAdminPermissions(page);
    const reviewer = await openPersona(browser);
    const fixtures: PromotionFixture[] = [];
    try {
      for (const decision of ['REBASE', 'BACKPORT', 'KEEP_OVERRIDE', 'OVERWRITE'] as const) {
        fixtures.push(await createPromotionFixture(page, reviewer, decision));
      }
      await page.goto('/admin/promotions', { waitUntil: 'domcontentloaded' });
      await expect(page.getByRole('heading', { name: 'Promotions' })).toBeVisible();

      for (const fixture of fixtures) {
        const row = page.getByTestId(`promotion-row-${fixture.promotionPid}`);
        await expect(row).toBeVisible();
        await row.click();
        const driftPanel = page.getByTestId(`promotion-drift-${fixture.unitPid}`);
        await expect(driftPanel).toBeVisible();
        const decisionTitle = decisionLabel(fixture.decision);
        const decisionRadio = driftPanel.getByRole('radio', { name: decisionTitle });
        await driftPanel.getByText(decisionTitle, { exact: true }).click();
        await expect(decisionRadio).toBeChecked();
        await driftPanel
          .getByLabel('决策原因（必填）')
          .fill(`PC-AUTH-048 ${fixture.decision} governed execution`);
        const decisionResponse = page.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            new URL(response.url()).pathname ===
              `/api/admin/promotions/${fixture.promotionPid}/drifts/${fixture.unitPid}/decision`,
        );
        await driftPanel.getByTestId(`promotion-drift-submit-${fixture.unitPid}`).click();
        expect((await decisionResponse).status()).toBe(200);

        if (fixture.decision === 'REBASE' || fixture.decision === 'OVERWRITE') {
          const applyButton = page.getByTestId(`promotion-apply-${fixture.promotionPid}`);
          await expect(applyButton).toBeEnabled();
          await applyButton.click();
          const dialog = page.getByRole('dialog', { name: /Apply Promotion/i });
          await dialog.getByLabel(/reason/i).fill(`PC-AUTH-048 apply ${fixture.decision}`);
          const applyResponse = page.waitForResponse(
            (response) =>
              response.request().method() === 'POST' &&
              new URL(response.url()).pathname ===
                `/api/admin/promotions/${fixture.promotionPid}/apply`,
          );
          await dialog.getByRole('button', { name: /^Apply$/ }).click();
          expect((await applyResponse).status()).toBe(200);
        }

        await assertPromotionOutcome(fixture);
      }

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-048-promotion-four-outcomes.png'),
        fullPage: true,
      });
    } finally {
      await cleanupPromotionFixtures(fixtures);
      await reviewer.context().close();
    }
  });
});

async function loginToBusinessTenant(
  page: Page,
  email: string = DEFAULT_TEST_ACCOUNT.email,
  password: string = DEFAULT_TEST_ACCOUNT.password,
): Promise<void> {
  await loginViaUI(page, email, password);
  const spaces = await apiData<
    Array<{ tenantId: string | number; tenantName?: string; spaceType?: string }>
  >(await page.request.get('/api/tenant-selection/my-spaces'), 'load tenant spaces');
  const business =
    spaces.find(
      (space) => space.spaceType === 'business' && space.tenantName === 'AuraBoot Demo',
    ) ?? spaces.find((space) => space.spaceType === 'business');
  expect(business?.tenantId, 'business tenant').toBeTruthy();
  const me = await apiData<{ user: { tenantId?: string | number } }>(
    await page.request.get('/api/auth/me'),
    'load current auth',
  );
  if (String(me.user.tenantId) !== String(business!.tenantId)) {
    const switched = await page.request.post('/_action/switch-space', {
      form: { tenantId: String(business!.tenantId), redirectTo: '/home' },
      maxRedirects: 0,
    });
    expect([302, 303]).toContain(switched.status());
    const sessionCookie = switched.headers()['set-cookie']?.match(/__session=([^;]+)/)?.[1];
    expect(sessionCookie).toBeTruthy();
    await page.context().addCookies([
      {
        name: '__session',
        value: sessionCookie!,
        path: '/',
        domain: 'localhost',
        httpOnly: true,
        sameSite: 'Lax',
      },
      {
        name: '__session',
        value: sessionCookie!,
        path: '/',
        domain: '127.0.0.1',
        httpOnly: true,
        sameSite: 'Lax',
      },
    ]);
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
  }
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function openPersona(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginToBusinessTenant(page, PERSONA.email, DEFAULT_TEST_ACCOUNT.password);
  return page;
}

async function ensurePersonaAndAdminPermissions(admin: Page): Promise<void> {
  const roles = await apiData<Role[]>(await admin.request.get('/api/roles/all'), 'load roles');
  let role = roles.find((candidate) => candidate.code === PERSONA.roleCode);
  if (!role) {
    role = await apiData<Role>(
      await admin.request.post('/api/roles', {
        data: {
          code: PERSONA.roleCode,
          name: PERSONA.roleName,
          type: 'custom',
          description: 'PC-AUTH-047 dual-account personalization persona',
        },
      }),
      'create personalization role',
    );
  }
  const permissionCodes = [
    'dashboard.saved_view.read',
    'dashboard.saved_view.update',
    'page.page.read',
    'e2et.order.read',
    'model.e2et_order',
    'model.e2et_order.read',
    'meta.publish.read',
    'meta.publish.update',
  ];
  const permissions = (
    await Promise.all(
      ['function', 'operation', 'data', 'model'].map(async (type) =>
        apiData<Array<{ pid: string; code: string }>>(
          await admin.request.get(`/api/permissions/resource-type/${type}`),
          `load ${type} permissions`,
        ),
      ),
    )
  ).flat();
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  expect(permissionCodes.filter((code) => !byCode.has(code))).toEqual([]);
  await apiData(
    await admin.request.post(`/api/roles/${role.pid}/permissions`, {
      data: permissionCodes.map((code) => byCode.get(code)!),
    }),
    'assign persona permissions',
  );
  const adminLogin = await admin.request.post('/api/auth/login', {
    data: { email: PERSONA.email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  if (!adminLogin.ok()) {
    await apiData(
      await admin.request.post('/api/admin/users', {
        data: {
          email: PERSONA.email,
          displayName: PERSONA.roleName,
          initialPassword: DEFAULT_TEST_ACCOUNT.password,
          roleCodes: [PERSONA.roleCode],
          sendInviteEmail: false,
        },
      }),
      'create personalization persona',
    );
  }
  const members = await apiData<Array<{ email?: string; memberPid?: string }>>(
    await admin.request.get(
      `/api/org/members/unlinked?keyword=${encodeURIComponent(PERSONA.email)}`,
    ),
    'resolve persona member',
  );
  const member = members.find((candidate) => candidate.email === PERSONA.email);
  expect(member?.memberPid).toBeTruthy();
  await apiData(
    await admin.request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: member!.memberPid, roleCodes: [PERSONA.roleCode] },
    }),
    'activate persona role',
  );
}

async function createLayerFixture(admin: Page, personaPage: Page): Promise<LayerFixture> {
  const prefix = `pc47_${Date.now().toString(36)}_${process.pid}`;
  const [adminIdentity, personaIdentity] = await Promise.all([
    findIdentity(DEFAULT_TEST_ACCOUNT.email),
    findIdentity(PERSONA.email),
  ]);
  const team = await apiData<Team>(
    await admin.request.post('/api/org/teams', {
      data: { code: `${prefix}_team`, name: `PC-AUTH-047 team ${prefix}` },
    }),
    'create overlay team',
  );
  for (const identity of [adminIdentity, personaIdentity]) {
    await apiData(
      await admin.request.post(`/api/org/teams/${team.pid}/members`, {
        data: { userPid: identity.userPid, memberPid: identity.memberPid, role: 'member' },
      }),
      `add ${identity.userPid} to overlay team`,
    );
  }
  const roles = await apiData<Role[]>(await admin.request.get('/api/roles/all'), 'reload roles');
  const role = roles.find((candidate) => candidate.code === PERSONA.roleCode)!;
  await apiData(
    await admin.request.post('/api/user-roles/assign-by-pid', {
      data: { memberPid: adminIdentity.memberPid, rolePids: [role.pid] },
    }),
    'add admin to overlay role',
  );

  const global = await createView(admin, `${prefix}_global`, 'global', {
    columns: [{ fieldCode: 'e2et_order_no', visible: true, width: 100, order: 0 }],
    density: 'default',
  });
  const teamView = await createView(
    admin,
    `${prefix}_team`,
    'team',
    {
      columns: [{ fieldCode: 'e2et_order_no', visible: true, width: 120, order: 0 }],
      density: 'compact',
    },
    { teamId: team.pid },
  );
  const roleView = await createView(
    admin,
    `${prefix}_role`,
    'role',
    {
      columns: [{ fieldCode: 'e2et_order_no', visible: true, width: 140, order: 0 }],
      rowHeight: 'medium',
    },
    { roleId: role.pid },
  );
  const adminPersonal = await createView(admin, `${prefix}_admin_personal`, 'personal', {
    columns: [{ fieldCode: 'e2et_order_no', visible: true, width: 180, order: 0 }],
    density: 'comfortable',
    rowHeight: 'extra-tall',
  });
  const personaPersonal = await createView(personaPage, `${prefix}_persona_personal`, 'personal', {
    columns: [{ fieldCode: 'e2et_order_no', visible: true, width: 160, order: 0 }],
    rowHeight: 'tall',
  });
  return {
    prefix,
    teamPid: team.pid,
    rolePid: role.pid,
    admin: adminIdentity,
    persona: personaIdentity,
    views: { global, team: teamView, role: roleView, adminPersonal, personaPersonal },
  };
}

async function createView(
  page: Page,
  name: string,
  scope: SavedView['scope'],
  viewConfig: SavedView['viewConfig'],
  owner: { teamId?: string; roleId?: string } = {},
): Promise<SavedView> {
  return apiData<SavedView>(
    await page.request.post('/api/views', {
      data: {
        name,
        modelCode: MODEL_CODE,
        pageKey: PAGE_KEY,
        viewType: 'table',
        scope,
        teamId: owner.teamId,
        roleId: owner.roleId,
        isDefault: true,
        viewConfig,
      },
    }),
    `create ${scope} overlay`,
  );
}

async function getDefaultView(page: Page): Promise<SavedView> {
  return apiData<SavedView>(
    await page.request.get(`/api/views/default?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`),
    'load effective default view',
  );
}

async function getAccessibleViews(page: Page): Promise<SavedView[]> {
  return apiData<SavedView[]>(
    await page.request.get(`/api/views/accessible?modelCode=${MODEL_CODE}&pageKey=${PAGE_KEY}`),
    'load accessible views',
  );
}

function columnWidth(view: SavedView, fieldCode: string): number | undefined {
  return view.viewConfig.columns?.find((column) => column.fieldCode === fieldCode)?.width;
}

async function findIdentity(email: string): Promise<Identity> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<Identity>(
      `SELECT m.tenant_id::text AS "tenantId", u.id::text AS "userId",
              u.pid AS "userPid", m.id::text AS "memberId", m.pid AS "memberPid"
         FROM ab_user u
         JOIN ab_tenant_member m ON m.user_id = u.id AND m.deleted_flag = FALSE
         JOIN ab_tenant t ON t.id = m.tenant_id
        WHERE u.email = $1
          AND t.name <> 'System'
          AND m.status = 'active'
        ORDER BY m.updated_at DESC
        LIMIT 1`,
      [email],
    );
    expect(result.rows[0], `identity for ${email}`).toBeTruthy();
    return result.rows[0];
  } finally {
    await client.end();
  }
}

async function loadStoredViewConfig(pid: string): Promise<Record<string, unknown>> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<{ view_config: Record<string, unknown> }>(
      'SELECT view_config FROM ab_saved_view WHERE pid = $1 AND deleted_flag = FALSE',
      [pid],
    );
    return result.rows[0]?.view_config ?? {};
  } finally {
    await client.end();
  }
}

async function markMandatoryElements(): Promise<{
  field: string;
  action: string;
  blocks: unknown;
}> {
  const field = 'e2et_order_no';
  const action = 'create';
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const current = await client.query<{ blocks: unknown }>(
      `SELECT blocks FROM ab_page_schema
        WHERE page_key = $1 AND is_current = TRUE AND deleted_flag = FALSE
        LIMIT 1`,
      [PAGE_KEY],
    );
    const blocks = current.rows[0]!.blocks as Array<Record<string, any>>;
    const patched = structuredClone(blocks);
    for (const block of patched) {
      for (const column of block.columns ?? []) {
        if (column.field === field) column.mandatory = true;
      }
      for (const button of block.buttons ?? []) {
        if (button.code === action) button.mandatory = true;
      }
    }
    const updated = await client.query(
      `UPDATE ab_page_schema SET blocks = $2::jsonb, row_version = row_version + 1,
              updated_at = CURRENT_TIMESTAMP
        WHERE page_key = $1 AND is_current = TRUE AND deleted_flag = FALSE`,
      [PAGE_KEY, JSON.stringify(patched)],
    );
    expect(updated.rowCount).toBe(1);
    return { field, action, blocks: current.rows[0]!.blocks };
  } finally {
    await client.end();
  }
}

async function restoreMandatoryElements(blocks: unknown): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    await client.query(
      `UPDATE ab_page_schema SET blocks = $2::jsonb, row_version = row_version + 1,
              updated_at = CURRENT_TIMESTAMP
        WHERE page_key = $1 AND is_current = TRUE AND deleted_flag = FALSE`,
      [PAGE_KEY, JSON.stringify(blocks)],
    );
  } finally {
    await client.end();
  }
}

async function injectLegacyStaleReferences(viewPid: string): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query(
      `UPDATE ab_saved_view
          SET view_config = jsonb_set(
                jsonb_set(
                  jsonb_set(
                    jsonb_set(
                      view_config,
                      '{columns}',
                      CASE WHEN jsonb_typeof(view_config->'columns') = 'array'
                        THEN view_config->'columns' ELSE '[]'::jsonb END
                        || '[{"fieldCode":"legacy_removed_field","visible":true,"width":77}]'::jsonb,
                      TRUE),
                    '{toolbarActions}',
                    CASE WHEN jsonb_typeof(view_config->'toolbarActions') = 'array'
                      THEN view_config->'toolbarActions' ELSE '[]'::jsonb END
                      || '[{"code":"legacy_removed_action","visible":true}]'::jsonb,
                    TRUE),
                  '{meta,baseFieldCodes}',
                  COALESCE(view_config#>'{meta,baseFieldCodes}', '[]'::jsonb)
                    || '["legacy_removed_field"]'::jsonb,
                  TRUE),
                '{meta,baseActionCodes}',
                COALESCE(view_config#>'{meta,baseActionCodes}', '[]'::jsonb)
                  || '["legacy_removed_action"]'::jsonb,
                TRUE),
              updated_at = CURRENT_TIMESTAMP
        WHERE pid = $1 AND deleted_flag = FALSE`,
      [viewPid],
    );
    expect(result.rowCount).toBe(1);
  } finally {
    await client.end();
  }
}

async function assertNoPersonalizationAuthoringSideEffects(prefix: string): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<{ changes: string }>(
      `SELECT COUNT(*)::text AS changes
         FROM ab_authoring_change_set
        WHERE title LIKE $1 AND deleted_flag = FALSE`,
      [`%${prefix}%`],
    );
    expect(result.rows[0]?.changes).toBe('0');
  } finally {
    await client.end();
  }
}

async function cleanupLayerFixture(prefix: string): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    await client.query('BEGIN');
    await client.query(`UPDATE ab_saved_view SET deleted_flag = TRUE WHERE name LIKE $1`, [
      `${prefix}%`,
    ]);
    await client.query(`UPDATE ab_team SET deleted_flag = TRUE WHERE code = $1`, [
      `${prefix}_team`,
    ]);
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    await client.end();
  }
}

type PromotionDecision = 'REBASE' | 'BACKPORT' | 'KEEP_OVERRIDE' | 'OVERWRITE';
type PromotionFixture = {
  prefix: string;
  decision: PromotionDecision;
  promotionPid: string;
  unitPid: string;
  sourceEnvId: string;
  targetEnvId: string;
  sourcePagePid: string;
  targetPagePid: string;
  targetReleasePid: string;
  overridePid: string;
};

async function createPromotionFixture(
  page: Page,
  reviewer: Page,
  decision: PromotionDecision,
): Promise<PromotionFixture> {
  const prefix = `pc48_${decision.toLowerCase()}_${Date.now().toString(36)}_${process.pid}`;
  const sourceEnv = await apiData<{ id: string; code: string }>(
    await page.request.post('/api/admin/environments', {
      data: { code: `${prefix}_src`, name: `${prefix} source`, isDefault: false, sortOrder: 0 },
    }),
    `create ${decision} source env`,
  );
  const targetEnv = await apiData<{ id: string; code: string }>(
    await page.request.post('/api/admin/environments', {
      data: { code: `${prefix}_tgt`, name: `${prefix} target`, isDefault: false, sortOrder: 0 },
    }),
    `create ${decision} target env`,
  );
  const sourcePage = await createApplicationPage(page, `${prefix}_src`, sourceEnv.code, 'compact');
  const targetPage = await createApplicationPage(
    page,
    `${prefix}_tgt`,
    targetEnv.code,
    'normal',
    sourcePage.pageKey,
  );
  const published = await publishTargetOverride(page, reviewer, targetEnv.code, targetPage.pid);
  const promotion = await apiData<{
    pid: string;
    units: Array<{ pid: string }>;
    dryRunResult?: { drifts?: Array<{ unitPid: string }> };
  }>(
    await page.request.post('/api/admin/promotions', {
      data: {
        sourceEnvId: sourceEnv.id,
        targetEnvId: targetEnv.id,
        units: [{ resourceType: 'PAGE_SCHEMA', resourcePid: sourcePage.pid, sortOrder: 0 }],
      },
    }),
    `create ${decision} promotion`,
  );
  await apiData(
    await page.request.post(`/api/admin/promotions/${promotion.pid}/validate`),
    `validate ${decision}`,
  );
  const loaded = await apiData<{
    units: Array<{ pid: string }>;
    dryRunResult: { drifts: Array<{ unitPid: string }> };
  }>(
    await page.request.get(`/api/admin/promotions/${promotion.pid}`),
    `load ${decision} promotion`,
  );
  return {
    prefix,
    decision,
    promotionPid: promotion.pid,
    unitPid: loaded.dryRunResult.drifts[0]!.unitPid,
    sourceEnvId: sourceEnv.id,
    targetEnvId: targetEnv.id,
    sourcePagePid: sourcePage.pid,
    targetPagePid: targetPage.pid,
    targetReleasePid: published.releasePid,
    overridePid: published.overridePid,
  };
}

async function createApplicationPage(
  page: Page,
  prefix: string,
  envCode: string,
  density: string,
  pageKey = `${prefix}_orders`,
): Promise<{ pid: string; pageKey: string }> {
  const created = await apiData<{ pid: string }>(
    await page.request.post('/api/pages', {
      headers: { 'X-Environment': envCode },
      data: {
        pageKey,
        modelCode: 'e2et_order',
        name: `${prefix} page`,
        title: `${prefix} page`,
        kind: 'list',
        profile: 'admin',
        layout: { type: 'stack' },
        blocks: [{ id: 'table-1', blockType: 'table', props: { density } }],
        schemaVersion: 4,
        isTemplate: false,
        sortWeight: 0,
        semver: '1.0.0',
        pluginPid: '01KZT2P9YDEGKZYBG6A12ZWS8D',
      },
    }),
    `create application page ${prefix}`,
  );
  await apiData(
    await page.request.post(`/api/pages/${created.pid}/publish`, {
      headers: { 'X-Environment': envCode },
    }),
    `publish application page ${prefix}`,
  );
  return { pid: created.pid, pageKey };
}

async function publishTargetOverride(
  page: Page,
  reviewer: Page,
  envCode: string,
  pagePid: string,
): Promise<{ releasePid: string; overridePid: string }> {
  const session = await apiData<{
    sessionPid: string;
    changeSetPid: string;
    revision: number;
    ownership: { overridePid: string };
    snapshot: { blocks: Array<{ id: string; blockType: string }> };
  }>(
    await page.request.post('/api/authoring/sessions', {
      headers: { 'X-Environment': envCode },
      data: { pagePid },
    }),
    'open target override session',
  );
  const table = findBlock(session.snapshot.blocks, 'table');
  expect(table?.id).toBeTruthy();
  const capabilities = await apiData<{ manifests: Array<{ blockType: string; checksum: string }> }>(
    await page.request.get('/api/authoring/capabilities', {
      headers: { 'X-Environment': envCode },
    }),
    'load authoring capabilities',
  );
  const checksum = capabilities.manifests.find(
    (manifest) => manifest.blockType === 'table',
  )!.checksum;
  const patched = await apiData<{ session: { revision: number } }>(
    await page.request.patch(`/api/authoring/sessions/${session.sessionPid}/studio-patches`, {
      headers: { 'X-Environment': envCode },
      data: {
        expectedRevision: session.revision,
        blockId: table!.id,
        propertyPath: '/dataSource',
        operation: 'ADD',
        value: { model: 'e2et_order' },
        manifestChecksum: checksum,
      },
    }),
    'patch target override',
  );
  const prepared = await apiData<{ revision: number }>(
    await page.request.post(`/api/authoring/sessions/${session.sessionPid}/prepare`, {
      headers: { 'X-Environment': envCode },
      data: { expectedRevision: patched.session.revision },
    }),
    'prepare target override',
  );
  await apiData(
    await page.request.post(`/api/authoring/sessions/${session.sessionPid}/submit`, {
      headers: { 'X-Environment': envCode },
      data: { expectedRevision: prepared.revision },
    }),
    'submit target override',
  );
  await apiData(
    await reviewer.request.post(`/api/authoring/change-sets/${session.changeSetPid}/approve`, {
      headers: { 'X-Environment': envCode },
      data: {
        expectedRevision: prepared.revision,
        reason: 'PC-AUTH-048 independent reviewer approval',
      },
    }),
    'approve target override as independent reviewer',
  );
  const release = await apiData<{ releasePid: string }>(
    await page.request.post(`/api/authoring/change-sets/${session.changeSetPid}/publish`, {
      headers: { 'X-Environment': envCode },
      data: { expectedRevision: prepared.revision },
    }),
    'publish target override',
  );
  return { releasePid: release.releasePid, overridePid: session.ownership.overridePid };
}

function findBlock(
  blocks: Array<{ id: string; blockType: string; blocks?: Array<any> }>,
  blockType: string,
): { id: string; blockType: string } | null {
  for (const block of blocks ?? []) {
    if (block.blockType === blockType) return block;
    const nested = findBlock(block.blocks ?? [], blockType);
    if (nested) return nested;
  }
  return null;
}

function decisionLabel(decision: PromotionDecision): string {
  return {
    REBASE: '重放本地变更',
    BACKPORT: '回迁源环境',
    KEEP_OVERRIDE: '保留租户覆盖',
    OVERWRITE: '用发布版本覆盖',
  }[decision];
}

async function assertPromotionOutcome(fixture: PromotionFixture): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const result = await client.query<{
      promotion_status: string;
      drift_status: string;
      drift_decision: string;
      execution_status: string;
      execution_pid: string | null;
      release_status: string;
      override_status: string;
      event_types: string[];
    }>(
      `SELECT p.status AS promotion_status, u.drift_status, u.drift_decision,
              u.drift_execution_status AS execution_status, u.drift_execution_pid AS execution_pid,
              r.status AS release_status, o.status AS override_status,
              ARRAY(SELECT e.event_type FROM ab_promotion_drift_event e
                     WHERE e.promotion_unit_id=u.id ORDER BY e.id) AS event_types
         FROM ab_promotion p
         JOIN ab_promotion_unit u ON u.promotion_id=p.id AND u.pid=$2
         JOIN ab_authoring_release r ON r.pid=$3
         JOIN ab_authoring_tenant_override o ON o.pid=$4
        WHERE p.pid=$1`,
      [fixture.promotionPid, fixture.unitPid, fixture.targetReleasePid, fixture.overridePid],
    );
    const row = result.rows[0]!;
    expect(row.drift_decision).toBe(fixture.decision);
    expect(row.event_types).toEqual(expect.arrayContaining(['DETECTED', 'DECIDED', 'EXECUTED']));
    if (fixture.decision === 'REBASE' || fixture.decision === 'OVERWRITE') {
      expect(row).toMatchObject({
        promotion_status: 'APPLIED',
        drift_status: 'APPLIED',
        execution_status: 'APPLIED',
        release_status: 'SUPERSEDED',
        override_status: 'SUPERSEDED',
      });
      expect(row.event_types).toContain('APPLIED');
    } else if (fixture.decision === 'BACKPORT') {
      expect(row).toMatchObject({
        promotion_status: 'DRAFT',
        drift_status: 'RESOLVED',
        execution_status: 'BACKPORTED',
        release_status: 'ACTIVE',
        override_status: 'ACTIVE',
      });
      const reverse = await client.query<{ count: string }>(
        `SELECT COUNT(*)::text AS count FROM ab_promotion
          WHERE parent_promotion_pid=$1 AND pid=$2 AND deleted_flag=FALSE`,
        [fixture.promotionPid, row.execution_pid],
      );
      expect(reverse.rows[0]?.count).toBe('1');
    } else {
      expect(row).toMatchObject({
        promotion_status: 'DRAFT',
        drift_status: 'RESOLVED',
        execution_status: 'DEFERRED',
        release_status: 'ACTIVE',
        override_status: 'ACTIVE',
      });
    }
  } finally {
    await client.end();
  }
}

async function cleanupPromotionFixtures(fixtures: PromotionFixture[]): Promise<void> {
  if (fixtures.length === 0) return;
  const prefixes = fixtures.map((fixture) => fixture.prefix);
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    await client.query('BEGIN');
    await client.query(`UPDATE ab_promotion SET deleted_flag=TRUE WHERE pid = ANY($1::text[])`, [
      fixtures.map((fixture) => fixture.promotionPid),
    ]);
    await client.query(
      `UPDATE ab_page_schema SET deleted_flag=TRUE, is_current=FALSE WHERE page_key = ANY($1::text[])`,
      [prefixes.map((prefix) => `${prefix}_src_orders`)],
    );
    await client.query(
      `UPDATE ab_page_schema SET deleted_flag=TRUE, is_current=FALSE WHERE name LIKE ANY($1::text[])`,
      [prefixes.map((prefix) => `${prefix}%`)],
    );
    await client.query(
      `UPDATE ab_environment SET deleted_flag=TRUE WHERE code LIKE ANY($1::text[])`,
      [prefixes.map((prefix) => `${prefix}%`)],
    );
    await client.query('COMMIT');
  } catch (error) {
    await client.query('ROLLBACK');
    throw error;
  } finally {
    await client.end();
  }
}

async function apiData<T>(
  response: { ok(): boolean; status(): number; text(): Promise<string> },
  label: string,
): Promise<T> {
  const text = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`${label}: non-JSON HTTP ${response.status()}: ${text}`);
  }
  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body.data as T;
}
