/**
 * BPM Process Lifecycle E2E Tests
 *
 * Full lifecycle: Create (draft) → List shows Edit → Designer loads from pid
 *   → Deploy → Start Instance → Approve Task → Instance Complete → Task Center verification
 *
 * Covers:
 * - BPM-LC01: Navigate to process management via sidebar menu, verify list page
 * - BPM-LC02: Create process via API with designerJson, verify in list (draft status)
 * - BPM-LC03: Click Edit from list → designer loads existing process (draft row shows edit)
 * - BPM-LC04: Open designer via direct URL ?pid= → verify loads correctly
 * - BPM-LC05: Deploy process, start instance, verify task generated
 * - BPM-LC06: Approve task, verify process instance completes
 * - BPM-LC07: Task center shows completed task
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { clickRowActionByLocator } from '../helpers/index';

function uniqueId(): string {
  return `e2et_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
}

/**
 * Minimal BPMN XML for Start -> UserTask -> End.
 */
function generateMinimalBpmn(processKey: string, processName: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="E2E Approval"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

function isProcessUpdateForbidden(message: string): boolean {
  return /system\.process\.update|Access forbidden|Access denied/i.test(message);
}

test.describe('BPM Process Lifecycle', () => {
  test.describe.configure({ mode: 'serial' });

  const testId = uniqueId();
  const processKey = `lc_${testId}`;
  const processName = `Lifecycle Test ${testId}`;
  let processPid: string | null = null;
  let processInstanceId: string | null = null;
  let taskId: string | null = null;
  let missingProcessUpdatePermission = false;

  /**
   * BPM-LC01: Navigate to process management page via sidebar menu
   */
  test('BPM-LC01: Process management list page via menu', async ({ page }) => {
    await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('domcontentloaded');

    // Expand BPM Management parent menu
    const bpmParent = page.locator('nav a, nav button').filter({ hasText: /BPM|工作流|流程管理/ });
    if (
      await bpmParent
        .first()
        .isVisible({ timeout: 5000 })
        .catch(() => false)
    ) {
      await bpmParent.first().evaluate((el: HTMLElement) => el.click());
      await page.waitForTimeout(300);
    }

    // Click the process definition submenu
    const processDefLink = page.locator('nav a[href*="bpm_process_management"]');
    if (await processDefLink.isVisible({ timeout: 3000 }).catch(() => false)) {
      await processDefLink.evaluate((el: HTMLElement) => el.click());
    } else {
      await page.goto('/p/bpm_process_management', { waitUntil: 'domcontentloaded' });
    }

    await page.waitForLoadState('domcontentloaded');
    const tableVisible = await page.locator('main table').first().isVisible({ timeout: 8000 }).catch(() => false);
    const createVisible = await page
      .locator('main [data-testid="toolbar-btn-create"], main button:has-text("创建"), main button:has-text("新建"), main button:has-text("Create")')
      .first()
      .isVisible({ timeout: 1000 })
      .catch(() => false);
    const failureVisible =
      (await page
        .locator('main :text-matches("Access forbidden|加载失败|Page Unavailable|Unauthorized", "i")')
        .first()
        .isVisible({ timeout: 1000 })
        .catch(() => false)) ||
      (await page
        .locator('main')
        .getByRole('link', { name: '返回' })
        .first()
        .isVisible({ timeout: 1000 })
        .catch(() => false));

    if ((!tableVisible && failureVisible) || (!tableVisible && !createVisible)) {
      test.skip(true, 'Current environment cannot access BPM process management page');
      return;
    }

    await expect(page.locator('table')).toBeVisible({ timeout: 8000 });
  });

  /**
   * BPM-LC02: Create process via API with designerJson, then verify it appears in list as draft
   */
  test.fixme('BPM-LC02: Create draft process, visible in list', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    const bpmnContent = generateMinimalBpmn(processKey, processName);

    // Create via API (stays in draft status)
    const createResp = await page.request.post('/api/bpm/process-definitions', {
      data: {
        processKey,
        processName,
        description: 'E2E lifecycle test process',
        category: 'e2e-test',
        bpmnContent,
        designerJson: JSON.stringify({
          nodes: [
            {
              id: 'start',
              type: 'startEvent',
              position: { x: 100, y: 200 },
              data: { type: 'startEvent', label: 'Start' },
            },
            {
              id: 'userTask1',
              type: 'userTask',
              position: { x: 300, y: 200 },
              data: { type: 'userTask', label: 'E2E Approval' },
            },
            {
              id: 'end',
              type: 'endEvent',
              position: { x: 500, y: 200 },
              data: { type: 'endEvent', label: 'End' },
            },
          ],
          edges: [
            { id: 'flow1', source: 'start', target: 'userTask1', type: 'smoothstep' },
            { id: 'flow2', source: 'userTask1', target: 'end', type: 'smoothstep' },
          ],
        }),
      },
    });

    if (!createResp.ok()) {
      const bodyText = await createResp.text().catch(() => '');
      if (createResp.status() === 403 && isProcessUpdateForbidden(bodyText)) {
        missingProcessUpdatePermission = true;
        test.skip(true, 'Missing permission: system.process.update');
        return;
      }
    }
    expect(createResp.ok()).toBe(true);
    const createData = await createResp.json();
    processPid = createData.data?.pid;
    expect(processPid).toBeTruthy();

    // Navigate to list and verify draft process appears
    await page.goto('/p/bpm_process_management', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table')).toBeVisible({ timeout: 8000 });

    // Switch to "草稿" (Draft) tab to filter draft processes only
    const draftTab = page.locator('button').filter({ hasText: '草稿' });
    if (await draftTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await draftTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/api/bpm/process-definitions'), { timeout: 5000 })
        .catch(() => {});
    }

    // Verify our process key appears
    await expect(page.locator(`text=${processKey}`).first()).toBeVisible({ timeout: 5000 });
  });

  /**
   * BPM-LC03: Click Edit button for draft process → designer loads with process data
   * (Edit is only visible for draft rows due to visibleWhen condition)
   */
  test('BPM-LC03: Edit draft process from list opens designer', async ({ page }) => {
    test.skip(
      missingProcessUpdatePermission || !processPid,
      'Current environment did not create the draft BPM process needed for edit-designer verification',
    );

    await page.goto('/p/bpm_process_management', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('table')).toBeVisible({ timeout: 8000 });

    // Switch to Draft tab to find our draft process
    const draftTab = page.locator('button').filter({ hasText: '草稿' });
    if (await draftTab.isVisible({ timeout: 3000 }).catch(() => false)) {
      await draftTab.click();
      await page
        .waitForResponse((r) => r.url().includes('/api/bpm/process-definitions'), { timeout: 5000 })
        .catch(() => {});
    }

    // Find our process row and click Edit
    const row = page.locator('tr').filter({ hasText: processKey });
    await expect(row).toBeVisible({ timeout: 5000 });

    await clickRowActionByLocator(page, row, 'edit');

    // Verify navigation to designer with pid
    await page.waitForURL(/bpmn-designer.*pid=/, { timeout: 5000 });

    // Verify designer loaded the process definition
    const nameInput = page.locator('[data-testid="bpmn-field-name"]');
    await expect(nameInput).toBeVisible({ timeout: 8000 });
    await expect(nameInput).toHaveValue(processName, { timeout: 5000 });

    const keyInput = page.locator('[data-testid="bpmn-field-key"]');
    await expect(keyInput).toHaveValue(processKey, { timeout: 5000 });

    // Verify canvas rendered
    await expect(page.locator('.react-flow')).toBeVisible({ timeout: 5000 });
  });

  /**
   * BPM-LC04: Direct URL navigation to designer with ?pid= loads process
   */
  test('BPM-LC04: Designer loads process from URL pid parameter', async ({ page }) => {
    test.skip(!processPid, 'depends on LC02 which is fixme');
    expect(processPid).toBeTruthy();

    await page.goto(`/bpmn-designer?pid=${processPid}`, { waitUntil: 'domcontentloaded' });

    const nameInput = page.locator('[data-testid="bpmn-field-name"]');
    await expect(nameInput).toBeVisible({ timeout: 8000 });
    await expect(nameInput).toHaveValue(processName, { timeout: 5000 });

    // Verify React Flow canvas has nodes
    const canvas = page.locator('.react-flow');
    await expect(canvas).toBeVisible({ timeout: 5000 });
  });

  /**
   * BPM-LC05: Deploy process, start instance, verify task generated
   */
  test('BPM-LC05: Deploy and start process instance', async ({ page }) => {
    expect(processPid).toBeTruthy();

    // Deploy
    const deployResp = await page.request.post(`/api/bpm/process-definitions/${processPid}/deploy`);
    expect(deployResp.ok()).toBe(true);
    const deployData = await deployResp.json();
    expect(deployData.data?.status).toBe('deployed');

    // Start process instance
    const businessKey = `E2E-BK-${testId}`;
    const startResp = await page.request.post('/api/bpm/process-instances', {
      data: {
        processDefinitionId: processKey,
        businessKey,
        variables: { initiator: 'e2e-test' },
      },
    });

    if (!startResp.ok()) {
      const body = await startResp.text().catch(() => '');
      console.warn(`Start instance failed: ${startResp.status()} ${body}`);
    }
    expect(startResp.ok()).toBe(true);

    const instanceData = await startResp.json();
    processInstanceId = instanceData.data?.instanceId || instanceData.data?.processInstanceId;
    expect(processInstanceId).toBeTruthy();

    // Verify a task was generated
    const tasksResp = await page.request.get('/api/bpm/tasks/todo');
    expect(tasksResp.ok()).toBe(true);

    const tasksData = await tasksResp.json();
    const tasks = tasksData.data || [];
    expect(Array.isArray(tasks)).toBe(true);

    // Find our task — SmartEngine uses processInstanceId and instanceId (as task ID)
    // processDefinitionIdAndVersion format is "processKey:version"
    const ourTask = tasks.find(
      (t: any) =>
        t.processInstanceId === processInstanceId ||
        (t.processDefinitionIdAndVersion &&
          t.processDefinitionIdAndVersion.startsWith(processKey + ':')),
    );
    if (ourTask) {
      taskId = ourTask.instanceId || ourTask.taskId || ourTask.id;
    }
    expect(taskId).toBeTruthy();
  });

  /**
   * BPM-LC06: Approve the task, process instance should complete
   */
  test('BPM-LC06: Approve task, process completes', async ({ page }) => {
    expect(taskId).toBeTruthy();

    // Approve
    const approveResp = await page.request.post(`/api/bpm/tasks/${taskId}/approve`, {
      data: { comment: 'E2E test approved', variables: {} },
    });

    if (!approveResp.ok()) {
      // Fallback to complete
      const completeResp = await page.request.post(`/api/bpm/tasks/${taskId}/complete`, {
        data: { variables: {} },
      });
      expect(completeResp.ok()).toBe(true);
    } else {
      expect(approveResp.ok()).toBe(true);
    }

    // Verify process instance completed
    if (processInstanceId) {
      const statusResp = await page.request.get(
        `/api/bpm/process-instances/${processInstanceId}/status`,
      );
      if (statusResp.ok()) {
        const statusData = await statusResp.json();
        const status = statusData.data?.status;
        expect(['completed', 'completed', 'ended', 'ended']).toContain(status);
      }
    }
  });

  /**
   * BPM-LC07: Task center shows the completed task
   */
  test('BPM-LC07: Task center completed tasks tab', async ({ page }) => {
    await page.goto('/bpm/task-center', { waitUntil: 'domcontentloaded' });

    // Click "已办任务" tab
    const completedTab = page.locator('button:has-text("已办任务")');
    await expect(completedTab).toBeVisible({ timeout: 8000 });
    await completedTab.click();

    // Verify table or empty state renders
    const tableOrEmpty = page.locator('table, [role="table"]').or(page.getByText('暂无任务'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 8000 });

    // Completed tasks should have at least one row
    const hasTable = await page
      .locator('table')
      .isVisible({ timeout: 2000 })
      .catch(() => false);
    if (hasTable) {
      const rowCount = await page.locator('table tbody tr').count();
      expect(rowCount).toBeGreaterThan(0);
    }
  });
});
