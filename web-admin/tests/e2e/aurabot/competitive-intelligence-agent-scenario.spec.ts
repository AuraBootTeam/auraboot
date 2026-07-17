/**
 * AuraBot Competitive Intelligence Agent Scenario E2E.
 *
 * This spec proves more than route wiring:
 * chat/stream -> durable workflow -> stub LLM tool_use -> confirmation ->
 * ToolLoopService -> real DSL command -> persisted report artifact -> UI list.
 *
 * The stub provider is deterministic, but the runtime, tool policy,
 * confirmation, command execution, database write, quality rubric, and UI
 * verification are real.
 */

import { expect, test, type Page } from '../../fixtures';
import type { TestInfo } from '@playwright/test';
import { BACKEND_URL } from '../../helpers/environments';

const TEST_ACCOUNT = { email: 'admin@auraboot.com', password: 'Test2026x' };
const STUB_TOOL_USE_MARKER = '@@AURABOOT_STUB_TOOL_USE@@';

type SseEvent = {
  event: string;
  data: any;
};

type QualityCheck = {
  name: string;
  passed: boolean;
  weight: number;
  evidence: unknown;
};

function uniqueId(prefix = 'ciwb_agent'): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`;
}

function apiHeaders(jwt: string): Record<string, string> {
  return {
    Authorization: `Bearer ${jwt}`,
    'Content-Type': 'application/json',
  };
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
    const rawData = line.slice('data:'.length).trim();
    let data: any = rawData;
    try {
      data = JSON.parse(rawData);
    } catch {
      // Keep non-JSON payloads as text for evidence.
    }
    events.push({ event: currentEvent, data });
  }
  return events;
}

async function postSse(jwt: string, path: string, data: unknown): Promise<SseEvent[]> {
  const response = await fetch(`${BACKEND_URL}${path}`, {
    method: 'POST',
    headers: {
      ...apiHeaders(jwt),
      Accept: 'text/event-stream',
    },
    body: JSON.stringify(data),
    signal: AbortSignal.timeout(90_000),
  });
  const text = await response.text();
  expect(response.ok, `${path} should return HTTP 2xx: ${text.slice(0, 1000)}`).toBe(true);
  return parseSse(text);
}

async function queryDynamicList(
  jwt: string,
  modelCode: string,
  keyword: string,
): Promise<Record<string, any>[]> {
  const search = new URLSearchParams({ pageNum: '1', pageSize: '20', keyword });
  const response = await fetch(`${BACKEND_URL}/api/dynamic/${modelCode}/list?${search}`, {
    headers: apiHeaders(jwt),
  });
  const body = await response.json();
  expect(response.ok, `${modelCode} list should return HTTP 2xx`).toBe(true);
  expect(String(body.code), JSON.stringify(body)).toBe('0');
  return body.data?.records || [];
}

function buildReportContent(uid: string): string {
  return [
    `# Weekly competitor scan ${uid}`,
    '',
    '## Sources',
    '- https://example.com/acme/pricing',
    '- https://example.com/northwind/releases',
    '- https://example.com/acme/jobs',
    '',
    '## Pricing Signals',
    'Acme CRM raised the Pro tier entry price and narrowed the self-serve discount window.',
    '',
    '## Release Signals',
    'Northwind AI shipped workflow automation and expanded admin approval controls.',
    '',
    '## Hiring Signals',
    'Acme CRM opened GTM analytics and partner enablement roles in two regions.',
    '',
    '## Sales Impact',
    'Update the battlecard with Acme price pressure, Northwind automation parity, and approval-governance positioning.',
    '',
    '## Risk And Confidence',
    'Confidence is medium because public pricing and release pages are strong signals, while hiring signal interpretation is directional.',
    '',
    '## Next Steps',
    'Ask sales enablement to review pricing deltas, validate source freshness, and publish battlecard notes after approval.',
  ].join('\n');
}

function scoreArtifact(content: string): { score: number; checks: QualityCheck[] } {
  const requiredSections = [
    'Sources',
    'Pricing Signals',
    'Release Signals',
    'Hiring Signals',
    'Sales Impact',
    'Risk And Confidence',
    'Next Steps',
  ];
  const sourceLinks = content.match(/https?:\/\/\S+/g) || [];
  const checks: QualityCheck[] = [
    {
      name: 'minimum_length',
      passed: content.length >= 700,
      weight: 10,
      evidence: content.length,
    },
    {
      name: 'required_sections',
      passed: requiredSections.every((section) => content.includes(section)),
      weight: 30,
      evidence: requiredSections.filter((section) => content.includes(section)),
    },
    {
      name: 'source_links',
      passed: sourceLinks.length >= 2,
      weight: 15,
      evidence: sourceLinks,
    },
    {
      name: 'competitor_specificity',
      passed: /Acme CRM/.test(content) && /Northwind AI/.test(content),
      weight: 15,
      evidence: {
        acme: /Acme CRM/.test(content),
        northwind: /Northwind AI/.test(content),
      },
    },
    {
      name: 'gtm_actionability',
      passed: /battlecard/i.test(content) && /sales enablement/i.test(content),
      weight: 15,
      evidence: {
        battlecard: /battlecard/i.test(content),
        salesEnablement: /sales enablement/i.test(content),
      },
    },
    {
      name: 'no_stub_placeholder_in_artifact',
      passed: !content.includes('[stub response]'),
      weight: 15,
      evidence: content.includes('[stub response]'),
    },
  ];
  return {
    score: checks.reduce((sum, check) => sum + (check.passed ? check.weight : 0), 0),
    checks,
  };
}

async function triggerAgentScenario(
  jwt: string,
  uid: string,
): Promise<{
  artifactPid: string;
  title: string;
  content: string;
  startEvents: SseEvent[];
  resumeEvents: SseEvent[];
}> {
  const title = `Competitive intelligence report ${uid}`;
  const content = buildReportContent(uid);
  const toolId = `tool-${uid}`;
  const directive = `${STUB_TOOL_USE_MARKER} ${JSON.stringify({
    id: toolId,
    name: 'cmd_acp_create_agent_artifact',
    input: {
      artifact_type: 'report',
      title,
      content,
      version: 1,
      tags: 'e2e,competitive-intelligence,agent-runtime',
      metadata: JSON.stringify({
        scenario: 'competitive-intelligence',
        uid,
        generatedBy: 'durable-agent-runtime',
      }),
    },
  })}`;

  const startEvents = await postSse(jwt, '/api/ai/aurabot/chat/stream', {
    sessionId: `session-${uid}`,
    message:
      `Run a durable Agent scenario for agent_artifact. Create a competitive intelligence report artifact for ${uid}. ` +
      'The artifact must include sources, pricing signals, release signals, hiring signals, sales impact, confidence, and next steps.\n' +
      directive,
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
  expect(confirm.toolName).toBe('cmd_acp_create_agent_artifact');
  expect(confirm.toolId).toBe(toolId);
  expect(confirm.pendingTurnId, JSON.stringify(confirm)).toBeTruthy();

  const resumeEvents = await postSse(jwt, '/api/ai/aurabot/execute', {
    pendingTurnId: confirm.pendingTurnId,
    toolId,
    confirmed: true,
  });
  const toolResult = resumeEvents.find((event) => event.event === 'tool_result')?.data;
  expect(toolResult, JSON.stringify(resumeEvents)).toBeTruthy();
  expect(toolResult.success, JSON.stringify(toolResult)).toBe(true);
  expect(toolResult.result?.success, JSON.stringify(toolResult)).toBe(true);
  // Public-record pid-only contract: the created artifact's pid is surfaced at
  // recordPid (recordId, the internal BIGINT, is no longer returned). Depending on
  // the command it sits directly on result or nested under result.data.
  const artifactPid =
    toolResult.result?.recordPid ??
    toolResult.result?.data?.recordPid ??
    toolResult.result?.recordId;
  expect(artifactPid, JSON.stringify(toolResult)).toBeTruthy();

  return {
    artifactPid,
    title,
    content,
    startEvents,
    resumeEvents,
  };
}

async function verifyArtifactFromDashboard(page: Page, title: string): Promise<void> {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const dashboardLink = page.locator('nav a[href="/aurabot/dashboard"]').first();
  await expect(dashboardLink).toBeVisible({ timeout: 15_000 });
  await dashboardLink.click();
  await page.waitForURL((url) => url.pathname === '/aurabot/dashboard', { timeout: 15_000 });

  const dashboard = page.locator('[data-testid="mc-dashboard"]');
  await expect(dashboard).toBeVisible({ timeout: 15_000 });
  await dashboard
    .getByRole('button', { name: /查看产出物|Open artifacts/i })
    .first()
    .click();
  await page.waitForURL((url) => url.pathname === '/p/agent_artifact', { timeout: 15_000 });
  await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 15_000 });

  const input = page.locator('[data-testid="list-search-input"]');
  const responsePromise = page
    .waitForResponse(
      (response) =>
        response.url().includes('/api/dynamic/') &&
        response.url().includes('/list') &&
        response.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);
  await input.fill(title);
  await input.press('Enter');
  await responsePromise;
  await expect(page.getByText(title).first()).toBeVisible({ timeout: 15_000 });
}

test.describe('AuraBot competitive intelligence agent scenario @critical', () => {
  test.setTimeout(120_000);

  test('runs durable Agent workflow and validates report artifact quality', async ({
    page,
  }, testInfo: TestInfo) => {
    const uid = uniqueId();
    const jwt = await login();

    const scenario = await triggerAgentScenario(jwt, uid);
    const artifacts = await queryDynamicList(jwt, 'agent_artifact', scenario.title);
    const artifact = artifacts.find((record) => record.pid === scenario.artifactPid);
    expect(artifact, JSON.stringify(artifacts)).toBeTruthy();
    if (!artifact) {
      throw new Error(`Created artifact ${scenario.artifactPid} was not returned by list API`);
    }
    expect(artifact.title).toBe(scenario.title);
    expect(artifact.artifact_type).toBe('report');
    expect(artifact.tags).toContain('agent-runtime');
    expect(artifact.metadata).toContain(uid);

    const quality = scoreArtifact(String(artifact.content || ''));
    expect(quality.score, JSON.stringify(quality, null, 2)).toBeGreaterThanOrEqual(90);
    expect(
      quality.checks.filter((check) => !check.passed),
      JSON.stringify(quality, null, 2),
    ).toEqual([]);

    await testInfo.attach('ciwb-agent-runtime-events.json', {
      body: JSON.stringify(
        {
          uid,
          artifactPid: scenario.artifactPid,
          startEvents: scenario.startEvents,
          resumeEvents: scenario.resumeEvents,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });
    await testInfo.attach('ciwb-agent-artifact-quality.json', {
      body: JSON.stringify(
        {
          uid,
          artifactPid: scenario.artifactPid,
          title: scenario.title,
          score: quality.score,
          checks: quality.checks,
          contentPreview: String(artifact.content || '').slice(0, 800),
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await verifyArtifactFromDashboard(page, scenario.title);
  });
});
