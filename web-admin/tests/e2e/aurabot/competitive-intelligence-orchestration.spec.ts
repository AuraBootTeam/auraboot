/**
 * AuraBot Competitive Intelligence Orchestration E2E.
 *
 * Covers the scenario chain:
 * Mission -> Tasks -> Research/Data/Sales Agents -> Approval policy ->
 * Schedule trigger -> Run evidence -> Artifact -> Memory -> Dashboard UI.
 *
 * Stub LLM is used only to deterministically request tool calls. Every
 * business object is created through the real AuraBot runtime confirmation
 * path and real ACP DSL commands.
 */

import { expect, test, type Page } from '../../fixtures';
import type { TestInfo } from '@playwright/test';
import { BACKEND_URL } from '../../helpers/environments';

const TEST_ACCOUNT = { email: 'admin@auraboot.com', password: 'Test2026x' };
const STUB_TOOL_USE_MARKER = '@@AURABOOT_STUB_TOOL_USE@@';

type SseEvent = { event: string; data: any };
type EvidenceStep = {
  label: string;
  commandCode?: string;
  recordId?: string;
  toolName?: string;
  events?: string[];
};

function uniqueId(prefix = 'ciwb_orch'): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

function apiHeaders(jwt: string): Record<string, string> {
  return {
    Authorization: `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  };
}

function toolNameForCommand(commandCode: string): string {
  return `cmd_${commandCode.replace(':', '_').replace(/\./g, '_')}`;
}

function parseSse(text: string): SseEvent[] {
  const events: SseEvent[] = [];
  let currentEvent = 'message';
  for (const rawLine of text.split('\n')) {
    const line = rawLine.trimEnd();
    if (line.startsWith('event:')) {
      currentEvent = line.slice('event:'.length).trim();
      continue;
    }
    if (!line.startsWith('data:')) continue;
    let data: any = line.slice('data:'.length).trim();
    try {
      data = JSON.parse(data);
    } catch {
      // Preserve raw data for evidence.
    }
    events.push({ event: currentEvent, data });
  }
  return events;
}

async function login(): Promise<string> {
  const response = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(TEST_ACCOUNT),
  });
  const body = await response.json();
  expect(response.ok, `admin login HTTP status: ${response.status}`).toBe(true);
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  return body.data.jwt;
}

async function postSse(jwt: string, path: string, data: unknown): Promise<SseEvent[]> {
  const response = await fetch(`${BACKEND_URL}${path}`, {
    method: 'POST',
    headers: { ...apiHeaders(jwt), Accept: 'text/event-stream' },
    body: JSON.stringify(data),
    signal: AbortSignal.timeout(90_000),
  });
  const text = await response.text();
  expect(response.ok, `${path} should return HTTP 2xx: ${text.slice(0, 1000)}`).toBe(true);
  return parseSse(text);
}

async function runConfirmedCommand(
  jwt: string,
  uid: string,
  orchestratorAgentCode: string,
  commandCode: string,
  payload: Record<string, unknown>,
  evidence: EvidenceStep[],
): Promise<string> {
  const toolName = toolNameForCommand(commandCode);
  const toolId = `tool-${commandCode.replace(/[^a-z0-9]/gi, '_')}-${Date.now().toString(36)}`;
  const directive = `${STUB_TOOL_USE_MARKER} ${JSON.stringify({
    id: toolId,
    name: toolName,
    input: payload,
  })}`;

  const startEvents = await postSse(jwt, '/api/ai/aurabot/chat/stream', {
    sessionId: `session-${uid}-${toolId}`,
    agentCode: orchestratorAgentCode,
    message: `Execute ${commandCode} for competitive intelligence orchestration ${uid}.\n${directive}`,
    options: {
      provider: 'stub',
      model: 'stub-model',
      maxTokens: 512,
      explicitDurableRequest: true,
      durableWorkflow: true,
    },
  });
  const confirm = startEvents.find((event) => event.event === 'confirm_required')?.data;
  expect(confirm, JSON.stringify(startEvents)).toBeTruthy();
  expect(confirm.toolName).toBe(toolName);
  expect(confirm.toolId).toBe(toolId);
  expect(confirm.pendingTurnId, JSON.stringify(confirm)).toBeTruthy();

  const resumeEvents = await postSse(jwt, '/api/ai/aurabot/execute', {
    pendingTurnId: confirm.pendingTurnId,
    toolId,
    confirmed: true,
  });
  const toolResult = resumeEvents.find((event) => event.event === 'tool_result')?.data;
  const resultContract = resumeEvents.find((event) => event.event === 'result_contract')?.data;
  expect(toolResult, JSON.stringify(resumeEvents)).toBeTruthy();
  expect(toolResult.success, JSON.stringify(toolResult)).toBe(true);
  expect(toolResult.result?.success, JSON.stringify(toolResult)).toBe(true);
  const recordId = toolResult.result?.recordId ?? resultContract?.data?.recordId;
  expect(recordId, JSON.stringify(resumeEvents)).toBeTruthy();

  evidence.push({
    label: commandCode,
    commandCode,
    recordId,
    toolName,
    events: resumeEvents.map((event) => event.event),
  });
  return String(recordId);
}

async function seedOrchestratorAgent(jwt: string, uid: string): Promise<string> {
  const agentCode = `ciwb_orchestrator_${uid}`.replace(/[^a-z0-9_]/gi, '_').slice(0, 48);
  const payload = {
    agent_code: agentCode,
    name: `CIWB Orchestrator ${uid}`,
    description:
      'E2E-only orchestrator agent with ACP tools for competitive intelligence scenario coverage.',
    agent_type: 'supervisor',
    model: 'stub-model',
    system_prompt:
      'Use the exact scripted tool requested by the test. Do not invent additional tools or alter payload fields.',
    tools: JSON.stringify([
      'cmd:acp:create_agent_definition',
      'cmd:acp:create_mission',
      'cmd:acp:create_agent_task',
      'cmd:acp:create_approval_policy',
      'cmd:acp:create_agent_memory',
      'cmd:acp:create_agent_schedule',
      'cmd:acp:create_agent_artifact',
    ]),
    skills: JSON.stringify(['dsl.command']),
    guardrails: JSON.stringify({ provider: 'stub', maxCostPerRun: 1.0 }),
    status: 'active',
    expertise: 'competitive intelligence orchestration',
  };
  const response = await fetch(`${BACKEND_URL}/api/dynamic/agent-definition/create`, {
    method: 'POST',
    headers: apiHeaders(jwt),
    body: JSON.stringify(payload),
  });
  const body = await response.json();
  expect(response.ok, `orchestrator seed should return HTTP 2xx: ${JSON.stringify(body)}`).toBe(
    true,
  );
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  return agentCode;
}

async function apiPost(jwt: string, path: string, data: unknown): Promise<any> {
  const response = await fetch(`${BACKEND_URL}${path}`, {
    method: 'POST',
    headers: apiHeaders(jwt),
    body: JSON.stringify(data),
  });
  const body = await response.json();
  expect(response.ok, `${path} should return HTTP 2xx: ${JSON.stringify(body)}`).toBe(true);
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  return body.data;
}

async function queryDynamicList(
  jwt: string,
  modelCode: string,
  options: { keyword?: string; filters?: Array<Record<string, unknown>>; pageSize?: number },
): Promise<Record<string, any>[]> {
  const search = new URLSearchParams({
    pageNum: '1',
    pageSize: String(options.pageSize ?? 50),
  });
  if (options.keyword) search.set('keyword', options.keyword);
  if (options.filters) search.set('filters', JSON.stringify(options.filters));
  const response = await fetch(`${BACKEND_URL}/api/dynamic/${modelCode}/list?${search}`, {
    headers: apiHeaders(jwt),
  });
  const body = await response.json();
  expect(response.ok, `${modelCode} list should return HTTP 2xx`).toBe(true);
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  return body.data?.records || [];
}

async function pollForRunByTask(jwt: string, taskPid: string): Promise<Record<string, any>> {
  for (let attempt = 0; attempt < 30; attempt += 1) {
    const records = await queryDynamicList(jwt, 'agent_run', {
      filters: [{ fieldName: 'task_id', operator: 'EQ', value: taskPid }],
      pageSize: 5,
    });
    const run = records.find((record) => record.task_id === taskPid);
    if (run?.pid && ['completed', 'success', 'failed'].includes(String(run.run_status))) {
      return run;
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`No terminal agent run found for task ${taskPid}`);
}

function buildReportContent(uid: string): string {
  return [
    `# 竞对调研：A/B/C 公司本周变化 ${uid}`,
    '',
    '## Mission',
    '本周关注 A 公司、B 公司、C 公司在价格、功能、客户案例和关键词上的变化。',
    '',
    '## Research Evidence',
    '- A 公司官网价格页：https://example.com/a/pricing',
    '- B 公司发布日志：https://example.com/b/releases',
    '- C 公司客户案例：https://example.com/c/customers',
    '',
    '## Price Changes',
    'A 公司上调 Pro 套餐入口价，并缩短折扣窗口。',
    '',
    '## Feature Changes',
    'B 公司发布审批流自动化，C 公司增强数据导出能力。',
    '',
    '## Comparison Table',
    '| Company | Pricing | Feature | Customer Signal |',
    '| --- | --- | --- | --- |',
    '| A | Price up | No major feature | Enterprise upsell |',
    '| B | Stable | Workflow automation | Mid-market expansion |',
    '| C | Stable | Data export | Customer proof |',
    '',
    '## Sales Interpretation',
    'Sales Agent 建议更新 battlecard：突出审批治理、数据导出和总体拥有成本。',
    '',
    '## Actions',
    '1. 产品策略复核 B 公司自动化能力差距。',
    '2. 销售赋能团队更新 A 公司价格异动话术。',
    '3. 下周继续监控 C 公司客户案例关键词。',
  ].join('\n');
}

function qualityScore(content: string): { score: number; missing: string[] } {
  const required = [
    'Mission',
    'Research Evidence',
    'Price Changes',
    'Feature Changes',
    'Comparison Table',
    'Sales Interpretation',
    'Actions',
    'https://example.com/a/pricing',
    'https://example.com/b/releases',
    'https://example.com/c/customers',
    'battlecard',
  ];
  const missing = required.filter((item) => !content.includes(item));
  const score = Math.max(0, 100 - missing.length * 10);
  return { score, missing };
}

async function verifyDashboardEntry(page: Page, target: 'artifact' | 'schedule', text: string) {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const dashboardLink = page.locator('nav a[href="/aurabot/dashboard"]').first();
  await expect(dashboardLink).toBeVisible({ timeout: 15_000 });
  await dashboardLink.click();
  await page.waitForURL((url) => url.pathname === '/aurabot/dashboard', { timeout: 15_000 });

  const dashboard = page.locator('[data-testid="mc-dashboard"]');
  await expect(dashboard).toBeVisible({ timeout: 15_000 });
  if (target === 'artifact') {
    await dashboard
      .getByRole('button', { name: /查看产出物|Open artifacts/i })
      .first()
      .click();
    await page.waitForURL((url) => url.pathname === '/p/agent_artifact', { timeout: 15_000 });
  } else {
    await dashboard
      .getByRole('button', { name: /调度.*周期性研究任务|Schedules.*recurring/i })
      .first()
      .click();
    await page.waitForURL((url) => url.pathname === '/p/agent_schedule', { timeout: 15_000 });
  }

  const input = page.locator('[data-testid="list-search-input"]');
  await expect(input).toBeVisible({ timeout: 15_000 });
  const responsePromise = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/') &&
        response.url().includes('/list') &&
        response.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);
  await input.fill(text);
  await input.press('Enter');
  await responsePromise;
  await expect(page.getByText(text).first()).toBeVisible({ timeout: 15_000 });
}

test.describe('AuraBot competitive intelligence orchestration @critical', () => {
  test.setTimeout(240_000);

  test('connects Mission, Tasks, Agents, Approval, Run, Artifact, Memory, and Schedule', async ({
    page,
  }, testInfo: TestInfo) => {
    const uid = uniqueId();
    const jwt = await login();
    const evidence: EvidenceStep[] = [];
    const orchestratorAgentCode = await seedOrchestratorAgent(jwt, uid);

    const agentCodes = {
      research: `research_${uid}`.replace(/[^a-z0-9_]/gi, '_').slice(0, 48),
      analyst: `analyst_${uid}`.replace(/[^a-z0-9_]/gi, '_').slice(0, 48),
      sales: `sales_${uid}`.replace(/[^a-z0-9_]/gi, '_').slice(0, 48),
      aurabot: `aurabot_${uid}`.replace(/[^a-z0-9_]/gi, '_').slice(0, 48),
    };

    for (const [role, agentCode] of Object.entries(agentCodes)) {
      await runConfirmedCommand(
        jwt,
        uid,
        orchestratorAgentCode,
        'acp:create_agent_definition',
        {
          agent_code: agentCode,
          name: `${role} Agent ${uid}`,
          description: `${role} agent for competitive intelligence orchestration E2E.`,
          agent_type: role === 'aurabot' ? 'supervisor' : 'reactive',
          model: 'stub-model',
          system_prompt: `Act as the ${role} agent in a competitive intelligence workflow.`,
          tools: JSON.stringify(['cmd:acp:create_agent_artifact']),
          skills: JSON.stringify(['dsl.command']),
          guardrails: JSON.stringify({ provider: 'stub', maxCostPerRun: 0.5 }),
          status: 'active',
          expertise: 'competitive intelligence',
        },
        evidence,
      );
    }

    const missionTitle = `竞对调研：A/B/C 公司本周变化 ${uid}`;
    const missionPid = await runConfirmedCommand(
      jwt,
      uid,
      orchestratorAgentCode,
      'acp:create_mission',
      {
        title: missionTitle,
        description:
          'Track weekly competitor changes across pricing, features, customer stories, and keywords.',
        mission_status: 'active',
        acp_priority: 4,
        kpis: JSON.stringify(['weekly report', 'comparison table', 'source evidence']),
        tags: 'e2e,competitive-intelligence,orchestration',
      },
      evidence,
    );

    const subTasks = [
      {
        key: 'official-site',
        agentCode: agentCodes.research,
        title: `抓取官网变化 ${uid}`,
        description: 'Research Agent collects public website and release-note changes.',
      },
      {
        key: 'pricing',
        agentCode: agentCodes.research,
        title: `整理价格变化 ${uid}`,
        description: 'Research Agent captures pricing page changes and source links.',
      },
      {
        key: 'features',
        agentCode: agentCodes.analyst,
        title: `提取功能变化并生成对比表 ${uid}`,
        description: 'Data Analyst extracts feature deltas and normalizes a comparison table.',
      },
      {
        key: 'sales',
        agentCode: agentCodes.sales,
        title: `输出业务解读 ${uid}`,
        description: 'Sales Agent turns findings into battlecard notes and action recommendations.',
      },
      {
        key: 'report',
        agentCode: agentCodes.aurabot,
        title: `生成竞对周报 ${uid}`,
        description:
          'AuraBot supervises the final report, cost summary, evidence chain, and handoff.',
      },
    ];

    const taskIds: Record<string, string> = {};
    for (const task of subTasks) {
      taskIds[task.key] = await runConfirmedCommand(
        jwt,
        uid,
        orchestratorAgentCode,
        'acp:create_agent_task',
        {
          mission_id: missionPid,
          title: task.title,
          description: task.description,
          task_status: 'todo',
          task_priority: task.key === 'report' ? 'high' : 'normal',
          assignee_type: 'agent',
          assignee_id: task.agentCode,
          input_data: JSON.stringify({
            competitors: ['A 公司', 'B 公司', 'C 公司'],
            focus: ['pricing', 'features', 'customer stories', 'keywords'],
            scenario: uid,
          }),
          max_retries: 2,
          tags: `e2e,ciwb-orchestration,${task.key}`,
        },
        evidence,
      );
    }

    const policyPid = await runConfirmedCommand(
      jwt,
      uid,
      orchestratorAgentCode,
      'acp:create_approval_policy',
      {
        policy_name: `竞对调研审批策略 ${uid}`,
        description:
          'Require approval for external browsing, budget overrun, email sending, and high-risk commands.',
        trigger_rules: JSON.stringify([
          { type: 'external_site_access', requiresApproval: true },
          { type: 'budget_overrun', threshold: 0.5, requiresApproval: true },
          { type: 'send_email', requiresApproval: true },
          { type: 'high_risk_command', requiresApproval: true },
        ]),
        approver_rules: JSON.stringify([{ role: 'tenant_admin' }]),
        auto_approve: false,
        timeout_hours: 24,
        timeout_action: 'reject',
        policy_status: 'active',
      },
      evidence,
    );

    const memoryPid = await runConfirmedCommand(
      jwt,
      uid,
      orchestratorAgentCode,
      'acp:create_agent_memory',
      {
        memory_agent_id: agentCodes.aurabot,
        memory_type: 'preference',
        category: 'competitive-intelligence',
        memory_title: `企业竞对偏好 ${uid}`,
        memory_content:
          '重点关注价格、功能、客户案例、竞品关键词；输出必须包含来源链接和销售行动建议。',
        importance: 9,
        metadata: JSON.stringify({ scenario: uid, missionPid }),
      },
      evidence,
    );

    const scheduleTitle = `每周一竞对调研 ${uid}`;
    const schedulePid = await runConfirmedCommand(
      jwt,
      uid,
      orchestratorAgentCode,
      'acp:create_agent_schedule',
      {
        mission_id: missionPid,
        title: scheduleTitle,
        description: 'Run the competitive intelligence workflow every Monday morning.',
        schedule_type: 'cron',
        cron_expression: '0 0 9 ? * MON',
        schedule_status: 'active',
        timezone: 'Asia/Shanghai',
        task_template: JSON.stringify({
          title: `每周一自动运行：竞对调研 ${uid}`,
          description: 'Scheduled competitive intelligence run generated by orchestration E2E.',
          assignee_id: agentCodes.aurabot,
          task_priority: 'high',
        }),
        max_runs: 12,
      },
      evidence,
    );

    const scheduled = await apiPost(jwt, `/api/agent/schedule/${schedulePid}/trigger`, {});
    expect(scheduled.taskPid, JSON.stringify(scheduled)).toBeTruthy();
    const scheduledRun = await pollForRunByTask(jwt, scheduled.taskPid);
    evidence.push({
      label: 'schedule-trigger-run',
      recordId: scheduledRun.pid,
      events: [`run_status:${scheduledRun.run_status}`, `task:${scheduled.taskPid}`],
    });
    expect(String(scheduledRun.agent_id)).toBe(agentCodes.aurabot);
    expect(['completed', 'success']).toContain(String(scheduledRun.run_status));

    const reportContent = buildReportContent(uid);
    const reportQuality = qualityScore(reportContent);
    expect(reportQuality.score, JSON.stringify(reportQuality)).toBeGreaterThanOrEqual(90);
    expect(reportQuality.missing, JSON.stringify(reportQuality)).toEqual([]);

    const artifactTitle = `竞对周报与对比表 ${uid}`;
    const artifactPid = await runConfirmedCommand(
      jwt,
      uid,
      orchestratorAgentCode,
      'acp:create_agent_artifact',
      {
        run_id: scheduledRun.pid,
        task_id: taskIds.report,
        artifact_type: 'report',
        title: artifactTitle,
        content: reportContent,
        version: 1,
        tags: 'e2e,ciwb-orchestration,weekly-report,comparison-table',
        metadata: JSON.stringify({
          scenario: uid,
          missionPid,
          taskIds,
          policyPid,
          memoryPid,
          schedulePid,
          scheduledRunPid: scheduledRun.pid,
        }),
      },
      evidence,
    );

    const [missionRows, taskRows, agentRows, policyRows, memoryRows, scheduleRows, artifactRows] =
      await Promise.all([
        queryDynamicList(jwt, 'mission', { keyword: missionTitle }),
        queryDynamicList(jwt, 'agent_task', { keyword: uid }),
        queryDynamicList(jwt, 'agent_definition', { keyword: uid }),
        queryDynamicList(jwt, 'approval_policy', { keyword: uid }),
        queryDynamicList(jwt, 'agent_memory', { keyword: uid }),
        queryDynamicList(jwt, 'agent_schedule', { keyword: scheduleTitle }),
        queryDynamicList(jwt, 'agent_artifact', { keyword: artifactTitle }),
      ]);

    expect(missionRows.some((record) => record.pid === missionPid)).toBe(true);
    expect(taskRows.filter((record) => String(record.title).includes(uid))).toHaveLength(
      subTasks.length + 1,
    );
    const businessAgentCodes = new Set(Object.values(agentCodes));
    expect(
      agentRows.filter((record) => businessAgentCodes.has(String(record.agent_code))),
    ).toHaveLength(4);
    expect(policyRows.some((record) => record.pid === policyPid)).toBe(true);
    expect(memoryRows.some((record) => record.pid === memoryPid)).toBe(true);
    expect(scheduleRows.some((record) => record.pid === schedulePid)).toBe(true);
    const artifact = artifactRows.find((record) => record.pid === artifactPid);
    expect(artifact, JSON.stringify(artifactRows)).toBeTruthy();
    expect(artifact?.content).toContain('Comparison Table');
    expect(artifact?.metadata).toContain(String(scheduledRun.pid));

    await testInfo.attach('ciwb-orchestration-evidence.json', {
      body: JSON.stringify(
        {
          uid,
          orchestratorAgentCode,
          missionPid,
          agentCodes,
          taskIds,
          policyPid,
          memoryPid,
          schedulePid,
          scheduledTaskPid: scheduled.taskPid,
          scheduledRunPid: scheduledRun.pid,
          artifactPid,
          reportQuality,
          evidence,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await verifyDashboardEntry(page, 'schedule', scheduleTitle);
    await verifyDashboardEntry(page, 'artifact', artifactTitle);
  });
});
