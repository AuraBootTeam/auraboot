import { requireRuntimeUrl } from '../../helpers/environments';
import fs from 'node:fs';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { isDeepStrictEqual } from 'node:util';
import { test, expect, type Response } from '@playwright/test';

// API calls provision the global shared artifact and roles only. Source queries
// originate from the published viewer after two independent real BFF UI logins.
test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});
test.setTimeout(240000);
const sourceRead = 'model.tpm_project.read';
const root = path.resolve(process.cwd(), '../plugins/project-management');
const template = JSON.parse(
  fs.readFileSync(path.join(root, 'examples/dashboard-customer-delivery.json'), 'utf8'),
);
const references: unknown[][] = JSON.parse(
  fs.readFileSync(path.join(root, 'scripts/reference-projects.json'), 'utf8'),
);

test('SC04-PERMISSIONS global dashboard viewing does not grant customer-project source access', async ({
  page,
  browser,
}, info) => {
  const origin = new URL(requireRuntimeUrl('base')).origin;
  expect(new URL(origin).hostname).toBe('localhost');
  expect(process.env.PW_ADMIN_STORAGE_STATE).toBeTruthy();
  expect(
    process.env.DELIVERY_FIXTURE_PATH,
    'reuse the retained shared project fixture',
  ).toBeTruthy();
  const fixture = JSON.parse(fs.readFileSync(process.env.DELIVERY_FIXTURE_PATH!, 'utf8'));
  const expected = {
    count: fixture.projects.length,
    budget: references.reduce((sum, row) => sum + Number(String(row[11]).replace(/,/g, '')), 0),
    acceptance: references.filter((row) => row[3] === '待验收').length,
    completed: references.filter((row) => row[3] === '已交付').length,
  };
  await page.goto('/home');
  const adminIdentity = await page.request.get('/api/auth/me');
  expect(adminIdentity.status()).toBe(200);
  const tenantId = String((await adminIdentity.json()).data.user.tenantId);
  expect(tenantId).toMatch(/^\d+$/);
  const source = await page.request.get('/api/dynamic/tpm_project/list?pageNum=1&pageSize=500');
  expect(source.status()).toBe(200);
  const sourceBody = await source.json();
  expect(String(sourceBody.code)).toBe('0');
  expect(sourceBody.data.records.map((row: { pid: string }) => row.pid).sort()).toEqual(
    expect.arrayContaining(fixture.projects.map((row: { pid: string }) => row.pid).sort()),
  );
  expect(sourceBody.data.total).toBe(sourceBody.data.records.length);
  const fixtureIds = new Set(fixture.projects.map((row: { pid: string }) => row.pid));
  const baseline = sourceBody.data.records.filter((row: any) => fixtureIds.has(row.pid));
  expect(baseline.reduce((sum: number, row: any) => sum + Number(row.tpm_pj_budget), 0)).toBe(
    expected.budget,
  );
  expected.count = sourceBody.data.records.length;
  expected.budget = sourceBody.data.records.reduce(
    (sum: number, row: any) => sum + Number(row.tpm_pj_budget),
    0,
  );
  expected.acceptance = sourceBody.data.records.filter(
    (row: any) => row.tpm_pj_status === 'acceptance',
  ).length;
  expected.completed = sourceBody.data.records.filter(
    (row: any) => row.tpm_pj_status === 'completed',
  ).length;
  const key = `delivery_perm_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
  const title = `客户交付看板源权限-${key}`;
  const monetaryDashboard = structuredClone(template.dashboard);
  monetaryDashboard.widgets.find(
    (widget: { id: string }) => widget.id === 'project-budget',
  ).config.precision = 2;
  const created = await page.request.post('/api/dashboards', {
    data: { ...monetaryDashboard, code: key, title, scope: 'global' },
  });
  expect(created.status()).toBe(200);
  const createdBody = await created.json();
  expect(String(createdBody.code)).toBe('0');
  const dashboardPid = createdBody.data.pid;
  expect(dashboardPid).toMatch(/^[0-9A-Z]{26}$/);
  const published = await page.request.post(`/api/dashboards/${dashboardPid}/publish`);
  expect(published.status()).toBe(200);
  expect(String((await published.json()).code)).toBe('0');
  const definition = await page.request.get(`/api/dashboards/${dashboardPid}`);
  const publishedDefinition = (await definition.json()).data;
  expect(publishedDefinition).toMatchObject({
    pid: dashboardPid,
    code: key,
    scope: 'global',
    status: 'published',
  });
  const tree = await page.request.get('/api/permissions/tree');
  expect(tree.status()).toBe(200);
  const permissionIds = new Map<string, number>();
  type PermissionNode = { code: string; id: number; children?: PermissionNode[] };
  const collect = (nodes: PermissionNode[]) =>
    nodes.forEach((node) => {
      permissionIds.set(node.code, node.id);
      collect(node.children || []);
    });
  collect((await tree.json()).data);
  const receipts: unknown[] = [];
  let llmRequests = 0;
  for (const access of ['joined', 'project', 'artifact'] as const) {
    const hasSourceRead = access !== 'artifact';
    const hasCustomerRead = access === 'joined';
    const evidenceLabel =
      access === 'joined'
        ? 'source-reader'
        : access === 'project'
          ? 'project-only'
          : 'artifact-only';
    const roleCode = `${key}_${access}`;
    const roleResponse = await page.request.post('/api/roles', {
      data: {
        code: roleCode,
        name: roleCode,
        type: 'custom',
        status: 'active',
        scopeType: 'tenant',
        defaultDataScopeType: 'all',
      },
    });
    expect(roleResponse.status()).toBe(200);
    const roleBody = await roleResponse.json();
    expect(String(roleBody.code)).toBe('0');
    const rolePid = roleBody.data.pid;
    const grants = [
      'dashboard.read',
      ...(hasSourceRead ? [sourceRead] : []),
      ...(hasCustomerRead ? ['model.tpm_customer.read'] : []),
    ];
    const granted = await page.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: grants.map((code) => {
        expect(permissionIds.has(code), `published permission ${code}`).toBe(true);
        return { permissionId: permissionIds.get(code), granted: true };
      }),
    });
    expect(granted.status()).toBe(200);
    expect(String((await granted.json()).code)).toBe('0');
    const email = `${roleCode}@e2e.local`;
    const password = 'Test2026x';
    const user = await page.request.post('/api/admin/users', {
      data: {
        email,
        displayName: roleCode.slice(0, 50),
        initialPassword: password,
        roleCodes: [roleCode],
        sendInviteEmail: false,
      },
    });
    expect(user.status()).toBe(200);
    expect(String((await user.json()).code)).toBe('0');
    const context = await browser.newContext({
      baseURL: origin,
      storageState: { cookies: [], origins: [] },
      locale: 'zh-CN',
      viewport: { width: 1440, height: 1000 },
    });
    const actor = await context.newPage();
    const chartResponses: Response[] = [];
    const tableResponses: Response[] = [];
    actor.on('request', (request) => {
      if (new URL(request.url()).pathname === '/api/ai/aurabot/chat/stream') llmRequests++;
    });
    actor.on('response', (response) => {
      if (
        new URL(response.url()).pathname === '/api/meta/chart-data' &&
        response.request().method() === 'POST' &&
        response.request().postDataJSON()?.queryCode === 'tpm_delivery_brief'
      )
        tableResponses.push(response);
      if (
        new URL(response.url()).pathname === '/api/meta/chart-data' &&
        response.request().method() === 'POST' &&
        response.request().postDataJSON()?.modelCode === 'tpm_project'
      )
        chartResponses.push(response);
    });
    try {
      await actor.goto('/login');
      await expect(actor.getByTestId('login-page-root')).toHaveAttribute('data-hydrated', 'true');
      await actor.locator('#identifier').fill(email);
      await actor.locator('#password').fill(password);
      await actor.locator('form button[type="submit"]').click();
      await expect(actor).not.toHaveURL(/\/login(?:$|\?)/);
      if (new URL(actor.url()).pathname === '/tenant-selection') {
        await actor.getByTestId(`space-business-${tenantId}`).click();
        await expect(actor).not.toHaveURL(/\/tenant-selection(?:$|\?)/);
      }
      const identityResponse = await actor.request.get('/api/auth/me');
      expect(identityResponse.status()).toBe(200);
      const identity = (await identityResponse.json()).data;
      expect(identity.user.email).toBe(email);
      expect(String(identity.user.tenantId)).toBe(tenantId);
      const roles = identity.permissions.roles.map((role: { code: string }) => role.code);
      expect(roles).toContain(roleCode);
      expect(roles).not.toContain('tenant_admin');
      const effective: string[] = identity.permissions.permissionCodes;
      expect(effective).toContain('dashboard.read');
      expect(effective.includes(sourceRead)).toBe(hasSourceRead);
      expect(effective.includes('model.tpm_customer.read')).toBe(hasCustomerRead);
      expect(effective).not.toContain('dashboard.manage');
      expect(effective).not.toContain('*');
      const configuration = actor.waitForResponse(
        (response) =>
          new URL(response.url()).pathname === `/api/dashboards/code/${key}` &&
          response.request().method() === 'GET',
      );
      // A published global viewer is a real shared-link entry. A personal or team
      // artifact would incorrectly test its owner ACL before model authorization.
      await actor.goto(`/dashboards/view/${key}`);
      const configResponse = await configuration;
      expect(
        configResponse.status(),
        'shared artifact read must succeed before source isolation is judged',
      ).toBe(200);
      const configBody = await configResponse.json();
      expect(String(configBody.code)).toBe('0');
      expect(configBody.data).toMatchObject({
        pid: dashboardPid,
        code: key,
        scope: 'global',
        status: 'published',
      });
      await expect(actor.getByRole('heading', { name: title, exact: true })).toBeVisible();
      const metricSources = [
        'project-count',
        'project-budget',
        'awaiting-acceptance',
        'delivered-projects',
      ].map(
        (id) =>
          template.dashboard.widgets.find((widget: { id: string }) => widget.id === id).config
            .dataSource,
      );
      await expect
        .poll(() =>
          metricSources.every((source) =>
            chartResponses.some((response) => {
              const request = response.request().postDataJSON();
              return (
                isDeepStrictEqual(request.metrics, source.metrics) &&
                isDeepStrictEqual(request.filters || [], source.filters || [])
              );
            }),
          ),
        )
        .toBe(true);
      const queries = await Promise.all(
        chartResponses.map(async (response) => ({
          status: response.status(),
          request: response.request().postDataJSON(),
          origin: response.request().headers().origin,
          referer: response.request().headers().referer,
          body: await response.json(),
        })),
      );
      await info.attach(`SC04-${hasSourceRead ? 'source-reader' : 'artifact-viewer'}-responses`, {
        body: JSON.stringify(
          {
            tenantId,
            roleCode,
            hasSourceRead,
            dashboardPid,
            configStatus: configResponse.status(),
            queries,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });
      for (const query of queries) {
        expect(query.request.modelCode).toBe('tpm_project');
        expect(query.origin).toBe(origin);
        expect(new URL(query.referer!).origin).toBe(origin);
        expect(JSON.stringify(query.body)).not.toContain('csrf_origin_rejected');
        expect(query.status).toBe(hasSourceRead ? 200 : 403);
        expect(String(query.body.code)).toBe(hasSourceRead ? '0' : '403');
        if (!hasSourceRead) expect(JSON.stringify(query.body)).toContain(sourceRead);
      }
      if (!hasSourceRead) {
        for (const id of [
          'project-count',
          'project-budget',
          'awaiting-acceptance',
          'delivered-projects',
        ])
          await expect(actor.getByTestId(`dashboard-block-${id}`).getByRole('alert')).toBeVisible();
        await expect(
          actor
            .getByTestId('dashboard-block-project-count')
            .getByText(String(expected.count), { exact: true }),
        ).toHaveCount(0);
        const budget = actor.getByTestId('dashboard-block-project-budget');
        await expect(budget).not.toContainText(expected.budget.toLocaleString('zh-CN'));
        await expect(budget).not.toContainText(String(expected.budget));
        const table = actor.getByTestId('dashboard-block-delivery-projects');
        for (const project of fixture.projects)
          await expect(table.getByText(project.name, { exact: true })).toHaveCount(0);
        await expect(
          actor.getByTestId('dashboard-block-delivery-stages').locator('[data-chart-ready="true"]'),
        ).toHaveCount(0);
      } else {
        for (const [id, value] of [
          ['project-count', expected.count],
          ['awaiting-acceptance', expected.acceptance],
          ['delivered-projects', expected.completed],
        ] as const)
          await expect(
            actor.getByTestId(`dashboard-block-${id}`).getByText(String(value), { exact: true }),
          ).toBeVisible();
        await expect(actor.getByTestId('dashboard-block-project-budget')).toContainText(
          expected.budget.toLocaleString('zh-CN'),
        );

        for (const [id, value] of [
          ['project-count', expected.count],
          ['project-budget', expected.budget],
          ['awaiting-acceptance', expected.acceptance],
          ['delivered-projects', expected.completed],
        ] as const) {
          const source = template.dashboard.widgets.find(
            (widget: { id: string }) => widget.id === id,
          ).config.dataSource;
          expect(
            queries.some(
              (query) =>
                isDeepStrictEqual(query.request.metrics, source.metrics) &&
                isDeepStrictEqual(query.request.filters || [], source.filters || []) &&
                Number(query.body.data.rows?.[0]?.value) === value,
            ),
          ).toBe(true);
        }
      }
      await expect.poll(() => tableResponses.length).toBeGreaterThan(0);
      const tableResults = await Promise.all(
        tableResponses.map(async (response) => ({
          status: response.status(),
          body: await response.json(),
        })),
      );
      const table = actor.getByTestId('dashboard-block-delivery-projects');
      for (const result of tableResults) {
        expect(result.status).toBe(hasCustomerRead ? 200 : 403);
        expect(String(result.body.code)).toBe(hasCustomerRead ? '0' : '403');
        if (!hasCustomerRead) expect(result.body.data).toBeNull();
      }
      if (hasCustomerRead) {
        const seen = new Set<string>();
        const next = table.getByRole('button', { name: '下一页', exact: true });
        for (let index = 0; index < Math.ceil(expected.count / 10); index++) {
          await expect(table.getByRole('row').nth(1)).toBeVisible();
          for (const project of fixture.projects)
            if (await table.getByText(project.name, { exact: true }).isVisible())
              seen.add(project.name);
          if (index + 1 < Math.ceil(expected.count / 10)) {
            await expect(next).toBeEnabled();
            await next.click();
          }
        }
        expect([...seen].sort()).toEqual(
          fixture.projects.map((project: { name: string }) => project.name).sort(),
        );
        if (await table.getByRole('button', { name: '上一页', exact: true }).isVisible()) {
          while (await table.getByRole('button', { name: '上一页', exact: true }).isEnabled())
            await table.getByRole('button', { name: '上一页', exact: true }).click();
        }
      } else {
        await expect(table.getByRole('alert')).toBeVisible();
        for (const project of fixture.projects)
          await expect(table.getByText(project.name, { exact: true })).toHaveCount(0);
      }
      await info.attach(`SC04-${access}-joined-source-results`, {
        body: JSON.stringify({ access, tableResults }, null, 2),
        contentType: 'application/json',
      });
      await actor.screenshot({ path: info.outputPath(`CDP-${evidenceLabel}.png`), fullPage: true });
      await actor.goto(`/dashboard-designer/${dashboardPid}`);
      await expect(
        actor.getByRole('heading', { name: '无权编辑仪表盘', exact: true }),
      ).toBeVisible();
      await expect(actor.getByTestId('widget-palette')).toHaveCount(0);
      await expect(actor.getByTestId('designer-toolbar-btn-save')).toHaveCount(0);
      const deniedEdit = await actor.request.put(`/api/dashboards/${dashboardPid}`, {
        data: { ...publishedDefinition, title: 'Forbidden reader edit' },
      });
      expect(deniedEdit.status()).toBe(403);
      const retained = await page.request.get(`/api/dashboards/${dashboardPid}`);
      expect((await retained.json()).data.title).toBe(title);
      await actor.screenshot({
        path: path.join(
          process.env.AURA_EVIDENCE_DIR!,
          `DS03-designer-denied-${access === 'joined' ? 'source' : access}.png`,
        ),
        fullPage: true,
      });
      receipts.push({
        tenantId,
        roleCode,
        hasSourceRead,
        hasCustomerRead,
        dashboardPid,
        configStatus: configResponse.status(),
        queryStatuses: queries.map((query) => query.status),
        expected,
      });
    } finally {
      await context.close();
    }
  }
  expect(llmRequests).toBe(0);
  await info.attach('customer-delivery-permissions-ledger', {
    body: JSON.stringify(
      { dashboardPid, code: key, scope: 'global', expected, receipts, llmRequests },
      null,
      2,
    ),
    contentType: 'application/json',
  });
});
