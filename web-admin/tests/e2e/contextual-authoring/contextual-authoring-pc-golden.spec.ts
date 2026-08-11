import { test, expect, type APIResponse, type Browser, type Page } from '@playwright/test';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { loginViaUI } from '../../helpers/wd-fixtures';

const RUNTIME_ROUTE = '/production-exception-list-v4';
const RUNTIME_ONLY_ROLE = 'e2e_contextual_authoring_runtime_only';
const RUNTIME_ONLY_EMAIL = 'e2e-contextual-authoring-runtime-only@test.com';

type ApiEnvelope<T> = {
  code?: number | string;
  data?: T;
  message?: string;
};

type PermissionRecord = {
  pid: string;
  code: string;
};

type RoleRecord = {
  pid: string;
  code: string;
};

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring PC Web golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 60_000 });

  test.beforeEach(async ({ page }) => {
    await login(page);
  });

  test('PC-AUTH-001 @critical @smoke — menu to runtime to contextual authoring', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await openRuntimeFromMenu(page);

    const runtimeMain = page.getByRole('main').first();
    await expect(
      runtimeMain.getByRole('heading', { name: 'Production Exception List V4' }),
    ).toBeVisible();
    await expect(runtimeMain.getByText('EXC-V4-REAL-001')).toBeVisible();
    await runtimeMain.getByRole('button', { name: '配置此页' }).click();

    const surface = page.getByTestId('contextual-authoring-surface');
    const canvas = page.getByTestId('contextual-authoring-canvas');
    await expect(surface).toBeVisible();
    await expect(surface).toHaveAttribute('data-read-only', 'false');
    await expect(page.getByRole('note', { name: 'ChangeSet 风险与发布策略' })).toContainText('L0');
    await expect(page.getByTestId('authoring-outline-open')).toBeVisible();
    await expect(page.getByTestId('authoring-inspector-open')).toBeVisible();

    const canvasBox = await canvas.boundingBox();
    expect(canvasBox?.width ?? 0, '1280px 下运行画布不应被双侧栏挤成窄列').toBeGreaterThan(700);

    await page.getByTestId('authoring-inspector-open').focus();
    await page.keyboard.press('Enter');
    const inspector = page.getByTestId('authoring-inspector');
    await expect(inspector).toBeVisible();
    await expect(inspector).toContainText('Production Exception List V4');
    await expect(inspector).toContainText('页面');
    await testInfo.attach('contextual-authoring-1280', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });

  test('PC-AUTH-002 @critical — explain handoff, embedded Studio and governance drawer', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openRuntimeFromMenu(page);
    await page.getByRole('main').first().getByRole('button', { name: '配置此页' }).click();
    await expect(page.getByTestId('contextual-authoring-surface')).toBeVisible();

    await page.getByTestId('authoring-inspector-open').click();
    await page.getByRole('button', { name: '高级设置' }).click();
    const explain = page.getByRole('dialog', { name: '进入应用设计中心' });
    await expect(explain).toContainText('页面级结构、路由和资源关系');
    await expect(explain).toContainText('URL 不包含 pagePid、recordPid 或业务筛选');
    await explain.getByRole('button', { name: '继续到应用设计中心' }).click();

    await expect(page).toHaveURL(/\/unified-designer\?authoringSession=/);
    const sessionPid = new URL(page.url()).searchParams.get('authoringSession');
    expect(sessionPid).toBeTruthy();

    const studio = page.getByTestId('studio-handoff-context');
    await expect(studio).toBeVisible();
    await expect(studio).toContainText('应用设计中心');
    await expect(studio).toContainText('修订 r');
    await expect(studio).toContainText('写回同一隔离草稿');
    await expect(page.getByTestId('unified-designer-workbench')).toHaveCount(1);
    await expect(page.getByTestId('unified-designer-workbench')).toBeVisible();
    expect(await studio.innerText()).not.toContain(sessionPid!);

    const workbenchBox = await page.getByTestId('unified-designer-workbench').boundingBox();
    expect(workbenchBox?.height ?? 0, 'Studio 工作台应占据剩余可视区').toBeGreaterThan(600);

    const drawer = page.getByTestId('studio-governance-drawer');
    await expect(drawer).toBeHidden();
    await page.getByTestId('studio-governance-open').focus();
    await page.keyboard.press('Enter');
    await expect(drawer).toBeVisible();
    await expect(drawer).toContainText('发布历史');
    await expect(drawer).not.toContainText(sessionPid!);
    await testInfo.attach('studio-governance-1440', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
    await page.getByTestId('studio-governance-close').click();
    await expect(drawer).toBeHidden();
  });

  test('PC-AUTH-003 @critical — wide desktop keeps persistent context panels', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1600, height: 900 });
    await openRuntimeFromMenu(page);
    await page.getByRole('main').first().getByRole('button', { name: '配置此页' }).click();

    await expect(page.getByTestId('authoring-outline')).toBeVisible();
    await expect(page.getByTestId('authoring-inspector')).toBeVisible();
    await expect(page.getByTestId('authoring-outline-open')).toBeHidden();
    await expect(page.getByTestId('authoring-inspector-open')).toBeHidden();

    const canvasBox = await page.getByTestId('contextual-authoring-canvas').boundingBox();
    expect(canvasBox?.width ?? 0, '1600px 下持久双侧栏仍须保留足够画布').toBeGreaterThan(650);
    await testInfo.attach('contextual-authoring-1600', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });

  test('PC-AUTH-004 @critical — runtime-only role can use the page but cannot enter authoring', async ({
    page,
    browser,
  }) => {
    await ensureRuntimeOnlyPersona(page);

    const actor = await openAsRuntimeOnlyUser(browser);
    try {
      await actor.page.setViewportSize({ width: 1280, height: 720 });
      await openRuntimeFromMenu(actor.page);

      const runtimeMain = actor.page.getByRole('main').first();
      await expect(runtimeMain.getByText('EXC-V4-REAL-001')).toBeVisible();
      await expect(runtimeMain.getByRole('button', { name: '配置此页' })).toHaveCount(0);
      await expect(actor.page.getByTestId('contextual-authoring-enter')).toHaveCount(0);

      const denied = await actor.page.request.post('/api/authoring/sessions', {
        data: {
          pagePid: 'production-exception-list-v4',
          interactionContext: { route: RUNTIME_ROUTE },
        },
      });
      expect(denied.status(), '后端必须独立拒绝无设计权限账号创建配置会话').toBe(403);
      const deniedBody = await denied.json().catch(() => ({}));
      expect(JSON.stringify(deniedBody)).not.toContain('snapshot');
      expect(JSON.stringify(deniedBody)).not.toContain('changeSetPid');

      await actor.page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
      await expect(
        actor.page.getByRole('heading', { name: '应用设计中心不可用' }),
      ).toBeVisible();
      await expect(actor.page.getByText(/meta\.designer\.read/)).toBeVisible();
      await expect(actor.page.getByTestId('unified-designer-workbench')).toHaveCount(0);
    } finally {
      await actor.close();
    }
  });
});

async function login(page: Page): Promise<void> {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function openRuntimeFromMenu(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${RUNTIME_ROUTE}"]`).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${RUNTIME_ROUTE}$`));
  await expect(page.getByRole('main').first().getByText('EXC-V4-REAL-001')).toBeVisible({
    timeout: 15_000,
  });
}

async function expectApiData<T>(response: APIResponse, label: string): Promise<T> {
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

async function ensureRuntimeOnlyPersona(adminPage: Page): Promise<void> {
  const roles = await expectApiData<RoleRecord[]>(
    await adminPage.request.get('/api/roles/all'),
    'load roles',
  );
  let role = roles.find((candidate) => candidate.code === RUNTIME_ONLY_ROLE);
  if (!role) {
    role = await expectApiData<RoleRecord>(
      await adminPage.request.post('/api/roles', {
        data: {
          code: RUNTIME_ONLY_ROLE,
          name: 'Contextual authoring runtime only',
          description: 'PC golden persona: runtime access without designer permissions',
          type: 'custom',
        },
      }),
      'create runtime-only role',
    );
  }

  const permissionPids = await resolvePermissionPids(adminPage, [
    'model.production_exception.read',
    'page.page.read',
  ]);
  await expectApiData<boolean>(
    await adminPage.request.post(`/api/roles/${role.pid}/permissions`, {
      data: permissionPids,
    }),
    'assign runtime-only permissions',
  );

  const loginProbe = await adminPage.request.post('/api/auth/login', {
    data: { email: RUNTIME_ONLY_EMAIL, password: DEFAULT_TEST_ACCOUNT.password },
  });
  if (!loginProbe.ok()) {
    const created = await expectApiData<{ assignedRoles?: string[] }>(
      await adminPage.request.post('/api/admin/users', {
        data: {
          email: RUNTIME_ONLY_EMAIL,
          displayName: 'Contextual authoring runtime only',
          initialPassword: DEFAULT_TEST_ACCOUNT.password,
          roleCodes: [RUNTIME_ONLY_ROLE],
          sendInviteEmail: false,
        },
      }),
      'create runtime-only user',
    );
    expect(created.assignedRoles ?? []).toContain(RUNTIME_ONLY_ROLE);
  }

  const members = await expectApiData<Array<{ email?: string; memberPid?: string }>>(
    await adminPage.request.get(
      `/api/org/members/unlinked?keyword=${encodeURIComponent(RUNTIME_ONLY_EMAIL)}`,
    ),
    'resolve runtime-only member',
  );
  const member = members.find((candidate) => candidate.email === RUNTIME_ONLY_EMAIL);
  expect(member?.memberPid, 'runtime-only member pid').toBeTruthy();
  await expectApiData<boolean>(
    await adminPage.request.post('/api/user-roles/assign-by-code', {
      data: { memberPid: member!.memberPid, roleCodes: [RUNTIME_ONLY_ROLE] },
    }),
    'activate runtime-only role',
  );
}

async function resolvePermissionPids(page: Page, permissionCodes: string[]): Promise<string[]> {
  const resourceTypes = ['function', 'operation', 'data', 'model'];
  const permissions = (
    await Promise.all(
      resourceTypes.map(async (resourceType) =>
        expectApiData<PermissionRecord[]>(
          await page.request.get(`/api/permissions/resource-type/${resourceType}`),
          `load ${resourceType} permissions`,
        ),
      ),
    )
  ).flat();
  const byCode = new Map(permissions.map((permission) => [permission.code, permission.pid]));
  const missing = permissionCodes.filter((code) => !byCode.has(code));
  expect(missing, `missing runtime-only permissions: ${missing.join(', ')}`).toEqual([]);
  return permissionCodes.map((code) => byCode.get(code)!);
}

async function openAsRuntimeOnlyUser(
  browser: Browser,
): Promise<{ page: Page; close: () => Promise<void> }> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  await loginViaUI(page, RUNTIME_ONLY_EMAIL, DEFAULT_TEST_ACCOUNT.password);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
  return { page, close: () => context.close() };
}
