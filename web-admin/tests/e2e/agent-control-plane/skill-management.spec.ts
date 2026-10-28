/** Real-stack UI authoring -> command persistence -> colleague binding -> governed tool execution. */
import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { authenticateBffSession } from '../../helpers/bff-auth-session';
import { provisionOwnedTenantUser } from '../../helpers/owned-tenant-user';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

test.use({
  storageState: { cookies: [], origins: [] },
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
});
const shots = process.env.AURA_EVIDENCE_DIR ?? 'test-results/skill-management';
const code = `skill_ui_${Date.now()}`;
test('SM01-07: maintain, bind and execute a skill from the AI Center menu', async ({
  page,
  baseURL,
}) => {
  test.setTimeout(150_000);
  expect(baseURL).toBeTruthy();
  mkdirSync(shots, { recursive: true });
  await authenticateBffSession(
    page.context().request,
    baseURL!,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  const fixture = await page.request.post(
    '/api/meta/commands/execute/acp:create_agent_definition',
    {
      data: {
        operationType: 'CREATE',
        payload: {
          agent_code: `${code}_colleague`,
          name: '技能验收同事',
          agent_type: 'copilot',
          status: 'active',
          visibility: 'private',
          skills: '["form_draft_fill"]',
        },
      },
    },
  );
  const fixtureBody = await fixture.json();
  expect(fixtureBody.code).toBe('0');
  const agentPid = fixtureBody.data.data.recordPid;
  expect(agentPid).toBeTruthy();
  await page.goto('/home');
  const skillsMenu = page.getByRole('link', { name: '技能管理', exact: true });
  if (!(await skillsMenu.isVisible()))
    await page.getByRole('button', { name: 'AI 中心', exact: true }).click();
  await page.getByRole('link', { name: '技能管理', exact: true }).click();
  await expect(page).toHaveURL(/\/p\/agent_skill$/);
  await expect(page.getByRole('button', { name: /新建|Create/ }).first()).toBeVisible();
  await page.screenshot({ path: path.join(shots, 'SM01.png'), fullPage: true });
  await page
    .getByRole('button', { name: /新建|Create/ })
    .first()
    .click();
  const editor = page.getByTestId('skill-definition-editor');
  await expect(editor).toBeVisible();
  await editor.getByLabel('技能名称', { exact: true }).fill('技能管理业务验收');
  await editor.getByLabel('技能标识', { exact: true }).fill(code);
  await editor.getByLabel('版本', { exact: true }).fill('1.0.0');
  await editor.getByLabel('使用状态', { exact: true }).selectOption('active');
  await editor
    .getByLabel('适用任务与边界', { exact: true })
    .fill('读取当前技能目录；不执行业务写入。');
  await editor.getByLabel('业务模型范围', { exact: true }).fill('agent_skill');
  await editor.getByRole('button', { name: '加载工具', exact: true }).click();
  await editor.getByTestId('skill-tool-list:agent_skill').check();
  const saved = page.waitForResponse(
    (response) =>
      response.url().includes('/api/meta/commands/execute/acp:create_agent_skill') &&
      response.request().method() === 'POST',
  );
  await editor.getByRole('button', { name: '保存技能', exact: true }).click();
  const savedBody = await (await saved).json();
  expect(savedBody.code, JSON.stringify(savedBody)).toBe('0');
  const skillPid = savedBody.data.data.recordPid;
  expect(skillPid).toBeTruthy();
  await expect(page).toHaveURL(new RegExp(`/p/agent_skill/edit/${skillPid}$`));
  await expect(editor.getByLabel('技能名称', { exact: true })).toHaveValue('技能管理业务验收');
  await page.reload();
  await expect(editor.getByTestId('skill-tool-list:agent_skill')).toHaveCount(0); // scoped tools are explicitly reloaded, never fabricated
  await expect(editor.getByLabel('技能名称', { exact: true })).toHaveValue('技能管理业务验收');
  await page.screenshot({ path: path.join(shots, 'SM02.png'), fullPage: true });
  await editor.getByLabel('技能名称', { exact: true }).fill('技能管理业务验收·修订');
  await editor.getByRole('button', { name: '保存技能', exact: true }).click();
  await expect(editor.getByRole('status').filter({ hasText: '技能已保存' })).toBeVisible();
  const current = await (await page.request.get(`/api/dynamic/agent-skill/${skillPid}`)).json();
  expect(current.data.skill_name).toBe('技能管理业务验收·修订');
  expect(current.data.execution_mode).toBe('template');
  await page.screenshot({ path: path.join(shots, 'SM03.png'), fullPage: true });
  await editor.getByRole('button', { name: '选择AI同事', exact: true }).click();
  await editor.getByLabel('AI同事', { exact: true }).selectOption(agentPid);
  await editor.getByRole('button', { name: '绑定技能', exact: true }).click();
  await expect(editor.getByRole('status').filter({ hasText: '技能已绑定' })).toBeVisible();
  const bound = await (await page.request.get(`/api/dynamic/agent-definition/${agentPid}`)).json();
  expect(JSON.parse(bound.data.skills)).toContain(code);
  expect(JSON.parse(bound.data.skills)).toContain('form_draft_fill');
  await page.screenshot({ path: path.join(shots, 'SM05.png'), fullPage: true });
  const run = editor.getByRole('button', { name: '执行一次', exact: true });
  await expect(run).toBeDisabled();
  await editor.getByLabel('我已核对输入，允许本次真实执行。', { exact: true }).check();
  const executed = page.waitForResponse((response) =>
    response.url().includes(`/api/agent/skills/${code}/execute`),
  );
  await run.click();
  const execution = await (await executed).json();
  expect(execution.code).toBe('0');
  expect(execution.data.status).toBe('SUCCESS');
  expect(execution.data.toolCallCount).toBe(1);
  await expect(editor.getByTestId('skill-trial-result')).toBeVisible();
  await expect(run).toBeDisabled();
  await page.screenshot({ path: path.join(shots, 'SM06.png'), fullPage: true });
  await editor.getByLabel('使用状态', { exact: true }).selectOption('deprecated');
  await editor.getByRole('button', { name: '保存技能', exact: true }).click();
  await expect(editor.getByRole('status').filter({ hasText: '技能已保存' })).toBeVisible();
  await page.reload();
  await expect(editor.getByLabel('使用状态', { exact: true })).toHaveValue('deprecated');
  await expect(run).toBeDisabled();
  await page.screenshot({ path: path.join(shots, 'SM07.png'), fullPage: true });
});

test('SM08: a fresh member cannot discover the menu or execute managed skills', async ({
  page,
  browser,
  baseURL,
}) => {
  await authenticateBffSession(
    page.context().request,
    baseURL!,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  const actor = await provisionOwnedTenantUser(page, 'skill_no_role');
  const isolated = await browser.newContext({
    baseURL,
    locale: 'zh-CN',
    storageState: { cookies: [], origins: [] },
  });
  try {
    await authenticateBffSession(isolated.request, baseURL!, actor.email, actor.password);
    const viewer = await isolated.newPage();
    await viewer.goto('/home');
    await expect(viewer.getByRole('link', { name: '技能管理', exact: true })).toHaveCount(0);
    const denied = await isolated.request.post('/api/agent/skills/dsl.query/execute', {
      data: { input: {} },
    });
    expect(denied.status()).toBe(403);
    expect(String((await denied.json()).code)).not.toBe('0');
    await viewer.screenshot({ path: path.join(shots, 'SM08.png'), fullPage: true });
  } finally {
    await isolated.close();
  }
});

test('SM09: a real approval stop is shown as pending without execution or automatic replay', async ({
  page,
  baseURL,
}) => {
  await authenticateBffSession(
    page.context().request,
    baseURL!,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  const suffix = `sm_approval_${Date.now()}`;
  const name = `专用工具审批验收-${suffix}`;
  const toolCode = `${suffix}_tool`;
  const tool = await page.request.post('/api/meta/commands/execute/acp:create_agent_tool', {
    data: {
      operationType: 'CREATE',
      payload: {
        tool_code: toolCode,
        tool_name: name,
        tool_description: 'Owned approval-only fixture; must stop before dispatch',
        tool_type: 'custom_api',
        source_type: 'custom',
        source_code: 'POST http://127.0.0.1:9/not-called',
        requires_approval: true,
        risk_level: 'high',
        tool_status: 'active',
        input_schema: JSON.stringify({ type: 'object', properties: {} }),
      },
    },
  });
  expect((await tool.json()).code).toBe('0');
  const policy = await page.request.post('/api/meta/commands/execute/acp:create_approval_policy', {
    data: {
      operationType: 'CREATE',
      payload: {
        policy_name: suffix,
        trigger_rules: JSON.stringify([{ type: 'tool_call', pattern: toolCode }]),
        approver_rules: JSON.stringify([{ type: 'role', roleCode: 'tenant_admin' }]),
        auto_approve: false,
        policy_status: 'active',
        timeout_hours: 24,
        timeout_action: 'reject',
      },
    },
  });
  expect((await policy.json()).code).toBe('0');
  const skill = await page.request.post('/api/meta/commands/execute/acp:create_agent_skill', {
    data: {
      operationType: 'CREATE',
      payload: {
        skill_code: suffix,
        skill_name: name,
        skill_level: 'atomic',
        skill_status: 'active',
        execution_mode: 'template',
        skill_tools: JSON.stringify([toolCode]),
        skill_input_schema: JSON.stringify({ type: 'object', properties: {} }),
      },
    },
  });
  expect((await skill.json()).code).toBe('0');
  await page.goto('/home');
  const menu = page.getByRole('link', { name: '技能管理', exact: true });
  if (!(await menu.isVisible()))
    await page.getByRole('button', { name: 'AI 中心', exact: true }).click();
  await menu.click();
  const row = page.getByRole('row').filter({ hasText: name });
  await row.getByRole('button', { name: /编辑|Edit/ }).click();
  const editor = page.getByTestId('skill-definition-editor');
  await expect(editor).toBeVisible();
  await editor.getByLabel('我已核对输入，允许本次真实执行。', { exact: true }).check();
  const response = page.waitForResponse((r) =>
    r.url().includes(`/api/agent/skills/${suffix}/execute`),
  );
  await editor.getByRole('button', { name: '执行一次', exact: true }).click();
  const output = await (await response).json();
  expect(output.code).toBe('0');
  expect(output.data.data.approvalRequired).toBe(true);
  expect(output.data.status).not.toBe('SUCCESS');
  expect(output.data.actionCount).toBe(0);
  await expect(editor.getByTestId('skill-trial-result')).toContainText('已暂停');
  await expect(editor.getByRole('button', { name: '执行一次', exact: true })).toBeDisabled();
  const pending = await (await page.request.get('/api/agent/approvals/pending')).json();
  expect(pending.code).toBe('0');
  expect(
    pending.data.some((approval: { pid: string }) => approval.pid === output.data.data.approvalPid),
  ).toBe(true);
  await page.screenshot({ path: path.join(shots, 'SM09.png'), fullPage: true });
});

test('SM04/10: native required validation and builtin read-only protection are visible from the list', async ({
  page,
  baseURL,
}) => {
  await authenticateBffSession(
    page.context().request,
    baseURL!,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  await page.goto('/home');
  const menu = page.getByRole('link', { name: '技能管理', exact: true });
  if (!(await menu.isVisible()))
    await page.getByRole('button', { name: 'AI 中心', exact: true }).click();
  await menu.click();
  const builtin = page.getByRole('row').filter({ hasText: 'Execute DSL Command' });
  await expect(builtin).toBeVisible();
  await expect(builtin.getByRole('button', { name: /删除|Delete/ })).toHaveCount(0);
  await builtin.getByRole('button', { name: /编辑|Edit/ }).click();
  const editor = page.getByTestId('skill-definition-editor');
  await expect(editor.getByRole('button', { name: '保存技能', exact: true })).toBeDisabled();
  await expect(editor.getByRole('status')).toContainText('内置技能');
  await page.screenshot({ path: path.join(shots, 'SM10.png'), fullPage: true });
  await editor.getByRole('link', { name: '返回技能管理', exact: true }).click();
  await page
    .getByRole('button', { name: /新建|Create/ })
    .first()
    .click();
  let commands = 0;
  page.on('request', (request) => {
    if (request.url().includes('/api/meta/commands/execute/acp:create_agent_skill')) commands++;
  });
  await editor.getByRole('button', { name: '保存技能', exact: true }).click();
  expect(
    await editor
      .getByLabel('技能名称', { exact: true })
      .evaluate((input: HTMLInputElement) => input.validity.valueMissing),
  ).toBe(true);
  expect(commands).toBe(0);
  await page.screenshot({ path: path.join(shots, 'SM04.png'), fullPage: true });
  await editor.getByRole('link', { name: '返回技能管理', exact: true }).click();
  await expect(page).toHaveURL(/\/p\/agent_skill$/);
});

test('SM11/12: search empty state and English keyboard labels use the real menu', async ({
  page,
  baseURL,
}) => {
  await authenticateBffSession(
    page.context().request,
    baseURL!,
    DEFAULT_TEST_ACCOUNT.email,
    DEFAULT_TEST_ACCOUNT.password,
  );
  await page.goto('/home');
  const menu = page.getByRole('link', { name: '技能管理', exact: true });
  if (!(await menu.isVisible()))
    await page.getByRole('button', { name: 'AI 中心', exact: true }).click();
  await menu.click();
  const search = page.locator('main').getByPlaceholder('搜索...', { exact: true });
  await search.fill(`no_skill_${Date.now()}`);
  await search.press('Enter');
  await expect(page.getByText('没有匹配的技能', { exact: true })).toBeVisible();
  await expect(
    page.getByText('请调整搜索或筛选条件，也可以新建技能并绑定AI同事。', { exact: true }),
  ).toBeVisible();
  await page.screenshot({ path: path.join(shots, 'SM11.png'), fullPage: true });
  await search.fill('');
  await search.press('Enter');
  await page.getByTestId('lang-toggle').getByRole('button').click();
  await page
    .getByTestId('lang-dropdown')
    .getByRole('button', { name: /English/ })
    .click();
  await expect(page.getByRole('link', { name: 'Skills', exact: true })).toBeVisible();
  await page
    .getByRole('button', { name: /Create|New/ })
    .first()
    .click();
  const editor = page.getByTestId('skill-definition-editor');
  await expect(editor.getByLabel('Skill name', { exact: true })).toBeVisible();
  await editor.getByLabel('Skill name', { exact: true }).focus();
  await page.keyboard.press('Tab');
  await expect(editor.getByLabel('Skill identifier', { exact: true })).toBeFocused();
  await expect(editor).not.toContainText('ai.skills.editor.');
  await page.screenshot({ path: path.join(shots, 'SM12.png'), fullPage: true });
});
