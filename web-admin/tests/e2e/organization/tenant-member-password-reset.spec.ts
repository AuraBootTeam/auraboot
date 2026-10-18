import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { BASE_URL } from '../../helpers/environments';
import { LoginPage } from '../../pages/LoginPage';

test.use({ locale: 'zh-CN' });

test.describe('Tenant Member Password Reset', () => {
  test('MEM-RESET-001: admin can reset an active member password and see one-time temporary password @smoke', async ({
    page, browser,
  }, info) => {
    test.setTimeout(60000);
    const stamp = uniqueId('member-reset');
    const email = `${stamp}@e2e.local`;
    const created = await page.request.post('/api/admin/users', { data: {
      email, displayName: stamp, initialPassword: 'MemberE2e!2026',
      roleCodes: ['tenant_member'], sendInviteEmail: false,
    } });
    expect(created.ok()).toBe(true);
    expect((await created.json()).code).toBe('0');
    const found = await page.request.post('/api/tenant/members/search', {
      data: { keyword: email, pageNum: 1, pageSize: 50 },
    });
    expect(found.ok()).toBe(true);
    const bodyFound = await found.json();
    expect(bodyFound.code).toBe('0');
    const member = bodyFound.data.records.find((row: { user?: { email?: string } }) => row.user?.email === email);
    expect(member?.status).toBe('active');
    expect(member.pid).toMatch(/^[0-9A-Z]{26}$/);

    await page.goto('/', { waitUntil: 'domcontentloaded' });
    const membersLink = page.locator('nav a[href="/p/tenant_member"]');
    if (!(await membersLink.isVisible())) {
      await page.locator('nav button').filter({ hasText: '组织管理' }).first().click();
    }
    await expect(membersLink).toBeVisible();
    await membersLink.click();

    await expect(page.getByText(/企业成员管理|Tenant Members/i)).toBeVisible({ timeout: 10000 });
    const row = page.locator('table tbody tr').filter({ hasText: stamp });
    await expect(row).toHaveCount(1);
    await expect(row.getByTestId('row-action-more')).toBeVisible({
      timeout: 10000,
    });

    await row.getByTestId('row-action-more').click();
    await expect(page.locator('[data-testid="row-action-dropdown"]')).toContainText('重置密码');

    const resetResponse = page.waitForResponse(
      (response) =>
        response.url().includes('/api/meta/commands/execute/admin:reset_member_password') &&
        response.request().method() === 'POST',
      { timeout: 10000 },
    );

    await page.getByTestId('row-action-reset-password').click();
    await expect(page.getByText('确认为该成员生成新的临时密码？临时密码只会显示一次。')).toBeVisible({
      timeout: 5000,
    });
    await page.screenshot({
      path: info.outputPath('ui-07a-member-reset-confirm.png'),
      fullPage: true,
    });
    await page.getByRole('button', { name: /^确认$/ }).last().click();

    const response = await resetResponse;
    expect(response.ok()).toBe(true);
    expect(response.request().postDataJSON().targetRecordPid).toBe(member.pid);
    const body = await response.json();
    expect(body?.code).toBe('0');
    expect(body?.data?.data?.adminManaged).toBe(true);
    expect(typeof body?.data?.data?.tempPassword).toBe('string');
    expect(body.data.data.tempPassword.length).toBeGreaterThanOrEqual(8);

    // The success dialog title depends on the response shape: 临时密码已生成
    // (tempPassword only) or the account-credentials variant when userName is
    // included. Accept either; both must carry the one-time temp password.
    await expect(
      page
        .getByRole('heading')
        .filter({ hasText: /临时密码已生成|账号凭证|credentials generated|password generated/i }),
    ).toBeVisible({ timeout: 10000 });
    await expect(page.getByText(/临时密码|tempPassword|shown once/i).first()).toBeVisible();
    await page.screenshot({
      path: info.outputPath('ui-07-member-reset-temp-password.png'),
      fullPage: true,
    });
    const tempPassword = body.data.data.tempPassword as string;
    expect((await page.getByRole('dialog').last().textContent())?.includes(tempPassword),
      'the one-time dialog must show the password returned for this owned member').toBe(true);
    await page.reload({ waitUntil: 'domcontentloaded' });
    expect((await page.locator('body').textContent())?.includes(tempPassword),
      'reloading must not reveal the temporary password again').toBe(false);

    const context = await browser.newContext({
      baseURL: BASE_URL, locale: 'zh-CN', storageState: { cookies: [], origins: [] },
    });
    try {
      const memberPage = await context.newPage();
      const login = new LoginPage(memberPage);
      await login.goto();
      await login.waitForFormReady();
      await login.login(email, tempPassword);
      await login.expectLoggedIn();
      const current = await memberPage.request.get('/api/auth/me');
      expect(current.ok()).toBe(true);
      const currentBody = await current.json();
      expect(currentBody.code).toBe('0');
      expect(currentBody.data.user.email).toBe(email);
      await memberPage.screenshot({ path: info.outputPath('ui-07b-owned-member-login.png') });
    } finally {
      await context.close();
    }
  });
});
