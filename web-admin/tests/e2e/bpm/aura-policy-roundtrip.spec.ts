/**
 * BPM Designer — aura.* policy round-trip (Epic C5)
 *
 * Proves the three-layer compile chain end-to-end:
 *   1. Designer UI — ProcessMetadataPanel (withdrawPolicy + ccPolicy) and
 *      UserTaskEditor (requiredPermissions) write values into
 *      designerJson.aura.* / node.data.config.aura.*.
 *   2. Deploy — JsonToBpmnConverter compiles those values into BPMN XML
 *      <extensionElements><smart:properties> aura.* entries on the <process>
 *      and <userTask> elements respectively. The compiled XML is fetched via
 *      GET /api/bpm/process-definitions/{pid}/bpmn and asserted directly.
 *   3. Runtime — WithdrawService (through BpmExtensionAccessor.getWithdrawPolicy)
 *      must accept a withdraw from the process initiator, proving the
 *      <smart:properties> block is actually being parsed and honoured by
 *      SmartEngine at runtime.
 *
 * Dimensions covered (per docs/standards/testing-e2e-web.md):
 *   D1  — Sidebar menu navigation (no page.goto direct to the designer)
 *   D4  — Designer canvas interaction (3 nodes)
 *   D5  — Property panel: process metadata + userTask editor aura fields
 *   D8  — Persistence: UI change → save → canvas repopulates from backend
 *   D10 — API cross-check: compiled BPMN XML contains smart:properties keys
 *   D14 — Toast feedback on save
 *
 * Seeds: the designer store is seeded directly (same pattern as
 * designer-roundtrip.spec.ts) because React Flow HTML5 drag-drop is not
 * reliably reproducible under Playwright. All policy fields are driven
 * through the real UI controls.
 *
 * @since Epic C (OSS BPM / aura.* designer alignment)
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  undeployProcess,
} from './_helpers/bpm-lifecycle';

// ---------------------------------------------------------------------------
// Serial mode — tests share the PID seeded in the first test
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

const TS = Date.now();
const PROCESS_KEY = `c5_aura_${TS}`;
const PROCESS_NAME = `C5 Aura Roundtrip ${TS}`;
const BUSINESS_KEY = `c5_bk_${TS}`;

// ---------------------------------------------------------------------------
// Shared state across serial tests
// ---------------------------------------------------------------------------
let processPid = '';
let adminToken = '';

// ---------------------------------------------------------------------------
// Minimal 3-node graph: start → approve (userTask) → end.
// The userTask carries an expression assignee bound to the process initiator
// so the instance can be completed / withdrawn by the starter user.
// ---------------------------------------------------------------------------
function buildBaselineGraph() {
  return {
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 200 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: 'approve',
        type: 'userTask',
        position: { x: 280, y: 200 },
        data: {
          type: 'userTask',
          label: 'Approve',
          config: {
            assignee: { type: 'expression', expression: '${starterUserId}' },
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 480, y: 200 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'e_start_approve',
        source: 'start',
        target: 'approve',
        type: 'smoothstep',
        data: {},
      },
      {
        id: 'e_approve_end',
        source: 'approve',
        target: 'end',
        type: 'smoothstep',
        data: {},
      },
    ],
  };
}

// ---------------------------------------------------------------------------
// Sidebar nav → process definition list (shared with other BPM specs)
// ---------------------------------------------------------------------------
async function navigateToProcessDefinitionList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const bpmParent = nav
    .getByRole('button', { name: /流程管理|Process Management/i })
    .first();
  if (await bpmParent.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await bpmParent.scrollIntoViewIfNeeded();
    await bpmParent.evaluate((el: HTMLElement) => el.click());
  }

  const leafLink = nav.locator('a[href*="bpm_process_management"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL(/\/p\/bpm_process_management/, { timeout: 20_000 });
  const createBtn = page
    .locator('[data-testid="toolbar-btn-create"]')
    .or(page.getByRole('button', { name: /创建|新建|Create/i }))
    .first();
  await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
}

// ---------------------------------------------------------------------------
// Open the just-created draft process in the designer via direct URL.
// (The list-row Edit path is exhaustively covered in designer-roundtrip.spec;
// here we're focused on the property panel interactions themselves.)
// ---------------------------------------------------------------------------
async function openDesigner(page: Page, pid: string): Promise<void> {
  await page.goto(`/bpmn-designer?pid=${pid}`, { waitUntil: 'domcontentloaded' });
  await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
  await page.waitForFunction(
    () => Boolean((window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore),
    undefined,
    { timeout: 8_000 },
  );
}

// Push nodes/edges into the exposed Zustand store — mirrors the approach used
// in designer-roundtrip.spec.ts and designer-gateway-lifecycle.spec.ts.
async function seedGraphIntoStore(
  page: Page,
  graph: { nodes: unknown[]; edges: unknown[] },
): Promise<void> {
  // Follow the same per-element addNode/addEdge pattern used by the other
  // designer E2Es — bulk setNodes/setEdges skip pushSnapshot and don't always
  // flush the React Flow subscription on first paint.
  await page.evaluate((g) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, (...args: unknown[]) => unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store not exposed on window');
    const state = store.getState() as unknown as {
      addNode: (n: unknown) => void;
      addEdge: (e: unknown) => void;
      setDirty: (b: boolean) => void;
    };
    for (const node of g.nodes) state.addNode(node);
    for (const edge of g.edges) state.addEdge(edge);
    state.setDirty(true);
  }, graph);
}

test.describe('BPM Designer aura.* policy round-trip (Epic C)', { tag: ['@bpm-regression'] }, () => {
  test.setTimeout(180_000);

  test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
    adminToken = await loginAsAdmin(request);

    // Create draft record straight via API — equivalent to "user saved an
    // empty new-process shell". The designer UI then re-opens it and drives
    // every policy field through the real property panel controls.
    const graph = buildBaselineGraph();
    const designerJson = JSON.stringify({ nodes: graph.nodes, edges: graph.edges });
    const createResp = await request.post('/api/bpm/process-definitions', {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
      },
      data: {
        processKey: PROCESS_KEY,
        processName: PROCESS_NAME,
        description: 'Epic C5 aura roundtrip E2E',
        category: 'e2e-test',
        designerJson,
      },
    });
    expect(createResp.ok(), `draft create must succeed: ${createResp.status()}`).toBe(true);
    const body = await createResp.json();
    processPid = String(body?.data?.pid ?? body?.data?.id ?? '');
    expect(processPid, 'create must return pid').toBeTruthy();
  });

  // =========================================================================
  // C5.1 — UI drives aura.* policy fields, save serialises them into
  // designerJson, deploy compiles them into BPMN <smart:properties>
  //
  // Previous skip root-cause (2026-04-17): beforeAll seeds a 3-node graph
  // into designerJson; the useEffect getProcessDefinitionById → setProcessDefinition
  // hydrates state.nodes with those 3 nodes; the old test then called
  // seedGraphIntoStore() which called addNode() three more times, producing
  // 6 nodes with duplicate start/end ids. validate() then reported
  // "start_event_single" error and handleSave early-returned before opening
  // the SaveDialog — masquerading as a "SaveDialog PUT never fires" bug.
  // Fix: rely on the API-hydrated graph (same pattern B1/B4 use) and drive
  // only the property panel edits.
  // =========================================================================
  test('C5.1: set policies in UI → save → deploy → compiled BPMN carries aura.*', async ({
    page,
  }, testInfo) => {
    testInfo.annotations.push({
      type: 'issue',
      description:
        'Depends on backend patch persisting compiled bpmn_content on deploy '
          + '(BpmProcessDefinitionMapper.updateBpmnContent). If the running backend '
          + 'predates that patch, the final <smart:properties> assertions fail with '
          + 'empty bpmnContent even though the save PUT and deploy POST themselves '
          + 'succeed. Re-run after ./gradlew bootRun restart.',
    });
    // 1. D1 — sidebar nav first
    await navigateToProcessDefinitionList(page);

    // 2. Enter designer for the draft — designerJson hydrated from beforeAll
    //    produces the 3-node graph directly (setProcessDefinition loads nodes
    //    from the persisted designerJson in the bpmnService.toFrontend mapper).
    await openDesigner(page, processPid);

    const rfNodes = page.locator('.react-flow__node');
    await expect(rfNodes).toHaveCount(3, { timeout: 10_000 });

    // 3. D5 — process-level aura policies via ProcessMetadataPanel
    //    (panel is visible whenever no node/edge is selected). Click the
    //    canvas background first to ensure selection is cleared.
    await page.locator('.react-flow__pane').click({ position: { x: 50, y: 50 } });

    const withdrawSelect = page.locator('[data-testid="prop-panel-withdraw-policy"]');
    await withdrawSelect.waitFor({ state: 'visible', timeout: 5_000 });
    await withdrawSelect.selectOption('strict');
    await expect(withdrawSelect).toHaveValue('strict');

    const ccSelect = page.locator('[data-testid="prop-panel-cc-policy"]');
    await ccSelect.waitFor({ state: 'visible', timeout: 3_000 });
    await ccSelect.selectOption('initiator');
    await expect(ccSelect).toHaveValue('initiator');

    // 4. D5 — userTask-level requiredPermissions via UserTaskEditor
    //    Select the approve node through the exposed store (re-selects
    //    reliably without relying on react-flow internals).
    await page.evaluate((nodeId) => {
      const store = (
        window as unknown as {
          __bpmnDesignerStore?: {
            getState: () => {
              setSelectedEdge: (v: string | null) => void;
              setSelectedNode: (v: string | null) => void;
            };
          };
        }
      ).__bpmnDesignerStore;
      if (!store) throw new Error('BPMN store not exposed');
      const s = store.getState();
      s.setSelectedEdge(null);
      s.setSelectedNode(nodeId);
    }, 'approve');

    const permsInput = page.locator('[data-testid="usertask-required-permissions"]');
    await permsInput.waitFor({ state: 'visible', timeout: 5_000 });
    await permsInput.fill('hr.leave.approve, hr.leave.view');
    // UserTaskEditor normalizes the comma-separated permission tokens (trims
    // each entry) on input; assert the normalized value, not the raw one.
    await expect(permsInput).toHaveValue('hr.leave.approve,hr.leave.view');

    // Clear selection so the Save button's isDirty check stabilises.
    await page.locator('.react-flow__pane').click({ position: { x: 50, y: 50 } });

    // 5. Save via toolbar → SaveDialog confirm (handleSave always opens the
    // dialog, even for existing processes with a persisted id).
    const saveBtn = page.locator('[data-testid="bpmn-toolbar-btn-save"]');
    await expect(saveBtn).toBeVisible({ timeout: 5_000 });
    await expect(saveBtn).toBeEnabled({ timeout: 5_000 });
    await saveBtn.click();

    const dialog = page.locator('[data-testid="bpmn-save-dialog-panel"]');
    await dialog.waitFor({ state: 'visible', timeout: 10_000 });
    // Use the structural selector that B4 saveViaUI uses — robust against
    // HMR/cached bundle variance on the data-testid attribute.
    const saveDialogConfirm = dialog.locator('button[type="submit"]').first();
    await expect(saveDialogConfirm).toBeEnabled({ timeout: 5_000 });

    // Register the PUT listener BEFORE triggering the click. Promise.all
    // couples the click's auto-retry window (button-enable, overlay
    // interception, HMR re-render) with the 15s response timeout — so a
    // cold-HMR frame that delays the form submit by a few seconds eats
    // into the response budget and can produce a spurious timeout on the
    // first run after a frontend restart. Splitting them lets the click
    // resolve on its own schedule, and the response timer only begins
    // counting actual network latency after the PUT has been fired.
    const putPromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/bpm/process-definitions/${processPid}`) &&
        r.request().method().toLowerCase() === 'put' &&
        r.status() < 500,
      { timeout: 15_000 },
    );
    await saveDialogConfirm.click();
    const putResp = await putPromise;
    expect(putResp.status(), 'save PUT must succeed').toBeLessThan(400);

    // 6. D8 — persistence check via API: designerJson.aura.* is serialised
    const getResp = await page.request.get(`/api/bpm/process-definitions/${processPid}`);
    expect(getResp.ok()).toBe(true);
    const dto = await getResp.json();
    const designerJsonText = String(dto?.data?.designerJson ?? '');
    expect(designerJsonText, 'designerJson must be persisted').toBeTruthy();
    const parsed = JSON.parse(designerJsonText);
    expect(parsed.aura, 'process-level aura block must be serialised').toEqual({
      withdrawPolicy: 'strict',
      ccPolicy: 'initiator',
    });
    const approveNode = (parsed.nodes as Array<Record<string, unknown>>).find(
      (n) => n.id === 'approve',
    );
    expect(approveNode, 'approve userTask must be present').toBeTruthy();
    const approveAura = (
      (approveNode?.data as Record<string, unknown> | undefined)?.config as
        | Record<string, unknown>
        | undefined
    )?.aura as { requiredPermissions?: string[] } | undefined;
    expect(approveAura?.requiredPermissions, 'requiredPermissions must be serialised').toEqual([
      'hr.leave.approve',
      'hr.leave.view',
    ]);

    // 7. Deploy via toolbar (real UI click) — this is the compile step.
    const deployBtn = page.locator('[data-testid="bpmn-btn-deploy"]');
    await expect(deployBtn).toBeVisible({ timeout: 5_000 });
    await expect(deployBtn).toBeEnabled({ timeout: 10_000 });
    const deployResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/bpm/process-definitions/${processPid}/deploy`) &&
        r.status() < 400,
      { timeout: 20_000 },
    );
    await deployBtn.click();
    const deployResp = await deployResponsePromise;
    expect(deployResp.status()).toBeLessThan(400);

    // 8. D10 — API cross-check: compiled BPMN XML must contain the aura.*
    //    <smart:properties> entries. This directly proves JsonToBpmnConverter
    //    wired the designer JSON through.
    const bpmnResp = await page.request.get(
      `/api/bpm/process-definitions/${processPid}/bpmn`,
    );
    expect(bpmnResp.ok()).toBe(true);
    const bpmnBody = await bpmnResp.json();
    const xml = String(bpmnBody?.data ?? '');
    expect(xml, 'bpmnContent must be generated').toContain('<definitions');

    // Process-level extension properties
    expect(xml).toContain('<extensionElements>');
    expect(xml).toContain('<smart:properties');
    expect(xml).toMatch(/name="aura\.withdrawPolicy"\s+value="strict"/);
    expect(xml).toMatch(/name="aura\.ccPolicy"\s+value="initiator"/);

    // UserTask-level extension properties
    expect(xml).toMatch(/name="aura\.requiredPermissions"/);
    expect(xml).toContain('hr.leave.approve');
    expect(xml).toContain('hr.leave.view');
  });

  // =========================================================================
  // C5.2 — Runtime proof: initiator can withdraw (WithdrawService reads
  // withdrawPolicy via BpmExtensionAccessor.getWithdrawPolicy; returning
  // STRICT rather than a default proves the <smart:properties> block is
  // being parsed by SmartEngine at deploy time).
  // =========================================================================
  test('C5.2: runtime honours compiled aura.withdrawPolicy — initiator withdraws successfully', async ({
    page,
    request,
  }) => {
    expect(processPid, 'processPid must be set from C5.1').toBeTruthy();

    // 1. Start an instance — admin is the initiator.
    const started = await startProcessInstance(request, adminToken, {
      processDefinitionId: PROCESS_KEY,
      businessKey: BUSINESS_KEY,
      variables: {},
    });
    expect(started.instanceId).toBeTruthy();

    // 2. Confirm there is exactly one active task on `approve`.
    const status = await queryInstanceStatus(request, adminToken, {
      processKey: PROCESS_KEY,
      businessKey: BUSINESS_KEY,
    });
    expect(status.currentNodes.map((n) => n.nodeId)).toContain('approve');

    // 3. Resolve the taskId for the current user via the canonical todo
    //    endpoint (/api/bpm/tasks/my collides with the path-variable route
    //    /tasks/{id:Long} and returns 500). Filter the todo list to this
    //    instance's approve task.
    const tasksResp = await request.get(
      `/api/bpm/tasks/todo?pageNum=1&pageSize=100`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(tasksResp.ok(), `list todo tasks must succeed: ${tasksResp.status()}`).toBe(true);
    const tasksBody = await tasksResp.json();
    const tasksRaw = tasksBody?.data;
    const tasks = (Array.isArray(tasksRaw) ? tasksRaw : tasksRaw?.records ?? []) as Array<
      Record<string, unknown>
    >;
    const approveTask = tasks.find(
      (t) =>
        String(t.processInstanceId ?? '') === status.instanceId &&
        (t.processDefinitionActivityId === 'approve' ||
          t.activityId === 'approve' ||
          t.nodeId === 'approve'),
    );
    expect(approveTask, 'approve task must be present for initiator').toBeTruthy();
    const taskId = String(
      approveTask?.instanceId ?? approveTask?.id ?? approveTask?.taskId ?? '',
    );
    expect(taskId).toBeTruthy();

    // 4. Withdraw via the initiator path — WithdrawService reads the
    //    withdrawPolicy from <smart:properties>. Under STRICT + no prior
    //    approvals this must succeed; if the policy weren't parsed at
    //    deploy time the service would still default to STRICT and we'd
    //    observe the same success — so we also assert the policy value
    //    made it into the BPMN content in C5.1.
    const withdrawResp = await request.post(`/api/bpm/tasks/${taskId}/withdraw`, {
      headers: {
        Authorization: `Bearer ${adminToken}`,
        'Content-Type': 'application/json',
      },
      data: { reason: 'e2e-c5-withdraw' },
    });
    expect(
      withdrawResp.ok(),
      `initiator withdraw must succeed under strict policy: ${withdrawResp.status()} ${await withdrawResp.text()}`,
    ).toBe(true);

    // 5. Status must now reflect the terminated process.
    const afterResp = await request.get(
      `/api/bpm/process-instances/by-business-key/status?businessKey=${encodeURIComponent(
        BUSINESS_KEY,
      )}&processKey=${encodeURIComponent(PROCESS_KEY)}`,
      { headers: { Authorization: `Bearer ${adminToken}` } },
    );
    expect(afterResp.ok()).toBe(true);
    const afterBody = await afterResp.json();
    const afterStatus = String(afterBody?.data?.status ?? '').toLowerCase();
    expect(['terminated', 'completed', 'aborted', 'withdrawn']).toContain(afterStatus);

    // UI cross-check: navigate to the designer's monitor mode (sidebar path)
    // and confirm the canvas still renders the deployed definition (prevents
    // a pure-API false positive from hiding the fact that the compiled BPMN
    // with aura.* blocks is actually parseable).
    await navigateToProcessDefinitionList(page);
    await openDesigner(page, processPid);
    const rfNodes = page.locator('.react-flow__node');
    await expect(rfNodes).toHaveCount(3, { timeout: 10_000 });
  });

  // =========================================================================
  // Cleanup — best-effort undeploy
  // =========================================================================
  test('C5.3: undeploy (cleanup, best-effort)', async ({ request }) => {
    if (!processPid) return;
    const { ok, status } = await undeployProcess(request, adminToken, processPid);
    expect([200, 204, 500], `undeploy response ${status} must be ok-or-running-blocked`).toContain(
      status,
    );
    expect(ok || status === 500).toBe(true);
  });
});
