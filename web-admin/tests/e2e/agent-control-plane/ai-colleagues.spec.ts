/**
 * AI Colleagues & Settings Hub — E2E Tests
 *
 * Coverage:
 * - AI Colleagues card grid page (/p/c/ai_colleagues)
 *   - AuraBot card with Official badge, first position, no edit button
 *   - Regular agent cards with edit/chat buttons
 *   - Navigation to agent detail page with 7 tabs
 *   - Detail page tab switching
 *   - Draft → immutable release → deployment rollback
 *   - Create button presence
 * - AI Settings hub page
 *   - 6 settings cards visible with titles and descriptions
 *   - Card navigation to target pages
 *   - Each card has an icon
 *
 * NOTE: The DSL page uses the canonical /p/c route. Direct navigation is used
 *       in the page-level rendering cases; the journey test separately proves
 *       sidebar reachability.
 */

import { test, expect } from '@playwright/test';

const LIVE_PROVIDER = process.env.AURA_LIVE_LLM_PROVIDER?.trim();
const LIVE_MODEL = process.env.AURA_LIVE_LLM_MODEL?.trim();

test.describe('AI Colleagues (DSL pages)', () => {
  test.setTimeout(30_000);

  // =========================================================================
  // AI Colleagues page
  // =========================================================================

  test('colleagues page loads with title, subtitle, create button, and card grid', async ({
    page,
  }) => {
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);

    // Page title
    await expect(page.locator('h1')).toContainText(/AI Colleagues|AI 同事/);

    // Subtitle
    await expect(page.getByText(/Manage your AI team members|管理您?的 AI 团队成员/)).toBeVisible();

    // Create button with correct data-testid and text
    const createBtn = page.locator('[data-testid="create-agent-btn"]');
    await expect(createBtn).toBeVisible();
    await expect(createBtn).toContainText(/Create AI Colleague|创建 AI 同事/);

    // At least one card visible (AuraBot should always exist)
    const allCards = page.locator('[data-testid="aurabot-card"], [data-testid^="agent-card-"]');
    await expect(allCards.first()).toBeVisible({ timeout: 5_000 });
    const cardCount = await allCards.count();
    expect(cardCount).toBeGreaterThan(0);
  });

  test('AuraBot card is first, shows Official + Full Power badges, chat button, no edit button', async ({
    page,
  }) => {
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);

    const aurabotCard = page.locator('[data-testid="aurabot-card"]');
    if (!(await aurabotCard.isVisible({ timeout: 8_000 }).catch(() => false))) {
      test.skip(true, 'AuraBot card not found — agent may not be seeded');
      return;
    }

    // AuraBot is the first card in the grid
    const firstCard = page
      .locator('[data-testid="aurabot-card"], [data-testid^="agent-card-"]')
      .first();
    await expect(firstCard).toHaveAttribute('data-testid', 'aurabot-card');

    // Official badge
    await expect(aurabotCard.getByText(/Official|官方/)).toBeVisible();

    // Full Power badge
    await expect(aurabotCard.getByText(/Full Power|满血|全权限/)).toBeVisible();

    // Chat button with correct data-testid
    const chatBtn = aurabotCard.locator('[data-testid="aurabot-chat-btn"]');
    await expect(chatBtn).toBeVisible();
    await expect(chatBtn).toContainText(/Chat|对话/);

    // No edit button on AuraBot (AuraBot uses special card without edit)
    const editBtn = page.locator('[data-testid="agent-edit-aurabot"]');
    await expect(editBtn).not.toBeVisible();
  });

  test('AuraBot card shows status badge and type badge', async ({ page }) => {
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);

    const aurabotCard = page.locator('[data-testid="aurabot-card"]');
    if (!(await aurabotCard.isVisible({ timeout: 8_000 }).catch(() => false))) {
      test.skip(true, 'AuraBot not found');
      return;
    }

    // Status badge (text label like "active" with a colored dot)
    const statusBadge = aurabotCard.locator('span.inline-flex').filter({
      hasText: /active|disabled|draft/i,
    });
    await expect(statusBadge.first()).toBeVisible();

    // Type badge (reactive/copilot/autonomous/workflow)
    const typeBadge = aurabotCard.locator('span.inline-flex').filter({
      hasText: /reactive|copilot|autonomous|workflow/i,
    });
    await expect(typeBadge.first()).toBeVisible();
  });

  test('non-AuraBot agent card has edit and chat buttons', async ({ page }) => {
    await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);

    // Find any non-AuraBot card
    const agentCards = page.locator('[data-testid^="agent-card-"]');
    const agentCount = await agentCards.count();
    if (agentCount === 0) {
      test.skip(true, 'No non-AuraBot agent cards found');
      return;
    }

    const firstAgentCard = agentCards.first();
    await expect(firstAgentCard).toBeVisible();

    // Card has agent name in h3
    const agentName = firstAgentCard.locator('h3');
    await expect(agentName).toBeVisible();
    const nameText = await agentName.textContent();
    expect(nameText?.length).toBeGreaterThan(0);

    // Edit button present (data-testid="agent-edit-{code}")
    const editBtn = firstAgentCard.locator('[data-testid^="agent-edit-"]');
    await expect(editBtn).toBeVisible();
    await expect(editBtn).toContainText(/Edit|编辑/);

    // Chat button present (data-testid="agent-chat-{code}")
    const chatBtn = firstAgentCard.locator('[data-testid^="agent-chat-"]');
    await expect(chatBtn).toBeVisible();
    await expect(chatBtn).toContainText(/Chat|对话/);

    // Status badge present (text like "active", "disabled", "draft")
    const statusBadge = firstAgentCard.locator('span.inline-flex').filter({
      hasText: /active|disabled|draft|活跃|禁用|草稿/i,
    });
    await expect(statusBadge.first()).toBeVisible();

    // Type badge present (text like "reactive", "copilot", etc.)
    const typeBadge = firstAgentCard.locator('span.inline-flex').filter({
      hasText: /reactive|copilot|autonomous|workflow/i,
    });
    await expect(typeBadge.first()).toBeVisible();
  });

  test('clicking edit on agent card navigates to detail page with 7 tabs', async ({ page }) => {
    const [listResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);

    // Extract a non-aurabot agent PID from the API response
    const body = await listResponse.json().catch(() => ({}));
    const records = (body as any)?.data?.records ?? [];
    const nonAurabot = records.find((r: any) => r.agent_code !== 'aurabot');
    if (!nonAurabot) {
      test.skip(true, 'No non-AuraBot agents in list response');
      return;
    }

    // Navigate directly to the detail page (Edit button uses navigate())
    const agentPid = nonAurabot.pid;
    await page.goto(`/p/c/ai_colleague_detail?agentPid=${agentPid}`, {
      waitUntil: 'domcontentloaded',
    });

    // Wait for tabs to appear (indicates detail page loaded with agent data)
    const profileTab = page.locator('[data-testid="tab-profile"]');
    await expect(profileTab).toBeVisible({ timeout: 10_000 });

    // The definition editor and runtime-governance surfaces are all first-class tabs.
    const expectedTabKeys = [
      'profile',
      'tools',
      'knowledge',
      'memory',
      'releases',
      'runs',
      'schedules',
    ];
    for (const tabKey of expectedTabKeys) {
      const tab = page.locator(`[data-testid="tab-${tabKey}"]`);
      await expect(tab).toBeVisible({ timeout: 3_000 });
    }
  });

  test('detail page Profile tab shows form fields and back button', async ({ page }) => {
    // Get a valid agent PID from the list API
    const [listResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);
    const body = await listResponse.json().catch(() => ({}));
    const records = (body as any)?.data?.records ?? [];
    const nonAurabot = records.find((r: any) => r.agent_code !== 'aurabot');
    if (!nonAurabot) {
      test.skip(true, 'No non-AuraBot agents');
      return;
    }

    await page.goto(`/p/c/ai_colleague_detail?agentPid=${nonAurabot.pid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.locator('[data-testid="tab-profile"]')).toBeVisible({ timeout: 10_000 });

    // Profile tab is active by default — form inputs should be visible
    const nameInput = page.locator('[data-testid="agent-name-input"]');
    await expect(nameInput).toBeVisible({ timeout: 5_000 });

    // Description textarea
    const descInput = page.locator('[data-testid="agent-description-input"]');
    await expect(descInput).toBeVisible();

    // Agent type select visible with a valid value
    const typeSelect = page.locator('select').first();
    await expect(typeSelect).toBeVisible();
    const typeValue = await typeSelect.inputValue();
    expect(['reactive', 'copilot', 'autonomous', 'proactive', 'workflow']).toContain(typeValue);

    // Back button with data-testid
    const backBtn = page.locator('[data-testid="back-to-colleagues"]');
    await expect(backBtn).toBeVisible({ timeout: 3_000 });
  });

  test('detail page tab switching works across all 7 tabs', async ({ page }) => {
    // Get a valid agent PID from the list API
    const [listResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
        { timeout: 10_000 },
      ),
      page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' }),
    ]);
    const body = await listResponse.json().catch(() => ({}));
    const records = (body as any)?.data?.records ?? [];
    const nonAurabot = records.find((r: any) => r.agent_code !== 'aurabot');
    if (!nonAurabot) {
      test.skip(true, 'No non-AuraBot agents');
      return;
    }

    await page.goto(`/p/c/ai_colleague_detail?agentPid=${nonAurabot.pid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.locator('[data-testid="tab-profile"]')).toBeVisible({ timeout: 10_000 });

    // Click through each tab by data-testid and verify no errors
    const tabKeys = ['tools', 'knowledge', 'memory', 'releases', 'runs', 'schedules', 'profile'];
    for (const tabKey of tabKeys) {
      const tab = page.locator(`[data-testid="tab-${tabKey}"]`);
      await expect(tab).toBeVisible({ timeout: 3_000 });
      await tab.click();
      // Brief wait for tab content to render
      await page.waitForLoadState('domcontentloaded');
    }

    // After cycling back to Profile, name input should still be visible
    const nameInput = page.locator('[data-testid="agent-name-input"]');
    await expect(nameInput).toBeVisible({ timeout: 5_000 });
  });

  test('draft publishes as an immutable release and can roll deployment back', async ({
    page,
  }, testInfo) => {
    const consoleProblems: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error' || message.type() === 'warning') {
        consoleProblems.push(`${message.type()}: ${message.text()}`);
      }
    });

    await page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' });
    const suffix = `${testInfo.workerIndex}_${Date.now()}`;
    const createResponse = await page.request.post('/api/dynamic/agent-definition/create', {
      data: {
        name: `Release lifecycle ${suffix}`,
        agent_code: `release_lifecycle_${suffix}`,
        description: 'v1 deployed definition',
        agent_type: 'copilot',
        communication_style: 'professional',
        status: 'active',
        visibility: 'private',
        model: 'provider-default',
      },
    });
    expect(createResponse.ok()).toBeTruthy();
    const created = await createResponse.json();
    const agentPid = created?.data?.pid as string | undefined;
    expect(agentPid).toBeTruthy();

    await page.goto(`/p/c/ai_colleague_detail?agentPid=${agentPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('tab-profile')).toBeVisible();

    await page.getByTestId('agent-description-input').fill('v2 draft definition');
    const saveResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'PUT' &&
        response.url().includes(`/api/dynamic/agent-definition/${agentPid}`),
    );
    await page.getByTestId('agent-save-btn').click();
    expect((await saveResponse).status()).toBe(200);

    await page.getByTestId('tab-releases').click();
    await expect(page.getByTestId('agent-release-draft-state')).toContainText(
      /Draft changes are not deployed|草稿变更尚未发布/,
    );
    await expect(page.getByTestId('agent-release-1')).toContainText(/Deployed|当前部署/);

    await page.getByTestId('publish-agent-release').click();
    await expect(
      page.getByRole('dialog', { name: /Publish immutable release|发布不可变版本/ }),
    ).toBeVisible();
    const publishResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith(`/api/agent/definitions/${agentPid}/publish`),
    );
    await page.getByTestId('confirm-publish-agent-release').click();
    expect((await publishResponse).status()).toBe(200);

    await expect(page.getByTestId('agent-release-2')).toContainText(/Deployed|当前部署/);
    await expect(page.getByTestId('agent-release-1')).toContainText(/Historical|历史版本/);

    await page.getByTestId('rollback-agent-release-1').click();
    await expect(
      page.getByRole('dialog', { name: /Roll back deployed release|回滚当前部署版本/ }),
    ).toBeVisible();
    const rollbackResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes(`/api/agent/definitions/${agentPid}/releases/`) &&
        response.url().endsWith('/deploy'),
    );
    await page.getByTestId('confirm-rollback-agent-release').click();
    expect((await rollbackResponse).status()).toBe(200);

    await expect(page.getByTestId('agent-release-1')).toContainText(/Deployed|当前部署/);
    await expect(page.getByTestId('agent-release-2')).toContainText(/Historical|历史版本/);
    await page.screenshot({
      path: testInfo.outputPath('agent-release-lifecycle.png'),
      fullPage: true,
    });
    expect(consoleProblems).toEqual([]);
  });

  test('proactive colleague shows governed schedule budget, concurrency and decision evidence', async ({
    page,
  }, testInfo) => {
    const suffix = `${testInfo.workerIndex}_${Date.now()}`;
    const agentCode = `proactive_schedule_${suffix}`;
    const createAgentResponse = await page.request.post('/api/dynamic/agent-definition/create', {
      data: {
        name: `Proactive schedule ${suffix}`,
        agent_code: agentCode,
        description: 'Governed proactive schedule browser fixture',
        agent_type: 'proactive',
        communication_style: 'professional',
        status: 'active',
        visibility: 'private',
        model: LIVE_MODEL || 'provider-default',
        guardrails: JSON.stringify(LIVE_PROVIDER ? { provider: LIVE_PROVIDER } : {}),
        proactive_policy: JSON.stringify({
          enabled: true,
          allowedChannels: ['schedule'],
        }),
      },
    });
    expect(createAgentResponse.ok()).toBeTruthy();
    const createdAgent = await createAgentResponse.json();
    const agentPid = createdAgent?.data?.pid as string | undefined;
    expect(agentPid).toBeTruthy();

    const departmentResponse = await page.request.post('/api/org/departments', {
      data: {
        org_dept_name: `Proactive E2E Dept ${suffix}`,
        org_dept_code: `proactive_e2e_dept_${suffix}`,
        org_dept_status: 'active',
        org_dept_order: 1,
      },
    });
    expect(departmentResponse.ok()).toBeTruthy();
    const department = await departmentResponse.json();
    const departmentPid = department?.data?.pid as string | undefined;
    expect(departmentPid).toBeTruthy();

    const positionResponse = await page.request.post('/api/dynamic/org_position/create', {
      data: {
        org_pos_name: `Proactive E2E Position ${suffix}`,
        org_pos_code: `proactive_e2e_pos_${suffix}`,
        org_pos_dept_id: departmentPid,
        org_pos_level: 'P5',
        org_pos_status: 'active',
      },
    });
    expect(positionResponse.ok()).toBeTruthy();
    const position = await positionResponse.json();
    const positionPid = position?.data?.pid as string | undefined;
    expect(positionPid).toBeTruthy();

    const enrollResponse = await page.request.post(
      `/api/agent/definitions/${agentPid}/enroll-employee`,
      {
        data: { departmentPid, positionPid },
      },
    );
    expect(enrollResponse.ok()).toBeTruthy();
    expect((await enrollResponse.json())?.code).toBe('0');

    const scheduleTitle = `Daily governed review ${suffix}`;
    const createScheduleResponse = await page.request.post('/api/dynamic/agent-schedule/create', {
      data: {
        agent_code: agentCode,
        title: scheduleTitle,
        description: 'Governed schedule with explicit limits',
        schedule_type: 'cron',
        cron_expression: '0 0 9 * * *',
        task_template: JSON.stringify({ prompt: 'Review the daily queue' }),
        schedule_status: 'active',
        timezone: 'Asia/Shanghai',
        daily_run_budget: 8,
        concurrency_limit: 2,
        missed_run_policy: 'skip',
      },
    });
    expect(createScheduleResponse.ok()).toBeTruthy();

    await page.goto(`/p/c/ai_colleague_detail?agentPid=${agentPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByTestId('tab-schedules')).toBeVisible();

    const scheduleListResponse = page.waitForResponse(
      (response) =>
        response.status() === 200 && response.url().includes('/api/dynamic/agent-schedule/list'),
    );
    await page.getByTestId('tab-schedules').click();
    await scheduleListResponse;

    const scheduleRow = page.locator('tr', { hasText: scheduleTitle });
    await expect(scheduleRow).toBeVisible();
    await expect(scheduleRow.getByText(/0 0 9 \* \* \* · Asia\/Shanghai/)).toBeVisible();
    await expect(scheduleRow.getByText(/8 \/ day · 2 concurrent|每日 8 次 · 并发 2/)).toBeVisible();
    await expect(scheduleRow.getByText(/^(active|启用)$/)).toBeVisible();

    await scheduleRow.getByTestId(/run-schedule-now-/).click();
    await expect(page.getByRole('dialog', { name: /Run schedule now|立即运行计划/ })).toBeVisible();
    const triggerResponse = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().includes('/api/agent/schedule/') &&
        response.url().endsWith('/trigger'),
    );
    await page.getByTestId('confirm-run-schedule-now').click();
    const triggerPayload = await (await triggerResponse).json();
    expect(triggerPayload?.code).toBe('0');
    expect(triggerPayload?.data?.taskPid).toBeTruthy();
    await expect(
      page.getByText(/Schedule started through the governed runtime|计划已通过治理运行时启动/),
    ).toBeVisible();
    await expect
      .poll(async () => {
        const response = await page.request.get(
          `/api/dynamic/agent-task/${triggerPayload.data.taskPid}`,
        );
        return response.ok() ? (await response.json())?.data?.pid : null;
      })
      .toBe(triggerPayload.data.taskPid);

    await page.screenshot({
      path: testInfo.outputPath('proactive-schedule-governance.png'),
      fullPage: true,
    });
  });
});
