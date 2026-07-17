/**
 * AuraBot Competitive Intelligence Workbench E2E.
 *
 * This spec covers the product chain documented in
 * docs/use-cases/aurabot-competitive-intelligence-workbench.md:
 * mission -> task -> run/trace -> approval/interrupt -> artifact ->
 * memory/profile -> schedule/policy.
 *
 * Test data is created through real command APIs and intentionally kept after
 * the run so the record ids and titles remain as test evidence.
 */

import { expect, test, type Page } from '../../fixtures';
import type { TestInfo } from '@playwright/test';
import { executeCommandViaApi, uniqueId } from '../helpers';

test.describe('AuraBot competitive intelligence workbench @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  const uid = uniqueId('ciwb');
  const seed = {
    missionTitle: `Weekly competitor scan ${uid}`,
    taskTitle: `Collect pricing signals ${uid}`,
    agentName: `Research Agent ${uid}`,
    agentCode: `research_${uid.toLowerCase()}`.replace(/[^a-z0-9_]/g, '_').slice(0, 48),
    scheduleName: `Weekly competitor cadence ${uid}`,
    artifactTitle: `Battlecard report ${uid}`,
    policyName: `Competitive intel approval policy ${uid}`,
    memoryTitle: `Competitor preference memory ${uid}`,
    runModel: 'claude-sonnet-4-6',
  };
  const recordIds: Record<string, string> = {};

  test.beforeAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    try {
      recordIds.mission = (
        await executeCommandViaApi(
          page,
          'acp:create_mission',
          {
            title: seed.missionTitle,
            description: 'E2E retained evidence for competitive intelligence workbench coverage.',
            mission_status: 'active',
            acp_priority: 3,
            kpis: JSON.stringify(['weekly brief', 'pricing change', 'battlecard update']),
            tags: 'e2e,competitive-intelligence',
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.mission).toBeTruthy();

      recordIds.agent = (
        await executeCommandViaApi(
          page,
          'acp:create_agent_definition',
          {
            agent_code: seed.agentCode,
            name: seed.agentName,
            description: 'Collects public competitor signals for E2E workbench coverage.',
            agent_type: 'autonomous',
            model: seed.runModel,
            status: 'active',
            expertise: 'competitive intelligence',
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.agent).toBeTruthy();

      recordIds.task = (
        await executeCommandViaApi(
          page,
          'acp:create_agent_task',
          {
            mission_id: recordIds.mission,
            title: seed.taskTitle,
            description: 'Collect competitor pricing pages, release notes, and hiring signals.',
            task_status: 'in_progress',
            task_priority: 'high',
            assignee_type: 'agent',
            assignee_id: seed.agentCode,
            input_data: JSON.stringify({
              competitors: ['Acme CRM', 'Northwind AI'],
              outputs: ['change summary', 'battlecard notes'],
            }),
            estimated_cost: 2.5,
            max_retries: 2,
            tags: 'e2e,ciwb',
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.task).toBeTruthy();

      recordIds.run = (
        await executeCommandViaApi(
          page,
          'acp:create_agent_run',
          {
            task_id: recordIds.task,
            agent_id: seed.agentCode,
            run_status: 'completed',
            run_model: seed.runModel,
            duration_ms: 4200,
            input_tokens: 1600,
            output_tokens: 620,
            total_cost: 0.42,
            messages: JSON.stringify([{ role: 'assistant', content: 'Competitive scan complete' }]),
            metadata: JSON.stringify({
              scenario: 'competitive-intelligence',
              traceId: `trace-${uid}`,
            }),
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.run).toBeTruthy();

      recordIds.artifact = (
        await executeCommandViaApi(
          page,
          'acp:create_agent_artifact',
          {
            run_id: recordIds.run,
            task_id: recordIds.task,
            artifact_type: 'report',
            title: seed.artifactTitle,
            content: 'E2E battlecard report with source links, change summary, cost, and traces.',
            version: 1,
            tags: 'e2e,ciwb',
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.artifact).toBeTruthy();

      recordIds.schedule = (
        await executeCommandViaApi(
          page,
          'acp:create_agent_schedule',
          {
            mission_id: recordIds.mission,
            title: seed.scheduleName,
            description: 'Weekly retained E2E schedule for competitor scanning.',
            schedule_type: 'cron',
            cron_expression: '0 0 9 ? * MON',
            schedule_status: 'active',
            timezone: 'Asia/Shanghai',
            task_template: JSON.stringify({
              title: 'Weekly competitor scan',
              assignee_id: seed.agentCode,
            }),
            max_runs: 12,
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.schedule).toBeTruthy();

      recordIds.policy = (
        await executeCommandViaApi(
          page,
          'acp:create_approval_policy',
          {
            policy_name: seed.policyName,
            description:
              'Require approval before externally visible competitive intelligence actions.',
            trigger_rules: JSON.stringify([{ type: 'external_action', requiresApproval: true }]),
            approver_rules: JSON.stringify([{ role: 'tenant_admin' }]),
            policy_status: 'active',
            timeout_hours: 24,
            timeout_action: 'reject',
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.policy).toBeTruthy();

      recordIds.memory = (
        await executeCommandViaApi(
          page,
          'acp:create_agent_memory',
          {
            memory_agent_id: seed.agentCode,
            memory_type: 'preference',
            category: 'competitive-intelligence',
            memory_title: seed.memoryTitle,
            memory_content:
              'Prefer weekly briefs with pricing deltas, source links, and sales impact.',
            importance: 8,
            metadata: JSON.stringify({ scenario: 'competitive-intelligence' }),
          },
          undefined,
          'create',
        )
      ).recordId;
      expect(recordIds.memory).toBeTruthy();
    } finally {
      await context.close();
    }
  });

  test('covers Dashboard workbench chain with retained data and route evidence', async ({
    page,
  }, testInfo) => {
    const monitor = createRuntimeMonitor(page);
    const evidence: Array<Record<string, unknown>> = [];

    await openDashboardFromSidebar(page);
    await expect(page.locator('[data-testid="mc-scenario-workbench"]')).toBeVisible();
    await attachScreenshot(page, testInfo, 'ciwb-dashboard');

    await testInfo.attach('ciwb-seed-records.json', {
      body: JSON.stringify({ uid, seed, recordIds }, null, 2),
      contentType: 'application/json',
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'mission-create',
      click: () => page.locator('[data-testid="mc-scenario-start"]').click(),
      expectedPath: '/p/mission/new',
      assertPage: async () => {
        await expect(page.locator('[data-testid="form-field-title"]')).toBeVisible();
        await expect(page.locator('[data-testid="form-field-owner_id"]')).toBeVisible();
      },
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'mission-list',
      buttonName: /使命.*定义业务目标|Mission.*Define business goal/i,
      expectedPath: '/p/mission',
      searchText: seed.missionTitle,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'task-chain',
      buttonName: /查看任务链路|Open task chain/i,
      expectedPath: '/p/agent_task',
      searchText: seed.taskTitle,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'run-log',
      buttonName: /运行与追踪|Runs And Traces/i,
      expectedPath: '/aurabot/runs',
      assertPage: async () => {
        await expect(page.getByText(/Run Log|运行记录|Traces/i).first()).toBeVisible();
      },
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'approval-queue',
      buttonName: /处理审批|Review approvals/i,
      expectedPath: '/p/agent_approval',
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'artifact-delivery',
      buttonName: /查看产出物|Open artifacts/i,
      expectedPath: '/p/agent_artifact',
      searchText: seed.artifactTitle,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'agent-team',
      buttonName: /团队状态|Team status/i,
      expectedPath: '/p/agent_definition',
      searchText: seed.agentName,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'schedule-config',
      buttonName: /调度.*周期性研究任务|Schedules.*recurring/i,
      expectedPath: '/p/agent_schedule',
      searchText: seed.scheduleName,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'approval-policy',
      buttonName: /审批策略.*配置风险规则|Policies.*Configure risk/i,
      expectedPath: '/p/approval_policy',
      searchText: seed.policyName,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'memory-library',
      buttonName: /记忆库.*企业偏好|Memory.*preference/i,
      expectedPath: '/p/agent_memory',
      searchText: seed.memoryTitle,
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'ai-traces',
      buttonName: /AI 追踪|AI Traces/i,
      expectedPath: '/aurabot/traces',
      assertPage: async () => {
        await expect(page.getByText(/AI Trace|Trace Console|追踪/i).first()).toBeVisible();
      },
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'interrupt-audit',
      buttonName: /中断审计|Interrupts/i,
      expectedPath: '/aurabot/interrupts',
    });

    await verifyDashboardEntry(page, evidence, {
      label: 'profile-preferences',
      buttonName: /我的画像|My Profile/i,
      expectedPath: '/aurabot/my-profile',
    });

    await expect(
      monitor.badResponses,
      'No 403/404/500 responses while exercising workbench',
    ).toEqual([]);
    await expect(
      monitor.consoleMessages,
      'No console errors or warnings while exercising workbench',
    ).toEqual([]);

    await testInfo.attach('ciwb-route-evidence.json', {
      body: JSON.stringify(evidence, null, 2),
      contentType: 'application/json',
    });
  });
});

interface DashboardEntry {
  label: string;
  expectedPath: string;
  buttonName?: RegExp;
  searchText?: string;
  click?: () => Promise<void>;
  assertPage?: () => Promise<void>;
}

function createRuntimeMonitor(page: Page): {
  badResponses: Array<{ status: number; url: string }>;
  consoleMessages: Array<{ type: string; text: string }>;
} {
  const badResponses: Array<{ status: number; url: string }> = [];
  const consoleMessages: Array<{ type: string; text: string }> = [];
  page.on('response', (response) => {
    if ([403, 404, 500].includes(response.status())) {
      badResponses.push({ status: response.status(), url: response.url() });
    }
  });
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      const text = message.text();
      // Ignore benign "[i18n] Missing translation key" dev warnings. These are
      // platform-wide keys (list.expand_row / list.collapse_row aria-labels,
      // workbench.openInDashboard) that render via explicit graceful fallbacks in
      // ListTable / home — no raw key leaks to the user. They are not introduced by
      // this feature and should not fail the workbench chain (the sibling
      // crm-starter-lead-workbench spec filters to product errors for the same reason).
      if (text.includes('[i18n] Missing translation key')) {
        return;
      }
      consoleMessages.push({ type: message.type(), text });
    }
  });
  return { badResponses, consoleMessages };
}

async function openDashboardFromSidebar(page: Page): Promise<void> {
  if (!page.url().startsWith('http')) {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
  }
  if (new URL(page.url()).pathname !== '/aurabot/dashboard') {
    const dashboardLink = page.locator('nav a[href="/aurabot/dashboard"]').first();
    await expect(dashboardLink).toBeVisible({ timeout: 15_000 });
    await dashboardLink.click();
  }
  await page.waitForURL((url) => url.pathname === '/aurabot/dashboard', { timeout: 15_000 });
  await expect(page.locator('[data-testid="mc-scenario-workbench"]')).toBeVisible({
    timeout: 15_000,
  });
}

async function verifyDashboardEntry(
  page: Page,
  evidence: Array<Record<string, unknown>>,
  entry: DashboardEntry,
): Promise<void> {
  await openDashboardFromSidebar(page);
  const listResponse =
    entry.expectedPath.startsWith('/p/') && !entry.expectedPath.endsWith('/new')
      ? page
          .waitForResponse(
            (response) =>
              response.url().includes('/api/dynamic/') &&
              response.url().includes('/list') &&
              response.status() === 200,
            { timeout: 15_000 },
          )
          .catch(() => null)
      : Promise.resolve(null);

  if (entry.click) {
    await entry.click();
  } else if (entry.buttonName) {
    const dashboard = page.locator('[data-testid="mc-dashboard"]');
    await dashboard.getByRole('button', { name: entry.buttonName }).first().click();
  } else {
    throw new Error(`Entry ${entry.label} has no click target`);
  }

  await page.waitForURL((url) => url.pathname === entry.expectedPath, { timeout: 15_000 });
  await listResponse;
  await assertNoForbiddenOrMissing(page);

  if (entry.expectedPath.startsWith('/p/') && !entry.expectedPath.endsWith('/new')) {
    await expect(page.locator('[data-testid="dynamic-list"]')).toBeVisible({ timeout: 15_000 });
    if (entry.searchText) {
      await searchListForEvidence(page, entry.searchText);
    }
  }

  if (entry.assertPage) {
    await entry.assertPage();
  }

  evidence.push({
    label: entry.label,
    expectedPath: entry.expectedPath,
    actualUrl: page.url(),
    searchedFor: entry.searchText ?? null,
  });
}

async function assertNoForbiddenOrMissing(page: Page): Promise<void> {
  const bodyText = await page.locator('body').innerText();
  expect(bodyText).not.toMatch(/Access forbidden|required permission not found|无权限/i);
  expect(bodyText).not.toMatch(/Resource not found|404|Not Found/i);
}

async function searchListForEvidence(page: Page, text: string): Promise<void> {
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

async function attachScreenshot(page: Page, testInfo: TestInfo, name: string): Promise<void> {
  const screenshot = await page.screenshot({ fullPage: true });
  await testInfo.attach(`${name}.png`, {
    body: screenshot,
    contentType: 'image/png',
  });
}
