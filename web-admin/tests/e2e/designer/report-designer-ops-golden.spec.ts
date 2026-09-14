/** Report designer golden ops: edit route, version rollback, broken source, export gates, reader mode, view page. */
import { test, expect, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { openAsRole } from '../rbac/rbac-helpers';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json', locale: 'zh-CN' });

test.describe.configure({ mode: 'serial' });
// Cold vite transforms on a fresh dev server can exceed the 15s default.
test.setTimeout(60000);

const code = `ops_golden_${randomUUID().replaceAll('-', '').slice(0, 10)}`;
const marker = `ops_${code}`;
let reportPid = '';
let queryPid = '';

const seed = async (request: APIRequestContext) => {
  const row = await request.post('/api/dynamic/e2et_order/create', {
    data: { e2et_order_title: marker, e2et_order_type: 'normal', e2et_order_urgent: false, e2et_order_status: 'draft' },
  });
  expect(row.status(), await row.text()).toBe(200);
  const query = await request.post('/api/meta/named-queries', {
    data: {
      code, title: 'Ops golden source', status: 'published', resourceCode: 'e2et_order', actionCode: 'read',
      fromSql: `SELECT pid, e2et_order_title AS title FROM mt_e2et_order WHERE e2et_order_title = '${marker}'`,
      fields: [
        { fieldCode: 'record_key', columnExpr: 'pid', dataType: 'string', operators: ['eq'] },
        { fieldCode: 'title', columnExpr: 'title', dataType: 'string', operators: ['eq'] },
      ],
    },
  });
  expect(query.status(), await query.text()).toBe(200);
  queryPid = (await query.json()).data.pid;
  const report = await request.post('/api/report-definitions', {
    data: {
      code, title: 'Ops golden report', profile: 'paged-media',
      dsl: {
        version: '1.0.0', title: 'Ops golden report',
        dataSources: { source: { type: 'namedQuery', queryCode: code } },
        body: [{ id: 'rows', blockType: 'table', dataSource: 'source',
          columns: [{ field: 'record_key', label: 'Record' }, { field: 'title', label: 'Title' }] }],
      },
    },
  });
  expect(report.status(), await report.text()).toBe(200);
  reportPid = (await report.json()).data.pid;
  const saved = await request.get(`/api/report-definitions/${reportPid}`);
  expect(saved.status()).toBe(200);
  expect((await saved.json()).data.code).toBe(code);
};

test('OPS-01 edit route renders the designer and version rollback flows from the UI', async ({ page, request }) => {
  await seed(request);
  page.on('console', (m) => {
    if (m.type() === 'error') console.log('[console]', m.text().slice(0, 200));
  });
  page.on('response', (r) => {
    if (r.url().includes('report-definitions')) console.log('[net]', r.status(), r.url().slice(0, 140));
  });
  await page.goto(`/report-designer/${reportPid}`);
  await expect(page.getByTestId('report-canvas')).toBeVisible({ timeout: 30000 });
  await expect(page.getByTestId('report-designer-toolbar')).toBeVisible({ timeout: 30000 });
  await expect(page.getByPlaceholder(/^(报表标题|Report Title)$/)).toHaveValue('Ops golden report', { timeout: 30000 });

  // Produce a second version so a non-latest row exists for preview.
  await page.getByRole('button', { name: /Rich Text|富文本/ }).click();
  await page.waitForTimeout(800);
  const handles = await page.locator('[data-testid^="drag-handle-"]').count();
  console.log('[diag] drag handles after add:', handles,
    '| palette visible:', await page.getByTestId('block-palette').isVisible().catch(() => false),
    '| dirty:', await page.locator('#report-export-status').isVisible().catch(() => false));
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/diag-after-add.png`, fullPage: true });
  const save = page.getByTestId('report-designer-toolbar-btn-save');
  await save.click();
  // After a successful save the document is clean: the export banner hides and a
  // new version appears in the history count.
  await expect(page.locator('#report-export-status')).toBeHidden();

  await page.locator('button[title="版本历史"]').click();
  const panel = page.getByTestId('version-history-panel');
  await expect(panel).toBeVisible();
  await expect(panel).toContainText('个可用版本');

  // Preview the earliest version row (rows newest-first; the latest row carries the badge).
  const oldest = panel.locator('button').filter({ hasText: /由/ }).filter({ hasNotText: '最新' }).last();
  await oldest.click();
  await expect(page.getByTestId('report-version-preview')).toBeVisible();
  await page.getByRole('button', { name: /^(返回当前报表|Return to current report)$/ }).click();
  await expect(page.getByTestId('report-version-preview')).toHaveCount(0);
});

test('OPS-02 broken data source surfaces the query alert and recovers after fixing', async ({ browser, request }) => {
  // Permission revocation propagates sub-second (B108 measurement) and is not subject
  // to the named-query definition cache, making this the deterministic break trigger.
  const roleCode = `${code}_srcreader`;
  const role = await request.post('/api/roles', {
    data: { code: roleCode, name: roleCode, type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'all' },
  });
  expect(role.status(), await role.text()).toBe(200);
  const rolePid = (await role.json()).data.pid;
  const tree = await (await request.get('/api/permissions/tree')).json();
  const perms = new Map<string, number>();
  const walk = (n: any) => { perms.set(n.code, n.id); (n.children ?? []).forEach(walk); };
  tree.data.forEach(walk);
  const grants = ['report.definition.view', 'report.export.execute', 'meta.query.read', 'model.e2et_order.read', 'data.datasource.read']
    .map((key) => ({ permissionId: perms.get(key)!, granted: true }));
  expect((await request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })).status()).toBe(200);
  const email = `${roleCode}@e2e.local`;
  const password = `Aa7!${randomUUID()}`;
  expect((await request.post('/api/admin/users', {
    data: { email, displayName: 'Source reader', initialPassword: password, roleCodes: [roleCode], sendInviteEmail: false },
  })).status()).toBe(200);

  const session = await openAsRole(browser, email, password, 'zh-CN');
  const page = session.page;
  try {
    await page.goto(`/report-designer/${reportPid}`);
    await expect(page.getByTestId('report-reader-toolbar')).toBeVisible({ timeout: 30000 });
    await expect(page.getByText(marker)).toBeVisible({ timeout: 30000 });

    const modelRead = perms.get('model.e2et_order.read')!;
    expect((await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: [{ permissionId: modelRead, granted: false }],
    })).status()).toBe(200);
    await page.reload();
    await expect(page.getByRole('alert').filter({ hasText: '查询未成功' })).toBeVisible({ timeout: 30000 });
    await expect(page.getByRole('alert')).toContainText('无权读取');
    await expect(page.getByText(marker)).toHaveCount(0);

    expect((await request.put(`/api/permissions/matrix/${rolePid}/batch`, {
      data: [{ permissionId: modelRead, granted: true }],
    })).status()).toBe(200);
    await page.reload();
    await expect(page.getByRole('alert').filter({ hasText: '查询未成功' })).toHaveCount(0);
    await expect(page.getByText(marker)).toBeVisible({ timeout: 30000 });
  } finally {
    await session.context.close();
  }
});

test('OPS-03 dirty state gates exports with the status banner and failures toast visibly', async ({ page, request }) => {
  await page.goto(`/report-designer/${reportPid}`);
  await page.getByRole('button', { name: /Rich Text|富文本/ }).click();
  const banner = page.locator('#report-export-status');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText('请先保存');
  await expect(page.getByRole('button', { name: /导出 PDF|Export PDF/ })).toBeDisabled();
  await page.getByTestId('report-designer-toolbar-btn-save').click();
  await expect(banner).toBeHidden();
  await expect(page.getByRole('button', { name: /导出 JSON|Export JSON/ })).toBeEnabled();

  const broken = await request.put(`/api/meta/named-queries/${queryPid}/status`, {
    data: { status: 'deprecated' },
  });
  expect(broken.status()).toBe(200);
  await page.getByRole('button', { name: /导出 JSON|Export JSON/ }).click();
  await expect(page.getByTestId('toast-stack').getByText('导出未完成')).toBeVisible();
  const fixed = await request.put(`/api/meta/named-queries/${queryPid}/status`, {
    data: { status: 'published' },
  });
  expect(fixed.status(), await fixed.text()).toBe(200);
});

test('OPS-04 view-only permission renders the reader toolbar without manage actions', async ({ browser, request }) => {
  const roleCode = `${code}_reader`;
  const role = await request.post('/api/roles', {
    data: { code: roleCode, name: roleCode, type: 'custom', status: 'active', scopeType: 'tenant', defaultDataScopeType: 'all' },
  });
  expect(role.status(), await role.text()).toBe(200);
  const rolePid = (await role.json()).data.pid;
  const tree = await (await request.get('/api/permissions/tree')).json();
  const perms = new Map<string, number>();
  const walk = (n: any) => { perms.set(n.code, n.id); (n.children ?? []).forEach(walk); };
  tree.data.forEach(walk);
  const grants = ['report.definition.view', 'report.export.execute', 'meta.query.read', 'model.e2et_order.read', 'data.datasource.read']
    .map((key) => ({ permissionId: perms.get(key), granted: true }));
  expect((await request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })).status()).toBe(200);
  const email = `${roleCode}@e2e.local`;
  const password = `Aa7!${randomUUID()}`;
  expect((await request.post('/api/admin/users', {
    data: { email, displayName: 'Ops reader', initialPassword: password, roleCodes: [roleCode], sendInviteEmail: false },
  })).status()).toBe(200);
  const login = await request.post('/api/auth/login', { data: { email, password } });
  expect(login.status()).toBe(200);
  const session = await openAsRole(browser, email, password, 'zh-CN');
  const page = session.page;
  try {
    await page.goto(`/report-designer/${reportPid}`);
    await expect(page.getByTestId('report-reader-toolbar')).toBeVisible({ timeout: 20000 });
    // Screenshot discipline: capture only after the runtime content has settled.
    await expect(page.getByText(/Loading report/)).toBeHidden({ timeout: 30000 });
    await page.waitForTimeout(500);
  await expect(page.getByTestId('report-designer-toolbar')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /导出 PDF|Export PDF/ })).toBeVisible();
    await expect(page.getByRole('button', { name: /^(保存|Save)$/ })).toHaveCount(0);
    await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/ops-reader-toolbar.png`, fullPage: true });
  } finally {
    await session.context.close();
  }
});

test('OPS-05 standalone view page renders the saved report and print actions', async ({ page }) => {
  await page.goto(`/reports/view/${code}`);
  await expect(page.getByRole('button', { name: /Export PDF|导出 PDF/ })).toBeVisible();
  await expect(page.getByRole('button', { name: /^Print|打印$/ })).toBeVisible();
  await expect(page.locator('table').first()).toContainText(marker.slice(0, 8));
  await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/ops-view-page.png`, fullPage: true });
});
