import { expect, test, type Page } from '@playwright/test';
import { createCookieSessionStorage } from 'react-router';
import path from 'node:path';
import { BACKEND_URL, BASE_URL as WEB_BASE_URL } from '../../helpers/environments';

const EVIDENCE_DIR = path.join(
  process.env.AURA_EVIDENCE_ROOT || '/tmp',
  'open-platform-collaboration-20260927',
);
const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [process.env.SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: false,
  },
});

type Role = 'owner' | 'maintainer' | 'viewer';

async function authenticate(page: Page) {
  const loginResponse = await page.request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: 'admin@auraboot.com', password: 'Test2026x' },
  });
  expect(loginResponse.ok()).toBeTruthy();
  const login = (await loginResponse.json()).data;
  let jwt = login.jwt as string;
  if (!login.tenantId) {
    const spacesResponse = await page.request.get(`${BACKEND_URL}/api/tenant-selection/my-spaces`, {
      headers: { Authorization: `Bearer ${jwt}` },
    });
    expect(spacesResponse.ok()).toBeTruthy();
    const spaces = (await spacesResponse.json()).data as Array<{
      tenantId: string;
      spaceType: string;
    }>;
    const businessTenant = spaces.find((space) => space.spaceType === 'business');
    expect(businessTenant).toBeTruthy();
    const selectionResponse = await page.request.post(
      `${BACKEND_URL}/api/tenant-selection/process`,
      {
        headers: { Authorization: `Bearer ${jwt}` },
        data: { action: 'select', tenantId: businessTenant!.tenantId },
      },
    );
    expect(selectionResponse.ok()).toBeTruthy();
    jwt = (await selectionResponse.json()).data.jwt;
  }
  expect(jwt).toEqual(expect.any(String));
  const session = await sessionStorage.getSession();
  session.set('jwtToken', jwt);
  const setCookie = await sessionStorage.commitSession(session, { maxAge: 604800 });
  const value = setCookie.match(/__session=([^;]+)/)?.[1];
  expect(value).toBeTruthy();
  await page.context().addCookies([
    { name: '__session', value: value!, url: WEB_BASE_URL, httpOnly: true, sameSite: 'Lax' },
    { name: 'locale', value: 'zh-CN', url: WEB_BASE_URL, sameSite: 'Lax' },
  ]);
  await page.addInitScript(() => localStorage.setItem('locale', 'zh-CN'));
}

async function capture(page: Page, id: string) {
  await page.screenshot({ path: path.join(EVIDENCE_DIR, `${id}.png`), fullPage: true });
}

async function openAccountMenu(page: Page) {
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true', {
    timeout: 15_000,
  });
  const userMenu = page.getByTestId('user-menu');
  await userMenu.getByRole('button', { name: /User avatar/ }).click();
  await expect(userMenu.getByTestId('user-dropdown')).toBeVisible();
}

async function dismissToasts(page: Page) {
  await expect(page.getByRole('alert')).toHaveCount(0, { timeout: 10_000 });
}

async function installRoleFixture(page: Page, role: Role | 'nonmember') {
  await page.unroute('**/api/open-platform/**');
  const permissions = {
    manageMembers: role === 'owner',
    disableApplication: role === 'owner',
    manageRuntime: role === 'owner' || role === 'maintainer',
    readOperations: role !== 'nonmember',
  };
  await page.route('**/api/open-platform/access', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: '0',
        message: 'OK',
        data: { platformAdmin: false, canCreateApplications: false },
      }),
    }),
  );
  await page.route('**/api/open-platform/capabilities', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({ code: '0', message: 'OK', data: [] }),
    }),
  );
  await page.route('**/api/open-platform/applications', (route) =>
    route.fulfill({
      contentType: 'application/json',
      body: JSON.stringify({
        code: '0',
        message: 'OK',
        data:
          role === 'nonmember'
            ? []
            : [
                {
                  pid: 'app_visual_fixture',
                  name: '供应链协作连接器',
                  description: '受控视觉 fixture：仅验证角色对应的界面动作投影',
                  status: 'active',
                  createdAt: '2026-09-27T00:00:00Z',
                  accessRole: role,
                  permissions,
                  members: [
                    {
                      userPid: 'user_owner',
                      displayName: '平台所有者',
                      email: 'owner@example.test',
                      role: 'owner',
                      createdAt: '2026-09-27T00:00:00Z',
                    },
                    {
                      userPid: 'user_current',
                      displayName: role === 'viewer' ? '只读协作者' : '运行维护者',
                      email: 'collaborator@example.test',
                      role,
                      createdAt: '2026-09-27T00:00:00Z',
                    },
                  ],
                  installations: [
                    {
                      pid: 'installation_visual_fixture',
                      environment: 'production',
                      status: 'active',
                      scopes: ['openapi.profile.read', 'openapi.events.read'],
                      rateLimitPerMinute: 600,
                      installedAt: '2026-09-27T00:00:00Z',
                    },
                  ],
                },
              ],
      }),
    }),
  );
}

test.describe('Open Platform collaboration golden states', () => {
  test.setTimeout(120_000);
  test.use({
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });

  test('captures owner, maintainer, viewer and non-member projections', async ({ page }) => {
    await authenticate(page);
    await page.goto('/');
    await expect(page).not.toHaveURL(/\/login|\/setup/);
    await expect(page.locator('.animate-spin')).toHaveCount(0, { timeout: 15_000 });
    await openAccountMenu(page);
    await expect(page.getByTestId('open-platform-link')).toBeVisible();
    await capture(page, 'OPC-UI-01');
    await page.getByTestId('open-platform-link').click();
    await expect(page.getByTestId('open-platform-page')).toBeVisible();
    await expect(
      page
        .getByTestId('open-platform-empty')
        .or(page.getByTestId('open-platform-application-list')),
    ).toBeVisible();

    if ((await page.getByTestId('open-platform-empty').count()) > 0) {
      await page.getByTestId('open-platform-create-app').click();
      const dialog = page.getByRole('dialog', { name: '创建外部应用' });
      await page.getByTestId('open-platform-app-name').fill('开放平台协作验收应用');
      await dialog.getByRole('textbox', { name: '描述' }).fill('真实后端 owner 与成员管理验收');
      await dialog.getByRole('button', { name: '新建' }).click();
    }
    const application = page.getByTestId('open-platform-application-list');
    await expect(application).toContainText('开放平台协作验收应用');
    await dismissToasts(page);
    await expect(application).toContainText('所有者');
    await expect(page.getByRole('button', { name: '管理成员' })).toBeVisible();
    await expect(page.getByRole('button', { name: '添加安装' })).toBeVisible();
    await capture(page, 'OPC-UI-02');

    await page.getByRole('button', { name: '管理成员' }).click();
    const memberDialog = page.getByRole('dialog', { name: '管理成员' });
    await expect(memberDialog).toBeVisible();
    await capture(page, 'OPC-UI-03');
    const soleOwnerRemove = memberDialog.getByRole('button', { name: /移除成员/ }).first();
    await expect(soleOwnerRemove).toBeDisabled();
    await memberDialog.screenshot({ path: path.join(EVIDENCE_DIR, 'OPC-UI-04.png') });
    await memberDialog.getByRole('button', { name: '取消' }).click();

    await installRoleFixture(page, 'viewer');
    await page.goto('/settings/api-docs');
    await expect(page.getByTestId('open-platform-page')).toContainText('查看者');
    await expect(page.getByRole('button', { name: '运维' })).toBeVisible();
    await expect(page.getByRole('button', { name: '管理成员' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '添加安装' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '新建凭据' })).toHaveCount(0);
    await capture(page, 'OPC-UI-05');

    await installRoleFixture(page, 'nonmember');
    await page.goto('/settings/api-docs');
    await expect(page.getByTestId('open-platform-empty')).toContainText(
      '暂无共享给你的开放平台应用',
    );
    await expect(page.getByTestId('open-platform-create-app')).toHaveCount(0);
    await capture(page, 'OPC-UI-06');

    await installRoleFixture(page, 'maintainer');
    await page.goto('/settings/api-docs');
    await expect(page.getByTestId('open-platform-page')).toContainText('维护者');
    await expect(page.getByRole('button', { name: '添加安装' })).toBeVisible();
    await expect(page.getByRole('button', { name: '新建凭据' })).toBeVisible();
    await expect(page.getByRole('button', { name: '管理成员' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '停用', exact: true })).toHaveCount(0);
    await capture(page, 'OPC-UI-07');
  });
});
