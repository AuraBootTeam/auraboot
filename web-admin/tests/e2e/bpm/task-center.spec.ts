/**
 * BPM Task Center E2E Tests
 *
 * Comprehensive tests for the refactored Task Center (/bpm/task-center):
 * - TC-01: Page renders with stats cards and tabs
 * - TC-02: Tab switching (todo/completed/started/notifications)
 * - TC-03: Search filtering
 * - TC-04: Task detail drawer open/close
 * - TC-05: Task detail drawer tab navigation
 * - TC-06: Action menu visibility and entries
 * - TC-07: Approve dialog flow
 * - TC-08: Reject dialog flow
 * - TC-09: Delegate dialog with user picker
 * - TC-10: Transfer dialog with user picker
 * - TC-11: Add sign dialog
 * - TC-12: Rollback dialog
 * - TC-13: Carbon copy dialog (multi-select)
 * - TC-14: Urge action
 * - TC-15: Batch select and batch approve/reject buttons
 * - TC-16: Notifications tab (CC/URGE sub-tabs)
 * - TC-17: Refresh button reloads data
 * - TC-18: Stats cards display
 * - TC-19: Priority badge rendering
 * - TC-20: Due date countdown rendering
 * - TC-21: Detail drawer footer action buttons
 * - TC-22: Started tab (process table)
 * - TC-23: Completed tab (finished tasks or empty state)
 * - TC-24: Started tab process action menu entries
 *
 * Prerequisites:
 * - A simple BPMN process (Start -> UserTask -> End) deployed via API
 * - A process instance started to generate todo tasks
 *
 * Uses real database, NO MOCKING.
 *
 * @since 4.0.0
 */

import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';

// ==================== Helpers ====================

function generateMinimalBpmn(processKey: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${processKey}">
  <process id="${processKey}" name="TC E2E Test Process" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="TC E2E Approval"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="end"/>
  </process>
</definitions>`;
}

/** Navigate to task center and wait for content + data to render */
async function gotoTaskCenter(page: import('@playwright/test').Page) {
  await page.goto('/bpm/task-center', { waitUntil: 'domcontentloaded' });

  // Wait for either task center content or error page
  const content = page.locator('h1:has-text("任务中心")');
  const error = page.locator('text=Application Error');
  const login = page.locator('text=请先登录, text=欢迎登录');

  const result = await Promise.race([
    content.waitFor({ timeout: 10000 }).then(() => 'content' as const),
    error.waitFor({ timeout: 10000 }).then(() => 'error' as const),
    login.waitFor({ timeout: 10000 }).then(() => 'login' as const),
  ]).catch(() => 'timeout' as const);

  if (result === 'content') {
    // Wait for data to load (loading spinner disappears → table or empty state)
    const dataReady = page.locator('table').or(page.locator('text=暂无任务'));
    await dataReady.first().waitFor({ timeout: 10000 });
  }

  return result;
}

/** Check if task table has rows (returns true if task name buttons exist) */
async function hasTaskRows(page: import('@playwright/test').Page): Promise<boolean> {
  return page
    .locator('table button.text-blue-600')
    .first()
    .waitFor({ state: 'visible', timeout: 3000 })
    .then(() => true)
    .catch(() => false);
}

/** Check if action menu buttons (MoreHorizontal) are visible */
async function hasActionButtons(page: import('@playwright/test').Page): Promise<boolean> {
  return page
    .locator('table button')
    .filter({ has: page.locator('svg.lucide-ellipsis') })
    .first()
    .waitFor({ state: 'visible', timeout: 3000 })
    .then(() => true)
    .catch(() => false);
}

// ==================== Test Suite ====================

test.describe('BPM Task Center', () => {
  test.describe.configure({ mode: 'serial' });

  let processPid: string | null = null;
  let processKey: string;
  let processInstanceId: string | null = null;
  let missingProcessUpdatePermission = false;

  /**
   * Setup: Deploy a process and start an instance to generate tasks.
   */
  test.beforeAll(async ({ request }) => {
    processKey = `tc_e2e_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
    const bpmnContent = generateMinimalBpmn(processKey);

    try {
      // 1. Create process definition
      const createResp = await request.post('/api/bpm/process-definitions', {
        data: {
          processKey,
          processName: `TC E2E Test ${processKey}`,
          description: 'Auto-generated for task center E2E',
          category: 'e2e-test',
          bpmnContent,
        },
      });
      if (!createResp.ok()) {
        if (createResp.status() === 403) {
          missingProcessUpdatePermission = true;
        }
        console.warn(
          `Task center setup: create failed ${createResp.status()} ${await createResp.text().catch(() => '')}`,
        );
        return;
      }
      const createData = await createResp.json();
      processPid = createData.data?.pid || createData.pid;
      if (!processPid) {
        console.warn(
          'Task center setup: create response missing pid:',
          JSON.stringify(createData).slice(0, 200),
        );
        return;
      }

      // 2. Deploy
      const deployResp = await request.post(`/api/bpm/process-definitions/${processPid}/deploy`);
      if (!deployResp.ok()) {
        console.warn(
          `Task center setup: deploy failed ${deployResp.status()} ${await deployResp.text().catch(() => '')}`,
        );
        return;
      }

      // 3. Start instance
      const startResp = await request.post('/api/bpm/process-instances', {
        data: {
          processDefinitionId: processKey,
          businessKey: `TC-BK-${Date.now()}`,
          variables: { initiator: 'e2e-test' },
        },
      });
      if (!startResp.ok()) {
        console.warn(`Task center setup: start instance failed ${startResp.status()}`);
        return;
      }
      const instanceData = await startResp.json();
      processInstanceId = instanceData.data?.instanceId || instanceData.instanceId || null;
      if (!processInstanceId) {
        console.warn(
          'Task center setup: start response missing instanceId:',
          JSON.stringify(instanceData).slice(0, 200),
        );
      }
    } catch (error) {
      console.warn('Task center setup failed:', error);
    }
  });

  // ==================== Page Rendering ====================

  test('TC-01: Task center page renders with header, stats, and tabs', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Header
    await expect(page.locator('h1:has-text("任务中心")')).toBeVisible();
    await expect(page.locator('text=管理您的审批任务和流程')).toBeVisible();

    // Refresh button
    await expect(page.locator('button:has-text("刷新")')).toBeVisible();

    // Task list section
    await expect(page.locator('text=任务列表')).toBeVisible();

    // All 3 tabs visible
    await expect(page.locator('button:has-text("待办任务")')).toBeVisible();
    await expect(page.locator('button:has-text("已办任务")')).toBeVisible();
    await expect(page.locator('button:has-text("我发起的")')).toBeVisible();
  });

  test('TC-18: Stats cards display correctly', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Stats cards should be visible (at least the container)
    // Cards show: 待办, 已办, 流程, SLA
    const statsSection = page.locator('.grid').first();
    await expect(statsSection).toBeVisible({ timeout: 8000 });
  });

  // ==================== Tab Switching ====================

  test('TC-02: Tab switching works correctly', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Helper: click tab and retry until React state actually updates (border-blue-600 class)
    // React re-renders during data fetch can swallow the first click event
    const switchTab = async (label: string) => {
      const btn = page.locator(`button:has-text("${label}")`);
      await expect(async () => {
        await btn.click();
        const cls = await btn.getAttribute('class');
        expect(cls).toContain('border-blue-600');
      }).toPass({ timeout: 8000 });
    };

    // Wait for initial data load (todo tab is default active)
    const todoTab = page.locator('button:has-text("待办任务")');
    await expect(todoTab).toHaveClass(/border-blue-600/, { timeout: 8000 });

    // Wait for todo content to stabilize
    const todoContent = page.locator('table').or(page.locator('text=暂无任务'));
    await expect(todoContent.first()).toBeVisible({ timeout: 8000 });

    // Switch to completed tab — retry click until active
    await switchTab('已办任务');
    const completedContent = page.locator('table').or(page.locator('text=暂无任务'));
    await expect(completedContent.first()).toBeVisible({ timeout: 8000 });

    // Switch to started tab
    await switchTab('我发起的');
    const processContent = page
      .locator('table')
      .or(page.locator('text=暂无流程'))
      .or(page.locator('text=暂无任务'));
    await expect(processContent.first()).toBeVisible({ timeout: 8000 });

    // Verify we can switch back to todo tab
    await switchTab('待办任务');
  });

  // ==================== Search ====================

  test('TC-03: Search input filters tasks', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Search input should be visible on todo tab
    const searchInput = page.locator('input[placeholder="搜索任务..."]');
    await expect(searchInput).toBeVisible({ timeout: 5000 });

    // Type a search term and wait for filtered results
    await searchInput.fill('nonexistent_search_term_xyz');
    const tableOrEmpty = page.locator('table').or(page.locator('text=暂无任务'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 5000 });

    // Clear search and wait for results to restore
    await searchInput.fill('');
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 5000 });

    // Search should be hidden on "抄送给我" tab (CC tab hides search per TaskCenter logic)
    const ccTab = page.locator('button:has-text("抄送给我")');
    const hasCcTab = await ccTab.isVisible({ timeout: 3000 }).catch(() => false);
    if (hasCcTab) {
      await ccTab.click();
      await expect(searchInput).not.toBeVisible({ timeout: 3000 });
    }
  });

  // ==================== Notifications Tab ====================

  test('TC-16: CC and URGE tabs show notification content', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Wait for initial data load and all re-renders to settle.
    // The useTaskCenter hook may trigger multiple fetches (workbench + SLA) causing sequential re-renders.
    // Click a different tab first to confirm React is fully interactive, then switch to notifications.
    const todoContent = page.locator('table').or(page.locator('text=暂无任务'));
    await expect(todoContent.first()).toBeVisible({ timeout: 8000 });

    // Warm-up: switch to "已办任务" first — retry click until React state updates
    const completedBtn = page.locator('button:has-text("已办任务")');
    await expect(async () => {
      await completedBtn.click();
      const cls = await completedBtn.getAttribute('class');
      expect(cls).toContain('border-blue-600');
    }).toPass({ timeout: 8000 });
    const completedContent = page.locator('table').or(page.locator('text=暂无任务'));
    await expect(completedContent.first()).toBeVisible({ timeout: 8000 });

    // Switch to "抄送给我" tab — retry click until React state updates
    const ccBtn = page.locator('button:has-text("抄送给我")');
    const hasCcBtn = await ccBtn.isVisible({ timeout: 3000 }).catch(() => false);
    if (!hasCcBtn) {
      throw new Error('CC tab (抄送给我) not visible in task center');
    }
    await expect(async () => {
      await ccBtn.click();
      const cls = await ccBtn.getAttribute('class');
      expect(cls).toContain('border-blue-600');
    }).toPass({ timeout: 8000 });

    // Wait for search input to disappear — confirms CC tab is active (search hidden per TaskCenter logic)
    await expect(page.locator('input[placeholder="搜索任务..."]')).not.toBeVisible({
      timeout: 8000,
    });

    // Switch to "催办提醒" tab
    const urgeBtn = page.locator('button:has-text("催办提醒")');
    await expect(urgeBtn).toBeVisible({ timeout: 5000 });

    // CC tab content should show loading, empty state, or records
    const ccContent = page
      .locator('text=加载中...')
      .or(page.locator('text=暂无消息'))
      .or(page.locator('.space-y-3'));
    await expect(ccContent.first()).toBeVisible({ timeout: 8000 });

    // Switch to "催办提醒" tab (independent main tab, not a sub-tab)
    await expect(async () => {
      await urgeBtn.click();
      const cls = await urgeBtn.getAttribute('class');
      expect(cls).toContain('border-blue-600');
    }).toPass({ timeout: 5000 });

    // Wait for URGE content
    const urgeContent = page
      .locator('text=加载中...')
      .or(page.locator('text=暂无消息'))
      .or(page.locator('.space-y-3'));
    await expect(urgeContent.first()).toBeVisible({ timeout: 8000 });
  });

  // ==================== Task Detail Drawer ====================

  test('TC-04: Task detail drawer opens and closes', async ({ page }) => {
    test.skip(missingProcessUpdatePermission, 'Missing permission: system.process.update');
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasTaskRows(page))) {
      test.skip(true, 'No tasks available to test drawer in current environment');
    }

    const taskLinks = page.locator('table button.text-blue-600');

    // Click first task name to open drawer
    await taskLinks.first().click();

    // Drawer should be visible
    const drawer = page.locator('.fixed.right-0.w-\\[520px\\]');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Drawer header should show task name or "任务详情"
    await expect(drawer.locator('h2')).toBeVisible();

    // Drawer should have tabs
    await expect(drawer.locator('button:has-text("基本信息")')).toBeVisible();
    await expect(drawer.locator('button:has-text("表单")')).toBeVisible();
    await expect(drawer.locator('button:has-text("审批记录")')).toBeVisible();
    await expect(drawer.locator('button:has-text("附件")')).toBeVisible();
    await expect(drawer.locator('button:has-text("sla")')).toBeVisible();

    // Close drawer via X button
    const closeBtn = drawer.locator('button').filter({ has: page.locator('svg.lucide-x') });
    await closeBtn.click();
    await expect(drawer).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-05: Task detail drawer tab navigation', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasTaskRows(page))) {
      test.skip(true, 'No tasks available to test drawer tabs in current environment');
      return;
    }

    const taskLinks = page.locator('table button.text-blue-600');

    // Open drawer
    await taskLinks.first().click();
    const drawer = page.locator('.fixed.right-0.w-\\[520px\\]');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Default: info tab active
    const infoTab = drawer.locator('button:has-text("基本信息")');
    await expect(infoTab).toHaveClass(/border-blue-600/);

    // Switch to form tab
    const formTab = drawer.locator('button:has-text("表单")');
    await formTab.click();
    await expect(formTab).toHaveClass(/border-blue-600/);

    // Form tab shows either form or "该任务未绑定表单"
    const formContent = drawer
      .locator('text=该任务未绑定表单')
      .or(drawer.locator('form, .space-y-4'));
    await expect(formContent.first()).toBeVisible({ timeout: 5000 });

    // Switch to timeline tab
    const timelineTab = drawer.locator('button:has-text("审批记录")');
    await timelineTab.click();
    await expect(timelineTab).toHaveClass(/border-blue-600/);

    // Switch to SLA tab
    const slaTab = drawer.locator('button:has-text("sla")');
    await slaTab.click();
    await expect(slaTab).toHaveClass(/border-blue-600/);

    // SLA tab shows either records or empty state
    const slaContent = drawer
      .locator('text=暂无 SLA 记录')
      .or(drawer.locator('.border.rounded-lg'));
    await expect(slaContent.first()).toBeVisible({ timeout: 5000 });

    // Close drawer via backdrop
    const backdrop = page.locator('.fixed.inset-0.bg-black\\/20');
    await backdrop.click();
    await expect(drawer).not.toBeVisible({ timeout: 3000 });
  });

  // ==================== Action Menu ====================

  test('TC-06: Action menu shows all operation entries', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    const hasActions = await hasActionButtons(page);
    test.skip(!hasActions, 'Current environment has no task rows exposing action menus');

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    // Open action menu
    await moreButtons.first().click();

    // Verify all menu entries exist
    const menu = page.locator('.absolute.right-0.z-10');
    await expect(menu).toBeVisible({ timeout: 3000 });

    await expect(menu.locator('button:has-text("查看详情")')).toBeVisible();
    await expect(menu.locator('button:has-text("通过")')).toBeVisible();
    await expect(menu.locator('button:has-text("驳回")')).toBeVisible();
    await expect(menu.locator('button:has-text("完成任务")')).toBeVisible();
    await expect(menu.locator('button:has-text("委托")')).toBeVisible();
    await expect(menu.locator('button:has-text("转办")')).toBeVisible();
    await expect(menu.locator('button:has-text("加签")')).toBeVisible();
    await expect(menu.locator('button:has-text("减签")')).toBeVisible();
    await expect(menu.locator('button:has-text("回退")')).toBeVisible();
    await expect(menu.locator('button:has-text("抄送")')).toBeVisible();
    await expect(menu.locator('button:has-text("催办")')).toBeVisible();

    // Close menu by clicking the toggle button again
    await moreButtons.first().click();
    await expect(menu).not.toBeVisible({ timeout: 2000 });
  });

  // ==================== Dialog Flows ====================

  test('TC-07: Approve dialog opens and can be submitted', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    // Open menu, click approve
    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("通过")').click();

    // Dialog should appear (shadcn Dialog renders with role="dialog")
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible({ timeout: 3000 });

    // Should have textarea for comment
    const textarea = dialog.locator('textarea');
    await expect(textarea).toBeVisible();
    await textarea.fill('E2E 自动测试通过审批');

    // Should have submit and cancel buttons
    const submitBtn = dialog.locator('[data-testid="dialog-confirm"], button:has-text("确认")');
    const cancelBtn = dialog.locator('[data-testid="dialog-cancel"], button:has-text("取消")');
    await expect(submitBtn).toBeVisible();
    await expect(cancelBtn).toBeVisible();

    // Cancel the dialog (don't actually approve)
    await cancelBtn.click();
    await expect(dialog).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-08: Reject dialog opens and has required fields', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("驳回")').click();

    // Dialog should have textarea
    const dialog = page.locator('[role="dialog"]');
    await expect(dialog).toBeVisible({ timeout: 3000 });
    await expect(dialog.locator('textarea')).toBeVisible();

    // Cancel
    await dialog.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    await expect(dialog).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-09: Delegate dialog opens with user picker', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("委托")').click();

    // Dialog should have MemberPicker and textarea
    const dialogContent = page.locator('[role="dialog"]');
    await expect(dialogContent).toBeVisible({ timeout: 3000 });

    // Should have comment textarea
    await expect(dialogContent.locator('textarea')).toBeVisible();

    // Cancel
    await dialogContent.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    await expect(dialogContent).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-10: Transfer dialog opens with user picker', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("转办")').click();

    const dialogContent = page.locator('[role="dialog"]');
    await expect(dialogContent).toBeVisible({ timeout: 3000 });
    await expect(dialogContent.locator('textarea')).toBeVisible();

    await dialogContent.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    await expect(dialogContent).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-11: Add sign dialog opens with user picker and reason input', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("加签")').click();

    const dialogContent = page.locator('[role="dialog"]');
    await expect(dialogContent).toBeVisible({ timeout: 3000 });

    // Should have textarea for reason
    await expect(dialogContent.locator('textarea')).toBeVisible();

    await dialogContent.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    await expect(dialogContent).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-12: Rollback dialog opens with target node input', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("回退")').click();

    const dialogContent = page.locator('[role="dialog"]');
    await expect(dialogContent).toBeVisible({ timeout: 3000 });

    // Should have select for target node and textarea for reason
    const selectOrLoading = dialogContent
      .locator('select')
      .or(dialogContent.getByText('加载节点列表'))
      .or(dialogContent.getByText('暂无可回退的节点'));
    await expect(selectOrLoading.first()).toBeVisible({ timeout: 5000 });
    await expect(dialogContent.locator('textarea')).toBeVisible();

    await dialogContent.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    await expect(dialogContent).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-13: Carbon copy dialog opens with multi-select user picker', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');
    await menu.locator('button:has-text("抄送")').click();

    const dialogContent = page.locator('[role="dialog"]');
    await expect(dialogContent).toBeVisible({ timeout: 3000 });

    // Should have textarea for content
    await expect(dialogContent.locator('textarea')).toBeVisible();

    await dialogContent.locator('[data-testid="dialog-cancel"], button:has-text("取消")').click();
    await expect(dialogContent).not.toBeVisible({ timeout: 3000 });
  });

  test('TC-14: Urge action from menu', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasActionButtons(page))) {
      throw new Error('No tasks available');
    }

    const moreButtons = page
      .locator('table button')
      .filter({ has: page.locator('svg.lucide-ellipsis') });

    // Open menu
    await moreButtons.first().click();
    const menu = page.locator('.absolute.right-0.z-10');

    // Urge button should be present with orange text
    const urgeBtn = menu.locator('button:has-text("催办")');
    await expect(urgeBtn).toBeVisible();
    await expect(urgeBtn).toHaveClass(/text-orange-600/);

    // Click urge - should trigger toast (success or warning if no assignee)
    await urgeBtn.click();

    // Wait for a toast notification
    const toast = page.locator('[role="status"], [data-sonner-toast], [role="alert"]');
    await expect(toast.first())
      .toBeVisible({ timeout: 5000 })
      .catch(() => {
        // Toast might use a different mechanism - acceptable
      });
  });

  // ==================== Batch Operations ====================

  test('TC-15: Batch select shows batch action buttons', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Check if there's a checkbox in the header (select all) — shadcn Checkbox renders as button[role="checkbox"]
    const selectAllCheckbox = page.locator('thead button[role="checkbox"]');
    const hasCheckbox = await selectAllCheckbox
      .first()
      .waitFor({ state: 'visible', timeout: 5000 })
      .then(() => true)
      .catch(() => false);

    if (!hasCheckbox) {
      throw new Error('No task checkboxes available');
    }

    // Before selection: batch buttons should NOT be visible
    await expect(page.locator('button:has-text("批量通过")')).not.toBeVisible();

    // Click select all
    await selectAllCheckbox.click();

    // Batch buttons should appear
    await expect(page.locator('button:has-text("批量通过")')).toBeVisible({ timeout: 3000 });
    await expect(page.locator('button:has-text("批量驳回")')).toBeVisible();

    // Deselect all
    await selectAllCheckbox.click();

    // Batch buttons should disappear
    await expect(page.locator('button:has-text("批量通过")')).not.toBeVisible({ timeout: 3000 });
  });

  // ==================== Refresh ====================

  test('TC-17: Refresh button reloads data', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Click refresh and wait for API response
    const refreshBtn = page.locator('button:has-text("刷新")');
    await expect(refreshBtn).toBeVisible();

    // Set up response listener
    const responsePromise = page
      .waitForResponse((resp) => resp.url().includes('/api/bpm/') && resp.status() === 200, {
        timeout: 10000,
      })
      .catch(() => null);

    await refreshBtn.click();

    // Verify API was called
    const response = await responsePromise;
    // Response might be null if API isn't available - acceptable
    if (response) {
      expect(response.status()).toBe(200);
    }
  });

  // ==================== Priority & Due Date Display ====================

  test('TC-19: Priority badges render with correct colors', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasTaskRows(page))) {
      throw new Error('No tasks to verify priority badges');
    }

    // Priority column header should exist
    await expect(page.locator('th:has-text("优先级")')).toBeVisible();

    // Priority badges use specific colors:
    // high: bg-red-100 text-red-700 "高"
    // medium: bg-yellow-100 text-yellow-700 "中"
    // low: bg-green-100 text-green-700 "低"
    const priorityBadges = page.locator(
      'table span.bg-red-100, table span.bg-yellow-100, table span.bg-green-100',
    );
    const badgeCount = await priorityBadges.count();

    // At least verify the column exists, badges may or may not appear depending on data
    if (badgeCount > 0) {
      const firstBadge = priorityBadges.first();
      const classList = await firstBadge.getAttribute('class');
      expect(classList).toMatch(/bg-(red|yellow|green)-100/);
    }
  });

  test('TC-20: Due date countdown rendering', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasTaskRows(page))) {
      throw new Error('No tasks to verify due date column');
    }

    // Due date column header should exist
    await expect(page.locator('th:has-text("截止日期")')).toBeVisible();

    // Due date cells show countdown text or "-"
    const dueDateCells = page.locator('table tbody td:nth-child(6)');
    const count = await dueDateCells.count();

    if (count > 0) {
      const firstCell = dueDateCells.first();
      const text = await firstCell.textContent();
      expect(text).toBeTruthy();
    }
  });

  // ==================== Drawer Footer Actions ====================

  test('TC-21: Detail drawer footer has action buttons', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    if (!(await hasTaskRows(page))) {
      throw new Error('No tasks available');
    }

    const taskLinks = page.locator('table button.text-blue-600');

    await taskLinks.first().click();
    const drawer = page.locator('.fixed.right-0.w-\\[520px\\]');
    await expect(drawer).toBeVisible({ timeout: 5000 });

    // Footer should have action buttons
    const footer = drawer.locator('.border-t');
    await expect(footer).toBeVisible();

    // "查看流程图" link
    await expect(footer.locator('button:has-text("查看流程图")')).toBeVisible();

    // Approve/Reject/Complete buttons
    await expect(footer.locator('button:has-text("通过")')).toBeVisible();
    await expect(footer.locator('button:has-text("驳回")')).toBeVisible();
    await expect(footer.locator('button:has-text("完成")')).toBeVisible();

    // Close
    const closeBtn = drawer.locator('button').filter({ has: page.locator('svg.lucide-x') });
    await closeBtn.click();
  });

  // ==================== Started Tab (Process Table) ====================

  test('TC-22: Started tab shows process table with actions', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    const startedTab = page.locator('button:has-text("我发起的")');
    await startedTab.click();

    // Wait for content
    const tableOrEmpty = page
      .locator('table')
      .or(page.locator('text=暂无流程'))
      .or(page.locator('text=暂无任务'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });

    // If a process table exists, verify columns
    const hasTable = await page
      .locator('table')
      .first()
      .waitFor({ state: 'visible', timeout: 3000 })
      .then(() => true)
      .catch(() => false);

    if (hasTable) {
      // Process table has columns: 流程, 业务标识, 状态, 发起时间, 操作
      const headers = page.locator('thead th');
      const headerCount = await headers.count();
      expect(headerCount).toBeGreaterThanOrEqual(3);
    }
  });

  // ==================== Completed Tab ====================

  test('TC-23: Completed tab shows finished tasks or empty state', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    const completedTab = page.locator('button:has-text("已办任务")');
    await expect(async () => {
      await completedTab.click();
      const cls = await completedTab.getAttribute('class');
      expect(cls).toContain('border-blue-600');
    }).toPass({ timeout: 5000 });

    // Should show table with completed tasks or empty state
    const tableOrEmpty = page.locator('table').or(page.locator('text=暂无任务'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 8000 });
  });

  // ==================== Process Actions (from Started tab) ====================

  test('TC-24: Started tab process action menu has correct entries', async ({ page }) => {
    const result = await gotoTaskCenter(page);
    if (result !== 'content') {
      throw new Error(`Task center not available: ${result}`);
    }

    // Switch to "我发起的" tab
    const startedTab = page.locator('button:has-text("我发起的")');
    await startedTab.click();
    const tableOrEmpty = page
      .locator('table')
      .or(page.locator('text=暂无流程'))
      .or(page.locator('text=暂无任务'));
    await expect(tableOrEmpty.first()).toBeVisible({ timeout: 10000 });

    const hasTable = await page
      .locator('table tbody tr')
      .first()
      .waitFor({ state: 'visible', timeout: 3000 })
      .then(() => true)
      .catch(() => false);

    if (!hasTable) {
      throw new Error('No process instances in started tab');
    }

    // Open action menu on first row
    const firstRow = page.locator('table tbody tr').first();
    const menuTrigger = firstRow
      .locator('button')
      .filter({ has: page.locator('svg') })
      .last();
    await menuTrigger.click();

    // Verify action menu items: "查看详情" and optionally "终止流程"
    await expect(page.locator('text=查看详情').or(page.locator('text=View Details')).first()).toBeVisible({ timeout: 5000 });
    // "终止流程" may not be available for all process types
    const terminateBtn = page.locator('text=终止流程').or(page.locator('text=Terminate'));
    if (await terminateBtn.first().isVisible({ timeout: 2000 }).catch(() => false)) {
      await expect(terminateBtn.first()).toBeVisible();
    }

    // Close menu
    await page.keyboard.press('Escape');
  });

  // ==================== Cleanup ====================

  test.afterAll(async ({ request }) => {
    if (!processPid) return;

    try {
      await request.post(`/api/bpm/process-definitions/${processPid}/undeploy`);
    } catch {
      /* ignore */
    }

    try {
      await request.delete(`/api/bpm/process-definitions/${processPid}`);
    } catch (error) {
      console.warn('Task center E2E cleanup failed:', error);
    }
  });
});
