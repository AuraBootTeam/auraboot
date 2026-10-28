import { test, expect, type Page } from '@playwright/test';
import type { Client } from 'pg';
import { join } from 'node:path';
import {
  fixture,
  evidence,
  record,
  storage,
  login,
  panel,
  send,
  propose,
  events,
  modelEffect,
  approvalWitness,
} from './qualification-support';

let database: Client;
test.beforeAll(async () => {
  database = await storage();
});
test.afterAll(async () => {
  await database?.end();
});
async function decide(page: Page, pending: any, token: string, confirmed: boolean) {
  const response = await page.request.post(fixture.backendURL + '/api/ai/aurabot/execute', {
    headers: { Authorization: 'Bearer ' + token },
    data: { pendingTurnId: pending.pendingTurnId, toolId: pending.toolId, confirmed },
    timeout: 150_000,
  });
  expect(response.status()).toBe(200);
  const raw = (await response.body()).toString('utf8');
  expect(raw).toMatch(/^event:/);
  const result = events(raw);
  record('decision-' + pending.pendingTurnId + '-' + confirmed, result);
  return result;
}

test('WB4: permission revoked after a real proposal is enforced at execution, followed by an allowed read', async ({
  page,
}) => {
  const code = fixture.codes.revoked;
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  const customer = (
    await database.query(
      'SELECT e2et_cust_code,e2et_cust_name,e2et_cust_contact FROM mt_e2et_customer WHERE tenant_id=$1 AND e2et_cust_code=$2',
      [fixture.tenantId, fixture.form.code],
    )
  ).rows;
  expect(customer).toEqual([
    {
      e2et_cust_code: fixture.form.code,
      e2et_cust_name: fixture.form.manualName,
      e2et_cust_contact: fixture.form.manualContact,
    },
  ]);
  const initialized = await page.request.put(fixture.backendURL + '/api/user-roles/sync-by-pid', {
    headers: { Authorization: 'Bearer ' + fixture.adminBearer },
    data: { memberPid: fixture.writer.memberPid, rolePids: fixture.writerInitialRolePids },
  });
  expect((await initialized.json()).code).toBe('0');
  await login(page, fixture.writer);
  const pending = await propose(page, code, 'WB4-proposal-before-revocation');
  const revoked = await page.request.put(fixture.backendURL + '/api/user-roles/sync-by-pid', {
    headers: { Authorization: 'Bearer ' + fixture.adminBearer },
    data: { memberPid: fixture.writer.memberPid, rolePids: [fixture.readerRolePid] },
  });
  expect(revoked.status()).toBe(200);
  expect((await revoked.json()).code).toBe('0');
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/ai/aurabot/execute' && r.request().method() === 'POST',
  );
  await page.getByTestId('skill-preview-card').getByTestId('confirm-btn').click();
  const result = events((await (await response).body()).toString('utf8'));
  expect(
    result.some(
      (item) => item.name === 'error' && /Approved tool execution failed/.test(item.data.error),
    ),
  ).toBe(true);
  expect(result.some((item) => item.name === 'tool_result' && item.data.success === true)).toBe(
    false,
  );
  const witness = await approvalWitness(database, pending);
  expect(witness.approval.approval_status).toBe('approved');
  expect(
    witness.runs.some(
      (row) =>
        row.resumed_from === witness.approval.run_id &&
        row.run_status === 'failed' &&
        JSON.stringify(row.execution_plan).includes("Unknown tool 'aurabot:model:create'"),
    ),
  ).toBe(true);
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  await expect(page.getByTestId('aurabot-input')).toBeEnabled({ timeout: 150_000 });
  await expect(page.getByTestId('skill-preview-card')).toHaveCount(0);
  await page.screenshot({ path: join(evidence, 'WB4-revoked-denial.png'), fullPage: true });
  await panel(page);
  const read = await send(
    page,
    `请实际调用只读工具 list_e2et_customer，pageNum=1、pageSize=20，不加关键词筛选，查询客户记录。告诉我客户编码 ${fixture.form.code} 的客户名称和联系人，用中文业务标签回答，不进行任何修改。`,
    'WB4-allowed-read-after-revocation',
  );
  const started = read.find(
    (item) => item.name === 'tool_start' && item.data.toolName === 'list_e2et_customer',
  );
  expect(started).toBeTruthy();
  const queried = read.find(
    (item) => item.name === 'tool_result' && item.data.toolId === started!.data.toolId,
  );
  expect(queried?.data.success).toBe(true);
  expect(
    queried?.data.result.records.some(
      (row: { e2et_cust_code: string }) => row.e2et_cust_code === fixture.form.code,
    ),
  ).toBe(true);
  await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.form.manualName);
  await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.form.manualContact);
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  const queryCard = page.getByTestId('chatbi-result-card').last();
  await expect(queryCard).toHaveAttribute('data-model-code', 'e2et_customer');
  await expect(
    queryCard.getByRole('columnheader', { name: '客户名称', exact: true }),
  ).toBeVisible();
  await expect(queryCard.getByRole('columnheader', { name: '联系人', exact: true })).toBeVisible();
  await expect(
    queryCard.getByRole('columnheader', { name: /^(tenant_id|pid|id|e2et_cust_)/ }),
  ).toHaveCount(0);
  await page.screenshot({ path: join(evidence, 'WB4-read-recovered.png'), fullPage: true });
  record('WB4-proof', {
    code,
    pending,
    result,
    witness,
    effect: await modelEffect(database, code),
    revokedBeforeExecution: true,
    currentWriteToolRemovedFromAuthorizedCatalog: true,
    allowedBusinessRead: customer,
  });
});

test('WB5-WB6: another user and tenant cannot consume a pending approval; owner cancellation persists', async ({
  page,
}) => {
  const code = fixture.codes.cancelled;
  await login(page);
  const pending = await propose(page, code, 'WB5-owned-pending');
  for (const [identity, token] of [
    ['reader', fixture.readerBearer],
    ['foreign-tenant', fixture.foreignBearer],
  ]) {
    const denied = await decide(page, pending, token, true);
    expect(denied.some((item) => item.name === 'error')).toBe(true);
    expect(denied.some((item) => item.name === 'tool_result' && item.data.success === true)).toBe(
      false,
    );
    expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
    expect((await approvalWitness(database, pending)).approval.approval_status).toBe('pending');
    record('WB5-' + identity + '-pending-denied', denied);
  }
  await page.screenshot({
    path: join(evidence, 'WB5-owner-pending-preserved.png'),
    fullPage: true,
  });
  const cancellation = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === '/api/ai/aurabot/execute' &&
      r.request().method() === 'POST' &&
      r.request().postDataJSON().confirmed === false,
  );
  await page.getByTestId('skill-preview-card').getByTestId('cancel-btn').click();
  const cancelled = events((await (await cancellation).body()).toString('utf8'));
  expect(cancelled.some((item) => item.name === 'done')).toBe(true);
  expect(cancelled.some((item) => item.name === 'error')).toBe(false);
  expect((await approvalWitness(database, pending)).approval.approval_status).toBe('rejected');
  await expect(page.getByTestId('skill-preview-card')).toHaveCount(0);
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  const repeated = await decide(page, pending, fixture.adminBearer, true);
  expect(repeated.some((item) => item.name === 'error')).toBe(true);
  await page.reload();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  if (!(await page.getByTestId('aurabot-input').isVisible()))
    await page.getByTestId('ai-panel-toggle').click();
  await expect(page.getByTestId('skill-preview-card')).toHaveCount(0);
  expect(await modelEffect(database, code)).toEqual({ rows: [], physical: null });
  await page.screenshot({ path: join(evidence, 'WB6-cancelled-refreshed.png'), fullPage: true });
  record('WB5-WB6-proof', {
    pending,
    effect: await modelEffect(database, code),
    crossUserAndTenantDenied: true,
    cancellationCannotBeReplayed: true,
  });
});

test('WB5: private source and history survive owner reload but do not leak after user or tenant switching', async ({
  page,
}) => {
  await login(page);
  await panel(page);
  const before = (
    await database.query(
      "SELECT count(*)::int AS n FROM ab_agent_memory WHERE tenant_id=$1 AND scope='user' AND scope_key=$2 AND memory_content LIKE $3 AND deleted_flag=FALSE",
      [fixture.tenantId, fixture.admin.userId, '%' + fixture.memory.value + '%'],
    )
  ).rows[0].n;
  expect(Number.isInteger(before)).toBe(true);
  const source = await send(
    page,
    `请先实际调用只读技能 model:query（aurabot_model_query）查询 e2et_customer 的模型元数据，成功后只用一行、不超过80字复述以下人工确认的私有验收业务备注，不输出模型字段或元数据表：我的私人验收取件暗号是“${fixture.memory.value}”。一行摘要必须保留暗号原文，不修改客户记录，不调用任何写工具。`,
    'WB5-private-source-turn',
  );
  expect(source.some((item) => item.name === 'tool_result' && item.data.success === true)).toBe(
    true,
  );
  await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.memory.value);
  const conversations = await (await page.request.get('/api/ai/aurabot/conversations')).json();
  expect(conversations.code).toBe('0');
  const conversation = conversations.data[0];
  expect(conversation.conversationId).toBeTruthy();
  await expect
    .poll(
      async () =>
        (
          await database.query(
            "SELECT count(*)::int AS n FROM ab_agent_memory WHERE tenant_id=$1 AND scope='user' AND scope_key=$2 AND memory_content LIKE $3 AND deleted_flag=FALSE",
            [fixture.tenantId, fixture.admin.userId, '%' + fixture.memory.value + '%'],
          )
        ).rows[0].n,
    )
    .toBe(before + 1);
  await panel(page);
  await send(
    page,
    '请回顾我的私人历史业务摘要：我提到的验收取件暗号是什么？若有请明确标为历史备注，不声称是当前客户记录；找不到就说未找到，不猜测。',
    'WB5-owner-recall',
  );
  await expect(page.getByTestId('chat-msg-agent').last()).toContainText(fixture.memory.value);
  await page.reload();
  await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
  if (!(await page.getByTestId('aurabot-input').isVisible()))
    await page.getByTestId('ai-panel-toggle').click();
  await expect(page.getByTestId('aurabot-panel')).toContainText(fixture.memory.value);
  await page.screenshot({ path: join(evidence, 'WB5-owner-private-recall.png'), fullPage: true });
  for (const [identity, token] of [
    ['reader', fixture.readerBearer],
    ['foreign-tenant', fixture.foreignBearer],
  ]) {
    const response = await page.request.get(
      fixture.backendURL +
        '/api/ai/aurabot/conversations/' +
        conversation.conversationId +
        '/messages',
      { headers: { Authorization: 'Bearer ' + token } },
    );
    const body = await response.text();
    expect(body).not.toContain(fixture.memory.value);
    record('WB5-' + identity + '-private-history-denied', { status: response.status(), body });
  }
  await page.getByTestId('user-menu').getByRole('button').first().click();
  await page.getByTestId('user-dropdown').locator('a[href="/logout"]').click();
  await page.getByRole('button', { name: '确认退出' }).click();
  await page.waitForURL(/\/login/);
  await login(page, fixture.reader);
  await panel(page);
  await expect(page.getByTestId('aurabot-panel')).not.toContainText(fixture.memory.value);
  await send(
    page,
    '请回顾我的私人历史业务摘要：我提到的验收取件暗号是什么？若有请明确标为历史备注，不声称是当前客户记录；找不到就说未找到，不猜测。',
    'WB5-reader-private-recall-denied',
  );
  await expect(page.getByTestId('chat-msg-agent').last()).not.toContainText(fixture.memory.value);
  await page.screenshot({ path: join(evidence, 'WB5-reader-isolated.png'), fullPage: true });
  record('WB5-private-proof', {
    owner: fixture.admin.userId,
    reader: fixture.reader.userId,
    foreignTenant: fixture.foreignTenantId,
    conversationId: conversation.conversationId,
    sourceCountBefore: before,
    sourceCountAfterThisTurn: before + 1,
    sourcePersistedAndOwnerRecalled: true,
  });
});
