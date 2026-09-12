/** Real role grants and revocation through the existing AuraBot analysis card. */
import { test, expect } from '../../fixtures';
import { randomUUID } from 'node:crypto';
import { resolve } from 'node:path';
import { Client } from 'pg';
import { PG_CONN } from '../../helpers/environments';
import { ensureRoleUser, makeRoleUser, openAsRole, fetchRoleSnapshot } from '../rbac/rbac-helpers';

test.use({
  storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
  locale: 'zh-CN',
});

test('revoked source access rejects adoption and suggestion content reads', async ({
  page: admin,
  browser,
}) => {
  test.setTimeout(120000);
  const imported = await admin.request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: resolve(process.cwd(), '../plugins/core-dashboard'),
      conflictStrategy: 'OVERWRITE',
      validateReferences: true,
      autoPublishPages: true,
    },
  });
  expect(imported.status()).toBe(200);
  expect((await imported.json()).success).toBe(true);
  const code = `suggestion_${randomUUID().replaceAll('-', '').slice(0, 12)}`;
  const role = await admin.request.post('/api/roles', {
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
  const tree = await admin.request.get('/api/permissions/tree');
  expect(tree.status()).toBe(200);
  const permissions = new Map<string, number>();
  const collect = (nodes: any[]) => {
    for (const node of nodes) {
      permissions.set(node.code, node.id);
      collect(node.children ?? []);
    }
  };
  collect((await tree.json()).data);
  const codes = [
    'meta.command.execute',
    'model.e2et_order.read',
    'analytics.suggestion.read',
    'analytics.suggestion.propose',
    'analytics.suggestion.adopt',
  ];
  const grants = codes.map((code) => {
    expect(permissions.get(code), code).toBeTruthy();
    return { permissionId: permissions.get(code), granted: true };
  });
  expect(
    (
      await admin.request.put(`/api/permissions/matrix/${rolePid}/batch`, { data: grants })
    ).status(),
  ).toBe(200);
  const user = makeRoleUser(code, [code]);
  await ensureRoleUser(admin, user);
  const fixture = await admin.request.post('/api/dynamic/e2et_order/create', {
    data: {
      e2et_order_title: code,
      e2et_order_type: 'normal',
      e2et_order_urgent: false,
      e2et_order_status: 'draft',
    },
  });
  expect(fixture.status()).toBe(200);
  const session = await openAsRole(browser, user.email, user.password, 'zh-CN');
  const page = session.page;
  try {
    const snapshot = await fetchRoleSnapshot(page);
    expect(snapshot.roleCodes).not.toContain('tenant_admin');
    for (const permission of codes) expect(snapshot.permissionCodes).toContain(permission);
    await page.waitForFunction(() => {
      const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
      return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
    });
    const panel = page.getByTestId('aurabot-panel');
    if (!(await panel.getByTestId('aurabot-input').isVisible()))
      await page.getByTestId('ai-panel-toggle').click();
    const query = {
      modelCode: 'e2et_order',
      dimensions: ['e2et_order_title'],
      metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
      filters: [{ field: 'e2et_order_title', operator: 'eq', value: code }],
      limit: 5,
    };
    await panel
      .getByTestId('aurabot-input')
      .fill(
        '@@AURABOOT_STUB_TOOL_USE@@ ' +
          JSON.stringify({ name: 'aurabot_chat-bi', input: { ...query, chartType: 'table' } }),
      );
    await panel.getByTestId('aurabot-input').press('Enter');
    const card = panel.getByTestId('chatbi-result-card');
    await expect(card).toHaveAttribute('data-row-count', '1', { timeout: 45000 });
    const analysisId = await card.getAttribute('data-analysis-id');
    const suggestions = card.getByTestId('analytics-suggestions');
    await suggestions.getByRole('button', { name: '记录建议', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByLabel('建议标题', { exact: true }).fill('订单处理复核');
    await dialog.getByLabel('建议内容', { exact: true }).fill('核对本次订单分析后安排人工复核。');
    const proposed = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().includes('/execute/core_dashboard:propose_suggestion'),
    );
    await dialog.getByRole('button', { name: '保存建议版本', exact: true }).click();
    const saved = await proposed;
    expect(saved.status(), await saved.text()).toBe(200);
    const version = (await saved.json()).data.data.record;
    let previousPid = version.pid;
    for (let index = 2; index <= 6; index++) {
      const revision = await page.request.post(
        '/api/meta/commands/execute/core_dashboard:propose_suggestion',
        {
          data: {
            payload: {
              ...saved.request().postDataJSON().payload,
              previousPid,
              content: `Review revision ${index}`,
              requestId: randomUUID(),
            },
          },
        },
      );
      expect(revision.status(), await revision.text()).toBe(200);
      previousPid = (await revision.json()).data.data.record.pid;
    }
    await suggestions.getByRole('button', { name: '刷新建议', exact: true }).click();
    await expect(suggestions.getByTestId('analytics-suggestion')).toHaveCount(5);
    await expect(
      suggestions.getByTestId('analytics-suggestion').filter({ hasText: '版本 1' }),
    ).toHaveCount(0);
    const more = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname === '/api/analytics/suggestions' &&
        new URL(r.url()).searchParams.get('page') === '2',
    );
    await suggestions.getByRole('button', { name: '加载更多版本', exact: true }).click();
    expect((await more).status()).toBe(200);
    await expect(suggestions.getByTestId('analytics-suggestion')).toHaveCount(6);
    await expect(
      suggestions.getByRole('button', { name: '加载更多版本', exact: true }),
    ).toHaveCount(0);

    await expect(
      suggestions.getByTestId('analytics-suggestion').filter({ hasText: '版本 1' }),
    ).toContainText('订单处理复核');
    expect(
      (
        await admin.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [{ permissionId: permissions.get('model.e2et_order.read'), granted: false }],
        })
      ).status(),
    ).toBe(200);
    await expect
      .poll(async () =>
        (await fetchRoleSnapshot(page)).permissionCodes.includes('model.e2et_order.read'),
      )
      .toBe(false);
    await suggestions
      .getByTestId('analytics-suggestion')
      .filter({ hasText: '版本 1' })
      .getByRole('button', { name: '采纳此版本', exact: true })
      .click();
    const deniedAdoption = page.waitForResponse(
      (r) =>
        r.request().method() === 'POST' &&
        r.url().includes('/execute/core_dashboard:adopt_suggestion'),
    );
    await dialog.getByRole('button', { name: '确认采纳', exact: true }).click();
    const rejected = await deniedAdoption;
    expect(rejected.status()).toBe(403);
    expect(rejected.request().postDataJSON().payload.versionPid).toBe(version.pid);
    await expect(dialog.getByRole('alert')).toBeVisible();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/revoked-adoption.png`,
      fullPage: true,
    });
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    const deniedRead = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        new URL(r.url()).pathname === '/api/analytics/suggestions',
    );
    await suggestions.getByRole('button', { name: '刷新建议', exact: true }).click();
    const read = await deniedRead;
    const readBody = await read.json();
    expect(String(readBody.code), JSON.stringify(readBody)).toBe('403');
    expect(readBody.data).toBeNull();
    const emptyPage = await page.request.get('/api/analytics/suggestions', {
      params: { analysisId: analysisId!, page: '99', pageSize: '5' },
    });
    const emptyPageBody = await emptyPage.json();
    expect(String(emptyPageBody.code)).toBe('403');
    expect(emptyPageBody.data).toBeNull();
    await expect(suggestions.getByTestId('analytics-suggestion')).toHaveCount(0);
    await expect(suggestions.getByRole('alert')).toBeVisible();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/revoked-read.png`,
      fullPage: true,
    });
    const historyResponse = page.waitForResponse(
      (r) =>
        r.request().method() === 'GET' &&
        /\/conversations\/\d+\/messages/.test(new URL(r.url()).pathname),
    );
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => {
      const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
      return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
    });
    if (!(await panel.getByTestId('aurabot-input').isVisible()))
      await page.getByTestId('ai-panel-toggle').click();
    const history = await historyResponse;
    expect(history.status()).toBe(200);
    const contracts = (await history.json()).data.flatMap(
      (message: any) => message.resultContracts ?? [],
    );
    expect(contracts).toHaveLength(1);
    expect(contracts[0].status).toBe('failed');
    expect(contracts[0].data).toBeFalsy();
    await expect(panel.getByTestId('chatbi-result-card')).toHaveCount(0);
    await expect(panel.getByRole('alert')).toContainText('历史分析暂不可用');
    await panel.getByRole('alert').scrollIntoViewIfNeeded();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/revoked-history.png`,
      fullPage: true,
    });
    expect(
      (
        await admin.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [
            { permissionId: permissions.get('model.e2et_order.read'), granted: true },
            { permissionId: permissions.get('meta.command.execute'), granted: false },
          ],
        })
      ).status(),
    ).toBe(200);
    await expect
      .poll(async () =>
        (await fetchRoleSnapshot(page)).permissionCodes.includes('meta.command.execute'),
      )
      .toBe(false);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.waitForFunction(() => {
      const toggle = document.querySelector('[data-testid="ai-panel-toggle"]');
      return toggle && Object.keys(toggle).some((key) => key.startsWith('__reactProps$'));
    });
    if (!(await panel.getByTestId('aurabot-input').isVisible()))
      await page.getByTestId('ai-panel-toggle').click();
    await expect(card).toHaveAttribute('data-row-count', '1');
    await expect(suggestions.getByTestId('analytics-suggestion')).toHaveCount(5);
    for (const name of ['记录建议', '修订建议', '采纳此版本'])
      await expect(suggestions.getByRole('button', { name, exact: true })).toHaveCount(0);
    await suggestions.scrollIntoViewIfNeeded();
    await page.screenshot({
      path: `${process.env.AURA_EVIDENCE_DIR}/command-denied.png`,
      fullPage: true,
    });
    const deniedCommand = await page.request.post(
      '/api/meta/commands/execute/core_dashboard:adopt_suggestion',
      {
        data: { payload: { versionPid: version.pid, requestId: randomUUID() } },
      },
    );
    expect(deniedCommand.status()).toBe(403);
    const db = new Client(PG_CONN);
    await db.connect();
    try {
      const facts = await db.query(
        "SELECT count(*)::int AS count FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 AND event_name='analytics_suggestion_adopted'",
        [analysisId],
      );
      expect(facts.rows[0].count).toBe(0);
    } finally {
      await db.end();
    }
  } finally {
    await session.context.close();
  }
});
