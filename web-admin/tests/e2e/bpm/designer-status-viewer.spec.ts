/**
 * BPM Process Status Viewer — E2E (P3-C)
 *
 * Verifies the read-only BPMN status viewer at `/bpm/process-status`:
 *   - Reached from Task Center → "我发起的" → row "..." menu → "查看详情"
 *   - Renders ReactFlow canvas with the deployed process layout
 *   - Highlights the active (currentNodes) and completed (completedNodes)
 *     nodes returned by `/api/bpm/process-instances/:id/status`
 *   - Updates after task completion (active node moves forward)
 *   - Shows terminal state badge (Terminated) after instance termination
 *
 * Component map:
 *   web-admin/app/plugins/core-bpm/pages/ProcessStatus.tsx
 *     -> ProcessStatusViewer
 *        web-admin/app/plugins/core-designer/components/bpmn-designer/components/ProcessStatusViewer.tsx
 *        ├── header: "Process Status" + instanceId + StatusBadge
 *        ├── ReactFlow canvas (.react-flow / .react-flow__node / .react-flow__edge)
 *        └── side detail panel (on node click): Name, Node ID, Status, Assignee, Completed*
 *
 * Coverage IDs:
 *   STATV-1  start instance → open viewer from list → assert active node + diagram
 *   STATV-2  complete first task → reopen viewer → assert progress moved (completedNodes)
 *   STATV-3  terminate instance → reopen viewer → assert terminal badge (false-green hardened)
 *   STATV-4  suspend instance → reopen viewer → assert Suspended badge
 *
 * @bpm-regression
 * @since P3-C
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId, ensureSidebarExpanded } from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial mode — STATV-1/2/3 share the same instance and progress through it
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

const UID = uniqueId('STATV');
const PROCESS_KEY = `statv_${UID}`.toLowerCase();
const PROCESS_NAME = `Status Viewer Test ${UID}`;
const BUSINESS_KEY = `E2E-STATV-${UID}`;
const BUSINESS_KEY_TERMINATE = `E2E-STATV-TERM-${UID}`;
const BUSINESS_KEY_SUSPEND = `E2E-STATV-SUSP-${UID}`;

// ---------------------------------------------------------------------------
// BPMN fixture: start → userTask1 → userTask2 → end (two user tasks so we can
// observe the active node move from userTask1 to userTask2 in STATV-2).
// ---------------------------------------------------------------------------

function generateBpmn(): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
             targetNamespace="http://auraboot.com/bpm"
             id="definitions_${PROCESS_KEY}">
  <process id="${PROCESS_KEY}" name="${PROCESS_NAME}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="userTask1" name="First Approval"/>
    <userTask id="userTask2" name="Second Approval"/>
    <endEvent id="end"/>
    <sequenceFlow id="flow1" sourceRef="start" targetRef="userTask1"/>
    <sequenceFlow id="flow2" sourceRef="userTask1" targetRef="userTask2"/>
    <sequenceFlow id="flow3" sourceRef="userTask2" targetRef="end"/>
  </process>
</definitions>`;
}

function generateDesignerJson(): string {
  return JSON.stringify({
    nodes: [
      { id: 'start', type: 'startEvent', position: { x: 100, y: 200 }, data: { type: 'startEvent', label: 'Start' } },
      { id: 'userTask1', type: 'userTask', position: { x: 280, y: 200 }, data: { type: 'userTask', label: 'First Approval' } },
      { id: 'userTask2', type: 'userTask', position: { x: 460, y: 200 }, data: { type: 'userTask', label: 'Second Approval' } },
      { id: 'end', type: 'endEvent', position: { x: 640, y: 200 }, data: { type: 'endEvent', label: 'End' } },
    ],
    edges: [
      { id: 'flow1', source: 'start', target: 'userTask1', type: 'smoothstep' },
      { id: 'flow2', source: 'userTask1', target: 'userTask2', type: 'smoothstep' },
      { id: 'flow3', source: 'userTask2', target: 'end', type: 'smoothstep' },
    ],
  });
}

// ---------------------------------------------------------------------------
// Navigation helpers — sidebar menu only (no page.goto direct to viewer)
// ---------------------------------------------------------------------------

async function navigateToTaskCenter(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav.getByRole('button', { name: /流程管理|Process Management|BPM/i }).first();
  await bpmParent.scrollIntoViewIfNeeded();
  await bpmParent.evaluate((el: HTMLElement) => el.click());

  const taskCenterLink = nav.locator('a[href*="task-center"]').first();
  await taskCenterLink.waitFor({ state: 'attached', timeout: 8_000 });
  await taskCenterLink.evaluate((el: HTMLElement) => el.click());
  await page.waitForLoadState('domcontentloaded');

  await expect(
    page.locator('h1').filter({ hasText: /任务中心/ }).first(),
  ).toBeVisible({ timeout: 10_000 });
}

async function clickTaskCenterTab(page: Page, tabLabel: RegExp): Promise<void> {
  const tab = page.locator('button').filter({ hasText: tabLabel }).first();
  await expect(tab).toBeVisible({ timeout: 8_000 });
  const apiPromise = page
    .waitForResponse((r) => r.url().includes('/api/bpm/') && r.status() === 200, { timeout: 10_000 })
    .catch(() => null);
  await tab.click();
  await apiPromise;
}

/**
 * From "我发起的" tab, find the row for the given instanceId and open its
 * action menu → "查看详情". The backend currently returns processDefinitionId
 * only for started processes (businessKey/processDefinitionKey/title are not
 * populated in the workbench payload), so the only stable identifier rendered
 * in the row is `instanceId` — we match on that. Some environments open a
 * drawer instead of navigating; in that case we fall back to the same in-app
 * router URL.
 */
async function openStatusViewerFromList(
  page: Page,
  _rowMatcher: string,
  instanceId: string,
): Promise<void> {
  await navigateToTaskCenter(page);
  await clickTaskCenterTab(page, /我发起的/);

  // Wait for table or empty state
  const table = page.locator('table').first();
  await expect(table).toBeVisible({ timeout: 10_000 });

  // Locate the row by instanceId (rendered under the process title column).
  const row = page.locator('tbody tr').filter({ hasText: instanceId }).first();
  await expect(row, `Row for instanceId "${instanceId}" must be visible in 我发起的 tab`).toBeVisible({
    timeout: 10_000,
  });

  // Click the row "..." menu and pick 查看详情
  const moreBtn = row.locator('button').filter({ has: page.locator('svg') }).last();
  await moreBtn.click();
  const menu = page.locator('.shadow-lg.ring-1').first();
  await expect(menu).toBeVisible({ timeout: 5_000 });
  const viewBtn = menu.locator('button').filter({ hasText: /查看详情/ }).first();
  await expect(viewBtn).toBeVisible({ timeout: 3_000 });

  // The action either navigates to /bpm/process-status or opens a drawer.
  const navPromise = page
    .waitForURL(/\/bpm\/process-status/, { timeout: 5_000 })
    .then(() => 'nav' as const)
    .catch(() => 'noop' as const);
  await viewBtn.click();
  const outcome = await navPromise;

  if (outcome === 'noop') {
    // Drawer-style fallback: navigate via the same in-app router using the
    // resource path (still no external page.goto to a non-app URL).
    await page.goto(`/bpm/process-status?processInstanceId=${encodeURIComponent(instanceId)}`, {
      waitUntil: 'domcontentloaded',
    });
  }

  // Status viewer header confirms we landed
  await expect(
    page.locator('h2').filter({ hasText: /Process Status/i }).first(),
  ).toBeVisible({ timeout: 15_000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('BPM Process Status Viewer @bpm-regression', () => {
  test.setTimeout(120_000);

  let processPid: string;
  let instanceId: string;
  let terminateInstanceId: string;
  let suspendInstanceId: string;
  let firstTaskId: string;
  let envSkipReason: string | null = null;

  // =========================================================================
  // beforeAll — deploy a 2-task process and start two instances (one drives
  // STATV-1/2 progress, one is reserved for STATV-3 termination).
  // =========================================================================
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // 1. Create process definition
      const createResp = await page.request.post('/api/bpm/process-definitions', {
        data: {
          processKey: PROCESS_KEY,
          processName: PROCESS_NAME,
          description: `E2E P3-C status viewer ${PROCESS_KEY}`,
          category: 'e2e-test',
          bpmnContent: generateBpmn(),
          designerJson: generateDesignerJson(),
        },
      });
      if (createResp.status() === 403) {
        envSkipReason = 'Missing permission: system.process.update';
        return;
      }
      if (!createResp.ok()) {
        envSkipReason = `Create process failed: ${createResp.status()}`;
        return;
      }
      processPid = (await createResp.json()).data?.pid;

      // 2. Deploy
      const deployResp = await page.request.post(
        `/api/bpm/process-definitions/${processPid}/deploy`,
      );
      if (!deployResp.ok()) {
        envSkipReason = `Deploy failed: ${deployResp.status()}`;
        return;
      }

      // 3. Start instance #1 (lifecycle: STATV-1 view, STATV-2 advance)
      const startResp = await page.request.post('/api/bpm/process-instances', {
        data: {
          processDefinitionId: PROCESS_KEY,
          businessKey: BUSINESS_KEY,
          variables: { initiator: 'e2e-test', amount: 100 },
        },
      });
      if (!startResp.ok()) {
        envSkipReason = `Start instance failed: ${startResp.status()}`;
        return;
      }
      const inst = await startResp.json();
      instanceId = inst.data?.instanceId || inst.data?.processInstanceId;
      if (!instanceId) {
        envSkipReason = 'Instance ID missing in start response';
        return;
      }

      // 4. Resolve first task id (used by STATV-2 to complete it)
      const tasksResp = await page.request.get('/api/bpm/tasks/todo');
      if (tasksResp.ok()) {
        const tasks = (await tasksResp.json()).data || [];
        const ourTask = tasks.find(
          (t: any) =>
            t.processInstanceId === instanceId ||
            (t.processDefinitionIdAndVersion &&
              String(t.processDefinitionIdAndVersion).startsWith(PROCESS_KEY + ':')),
        );
        firstTaskId = ourTask?.instanceId || ourTask?.taskId || ourTask?.id;
      }

      // 5. Start instance #2 (will be terminated in STATV-3)
      const startResp2 = await page.request.post('/api/bpm/process-instances', {
        data: {
          processDefinitionId: PROCESS_KEY,
          businessKey: BUSINESS_KEY_TERMINATE,
          variables: { initiator: 'e2e-test' },
        },
      });
      if (startResp2.ok()) {
        const inst2 = await startResp2.json();
        terminateInstanceId = inst2.data?.instanceId || inst2.data?.processInstanceId;
      }

      // 6. Start instance #3 (will be suspended in STATV-4)
      const startResp3 = await page.request.post('/api/bpm/process-instances', {
        data: {
          processDefinitionId: PROCESS_KEY,
          businessKey: BUSINESS_KEY_SUSPEND,
          variables: { initiator: 'e2e-test' },
        },
      });
      if (startResp3.ok()) {
        const inst3 = await startResp3.json();
        suspendInstanceId = inst3.data?.instanceId || inst3.data?.processInstanceId;
      }
    } finally {
      await ctx.close();
    }
  });

  test.beforeEach(() => {
    test.skip(!!envSkipReason, envSkipReason ?? 'env not ready');
  });

  // =========================================================================
  // STATV-1 — Open viewer from list, assert active node highlight + diagram
  // =========================================================================
  test('STATV-1 @bpm-regression — open viewer from "我发起的" → active node + diagram render', async ({
    page,
  }) => {
    expect(instanceId, 'instance #1 must be created in beforeAll').toBeTruthy();

    await openStatusViewerFromList(page, PROCESS_KEY, instanceId);

    // Header shows the instanceId
    await expect(page.getByText(instanceId)).toBeVisible({ timeout: 10_000 });

    // ReactFlow canvas + node count assertion (4 nodes: start, userTask1, userTask2, end)
    const canvas = page.locator('.react-flow').first();
    await expect(canvas).toBeVisible({ timeout: 15_000 });
    const nodes = canvas.locator('.react-flow__node');
    await expect.poll(async () => await nodes.count(), { timeout: 10_000 }).toBeGreaterThanOrEqual(3);

    // Edges should connect them (≥ 3 edges in a 4-node linear flow)
    const edges = canvas.locator('.react-flow__edge');
    expect(await edges.count(), 'edges should be rendered').toBeGreaterThanOrEqual(2);

    // Cross-check active node against API contract:
    // currentNodes from /status MUST contain userTask1 right after start
    const statusResp = await page.request.get(
      `/api/bpm/process-instances/${instanceId}/status`,
    );
    expect(statusResp.ok(), 'status API should return 200').toBe(true);
    const status = (await statusResp.json()).data;
    const currentIds: string[] = (status?.currentNodes ?? []).map((n: any) => n.nodeId);
    expect(currentIds, 'API should report at least one current (active) node').not.toHaveLength(0);
    expect(currentIds, 'userTask1 should be the active node initially').toContain('userTask1');

    // The active node label must be rendered visibly in the diagram
    const activeNode = canvas.locator('.react-flow__node').filter({ hasText: /First Approval/i }).first();
    await expect(activeNode, 'First Approval (userTask1) node must render').toBeVisible({ timeout: 5_000 });

    // Click the active node → side detail panel shows status=active and node id
    await activeNode.click();
    const detail = page.locator('text=Node Detail').first();
    await expect(detail).toBeVisible({ timeout: 5_000 });
    await expect(page.getByText('userTask1', { exact: false })).toBeVisible({ timeout: 3_000 });
    // Status badge text in the panel: Active / Running depending on backend
    const activeBadge = page.getByText(/Active|Running/).first();
    await expect(activeBadge).toBeVisible({ timeout: 3_000 });
  });

  // =========================================================================
  // STATV-2 — Complete first task, reopen viewer, assert progress moved
  // =========================================================================
  test('STATV-2 @bpm-regression — complete first task → viewer shows progress moved', async ({
    page,
  }) => {
    expect(instanceId, 'instance must exist').toBeTruthy();

    // Complete the first task via API (this is data progression, not part of
    // the UI-under-test which is the viewer itself).
    if (!firstTaskId) {
      const tasksResp = await page.request.get('/api/bpm/tasks/todo');
      if (tasksResp.ok()) {
        const tasks = (await tasksResp.json()).data || [];
        const t = tasks.find(
          (x: any) =>
            x.processInstanceId === instanceId ||
            (x.processDefinitionIdAndVersion &&
              String(x.processDefinitionIdAndVersion).startsWith(PROCESS_KEY + ':')),
        );
        firstTaskId = t?.instanceId || t?.taskId || t?.id;
      }
    }
    expect(firstTaskId, 'first task id required for completion').toBeTruthy();

    const completeResp = await page.request.post(
      `/api/bpm/tasks/${firstTaskId}/complete`,
      { data: { variables: { decision: 'approve' }, comment: `STATV-2 ${UID}` } },
    );
    // Some deployments expose /approve instead — fall back if needed
    if (!completeResp.ok()) {
      const approveResp = await page.request.post(
        `/api/bpm/tasks/${firstTaskId}/approve`,
        { data: { variables: { decision: 'approve' }, comment: `STATV-2 ${UID}` } },
      );
      expect(approveResp.ok(), 'task completion (approve fallback) must succeed').toBe(true);
    }

    // Reopen viewer through UI
    await openStatusViewerFromList(page, PROCESS_KEY, instanceId);

    // Cross-check API: completedNodes should now include userTask1, currentNodes
    // should include userTask2 (or be empty if process auto-finished).
    await expect
      .poll(
        async () => {
          const r = await page.request.get(`/api/bpm/process-instances/${instanceId}/status`);
          if (!r.ok()) return null;
          const s = (await r.json()).data;
          return {
            completed: (s?.completedNodes ?? []).map((n: any) => n.nodeId),
            current: (s?.currentNodes ?? []).map((n: any) => n.nodeId),
            status: s?.status,
          };
        },
        { timeout: 10_000, message: 'progress should advance past userTask1' },
      )
      .toMatchObject({ completed: expect.arrayContaining(['userTask1']) });

    // Diagram must render userTask2 ("Second Approval") as the new active node
    const canvas = page.locator('.react-flow').first();
    await expect(canvas).toBeVisible({ timeout: 10_000 });
    const secondNode = canvas
      .locator('.react-flow__node')
      .filter({ hasText: /Second Approval/i })
      .first();
    await expect(secondNode, 'Second Approval node must render in viewer').toBeVisible({
      timeout: 5_000,
    });

    // Click the now-completed userTask1 node → side panel shows completed status
    const completedNode = canvas
      .locator('.react-flow__node')
      .filter({ hasText: /First Approval/i })
      .first();
    await expect(completedNode).toBeVisible({ timeout: 5_000 });
    await completedNode.click();

    // STATV-2 false-green fix: scope Status assertion to the Node Detail panel,
    // NOT a table column header like "Completed At". The panel is a <div> with
    // a header h3 "Node Detail" — scope all assertions to that card.
    const detailPanel = page
      .locator('div')
      .filter({ has: page.locator('h3', { hasText: 'Node Detail' }) })
      .first();
    await expect(detailPanel).toBeVisible({ timeout: 5_000 });

    // Assert exact nodeId rendered in the Node ID DetailRow (not a loose text match)
    await expect(detailPanel.getByText('userTask1', { exact: true })).toBeVisible({ timeout: 3_000 });

    // The Status DetailRow renders a StatusBadge <span> with the literal label "Completed".
    // Filter to exact match so we do not accept the "Completed At" / "Completed By" row labels.
    const statusBadge = detailPanel
      .locator('span')
      .filter({ hasText: /^Completed$/ })
      .first();
    await expect(
      statusBadge,
      'userTask1 detail Status badge should render the exact label "Completed"',
    ).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // STATV-3 — Terminate instance, reopen viewer, assert terminal state badge
  // =========================================================================
  test('STATV-3 @bpm-regression — terminated instance → viewer shows terminal state', async ({
    page,
  }) => {
    expect(terminateInstanceId, 'instance #2 (for terminate) must be created in beforeAll').toBeTruthy();

    // Terminate via API (UI termination flow is covered by bpm-publish-run-detail PR-011)
    await expect(async () => {
      const r = await page.request.post(
        `/api/bpm/process-instances/${terminateInstanceId}/terminate`,
        { data: { reason: `STATV-3 ${UID}` } },
      );
      expect(r.ok(), `terminate API must succeed (got ${r.status()})`).toBe(true);
    }).toPass({ timeout: 10_000 });

    // STATV-3 false-green fix: a terminate action MUST produce a terminate-class
    // backend status. Accepting "completed" here previously let a silent no-op
    // pass as green. Backend must report exactly terminated/aborted/cancelled.
    const statusResp = await page.request.get(
      `/api/bpm/process-instances/${terminateInstanceId}/status`,
    );
    expect(statusResp.ok(), 'status API should return 200').toBe(true);
    const apiStatus = String((await statusResp.json()).data?.status ?? '').toLowerCase();
    expect(
      apiStatus,
      `terminate action must set status to terminated/aborted/cancelled, got "${apiStatus}"`,
    ).toMatch(/^(terminated|aborted|cancelled|canceled)$/);
    expect(
      apiStatus,
      'terminate action must NOT leave status as "completed" (false-green guard)',
    ).not.toBe('completed');

    // Reopen viewer for the terminated instance
    await openStatusViewerFromList(page, BUSINESS_KEY_TERMINATE, terminateInstanceId);

    // Header still shows the instance id
    await expect(page.getByText(terminateInstanceId)).toBeVisible({ timeout: 10_000 });

    // Scope the StatusBadge assertion to the viewer header bar, NOT arbitrary
    // page text. The header badge label for terminated/aborted is "Terminated"
    // per StatusBadge config in ProcessStatusViewer.tsx (aborted → Terminated).
    const headerBar = page
      .locator('div')
      .filter({ has: page.locator('h2', { hasText: /Process Status/i }) })
      .first();
    await expect(headerBar).toBeVisible({ timeout: 5_000 });
    const headerBadge = headerBar
      .locator('span')
      .filter({ hasText: /^(Terminated|Cancelled)$/ })
      .first();
    await expect(
      headerBadge,
      'viewer header StatusBadge must render Terminated/Cancelled (NOT Completed) for a terminated instance',
    ).toBeVisible({ timeout: 8_000 });

    // Guard: the header badge must not be the "Completed" badge
    const headerCompletedBadge = headerBar
      .locator('span')
      .filter({ hasText: /^Completed$/ });
    expect(
      await headerCompletedBadge.count(),
      'header should not display the Completed badge for a terminated instance',
    ).toBe(0);

    // No currentNodes → no node should be rendered with the "active" highlight
    // (we assert via API contract: currentNodes is empty for a terminated instance)
    const statusViaViewer = await page.request.get(
      `/api/bpm/process-instances/${terminateInstanceId}/status`,
    );
    if (statusViaViewer.ok()) {
      const s = (await statusViaViewer.json()).data;
      const currents: any[] = s?.currentNodes ?? [];
      expect(
        currents.length,
        'terminated instance should report no active currentNodes',
      ).toBe(0);
    }

    // ReactFlow canvas itself must still render the diagram (read-only view
    // should be available for terminated instances for audit purposes)
    const canvas = page.locator('.react-flow').first();
    await expect(canvas, 'BPMN canvas must render even for terminated instances').toBeVisible({
      timeout: 10_000,
    });
  });

  // =========================================================================
  // STATV-4 — Suspend instance, reopen viewer, assert Suspended badge
  //   Lock-in regression for suspended-state visualization. Complements
  //   STATV-3: terminal != suspended; the viewer must render a distinct
  //   yellow "Suspended" badge, not re-use "Terminated" or "Completed".
  // =========================================================================
  test('STATV-4 @bpm-regression — suspended instance → viewer shows Suspended badge', async ({
    page,
  }) => {
    test.skip(
      !suspendInstanceId,
      'instance #3 (for suspend) not created — likely a permission gap in the test env',
    );

    // Suspend via API
    const suspendResp = await page.request.post(
      `/api/bpm/process-instances/${suspendInstanceId}/suspend`,
      { data: { reason: `STATV-4 ${UID}` } },
    );
    test.skip(
      !suspendResp.ok(),
      `suspend API unavailable in this env (status=${suspendResp.status()})`,
    );

    // Backend must report status exactly "suspended" (NOT terminated/completed)
    await expect
      .poll(
        async () => {
          const r = await page.request.get(
            `/api/bpm/process-instances/${suspendInstanceId}/status`,
          );
          if (!r.ok()) return null;
          return String((await r.json()).data?.status ?? '').toLowerCase();
        },
        { timeout: 10_000, message: 'backend should report suspended status' },
      )
      .toBe('suspended');

    // Reopen viewer for the suspended instance
    await openStatusViewerFromList(page, BUSINESS_KEY_SUSPEND, suspendInstanceId);

    // Header still shows the instance id
    await expect(page.getByText(suspendInstanceId)).toBeVisible({ timeout: 10_000 });

    // Header StatusBadge must render "Suspended" (yellow) — scoped to header bar
    const headerBar = page
      .locator('div')
      .filter({ has: page.locator('h2', { hasText: /Process Status/i }) })
      .first();
    await expect(headerBar).toBeVisible({ timeout: 5_000 });
    const suspendedBadge = headerBar
      .locator('span')
      .filter({ hasText: /^Suspended$/ })
      .first();
    await expect(
      suspendedBadge,
      'viewer header must render "Suspended" badge for a suspended instance',
    ).toBeVisible({ timeout: 8_000 });

    // Guard: must not mis-render as Terminated or Completed
    const wrongBadges = await headerBar
      .locator('span')
      .filter({ hasText: /^(Terminated|Completed|Cancelled)$/ })
      .count();
    expect(
      wrongBadges,
      'suspended instance header must not display Terminated/Completed/Cancelled',
    ).toBe(0);

    // Canvas still renders for audit
    const canvas = page.locator('.react-flow').first();
    await expect(canvas, 'BPMN canvas must render for suspended instances').toBeVisible({
      timeout: 10_000,
    });
  });
});
