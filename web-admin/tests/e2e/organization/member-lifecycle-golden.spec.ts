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
  await expect(page.getByTestId('invite-section')).toBeVisible();
  const row = page.locator('table tbody tr').filter({ hasText: stamp });
  await expect(row).toHaveCount(1, { timeout: 20000 });
  await expect(row).toBeVisible();
  const statusContent = row.locator('[data-testid$="-status"] div.truncate');
  await expect(statusContent).toHaveText('已激活');
  await expect.poll(() => statusContent.evaluate((content) =>
    content.scrollWidth <= content.clientWidth),
  { message: 'Member status label is readable without clipping' }).toBe(true);
  const createdDate = row.locator('[data-testid$="-created_at"]');
  await expect(createdDate).toContainText(/\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}/);
  await expect.poll(() => createdDate.evaluate((cell) => {
    const content = cell.querySelector('div.truncate');
    const actions = cell.closest('tr')?.querySelector('td:last-child');
    return Boolean(content && actions && content.scrollWidth <= content.clientWidth &&
      cell.getBoundingClientRect().right <= actions.getBoundingClientRect().left + 1);
  }), { message: 'Full creation timestamp is readable and not covered by pinned actions' }).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('native-member-list-dates.png'), fullPage: true });
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
      const reason = page.getByTestId('form-dialog-field-reason');
      await expect(reason).toHaveAttribute('placeholder', command === 'admin:suspend_member' ? /请说明暂停原因/ : /请填写离职说明/);
      await expect(page.getByText(command === 'admin:suspend_member'
        ? '暂停后，该成员的所有登录会话将立即失效。'
        : '可补充离职背景；涉及的资源交接将在提交前确认。', { exact: true })).toBeVisible();
      await reason.fill(`E2E ${command}`);
      await capture(`${command.split(':')[1]}-input`);
      await page.getByTestId('form-dialog-submit').click();
    } else {
      await expect(page.getByTestId('confirm-dialog')).toBeVisible();
      // Assert the control is actionable — not its styling classes (the
      // row-action confirm dialog and the shared ConfirmDialog differ in
      // variant styling, which is an implementation detail).
      await expect(page.getByTestId('confirm-ok')).toBeEnabled();
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
  const memberCapabilities = ['org.cap.member_view', 'org.cap.member_offboarding', 'org.cap.member_remove', 'org.cap.tenant', 'sys.cap.saved_view_personalize', 'sys.cap.member_base'];
  const saveCapability = async (selected: string | string[] | null) => {
    const selectedCodes = selected === null ? [] : Array.isArray(selected) ? selected : [selected];
    for (const code of memberCapabilities) {
      const checkbox = page.getByTestId(`capability-checkbox-${code}`);
      await checkbox.scrollIntoViewIfNeeded();
      await checkbox.setChecked(selectedCodes.includes(code));
    }
    await page.getByTestId('capability-save').click();
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    const savedPromise = page.waitForResponse(response => response.request().method() === 'PUT' &&
      response.url().includes('/api/permission/capabilities?'));
    await page.getByTestId('confirm-ok').click();
    const response = await savedPromise;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    expect([...response.request().postDataJSON()].sort()).toEqual([...selectedCodes].sort());
    await expect(page.getByTestId('capability-save')).toBeDisabled();
  };
  await saveCapability('org.cap.member_view');
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
    const automaticInviteReads: string[] = [];
    viewerPage.on('request', request => {
      if (new URL(request.url()).pathname === '/api/tenant/invite-code/current') {
        automaticInviteReads.push(request.url());
      }
    });
    await membersLink.click();
    const row = viewerPage.locator('table tbody tr').filter({ hasText: target.displayName });
    await expect(row).toHaveCount(1);
    await expect(viewerPage.getByTestId('invite-section')).toHaveCount(0);
    await expect(viewerPage.getByRole('button', { name: '从人员开通账号', exact: true })).toHaveCount(0);
    await expect(viewerPage.locator('table').getByRole('button', { name: '暂停', exact: true })).toHaveCount(0);
    await expect(row.getByRole('button', { name: /^(同意|拒绝|暂停|恢复|离职|删除|重置密码)$/ })).toHaveCount(0);
    expect(automaticInviteReads, 'member reader must not automatically request invitation data').toEqual([]);
    await expect(viewerPage.getByTestId('invite-dialog')).toHaveCount(0);
    expect((await viewerPage.request.get('/api/tenant/invite-code/current')).status()).toBe(403);
    expect((await viewerPage.request.post('/api/tenant/invite-code/generate?expiryDays=7')).status()).toBe(403);
    expect((await viewerPage.request.post('/api/tenant/invite-code/revoke?code=unauthorized-probe')).status()).toBe(403);
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

    // The existing enterprise-profile capability owns invitation management.
    // Combine it with member read for menu access, then revoke it in the same session.
    await saveCapability(['org.cap.member_view', 'org.cap.tenant']);
    const inviteGranted = await fetchRoleSnapshot(viewerPage);
    expect(inviteGranted.permissionCodes).toContain('org.tenant.invite.manage');
    expect(inviteGranted.permissionCodes).toContain('model.tenant_member.read');
    expect(inviteGranted.permissionCodes).not.toContain('admin_tenant_member');
    expect(inviteGranted.roleCodes).not.toContain('tenant_admin');
    await viewerPage.goto('/home');
    await viewerPage.reload();
    if (!(await membersLink.isVisible())) {
      await viewerPage.locator('nav button').filter({ hasText: '组织管理' }).first().click();
    }
    await expect(membersLink).toBeVisible();
    await membersLink.click();
    await expect(viewerPage.getByTestId('invite-section')).toBeVisible();
    const inviteResponse = (action: string, method: string) => viewerPage.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === `/api/tenant/invite-code/${action}` &&
        url.origin === new URL(viewerPage.url()).origin && response.request().method() === method;
    });
    const initialInvite = inviteResponse('current', 'GET');
    await viewerPage.getByTestId('invite-section').click();
    expect((await initialInvite).status()).toBe(200);
    const inviteDialog = viewerPage.getByTestId('invite-dialog');
    await expect(inviteDialog.getByRole('heading')).toHaveText('成员邀请');
    await expect(viewerPage.getByTestId('invite-loading')).toHaveCount(0);
    await expect(viewerPage.getByTestId('invite-read-error')).toHaveCount(0);
    const generatedInvite = inviteResponse('generate', 'POST');
    const generatedReadback = inviteResponse('current', 'GET');
    await inviteDialog.getByRole('button', { name: /^生成(新)?邀请码$/ }).click();
    const generated = await generatedInvite;
    expect(generated.status()).toBe(200);
    expect(new URL(generated.url()).searchParams.get('expiryDays')).toBe('7');
    const generatedBody = await generated.json();
    expect(generatedBody.code).toBe('0');
    expect(generatedBody.data).toMatch(/^[a-z0-9]{8}$/);
    const generatedCurrent = await generatedReadback;
    expect(generatedCurrent.status()).toBe(200);
    const generatedCurrentBody = await generatedCurrent.json();
    expect(generatedCurrentBody.code).toBe('0');
    expect(generatedCurrentBody.data.code).toBe(generatedBody.data);
    await expect(viewerPage.getByTestId('invite-code-value')).toHaveText(generatedBody.data);
    await viewerPage.screenshot({ path: info.outputPath('upper-invitation-generated.png'), fullPage: true });

    const inviteRevoke = inviteResponse('revoke', 'POST');
    const revokedReadback = inviteResponse('current', 'GET');
    await inviteDialog.getByRole('button', { name: '撤销当前邀请码', exact: true }).click();
    const revokedInvite = await inviteRevoke;
    expect(revokedInvite.status()).toBe(200);
    expect(new URL(revokedInvite.url()).searchParams.get('code')).toBe(generatedBody.data);
    const revokedInviteBody = await revokedInvite.json();
    expect(revokedInviteBody.code).toBe('0');
    expect(revokedInviteBody.data).toBe(true);
    const remainingInvite = await revokedReadback;
    expect(remainingInvite.status()).toBe(200);
    const remainingBody = await remainingInvite.json();
    expect(remainingBody.code).toBe('0');
    expect(remainingBody.data?.code).not.toBe(generatedBody.data);
    const validation = await viewerPage.request.get(`/api/tenant/invite-code/validate?code=${encodeURIComponent(generatedBody.data)}`);
    expect(validation.status()).toBe(200);
    const validationBody = await validation.json();
    expect(validationBody.code).toBe('0');
    expect(validationBody.data).toBe(false);
    await expect(viewerPage.getByTestId('invite-read-error')).toHaveCount(0);
    if (remainingBody.data) {
      await expect(viewerPage.getByTestId('invite-code-value')).toHaveText(remainingBody.data.code);
    } else {
      await expect(inviteDialog).toContainText('当前没有有效邀请码');
    }
    await viewerPage.screenshot({ path: info.outputPath('upper-invitation-code-revoked.png'), fullPage: true });

    await saveCapability('org.cap.member_view');
    const inviteRemoved = await fetchRoleSnapshot(viewerPage);
    expect(inviteRemoved.permissionCodes).not.toContain('org.tenant.invite.manage');
    expect(inviteRemoved.permissionCodes).not.toContain('org.tenant.update');
    expect(inviteRemoved.permissionCodes).toContain('model.tenant_member.read');
    await viewerPage.reload();
    await expect(viewerPage.locator('table')).toBeVisible();
    await expect(viewerPage.getByTestId('invite-section')).toHaveCount(0);
    await expect(inviteDialog).toHaveCount(0);
    const deniedInviteRead = await viewerPage.request.get('/api/tenant/invite-code/current');
    const deniedInviteGenerate = await viewerPage.request.post('/api/tenant/invite-code/generate?expiryDays=7');
    const deniedInviteRevoke = await viewerPage.request.post(`/api/tenant/invite-code/revoke?code=${encodeURIComponent(generatedBody.data)}`);
    expect(deniedInviteRead.status()).toBe(403);
    expect(deniedInviteGenerate.status()).toBe(403);
    expect(deniedInviteRevoke.status()).toBe(403);
    const unchangedInvite = await page.request.get('/api/tenant/invite-code/current');
    expect(unchangedInvite.status()).toBe(200);
    const unchangedInviteBody = await unchangedInvite.json();
    expect(unchangedInviteBody.code).toBe('0');
    expect(unchangedInviteBody.data).toEqual(remainingBody.data);
    await viewerPage.screenshot({ path: info.outputPath('upper-invitation-capability-revoked.png'), fullPage: true });
    await info.attach('upper-invitation-grant-revoke', {
      body: JSON.stringify({ rolePid: role.pid, granted: inviteGranted.permissionCodes,
        removed: inviteRemoved.permissionCodes, generatedCode: generatedBody.data,
        remainingCode: remainingBody.data?.code ?? null, deniedStatuses: [403, 403, 403] }),
      contentType: 'application/json',
    });

    // Verify the existing shared-view contract through an enabled business list.
    const sharedName = `Shared member view ${stamp}`;
    const createdView = await page.request.post('/api/views', {
      data: { name: sharedName, modelCode: 'tenant_member', pageKey: 'tenant_member_list',
        viewType: 'table', scope: 'global', viewConfig: { rowHeight: 'medium' } },
    });
    expect(createdView.status()).toBe(200);
    const createdViewBody = await createdView.json();
    expect(String(createdViewBody.code)).toBe('0');
    const viewPid = createdViewBody.data.pid as string;
    expect(viewPid).toBeTruthy();
    await expect(page.getByTestId('capability-checkbox-sys.cap.saved_view_personalize'))
      .toHaveAttribute('aria-label', '维护列表视图');
    await saveCapability(['org.cap.member_view', 'sys.cap.member_base', 'sys.cap.saved_view_personalize']);
    const viewGranted = await fetchRoleSnapshot(viewerPage);
    expect(viewGranted.permissionCodes).toContain('dashboard.saved_view.update');
    expect(viewGranted.permissionCodes).not.toContain('admin_tenant_member');
    const selectSharedView = async () => {
      await viewerPage.getByTestId('view-selector-trigger').click();
      const dropdown = viewerPage.getByRole('listbox');
      const option = dropdown.getByRole('option').filter({ hasText: sharedName });
      await expect(option).toHaveCount(1);
      await option.click();
      await expect(viewerPage.getByTestId('view-selector-trigger')).toContainText(sharedName);
    };
    await viewerPage.reload();
    await expect(row).toHaveCount(1);
    await selectSharedView();
    await viewerPage.getByTestId('row-height-btn').click();
    await viewerPage.getByTestId('row-height-option-tall').click();
    await expect(viewerPage.getByTestId('shared-view-draft-banner')).toBeVisible();
    const beforeSave = await page.request.get(`/api/views/${viewPid}`);
    expect(beforeSave.status()).toBe(200);
    expect((await beforeSave.json()).data.viewConfig.rowHeight).toBe('medium');
    await expect(viewerPage.getByTestId('shared-view-save')).toBeEnabled();
    await viewerPage.getByTestId('shared-view-save').click();
    const viewSavedPromise = viewerPage.waitForResponse(response => {
      const url = new URL(response.url());
      return url.origin === new URL(viewerPage.url()).origin &&
        url.pathname === `/api/views/${viewPid}` && response.request().method() === 'PUT';
    });
    await acceptConfirmDialog(viewerPage);
    const viewSaved = await viewSavedPromise;
    expect(viewSaved.status()).toBe(200);
    expect(viewSaved.request().postDataJSON().viewConfig.rowHeight).toBe('tall');
    expect(String((await viewSaved.json()).code)).toBe('0');
    const storedView = await page.request.get(`/api/views/${viewPid}`);
    expect(storedView.status()).toBe(200);
    expect((await storedView.json()).data.viewConfig.rowHeight).toBe('tall');
    await viewerPage.reload();
    await selectSharedView();
    await expect(viewerPage.getByTestId('shared-view-draft-banner')).toHaveCount(0);
    await viewerPage.screenshot({ path: info.outputPath('upper-list-view-shared-saved.png'), fullPage: true });

    await saveCapability(['org.cap.member_view', 'sys.cap.member_base']);
    const viewRemoved = await fetchRoleSnapshot(viewerPage);
    expect(viewRemoved.permissionCodes).not.toContain('dashboard.saved_view.update');
    expect(viewRemoved.permissionCodes).toContain('dashboard.saved_view.read');
    expect(viewRemoved.permissionCodes).toContain('model.tenant_member.read');
    await viewerPage.reload();
    await selectSharedView();
    await viewerPage.getByTestId('row-height-btn').click();
    await viewerPage.getByTestId('row-height-option-short').click();
    await expect(viewerPage.getByTestId('shared-view-draft-banner')).toBeVisible();
    await expect(viewerPage.getByTestId('shared-view-save-disabled')).toBeDisabled();
    const draftBounds = await viewerPage.getByTestId('shared-view-draft-banner').boundingBox();
    const selectorBounds = await viewerPage.getByTestId('view-selector-trigger').boundingBox();
    expect(draftBounds).not.toBeNull();
    expect(selectorBounds).not.toBeNull();
    expect(draftBounds!.y + draftBounds!.height).toBeLessThanOrEqual(selectorBounds!.y);
    const deniedViewWrite = await viewerPage.request.put(`/api/views/${viewPid}`, {
      data: { viewConfig: { rowHeight: 'short' } },
    });
    expect(deniedViewWrite.status()).toBe(403);
    const unchangedView = await page.request.get(`/api/views/${viewPid}`);
    expect(unchangedView.status()).toBe(200);
    expect((await unchangedView.json()).data.viewConfig.rowHeight).toBe('tall');
    await viewerPage.screenshot({ path: info.outputPath('upper-list-view-maintenance-revoked.png'), fullPage: true });
    await info.attach('upper-shared-view-grant-revoke', {
      body: JSON.stringify({ rolePid: role.pid, viewPid, scope: 'global',
        granted: viewGranted.permissionCodes, removed: viewRemoved.permissionCodes,
        persistedRowHeight: 'tall', deniedWriteStatus: deniedViewWrite.status() }),
      contentType: 'application/json',
    });

    await saveCapability(null);
    const revokedReadPromise = viewerPage.waitForResponse(response => new URL(response.url()).pathname === `/api/tenant/members/${pid}`);
    await viewerPage.goto(`/organization/members/${pid}`);
    const revoked = await revokedReadPromise;
    expect(revoked.status()).toBe(403);
    await expect(viewerPage.getByTestId('member-name')).toHaveCount(0);
    await expect(viewerPage.getByTestId('member-status')).toHaveCount(0);
    await expect(viewerPage.getByTestId('member-load-error')).toHaveAttribute('data-error-kind', 'forbidden');
    await expect(viewerPage.getByText('无权查看此成员', { exact: true })).toBeVisible();
    await expect(viewerPage.getByText('成员不存在', { exact: true })).toHaveCount(0);
    await expect(viewerPage.getByText('Access forbidden', { exact: true })).toHaveCount(0);
    await viewerPage.screenshot({ path: info.outputPath('independent-member-read-revoked.png'), fullPage: true });

    // Grant each independent lifecycle capability through the same upper editor.
    // Keep the old session: role changes must affect its next request and UI reload.
    const openTargetFromMenu = async () => {
      await viewerPage.goto('/home');
      await viewerPage.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
      await viewerPage.reload();
      const link = viewerPage.locator('nav a[href="/p/tenant_member"]');
      if (!(await link.isVisible())) {
        await viewerPage.locator('nav button').filter({ hasText: '组织管理' }).first().click();
      }
      await expect(link).toBeVisible();
      await link.click();
      const targetRow = viewerPage.locator('table tbody tr').filter({ hasText: target.displayName });
      await expect(targetRow).toHaveCount(1);
      await targetRow.click();
      await expect(viewerPage).toHaveURL(new RegExp(`/organization/members/${pid}$`));
      await expect(viewerPage.getByTestId('member-name')).toHaveText(target.displayName);
    };
    const capture = async (name: string, client = viewerPage) => {
      await client.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true });
    };
    const denyLifecycle = async (action: string, commandCode: string) => {
      const impact = await viewerPage.request.get(`/api/tenant/members/${pid}/offboarding-impact?action=${action}`);
      expect(impact.status()).toBe(403);
      const command = await viewerPage.request.post(`/api/meta/commands/execute/${commandCode}`, {
        data: { targetRecordPid: pid, operationType: commandCode === 'admin:delete_member' ? 'DELETE' : 'UPDATE', payload: { reason: 'Independent lifecycle denial' } },
      });
      expect(command.status()).toBe(403);
      const stored = await page.request.get(`/api/tenant/members/${pid}`);
      expect(stored.status()).toBe(200);
      const body = await stored.json();
      expect(String(body.code)).toBe('0');
      expect(body.data.pid).toBe(pid);
      return body.data.status as string;
    };

    await saveCapability('org.cap.member_offboarding');
    await openTargetFromMenu();
    const offboarding = await fetchRoleSnapshot(viewerPage);
    expect(offboarding.permissionCodes).toContain('model.tenant_member.leave');
    for (const code of ['admin_tenant_member', 'model.tenant_member.delete', 'model.tenant_member.suspend']) {
      expect(offboarding.permissionCodes).not.toContain(code);
    }
    await expect(viewerPage.getByTestId('action-bar').getByRole('button', { name: '离职', exact: true })).toBeVisible();
    await expect(viewerPage.getByTestId('action-bar').getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
    expect(await denyLifecycle('remove', 'admin:delete_member')).toBe('active');
    await capture('independent-member-remove-denied');

    const leaveImpact = viewerPage.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === `/api/tenant/members/${pid}/offboarding-impact` && url.searchParams.get('action') === 'deactivate';
    });
    await viewerPage.getByTestId('action-bar').getByRole('button', { name: '离职', exact: true }).click();
    const leavePreflight = await leaveImpact;
    expect(leavePreflight.status()).toBe(200);
    const leavePreflightBody = await leavePreflight.json();
    expect(String(leavePreflightBody.code)).toBe('0');
    expect(leavePreflightBody.data.transferRequired, 'controlled member owns no resources').toBe(false);
    await expect(viewerPage.getByTestId('form-dialog').getByRole('heading')).toHaveText('办理离职');
    const reason = `Independent offboarding ${stamp}`;
    await viewerPage.getByTestId('form-dialog-field-reason').fill(reason);
    await capture('independent-member-offboarding-confirm');
    const leaveResponse = viewerPage.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/meta/commands/execute/admin:leave_member');
    await viewerPage.getByTestId('form-dialog-submit').click();
    const left = await leaveResponse;
    expect(left.status()).toBe(200);
    expect(left.request().postDataJSON().targetRecordPid).toBe(pid);
    expect(left.request().postDataJSON().payload.reason).toBe(reason);
    expect(String((await left.json()).code)).toBe('0');
    await expect(viewerPage.getByTestId('member-status')).toHaveAttribute('data-status', 'inactive');
    const leftRead = await page.request.get(`/api/tenant/members/${pid}`);
    expect(leftRead.status()).toBe(200);
    const leftBody = await leftRead.json();
    expect(String(leftBody.code)).toBe('0');
    expect(leftBody.data.pid).toBe(pid);
    expect(leftBody.data.status).toBe('inactive');
    await viewerPage.reload();
    await expect(viewerPage.getByTestId('member-status')).toHaveText('已离职');
    await capture('independent-member-offboarded');

    await saveCapability('org.cap.member_view');
    await viewerPage.reload();
    expect(await denyLifecycle('deactivate', 'admin:leave_member')).toBe('inactive');
    await expect(viewerPage.getByTestId('action-bar').getByRole('button')).toHaveCount(0);
    await expect(viewerPage.getByTestId('member-name')).toHaveText(target.displayName);
    await capture('independent-member-offboarding-revoked');

    await saveCapability('org.cap.member_remove');
    await openTargetFromMenu();
    const removal = await fetchRoleSnapshot(viewerPage);
    expect(removal.permissionCodes).toContain('model.tenant_member.delete');
    for (const code of ['admin_tenant_member', 'model.tenant_member.leave', 'model.tenant_member.suspend']) {
      expect(removal.permissionCodes).not.toContain(code);
    }
    await expect(viewerPage.getByTestId('action-bar').getByRole('button', { name: '删除', exact: true })).toBeVisible();
    const removeImpact = viewerPage.waitForResponse(response => {
      const url = new URL(response.url());
      return url.pathname === `/api/tenant/members/${pid}/offboarding-impact` && url.searchParams.get('action') === 'remove';
    });
    await viewerPage.getByTestId('action-bar').getByRole('button', { name: '删除', exact: true }).click();
    const removePreflight = await removeImpact;
    expect(removePreflight.status()).toBe(200);
    const removePreflightBody = await removePreflight.json();
    expect(String(removePreflightBody.code)).toBe('0');
    expect(removePreflightBody.data.transferRequired).toBe(false);
    await expect(viewerPage.getByTestId('confirm-dialog')).toBeVisible();
    await capture('independent-member-removal-confirm');
    const removeResponse = viewerPage.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/meta/commands/execute/admin:delete_member');
    await acceptConfirmDialog(viewerPage);
    const removed = await removeResponse;
    expect(removed.status()).toBe(200);
    expect(removed.request().postDataJSON().targetRecordPid).toBe(pid);
    expect(String((await removed.json()).code)).toBe('0');
    await expect(viewerPage).toHaveURL(/\/p\/tenant_member$/);
    const afterRemoval = await page.request.post('/api/tenant/members/search', {
      data: { keyword: target.email, pageNum: 1, pageSize: 50 },
    });
    expect(afterRemoval.status()).toBe(200);
    const afterRemovalBody = await afterRemoval.json();
    expect(String(afterRemovalBody.code)).toBe('0');
    expect(afterRemovalBody.data.records.some((record: { pid: string }) => record.pid === pid)).toBe(false);
    await expect(viewerPage.locator('table')).toBeVisible();
    await expect(viewerPage.locator('table').getByRole('columnheader').first()).toBeVisible();
    await expect(viewerPage.locator('table tbody')).not.toContainText(/加载中|Loading/i);
    await expect(viewerPage.locator('table tbody tr').first()).toBeVisible();
    await expect(viewerPage.locator('table tbody')).not.toContainText(/暂无数据|No data/i);
    await expect(viewerPage.locator('table tbody tr').filter({ hasText: target.displayName })).toHaveCount(0);
    await expect(viewerPage.locator('table').getByRole('button', { name: '暂停', exact: true })).toHaveCount(0);
    await expect(viewerPage.getByRole('button', { name: '从人员开通账号', exact: true })).toHaveCount(0);
    await expect(viewerPage.getByTestId('invite-section')).toHaveCount(0);
    await capture('independent-member-removed');

    // Test revocation against an existing member, not the deleted target's 404.
    const actorSearch = await page.request.post('/api/tenant/members/search', {
      data: { keyword: viewer.email, pageNum: 1, pageSize: 50 },
    });
    expect(actorSearch.status()).toBe(200);
    const actorBody = await actorSearch.json();
    expect(String(actorBody.code)).toBe('0');
    const actorMember = actorBody.data.records.find((record: { user?: { email: string } }) => record.user?.email === viewer.email);
    expect(actorMember).toBeDefined();
    await saveCapability(null);
    expect((await viewerPage.request.get(`/api/tenant/members/${actorMember.pid}`)).status()).toBe(403);
    expect((await viewerPage.request.get(`/api/tenant/members/${actorMember.pid}/offboarding-impact?action=remove`)).status()).toBe(403);
    await viewerPage.goto('/home');
    await viewerPage.reload();
    await expect(viewerPage.locator('nav a[href="/p/tenant_member"]')).toHaveCount(0);
    const revokedSidebar = viewerPage.locator('nav');
    await expect(revokedSidebar).toBeVisible();
    await revokedSidebar.screenshot({ path: info.outputPath('independent-member-removal-revoked.png') });
  } finally {
    await opened.context.close();
  }
});
