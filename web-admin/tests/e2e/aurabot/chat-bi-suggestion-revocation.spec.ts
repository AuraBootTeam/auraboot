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
  const outcomeTitle = `outcome_${code}`;
  const deleteResults = process.env.AURA_BUSINESS_RESULT_OPERATION === 'delete';
  const actorOwnsTarget = deleteResults && process.env.AURA_HISTORY_TARGET_OWNER === 'actor';
  const peerOwnsTarget = deleteResults && process.env.AURA_HISTORY_TARGET_OWNER === 'peer';
  const deletedTargets: string[] = [];
  const paginateResults = process.env.AURA_BUSINESS_RESULT_PAGINATION === '1';
  const outcomeTitles = Array.from(
    { length: paginateResults ? 11 : 1 },
    (_, index) => `${outcomeTitle}-${index}`,
  );
  if (deleteResults && !actorOwnsTarget && !peerOwnsTarget) {
    for (const title of outcomeTitles) {
      const created = await admin.request.post('/api/dynamic/e2et_customer/create', {
        data: { e2et_cust_code: title, e2et_cust_name: title, e2et_cust_region: 'east', e2et_cust_active: true },
      });
      expect(created.status(), await created.text()).toBe(200);
      const pid = (await created.json()).data.pid;
      expect(pid).toBeTruthy();
      deletedTargets.push(pid);
    }
  }
  const calls = outcomeTitles.map((title, index) => ({
    id: `customer-${index}`,
    name: deleteResults ? 'cmd:e2et:delete_customer' : 'cmd:e2et:create_customer',
    input: {
      recordPid: deleteResults ? deletedTargets[index] : undefined,
      e2et_cust_code: title,
      e2et_cust_name: title,
      e2et_cust_region: 'east',
      e2et_cust_active: true,
    },
  }));
  let executionGoal =
    `${deleteResults ? 'Delete' : 'Create'} e2et_customer follow-up records using the existing customer command.\n@@AURABOOT_STUB_TOOL_USE@@ ` +
    JSON.stringify(paginateResults ? { calls } : calls[0]);
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
  const permissionPids = new Map<string, string>();
  const collect = (nodes: any[]) => {
    for (const node of nodes) {
      permissions.set(node.code, node.id);
      permissionPids.set(node.code, node.pid);
      collect(node.children ?? []);
    }
  };
  collect((await tree.json()).data);
  const codes = [
    'meta.command.execute',
    'e2et.customer.manage',
    'model.e2et_customer.read',
    'model.e2et_order.read',
    'analytics.suggestion.read',
    'analytics.suggestion.propose',
    'analytics.suggestion.adopt',
    'analytics.suggestion.execute',
    'model.core_dashboard_suggestion.read',
    'model.core_dashboard_adoption.read',
  ];
  if (process.env.AURA_HISTORY_DEPARTMENT_SCOPE === '1') codes.push('model.org_employee.read');
  if (peerOwnsTarget) codes.push('model.org_employee.read', 'model.org_department.read');
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
    if (actorOwnsTarget || peerOwnsTarget) {
      let creatorSession: Awaited<ReturnType<typeof openAsRole>> | undefined;
      if (peerOwnsTarget) {
        const creator = makeRoleUser(`${code}_creator`, [code]);
        await ensureRoleUser(admin, creator);
        creatorSession = await openAsRole(browser, creator.email, creator.password, 'zh-CN');
      }
      const creatorPage = creatorSession?.page ?? page;
      try {
      for (const [index, title] of outcomeTitles.entries()) {
        const created = await creatorPage.request.post('/api/meta/commands/execute/e2et:create_customer', {
          data: { payload: { e2et_cust_code: title, e2et_cust_name: title,
            e2et_cust_region: 'east', e2et_cust_active: true } },
        });
        expect(created.status(), await created.text()).toBe(200);
        const body = await created.json();
        expect(String(body.code), JSON.stringify(body)).toBe("0");
        const pid = body.data.data.recordPid;
        expect(pid, JSON.stringify(body)).toBeTruthy();
        deletedTargets.push(pid);
        calls[index].input.recordPid = pid;
      }
      } finally {
        await creatorSession?.context.close();
      }
      executionGoal = 'Delete e2et_customer follow-up records using the existing customer command.\n@@AURABOOT_STUB_TOOL_USE@@ ' +
        JSON.stringify(paginateResults ? { calls } : calls[0]);
    }

    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
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
              executionIntent: {
                type: 'agent_task',
                goal: executionGoal,
              },
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
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
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
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
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
      const grant = async (permission: string, granted: boolean) => {
        const response = await admin.request.put(`/api/permissions/matrix/${rolePid}/batch`, {
          data: [{ permissionId: permissions.get(permission), granted }],
        });
        expect(response.status()).toBe(200);
        await expect
          .poll(async () => {
            const current = await page.request.get('/api/auth/me', { timeout: 5000 });
            expect(current.status()).toBe(200);
            const codes = (await current.json()).data.permissions.permissionCodes;
            expect(Array.isArray(codes)).toBe(true);
            return codes.includes(permission);
          }, { message: `Permission ${permission} must become ${granted}` })
          .toBe(granted);
      };
      const reopen = async () => {
        await page.reload({ waitUntil: 'domcontentloaded' });
        await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
        await page.getByTestId('ai-panel-toggle').click();
        await expect(suggestions.getByTestId('analytics-suggestion')).toHaveCount(5);
      };
      await grant('meta.command.execute', true);
      await reopen();
      const latest = suggestions.getByTestId('analytics-suggestion').filter({ hasText: '版本 6' });
      await latest.getByRole('button', { name: '采纳此版本', exact: true }).click();
      const adoptedResponse = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().includes('/execute/core_dashboard:adopt_suggestion'),
      );
      await dialog.getByRole('button', { name: '确认采纳', exact: true }).click();
      const adopted = await adoptedResponse;
      expect(adopted.status(), await adopted.text()).toBe(200);
      const adoptionPid = (await adopted.json()).data.data.record.pid;
      await expect(latest).toContainText('已采纳');
      await latest.getByRole('button', { name: '发起执行', exact: true }).click();
      await expect(dialog).toContainText(executionGoal);
      await grant('model.e2et_order.read', false);
      const deniedExecution = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith('/api/ai/aurabot/chat/stream') &&
          !!response.request().postDataJSON()?.analyticsExecution,
      );
      await dialog.getByRole('button', { name: '确认执行', exact: true }).click();
      const denied = await deniedExecution;
      expect(denied.request().postDataJSON().analyticsExecution.adoptionPid).toBe(adoptionPid);
      const deniedBody = await denied.text();
      expect(deniedBody).toContain('event:error');
      expect(deniedBody).toContain('Report aggregate data access denied');
      const feedback = panel.getByText(
        '执行未完成。请刷新建议并检查权限后重试；已有任务的状态以服务器记录为准。',
        { exact: true },
      );
      await expect(feedback).toBeVisible();
      await feedback.scrollIntoViewIfNeeded();
      await page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/suggestion-ai-execute-denied.png`,
        fullPage: true,
      });
      const assertNoExecution = async () => {
        expect(
          (
            await db.query(
              'SELECT count(*)::int AS count FROM ab_agent_task WHERE description=$1',
              [executionGoal],
            )
          ).rows[0].count,
        ).toBe(0);
        expect(
          (
            await db.query(
              'SELECT count(*)::int AS count FROM ab_analytics_task_execution WHERE adoption_pid=$1',
              [adoptionPid],
            )
          ).rows[0].count,
        ).toBe(0);
        expect(
          (
            await db.query(
              "SELECT count(*)::int AS count FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 AND event_name='agent_execution_started'",
              [analysisId],
            )
          ).rows[0].count,
        ).toBe(0);
      };
      await assertNoExecution();
      await grant('model.e2et_order.read', true);
      await grant('analytics.suggestion.execute', false);
      await reopen();
      await expect(latest).toContainText('已采纳');
      await expect(latest.getByRole('button', { name: '发起执行', exact: true })).toHaveCount(0);
      await latest.scrollIntoViewIfNeeded();
      await page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/suggestion-execute-permission-hidden.png`,
        fullPage: true,
      });
      const bypass = await page.request.post('/api/ai/aurabot/chat/stream', {
        data: {
          sessionId: randomUUID(),
          clientMsgId: randomUUID(),
          message: 'Execute adopted suggestion',
          analyticsExecution: { adoptionPid, requestId: randomUUID() },
        },
      });
      const bypassBody = await bypass.text();
      expect(bypassBody).toContain('event:error');
      expect(bypassBody).toContain('Analytics source permission required');
      await assertNoExecution();
      await grant('analytics.suggestion.execute', true);
      const peerUser = makeRoleUser(`${code}_peer`, [code]);
      await ensureRoleUser(admin, peerUser);
      const peer = await openAsRole(browser, peerUser.email, peerUser.password, 'zh-CN');
      try {
        const peerSnapshot = await fetchRoleSnapshot(peer.page);
        for (const permission of codes) expect(peerSnapshot.permissionCodes).toContain(permission);
        expect(peerSnapshot.roleCodes).not.toContain('tenant_admin');
        const foreignExecution = await peer.page.request.post('/api/ai/aurabot/chat/stream', {
          data: {
            sessionId: randomUUID(),
            clientMsgId: randomUUID(),
            message: 'Execute adopted suggestion',
            analyticsExecution: { adoptionPid, requestId: randomUUID() },
          },
        });
        const foreignBody = await foreignExecution.text();
        expect(foreignBody).toContain('event:error');
        expect(foreignBody).toContain('Analytics execution source is unavailable to this user');
        await assertNoExecution();
        const foreignRead = await peer.page.request.get(
          `/api/dynamic/core_dashboard_adoption/${adoptionPid}`,
        );
        expect(foreignRead.status(), 'Generic reads must not expose another user adoption').toBe(
          403,
        );
        for (const model of ['core_dashboard_suggestion', 'core_dashboard_adoption']) {
          const rawList = await peer.page.request.get(`/api/dynamic/${model}/list`);
          expect(rawList.status(), `Raw ${model} list must remain private`).toBe(403);
          const rawExport = await peer.page.request.post(`/api/dynamic/${model}/export`, {
            data: { format: 'CSV' },
          });
          expect(rawExport.status(), `Raw ${model} export must remain private`).toBe(403);
          const rawAggregate = await peer.page.request.post('/api/meta/chart-data', {
            data: {
              type: 'aggregate',
              modelCode: model,
              metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
              limit: 5,
            },
          });
          expect(rawAggregate.status(), `Raw ${model} aggregate must remain private`).toBe(403);
        }
        const ownerRaw = await page.request.get(
          `/api/dynamic/core_dashboard_adoption/${adoptionPid}`,
        );
        expect(ownerRaw.status(), 'Owners must also use the authorized projection').toBe(403);
      } finally {
        await peer.context.close();
      }
      await reopen();
      await latest.getByRole('button', { name: '发起执行', exact: true }).click();
      const restoredExecution = page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          response.url().endsWith('/api/ai/aurabot/chat/stream') &&
          !!response.request().postDataJSON()?.analyticsExecution,
      );
      await dialog.getByRole('button', { name: '确认执行', exact: true }).click();
      const restoredResponse = await restoredExecution;
      expect(await restoredResponse.text()).not.toContain('event:error');
      await expect(latest.getByTestId('analytics-execution-status')).toContainText('执行成功', {
        timeout: 15000,
      });
      const runs = await db.query(
        'SELECT r.run_status FROM ab_analytics_task_execution a JOIN ab_agent_run r ON r.tenant_id=a.tenant_id AND r.task_id=a.task_pid WHERE a.adoption_pid=$1',
        [adoptionPid],
      );
      expect(runs.rows).toEqual([{ run_status: 'success' }]);
      await latest.scrollIntoViewIfNeeded();
      await page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/suggestion-execute-restored.png`,
        fullPage: true,
      });
      const orders = await db.query(
        'SELECT pid FROM mt_e2et_customer WHERE e2et_cust_name=ANY($1)',
        [outcomeTitles],
      );
      expect(orders.rows).toHaveLength(deleteResults ? 0 : outcomeTitles.length);
      const businessFacts = await db.query(
        "SELECT event_id, target_key FROM ab_behavior_outcome_outbox WHERE interaction_id=$1 AND event_name='analytics_business_command_committed' ORDER BY id",
        [analysisId],
      );
      expect(businessFacts.rows).toHaveLength(outcomeTitles.length);
      expect(businessFacts.rows.map((row) => row.target_key).sort()).toEqual(
        (deleteResults ? deletedTargets : orders.rows.map((row) => row.pid)).sort(),
      );
      const expectedFirstPage = businessFacts.rows.slice(0, 10).map((row) => row.event_id);
      const resultUrl = `/api/analytics/suggestions/${adoptionPid}/business-results`;
      const resultResponse = () =>
        page.waitForResponse(
          (response) => response.request().method() === 'GET' && response.url().includes(resultUrl),
        );
      await grant('analytics.suggestion.execute', false);
      const readable = resultResponse();
      await latest.getByRole('button', { name: '查看业务结果', exact: true }).click();
      const read = await readable;
      expect(read.status()).toBe(200);
      expect((await read.json()).data.records.map((row: any) => row.eventId)).toEqual(
        expectedFirstPage,
      );
      const results = page.getByRole('dialog', { name: '已提交的业务操作' });
      await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
      await expect(results).toContainText(deleteResults ? '删除已提交' : '新增已提交');
      await expect(results).toContainText('客户');
      await page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/result-execute-revoked.png`,
      });
      for (const permission of [
        'analytics.suggestion.read',
        'model.core_dashboard_adoption.read',
        'model.core_dashboard_suggestion.read',
        'model.e2et_order.read',
        'model.e2et_customer.read',
      ]) {
        await grant(permission, false);
        if (permission === 'model.e2et_customer.read') {
          const sourceRead = await page.request.get('/api/analytics/suggestions', {
            params: { analysisId: analysisId! },
          });
          expect(
            sourceRead.status(),
            'Source remains readable while the independent target is denied',
          ).toBe(200);
          expect((await sourceRead.json()).data.records.length).toBeGreaterThan(0);
          expect((await fetchRoleSnapshot(page)).permissionCodes).toContain(
            'model.e2et_order.read',
          );
        }
        const deniedRead = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const response = await deniedRead;
        expect(response.status(), permission).toBe(403);
        expect(await response.text()).not.toContain(businessFacts.rows[0].event_id);
        await expect(results.getByRole('alert')).toBeVisible();
        await expect(results.getByRole('listitem')).toHaveCount(0);
        await page.screenshot({
          path: `${process.env.AURA_EVIDENCE_DIR}/result-denied-${permission}.png`,
        });
        await grant(permission, true);
        const restoredRead = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const restored = await restoredRead;
        expect(restored.status(), permission).toBe(200);
        expect((await restored.json()).data.records.map((row: any) => row.eventId)).toEqual(
          expectedFirstPage,
        );
        await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
        await expect(results.getByRole('alert')).toHaveCount(0);
      }
      const setTargetScope = async (scopeType: 'none' | 'all' | 'self' | 'dept' | 'dept_and_sub') => {
        const response = await admin.request.put(`/api/permissions/matrix/${rolePid}/scope`, {
          data: {
            resourceCode: 'e2et_customer',
            actionCode: 'read',
            scopeType,
            mergeStrategy: 'MIN',
          },
        });
        expect(response.status()).toBe(200);
      };
      await setTargetScope('none');
      expect((await fetchRoleSnapshot(page)).permissionCodes).toContain('model.e2et_customer.read');
      const stillReadable = await page.request.get('/api/analytics/suggestions', {
        params: { analysisId: analysisId! },
      });
      expect(stillReadable.status()).toBe(200);
      const deniedScope = resultResponse();
      await results.getByRole('button', { name: '重新读取', exact: true }).click();
      const scopeResponse = await deniedScope;
      expect(scopeResponse.status()).toBe(403);
      expect(await scopeResponse.text()).not.toContain(businessFacts.rows[0].event_id);
      await expect(results.getByRole('listitem')).toHaveCount(0);
      await expect(results.getByRole('alert')).toBeVisible();
      await page.screenshot({
        path: `${process.env.AURA_EVIDENCE_DIR}/result-target-scope-denied.png`,
      });
      if (deleteResults) {
        const ownership = await db.query(
          `SELECT b.created_by=a.actor_user_id AS owned
           FROM ab_analytics_deleted_record_basis b
           JOIN ab_behavior_outcome_outbox o ON o.tenant_id=b.tenant_id AND o.event_id=b.event_id
           JOIN ab_agent_run r ON r.tenant_id=o.tenant_id AND r.pid=o.run_id
           JOIN ab_analytics_task_execution a ON a.tenant_id=r.tenant_id AND a.task_pid=r.task_id
           WHERE a.adoption_pid=$1 ORDER BY o.id`, [adoptionPid],
        );
        expect(ownership.rows).toEqual(outcomeTitles.map(() => ({ owned: actorOwnsTarget })));
        await setTargetScope('self');
        const deniedSelf = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const selfResponse = await deniedSelf;
        expect(selfResponse.status()).toBe(actorOwnsTarget ? 200 : 403);
        if (actorOwnsTarget) {
          expect((await selfResponse.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
          await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
          await expect(results.getByRole('alert')).toHaveCount(0);
        } else {
          await expect(results.getByRole('listitem')).toHaveCount(0);
        }
        await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-history-self-${actorOwnsTarget ? 'allowed' : 'denied'}.png` });
      }
      await setTargetScope('all');
      const restoredScope = resultResponse();
      await results.getByRole('button', { name: '重新读取', exact: true }).click();
      const restoredScopeResponse = await restoredScope;
      expect(restoredScopeResponse.status()).toBe(200);
      expect(
        (await restoredScopeResponse.json()).data.records.map((row: any) => row.eventId),
      ).toEqual(expectedFirstPage);
      await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
      await expect(results.getByRole('alert')).toHaveCount(0);
      await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-read-restored.png` });
      if (peerOwnsTarget) {
        const members = await db.query(
          `SELECT actor.pid AS actor_member, creator.pid AS creator_member,
                  au.pid AS actor_user, cu.pid AS creator_user
           FROM ab_analytics_task_execution a
           JOIN ab_agent_run r ON r.tenant_id=a.tenant_id AND r.task_id=a.task_pid
           JOIN ab_behavior_outcome_outbox o ON o.tenant_id=r.tenant_id AND o.run_id=r.pid
           JOIN ab_analytics_deleted_record_basis b ON b.tenant_id=o.tenant_id AND b.event_id=o.event_id
           JOIN ab_tenant_member actor ON actor.tenant_id=a.tenant_id AND actor.user_id=a.actor_user_id
           JOIN ab_tenant_member creator ON creator.tenant_id=a.tenant_id AND creator.user_id=b.created_by
           JOIN ab_user au ON au.id=actor.user_id JOIN ab_user cu ON cu.id=creator.user_id
           WHERE a.adoption_pid=$1`, [adoptionPid]);
        expect(members.rows).toHaveLength(1);
        const identity = members.rows[0];
        expect(identity.actor_member).not.toBe(identity.creator_member);
        const createRecord = async (model: string, data: Record<string, unknown>) => {
          const response = await admin.request.post(`/api/dynamic/${model}/create`, { data });
          expect(response.status(), await response.text()).toBe(200);
          const body = await response.json();
          expect(String(body.code), JSON.stringify(body)).toBe('0');
          expect(body.data.pid).toBeTruthy();
          return body.data.pid as string;
        };
        const departmentA = await createRecord('org_department', { org_dept_code: `${code}_a`, org_dept_name: `${code} A` });
        const departmentB = await createRecord('org_department', { org_dept_code: `${code}_b`, org_dept_name: `${code} B` });
        const position = await createRecord('org_position', { org_pos_code: code, org_pos_name: code,
          org_pos_level: '1', org_pos_dept_id: departmentA });
        await createRecord('org_employee', { org_emp_code: `${code}_actor`, org_emp_name: 'History reader',
          org_emp_dept_id: departmentA, org_emp_position_id: position, org_emp_status: 'active', org_emp_type: 'human',
          org_emp_member_id: identity.actor_member, org_emp_user_id: identity.actor_user });
        const creatorEmployee = await createRecord('org_employee', { org_emp_code: `${code}_creator`, org_emp_name: 'History creator',
          org_emp_dept_id: departmentA, org_emp_position_id: position, org_emp_status: 'active', org_emp_type: 'human',
          org_emp_member_id: identity.creator_member, org_emp_user_id: identity.creator_user });
        const modelResponse = await admin.request.get('/api/meta/models/code/e2et_customer');
        expect(modelResponse.status()).toBe(200);
        const model = (await modelResponse.json()).data;
        const originalExtension = model.extension ?? {};
        const saveExtension = async (extension: Record<string, unknown>) => {
          const response = await admin.request.put(`/api/meta/models/${model.pid}`, { data: { extension, displayName: model.displayName, description: model.description, modelType: model.modelType } });
          expect(response.status(), await response.text()).toBe(200);
          expect(String((await response.json()).code)).toBe('0');
        };
        try {
          await saveExtension({ ...originalExtension, dataScope: { ...(originalExtension.dataScope ?? {}),
            departmentOwnerField: 'created_by' } });
          await setTargetScope('dept');
          for (const [name, department, status] of [
            ['same', departmentA, 200], ['moved', departmentB, 403], ['returned', departmentA, 200],
          ] as const) {
            const updated = await admin.request.put(`/api/dynamic/org_employee/${creatorEmployee}`, {
              data: { org_emp_dept_id: department },
            });
            expect(updated.status(), await updated.text()).toBe(200);
            expect(String((await updated.json()).code)).toBe('0');
            const actual = await db.query('SELECT org_emp_dept_id FROM mt_org_employee WHERE pid=$1', [creatorEmployee]);
            expect(actual.rows).toEqual([{ org_emp_dept_id: department }]);
            const pending = resultResponse();
            await results.getByRole('button', { name: '重新读取', exact: true }).click();
            const response = await pending;
            expect(response.status(), name).toBe(status);
            if (status === 200) {
              expect((await response.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
              await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
              await expect(results.getByRole('alert')).toHaveCount(0);
            } else {
              expect(await response.text()).not.toContain(businessFacts.rows[0].event_id);
              await expect(results.getByRole('listitem')).toHaveCount(0);
              await expect(results.getByRole('alert')).toBeVisible();
            }
            await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-creator-${name}.png` });
          }
        } finally {
          await setTargetScope('all');
          await saveExtension(originalExtension);
        }
      }
      if (deleteResults && process.env.AURA_HISTORY_DEPARTMENT_SCOPE === '1') {
        const employees = await db.query(
          `SELECT e.pid FROM mt_org_employee e
           JOIN ab_tenant_member m ON m.tenant_id=e.tenant_id AND m.pid=e.org_emp_member_id
           JOIN ab_analytics_task_execution a ON a.tenant_id=m.tenant_id AND a.actor_user_id=m.user_id
           WHERE a.adoption_pid=$1`, [adoptionPid]);
        expect(employees.rows).toEqual([]);
        for (const scope of ['dept', 'dept_and_sub'] as const) {
          await setTargetScope(scope);
          const pending = resultResponse();
          await results.getByRole('button', { name: '重新读取', exact: true }).click();
          const response = await pending;
          expect(response.status(), scope).toBe(403);
          expect(await response.text()).not.toContain(businessFacts.rows[0].event_id);
          await expect(results.getByRole('listitem')).toHaveCount(0);
          await expect(results.getByRole('alert')).toBeVisible();
          await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-history-${scope}-denied.png` });
        }
        await setTargetScope('all');
        const pending = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const response = await pending;
        expect(response.status()).toBe(200);
        expect((await response.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
        await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
        await expect(results.getByRole('alert')).toHaveCount(0);
        await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-history-dept-restored.png` });
      }
      if (deleteResults && process.env.AURA_HISTORY_CUSTOM_SCOPE === '1') {
        const policyBase = { name: `History scope ${code}`, modelCode: 'e2et_customer',
          policyType: 'row', scopeType: 'custom', enabled: true };
        const condition = (path: string, value: string | boolean) => ({ type: 'compare', enabled: true,
          left: { type: 'path', scope: 'RECORD', path, dataType: typeof value === 'boolean' ? 'BOOLEAN' : 'STRING' },
          operator: 'EQ', right: { type: 'literal', value, dataType: typeof value === 'boolean' ? 'BOOLEAN' : 'STRING' },
        });
        const created = await admin.request.post('/api/meta/data-permissions', {
          data: { ...policyBase, conditionAst: condition('data.pid', deletedTargets[0]) },
        });
        expect(created.status(), await created.text()).toBe(200);
        const policyPid = (await created.json()).data.pid;
        expect(policyPid).toBeTruthy();
        const policyUrl = `/api/meta/data-permissions/${policyPid}`;
        const bindingUrl = `${policyUrl}/roles/${rolePid}`;
        expect((await admin.request.post(bindingUrl)).status()).toBe(200);
        try {
          for (const [name, path, value, expectedStatus] of [
            ['match', 'data.pid', deletedTargets[0], 200],
            ['mismatch', 'data.pid', 'different-record', 403],
            ['missing', 'data.e2et_cust_active', true, 403],
          ] as const) {
            const ast = condition(path, value);
            const updated = await admin.request.put(policyUrl, { data: { ...policyBase, conditionAst: ast } });
            expect(updated.status(), await updated.text()).toBe(200);
            const loaded = await admin.request.get(policyUrl);
            expect(loaded.status()).toBe(200);
            const storedAst = (await loaded.json()).data.conditionAst;
            expect(typeof storedAst).toBe('string');
            expect(JSON.parse(storedAst)).toEqual(ast);
            const pending = resultResponse();
            await results.getByRole('button', { name: '重新读取', exact: true }).click();
            const response = await pending;
            expect(response.status(), name).toBe(expectedStatus);
            if (expectedStatus === 200) {
              expect((await response.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
              await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
              await expect(results.getByRole('alert')).toHaveCount(0);
            } else {
              expect(await response.text()).not.toContain(businessFacts.rows[0].event_id);
              await expect(results.getByRole('listitem')).toHaveCount(0);
              await expect(results.getByRole('alert')).toBeVisible();
            }
            await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-custom-${name}.png` });
          }
        } finally {
          expect((await admin.request.delete(bindingUrl)).status()).toBe(200);
        }
        const pending = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const restored = await pending;
        expect(restored.status()).toBe(200);
        expect((await restored.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
        await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
        await expect(results.getByRole('alert')).toHaveCount(0);
        await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-custom-restored.png` });
      }
      if (deleteResults && process.env.AURA_HISTORY_RULE_GUARD === '1') {
        const policyUrl = `/api/permissions/matrix/${rolePid}/policy/${permissionPids.get('model.e2et_customer.read')}`;
        const originalResponse = await admin.request.get(policyUrl);
        expect(originalResponse.status()).toBe(200);
        const originalData = (await originalResponse.json()).data;
        expect(originalData === '' || originalData == null || typeof originalData === 'object').toBe(true);
        const originalPolicy = originalData && typeof originalData === 'object' ? originalData : {};
        const savePolicy = async (policy: Record<string, unknown>) => {
          const saved = await admin.request.put(policyUrl, { data: policy });
          expect(saved.status(), await saved.text()).toBe(200);
          const loaded = await admin.request.get(policyUrl);
          expect(loaded.status()).toBe(200);
          expect((await loaded.json()).data).toEqual(policy);
        };
        try {
          for (const [path, dataType, value, expectedStatus] of [
            ['data.pid', 'STRING', 'different-record', 200],
            ['data.e2et_cust_active', 'BOOLEAN', false, 403],
          ] as const) {
            await savePolicy({ expectedMatched: false, ruleBinding: {
              consumerType: 'PERMISSION', consumerCode: 'model.e2et_customer.read',
              consumerNodeId: 'history-read', bindingKind: 'CONDITION', enabled: true,
              conditionSpec: { root: { type: 'compare', enabled: true,
                left: { type: 'path', scope: 'RECORD', path, dataType }, operator: 'EQ',
                right: { type: 'literal', value, dataType },
              }, decisionBindings: [] },
            } });
            const pending = resultResponse();
            await results.getByRole('button', { name: '重新读取', exact: true }).click();
            const response = await pending;
            expect(response.status(), path).toBe(expectedStatus);
            if (expectedStatus === 200) {
              expect((await response.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
              await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
              await expect(results.getByRole('alert')).toHaveCount(0);
            } else {
              expect(await response.text()).not.toContain(businessFacts.rows[0].event_id);
              await expect(results.getByRole('listitem')).toHaveCount(0);
              await expect(results.getByRole('alert')).toBeVisible();
            }
            await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-rule-${expectedStatus}.png` });
          }
        } finally {
          await savePolicy(originalPolicy);
        }
        const pending = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const response = await pending;
        expect(response.status()).toBe(200);
        expect((await response.json()).data.records.map((row: any) => row.eventId)).toEqual(expectedFirstPage);
        await expect(results.getByRole('listitem')).toHaveCount(expectedFirstPage.length);
        await expect(results.getByRole('alert')).toHaveCount(0);
        await page.screenshot({ path: `${process.env.AURA_EVIDENCE_DIR}/result-rule-restored.png` });
      }
      if (paginateResults) {
        await grant('model.e2et_customer.read', false);
        const sourceRead = await page.request.get('/api/analytics/suggestions', {
          params: { analysisId: analysisId! },
        });
        expect(sourceRead.status()).toBe(200);
        const deniedNext = resultResponse();
        await results.getByRole('button', { name: '下一页', exact: true }).click();
        const deniedNextResponse = await deniedNext;
        expect(new URL(deniedNextResponse.url()).searchParams.get('page')).toBe('2');
        expect(deniedNextResponse.status()).toBe(403);
        const deniedBody = await deniedNextResponse.text();
        for (const fact of businessFacts.rows) expect(deniedBody).not.toContain(fact.event_id);
        await expect(results.getByRole('listitem')).toHaveCount(0);
        await expect(results.getByRole('alert')).toBeVisible();
        await page.screenshot({
          path: `${process.env.AURA_EVIDENCE_DIR}/result-page-two-denied.png`,
        });
        await grant('model.e2et_customer.read', true);
        const restart = resultResponse();
        await results.getByRole('button', { name: '重新读取', exact: true }).click();
        const restartResponse = await restart;
        expect(restartResponse.status()).toBe(200);
        expect((await restartResponse.json()).data.records.map((row: any) => row.eventId)).toEqual(
          expectedFirstPage,
        );
        const next = resultResponse();
        await results.getByRole('button', { name: '下一页', exact: true }).click();
        const nextResponse = await next;
        expect(nextResponse.status()).toBe(200);
        const nextPage = (await nextResponse.json()).data;
        expect(nextPage.page).toBe(2);
        expect(nextPage.hasMore).toBe(false);
        expect(nextPage.records.map((row: any) => row.eventId)).toEqual([
          businessFacts.rows[10].event_id,
        ]);
        await expect(results.getByRole('listitem')).toHaveCount(1);
        await expect(results.getByRole('alert')).toHaveCount(0);
        await page.screenshot({
          path: `${process.env.AURA_EVIDENCE_DIR}/result-page-two-restored.png`,
        });
      }
    } finally {
      await db.end();
    }
  } finally {
    await session.context.close();
  }
});
