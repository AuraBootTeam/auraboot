import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { test, expect } from '../../fixtures';
import { executeCommandViaApi } from '../helpers';
import { ensureRoleUser, makeRoleUser, openAsRole, fetchRoleSnapshot } from '../rbac/rbac-helpers';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

test('report export requires model read permission in addition to artifact permissions', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(180_000);
  const key = `rpt_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
  const order = await executeCommandViaApi(
    page,
    'e2et:create_order',
    {
      e2et_order_title: key,
      e2et_order_type: 'normal',
      e2et_order_customer: 'Permission fixture',
      e2et_order_urgent: false,
    },
    undefined,
    'create',
  );
  expect(order.code).toBe('0');
  const dsl = {
    $schema: 'auraboot://schemas/report/v1',
    version: '1.0.0',
    title: key,
    page: {
      size: 'A4',
      orientation: 'portrait',
      margin: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    dataSources: {
      orders: {
        type: 'model',
        modelCode: 'e2et_order',
        filters: [{ field: 'e2et_order_title', operator: 'EQ', value: key }],
      },
    },
    body: [
      {
        id: 'orders',
        blockType: 'table',
        title: 'Orders',
        dataSource: 'orders',
        columns: [{ field: 'e2et_order_title', label: 'Title' }],
        showHeader: true,
      },
    ],
  };
  const created = await page.request.post('/api/report-definitions', {
    data: { code: key, title: key, profile: 'paged-media', dsl },
  });
  expect(created.status()).toBe(200);
  const { pid } = (await created.json()).data;
  const tree = await page.request.get('/api/permissions/tree');
  expect(tree.status()).toBe(200);
  const permissions = new Map<string, unknown>();
  const collect = (nodes: any[]) => {
    for (const node of nodes) {
      permissions.set(node.code, node.id);
      collect(node.children ?? []);
    }
  };
  collect((await tree.json()).data);
  const evidence = [];
  for (const hasModelRead of [false, true]) {
    const code = `${key}_${hasModelRead ? 'reader' : 'exporter'}`;
    const role = await page.request.post('/api/roles', {
      data: {
        code,
        name: code,
        type: 'custom',
        status: 'active',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    });
    expect(role.status()).toBe(200);
    const rolePid = (await role.json()).data.pid;
    const codes = [
      'report.definition.view',
      'report.export.execute',
      ...(hasModelRead ? ['model.e2et_order.read'] : []),
    ];
    const grants = codes.map((permission) => {
      expect(permissions.get(permission), permission).toBeTruthy();
      return { permissionId: permissions.get(permission), granted: true };
    });
    expect(
      (
        await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })
      ).status(),
    ).toBe(200);
    const user = makeRoleUser(code, [code]);
    await ensureRoleUser(page, user);
    const session = await openAsRole(browser, user.email, user.password);
    try {
      const snapshot = await fetchRoleSnapshot(session.page);
      expect(snapshot.roleCodes).not.toContain('tenant_admin');
      expect(snapshot.permissionCodes.includes('model.e2et_order.read')).toBe(hasModelRead);
      const definition = await session.page.request.get(`/api/report-definitions/${pid}`);
      expect(definition.status()).toBe(200);
      expect(
        (await session.page.request.get(`/api/report-definitions/${pid}/versions`)).status(),
      ).toBe(200);
      expect(
        (
          await session.page.request.put(`/api/report-definitions/${pid}`, {
            data: { title: 'forbidden', profile: 'paged-media', dsl },
          })
        ).status(),
      ).toBe(403);
      const statuses = [];
      for (const format of ['json', 'excel', 'pdf']) {
        const response = await session.page.request.post(`/api/reports/export/${format}`, {
          data: { reportPid: pid },
        });
        expect(response.status(), `${code} ${format}`).toBe(hasModelRead ? 200 : 403);
        if (hasModelRead && format === 'json') {
          const data = await response.json();
          expect(data.dataSets.orders).toHaveLength(1);
          expect(data.dataSets.orders[0].e2et_order_title).toBe(key);
        }
        statuses.push({ format, status: response.status() });
      }

      expect(snapshot.menuPaths).toContain('/p/c/report_management');
      expect(snapshot.menuPaths).not.toContain('/meta/models');
      expect(snapshot.menuPaths).not.toContain('/meta/fields');
      const sidebar = session.page.getByTestId('sidebar');
      const entry = sidebar.locator('a[href="/p/c/report_management"]');
      if (!(await entry.isVisible()))
        await sidebar.getByText('元数据管理', { exact: true }).click();
      await entry.evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await entry.click();
      await session.page
        .getByRole('row')
        .filter({ hasText: key })
        .getByRole('button', { name: /打开|Open/ })
        .click();
      await session.page.getByRole('button', { name: 'Preview', exact: true }).click();
      if (hasModelRead) {
        await expect(session.page.getByRole('cell', { name: key, exact: true })).toBeVisible();
        const event = session.page.waitForEvent('download');
        await session.page.getByRole('button', { name: 'Export JSON', exact: true }).click();
        const artifact = await event;
        const path = `${process.env.AURA_EVIDENCE_DIR}/report-role-allowed.json`;
        await artifact.saveAs(path);
        expect(JSON.parse(await readFile(path, 'utf8')).dataSets.orders[0].e2et_order_title).toBe(
          key,
        );
      } else {
        await expect(session.page.getByRole('alert')).toContainText('查询未成功');
        await expect(session.page.getByRole('cell', { name: key, exact: true })).toHaveCount(0);
        for (const format of ['JSON', 'Excel', 'PDF'])
          await expect(
            session.page.getByRole('button', { name: `Export ${format}`, exact: true }),
          ).toBeDisabled();
      }
      await session.page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/report-role-${hasModelRead ? 'allowed' : 'denied'}.png`,
        fullPage: true,
      });
      evidence.push({ hasModelRead, statuses });
    } finally {
      await session.context.close();
    }
  }
  await testInfo.attach('report-permission-matrix', {
    body: JSON.stringify(evidence),
    contentType: 'application/json',
  });
});
