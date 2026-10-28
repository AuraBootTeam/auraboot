/** Real-stack UI authoring -> command persistence -> colleague binding -> governed tool execution. */
import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { authenticateBffSession } from '../../helpers/bff-auth-session';
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
          skills: '[]',
        },
      },
    },
  );
  const fixtureBody = await fixture.json();
  expect(fixtureBody.code).toBe('0');
  const agentPid = fixtureBody.data.data.recordPid;
  expect(agentPid).toBeTruthy();
  await page.goto('/home');
  await page.getByText('AI 中心', { exact: true }).first().click();
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
