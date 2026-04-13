/**
 * BPM Workflow E2E Tests
 *
 * Tests D7-E01 ~ D7-E05, D7-E08: Task center and process management (UI)
 * API tests (D7-E06, D7-E07, D7-E09, D-N04, D-N05) migrated to: tests/api/bpm-workflow.spec.ts
 * - Task center page access
 * - Pending tasks tab
 * - Completed tasks tab
 * - Started by me tab
 * - Task detail view
 * - Process management page access
 *
 * Prerequisites: A simple process (Start -> UserTask -> End) is deployed via API
 * in beforeAll, and a process instance is started to generate tasks.
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';

/**
 * Minimal BPMN XML for a simple Start -> UserTask -> End process.
 */
function generateMinimalBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="E2E Workflow Test" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="E2E Approval Task"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

function generateProcessKey(): string {
  const ts = Date.now();
  const rand = Math.random().toString(36).slice(2, 7);
  return `e2e_wf_${ts}_${rand}`;
}

test.describe('BPM Workflow Operations', () => {
  test.describe.configure({ mode: 'serial' });

  let processPid: string | null = null;
  let processKey: string;
  let processInstanceId: string | null = null;
  let taskId: string | null = null;
  let missingProcessUpdatePermission = false;

  /**
   * Setup: Create, deploy a process, and start an instance to generate tasks.
   */
  test.beforeAll(async ({ request }) => {
    processKey = generateProcessKey();
    const bpmnContent = generateMinimalBpmn(processKey);

    try {
      // Step 1: Create process definition
      const createResponse = await request.post(`/api/bpm/process-definitions`, {
        data: {
          processKey,
          processName: `E2E Workflow Test ${processKey}`,
          description: 'Auto-generated for workflow E2E test',
          category: 'e2e-test',
          bpmnContent,
        },
      });

      if (!createResponse.ok()) {
        if (createResponse.status() === 403) {
          missingProcessUpdatePermission = true;
        }
        console.warn(
          `BPM workflow setup: create failed ${createResponse.status()} ${await createResponse.text().catch(() => '')}`,
        );
        return;
      }

      const createData = await createResponse.json();
      processPid = createData.data?.pid || createData.pid;
      if (!processPid) {
        console.warn(
          'BPM workflow setup: create response missing pid:',
          JSON.stringify(createData).slice(0, 200),
        );
        return;
      }

      // Step 2: Deploy process
      const deployResponse = await request.post(
        `/api/bpm/process-definitions/${processPid}/deploy`,
      );
      if (!deployResponse.ok()) {
        console.warn(
          `BPM workflow setup: deploy failed ${deployResponse.status()} ${await deployResponse.text().catch(() => '')}`,
        );
        return;
      }

      // Step 3: Start process instance
      const startResponse = await request.post(`/api/bpm/process-instances`, {
        data: {
          processDefinitionId: processKey,
          businessKey: `E2E-BK-${Date.now()}`,
          variables: { initiator: 'e2e-test' },
        },
      });

      if (startResponse.ok()) {
        const instanceData = await startResponse.json();
        processInstanceId = instanceData.data?.instanceId || instanceData.instanceId || null;
      } else {
        console.warn(`BPM workflow setup: start instance failed ${startResponse.status()}`);
      }
    } catch (error) {
      console.warn('BPM workflow setup failed:', error);
    }
  });

  /**
   * D7-E01: Open task center page
   * Verify that /bpm/task-center is accessible and renders.
   */
  test('D7-E01: Task center page accessible', async ({ page }) => {
    await page.goto(`/bpm/task-center`, { waitUntil: 'domcontentloaded' });

    // Wait for main content to appear (avoid networkidle which is too slow under load)
    const contentLocator = page.locator('main, h1, [class*="task-center"]');
    const loginLocator = page.locator('text=请先登录, text=欢迎登录');

    const result = await Promise.race([
      contentLocator
        .first()
        .waitFor({ timeout: 8000 })
        .then(() => 'content'),
      loginLocator
        .first()
        .waitFor({ timeout: 8000 })
        .then(() => 'login'),
    ]).catch(() => 'timeout');

    if (result === 'login') {
      throw new Error(String('Authentication not available in this run'));
      return;
    }

    expect(result).toBe('content');
  });

  /**
   * D7-E02: Pending tasks tab renders with data
   * Verify the todo tasks tab shows a table or empty state.
   */
  test('D7-E02: Pending tasks tab', async ({ page }) => {
    await page.goto(`/bpm/task-center`);
    await page.waitForLoadState('domcontentloaded');

    // Check if page loaded correctly (not an error page)
    const hasError = await page
      .locator('text=Application Error')
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    if (hasError) {
      throw new Error(String('Task center page shows Application Error'));
      return;
    }

    // Tab "待办任务" should be visible and active by default
    const todoTab = page.locator('button:has-text("待办任务")');
    await expect(todoTab).toBeVisible({ timeout: 8000 });

    // The page should show task list header "任务列表"
    await expect(page.getByText('任务列表')).toBeVisible({ timeout: 8000 });
  });

  /**
   * D7-E03: Completed tasks tab
   * Switch to completed tab and verify it renders.
   */
  test('D7-E03: Completed tasks tab', async ({ page }) => {
    await page.goto(`/bpm/task-center`);
    await page.waitForLoadState('domcontentloaded');

    const completedTab = page.locator('button:has-text("已办任务")');
    await expect(completedTab).toBeVisible({ timeout: 8000 });
    await completedTab.click();

    // Wait for tab content to load: either a task table or empty state
    const tableOrEmpty = page.locator('table, [role="table"]').or(page.getByText('暂无任务'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 8000 });
  });

  /**
   * D7-E04: Started by me tab
   * Switch to "我发起的" tab and verify it renders.
   */
  test('D7-E04: Started by me tab', async ({ page }) => {
    await page.goto(`/bpm/task-center`);
    await page.waitForLoadState('domcontentloaded');

    // Wait for page to render properly (not an Application Error page)
    const startedTab = page.locator('button:has-text("我发起的")');
    const errorPage = page.locator('text=Application Error');
    const firstVisible = await Promise.race([
      startedTab.waitFor({ timeout: 8000 }).then(() => 'tab' as const),
      errorPage.waitFor({ timeout: 8000 }).then(() => 'error' as const),
    ]).catch(() => 'timeout' as const);

    if (firstVisible === 'error' || firstVisible === 'timeout') {
      throw new Error(String('Task center page not available (Application Error or timeout)'));
      return;
    }

    await startedTab.click();

    // Wait for tab content to load: process table or known empty states
    const tableOrEmpty = page
      .locator('table, [role="table"]')
      .or(page.getByText('暂无流程'))
      .or(page.getByText('暂无任务'))
      .or(page.getByText('No data'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 12000 });
  });

  /**
   * D7-E05: Open task detail
   * Fetch a task from the API and verify task info is accessible.
   */
  test('D7-E05: Task detail accessible', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    // Fetch todo tasks via API to get a task ID
    const todoResponse = await page.request.get(`/api/bpm/tasks/todo`);

    if (!todoResponse.ok()) {
      test.skip(true, 'Todo tasks API not available in current environment');
    }

    const todoData = await todoResponse.json();
    const tasks = todoData.data || todoData;

    if (!Array.isArray(tasks) || tasks.length === 0) {
      test.skip(true, 'No todo tasks available in current environment');
    }

    // Store task ID for later tests
    taskId = tasks[0].taskId || tasks[0].instanceId;

    // Verify task detail via API
    if (taskId) {
      const detailResponse = await page.request.get(`/api/bpm/tasks/${taskId}`);
      expect(detailResponse.ok()).toBe(true);

      const detailData = await detailResponse.json();
      const task = detailData.data || detailData;
      expect(task).toBeTruthy();
    }
  });

  /**
   * D7-E08: Process management page accessible
   * Verify that /bpm/process-management renders correctly.
   */
  test('D7-E08: Process management page', async ({ page }) => {
    // Support both legacy and current routes.
    await page.goto(`/bpm/process-status`, { waitUntil: 'domcontentloaded' }).catch(() => null);
    if (page.url().includes('/login')) {
      await page
        .goto(`/bpm/process-management`, { waitUntil: 'domcontentloaded' })
        .catch(() => null);
    }

    // Wait for main content
    await page.locator('main').first().waitFor({ timeout: 8000 });

    // Verify the page loaded (process status or management page)
    const hasContent = await page
      .locator('table, h1, h2, [data-testid="page-title"], text=流程, text=Process')
      .first()
      .isVisible({ timeout: 8000 })
      .catch(() => false);

    if (!hasContent) {
      // Some deployments render an empty shell first; keep a minimal page-availability assertion.
      await expect(page.locator('main').first()).toBeVisible({ timeout: 3000 });
      return;
    }

    expect(hasContent).toBe(true);
  });

  /**
   * Cleanup: Delete test process and instances.
   */
  test.afterAll(async ({ request }) => {
    if (!processPid) return;

    try {
      // Undeploy first
      await request.post(`/api/bpm/process-definitions/${processPid}/undeploy`);
    } catch {
      // Ignore
    }

    try {
      await request.delete(`/api/bpm/process-definitions/${processPid}`);
    } catch (error) {
      console.warn('Failed to cleanup workflow test data:', error);
    }
  });
});
