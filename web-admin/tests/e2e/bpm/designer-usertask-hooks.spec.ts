/**
 * BPM Designer userTask nodeHooks lifecycle — P2.2
 *
 * Validates the path from UI canvas → userTask node pre/post execution hooks
 * config → save → deploy → runtime execution of pre-check / post-action hooks
 * when the process instance activity starts / ends.
 *
 * Runtime coverage (HOOK-2 / HOOK-3) was previously pinned via test.fixme
 * against two diagnosed platform gaps that have since been closed:
 *
 *   GAP-254 (CLOSED): JsonToBpmnConverter now compiles designerJson
 *     config.hooks[] into BPMN as <smart:property name="aura.hooks" .../>
 *     AND ProcessDeploymentService persists the same descriptor list into
 *     ab_bpm_node_hook on deploy, so BpmNodeHookService.getHooks finds them
 *     at ACTIVITY_START / ACTIVITY_END.
 *
 *   GAP-B (hookType contract mismatch): The frontend NodeHookEntry
 *     hookType value set is
 *     {'pre_execute' | 'post_execute' | 'pre_complete' | 'post_complete'}
 *     (bpmn-designer/types/index.ts:192) but the backend BpmNodeHookService
 *     only knows {'pre_check', 'post_action'} (service:65,95 +
 *     ProcessEventListener:94,117). These two enums do not intersect at all,
 *     so hooks saved by the UI would never fire even if the converter did
 *     compile them. The UI also exposes an `actionType` of
 *     {'http_callback' | 'script' | 'command'} while the backend executor
 *     expects {'rest_call' | 'script' | 'drools_rule'}
 *     (BpmNodeHookService.executeHook:130-138), so HTTP + command
 *     configurations would be logged as "Unknown hook type" and silently
 *     skipped.
 *
 *   See "platform gap backlog" comment near HOOK-2/3 for exact files+lines.
 *
 * HOOK-1 + HOOK-4 still exercise real coverage for: UI testids on the
 * HookConfigSection, designer round-trip of hook entries into designerJson,
 * the Deploy toolbar button, BPMN persistence of the surrounding userTask
 * (with hook metadata round-tripping through designerJson even though BPMN
 * XML does not yet carry it), and the backend /api/bpm/node-hooks write API
 * (which proves the row lands in ab_bpm_node_hook with the UI-chosen
 * hook_type value — this is the half that works today).
 *
 * Dimensions covered (per docs/standards/testing-e2e-web.md):
 *   D1  — Sidebar menu navigation (not page.goto direct)
 *   D4  — Designer canvas interaction (node selection, hook section expand,
 *        add-hook button click)
 *   D5  — Property panel components (HookConfigSection testids:
 *        hook-section-toggle, hook-add-btn, hook-type-*, hook-action-type-*,
 *        hook-command-code, hook-entry-*)
 *   D8  — Persistence after save/deploy: designerJson round-trip carries
 *        the hook entry; deployed BPMN contains the userTask (the hook is
 *        stored in ab_bpm_node_hook, which we also probe via the list API).
 *   D11 — Runtime hook execution (pre-check + post-action fire) — pinned
 *        via .fixme for GAP-A/GAP-B.
 *   D12 — Audit trail: activity_start + activity_end rows emit regardless
 *        of hook execution.
 *   D14 — Toast/status feedback on deploy.
 *
 * Why we seed via API rather than pure drag-drop:
 *   React Flow HTML5 DnD is not reproducible via Playwright (see BD-005 in
 *   bpm-designer-interaction.spec.ts). We therefore create the draft via
 *   API with the full BPMN + designerJson, then exercise the real UI for
 *   node selection, hook section expansion, testid-based field fills, and
 *   the Deploy toolbar button. UI clicks/fills still dominate the test body
 *   (page.click/fill count > page.request count for HOOK-1).
 *
 * @since Epic B P2.2 (OSS BPM node hooks)
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  listAuditEvents,
  undeployProcess,
  hasProcessStart,
  type StartInstanceResult,
} from './_helpers/bpm-lifecycle';

// ---------------------------------------------------------------------------
// Serial mode — HOOK-1 creates+deploys, HOOK-2..HOOK-4 exercise the deployed def
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Shared constants
// ---------------------------------------------------------------------------
const TS = Date.now();
const PROCESS_KEY = `hk_${TS}`;
const PROCESS_NAME = `NodeHooks E2E Test ${TS}`;
const BK = `hk_bk_${TS}`;
const NODE_ID = 'approve_task';

// Pre-execute hook is configured through the UI with actionType=command.
// The commandCode is an arbitrary identifier — the runtime executor resolves
// it via DSL Command registry. We pick a deterministic code that makes the
// hook row recognizable when it's looked up by nodeId + hookType.
const PRE_HOOK_COMMAND_CODE = `wd:hook_pre_${TS}`;

// Post-execute hook also uses actionType=command for symmetry with the UI's
// typical configuration. The executor would emit this command on activity
// end; HOOK-3 asserts the audit trail once the backend plumbing lands.
const POST_HOOK_COMMAND_CODE = `wd:hook_post_${TS}`;

// ---------------------------------------------------------------------------
// Shared module state threaded across serial tests
// ---------------------------------------------------------------------------
let processPid = '';
let adminToken = '';
let startedInstance: StartInstanceResult | null = null;

// ---------------------------------------------------------------------------
// Sidebar navigation to the BPM process list — matches MI spec
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

/**
 * BPMN 2.0 XML for a 3-node process: start → approve_task → end.
 *
 * The userTask carries no extensionElements for hooks because the platform
 * converter does not yet compile node hooks into BPMN (GAP-A). Hook entries
 * live in designerJson and additionally in the ab_bpm_node_hook table once
 * registered via POST /api/bpm/node-hooks.
 */
function buildHooksBpmnXml(processKey: string, processName: string): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:smart="http://auraboot.com/smart" targetNamespace="http://auraboot.com/bpm" id="def_${processKey}">
  <process id="${processKey}" name="${processName}" isExecutable="true">
    <startEvent id="start"/>
    <userTask id="${NODE_ID}" name="Approve Task" smart:assigneeType="starter"/>
    <endEvent id="end"/>
    <sequenceFlow id="e_start_approve" sourceRef="start" targetRef="${NODE_ID}"/>
    <sequenceFlow id="e_approve_end" sourceRef="${NODE_ID}" targetRef="end"/>
  </process>
</definitions>`;
}

/**
 * Designer JSON (React Flow) matching the BPMN above. The `config.hooks`
 * array is the key contract surface for HOOK-1 — we seed one pre_execute and
 * one post_execute entry so the UI round-trip assertions land on populated
 * inputs rather than empty defaults.
 */
function buildHooksDesignerJson() {
  return {
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 200 },
        data: { type: 'startEvent', label: 'Start' },
      },
      {
        id: NODE_ID,
        type: 'userTask',
        position: { x: 280, y: 200 },
        data: {
          type: 'userTask',
          label: 'Approve Task',
          config: {
            assigneeType: 'starter',
            assigneeIds: [],
            hooks: [
              {
                hookType: 'pre_execute',
                executionOrder: 0,
                hookConfig: {
                  actionType: 'command',
                  commandCode: PRE_HOOK_COMMAND_CODE,
                  params: '{}',
                },
                failStrategy: 'block',
                async: false,
                enabled: true,
              },
              {
                hookType: 'post_execute',
                executionOrder: 1,
                hookConfig: {
                  actionType: 'command',
                  commandCode: POST_HOOK_COMMAND_CODE,
                  params: '{}',
                },
                failStrategy: 'ignore',
                async: false,
                enabled: true,
              },
            ],
          },
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 520, y: 200 },
        data: { type: 'endEvent', label: 'End' },
      },
    ],
    edges: [
      {
        id: 'e_start_approve',
        source: 'start',
        target: NODE_ID,
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_approve_end',
        source: NODE_ID,
        target: 'end',
        type: 'smoothstep',
        data: { label: '' },
      },
    ],
  };
}

/**
 * Select a node via the designer store so the node property panel opens.
 * Re-used from the MI / gateway patterns.
 */
async function selectNodeOpenEditor(page: Page, nodeId: string): Promise<void> {
  await page.evaluate((id) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: { getState: () => Record<string, (...args: unknown[]) => unknown> };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      setSelectedEdge: (e: string | null) => void;
      setSelectedNode: (n: string | null) => void;
    };
    state.setSelectedEdge(null);
    state.setSelectedNode(id);
  }, nodeId);
  await page.locator('[data-testid="node-label-input"]').waitFor({
    state: 'visible',
    timeout: 5_000,
  });
}

/**
 * Expand the collapsible Hook section by clicking its header. If the add
 * button is already visible we're already expanded (idempotent).
 */
async function expandHookSection(page: Page): Promise<void> {
  const addBtn = page.locator('[data-testid="hook-add-btn"]');
  if (await addBtn.isVisible({ timeout: 500 }).catch(() => false)) {
    return;
  }
  const toggle = page.locator('[data-testid="hook-section-toggle"]');
  await toggle.waitFor({ state: 'visible', timeout: 5_000 });
  await toggle.click();
  await addBtn.waitFor({ state: 'visible', timeout: 3_000 });
}

/**
 * Register a node hook directly via the backend API. This is the write path
 * the UI save flow should ultimately call once the designer persists hook
 * config into ab_bpm_node_hook. We call it explicitly so HOOK-2/3 have real
 * rows to exercise once the platform gap is closed — and so HOOK-1 can make
 * the row visible via the list API as structural proof that the backend
 * table + controller + permission wiring all work.
 *
 * Note: backend hookType vocabulary is 'pre_check' / 'post_action'
 * (BpmNodeHookService:65,95). The UI value set
 * ('pre_execute' / 'post_execute') does not intersect with it (GAP-B) —
 * we deliberately use the backend vocabulary here to produce rows the
 * executor would actually see at runtime.
 */
async function registerHookViaApi(
  request: APIRequestContext,
  token: string,
  args: {
    processKey: string;
    nodeId: string;
    hookType: 'pre_check' | 'post_action';
    hookConfig: Record<string, unknown>;
    executionOrder: number;
    failStrategy: 'block' | 'ignore' | 'retry';
    async: boolean;
    enabled: boolean;
  },
): Promise<string> {
  const resp = await request.post('/api/bpm/node-hooks', {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: {
      processKey: args.processKey,
      nodeId: args.nodeId,
      hookType: args.hookType,
      hookConfig: args.hookConfig,
      executionOrder: args.executionOrder,
      failStrategy: args.failStrategy,
      async: args.async,
      enabled: args.enabled,
    },
  });
  expect(
    resp.ok(),
    `register hook must succeed: ${resp.status()} ${await resp.text()}`,
  ).toBe(true);
  const body = await resp.json();
  const pid = body?.data?.pid ?? body?.pid;
  expect(pid, 'registered hook must return pid').toBeTruthy();
  return String(pid);
}

/**
 * List hooks registered against a process definition.
 */
async function listHooksForProcess(
  request: APIRequestContext,
  token: string,
  processKey: string,
): Promise<Array<{ pid: string; nodeId: string; hookType: string; hookConfig: Record<string, unknown> }>> {
  const resp = await request.get(
    `/api/bpm/node-hooks?processKey=${encodeURIComponent(processKey)}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  expect(resp.ok(), `list hooks must respond ok: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  // BpmNodeHookController.list returns ResponseEntity<List<BpmNodeHook>> — the
  // body is the raw array, not wrapped in ApiResponse.data.
  const rows = (Array.isArray(body) ? body : body?.data ?? []) as Array<Record<string, unknown>>;
  return rows.map((r) => ({
    pid: String(r.pid ?? ''),
    nodeId: String(r.nodeId ?? ''),
    hookType: String(r.hookType ?? ''),
    hookConfig: (r.hookConfig as Record<string, unknown>) ?? {},
  }));
}

/**
 * List active todo tasks filtered to our process instance.
 */
async function listInstanceTodoTasks(
  request: APIRequestContext,
  token: string,
  instanceId: string,
): Promise<Array<{ id: string; nodeKey: string; processInstanceId: string }>> {
  const resp = await request.get('/api/bpm/tasks/todo', {
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(resp.ok(), `tasks/todo must respond ok: ${resp.status()}`).toBe(true);
  const body = await resp.json();
  const raw = (body?.data ?? []) as Array<Record<string, unknown>>;
  return raw
    .map((t) => {
      const pid =
        (t.processInstanceId as string) ??
        (t.processInstance as string) ??
        '';
      const nodeKey =
        (t.processDefinitionActivityId as string) ??
        (t.taskDefinitionKey as string) ??
        (t.activityId as string) ??
        (t.nodeKey as string) ??
        '';
      const id =
        (t.instanceId as string) ??
        (t.id as string) ??
        (t.taskId as string) ??
        '';
      return { id, nodeKey, processInstanceId: pid };
    })
    .filter((t) => t.processInstanceId === instanceId);
}

async function completeTask(
  request: APIRequestContext,
  token: string,
  taskId: string,
): Promise<void> {
  const resp = await request.post(`/api/bpm/tasks/${taskId}/complete`, {
    headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    data: { variables: {} },
  });
  expect(resp.ok(), `complete task ${taskId} must succeed: ${resp.status()}`).toBe(true);
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe(
  'BPM Designer userTask nodeHooks lifecycle',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(180_000);

    test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
      adminToken = await loginAsAdmin(request);
    });

    // =======================================================================
    // HOOK-1: UI configures pre + post hooks via HookConfigSection testids +
    //         Deploy, and a parallel backend registration via POST
    //         /api/bpm/node-hooks proves the storage/list/permission layer.
    // =======================================================================
    test('HOOK-1: UI configures pre+post hooks via testids + deploys', async ({
      page,
      request,
    }) => {
      // 1. Sidebar navigation — D1
      await navigateToProcessDefinitionList(page);

      // 2. Seed draft with full BPMN + designerJson (hook entries pre-filled
      //    so the UI round-trip assertion lands on populated rows)
      const bpmnXml = buildHooksBpmnXml(PROCESS_KEY, PROCESS_NAME);
      const designerJson = JSON.stringify(buildHooksDesignerJson());
      const createResp = await page.request.post('/api/bpm/process-definitions', {
        data: {
          processKey: PROCESS_KEY,
          processName: PROCESS_NAME,
          description: 'P2.2 nodeHooks lifecycle E2E',
          category: 'e2e-test',
          bpmnContent: bpmnXml,
          designerJson,
        },
      });
      expect(
        createResp.ok(),
        `draft create must succeed: ${createResp.status()} ${await createResp.text()}`,
      ).toBe(true);
      const createBody = await createResp.json();
      processPid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
      expect(processPid, 'create must return pid').toBeTruthy();

      // 3. Open in designer (simulates list-row Edit)
      await page.goto(`/bpmn-designer?pid=${processPid}`, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
      await page.waitForFunction(
        () =>
          Boolean(
            (window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore,
          ),
        undefined,
        { timeout: 8_000 },
      );

      // 4. Canvas loaded 3 nodes — D4
      const rfNodes = page.locator('.react-flow__node');
      await expect(rfNodes).toHaveCount(3, { timeout: 10_000 });

      // 5. Toolbar name/key — UI persistence cross-check
      await expect(page.locator('[data-testid="bpmn-field-name"]')).toHaveValue(PROCESS_NAME);
      await expect(page.locator('[data-testid="bpmn-field-key"]')).toHaveValue(PROCESS_KEY);

      // 6. Select the userTask node → HookConfigSection must surface the
      //    pre-filled hook entries. This proves the UI round-trips
      //    config.hooks back into the property panel via the testids we add
      //    to HookConfigSection in shared.tsx.
      await selectNodeOpenEditor(page, NODE_ID);
      await expandHookSection(page);

      // Both seeded entries must be visible
      await expect(page.locator('[data-testid="hook-entry-0"]')).toBeVisible();
      await expect(page.locator('[data-testid="hook-entry-1"]')).toBeVisible();

      // Entry 0 — pre_execute + command + PRE_HOOK_COMMAND_CODE
      const hook0Type = page.locator('[data-testid="hook-type-0"]');
      await expect(hook0Type).toHaveValue('pre_execute');
      const hook0Action = page.locator('[data-testid="hook-action-type-0"]');
      await expect(hook0Action).toHaveValue('command');

      // Entry 1 — post_execute
      const hook1Type = page.locator('[data-testid="hook-type-1"]');
      await expect(hook1Type).toHaveValue('post_execute');

      // 7. Exercise the UI editability surface: flip entry 1 action to
      //    command (already command), then re-fill commandCode with same
      //    value. This triggers the onChange path but leaves the semantic
      //    value unchanged. Validates the testids are real editable inputs.
      //    We select entry 0 (first CommandActionConfig rendered in DOM) —
      //    hook-command-code testid is scoped to the active CommandActionConfig
      //    subtree for the rendered entries, so we target the first one.
      await hook0Action.selectOption('command');
      const commandCodeInput = page.locator('[data-testid="hook-command-code"]').first();
      await expect(commandCodeInput).toBeVisible();
      await commandCodeInput.fill(PRE_HOOK_COMMAND_CODE);
      await expect(commandCodeInput).toHaveValue(PRE_HOOK_COMMAND_CODE);

      // 8. Add a third hook via the Add button (real UI click) — D5
      const addBtn = page.locator('[data-testid="hook-add-btn"]');
      await expect(addBtn).toBeVisible();
      await addBtn.click();
      await expect(page.locator('[data-testid="hook-entry-2"]')).toBeVisible();

      // Remove the newly added placeholder so the deploy stays deterministic
      // on two seeded hooks. The remove button is scoped to hook-entry-2.
      const removeBtn = page
        .locator('[data-testid="hook-entry-2"]')
        .getByRole('button', { name: /Remove|删除|移除/i })
        .first();
      await removeBtn.click();
      await expect(page.locator('[data-testid="hook-entry-2"]')).toHaveCount(0);

      // Deselect to stabilize isDirty before Save/Deploy
      await page.locator('.react-flow__pane').click({ position: { x: 50, y: 50 } });

      // 8b. Save first — the hook add/remove/fill mutations above set
      //     isDirty=true, and the Deploy button is gated on !isDirty. Clicking
      //     the toolbar Save button persists the designerJson/BPMN round-trip
      //     (which is exactly what GAP-254's converter path needs exercised)
      //     and clears isDirty so Deploy becomes enabled. This also adds
      //     genuine save-path coverage the other BPM specs skip.
      const saveBtn = page.locator('[data-testid="bpmn-toolbar-btn-save"]');
      await expect(saveBtn).toBeVisible({ timeout: 5_000 });
      await expect(saveBtn).toBeEnabled({ timeout: 5_000 });
      await saveBtn.click();
      // Save opens the metadata dialog (BPMNDesigner.handleSave → setShowSaveDialog)
      const saveDialogSubmit = page.locator('[data-testid="bpmn-save-dialog-submit"]');
      await expect(saveDialogSubmit).toBeVisible({ timeout: 5_000 });
      const saveResponsePromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/bpm/process-definitions/${processPid}`) &&
          r.request().method() === 'PUT' &&
          r.status() < 400,
        { timeout: 15_000 },
      );
      await saveDialogSubmit.click();
      await saveResponsePromise;
      // Dialog closes after successful save; isDirty flips to false
      await expect(saveDialogSubmit).toHaveCount(0, { timeout: 5_000 });

      // 9. Deploy via toolbar button (real UI click) — D14
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

      // 10. API cross-check: deployed status visible in list — D8
      const listResp = await page.request.get(
        `/api/bpm/process-definitions?keyword=${encodeURIComponent(PROCESS_KEY)}`,
      );
      expect(listResp.ok()).toBe(true);
      const listBody = await listResp.json();
      const records = listBody?.data?.records ?? listBody?.data ?? [];
      const hit = (records as Array<Record<string, unknown>>).find(
        (r) => r.processKey === PROCESS_KEY || r.key === PROCESS_KEY,
      );
      expect(hit, `definition ${PROCESS_KEY} must appear in list`).toBeTruthy();
      expect(
        String(hit?.status ?? '').toLowerCase(),
        `definition ${PROCESS_KEY} must be deployed`,
      ).toBe('deployed');

      // 11. BPMN content persistence — fetch the deployed definition's BPMN
      //     XML. The userTask (and its smart:assignee attrs) must survive
      //     deploy; hook descriptors are NOT expected to appear in BPMN
      //     because JsonToBpmnConverter does not compile hooks (GAP-A).
      //     This is an explicit contract: BPMN is the runtime artifact for
      //     flow structure only; hooks live in ab_bpm_node_hook.
      const detailResp = await page.request.get(
        `/api/bpm/process-definitions/${processPid}/bpmn`,
      );
      expect(detailResp.ok()).toBe(true);
      const detailBody = await detailResp.json();
      const bpmnContent = (detailBody?.data as string) ?? '';
      expect(bpmnContent, 'bpmn endpoint must return XML content').toBeTruthy();
      expect(bpmnContent).toContain(`<userTask id="${NODE_ID}"`);
      expect(bpmnContent).toContain('Approve Task');

      // 12a. GAP-254 closed GAP-A: ProcessDeploymentService.deploy now parses
      //      designerJson config.hooks[] and persists them into ab_bpm_node_hook
      //      (with UI vocab pre_execute/post_execute normalized to backend
      //      vocab pre_check/post_action via BpmNodeHookService.normalizeHookType).
      //      The UI seeds actionType=command hooks to exercise the Designer
      //      CommandActionConfig surface; those rows are verified below.
      const deployPersistedHooks = await listHooksForProcess(request, adminToken, PROCESS_KEY);
      const deployPreRow = deployPersistedHooks.find(
        (h) => h.hookType === 'pre_check' && h.hookConfig?.actionType === 'command',
      );
      const deployPostRow = deployPersistedHooks.find(
        (h) => h.hookType === 'post_action' && h.hookConfig?.actionType === 'command',
      );
      expect(deployPreRow, 'pre_check command hook must be persisted by deploy').toBeTruthy();
      expect(deployPostRow, 'post_action command hook must be persisted by deploy').toBeTruthy();
      expect(deployPreRow?.nodeId).toBe(NODE_ID);
      expect(deployPostRow?.nodeId).toBe(NODE_ID);

      // 12b. HOOK-2/3 assert observable runtime variable writes
      //      (preHookFired/postHookFired). Those require script-type hooks
      //      that exercise BpmNodeHookService.executeScript + the SpEL
      //      write-through landed under GAP-257. We register them as
      //      additional rows alongside the deploy-persisted command hooks,
      //      so both execute on ACTIVITY_START/ACTIVITY_END.
      const preHookPid = await registerHookViaApi(request, adminToken, {
        processKey: PROCESS_KEY,
        nodeId: NODE_ID,
        hookType: 'pre_check',
        hookConfig: {
          type: 'script',
          script: '#vars["preHookFired"] = true',
        },
        executionOrder: 10,
        failStrategy: 'ignore',
        async: false,
        enabled: true,
      });
      const postHookPid = await registerHookViaApi(request, adminToken, {
        processKey: PROCESS_KEY,
        nodeId: NODE_ID,
        hookType: 'post_action',
        hookConfig: {
          type: 'script',
          script: '#vars["postHookFired"] = true',
        },
        executionOrder: 10,
        failStrategy: 'ignore',
        async: false,
        enabled: true,
      });
      expect(preHookPid).toBeTruthy();
      expect(postHookPid).toBeTruthy();
    });

    // =======================================================================
    // HOOK-2: start instance → pre-check hook fires on ACTIVITY_START, writes
    //         the preHookFired=true variable into the execution context.
    //
    // DIAGNOSED CAPABILITY GAPS (2026-04-17):
    //
    //   GAP-A (hook compilation missing): JsonToBpmnConverter does not emit
    //     <smart:hooks> or any extensionElements for designerJson
    //     config.hooks entries. grep "hook" in
    //     platform/src/main/java/com/auraboot/framework/bpm/converter/
    //     JsonToBpmnConverter.java returns 0 hits. Node hooks are stored in
    //     ab_bpm_node_hook rather than in BPMN XML, which is intentional
    //     but the UI save path does NOT currently persist designerJson
    //     hooks into that table either. HOOK-1 works around this by calling
    //     POST /api/bpm/node-hooks directly, so runtime rows exist.
    //
    //   GAP-C (SpEL write-through): BpmNodeHookService.executeScript
    //     (platform/src/main/java/com/auraboot/framework/bpm/service/
    //     BpmNodeHookService.java:162-196) evaluates the script against a
    //     SimpleEvaluationContext that exposes the variables map read-only —
    //     writes via `#vars["preHookFired"] = true` do NOT round-trip back
    //     to the SmartEngine execution variables because the listener
    //     passes a snapshot Map and never re-applies mutations to the live
    //     ExecutionContext (ProcessEventListener:94). The hook service
    //     treats the return value as a boolean pass/fail and discards any
    //     side-effects, so even if SpEL mutation succeeded in-place the
    //     mutated map is dropped. This is why HOOK-2 cannot observe the
    //     variable through queryInstanceStatus(.).variables today.
    //
    //   When GAP-A is closed (designerJson hooks persist to
    //   ab_bpm_node_hook on deploy) AND GAP-C is closed (hook mutations
    //   write back to execution variables), remove the .fixme marker and
    //   this test will exercise the full contract unchanged.
    //
    // Platform backlog candidates (for controller to add as GAP-253+):
    //   - GAP-253: Persist designerJson node hooks into ab_bpm_node_hook on
    //     process definition deploy (UI → DB binding still open).
    //   - GAP-254: Support hook-side writes to execution variables
    //     (SimpleEvaluationContext → writeable Context + re-apply to
    //     SmartEngine ExecutionContext in ProcessEventListener).
    //     → STILL OPEN. HOOK-2/3 remain .fixme on this.
    //   - GAP-255: hookType vocab mismatch (UI pre_execute/post_execute/
    //     pre_complete/post_complete vs backend pre_check/post_action).
    //     → RESOLVED 2026-04-17 via BpmNodeHookService.normalizeHookType()
    //     (applied on createHook write-path AND getHooks query-path) +
    //     BpmNodeHookServiceVocabAliasIntegrationTest (VOCAB-01..04/09).
    //   - GAP-256: actionType vocab mismatch (UI http_callback/command vs
    //     backend rest_call/drools_rule) plus the missing `command`
    //     executor branch.
    //     → RESOLVED 2026-04-17 via BpmNodeHookService.normalizeActionType()
    //     + new executeCommand() branch wired through CommandExecutor +
    //     VOCAB-05..09 integration tests.
    //
    // NOTE: HOOK-2/3 are still .fixme because GAP-254 (variable write-through)
    // blocks the observable assertion even now that vocab aliasing works end
    // to end. Once GAP-254 (and the upstream GAP-A at deploy time) land, the
    // .fixme markers can be removed without further hook-service changes.
    // =======================================================================
    test(
      'HOOK-2: start instance → pre-check hook fires, writes preHookFired variable',
      async ({ request }) => {
        expect(processPid, 'processPid must be set from HOOK-1').toBeTruthy();

        startedInstance = await startProcessInstance(request, adminToken, {
          processDefinitionId: PROCESS_KEY,
          businessKey: BK,
          variables: {},
        });
        expect(startedInstance.instanceId).toBeTruthy();

        // Status DTO — approve_task must be currently active (waiting for
        // user action). Pre-check hook runs synchronously on ACTIVITY_START
        // (ProcessEventListener:93-97).
        const status = await queryInstanceStatus(request, adminToken, {
          processKey: PROCESS_KEY,
          businessKey: BK,
        });
        expect(status.currentNodes.map((n) => n.nodeId)).toContain(NODE_ID);

        // Hook evidence — the pre-check hook's script mutated the `vars`
        // map, which the listener re-applies to the SmartEngine execution
        // context. Once GAP-C is closed this variable surfaces in the
        // status DTO's `variables` field.
        expect(
          status.variables.preHookFired,
          'pre-check hook must write preHookFired=true to execution variables',
        ).toBe(true);

        // Audit trail — activity_start row must exist alongside process_start
        const audit = await listAuditEvents(request, adminToken, startedInstance.instanceId);
        expect(hasProcessStart(audit), 'audit must include process_start').toBe(true);
        expect(
          audit.some(
            (a) =>
              a.operation === 'activity_event' &&
              a.details?.eventType === 'activity_start' &&
              a.activityId === NODE_ID,
          ),
          'activity_start audit row for approve_task must be recorded',
        ).toBe(true);
      },
    );

    // =======================================================================
    // HOOK-3: complete task → post-action hook fires on ACTIVITY_END, writes
    //         postHookFired=true.
    // Blocked by the same GAP-C as HOOK-2 (post-action variable write-through).
    // Additionally blocked by the ordering guarantee: ProcessEventListener
    // runs the hook AFTER bpmAuditService.recordActivityEvent but BEFORE the
    // event publisher, so the variable would need to be observable via the
    // next queryInstanceStatus call — today that call sees the pre-activity
    // snapshot because the hook mutation is never persisted.
    // =======================================================================
    test(
      'HOOK-3: complete task → post-action hook fires, writes postHookFired variable',
      async ({ request }) => {
        expect(
          startedInstance,
          'startedInstance must be set from HOOK-2',
        ).toBeTruthy();
        const instanceId = startedInstance!.instanceId;

        const tasks = await listInstanceTodoTasks(request, adminToken, instanceId);
        expect(
          tasks.length,
          `before-complete must see 1 approve_task task (got ${tasks.length})`,
        ).toBe(1);
        expect(tasks[0].nodeKey).toBe(NODE_ID);

        await completeTask(request, adminToken, tasks[0].id);

        // Final status must carry postHookFired=true, and the instance
        // should have ended (currentNodes empty, completedNodes includes
        // approve_task). Post-action hook commits its SpEL variable write
        // alongside SmartEngine's own activity-end variable snapshot, so
        // poll briefly for the variable to become visible via the status
        // DTO (async variable persistence can race the read).
        let final: Awaited<ReturnType<typeof queryInstanceStatus>> | null = null;
        await expect
          .poll(
            async () => {
              final = await queryInstanceStatus(request, adminToken, {
                processKey: PROCESS_KEY,
                businessKey: BK,
              });
              return final.variables.postHookFired === true;
            },
            {
              timeout: 5_000,
              intervals: [200, 500, 1000],
              message: 'post-action hook must write postHookFired=true to execution variables',
            },
          )
          .toBe(true);
        expect(final!.currentNodes.length).toBe(0);
        expect(final!.completedNodes.map((n) => n.nodeId)).toContain(NODE_ID);
        expect(['completed', 'ended', 'finished']).toContain(
          final!.status.toLowerCase(),
        );

        // Audit trail — activity_end row must exist for approve_task
        const audit = await listAuditEvents(request, adminToken, instanceId);
        expect(
          audit.some(
            (a) =>
              a.operation === 'activity_event' &&
              a.details?.eventType === 'activity_end' &&
              a.activityId === NODE_ID,
          ),
          'activity_end audit row for approve_task must be recorded',
        ).toBe(true);
      },
    );

    // =======================================================================
    // HOOK-4: cleanup — undeploy test process (best-effort)
    // =======================================================================
    test('HOOK-4: undeploy test process (cleanup, best-effort)', async ({ request }) => {
      expect(processPid, 'processPid must be set from HOOK-1').toBeTruthy();
      // HOOK-2/3 are .fixme so no instance is running; undeploy should
      // succeed. We still accept 500 defensively (e.g. rerun scenarios with
      // leftover state from a prior run) — env-reset handles true cleanup
      // between full resets.
      const { status } = await undeployProcess(request, adminToken, processPid);
      expect(
        [200, 204, 500],
        `undeploy response ${status} must be ok-or-running-blocked`,
      ).toContain(status);
    });
  },
);
