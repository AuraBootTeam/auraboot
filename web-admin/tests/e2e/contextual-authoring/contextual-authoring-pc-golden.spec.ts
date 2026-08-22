import { test, expect, type APIResponse, type Browser, type Page } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { Client as PgClient } from 'pg';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { PG_CONN } from '../../helpers/environments';
import { loginViaUI } from '../../helpers/wd-fixtures';

const SOURCE_PAGE_KEY = 'e2et_record_list';
const RUNTIME_ONLY_ROLE = 'e2e_contextual_authoring_runtime_only';
const RUNTIME_ONLY_EMAIL = 'e2e-contextual-authoring-runtime-only@test.com';
const SCREENSHOT_DIR = resolve(
  process.env.CONTEXTUAL_AUTHORING_SCREENSHOT_DIR ?? 'test-results/contextual-authoring',
);

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

type GatePage = {
  pid: string;
  pageKey: string;
  route: string;
  title: string;
  recordMarker: string;
  recordPids: string[];
  menuId: string | number;
};

let gatePage: GatePage;

test.use({ storageState: { cookies: [], origins: [] } });

test.describe('Contextual authoring PC Web golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 60_000 });

  test.beforeEach(async ({ browser, page }) => {
    gatePage = await createPcGatePage(browser);
    await login(page);
  });

  test.afterEach(async ({ browser }) => {
    if (gatePage) await cleanupPcGatePage(browser, gatePage);
  });

  test('PC-AUTH-001 @critical @smoke — menu to runtime to contextual authoring', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1280, height: 720 });
    await openRuntimeFromMenu(page);

    const runtimeMain = page.getByRole('main').first();
    await expect(runtimeMain.getByRole('heading', { name: gatePage.title })).toBeVisible();
    await expect(runtimeMain.getByText(gatePage.recordMarker)).toBeVisible();
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
    await expect(inspector).toContainText(gatePage.title);
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
      await expect(runtimeMain.getByText(gatePage.recordMarker)).toBeVisible();
      await expect(runtimeMain.getByRole('button', { name: '配置此页' })).toHaveCount(0);
      await expect(actor.page.getByTestId('contextual-authoring-enter')).toHaveCount(0);

      const denied = await actor.page.request.post('/api/authoring/sessions', {
        data: {
          pagePid: gatePage.pid,
          interactionContext: { route: gatePage.route },
        },
      });
      expect(denied.status(), '后端必须独立拒绝无设计权限账号创建配置会话').toBe(403);
      const deniedBody = await denied.json().catch(() => ({}));
      expect(JSON.stringify(deniedBody)).not.toContain('snapshot');
      expect(JSON.stringify(deniedBody)).not.toContain('changeSetPid');

      await actor.page.goto('/unified-designer', { waitUntil: 'domcontentloaded' });
      await expect(actor.page.getByRole('heading', { name: '应用设计中心不可用' })).toBeVisible();
      await expect(actor.page.getByText(/meta\.designer\.read/)).toBeVisible();
      await expect(actor.page.getByTestId('unified-designer-workbench')).toHaveCount(0);
    } finally {
      await actor.close();
    }
  });

  test('PC-AUTH-049 @critical — 200% effective zoom keeps authoring keyboard and screen-reader semantics intact', async ({
    page,
  }, testInfo) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openRuntimeFromMenu(page);
    await emulateDesktopAtTwoHundredPercent(page);

    const effectiveViewport = await page.evaluate(() => ({
      devicePixelRatio: window.devicePixelRatio,
      height: window.innerHeight,
      width: window.innerWidth,
    }));
    expect(effectiveViewport).toEqual({ devicePixelRatio: 2, height: 450, width: 720 });

    const runtimeMain = page.getByRole('main').first();
    await runtimeMain.getByRole('button', { name: '配置此页' }).focus();
    await page.keyboard.press('Enter');

    const surface = page.getByTestId('contextual-authoring-surface');
    await expect(surface).toBeVisible();
    await expect(page.getByRole('note', { name: 'ChangeSet 风险与发布策略' })).toContainText('L0');
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
      ),
      '200% 等效缩放下页面不应产生全局水平滚动',
    ).toBe(true);

    const inspectorTrigger = page.getByTestId('authoring-inspector-open');
    await inspectorTrigger.focus();
    await page.keyboard.press('Enter');

    const inspector = page.getByRole('dialog', { name: '属性检查器' });
    await expect(inspector).toBeVisible();
    await expect(inspector).toHaveAttribute('aria-modal', 'true');
    const inspectorAria = await inspector.ariaSnapshot();
    expect(inspectorAria).toContain('dialog "属性检查器"');
    expect(inspectorAria).toContain('button "高级设置"');
    await testInfo.attach('contextual-authoring-accessibility-tree', {
      body: inspectorAria,
      contentType: 'text/plain',
    });
    const inspectorBox = await inspector.boundingBox();
    expect(inspectorBox?.x ?? -1, '窄视口属性检查器应从 viewport 左缘开始').toBeLessThanOrEqual(1);
    expect(inspectorBox?.width ?? 0, '窄视口属性检查器应占满 viewport').toBeGreaterThanOrEqual(
      effectiveViewport.width - 2,
    );

    const inspectorClose = inspector.getByRole('button', { name: '关闭属性检查器' });
    await expect(inspectorClose).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(inspector.getByRole('button', { name: '高级设置' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(inspectorClose).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(inspector).toBeHidden();
    await expect(inspectorTrigger).toBeFocused();

    await page.keyboard.press('Enter');
    const advancedSettings = inspector.getByRole('button', { name: '高级设置' });
    await advancedSettings.focus();
    await page.keyboard.press('Enter');
    const explain = page.getByRole('dialog', { name: '进入应用设计中心' });
    await expect(explain).toBeVisible();
    await expect(explain).toHaveAttribute('aria-modal', 'true');
    await expect(explain.getByRole('button', { name: '取消' })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    await expect(explain.getByRole('button', { name: '继续到应用设计中心' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(explain).toBeHidden();
    await expect(advancedSettings).toBeFocused();

    await page.emulateMedia({ forcedColors: 'active' });
    await expect(inspector).toBeVisible();
    await expect(inspector.getByText('可配置属性')).toBeVisible();
    await mkdir(SCREENSHOT_DIR, { recursive: true });
    await useEquivalentLayoutViewport(page);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-049-contextual-200-percent-forced-colors-final.png'),
      fullPage: true,
    });
    await testInfo.attach('contextual-authoring-200-percent-forced-colors', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
    await page.emulateMedia({ forcedColors: 'none' });
    await emulateDesktopAtTwoHundredPercent(page);

    await expect(advancedSettings).toBeFocused();
    await page.keyboard.press('Enter');
    await explain.getByRole('button', { name: '继续到应用设计中心' }).click();
    await expect(page).toHaveURL(/\/unified-designer\?authoringSession=/);
    await emulateDesktopAtTwoHundredPercent(page);
    expect(await page.evaluate(() => window.innerWidth)).toBe(720);

    const governanceTrigger = page.getByTestId('studio-governance-open');
    await governanceTrigger.focus();
    await page.keyboard.press('Enter');
    const governance = page.getByRole('dialog', { name: '治理与发布' });
    await expect(governance).toBeVisible();
    await expect(governance).toHaveAttribute('aria-modal', 'true');
    const governanceAria = await governance.ariaSnapshot();
    expect(governanceAria).toContain('dialog "治理与发布"');
    expect(governanceAria).toContain('button "关闭治理与发布"');
    await testInfo.attach('studio-governance-accessibility-tree', {
      body: governanceAria,
      contentType: 'text/plain',
    });
    const governanceBox = await governance.boundingBox();
    expect(governanceBox?.x ?? -1, '200% 下治理抽屉应从 viewport 左缘开始').toBeLessThanOrEqual(1);
    expect(governanceBox?.width ?? 0, '200% 下治理抽屉应占满 viewport').toBeGreaterThanOrEqual(718);
    await expect(governance.getByRole('button', { name: '关闭治理与发布' })).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    expect(
      await governance.evaluate((element) => element.contains(document.activeElement)),
      '治理抽屉的反向 Tab 必须留在模态范围内',
    ).toBe(true);
    await useEquivalentLayoutViewport(page);
    await page.screenshot({
      path: resolve(SCREENSHOT_DIR, 'pc-auth-049-studio-200-percent-final.png'),
      fullPage: true,
    });
    await page.keyboard.press('Escape');
    await expect(governance).toBeHidden();
    await expect(governanceTrigger).toBeFocused();

    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1,
      ),
      'Studio 在 200% 等效缩放下不应产生全局水平滚动',
    ).toBe(true);
    await testInfo.attach('studio-governance-200-percent', {
      body: await page.screenshot(),
      contentType: 'image/png',
    });
  });
});

async function emulateDesktopAtTwoHundredPercent(page: Page): Promise<void> {
  await page.setViewportSize({ width: 1440, height: 900 });
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    mobile: false,
    width: 720,
    height: 450,
    deviceScaleFactor: 2,
    screenWidth: 1440,
    screenHeight: 900,
    screenOrientation: { angle: 0, type: 'landscapePrimary' },
  });
}

async function useEquivalentLayoutViewport(page: Page): Promise<void> {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.clearDeviceMetricsOverride');
  await page.setViewportSize({ width: 720, height: 900 });
}

async function createPcGatePage(browser: Browser): Promise<GatePage> {
  const suffix = `${Date.now().toString(36)}_${process.pid}`;
  const recordMarker = `PC-AUTH-A11Y-${suffix}`;
  const recordPids = await seedPcGateRecords(recordMarker, suffix);
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    const source = await expectApiData<Record<string, unknown>>(
      await page.request.get(`/api/pages/key/${SOURCE_PAGE_KEY}`),
      'load PC gate source page',
    );
    const menuId = Date.now();
    const pageKey = `contextual_authoring_pc_gate_${suffix}`;
    const route = `/contextual-authoring-pc-gate-${suffix.replaceAll('_', '-')}`;
    const title = `Contextual Authoring PC Gate ${suffix}`;
    const created = await expectApiData<{ pid: string }>(
      await page.request.post('/api/pages', {
        data: {
          pageKey,
          modelCode: source.modelCode,
          name: title,
          title,
          description: 'Self-contained PC accessibility and authoring golden fixture',
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
      'create PC gate page',
    );
    await expectApiData(
      await page.request.post(`/api/pages/${created.pid}/publish`),
      'publish PC gate page',
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
      'mount PC gate page in menu',
    );
    return {
      pid: created.pid,
      pageKey,
      route,
      title,
      recordMarker,
      recordPids,
      menuId: menu.id,
    };
  } catch (error) {
    await deletePcGateRecords(recordPids);
    throw error;
  } finally {
    await context.close();
  }
}

async function cleanupPcGatePage(browser: Browser, target: GatePage): Promise<void> {
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  try {
    await login(page);
    await expectApiData(
      await page.request.delete(`/api/menu/${encodeURIComponent(String(target.menuId))}`),
      'delete PC gate menu',
    );
    await expectApiData(
      await page.request.post(`/api/pages/${encodeURIComponent(target.pid)}/unpublish`),
      'unpublish PC gate page',
    );
    await expectApiData(
      await page.request.delete(`/api/pages/${encodeURIComponent(target.pid)}`),
      'delete PC gate page',
    );
  } finally {
    await context.close();
    await deletePcGateRecords(target.recordPids);
  }
}

async function seedPcGateRecords(marker: string, suffix: string): Promise<string[]> {
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
    const pids = tenants.rows.map(
      ({ tenant_id: tenantId }, index) => `pcgold_${suffix}_${index}_${tenantId.slice(-6)}`,
    );
    for (let index = 0; index < tenants.rows.length; index += 1) {
      await client.query(
        `INSERT INTO mt_e2et_record
           (pid, tenant_id, e2et_name, e2et_status, e2et_count, created_at, updated_at)
         VALUES ($1, $2, $3, 'active', 1, NOW(), NOW())`,
        [pids[index], tenants.rows[index].tenant_id, marker],
      );
    }
    return pids;
  } finally {
    await client.end();
  }
}

async function deletePcGateRecords(recordPids: string[]): Promise<void> {
  const client = new PgClient(PG_CONN);
  await client.connect();
  try {
    await client.query('DELETE FROM mt_e2et_record WHERE pid = ANY($1::text[])', [recordPids]);
  } finally {
    await client.end();
  }
}

async function login(page: Page): Promise<void> {
  await loginViaUI(page, DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await expect(page.locator('nav')).toBeVisible({ timeout: 15_000 });
}

async function openRuntimeFromMenu(page: Page): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav');
  const link = nav.locator(`a[href="${gatePage.route}"]`).first();
  await expect(link).toBeVisible({ timeout: 10_000 });
  await link.click();
  await expect(page).toHaveURL(new RegExp(`${gatePage.route}$`));
  await expect(page.getByRole('main').first().getByText(gatePage.recordMarker)).toBeVisible({
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
    'model.e2et_record.read',
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
