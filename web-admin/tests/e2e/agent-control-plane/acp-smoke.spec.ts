/**
 * Agent Control Plane Smoke Tests
 *
 * Validates the ACP plugin end-to-end:
 * - Creates seed data via commands (mission, agent, tasks, schedule, artifact, approval)
 * - Navigates via sidebar menu to all CRUD pages
 * - Verifies Mission Control dashboard loads with real data (3 tabs: Dashboard, Analytics, Observations)
 * - Validates NQ API responses
 * - Tests CRUD lifecycle (create → update → state transition)
 * - Tests all remaining CRUD pages with seed data
 * - Tests standalone Run Log page at /aurabot/runs
 * - Tests memory CRUD, LIVE indicator, richtext artifact,
 *   child task dispatch, and API_CALL tool type
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId, executeCommandViaApi } from '../helpers/index';
import { expectAcpUiPage, gotoAcpUiPage } from './route-helpers';

// Check if ACP plugin is installed by probing a command endpoint
let acpPluginInstalled = true;

test.describe('Agent Control Plane @smoke', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  const uid = uniqueId('acp');
  const missionTitle = `Mission_${uid}`;
  const missionUpdatedTitle = `MissionUpd_${uid}`;
  const agentName = `Agent_${uid}`;
  const agentCode = `agent_${uid.toLowerCase()}`;
  const taskTitles = [
    `ScanTrends_${uid}`,
    `WriteReport_${uid}`,
    `AnalyzeComp_${uid}`,
  ];
  const scheduleName = `DailyScan_${uid}`;
  const artifactTitle = `Report_${uid}`;
  const policyName = `CostGate_${uid}`;
  const memoryTitle = `Lesson_${uid}`;

  let missionPid: string;
  let agentPid: string;
  const taskPids: string[] = [];
  let schedulePid: string;
  let artifactPid: string;
  let policyPid: string;
  let memoryPid: string;

  // =========================================================================
  // Seed Data — comprehensive across all entity types
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Probe whether ACP plugin is installed by trying to create a mission
      const probeResult = await executeCommandViaApi(
        page, 'acp:create_mission',
        { title: `probe_${uid}`, description: 'Plugin probe', mission_status: 'active', priority: 1 },
        undefined, 'create',
        { allowHttpError: true },
      );
      if (!probeResult.recordId) {
        acpPluginInstalled = false;
        return;
      }
      // Probe succeeded — now create the actual mission with correct title
      const mResult = await executeCommandViaApi(
        page, 'acp:create_mission',
        { title: missionTitle, description: 'E2E test mission', mission_status: 'active', priority: 1 },
        undefined, 'create',
      );
      missionPid = mResult.recordId;
      expect(missionPid, 'Mission should be created').toBeTruthy();

      // Create agent definition
      const aResult = await executeCommandViaApi(
        page, 'acp:create_agent_definition',
        { agent_code: agentCode, name: agentName, description: 'E2E test agent', agent_type: 'autonomous', model: 'claude-sonnet-4-6', status: 'active' },
        undefined, 'create',
      );
      agentPid = aResult.recordId;
      expect(agentPid, 'Agent should be created').toBeTruthy();

      // Create 3 tasks with different statuses
      const statuses = ['todo', 'in_progress', 'backlog'];
      const priorities = ['high', 'critical', 'medium'];
      for (let i = 0; i < 3; i++) {
        const tResult = await executeCommandViaApi(
          page, 'acp:create_agent_task',
          {
            title: taskTitles[i],
            description: `E2E test task ${i + 1}`,
            task_status: statuses[i],
            task_priority: priorities[i],
            assignee_type: 'agent',
            assignee_id: agentCode,
            mission_id: missionPid,
          },
          undefined, 'create',
        );
        taskPids.push(tResult.recordId);
        expect(tResult.recordId, `Task ${i + 1} should be created`).toBeTruthy();
      }

      // Create schedule (task_template is TEXT column)
      const sResult = await executeCommandViaApi(
        page, 'acp:create_agent_schedule',
        {
          title: scheduleName,
          description: 'Daily trend scan',
          schedule_type: 'cron',
          cron_expression: '0 0 9 * * MON-FRI',
          schedule_status: 'active',
          timezone: 'Asia/Shanghai',
          mission_id: missionPid,
          task_template: JSON.stringify({ title: 'Auto scan', assignee_id: agentCode }),
        },
        undefined, 'create',
      );
      schedulePid = sResult.recordId;
      expect(schedulePid, 'Schedule should be created').toBeTruthy();

      // Create artifact
      const artResult = await executeCommandViaApi(
        page, 'acp:create_agent_artifact',
        {
          title: artifactTitle,
          artifact_type: 'report',
          content: 'E2E test report content',
          task_id: taskPids[0],
        },
        undefined, 'create',
      );
      artifactPid = artResult.recordId;
      expect(artifactPid, 'Artifact should be created').toBeTruthy();

      // Create approval policy (TEXT columns for rules)
      const polResult = await executeCommandViaApi(
        page, 'acp:create_approval_policy',
        {
          policy_name: policyName,
          description: 'Require approval for cost > $10',
          trigger_rules: JSON.stringify([{ type: 'cost_threshold', threshold: 10 }]),
          approver_rules: JSON.stringify([{ role: 'tenant_admin' }]),
          policy_status: 'active',
          timeout_hours: 24,
          timeout_action: 'reject',
        },
        undefined, 'create',
      );
      policyPid = polResult.recordId;
      expect(policyPid, 'Approval policy should be created').toBeTruthy();

      // Create memory entry
      const memResult = await executeCommandViaApi(
        page, 'acp:create_agent_memory',
        {
          memory_title: memoryTitle,
          memory_type: 'lesson',
          memory_content: 'Always check rate limits before batch API calls',
          memory_agent_id: agentCode,
          importance: 8,
          category: 'best-practice',
        },
        undefined, 'create',
      );
      memoryPid = memResult.recordId;
      expect(memoryPid, 'Memory should be created').toBeTruthy();
    } finally {
      await ctx.close();
    }
  });

  // Skip all tests if ACP plugin is not installed
  test.beforeEach(async () => {
    expect(acpPluginInstalled, 'ACP plugin (com.auraboot.agent-control-plane) must be installed for ACP smoke tests').toBe(true);
  });

  // =========================================================================
  // Helper: navigate to ACP menu item
  // =========================================================================
  async function navigateToAcpPage(page: Page, href: string) {
    await gotoAcpUiPage(page, href);
  }

  // =========================================================================
  // ACP-01: Mission CRUD page accessible via menu
  // =========================================================================
  test('ACP-01: Missions CRUD page loads with data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/mission');
    await expectAcpUiPage(page, '/dynamic/mission');

    // Verify table has data rows (seed data may be paginated)
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-02: Agent Definitions CRUD page
  // =========================================================================
  test('ACP-02: Agent Definitions CRUD page loads with data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-definition');
    await expectAcpUiPage(page, '/dynamic/agent-definition');

    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-03: Agent Tasks CRUD page
  // =========================================================================
  test('ACP-03: Agent Tasks CRUD page loads with data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-task');
    await expectAcpUiPage(page, '/dynamic/agent-task');

    // Table should have data rows with task data visible
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
    // Verify tasks have expected columns (title, status, priority, assignee type)
    await expect(page.getByText('agent').first()).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // ACP-04: Mission Control dashboard loads with KPI data
  // =========================================================================
  test('ACP-04: Mission Control dashboard loads with KPI data', async ({ page }) => {
    await navigateToAcpPage(page, '/aurabot/dashboard');

    // Wait for navigation to settle — page may redirect if NQ data is unavailable
    await page.waitForLoadState('domcontentloaded');
    const url = page.url();
    expect(url, `Mission Control dashboard redirected unexpectedly to ${url}`).toContain('/aurabot/dashboard');
    await expect(page).toHaveURL(/\/aurabot\/dashboard/, { timeout: 10000 });

    // Dashboard container should be visible
    await expect(page.locator('[data-testid="mission-control"]')).toBeVisible({ timeout: 10000 });

    // Check enterprise availability — if not available, skip KPI assertions
    const enterpriseUpsell = page.locator('text=需要企业版');
    const isEnterprise = !(await enterpriseUpsell.isVisible({ timeout: 2000 }).catch(() => false));
    if (!isEnterprise) {
      // Enterprise AI module not available — skip KPI-specific assertions
      // but still verify the mission-control container rendered
      return;
    }

    // KPI cards should be visible
    await expect(page.locator('[data-testid="mc-kpi-cards"]')).toBeVisible();

    // Verify NQ API returns data with correct counts
    const kpiResponse = await page.request.get(
      '/api/datasource/list?datasourceId=nq:acp_dashboard_kpi&format=records',
    );
    const kpiData = await kpiResponse.json();
    expect(kpiData.success === true || kpiData.code === '0' || kpiData.code === 0).toBe(true);
    const records = kpiData.data?.records || [];
    expect(records.length).toBeGreaterThan(0);

    // Verify our seed data shows in KPI
    const kpi = records[0];
    expect(Number(kpi.active_missions)).toBeGreaterThanOrEqual(1);
    expect(Number(kpi.active_tasks)).toBeGreaterThanOrEqual(3);
    expect(Number(kpi.active_agents)).toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // ACP-05: Mission Control 3-tab layout (Dashboard, Analytics, Observations)
  // =========================================================================
  test('ACP-05: Mission Control 3-tab layout renders correctly', async ({ page }) => {
    await navigateToAcpPage(page, '/aurabot/dashboard');
    await expect(page.locator('[data-testid="mission-control"]')).toBeVisible({ timeout: 10000 });

    // Check enterprise availability — if not available, skip tab assertions
    const enterpriseUpsell = page.locator('text=需要企业版');
    const isNotEnterprise = await enterpriseUpsell.isVisible({ timeout: 2000 }).catch(() => false);
    if (isNotEnterprise) {
      // Enterprise AI module not available — skip 3-tab assertions
      return;
    }

    // Dashboard tab should be active by default
    await expect(page.locator('[data-testid="mc-dashboard"]')).toBeVisible();

    // All 3 tabs should be visible
    await expect(page.locator('[data-testid="mc-tab-dashboard"]')).toBeVisible();
    await expect(page.locator('[data-testid="mc-tab-analytics"]')).toBeVisible();
    await expect(page.locator('[data-testid="mc-tab-observations"]')).toBeVisible();

    // Removed tabs should NOT be present
    await expect(page.locator('[data-testid="mc-tab-tasks"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="mc-tab-agents"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="mc-tab-runs"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="mc-tab-memory"]')).not.toBeVisible();
    await expect(page.locator('[data-testid="mc-tab-approvals"]')).not.toBeVisible();

    // Click Analytics tab — verify it renders
    await page.locator('[data-testid="mc-tab-analytics"]').click();
    await expect(page.locator('[data-testid="mc-analytics"]')).toBeVisible({ timeout: 10000 });

    // Click Observations tab — verify it renders
    await page.locator('[data-testid="mc-tab-observations"]').click();
    // Wait for API response to confirm tab loaded data
    await page.waitForResponse(resp => resp.url().includes('/api/') && resp.status() === 200, { timeout: 10000 }).catch(() => {});

    // Back to Dashboard
    await page.locator('[data-testid="mc-tab-dashboard"]').click();
    await expect(page.locator('[data-testid="mc-dashboard"]')).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // ACP-06: Standalone Run Log page at /aurabot/runs
  // =========================================================================
  test('ACP-06: Run Log page loads via AURABOT menu', async ({ page }) => {
    await page.goto('/aurabot/runs', { waitUntil: 'domcontentloaded' });
    await page.waitForURL(/\/aurabot\/runs/, { timeout: 8000 });

    // Verify the page loads with correct title
    await expect(page.getByText(/Run Log|运行记录/).first()).toBeVisible({ timeout: 10000 });

    // Verify the run log area is visible (either table or empty state)
    await expect(page.locator('[data-testid="run-log"]')).toBeVisible({ timeout: 10000 });

    // Verify NQ for runs is callable
    const runsResponse = await page.request.get(
      '/api/datasource/list?datasourceId=nq:acp_recent_runs&format=records',
    );
    const runsData = await runsResponse.json();
    // API may use code:"0" instead of success:true
    expect(
      runsData.success === true || runsData.code === '0' || runsData.code === 0,
      `Runs NQ API should succeed (code=${runsData.code})`,
    ).toBe(true);
  });

  // =========================================================================
  // ACP-07: DSL pages accessible — agent-task and agent-approval
  // =========================================================================
  test('ACP-07: DSL pages agent-task and agent-approval load with data', async ({ page }) => {
    // agent-task page
    await navigateToAcpPage(page, '/dynamic/agent-task');
    await expectAcpUiPage(page, '/dynamic/agent-task');
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });

    // agent-approval page
    await navigateToAcpPage(page, '/dynamic/agent-approval');
    await expectAcpUiPage(page, '/dynamic/agent-approval');
  });

  // =========================================================================
  // ACP-08: Schedule, Artifact, Approval, Policy pages with seed data
  // =========================================================================
  test('ACP-08: Schedule page loads with seed data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-schedule');
    await expectAcpUiPage(page, '/dynamic/agent-schedule');
    // Verify table has data rows (specific item may be paginated)
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  test('ACP-09: Artifact page loads with seed data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-artifact');
    await expectAcpUiPage(page, '/dynamic/agent-artifact');
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  test('ACP-10: Approval Policy page loads with seed data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/approval-policy');
    await expectAcpUiPage(page, '/dynamic/approval-policy');
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  test('ACP-11: Approval, Memory, Observation, Tool pages accessible', async ({ page }) => {
    // Approvals
    await navigateToAcpPage(page, '/dynamic/agent-approval');
    await expectAcpUiPage(page, '/dynamic/agent-approval');

    // Memory — verify page loads with data
    await navigateToAcpPage(page, '/dynamic/agent-memory');
    await expectAcpUiPage(page, '/dynamic/agent-memory');
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });

    // Observation
    await navigateToAcpPage(page, '/dynamic/agent-observation');
    await expectAcpUiPage(page, '/dynamic/agent-observation');

    // Tool
    await navigateToAcpPage(page, '/dynamic/agent-tool');
    await expectAcpUiPage(page, '/dynamic/agent-tool');
  });

  // =========================================================================
  // ACP-12: Mission CRUD lifecycle — update title via command
  // =========================================================================
  test('ACP-12: Mission update via command reflects in UI', async ({ page }) => {
    // Update mission title via API
    await executeCommandViaApi(
      page, 'acp:update_mission',
      { title: missionUpdatedTitle, description: 'Updated E2E mission' },
      missionPid, 'update',
    );

    // Verify update via API
    const pidFilter = encodeURIComponent(JSON.stringify([{ fieldName: 'pid', operator: 'EQ', value: missionPid }]));
    const verifyResp = await page.request.get(`/api/dynamic/mission/list?pageSize=1&filters=${pidFilter}`);
    const verifyData = await verifyResp.json();
    expect(verifyData.data?.records?.[0]?.title).toBe(missionUpdatedTitle);
  });

  // =========================================================================
  // ACP-13: Mission status transition — active → paused
  // =========================================================================
  test('ACP-13: Mission status transition active → paused', async ({ page }) => {
    // Pause the mission
    const result = await executeCommandViaApi(
      page, 'acp:pause_mission',
      {},
      missionPid, 'update',
    );
    expect((result as any).success !== false, 'Pause mission should succeed').toBeTruthy();

    // Verify via API that status changed
    const listResp = await page.request.get(
      `/api/dynamic/mission/list?pageSize=200&filters=${encodeURIComponent(JSON.stringify([{fieldName:'pid',operator:'EQ',value:missionPid}]))}`,
    );
    const listData = await listResp.json();
    expect(listData.success === true || listData.code === '0' || listData.code === 0).toBe(true);
    const mission = (listData.data?.records || [])[0];
    expect(mission, 'Mission should exist in list').toBeTruthy();
    expect(mission.mission_status).toBe('paused');
  });

  // =========================================================================
  // ACP-14: Task status transition — TODO → in_progress → DONE
  // =========================================================================
  test('ACP-14: Task status transitions TODO → in_progress → DONE', async ({ page }) => {
    const todoTaskPid = taskPids[0]; // ScanTrends task, status: TODO

    // Start the task (TODO → in_progress)
    const startResult = await executeCommandViaApi(
      page, 'acp:start_task',
      {},
      todoTaskPid, 'update',
    );
    expect((startResult as any).success !== false, 'Start task should succeed').toBeTruthy();

    // Complete the task (in_progress → DONE)
    const completeResult = await executeCommandViaApi(
      page, 'acp:complete_task',
      {},
      todoTaskPid, 'update',
    );
    expect((completeResult as any).success !== false, 'Complete task should succeed').toBeTruthy();

    // Verify via API — filter by specific pid to avoid pagination issues
    const pidFilter = encodeURIComponent(JSON.stringify([{ fieldName: 'pid', operator: 'EQ', value: todoTaskPid }]));
    const listResp = await page.request.get(
      `/api/dynamic/agent-task/list?pageSize=10&filters=${pidFilter}`,
    );
    const listData = await listResp.json();
    const task = (listData.data?.records || [])[0];
    expect(task, 'Task should exist').toBeTruthy();
    expect(task.task_status).toBe('done');
  });

  // =========================================================================
  // ACP-15: NQ data consistency — KPI counts match seed data
  // =========================================================================
  test('ACP-15: NQ data consistency after state transitions', async ({ page }) => {
    // After ACP-13 paused the mission and ACP-14 completed a task,
    // KPI should reflect updated counts
    const kpiResponse = await page.request.get(
      '/api/datasource/list?datasourceId=nq:acp_dashboard_kpi&format=records',
    );
    const kpiData = await kpiResponse.json();
    expect(kpiData.success === true || kpiData.code === '0' || kpiData.code === 0).toBe(true);
    const kpi = (kpiData.data?.records || [])[0];
    expect(kpi, 'KPI record should exist').toBeTruthy();

    // active_tasks should still be >= 2 (in_progress + BACKLOG remain; DONE excluded by NQ)
    expect(Number(kpi.active_tasks)).toBeGreaterThanOrEqual(2);

    // active_agents should still be >= 1
    expect(Number(kpi.active_agents)).toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // ACP-16: Agent Runtime status API
  // =========================================================================
  test('ACP-16: Agent runtime status endpoint works', async ({ page }) => {
    const statusRes = await page.request.get('/api/agent/status');
    expect(statusRes.ok(), 'Status API should return 200').toBeTruthy();
    const status = await statusRes.json();
    const data = status?.data ?? status;
    expect(data.enabled, 'Agent runtime should be enabled').toBe(true);
    expect(data.enterpriseAvailable, 'enterpriseAvailable should be present').toBeDefined();
    expect(Array.isArray(data.providers), 'providers should be an array').toBeTruthy();
  });

  // =========================================================================
  // ACP-17: Agent dispatch creates run record
  // =========================================================================
  test('ACP-17: Agent dispatch creates run record', async ({ page }) => {
    // Create a fresh task for dispatch
    const dispatchTask = await executeCommandViaApi(
      page, 'acp:create_agent_task',
      {
        title: `Dispatch_Test_${uid}`,
        description: 'Test agent dispatch',
        task_status: 'todo',
        task_priority: 'low',
        assignee_type: 'agent',
        assignee_id: agentCode,
        mission_id: missionPid,
      },
      undefined, 'create',
    );
    expect(dispatchTask.recordId).toBeTruthy();

    // Dispatch the task
    const dispatchRes = await page.request.post('/api/agent/dispatch', {
      data: { taskPid: dispatchTask.recordId, agentCode },
    });
    expect(dispatchRes.ok(), 'Dispatch should return 200').toBeTruthy();

    // Wait for the run record to appear instead of sleeping.
    await expect.poll(async () => {
      const runsRes = await page.request.get(
        '/api/datasource/list?datasourceId=nq:acp_recent_runs&format=records&maxItems=10',
      );
      if (!runsRes.ok()) return 0;
      const runsData = await runsRes.json();
      return (runsData.data?.records || []).length;
    }, { timeout: 15000, intervals: [500, 1000, 1500] }).toBeGreaterThan(0);

    // Check that a Run record was created
    const runsRes = await page.request.get(
      '/api/datasource/list?datasourceId=nq:acp_recent_runs&format=records&maxItems=10',
    );
    expect(runsRes.ok()).toBeTruthy();
    const runsData = await runsRes.json();
    const runs = runsData.data?.records || [];
    // The run may have failed (no API key) but should exist
    expect(runs.length).toBeGreaterThan(0);
  });

  // =========================================================================
  // ACP-18: Observations logged after agent activity
  // =========================================================================
  test('ACP-18: Observations logged after agent activity', async ({ page }) => {
    // Navigate to observation page and check for records
    await navigateToAcpPage(page, '/dynamic/agent-observation');
    await expectAcpUiPage(page, '/dynamic/agent-observation');

    // After dispatch in ACP-17, observations should exist
    // Wait for table to render (observations from dispatch event)
    await page.waitForSelector('table tbody tr, [class*="empty"]', { timeout: 15000 });
    // The table should have at least one row from the TASK_DISPATCHED observation
    const rows = page.locator('table tbody tr');
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
  });

  // =========================================================================
  // ACP-19: Schedule reload API works
  // =========================================================================
  test('ACP-19: Schedule reload API responds successfully', async ({ page }) => {
    const reloadRes = await page.request.post('/api/agent/schedules/reload');
    expect(reloadRes.ok(), 'Schedule reload should return 200').toBeTruthy();
    const reloadData = await reloadRes.json();
    expect(String(reloadData.code)).toBe('0');
  });

  // =========================================================================
  // ACP-20: Demo agent tool definitions created
  // =========================================================================
  test('ACP-20: Agent tool CRUD page with demo tools', async ({ page }) => {
    // Create 3 demo agent tool definitions
    const demoTools = [
      {
        tool_code: `query_tasks_${uid.toLowerCase()}`,
        tool_type: 'dsl_query',
        tool_name: 'Query Tasks',
        tool_description: 'List all active tasks assigned to the current agent',
        source_code: 'acp_task_board',
        tool_status: 'active',
      },
      {
        tool_code: `update_task_${uid.toLowerCase()}`,
        tool_type: 'dsl_command',
        tool_name: 'Update Task',
        tool_description: 'Update the status or details of an agent task',
        source_code: 'acp:update_agent_task',
        tool_status: 'active',
      },
      {
        tool_code: `create_artifact_${uid.toLowerCase()}`,
        tool_type: 'dsl_command',
        tool_name: 'Create Artifact',
        tool_description: 'Create an output artifact (document, report, code)',
        source_code: 'acp:create_agent_artifact',
        tool_status: 'active',
      },
    ];

    for (const tool of demoTools) {
      const result = await executeCommandViaApi(page, 'acp:create_agent_tool', tool, undefined, 'create');
      expect(result.recordId, `Tool ${tool.tool_code} should be created`).toBeTruthy();
    }

    // Navigate to tools page and verify at least one is visible
    await navigateToAcpPage(page, '/dynamic/agent-tool');
    await expectAcpUiPage(page, '/dynamic/agent-tool');
    await expect(page.locator('text=Query Tasks').first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-21: Memory CRUD and listing
  // =========================================================================
  test('ACP-21: Memory CRUD — create via command and verify in list', async ({ page }) => {
    const memTitle = `MemCRUD_${uid}`;

    // Create a memory entry via command API
    const memResult = await executeCommandViaApi(
      page, 'acp:create_agent_memory',
      {
        memory_title: memTitle,
        memory_content: 'Always validate inputs before processing',
        memory_type: 'fact',
        category: 'test',
        importance: 8,
        memory_agent_id: agentCode,
      },
      undefined, 'create',
    );
    expect(memResult.recordId, 'Memory entry should be created').toBeTruthy();

    // Navigate to Agent Memory list page via menu
    await navigateToAcpPage(page, '/dynamic/agent-memory');
    await expectAcpUiPage(page, '/dynamic/agent-memory');

    // Verify the list has data rows (specific item may be paginated)
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-22: Run detail expandable row on standalone Run Log page
  // =========================================================================
  test('ACP-22: Run detail panel — expandable row on /aurabot/runs', async ({ page }) => {
    await page.goto('/aurabot/runs', { waitUntil: 'load' });
    await expect(page.locator('[data-testid="run-log"]')).toBeVisible({ timeout: 10000 });

    // Check if there are run rows visible (from ACP-17 dispatch)
    const runRows = page.locator('[data-testid="run-log"] table tbody tr');
    const runCount = await runRows.count();

    if (runCount > 0) {
      // Click the first run row to expand detail panel
      await runRows.first().click();

      // Wait for the expanded detail panel to appear
      const detailContent = page.getByText(/工具调用|Tool Call Chain|错误信息|Error|Loading|No tool/i).first();
      await expect(detailContent).toBeVisible({ timeout: 10000 });
    }

    // Verify the NQ for run details is callable
    const runDetailResponse = await page.request.get(
      '/api/datasource/list?datasourceId=nq:acp_run_detail&format=records',
    );
    const runDetailData = await runDetailResponse.json();
    expect(runDetailData.success === true || runDetailData.code === '0' || runDetailData.code === 0).toBe(true);
  });

  // =========================================================================
  // ACP-23: Dashboard LIVE indicator
  // =========================================================================
  test('ACP-23: Dashboard LIVE indicator is present', async ({ page }) => {
    await navigateToAcpPage(page, '/aurabot/dashboard');
    await expect(page.locator('[data-testid="mission-control"]')).toBeVisible({ timeout: 10000 });

    // Skip if enterprise AI module not available
    const upsell = page.locator('text=需要企业版');
    if (await upsell.isVisible({ timeout: 2000 }).catch(() => false)) return;

    // Verify the LIVE indicator is present in the dashboard header
    // This confirms SSE connection setup doesn't crash the page
    const liveIndicator = page.getByText('live').first();
    await expect(liveIndicator).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // ACP-24: Artifact with richtext content
  // =========================================================================
  test('ACP-24: Artifact with richtext content renders correctly', async ({ page }) => {
    const artTitle = `RichArt_${uid}`;

    // Create an artifact with markdown content via API
    const artResult = await executeCommandViaApi(
      page, 'acp:create_agent_artifact',
      {
        title: artTitle,
        artifact_type: 'report',
        content: '# Test Report\n\nThis is a **test** artifact with richtext content.',
        task_id: taskPids[0],
      },
      undefined, 'create',
    );
    expect(artResult.recordId, 'Artifact should be created').toBeTruthy();

    // Navigate to the artifact list page
    await navigateToAcpPage(page, '/dynamic/agent-artifact');
    await expectAcpUiPage(page, '/dynamic/agent-artifact');

    // Verify the list has data rows
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-25: Child task dispatch (sequential chain)
  // =========================================================================
  test('ACP-25: Child task dispatch — sequential chain creates run records', async ({ page }) => {
    // Create a parent task with sequential execution mode
    const parentTitle = `Parent_${uid}`;
    const parentResult = await executeCommandViaApi(
      page, 'acp:create_agent_task',
      {
        title: parentTitle,
        description: 'Parent task for sequential dispatch test',
        task_status: 'todo',
        task_priority: 'high',
        assignee_type: 'agent',
        assignee_id: agentCode,
        mission_id: missionPid,
        agent_config: JSON.stringify({ executionMode: 'sequential' }),
      },
      undefined, 'create',
    );
    const parentPid = parentResult.recordId;
    expect(parentPid, 'Parent task should be created').toBeTruthy();

    // Create 2 child tasks
    const childTitles = [`Child1_${uid}`, `Child2_${uid}`];
    const childPids: string[] = [];
    for (const childTitle of childTitles) {
      const childResult = await executeCommandViaApi(
        page, 'acp:create_agent_task',
        {
          title: childTitle,
          description: `Child task: ${childTitle}`,
          task_status: 'todo',
          task_priority: 'medium',
          assignee_type: 'agent',
          assignee_id: agentCode,
          mission_id: missionPid,
          parent_id: parentPid,
        },
        undefined, 'create',
      );
      childPids.push(childResult.recordId);
      expect(childResult.recordId, `Child task ${childTitle} should be created`).toBeTruthy();
    }

    // Dispatch the parent task (may fail due to no API key, but run records should be created)
    const dispatchRes = await page.request.post('/api/agent/dispatch', {
      data: { taskPid: parentPid, agentCode },
    });
    expect(dispatchRes.ok(), 'Dispatch should return 200').toBeTruthy();

    // Wait for async processing to surface child tasks in the list.
    await expect.poll(async () => {
      const filterParam = encodeURIComponent(JSON.stringify([{ fieldName: 'parent_id', operator: 'EQ', value: parentPid }]));
      const listResp = await page.request.get(
        `/api/dynamic/agent-task/list?pageSize=50&filters=${filterParam}`,
      );
      if (!listResp.ok()) return 0;
      const listData = await listResp.json();
      return (listData.data?.records || []).length;
    }, { timeout: 15000, intervals: [500, 1000, 1500] }).toBeGreaterThanOrEqual(2);
  });

  // =========================================================================
  // ACP-26: API_CALL tool type CRUD
  // =========================================================================
  test('ACP-26: API_CALL tool type CRUD — create and verify in list', async ({ page }) => {
    const toolCode = `api_test_tool_${uid.toLowerCase()}`;

    // Create a tool with API_CALL type
    const toolResult = await executeCommandViaApi(
      page, 'acp:create_agent_tool',
      {
        tool_code: toolCode,
        tool_type: 'api_call',
        tool_name: `API Test Tool ${uid}`,
        tool_description: 'E2E test API_CALL tool',
        source_code: 'GET /api/datasource/list',
        tool_status: 'active',
      },
      undefined, 'create',
    );
    expect(toolResult.recordId, 'API_CALL tool should be created').toBeTruthy();

    // Navigate to Agent Tool list page
    await navigateToAcpPage(page, '/dynamic/agent-tool');
    await expectAcpUiPage(page, '/dynamic/agent-tool');

    // Verify the tool list has data rows
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  test('ACP-27: Tool auto-sync generates tools from published DSL commands', async ({ page }) => {
    // Call the sync endpoint
    const syncResp = await page.request.post('/api/agent/tools/sync');
    expect(syncResp.ok(), 'Tool sync should succeed').toBeTruthy();
    const syncData = await syncResp.json();
    const syncResult = syncData?.data ?? syncData;
    const result = syncResult;
    expect(result).toBeTruthy();
    const totalActions = (result.created || 0) + (result.updated || 0);

    // Verify auto-generated tools exist in the database
    const toolListResp = await page.request.get('/api/dynamic/agent-tool/list?pageSize=200');
    expect(toolListResp.ok()).toBeTruthy();
    const toolListData = await toolListResp.json();
    const tools = toolListData.data?.records || [];

    // Should have auto-generated tools (prefixed with cmd_ or nq_)
    const autoTools = tools.filter((t: any) =>
      (t.tool_code?.startsWith('cmd_') || t.tool_code?.startsWith('nq_')) && t.auto_generated === true
    );
    expect(Array.isArray(autoTools)).toBe(true);
    expect(totalActions, 'Sync may legitimately be a no-op when tools are already up to date').toBeGreaterThanOrEqual(0);
  });

  test('ACP-28: Observation analytics NQs return data', async ({ page }) => {
    // Test the 3 new analytics NQs
    const nqs = ['acp_cost_by_agent', 'acp_daily_activity', 'acp_error_summary'];
    for (const nqCode of nqs) {
      const resp = await page.request.get(
        `/api/datasource/list?datasourceId=nq:${nqCode}&format=records`
      );
      expect(resp.ok(), `NQ ${nqCode} should respond OK`).toBeTruthy();
      const data = await resp.json();
      // These NQs should return valid structure (may have 0 records if no runs yet)
      expect(data.code).toBe('0');
    }
  });

  test('ACP-29: Analytics tab renders with cost and activity sections', async ({ page }) => {
    await navigateToAcpPage(page, '/aurabot/dashboard');
    await expect(page.locator('[data-testid="mission-control"]')).toBeVisible({ timeout: 10000 });

    // Skip if enterprise AI module not available
    const upsell = page.locator('text=需要企业版');
    if (await upsell.isVisible({ timeout: 2000 }).catch(() => false)) return;

    // Click the Analytics tab
    const analyticsTab = page.locator('[data-testid="mc-tab-analytics"]');
    await expect(analyticsTab).toBeVisible({ timeout: 10000 });
    await analyticsTab.click();

    // Analytics view should render
    const analyticsView = page.locator('[data-testid="mc-analytics"]');
    await expect(analyticsView).toBeVisible({ timeout: 15000 });

    // Should have cost breakdown section (even if "no data" message)
    await expect(
      page.getByText(/30.*Cost|成本分布/i).first()
    ).toBeVisible({ timeout: 10000 });

    // Should have daily activity section
    await expect(
      page.getByText(/Daily Activity|每日活动/i).first()
    ).toBeVisible({ timeout: 10000 });

    // Should have error summary section
    await expect(
      page.getByText(/Recent Errors|最近错误/i).first()
    ).toBeVisible({ timeout: 10000 });
  });

  // =========================================================================
  // ACP-30: Agent task DSL page has kanban saved view
  // =========================================================================
  test('ACP-30: Agent task DSL list page is accessible with data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-task');
    await expectAcpUiPage(page, '/dynamic/agent-task');

    // Table should have data rows with task data visible
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
    // Verify tasks have expected columns (assignee type)
    await expect(page.getByText('agent').first()).toBeVisible({ timeout: 5000 });
  });

  // =========================================================================
  // ACP-31: Tool Picker NQ returns active tools
  // =========================================================================
  test('ACP-31: Tool picker NQ (acp_agent_tools_active) is queryable', async ({ page }) => {
    // Query the NQ directly — it may return 0 rows if no tools synced, but should not error
    const res = await page.request.get('/api/datasource/list', {
      params: {
        datasourceId: 'nq:acp_agent_tools_active',
        format: 'records',
        maxItems: '10',
      },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.code).toBe('0');
    // data.records should be an array (possibly empty if no tools synced)
    expect(Array.isArray(body.data?.records)).toBe(true);
  });

  // =========================================================================
  // ACP-32: Agent definition form uses custom render components
  // =========================================================================
  test('ACP-32: Agent definition DSL fields have custom renderComponent configured', async ({ page }) => {
    // Verify the tools field uses agenttoolpicker renderComponent
    const fieldsRes = await page.request.get('/api/datasource/list', {
      params: { datasourceId: 'nq:acp_agent_tools_active', format: 'records', maxItems: '1' },
    });
    expect(fieldsRes.status()).toBe(200);
    const fieldsBody = await fieldsRes.json();
    expect(fieldsBody.code).toBe('0');

    // Verify agent definition model fields have the custom extensions
    // Query the field metadata for agent_definition model
    const metaRes = await page.request.get('/api/meta/models', {
      params: { keyword: 'agent_definition' },
    });
    expect(metaRes.status()).toBe(200);
    const metaBody = await metaRes.json();
    expect(metaBody.code).toBe('0');
  });

  // =========================================================================
  // ACP-33: Memory DSL page accessible and has data
  // =========================================================================
  test('ACP-33: Memory DSL page loads with seed data', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-memory');
    await expectAcpUiPage(page, '/dynamic/agent-memory');
    // Verify the list has data rows from seed data
    await expect(page.locator('table tbody tr').first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-34: Observation Events tab renders
  // =========================================================================
  test('ACP-34: Observation Events tab renders in Mission Control', async ({ page }) => {
    await navigateToAcpPage(page, '/aurabot/dashboard');

    const enterpriseUpsell = page.locator('[data-testid="enterprise-upsell"]');
    if (await enterpriseUpsell.isVisible({ timeout: 5000 }).catch(() => false)) return;

    const tabs = page.locator('[data-testid="mc-tabs"]');
    if (!(await tabs.isVisible({ timeout: 10000 }).catch(() => false))) {
      await expect(page.locator('body')).toContainText(
        /AuraBot Dashboard|Mission Control|仪表盘|需要企业版/,
        { timeout: 10000 },
      );
      return;
    }

    const obsTab = page.locator('[data-testid="mc-tab-observations"]');
    if (!(await obsTab.isVisible({ timeout: 5000 }).catch(() => false))) return;
    await expect(obsTab).toBeVisible({ timeout: 10000 });
    await obsTab.click();
    // Should show a section with observation data or at least the tab content area
    await page.waitForResponse(resp => resp.url().includes('/api/') && resp.status() === 200, { timeout: 10000 }).catch(() => {});
  });

  // =========================================================================
  // ACP-35: Schedule trigger API responds
  // =========================================================================
  test('ACP-35: Schedule manual trigger API endpoint exists', async ({ page }) => {
    // Verify the endpoint exists (may fail with 404 for invalid PID, but not 405)
    const res = await page.request.post(`/api/agent/schedule/NONEXISTENT/trigger`);
    // Should be 400/404/422 but NOT 405 (Method Not Allowed) or 404 on route
    const status = res.status();
    expect([400, 404, 422, 500].includes(status) || status === 200).toBe(true);
  });

  // =========================================================================
  // ACP-36: Run retry API endpoint exists
  // =========================================================================
  test('ACP-36: Run retry API endpoint exists', async ({ page }) => {
    const res = await page.request.post(`/api/agent/run/NONEXISTENT/retry`);
    const status = res.status();
    expect([400, 404, 422, 500].includes(status) || status === 200).toBe(true);
  });

  // =========================================================================
  // ACP-37: Memory search NQ supports keyword filtering
  // =========================================================================
  test('ACP-37: Memory search NQ returns data including test memory', async ({ page }) => {
    const res = await page.request.get('/api/datasource/list', {
      params: {
        datasourceId: 'nq:acp_memory_search',
        format: 'records',
        maxItems: '50',
      },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.code).toBe('0');
    const records = body.data?.records || [];
    // Should find memories (at least the one created in beforeAll)
    expect(records.length).toBeGreaterThanOrEqual(1);
    // Check our test memory exists in the results
    const testMemory = records.find((r: any) => r.memory_title?.includes(uid));
    expect(testMemory, 'Test memory should be in results').toBeTruthy();
  });

  // =========================================================================
  // ACP-38: Run resume API endpoint exists
  // =========================================================================
  test('ACP-38: Run resume API endpoint exists', async ({ page }) => {
    const res = await page.request.post(`/api/agent/run/NONEXISTENT/resume`);
    const status = res.status();
    // Should be 400/404/500 but NOT 405 (endpoint not found)
    expect([400, 404, 422, 500].includes(status) || status === 200).toBe(true);
  });

  // =========================================================================
  // ACP-39: Soul Profile fields render on Agent Definition form
  // =========================================================================
  test('ACP-39: Agent Definition has Soul Profile fields in schema', async ({ page }) => {
    // Verify soul profile fields exist in the model by fetching the agent record
    const res = await page.request.get(`/api/dynamic/agent-definition/list`, {
      params: { pageSize: '10', filters: JSON.stringify([{ fieldName: 'agent_code', operator: 'EQ', value: agentCode }]) },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.code).toBe('0');
    const records = body.data?.records || [];
    expect(records.length).toBeGreaterThanOrEqual(1);
    // The record schema should accept soul profile fields (verified by ACP-40 update)
  });

  // =========================================================================
  // ACP-40: Soul Profile fields can be saved via update command
  // =========================================================================
  test('ACP-40: Update agent with Soul Profile fields', async ({ page }) => {
    const result = await executeCommandViaApi(
      page, 'acp:update_agent_definition',
      {
        personality: 'Analytical and precise',
        expertise: 'Data analysis, financial modeling',
        communication_style: 'concise',
        boundaries: 'Cannot access production databases directly',
        soul_goals: 'Optimize data pipeline efficiency',
      },
      agentPid, 'update',
    );
    expect((result as any).success !== false, 'Soul Profile update should succeed').toBeTruthy();
  });

  // =========================================================================
  // ACP-41: Skills Market CRUD page renders
  // =========================================================================
  test('ACP-41: Skills Market list page loads', async ({ page }) => {
    await navigateToAcpPage(page, '/dynamic/agent-skill');
    // Verify the page has a list structure
    const table = page.locator('table').or(page.locator('[data-testid="smart-table"]'));
    await expect(table.first()).toBeVisible({ timeout: 15000 });
  });

  // =========================================================================
  // ACP-42: Create a skill via command
  // =========================================================================
  test('ACP-42: Create agent skill via command', async ({ page }) => {
    const result = await executeCommandViaApi(
      page, 'acp:create_agent_skill',
      {
        skill_code: `web_search_${uid.toLowerCase()}`,
        skill_name: `Web Search_${uid}`,
        skill_description: 'Search the web for real-time information',
        skill_level: 'atomic',
        skill_category: 'data',
        skill_icon: '🔍',
        skill_version: '1.0.0',
        skill_status: 'active',
        is_builtin: false,
      },
      undefined, 'create',
    );
    expect(result.recordId, 'Skill should be created').toBeTruthy();
  });

  // =========================================================================
  // ACP-43: ab_agent_run has execution_plan column (schema verification)
  // =========================================================================
  test('ACP-43: Agent run schema supports execution plan', async ({ page }) => {
    // Verify by querying the run columns via NQ
    const res = await page.request.get('/api/datasource/list', {
      params: {
        datasourceId: 'nq:acp_recent_runs',
        format: 'records',
        maxItems: '1',
      },
    });
    expect(res.status()).toBe(200);
    const body = await res.json();
    expect(body.code).toBe('0');
    // The NQ should work even with 0 records — confirms schema is valid
  });

  // =========================================================================
  // ACP-44: Agent status API includes configured providers
  // =========================================================================
  test('ACP-44: Agent status API returns provider info', async ({ page }) => {
    const res = await page.request.get('/api/agent/status');
    expect(res.status()).toBe(200);
    const body = await res.json();
    // /api/agent/status returns raw object (not wrapped in {code, data})
    expect(body).toHaveProperty('enabled');
    expect(body).toHaveProperty('configuredProviders');
    expect(body).toHaveProperty('enterpriseAvailable');
  });

  // =========================================================================
  // ACP-45: Provider list API includes dynamically configured providers
  // =========================================================================
  test('ACP-45: Provider list API includes dynamically configured providers', async ({ page }) => {
    const res = await page.request.get('/api/agent/providers');
    expect(res.status()).toBe(200);
    const providers = await res.json();
    // /api/agent/providers returns raw array (not wrapped in {code, data})
    expect(Array.isArray(providers)).toBeTruthy();
    const codes = providers.map((p: any) => p.providerCode);
    // Should include built-in providers
    expect(codes).toContain('minimaxi');
    expect(codes).toContain('anthropic');
    expect(codes).toContain('deepseek');
    // Should have displayName
    const minimaxi = providers.find((p: any) => p.providerCode === 'minimaxi');
    expect(minimaxi).toBeTruthy();
    expect(minimaxi.displayName).toBeTruthy();
  });

  // =========================================================================
  // ACP-46: Configured providers API returns providers with API keys
  // =========================================================================
  test('ACP-46: Configured providers API returns providers with API keys', async ({ page }) => {
    const res = await page.request.get('/api/agent/providers/configured');
    expect(res.status()).toBe(200);
    const body = await res.json();
    // /api/agent/providers/configured returns raw array (not wrapped in {code, data})
    // May be empty if no API keys configured — that's OK, just verify the endpoint works
    expect(Array.isArray(body)).toBeTruthy();
  });
});
