/** Real project delivery through existing colleague, draft and approval surfaces. */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { authenticateBffSession } from '../../helpers/bff-auth-session';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { navigateToMenuByClick } from '../helpers';

test.use({
  storageState: { cookies: [], origins: [] },
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
});
const shots = process.env.AURA_EVIDENCE_DIR ?? 'test-results/agent-delivery';
async function body(response: Pick<Awaited<ReturnType<APIRequestContext['get']>>, 'ok' | 'json'>) {
  expect(response.ok()).toBe(true);
  const value = await response.json();
  expect(String(value.code), value.message).toBe('0');
  return value;
}
async function command(api: APIRequestContext, code: string, payload: Record<string, unknown>) {
  const value = await body(
    await api.post(`/api/meta/commands/execute/${code}`, {
      data: { operationType: 'CREATE', payload },
    }),
  );
  expect(value.data.data.recordPid).toBeTruthy();
  return value.data.data.recordPid as string;
}
async function setup(page: Page, base: string) {
  expect(process.env.PW_REQUIRE_LIVE_LLM).toBe('1');
  mkdirSync(shots, { recursive: true });
  const identity = await authenticateBffSession(
    page.context().request,
    base,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  const suffix = randomUUID().slice(0, 8);
  const customer = await command(page.request, 'tpm:create_customer', {
    tpm_cu_name: `交付客户-${suffix}`,
    tpm_cu_contact: '虚构联系人',
  });
  const projectName = `交付验收-${suffix}`;
  const project = await command(page.request, 'tpm:create_project', {
    tpm_pj_name: projectName,
    tpm_pj_customer_id: customer,
    tpm_pj_owner_user: identity.userPid,
    tpm_pj_progress: 67,
    tpm_pj_risk_summary: '客户测试环境未开放',
    tpm_pj_next_action: '联系客户开放测试环境',
  });
  await command(page.request, 'tpm:create_task', {
    tpm_ts_title: `接口联调-${suffix}`,
    tpm_ts_project_id: project,
    tpm_ts_assignee: '林晓',
    tpm_ts_description: '核对接口验收清单',
  });
  return { suffix, projectName, project };
}
async function taskRows(api: APIRequestContext, title: string) {
  const value = await body(
    await api.get('/api/dynamic/tpm_task/list', {
      params: {
        pageSize: '100',
        filters: JSON.stringify([{ fieldName: 'tpm_ts_title', operator: 'EQ', value: title }]),
      },
    }),
  );
  return value.data.records as Array<Record<string, unknown>>;
}
async function businessSnapshot(api: APIRequestContext, projectPid: string) {
  const project = await body(await api.get(`/api/dynamic/tpm_project/${projectPid}`));
  const tasks = await body(
    await api.get('/api/dynamic/tpm_task/list', {
      params: {
        pageSize: '100',
        filters: JSON.stringify([
          { fieldName: 'tpm_ts_project_id', operator: 'EQ', value: projectPid },
        ]),
      },
    }),
  );
  const fields = (row: Record<string, unknown>, prefix: string) =>
    Object.fromEntries(
      Object.entries(row)
        .filter(([key]) => key === 'pid' || key.startsWith(prefix))
        .sort(([a], [b]) => a.localeCompare(b)),
    );
  return {
    project: fields(project.data, 'tpm_pj_'),
    tasks: tasks.data.records
      .map((row: Record<string, unknown>) => fields(row, 'tpm_ts_'))
      .sort((a: Record<string, unknown>, b: Record<string, unknown>) =>
        String(a.pid).localeCompare(String(b.pid)),
      ),
  };
}
async function send(page: Page, text: string) {
  const chat = page.getByTestId('agent-chat-page');
  await chat.getByTestId('aurabot-input').fill(text);
  const completed = page.waitForResponse(
    (r) => r.url().includes('/api/ai/aurabot/chat/stream') && r.request().method() === 'POST',
  );
  await chat.getByTestId('aurabot-send').click();
  const response = await completed;
  expect(response.status()).toBe(200);
  return response;
}

test('D1: create a scoped delivery colleague, read facts, approve a task and reload the saved result', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(360_000);
  const fixture = await setup(page, baseURL!);
  const department = (
    await body(
      await page.request.post('/api/org/departments', {
        data: {
          org_dept_name: `交付验收部-${fixture.suffix}`,
          org_dept_code: `delivery_${fixture.suffix}`,
          org_dept_status: 'active',
          org_dept_order: 1,
        },
      }),
    )
  ).data.pid;
  const position = (
    await body(
      await page.request.post('/api/dynamic/org_position/create', {
        data: {
          org_pos_name: `交付负责人-${fixture.suffix}`,
          org_pos_code: `delivery_${fixture.suffix}`,
          org_pos_dept_id: department,
          org_pos_level: 'P5',
          org_pos_status: 'active',
        },
      }),
    )
  ).data.pid;
  await page.goto('/home');
  await navigateToMenuByClick(page, ['AI 中心', 'AI 同事']);
  await page.getByTestId('create-agent-btn').click();
  await expect(page.getByTestId('wizard-template-project_delivery')).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D01-delivery-template.png'),
    fullPage: true,
  });
  await page.getByTestId('wizard-template-project_delivery').click();
  const name = `交付助手-${fixture.suffix}`;
  await page.getByTestId('wizard-input-name').fill(name);
  await page.getByTestId('wizard-btn-next').click();
  await page.getByTestId('wizard-btn-next').click();
  await page.getByTestId('review-provider-select').selectOption('deepseek');
  await expect(page.getByTestId('review-tool-scope')).toContainText('不会授予角色');
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D02-delivery-review.png'),
    fullPage: true,
  });
  const created = page.waitForResponse(
    (r) => r.url().includes('/agent-definition/create') && r.request().method() === 'POST',
  );
  await page.getByTestId('wizard-btn-create').click();
  const receipt = await body(await created);
  const agentPid = receipt.data.pid;
  await expect(page).toHaveURL(/ai_colleague_detail\?agentPid=/);
  const stored = (await body(await page.request.get(`/api/dynamic/agent-definition/${agentPid}`)))
    .data;
  expect(JSON.parse(stored.allowed_models)).toEqual(['tpm_project', 'tpm_task', 'tpm_milestone']);
  expect(JSON.parse(stored.tools)).toContain('cmd:tpm:create_task');
  await page.getByTestId('enroll-as-employee-btn').click();
  await page.getByTestId('enroll-dept-select').selectOption(department);
  await page.getByTestId('enroll-position-select').selectOption(position);
  const enrolled = page.waitForResponse(
    (r) => r.url().endsWith('/enroll-employee') && r.request().method() === 'POST',
  );
  await page.getByTestId('enroll-confirm-btn').click();
  await body(await enrolled);
  // Administrator fixture configuration is explicit; templates and enrollment grant no roles.
  const placement = (
    await body(await page.request.get(`/api/agent/definitions/${agentPid}/org-placement`))
  ).data;
  const employee = (
    await body(await page.request.get(`/api/dynamic/org_employee/${placement.employeePid}`))
  ).data;
  const memberPid = employee.org_emp_member_id;
  expect(memberPid).toBeTruthy();
  expect((await body(await page.request.get(`/api/user-roles/member/${memberPid}`))).data).toEqual(
    [],
  );
  const roles = (await body(await page.request.get('/api/roles/all'))).data;
  const deliveryRoles = roles.filter(
    (role: { code: string }) => role.code === 'tpm_delivery_manager',
  );
  expect(deliveryRoles).toHaveLength(1);
  const rolePids = deliveryRoles.map((role: { pid: string }) => role.pid);
  await body(
    await page.request.put('/api/user-roles/sync-by-pid', { data: { memberPid, rolePids } }),
  );
  expect((await body(await page.request.get(`/api/user-roles/member/${memberPid}`))).data).toEqual(
    rolePids,
  );
  await page.getByTestId('tab-releases').click();
  await page.getByTestId('publish-agent-release').click();
  const published = page.waitForResponse(
    (r) =>
      r.url().endsWith(`/api/agent/definitions/${agentPid}/publish`) &&
      r.request().method() === 'POST',
  );
  await page.getByTestId('confirm-publish-agent-release').click();
  await body(await published);
  await page.getByTestId('back-to-colleagues').click();
  await page.getByTestId(`agent-chat-${stored.agent_code}`).click();
  const beforeRead = await businessSnapshot(page.request, fixture.project);
  const summary = await send(
    page,
    `请查询项目“${fixture.projectName}”，只读汇总已记录的进度、风险和已有任务，不创建或更新记录。`,
  );
  const stream = await summary.text();
  expect(stream).not.toContain('No LLM provider configured');
  const chat = page.getByTestId('agent-chat-page');
  await expect(chat).toContainText('67', { timeout: 150_000 });
  await expect(chat).toContainText('客户测试环境未开放');
  await expect(chat).toContainText(`接口联调-${fixture.suffix}`);
  expect(await businessSnapshot(page.request, fixture.project)).toEqual(beforeRead);
  const session = summary.request().postDataJSON().sessionId;
  const traces = await (
    await page.request.get('/api/ai/traces', { params: { sessionId: session, pageSize: '20' } })
  ).json();
  const trace = traces.records.find((row: { input?: string }) =>
    row.input?.includes(fixture.projectName),
  );
  expect(trace, 'the live conversation must have a persistent product trace').toBeTruthy();
  const detail = await (await page.request.get(`/api/ai/traces/${trace.traceId}`)).json();
  expect(
    detail.spans.some(
      (span: { type: string; model: string; inputTokens: number }) =>
        span.type === 'generation' &&
        span.model === process.env.PW_EXPECT_LIVE_MODEL &&
        span.inputTokens > 0,
    ),
  ).toBe(true);

  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D03-grounded-progress.png'),
    fullPage: true,
  });
  const title = `交付材料复核-${fixture.suffix}`;
  expect(await taskRows(page.request, title)).toHaveLength(0);
  await send(
    page,
    `请为项目“${fixture.projectName}”创建任务“${title}”，负责人林晓，截止2026-11-20，完成标准为核对培训手册与验收清单。请实际调用受控创建工具，由我确认。`,
  );
  const confirmation = chat.getByTestId('aurabot-confirm-card').last();
  await expect(confirmation).toBeVisible({ timeout: 150_000 });
  await expect(confirmation).toContainText(title);
  await expect(confirmation).toContainText('新建任务');
  await expect(confirmation).toContainText('负责人');
  await expect(confirmation).toContainText(fixture.projectName);
  await expect(confirmation).not.toContainText(/tpm_|Tpm Ts/);
  expect(await taskRows(page.request, title)).toHaveLength(0);
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D04-before-confirmation.png'),
    fullPage: true,
  });
  // Keep the denial fixture independent of successful multi-turn history.
  const beforeRevoke = await businessSnapshot(page.request, fixture.project);
  await body(
    await page.request.put('/api/user-roles/sync-by-pid', { data: { memberPid, rolePids: [] } }),
  );
  try {
    const denied = page.waitForResponse(
      (response) =>
        response.url().includes('/api/ai/aurabot/execute') &&
        response.request().method() === 'POST',
    );
    await confirmation.getByTestId('aurabot-confirm-approve').click();
    const receipt = await denied;
    expect(receipt.ok()).toBe(true);
    expect(await receipt.text()).toMatch(/permission|denied|revoked|权限|无权/i);
    await expect(chat).toContainText(/操作未完成|未完成|创建.*失败|无权|权限/);
    expect(await taskRows(page.request, title)).toHaveLength(0);
    expect(await businessSnapshot(page.request, fixture.project)).toEqual(beforeRevoke);
    await page.screenshot({
      animations: 'disabled',
      path: join(shots, 'D10-revoked-before-confirmation.png'),
      fullPage: true,
    });
  } finally {
    await body(
      await page.request.put('/api/user-roles/sync-by-pid', { data: { memberPid, rolePids } }),
    );
  }
  expect((await body(await page.request.get(`/api/user-roles/member/${memberPid}`))).data).toEqual(
    rolePids,
  );
  await page.getByTestId('agent-chat-new-session-btn').click();
  await expect(chat.getByTestId('chat-msg-user')).toHaveCount(0);
  await send(
    page,
    `请为项目“${fixture.projectName}”创建任务“${title}”，负责人林晓，截止2026-11-20，完成标准为核对培训手册与验收清单。请实际调用受控创建工具，由我确认。`,
  );
  await expect(confirmation).toContainText(title, { timeout: 90_000 });
  await expect(confirmation).toContainText(fixture.projectName);
  expect(await taskRows(page.request, title)).toHaveLength(0);
  const continuation = page.waitForResponse(
    (response) =>
      response.url().includes('/api/ai/aurabot/execute') && response.request().method() === 'POST',
  );
  await confirmation.getByTestId('aurabot-confirm-approve').click();
  expect((await continuation).ok()).toBe(true);
  expect(await (await continuation).finished()).toBeNull();
  await expect(chat.getByTestId('chat-msg-agent').filter({ hasText: title })).toContainText(
    /已创建|已保存|创建成功/,
    { timeout: 90_000 },
  );
  await expect
    .poll(async () => (await taskRows(page.request, title)).length, { timeout: 90_000 })
    .toBe(1);
  const saved = (await taskRows(page.request, title))[0];
  expect(saved.tpm_ts_project_id).toBe(fixture.project);
  expect(saved.tpm_ts_assignee).toBe('林晓');
  expect(saved.tpm_ts_status).toBe('todo');
  await expect(chat).toContainText(title);
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D05-confirmed-task.png'),
    fullPage: true,
  });
  await page.reload();
  await expect(chat).toBeVisible();
  await expect(chat.getByTestId('chat-msg-agent').filter({ hasText: title })).toContainText(
    /已创建|已保存|创建成功/,
    { timeout: 30_000 },
  );
  await expect(chat.getByTestId('aurabot-confirm-approve')).toHaveCount(0);
  expect(await taskRows(page.request, title)).toHaveLength(1);
  await chat.getByTestId('chat-msg-agent').filter({ hasText: title }).scrollIntoViewIfNeeded();
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D06-history-restored.png'),
    fullPage: true,
  });

  // O1: enter through the existing run-log menu and correlate this exact saved task.
  await navigateToMenuByClick(page, ['AI 中心', '运行日志']);
  await page.getByRole('button', { name: '会话任务', exact: true }).click();
  const operations = page.getByTestId('conversation-task-operations');
  await operations.getByRole('textbox').fill(title);
  const listed = page.waitForResponse(
    (r) =>
      r.url().includes('/api/admin/agent-runs/conversation-tasks?') &&
      r.url().includes(encodeURIComponent(title)),
  );
  await operations.getByRole('button', { name: '查询', exact: true }).click();
  const tasks = (await body(await listed)).data;
  expect(tasks.records.length).toBeGreaterThan(0);
  const task = tasks.records.find(
    (row: { title: string; traceId?: string }) => row.title.includes(title) && row.traceId,
  );
  expect(task).toBeTruthy();
  const row = operations.getByTestId(`conversation-task-${task.taskPid}`);
  await expect(row).toContainText('费用未知');
  const detailResponse = page.waitForResponse((r) =>
    r.url().endsWith(`/conversation-tasks/${task.taskPid}`),
  );
  await row.getByRole('button', { name: '联查回执' }).click();
  const operationDetail = (await body(await detailResponse)).data;
  expect(operationDetail.task.taskPid).toBe(task.taskPid);
  expect(operationDetail.traces.length).toBeGreaterThan(0);
  expect(
    operationDetail.actions.some(
      (action: { command: string }) => action.command === 'tpm:create_task',
    ),
  ).toBe(true);
  const evidence = operations.getByTestId('conversation-task-detail');
  await expect(evidence.getByRole('link', { name: '查看调用 1', exact: true })).toBeVisible();
  await evidence.scrollIntoViewIfNeeded();
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'O01-task-receipts.png'),
    fullPage: true,
  });
  await evidence.getByRole('link', { name: '打开我有权访问的会话' }).click();
  await expect(chat.getByTestId('chat-msg-agent').filter({ hasText: title })).toContainText(
    /已创建|已保存|创建成功/,
  );

  // O04: a second browser tab observes a genuinely executing, read-only model turn.
  const observer = await page.context().newPage();
  await observer.goto(page.url());
  await expect(observer.getByTestId('aurabot-input')).toBeEnabled();
  const beforeRecovery = await businessSnapshot(page.request, fixture.project);
  const readOnlyPrompt = `请只读查询项目“${fixture.projectName}”与现有任务，详细列出全部已记录的风险、进度、负责人和后续行动，区分缺失信息；不创建或修改任何记录。`;
  const execution = await send(page, readOnlyPrompt);
  const payload = execution.request().postDataJSON();
  expect(payload.conversationId).toBeTruthy();
  await expect
    .poll(
      async () =>
        (
          await body(
            await observer.request.get(
              `/api/ai/aurabot/conversations/${payload.conversationId}/execution`,
            ),
          )
        ).data.state,
    )
    .toBe('running');
  const running = observer.waitForResponse(
    async (r) =>
      r.url().endsWith(`/conversations/${payload.conversationId}/execution`) &&
      (await r.json()).data?.state === 'running',
  );
  await observer.reload();
  await running;
  await expect(observer.getByTestId('conversation-execution-state')).toContainText('执行');
  await expect(observer.getByTestId('aurabot-input')).toBeDisabled();
  await observer.screenshot({
    animations: 'disabled',
    path: join(shots, 'O04-running-reload.png'),
    fullPage: true,
  });
  const duplicate = await observer.request.post('/api/ai/aurabot/chat/stream', { data: payload });
  expect(await duplicate.text()).toContain('aurabot.execution.conversation_busy');
  expect(await execution.finished()).toBeNull();
  await expect(observer.getByTestId('aurabot-input')).toBeEnabled({ timeout: 150_000 });
  await expect(observer.getByTestId('agent-chat-page')).toContainText(readOnlyPrompt);
  await expect(observer.getByTestId('chat-msg-agent').last()).toContainText('67');
  await observer.reload();
  await expect(
    observer.getByTestId('chat-msg-user').filter({ hasText: readOnlyPrompt }),
  ).toHaveCount(1);
  await expect(observer.getByTestId('chat-msg-agent').last()).toContainText('67');
  expect(await businessSnapshot(observer.request, fixture.project)).toEqual(beforeRecovery);
  await observer.screenshot({
    animations: 'disabled',
    path: join(shots, 'O04-recovered-history.png'),
    fullPage: true,
  });
  await observer.close();
});

test('D2: customer notes create a reviewed task draft without saving or replacing the selected project', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(180_000);
  const fixture = await setup(page, baseURL!);
  await page.goto('/home');
  await navigateToMenuByClick(page, ['项目交付', 'AI整理交付任务']);
  await page.getByTestId('select-trigger-tpm_ts_project_id').click();
  await page.getByRole('option', { name: fixture.projectName, exact: true }).click();
  const title = `培训材料检查-${fixture.suffix}`;
  await page.getByTestId('ai-fill-trigger').click();
  await page
    .getByTestId('ai-fill-input')
    .fill(
      `只整理一项虚构任务：名称“${title}”，负责人林晓，完成标准“检查培训手册和验收清单”。截止日期未确定，不得猜测。所属项目已经选好，不能改动。`,
    );
  await page.getByTestId('ai-fill-confirm').click();
  await expect(page.getByTestId('ai-fill-dialog')).toBeHidden({ timeout: 120_000 });
  await expect(page.locator('#tpm_ts_title')).toHaveValue(title);
  await expect(page.locator('#tpm_ts_assignee')).toHaveValue('林晓');
  await expect(page.locator('#tpm_ts_due_date')).toHaveValue('');
  await expect(page.getByTestId('select-trigger-tpm_ts_project_id')).toContainText(
    fixture.projectName,
  );
  expect(await taskRows(page.request, title)).toHaveLength(0);
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D07-reviewed-task-draft.png'),
    fullPage: true,
  });
  await page.locator('#tpm_ts_description').scrollIntoViewIfNeeded();
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D07b-draft-scope-and-date.png'),
    fullPage: true,
  });
  const persisted = page.waitForResponse(
    (r) => r.url().includes('/execute/tpm:create_task') && r.request().method() === 'POST',
  );
  await page.getByTestId('form-btn-submit').click();
  await body(await persisted);
  expect(await taskRows(page.request, title)).toHaveLength(1);
  await expect(page).toHaveURL(/\/p\/tpm_task$/);
  await page.reload();
  await expect(page.getByRole('row').filter({ hasText: title })).toBeVisible();
  await page.screenshot({
    animations: 'disabled',
    path: join(shots, 'D08-task-saved.png'),
    fullPage: true,
  });
});
