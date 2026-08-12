import { test, expect, type Browser, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Client as PgClient } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { loginViaUI } from '../../helpers/wd-fixtures';

const SOURCE_PAGE_KEY = 'e2et_record_list';
const SCREENSHOT_DIR = resolve(
  process.env.CONTEXTUAL_AUTHORING_SCREENSHOT_DIR ?? 'test-results/contextual-authoring',
);

type ApiEnvelope<T> = {
  code?: number | string;
  data?: T;
  message?: string;
};

type ReadableHttpResponse = {
  ok(): boolean;
  status(): number;
  text(): Promise<string>;
};

type AuthoringSession = {
  sessionPid: string;
  changeSetPid: string;
  pagePid?: string;
  revision: number;
  riskLevel?: string;
  publishPolicy?: string;
  validationState: string;
  impactState: string;
  changeSetStatus: string;
  approvalState?: string;
  publishState: string;
  snapshot: Record<string, unknown>;
};

type PatchResult = {
  session: AuthoringSession;
};

type Release = {
  releasePid: string;
  changeSetPid: string;
  changeSetRevision: number;
  previousReleasePid: string | null;
  status: string;
  channelVersion: number;
};

type RuntimeTuple = {
  releasePid: string | null;
  channelVersion: number;
  cacheKey: string;
  density: string | undefined;
};

type GatePage = {
  pid: string;
  pageKey: string;
  route: string;
  title: string;
  recordMarker: string;
  menuId: string | number;
};

type PermissionRecord = { pid: string; code: string };
type RoleRecord = { pid: string; code: string };
type ReviewWorkspace = { session: AuthoringSession };

const REVIEWER = {
  roleCode: 'e2e_authoring_release_reviewer',
  roleName: 'Authoring release independent reviewer',
  email: 'e2e-authoring-release-reviewer@test.com',
  permissions: ['page.page.read', 'meta.publish.read', 'meta.publish.update'],
} as const;

let gatePage: GatePage;

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring release PC golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 300_000 });

  test.beforeAll(async ({ browser }) => {
    gatePage = await createGatePageFromPublishedRuntime(browser);
  });

  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test.afterAll(async ({ browser }) => {
    if (gatePage) await cleanupGatePage(browser, gatePage);
  });

  test('PC-AUTH-017 @critical — failed publish stays retryable and release history rolls back atomically', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });

    const first = await publishDensityRevisionViaUi(page, false);
    expect(first.release.status).toBe('ACTIVE');
    await page.getByTestId('studio-governance-close').click();
    const sourceMenuLink = page.locator('nav').locator(`a[href="${gatePage.route}"]`).first();
    await expect(sourceMenuLink).toBeVisible();
    await sourceMenuLink.click();
    await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
    await expect(page.getByRole('main').first().getByText(gatePage.recordMarker)).toBeVisible();

    const second = await publishDensityRevisionViaUi(page, true);
    expect(second.release.status).toBe('ACTIVE');
    expect(second.release.previousReleasePid).toBe(first.release.releasePid);
    expect(second.release.channelVersion).toBe(first.release.channelVersion + 1);

    const releaseHistory = page.getByTestId('authoring-release-history');
    await expect(releaseHistory.getByTestId('authoring-rollback-eligibility')).toContainText(
      '可回滚到 immediate previous Release',
    );
    const refreshResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        apiPath(response.url()) ===
          `/api/authoring/change-sets/${second.release.changeSetPid}/releases`,
    );
    await releaseHistory.getByTestId('authoring-release-refresh').click();
    expect((await refreshResponse).status()).toBe(200);
    await expect(
      releaseHistory.getByTestId(`authoring-release-${second.release.releasePid}`),
    ).toContainText('当前活动');
    await expect(releaseHistory.getByTestId('authoring-rollback-prepare')).toBeVisible();
    await releaseHistory.getByTestId('authoring-rollback-prepare').click();
    const rollbackConfirmation = releaseHistory.getByTestId('authoring-rollback-confirmation');
    await expect(rollbackConfirmation).toContainText('不会伪称撤销外部副作用');
    await expect(releaseHistory.getByTestId('authoring-rollback-confirm')).toBeDisabled();
    await rollbackConfirmation
      .getByLabel(/回滚原因/)
      .fill('PC 门禁：恢复 immediate previous Release');
    await expect(releaseHistory.getByTestId('authoring-rollback-confirm')).toBeEnabled();
    const rollbackResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) === `/api/authoring/releases/${second.release.releasePid}/rollback`,
    );
    await releaseHistory.getByTestId('authoring-rollback-confirm').click();
    const rolledBack = await expectApiData<Release>(
      await rollbackResponse,
      'rollback active release',
    );
    expect(rolledBack.releasePid).toBe(first.release.releasePid);
    expect(rolledBack.channelVersion).toBe(second.release.channelVersion + 1);
    await expect(releaseHistory.getByRole('status')).toContainText('活动 Release 已原子切换');
    await expect(
      releaseHistory.getByTestId(`authoring-release-${first.release.releasePid}`),
    ).toContainText('当前活动');
    await expect(
      releaseHistory.getByTestId(`authoring-release-${second.release.releasePid}`),
    ).toContainText('已回滚');
    await expect(releaseHistory.getByTestId('authoring-rollback-prepare')).toHaveCount(0);

    const runtimeAfterRollback = await loadRuntimeTuple(page);
    expect(runtimeAfterRollback.releasePid).toBe(first.release.releasePid);
    expect(runtimeAfterRollback.channelVersion).toBe(rolledBack.channelVersion);
    expect(runtimeAfterRollback.density).toBe(first.density);
    expect(runtimeAfterRollback.cacheKey).toContain(first.release.releasePid);

    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-017-release-history-rollback.png'),
      fullPage: true,
    });
  });

  test('PC-AUTH-028 @critical — governed new page stays private until independent review and atomic publish', async ({
    page,
    browser,
  }) => {
    await page.setViewportSize({ width: 1440, height: 1000 });
    await ensureReviewer(page);
    const source = await enterAuthoringFromMenu(page);
    await page.getByRole('button', { name: '新页面 / 菜单' }).click();
    const explain = page.getByRole('dialog', { name: '在应用设计中心创建页面' });
    await expect(explain).toContainText('新页面会改变页面树、路由和发布资源');
    await explain.getByRole('button', { name: '继续到应用设计中心' }).click();

    const wizard = page.getByTestId('new-page-workspace-wizard');
    await expect(wizard).toBeVisible();
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const pageKey = `authoring_new_gate_${suffix}`;
    const route = `/${pageKey.replaceAll('_', '-')}`;
    const title = `Governed New Page ${suffix}`;
    await wizard.getByLabel('页面标题').fill(title);
    await wizard.getByLabel('页面标识').fill(pageKey);
    await wizard.getByLabel('业务模型').selectOption('e2et_record');
    const parentSelect = wizard.getByLabel('父菜单');
    await expect.poll(() => parentSelect.locator('option').count()).toBeGreaterThan(1);
    await parentSelect.selectOption(
      (await parentSelect.locator('option').nth(1).getAttribute('value'))!,
    );
    await wizard.getByLabel('访问权限').selectOption('page.page.read');

    const createdResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        apiPath(response.url()) ===
          `/api/authoring/sessions/${source.sessionPid}/new-page-workspaces`,
    );
    await wizard.getByRole('button', { name: '创建并进入页面设计' }).click();
    const created = await expectApiData<AuthoringSession>(
      await createdResponse,
      'create governed new-page workspace',
    );
    expect(created.riskLevel).toBe('L3');
    expect(created.publishPolicy).toBe('STUDIO_APPROVAL');
    expect(created.pagePid).toBeTruthy();
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

    const runtimeProbe = await browser.newContext({ storageState: { cookies: [], origins: [] } });
    const probePage = await runtimeProbe.newPage();
    await login(probePage);
    try {
      await expect(page.locator('nav').locator(`a[href="${route}"]`)).toHaveCount(0);
      const unpublished = await probePage.request.get(`/api/pages/key/${pageKey}`);
      expect(unpublished.status(), 'new PageSchema must not exist before publish').toBe(404);
      const missingMenu = await expectApiData<unknown>(
        await probePage.request.get(`/api/menu/by-path?path=${encodeURIComponent(route)}`),
        'probe unpublished route menu',
      );
      expect(missingMenu).toBeNull();
      await probePage.goto(route, { waitUntil: 'domcontentloaded' });
      await expect(probePage.getByText(gatePage.recordMarker)).toHaveCount(0);

      await page.getByTestId('resource-tab-blocks').click();
      await expect(page.getByTestId('palette-add-table')).toBeEnabled();
      await page.getByTestId('palette-add-table').click();
      await expect(page.getByTestId('designer-dirty-state')).toContainText('未保存');
      await page.getByTestId('resource-tab-fields').click();
      await expect(page.getByTestId('model-field-e2et_name')).toBeEnabled();
      await page.getByTestId('model-field-e2et_name').click();
      await expect(page.getByTestId('model-field-e2et_name')).toHaveAttribute('data-used', 'true');

      const saveResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) ===
            `/api/authoring/sessions/${created.sessionPid}/studio-batches`,
      );
      await page.getByTestId('designer-save').click();
      const saved = await expectApiData<PatchResult>(await saveResponse, 'save new-page design');
      await expect(page.getByTestId('designer-dirty-state')).toContainText('已保存');

      const prepareResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/sessions/${created.sessionPid}/prepare`,
      );
      await page.getByTestId('studio-prepare-submit').click();
      const prepared = await expectApiData<AuthoringSession>(
        await prepareResponse,
        'prepare new page exact revision',
      );
      expect(prepared.revision).toBe(saved.session.revision);
      await expect(page.getByTestId('studio-prepare-submit')).toHaveText('提交评审');
      const submitResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          apiPath(response.url()) === `/api/authoring/sessions/${created.sessionPid}/submit`,
      );
      await page.getByTestId('studio-prepare-submit').click();
      const submitted = await expectApiData<{
        status: string;
        publishState: string;
      }>(await submitResponse, 'submit new page for independent review');
      expect(submitted.status).toBe('IN_REVIEW');

      const reviewer = await openReviewer(browser);
      try {
        const reviewResponse = reviewer.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/change-sets/${created.changeSetPid}/review-workspaces`,
        );
        await reviewer.goto(
          `/unified-designer?reviewChangeSetId=${encodeURIComponent(created.changeSetPid)}`,
          { waitUntil: 'domcontentloaded' },
        );
        const review = await expectApiData<ReviewWorkspace>(
          await reviewResponse,
          'open bounded new-page review workspace',
        );
        expect(review.session.revision).toBe(prepared.revision);
        await reviewer
          .getByTestId('authoring-governance-reason')
          .fill('PC 门禁：独立审核受治理新页面');
        const approvalResponse = reviewer.waitForResponse(
          (response) =>
            response.request().method() === 'POST' &&
            apiPath(response.url()) ===
              `/api/authoring/change-sets/${created.changeSetPid}/approve`,
        );
        await reviewer.getByTestId('authoring-governance-approve').click();
        await expectApiData(await approvalResponse, 'approve governed new page');
      } finally {
        await reviewer.context().close();
      }

      const published = await expectApiData<Release>(
        await page.request.post(`/api/authoring/change-sets/${created.changeSetPid}/publish`, {
          data: { expectedRevision: prepared.revision },
        }),
        'publish independently approved new page',
      );
      expect(published.status).toBe('ACTIVE');
      expect(published.channelVersion).toBe(1);

      await probePage.goto('/', { waitUntil: 'domcontentloaded' });
      const newMenu = probePage.locator('nav').locator(`a[href="${route}"]`).first();
      await expect(newMenu).toBeVisible({ timeout: 15_000 });
      await newMenu.click();
      await expect(probePage).toHaveURL(new RegExp(`${route}$`));
      const runtime = probePage.getByRole('main').first();
      await expect(runtime.getByRole('heading', { name: title })).toBeVisible();
      await expect(runtime.getByText(gatePage.recordMarker)).toBeVisible();
      const deployedPage = await expectApiData<Record<string, unknown>>(
        await probePage.request.get(`/api/pages/key/${pageKey}`),
        'read deployed new page release identity',
      );
      expect((deployedPage.runtime as Record<string, unknown>).source).toBe('AUTHORING_RELEASE');
      expect((deployedPage.runtime as Record<string, unknown>).channelVersion).toBe(1);

      const noModelReadReviewer = await openReviewer(browser);
      try {
        const denied = await noModelReadReviewer.request.get(
          '/api/dynamic/e2et_record/list?pageNum=1&pageSize=20',
        );
        expect(denied.status(), 'page permission must not imply model read').toBe(403);
        await noModelReadReviewer.goto(route, { waitUntil: 'domcontentloaded' });
        await expect(noModelReadReviewer.getByText(gatePage.recordMarker)).toHaveCount(0);
      } finally {
        await noModelReadReviewer.context().close();
      }

      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await probePage.screenshot({
        path: resolve(SCREENSHOT_DIR, 'pc-auth-028-new-page-published.png'),
        fullPage: true,
      });
    } finally {
      await runtimeProbe.close();
      await cleanupMaterializedNewPage(pageKey);
    }
  });
});

async function createGatePageFromPublishedRuntime(browser: Browser): Promise<GatePage> {
  const recordMarker = await seedReleaseGateRecord();
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    const source = await expectApiData<Record<string, unknown>>(
      await page.request.get(`/api/pages/key/${SOURCE_PAGE_KEY}`),
      'load published source page',
    );
    const suffix = `${Date.now().toString(36)}_${process.pid}`;
    const menuId = Date.now();
    const pageKey = `authoring_release_ui_gate_${suffix}`;
    const route = `/authoring-release-ui-gate-${suffix.replaceAll('_', '-')}`;
    const title = `Authoring Release UI Gate ${suffix}`;
    const created = await expectApiData<{ pid: string }>(
      await page.request.post('/api/pages', {
        data: {
          pageKey,
          modelCode: source.modelCode,
          name: title,
          title,
          description: 'Persistent browser evidence for governed release and rollback',
          kind: source.kind,
          profile: source.profile,
          layout: source.layout ?? {},
          blocks: source.blocks,
          schemaVersion: source.schemaVersion,
          isTemplate: false,
          sortWeight: 9999,
          semver: '1.0.0',
        },
      }),
      'create release UI gate page',
    );
    await expectApiData(
      await page.request.post(`/api/pages/${created.pid}/publish`),
      'publish release UI gate page',
    );
    const menu = await expectApiData<{ id: string | number }>(
      await page.request.post('/api/menu/create', {
        data: {
          id: menuId,
          pid: `menu_${suffix}`,
          code: pageKey,
          name: title,
          path: route,
          component: 'dynamic-page',
          type: 1,
          permissionCode: 'page.page.read',
          visible: true,
          orderNo: 9999,
          pageKey,
          pagePid: created.pid,
          status: 'active',
          deletedFlag: false,
        },
      }),
      'mount release UI gate page in the menu',
    );
    expect(Number(menu.id), 'safe release UI gate menu id').toBe(menuId);
    return { pid: created.pid, pageKey, route, title, recordMarker, menuId };
  } finally {
    await context.close();
  }
}

async function cleanupGatePage(browser: Browser, target: GatePage): Promise<void> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    await expectApiData(
      await page.request.delete(`/api/menu/${encodeURIComponent(String(target.menuId))}`),
      'delete release UI gate menu',
    );
    await expectApiData(
      await page.request.post(`/api/pages/${encodeURIComponent(target.pid)}/unpublish`),
      'unpublish release UI gate page',
    );
    await expectApiData(
      await page.request.delete(`/api/pages/${encodeURIComponent(target.pid)}`),
      'delete release UI gate page',
    );
  } finally {
    await context.close();
    const client = new PgClient(PG_CONN);
    await client.connect();
    try {
      await client.query('DELETE FROM mt_e2et_record WHERE e2et_name = $1', [target.recordMarker]);
      const residue = await client.query<{ active_menus: string; active_pages: string }>(
        `SELECT
           (SELECT COUNT(*)::text FROM ab_menu
             WHERE code = $1 AND deleted_flag = FALSE) AS active_menus,
           (SELECT COUNT(*)::text FROM ab_page_schema
             WHERE page_key = $1 AND deleted_flag = FALSE) AS active_pages`,
        [target.pageKey],
      );
      expect(residue.rows[0]).toEqual({ active_menus: '0', active_pages: '0' });
    } finally {
      await client.end();
    }
  }
}

async function ensureReviewer(adminPage: Page): Promise<void> {
  const roles = await expectApiData<RoleRecord[]>(
    await adminPage.request.get('/api/roles/all'),
    'load roles for release reviewer',
  );
  let role = roles.find((candidate) => candidate.code === REVIEWER.roleCode);
  if (!role) {
    role = await expectApiData<RoleRecord>(
      await adminPage.request.post('/api/roles', {
        data: {
          code: REVIEWER.roleCode,
          name: REVIEWER.roleName,
          description: 'PC release golden persona: independent reviewer without model read',
          type: 'custom',
        },
      }),
      'create release reviewer role',
    );
  }

  const permissions = (
    await Promise.all(
      ['function', 'operation', 'data', 'model'].map(async (resourceType) =>
        expectApiData<PermissionRecord[]>(
          await adminPage.request.get(`/api/permissions/resource-type/${resourceType}`),
          `load ${resourceType} permissions for release reviewer`,
        ),
      ),
    )
  ).flat();
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  const missing = REVIEWER.permissions.filter((code) => !byCode.has(code));
  expect(missing, `missing release reviewer permissions: ${missing.join(', ')}`).toEqual([]);
  await expectApiData<boolean>(
    await adminPage.request.post(`/api/roles/${role.pid}/permissions`, {
      data: REVIEWER.permissions.map((code) => byCode.get(code)!),
    }),
    'assign release reviewer permissions',
  );

  const loginProbe = await adminPage.request.post('/api/auth/login', {
    data: { email: REVIEWER.email, password: DEFAULT_TEST_ACCOUNT.password },
  });
  if (!loginProbe.ok()) {
    await expectApiData(
      await adminPage.request.post('/api/admin/users', {
        data: {
          email: REVIEWER.email,
          displayName: REVIEWER.roleName,
          initialPassword: DEFAULT_TEST_ACCOUNT.password,
          roleCodes: [REVIEWER.roleCode],
          sendInviteEmail: false,
        },
      }),
      'create release reviewer user',
    );
  }
  const members = await expectApiData<Array<{ email?: string; memberPid?: string }>>(
    await adminPage.request.get(
      `/api/org/members/unlinked?keyword=${encodeURIComponent(REVIEWER.email)}`,
    ),
    'resolve release reviewer member',
  );
  const member = members.find((candidate) => candidate.email === REVIEWER.email);
  expect(member?.memberPid, 'release reviewer member pid').toBeTruthy();
  await expectApiData<boolean>(
    await adminPage.request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: member!.memberPid, roleCodes: [REVIEWER.roleCode] },
    }),
    'activate release reviewer role',
  );
}

async function openReviewer(browser: Browser): Promise<Page> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, REVIEWER.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
  return page;
}

async function cleanupMaterializedNewPage(pageKey: string): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    await client.query('BEGIN');
    await client.query(
      `UPDATE ab_menu
          SET deleted_flag = TRUE, updated_at = NOW()
        WHERE code = $1 AND deleted_flag = FALSE`,
      [pageKey],
    );
    await client.query(
      `UPDATE ab_page_schema
          SET deleted_flag = TRUE, updated_at = NOW()
        WHERE page_key = $1 AND deleted_flag = FALSE`,
      [pageKey],
    );
    await client.query('COMMIT');
    const residue = await client.query<{ active_menus: string; active_pages: string }>(
      `SELECT
         (SELECT COUNT(*)::text FROM ab_menu
           WHERE code = $1 AND deleted_flag = FALSE) AS active_menus,
         (SELECT COUNT(*)::text FROM ab_page_schema
           WHERE page_key = $1 AND deleted_flag = FALSE) AS active_pages`,
      [pageKey],
    );
    expect(residue.rows[0]).toEqual({ active_menus: '0', active_pages: '0' });
  } catch (error) {
    await client.query('ROLLBACK').catch(() => {});
    throw error;
  } finally {
    await client.end();
  }
}

async function publishDensityRevisionViaUi(
  page: Page,
  exerciseFailure: boolean,
): Promise<{ release: Release; density: string }> {
  const opened = await enterAuthoringFromMenu(page);
  const { density } = await stageDensityEdit(page, opened);
  const saveResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'PATCH' &&
      apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/patches`,
  );
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const saved = await expectApiData<PatchResult>(await saveResponse, 'save density revision');
  await expect(page.getByText('0 项未保存')).toBeVisible();

  await page.getByRole('button', { name: '高级设置', exact: true }).click();
  const explain = page.getByRole('dialog', { name: '进入应用设计中心' });
  await expect(explain).toContainText('系统不会猜测影响范围');
  await expect(explain).toContainText('当前 ChangeSet、选择对象、返回位置');
  await expect(explain).toContainText('应用设计中心会重新检查权限');
  await explain.getByRole('button', { name: '继续到应用设计中心' }).click();
  await expect(page).toHaveURL(/\/unified-designer\?authoringSession=/);
  await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();

  const prepareResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/prepare`,
  );
  await page.getByTestId('studio-prepare-submit').click();
  const prepared = await expectApiData<AuthoringSession>(
    await prepareResponse,
    'validate and analyze exact revision',
  );
  expect(prepared.validationState).toBe('VALID');
  expect(prepared.impactState).toBe('KNOWN');
  await expect(page.getByTestId('studio-prepare-submit')).toHaveText('提交评审');

  const submitResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === `/api/authoring/sessions/${opened.sessionPid}/submit`,
  );
  await page.getByTestId('studio-prepare-submit').click();
  const submitted = await expectApiData<{
    status: string;
    publishState: string;
  }>(await submitResponse, 'submit directly publishable revision');
  expect(submitted.status).toBe('APPROVED');
  expect(submitted.publishState).toBe('READY');
  await expect(page.getByRole('button', { name: /当前状态：APPROVED/ })).toBeVisible();

  await page.getByTestId('studio-governance-open').click();
  const drawer = page.getByTestId('studio-governance-drawer');
  await expect(drawer).toBeVisible();
  const publishPath = `/api/authoring/change-sets/${opened.changeSetPid}/publish`;

  if (exerciseFailure) {
    const beforeFailure = await loadRuntimeTuple(page);
    await page.route(`**${publishPath}`, async (route) => {
      const payload = route.request().postDataJSON() as { expectedRevision: number };
      await route.continue({
        headers: { ...route.request().headers(), 'content-type': 'application/json' },
        postData: JSON.stringify({ expectedRevision: payload.expectedRevision - 1 }),
      });
    });
    const failedResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' && apiPath(response.url()) === publishPath,
    );
    await drawer.getByTestId('authoring-governance-publish').click();
    const failed = await failedResponse;
    expect(failed.status()).toBe(409);
    await expect(drawer.getByRole('alert')).toBeVisible();
    await expect(drawer.getByTestId('authoring-governance-publish')).toBeEnabled();
    await page.unroute(`**${publishPath}`);
    const afterFailure = await loadRuntimeTuple(page);
    expect(afterFailure).toEqual(beforeFailure);
  }

  const publishResponse = page.waitForResponse(
    (response) => response.request().method() === 'POST' && apiPath(response.url()) === publishPath,
  );
  await drawer.getByTestId('authoring-governance-publish').click();
  const release = await expectApiData<Release>(await publishResponse, 'publish exact revision');
  expect(release.changeSetRevision).toBe(saved.session.revision);
  await expect(page.getByRole('button', { name: /当前状态：PUBLISHED/ })).toBeVisible();
  await expect(drawer.getByTestId('authoring-governance-publish')).toHaveCount(0);
  await expect(drawer.getByTestId(`authoring-release-${release.releasePid}`)).toContainText(
    '当前活动',
  );
  await expect(
    drawer.getByText(`v${release.channelVersion}`, { exact: true }).first(),
  ).toBeVisible();
  return { release, density };
}

async function enterAuthoringFromMenu(page: Page): Promise<AuthoringSession> {
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
  const menuLink = page.locator('nav').locator(`a[href="${gatePage.route}"]`).first();
  await expect(menuLink).toBeVisible({ timeout: 15_000 });
  await menuLink.click();
  await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
  const runtime = page.getByRole('main').first();
  await expect(runtime.getByRole('heading', { name: gatePage.title })).toBeVisible();
  await expect(runtime.getByText(gatePage.recordMarker)).toBeVisible();

  const sessionResponse = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      apiPath(response.url()) === '/api/authoring/sessions',
  );
  await runtime.getByRole('button', { name: '配置此页' }).click();
  const session = await expectApiData<AuthoringSession>(
    await sessionResponse,
    'enter release gate authoring from its menu',
  );
  await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();
  return session;
}

async function seedReleaseGateRecord(): Promise<string> {
  const marker = `AUTHORING-RELEASE-REAL-${Date.now().toString(36)}`;
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    const tenants = await client.query<{ tenant_id: string }>(
      `SELECT DISTINCT tenant_id
         FROM ab_meta_model
        WHERE code = 'e2et_record'
          AND is_current = TRUE
          AND deleted_flag = FALSE`,
    );
    expect(tenants.rows.length, `${SOURCE_PAGE_KEY} model tenant`).toBeGreaterThan(0);
    for (const { tenant_id: tenantId } of tenants.rows) {
      await client.query(
        `INSERT INTO mt_e2et_record
           (pid, tenant_id, e2et_name, e2et_status, e2et_count, created_at, updated_at)
         VALUES ($1, $2, $3, 'active', 1, NOW(), NOW())
         ON CONFLICT (pid) DO UPDATE SET
           e2et_name = EXCLUDED.e2et_name,
           e2et_status = EXCLUDED.e2et_status,
           e2et_count = EXCLUDED.e2et_count,
           updated_at = NOW()`,
        [`authrel_${tenantId}`, tenantId, marker],
      );
    }
    return marker;
  } finally {
    await client.end();
  }
}

async function stageDensityEdit(
  page: Page,
  session: AuthoringSession,
): Promise<{ density: string }> {
  const table = findTableBlock(session.snapshot);
  expect(table?.id, 'table block id in release UI gate').toBeTruthy();
  const current = readObjectPath(table!, '/props/density');
  const density = current === 'compact' ? 'comfortable' : 'compact';
  await page.getByTestId('authoring-outline-open').click();
  await page.getByTestId(`authoring-outline-${String(table!.id)}`).click();
  await page.getByRole('button', { name: '关闭页面大纲' }).click();
  await page.getByTestId('authoring-inspector-open').click();
  const editor = page.getByTestId('authoring-property-/props/density').locator('input');
  await expect(editor).toBeVisible();
  await editor.fill(density);
  await expect(page.getByText('1 项未保存')).toBeVisible();
  return { density };
}

async function loadRuntimeTuple(page: Page): Promise<RuntimeTuple> {
  const runtimePage = await expectApiData<Record<string, unknown>>(
    await page.request.get(`/api/pages/key/${gatePage.pageKey}`),
    'load deployed runtime tuple',
  );
  const runtime = (runtimePage.runtime ?? {}) as Record<string, unknown>;
  const table = findTableBlock(runtimePage);
  return {
    releasePid: typeof runtime.releasePid === 'string' ? runtime.releasePid : null,
    channelVersion: Number(runtime.channelVersion ?? 0),
    cacheKey: String(runtime.cacheKey ?? ''),
    density: readObjectPath(table!, '/props/density') as string | undefined,
  };
}

function findTableBlock(value: unknown): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findTableBlock(item);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  if (record.type === 'table' || record.blockType === 'table') return record;
  for (const child of Object.values(record)) {
    const found = findTableBlock(child);
    if (found) return found;
  }
  return null;
}

function readObjectPath(root: Record<string, unknown>, path: string): unknown {
  return path
    .split('/')
    .filter(Boolean)
    .reduce<unknown>((value, segment) => {
      if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
      return (value as Record<string, unknown>)[segment];
    }, root);
}

async function login(page: Page): Promise<void> {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function expectApiData<T>(response: ReadableHttpResponse, label: string): Promise<T> {
  const text = await response.text();
  let body: ApiEnvelope<T>;
  try {
    body = JSON.parse(text) as ApiEnvelope<T>;
  } catch {
    throw new Error(`${label}: non-JSON response HTTP ${response.status()}: ${text}`);
  }
  expect(response.ok(), `${label}: HTTP ${response.status()}: ${text}`).toBe(true);
  expect(String(body.code ?? '0'), `${label}: API envelope ${text}`).toBe('0');
  return body.data as T;
}

function apiPath(url: string): string {
  return new URL(url).pathname;
}
