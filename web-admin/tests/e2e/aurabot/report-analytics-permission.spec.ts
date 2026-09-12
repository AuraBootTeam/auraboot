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
  expect(
    (
      await page.request.put(`/api/report-definitions/${pid}`, {
        data: {
          title: key,
          profile: 'paged-media',
          dsl: { ...dsl, description: 'Second revision' },
        },
      })
    ).status(),
  ).toBe(200);

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

      await expect(session.page.getByTestId('report-reader-toolbar')).toContainText('只读报表');
      await expect(session.page.getByPlaceholder('Report Title')).toHaveCount(0);
      for (const name of ['Preview', 'Edit', 'Settings', '保存'])
        await expect(session.page.getByRole('button', { name, exact: true })).toHaveCount(0);
      const writes: string[] = [];
      session.page.on('request', (request) => {
        if (
          request.url().includes('/api/report-definitions') &&
          ['POST', 'PUT', 'DELETE'].includes(request.method())
        )
          writes.push(request.url());
      });
      await session.page.keyboard.press('ControlOrMeta+s');
      const stillSaved = await session.page.request.get(`/api/report-definitions/${pid}`);
      expect((await stillSaved.json()).data.dsl.description).toBe('Second revision');
      expect(writes).toEqual([]);

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
        await expect(session.page.getByRole('alert')).toContainText('当前账号无权读取');
        await expect(session.page.getByRole('alert')).toContainText('当前没有可用结果');
        const retried = session.page.waitForResponse(
          (response) => response.url().includes('/list?') && response.status() === 403,
        );
        await session.page.getByRole('button', { name: '重新查询', exact: true }).click();
        await retried;
        await expect(session.page.getByRole('alert')).toContainText('当前没有可用结果');
        await expect(session.page.getByRole('cell', { name: key, exact: true })).toHaveCount(0);
        for (const format of ['JSON', 'Excel', 'PDF'])
          await expect(
            session.page.getByRole('button', { name: `Export ${format}`, exact: true }),
          ).toBeDisabled();
      }

      await session.page.getByRole('button', { name: 'Version History', exact: true }).click();
      await session.page.getByRole('button', { name: /^v1\b/ }).click();
      await expect(session.page.getByTestId('report-version-preview')).toBeVisible();
      await expect(session.page.getByRole('button', { name: /^(回滚|Rollback)$/ })).toHaveCount(0);
      await session.page.getByRole('button', { name: '返回当前报表', exact: true }).click();
      await expect(session.page.getByTestId('report-reader-toolbar')).toBeVisible();
      expect(writes).toEqual([]);
      await session.page.getByRole('button', { name: /关闭版本面板|Close version/ }).click();
      await expect(session.page.getByTestId('version-history-panel')).not.toBeInViewport();
      await expect(session.page.getByText('正在查询报表数据…', { exact: true })).toHaveCount(0);
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

test('named-query reports require source and declared resource permissions', async ({
  page,
  browser,
}, testInfo) => {
  test.setTimeout(180_000);
  const key = `nqr_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
  expect(
    (
      await executeCommandViaApi(
        page,
        'e2et:create_order',
        {
          e2et_order_title: key,
          e2et_order_type: 'normal',
          e2et_order_customer: 'Named query permissions',
          e2et_order_urgent: false,
        },
        undefined,
        'create',
      )
    ).code,
  ).toBe('0');
  const query = await page.request.post('/api/meta/named-queries', {
    data: {
      code: key,
      title: key,
      resourceCode: 'e2et_order',
      actionCode: 'read',
      fromSql: `SELECT o.e2et_order_title FROM mt_e2et_order o WHERE o.tenant_id = #{params.tenantId} AND o.e2et_order_title = '${key}'`,
      fields: [
        {
          fieldCode: 'e2et_order_title',
          columnExpr: 'e2et_order_title',
          dataType: 'string',
          displayName: 'Title',
          sortable: true,
          searchable: true,
          sortOrder: 1,
        },
      ],
    },
  });
  expect(query.status()).toBe(200);
  expect((await query.json()).code).toBe('0');
  const dsl = {
    $schema: 'auraboot://schemas/report/v1',
    version: '1.0.0',
    title: key,
    page: {
      size: 'A4',
      orientation: 'portrait',
      margin: { top: 20, right: 20, bottom: 20, left: 20 },
    },
    dataSources: { orders: { type: 'namedQuery', queryCode: key } },
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
  for (const mode of ['source-denied', 'resource-denied', 'allowed']) {
    const code = `${key}_${mode.replaceAll('-', '_')}`;
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
      ...(mode !== 'source-denied' ? ['data.datasource.read'] : []),
      ...(mode !== 'resource-denied' ? ['model.e2et_order.read'] : []),
    ];
    expect(
      (
        await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: codes.map((permission) => {
            expect(permissions.get(permission), permission).toBeTruthy();
            return { permissionId: permissions.get(permission), granted: true };
          }),
        })
      ).status(),
    ).toBe(200);
    const user = makeRoleUser(code, [code]);
    await ensureRoleUser(page, user);
    const session = await openAsRole(browser, user.email, user.password);
    try {
      const snapshot = await fetchRoleSnapshot(session.page);
      expect(snapshot.roleCodes).not.toContain('tenant_admin');
      expect(snapshot.permissionCodes.includes('data.datasource.read')).toBe(
        mode !== 'source-denied',
      );
      expect(snapshot.permissionCodes.includes('model.e2et_order.read')).toBe(
        mode !== 'resource-denied',
      );
      const statuses = [];
      for (const format of ['json', 'excel', 'pdf']) {
        const response = await session.page.request.post(`/api/reports/export/${format}`, {
          data: { reportPid: pid },
        });
        expect(response.status(), `${mode} ${format}`).toBe(mode === 'allowed' ? 200 : 403);
        if (mode === 'allowed' && format === 'json')
          expect((await response.json()).dataSets.orders).toEqual([{ e2et_order_title: key }]);
        statuses.push({ format, status: response.status() });
      }
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
      await expect(session.page.getByTestId('report-reader-toolbar')).toBeVisible();
      if (mode === 'allowed') {
        await expect(session.page.getByRole('cell', { name: key, exact: true })).toBeVisible();
        const download = session.page.waitForEvent('download');
        await session.page.getByRole('button', { name: 'Export JSON', exact: true }).click();
        const path = `${process.env.AURA_EVIDENCE_DIR}/report-named-allowed.json`;
        await (await download).saveAs(path);
        expect(JSON.parse(await readFile(path, 'utf8')).dataSets.orders).toEqual([
          { e2et_order_title: key },
        ]);
      } else {
        await expect(session.page.getByRole('alert')).toContainText('查询未成功');
        await expect(session.page.getByRole('alert')).toContainText('当前账号无权读取');
        await expect(session.page.getByRole('alert')).toContainText('当前没有可用结果');
        const retried = session.page.waitForResponse(
          (response) => response.url().includes('/list?') && response.status() === 403,
        );
        await session.page.getByRole('button', { name: '重新查询', exact: true }).click();
        await retried;
        await expect(session.page.getByRole('alert')).toContainText('当前没有可用结果');
        await expect(session.page.getByRole('cell', { name: key, exact: true })).toHaveCount(0);
        for (const format of ['JSON', 'Excel', 'PDF'])
          await expect(
            session.page.getByRole('button', { name: `Export ${format}`, exact: true }),
          ).toBeDisabled();
      }
      await session.page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/report-named-${mode}.png`,
        fullPage: true,
      });
      evidence.push({ mode, statuses });
    } finally {
      await session.context.close();
    }
  }
  await testInfo.attach('named-query-report-permission-matrix', {
    body: JSON.stringify(evidence),
    contentType: 'application/json',
  });
});
