import { test, expect } from '../../fixtures';
import { uniqueId, acceptConfirmDialog } from '../helpers';

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
  await capture('native-member-removed-list');
});
