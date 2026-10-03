import { test, expect } from '../../fixtures';
import { uniqueId, acceptConfirmDialog } from '../helpers';
import { ensureQuoteRoleUser, fetchRoleSnapshot, makeQuoteRoleUser, openQuoteRolePage } from '../pcba-solution/quote-e2e-helpers';

test.use({ locale: 'zh-CN' });

// Dedicated records are created through the public admin API as fixtures. Every
// lifecycle mutation below is driven by the native detail button, never by API fallback.
test('MEMBER-DETAIL-07: native lifecycle buttons execute commands and persist state @critical', async ({
  page,
}, testInfo) => {
  test.setTimeout(120000);
  const stamp = uniqueId('native-member');
  const email = `${stamp}@e2e.local`;
  const created = await page.request.post('/api/admin/users', {
    data: {
      email,
      displayName: stamp,
      initialPassword: 'MemberE2e!2026',
      roleCodes: ['crm_sales'],
      sendInviteEmail: false,
    },
  });
  expect(created.ok()).toBe(true);
  expect((await created.json()).code).toBe('0');
  const found = await page.request.post('/api/tenant/members/search', {
    data: { keyword: email, pageNum: 1, pageSize: 50 },
  });
  expect(found.ok()).toBe(true);
  const body = await found.json();
  expect(body.code).toBe('0');
  const rows = body.data.records as Array<{ pid: string; status: string; user: { email: string } }>;
  const member = rows.find((row) => row.user?.email === email);
  expect(member, 'newly provisioned lifecycle fixture').toBeDefined();
  const pid = member!.pid;
  expect(member!.status).toBe('active');

  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload();
  const membersLink = page.locator('nav a[href="/p/tenant_member"]');
  if (!(await membersLink.isVisible())) {
    await page.locator('nav button').filter({ hasText: '组织管理' }).first().click();
  }
  await expect(membersLink).toBeVisible();
  await membersLink.click();
  await expect(page).toHaveURL(/\/p\/tenant_member/);
  const row = page.locator('table tbody tr').filter({ hasText: stamp });
  await expect(row).toHaveCount(1, { timeout: 20000 });
  await expect(row).toBeVisible();
  await row.click();
  await expect(page).toHaveURL(new RegExp(`/organization/members/${pid}$`));
  await expect(page.getByTestId('member-status')).toHaveAttribute('data-status', 'active');
  await expect(page.getByTestId('member-status')).toHaveText('已激活');

  const capture = async (name: string) => {
    const path = testInfo.outputPath(`${name}.png`);
    await page.screenshot({ path, fullPage: true });
    await testInfo.attach(name, { path, contentType: 'image/png' });
  };
  await capture('native-member-active');
  const act = async (
    label: string,
    command: string,
    status?: string,
    offboardingAction?: string,
  ) => {
    const commandResponse = page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/meta/commands/execute/${command}`) &&
        response.request().method() === 'POST',
    );
    const impactResponse = offboardingAction
      ? page.waitForResponse((response) => {
          const url = new URL(response.url());
          return (
            url.pathname === `/api/tenant/members/${pid}/offboarding-impact` &&
            url.searchParams.get('action') === offboardingAction
          );
        })
      : null;
    await page.getByTestId('action-bar').getByRole('button', { name: label, exact: true }).click();
    if (impactResponse) {
      const impact = await impactResponse;
      expect(impact.ok()).toBe(true);
      const result = await impact.json();
      expect(result.code).toBe('0');
      expect(result.data.transferRequired, 'fixture owns no resources').toBe(false);
    }
    if (command === 'admin:suspend_member' || command === 'admin:leave_member') {
      await expect(page.getByTestId('form-dialog')).toBeVisible();
      await expect(page.getByTestId('form-dialog').getByRole('heading')).toHaveText(
        command === 'admin:suspend_member' ? '暂停成员' : '办理离职',
      );
      await page.getByTestId('form-dialog-field-reason').fill(`E2E ${command}`);
      await capture(`${command.split(':')[1]}-input`);
      await page.getByTestId('form-dialog-submit').click();
    } else {
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      await expect(page.getByTestId('confirm-ok')).toHaveClass(
        command === 'admin:restore_member' ? /bg-accent/ : /bg-red-600/,
      );
      await capture(`${command.split(':')[1]}-confirm`);
      await acceptConfirmDialog(page);
    }
    const response = await commandResponse;
    expect(response.ok()).toBe(true);
    const payload = response.request().postDataJSON();
    expect(payload.targetRecordPid).toBe(pid);
    expect((await response.json()).code).toBe('0');
    if (status) {
      await expect(page.getByTestId('member-status')).toHaveAttribute('data-status', status);
      await expect(page.getByTestId('member-status')).toHaveText(
        ({ active: '已激活', suspended: '已暂停', inactive: '已离职' } as Record<string, string>)[
          status
        ],
      );
      const persisted = await page.request.get(`/api/tenant/members/${pid}`);
      expect(persisted.ok()).toBe(true);
      const result = await persisted.json();
      expect(result.code).toBe('0');
      expect(result.data.status).toBe(status);
      await page.reload();
      await expect(page.getByTestId('member-status')).toHaveAttribute('data-status', status);
      await expect(page.getByTestId('member-status')).toHaveText(
        ({ active: '已激活', suspended: '已暂停', inactive: '已离职' } as Record<string, string>)[
          status
        ],
      );
      await capture(
        status === 'active' ? 'native-member-active-restored' : `native-member-${status}`,
      );
    }
  };
  await act('暂停', 'admin:suspend_member', 'suspended', 'suspend');
  await act('恢复', 'admin:restore_member', 'active');
  await act('离职', 'admin:leave_member', 'inactive', 'deactivate');
  await act('删除', 'admin:delete_member', undefined, 'remove');
  await expect(page).toHaveURL(/\/p\/tenant_member$/);
  const after = await page.request.post('/api/tenant/members/search', {
    data: { keyword: email, pageNum: 1, pageSize: 50 },
  });
  expect(after.ok()).toBe(true);
  const result = await after.json();
  expect(result.code).toBe('0');
  expect(result.data.records.some((entry: { pid: string }) => entry.pid === pid)).toBe(false);
  await expect(page.locator('table')).toBeVisible();
  await expect(page.locator('table').getByRole('columnheader').first()).toBeVisible();
  await expect(page.locator('table tbody')).not.toContainText(/加载中|Loading/i);
  await expect(page.locator('table tbody tr').first()).toBeVisible();
  await expect(page.locator('table tbody')).not.toContainText(/暂无数据|No data/i);
  await expect(page.locator('table tbody tr').filter({ hasText: stamp })).toHaveCount(0);
  await expect(page.getByTestId('invite-section')).toHaveText('邀请成员');
  await expect(page.getByTestId('member-import-entry')).toHaveText('导入成员');
  await capture('native-member-removed-list');
});


test('MEMBER-DETAIL-08: independent member-view capability permits reading and revocation denies access @critical', async ({ page, browser }, info) => {
  test.setTimeout(120000);
  const stamp = uniqueId('member-view');
  const roleCode = `e2e_member_view_${stamp}`;
  const roleResponse = await page.request.post('/api/roles', {
    data: { code: roleCode, name: `Member view ${stamp}`, type: 'custom', description: 'Independent member read boundary' },
  });
  expect(roleResponse.ok()).toBe(true);
  const roleBody = await roleResponse.json();
  expect(String(roleBody.code)).toBe('0');
  const role = roleBody.data as { pid: string };
  const viewer = makeQuoteRoleUser('member-view', stamp, [roleCode]);
  const target = makeQuoteRoleUser('member-target', stamp, []);
  await ensureQuoteRoleUser(page, viewer);
  await ensureQuoteRoleUser(page, target);
  const found = await page.request.post('/api/tenant/members/search', {
    data: { keyword: target.email, pageNum: 1, pageSize: 50 },
  });
  expect(found.ok()).toBe(true);
  const foundBody = await found.json();
  expect(String(foundBody.code)).toBe('0');
  const member = foundBody.data.records.find((row: { user?: { email: string } }) => row.user?.email === target.email);
  expect(member).toBeDefined();
  expect(member.status).toBe('active');
  const pid = member.pid as string;

  await page.goto('/home');
  await expect(page.locator('header[data-hydrated]')).toHaveAttribute('data-hydrated', 'true');
  await page.getByRole('link', { name: /角色|Roles/, exact: true }).click();
  await page.getByTestId('role-search-input').fill(roleCode);
  await page.getByTestId(`role-item-${roleCode}`).click();
  await expect(page.getByTestId('capability-role-editor')).toHaveAttribute('data-role-pid', role.pid);
  await page.getByTestId('data-scope-modify-btn').click();
  await page.getByTestId('data-scope-option-all').click();
  await page.getByTestId('data-scope-apply').click();
  await expect(page.getByTestId('data-scope-drawer')).toHaveCount(0);
  const checkbox = page.getByTestId('capability-checkbox-org.cap.member_view');
  const saveSelection = async (grant: boolean) => {
    await checkbox.scrollIntoViewIfNeeded();
    await checkbox.setChecked(grant);
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const savedPromise = page.waitForResponse(response => response.request().method() === 'PUT' &&
      response.url().includes('/api/permission/capabilities?'));
    await page.getByTestId('confirm-ok').click();
    const response = await savedPromise;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    expect(response.request().postDataJSON()).toEqual(grant ? ['org.cap.member_view'] : []);
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  };
  await saveSelection(true);
  const opened = await openQuoteRolePage(browser, viewer);
  const viewerPage = opened.page;
  try {
    const snapshot = await fetchRoleSnapshot(viewerPage);
    expect(snapshot.roleCodes).toContain(roleCode);
    expect(snapshot.permissionCodes).toContain('model.tenant_member.read');
    for (const code of ['admin_tenant_member', 'model.tenant_member.suspend', 'model.tenant_member.leave', 'model.tenant_member.delete']) {
      expect(snapshot.permissionCodes).not.toContain(code);
    }
    await info.attach('member-view-role-snapshot', { body: JSON.stringify(snapshot), contentType: 'application/json' });
    await viewerPage.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
    await viewerPage.reload();
    const membersLink = viewerPage.locator('nav a[href="/p/tenant_member"]');
    if (!(await membersLink.isVisible())) {
      await viewerPage.locator('nav button').filter({ hasText: '组织管理' }).first().click();
    }
    await expect(membersLink).toBeVisible();
    await membersLink.click();
    const row = viewerPage.locator('table tbody tr').filter({ hasText: target.displayName });
    await expect(row).toHaveCount(1);
    const readPromise = viewerPage.waitForResponse(response => new URL(response.url()).pathname === `/api/tenant/members/${pid}`);
    await row.click();
    const read = await readPromise;
    expect(read.status()).toBe(200);
    const readBody = await read.json();
    expect(String(readBody.code)).toBe('0');
    expect(readBody.data.pid).toBe(pid);
    await expect(viewerPage.getByTestId('member-name')).toHaveText(target.displayName);
    await expect(viewerPage.getByTestId('member-status')).toHaveAttribute('data-status', 'active');
    await expect(viewerPage.getByTestId('action-bar').getByRole('button')).toHaveCount(0);
    await viewerPage.screenshot({ path: info.outputPath('independent-member-view.png'), fullPage: true });

    const impact = await viewerPage.request.get(`/api/tenant/members/${pid}/offboarding-impact?action=suspend`);
    expect(impact.status()).toBe(403);
    const command = await viewerPage.request.post('/api/meta/commands/execute/admin:suspend_member', {
      data: { targetRecordPid: pid, data: { reason: 'Must be denied for read-only role' } },
    });
    expect(command.status()).toBe(403);
    const persisted = await page.request.get(`/api/tenant/members/${pid}`);
    expect(persisted.ok()).toBe(true);
    expect((await persisted.json()).data.status).toBe('active');
    await info.attach('member-write-denials', { body: JSON.stringify({ impact: await impact.json(), command: await command.json(), targetPid: pid, persistedStatus: 'active' }), contentType: 'application/json' });
    await viewerPage.screenshot({ path: info.outputPath('independent-member-write-denied.png'), fullPage: true });

    await saveSelection(false);
    const revokedReadPromise = viewerPage.waitForResponse(response => new URL(response.url()).pathname === `/api/tenant/members/${pid}`);
    await viewerPage.reload();
    const revoked = await revokedReadPromise;
    expect(revoked.status()).toBe(403);
    await expect(viewerPage.getByTestId('member-name')).toHaveCount(0);
    await expect(viewerPage.getByTestId('member-status')).toHaveCount(0);
    await expect(viewerPage.getByTestId('member-load-error')).toHaveAttribute('data-error-kind', 'forbidden');
    await expect(viewerPage.getByText('无权查看此成员', { exact: true })).toBeVisible();
    await expect(viewerPage.getByText('成员不存在', { exact: true })).toHaveCount(0);
    await expect(viewerPage.getByText('Access forbidden', { exact: true })).toHaveCount(0);
    await viewerPage.screenshot({ path: info.outputPath('independent-member-read-revoked.png'), fullPage: true });
  } finally {
    await opened.context.close();
  }
});
