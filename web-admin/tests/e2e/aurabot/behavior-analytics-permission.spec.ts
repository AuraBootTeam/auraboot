/** Real custom-role grants, baseline denial, and menu/API agreement. */
import { test, expect } from '../../fixtures';
import { randomUUID } from 'node:crypto';
import { ensureRoleUser, makeRoleUser, openAsRole, fetchRoleSnapshot } from '../rbac/rbac-helpers';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('behavior reads require dashboard permission for both APIs and menu', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(120000);
  const code = `analytics_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
  const created = await page.request.post('/api/roles', {
    data: {
      code,
      name: 'Analytics reader fixture',
      type: 'custom',
      status: 'active',
      scopeType: 'tenant',
      defaultDataScopeType: 'all',
    },
  });
  expect(created.status()).toBe(200);
  const rolePid = (await created.json()).data.pid;
  expect(rolePid).toBeTruthy();
  const tree = await page.request.get('/api/permissions/tree');
  expect(tree.status()).toBe(200);
  const find = (nodes: any[]): any => {
    for (const node of nodes) {
      if (node.code === 'dashboard.read') return node;
      const child = find(node.children || []);
      if (child) return child;
    }
  };
  const permission = find((await tree.json()).data);
  expect(permission?.id).toBeTruthy();
  const grant = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
    data: [{ permissionId: permission.id, granted: true }],
  });
  expect(grant.status()).toBe(200);
  const users = [
    makeRoleUser(`${code}-denied`, ['tenant_member']),
    makeRoleUser(`${code}-reader`, [code]),
  ];
  for (const user of users) await ensureRoleUser(page, user);
  const paths = [
    'overview',
    'top-events',
    'daily',
    'analysis-funnel',
    'executions',
    'retention?unit=user',
    'retention?unit=artifact',
  ];
  const window = {
    from: new Date(Date.now() - 30 * 86400000).toISOString(),
    to: new Date().toISOString(),
  };
  const rows = (body: any, path: string) => {
    if (path === 'executions') {
      const { dataCutoff, ...data } = body.data;
      expect(dataCutoff).toBeTruthy();
      return data;
    }
    return body.data?.records ?? body;
  };
  const expected = new Map<string, unknown>();
  for (const path of paths) {
    const response = await page.request.get(`/api/analytics/behavior/${path}`, { params: window });
    expect(response.status()).toBe(200);
    expected.set(path, rows(await response.json(), path));
  }
  const evidence: unknown[] = [];
  for (const [index, user] of users.entries()) {
    const session = await openAsRole(browser, user.email, user.password);
    try {
      const snapshot = await fetchRoleSnapshot(session.page);
      expect(snapshot.roleCodes).not.toContain('tenant_admin');
      expect(snapshot.permissionCodes.includes('dashboard.read')).toBe(index === 1);
      expect(snapshot.menuPaths.includes('/p/c/behavior_analytics')).toBe(index === 1);
      const results = [];
      for (const path of paths) {
        const response = await session.page.request.get(`/api/analytics/behavior/${path}`, {
          params: window,
        });
        expect(response.status(), `${user.key}: ${path}`).toBe(index === 1 ? 200 : 403);
        if (index === 1)
          expect(rows(await response.json(), path), path).toEqual(expected.get(path));
        results.push({ path, status: response.status() });
      }
      if (index === 0)
        await expect(
          session.page.getByTestId('sidebar').getByText('行为分析', { exact: true }),
        ).toHaveCount(0);
      if (index === 1) {
        const sidebar = session.page.getByTestId('sidebar');
        const entry = sidebar.getByText('行为分析', { exact: true });
        if (!(await entry.isVisible())) {
          await sidebar.getByText('数据分析', { exact: true }).click();
        }
        await expect(entry).toBeVisible();
        const [response] = await Promise.all([
          session.page.waitForResponse((r) =>
            r.url().includes('/api/analytics/behavior/analysis-funnel'),
          ),
          entry.click(),
        ]);
        expect(response.status()).toBe(200);
        await expect(
          session.page.getByRole('heading', { name: '分析任务转化', exact: true }),
        ).toBeVisible();
      }
      if (index === 1) {
        const revoked = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [{ permissionId: permission.id, granted: false }],
        });
        expect(revoked.status()).toBe(200);
        await expect
          .poll(async () =>
            (await fetchRoleSnapshot(session.page)).permissionCodes.includes('dashboard.read'),
          )
          .toBe(false);
        for (const path of paths) {
          const denied = await session.page.request.get(`/api/analytics/behavior/${path}`, {
            params: window,
          });
          expect(denied.status(), `revoked: ${path}`).toBe(403);
          results.push({ path: `revoked:${path}`, status: denied.status() });
        }
        await session.page.reload();
        await expect(
          session.page.getByTestId('sidebar').getByText('行为分析', { exact: true }),
        ).toHaveCount(0);
      }
      evidence.push({ role: user.roleCodes, snapshot, results });
    } finally {
      await session.context.close();
    }
  }
  await testInfo.attach('role-read-matrix', {
    body: JSON.stringify(evidence, null, 2),
    contentType: 'application/json',
  });
});
