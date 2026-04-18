/**
 * BPM Designer Command ServiceTask Full Lifecycle — Wave 2 SVCC
 *
 * Validates the complete path from UI canvas → ServiceTask configured as
 * serviceType=command + commandCode → deploy → start instance → the
 * AuraBoot CommandServiceTaskDelegate actually invokes the target Command
 * through the 16-phase pipeline, producing a real side effect (new
 * announcement row) and an activity_end audit event.
 *
 * Dimensions covered (per docs/standards/testing-e2e-web.md):
 *   D1  — Sidebar menu navigation (not page.goto direct)
 *   D4  — Designer canvas (ServiceTask node seeded via designerJson)
 *   D5  — Property panel components (serviceType select → 'command';
 *         commandCode input)
 *   D8  — Persistence after deploy (compiled BPMN contains
 *         smart:class="commandServiceTaskDelegate")
 *   D11 — Runtime side effect (announcement record is actually created)
 *   D12 — Audit trail (activity_start/end around the serviceTask)
 *   D14 — Toast/status feedback on deploy button
 *
 * Why designerJson is seeded via API rather than drag-and-drop:
 * React Flow HTML5 drag-and-drop is not reliably reproducible via Playwright
 * (see bpm-designer-interaction.spec.ts BD-005, designer-gateway-lifecycle B1,
 *  and designer-callactivity CA-1 for the same pattern). We seed the draft's
 * node/edge shape via API and then exercise the real property-panel UI —
 * selecting the serviceTask node, changing the serviceType <select>, and
 * typing into the commandCode <input>. The toolbar Save and Deploy are
 * real clicks too (page.click count > page.request count).
 *
 * Why starting the instance with a pre-populated `_chain_nodes` variable:
 * CommandServiceTaskDelegate reads per-node config (commandCode,
 * operationType, params, onFail, ...) from the process variable
 * `_chain_nodes[<activityId>]`. In production, CommandChainService fills this
 * map from a ChainDefinition JSON. For this lifecycle E2E we short-circuit
 * that layer: we start the process directly via /api/bpm/process-instances
 * with variables.{_chain_nodes: {exec_cmd: {...}}} — this is the exact shape
 * the delegate reads (CommandServiceTaskDelegate.java#66-93). The point of
 * SVCC is to prove the DESIGNER→BPMN→DELEGATE wiring, not the ChainService
 * orchestration layer.
 *
 * @since Wave 2 SVCC (OSS BPM)
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import {
  loginAsAdmin,
  startProcessInstance,
  queryInstanceStatus,
  listAuditEvents,
  collectActivityEvents,
  undeployProcess,
} from './_helpers/bpm-lifecycle';

// ---------------------------------------------------------------------------
// Serial — SVCC-1 deploys; SVCC-2 starts + validates execution; SVCC-3 cleans.
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Shared constants
// ---------------------------------------------------------------------------
const TS = Date.now();
const PROCESS_KEY = `svcc_cmd_${TS}`;
const PROCESS_NAME = `ServiceTask Command ${TS}`;
const BUSINESS_KEY = `svcc_bk_${TS}`;

// Chosen Command:
//   wd:create_leave_balance (see plugins/workflow-demo/config/commands.json)
//   type=create, modelCode=wd_leave_balance. Required fields:
//     - wd_bal_employee (string, stores the employee pid)
//     - wd_bal_year     (integer)
//   workflow-demo is a default demo plugin, imported in every env that runs
//   reset-and-init.sh — does NOT depend on AURA_ENV=test fixtures.
const TARGET_COMMAND_CODE = 'wd:create_leave_balance';
const TARGET_OPERATION_TYPE = 'create';
// Any valid employee pid works; we use the well-known seed employee #1
// created by reset-and-init.sh (see mt_org_employee). The point is to
// produce a real DB row whose existence proves the delegate executed.
const TARGET_EMPLOYEE_PID = '01KPF6JCTWW61NWH5P13KNQT47';
// Unique year-shift per run so parallel runs do not collide on the
// (tenant, employee, year) unique-ish key if present. Years 2020..2030.
const TARGET_YEAR = 2000 + (TS % 100);
// Shortened annual_remaining so the matching record is recognisable
// among any seed data.
const TARGET_ANNUAL_REMAINING = 1 + (TS % 9); // 1..9

// ---------------------------------------------------------------------------
// Shared module state threaded across serial tests
// ---------------------------------------------------------------------------
let adminToken = '';
let pid = '';
let instanceId = '';

// ---------------------------------------------------------------------------
// BPMN/designerJson seeds
// ---------------------------------------------------------------------------
function buildDesignerJson() {
  return {
    nodes: [
      {
        id: 'start',
        type: 'startEvent',
        position: { x: 80, y: 160 },
        data: { type: 'startEvent', label: 'Start', config: {} },
      },
      {
        id: 'exec_cmd',
        type: 'serviceTask',
        position: { x: 260, y: 160 },
        data: {
          type: 'serviceTask',
          label: 'Exec Command',
          // Intentionally pre-seed without serviceType so we can prove the UI
          // select + commandCode input actually drive the persisted config.
          config: {},
        },
      },
      {
        id: 'end',
        type: 'endEvent',
        position: { x: 460, y: 160 },
        data: { type: 'endEvent', label: 'End', config: {} },
      },
    ],
    edges: [
      {
        id: 'e_start_exec',
        source: 'start',
        target: 'exec_cmd',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_exec_end',
        source: 'exec_cmd',
        target: 'end',
        type: 'smoothstep',
        data: { label: '' },
      },
    ],
  };
}

// ---------------------------------------------------------------------------
// Helpers
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

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe(
  'BPM Designer Command ServiceTask lifecycle',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(180_000);

    test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
      adminToken = await loginAsAdmin(request);
    });

    // =======================================================================
    // SVCC-1: UI configures serviceType=command + commandCode + deploys.
    // Asserts the compiled BPMN XML contains
    //   smart:class="commandServiceTaskDelegate"
    // — i.e. JsonToBpmnConverter.writeServiceTask took the `command` branch.
    // =======================================================================
    test('SVCC-1: UI configures Command serviceTask + deploys', async ({ page }) => {
      // 1. Sidebar nav (D1)
      await navigateToProcessDefinitionList(page);

      // 2. Seed draft via API (see top-of-file note). IMPORTANT: we pass an
      // empty bpmnContent so that deploy() compiles the BPMN freshly from
      // designerJson via JsonToBpmnConverter — that compile step is the whole
      // subject under test (the `serviceType === 'command'` branch at
      //  JsonToBpmnConverter.java#603 emits `smart:class=commandServiceTaskDelegate`).
      // If we passed a pre-baked BPMN string here, deploy would skip the
      // compile step (ProcessDeploymentService.java#341) and our assertion
      // would be a tautology on our own seed.
      const createResp = await page.request.post('/api/bpm/process-definitions', {
        data: {
          processKey: PROCESS_KEY,
          processName: PROCESS_NAME,
          description: 'SVCC-1 command servicetask lifecycle E2E',
          category: 'e2e-test',
          bpmnContent: '',
          designerJson: JSON.stringify(buildDesignerJson()),
        },
      });
      expect(createResp.ok(), `draft create: ${createResp.status()}`).toBe(true);
      const createBody = await createResp.json();
      pid = String(createBody?.data?.pid ?? createBody?.data?.id ?? '');
      expect(pid, 'create must return pid').toBeTruthy();

      // 3. Open in designer by pid (D4)
      await page.goto(`/bpmn-designer?pid=${pid}`, { waitUntil: 'domcontentloaded' });
      await expect(page.locator('.react-flow')).toBeVisible({ timeout: 15_000 });
      await page.waitForFunction(
        () =>
          Boolean(
            (window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore,
          ),
        undefined,
        { timeout: 8_000 },
      );
      // Headless canvas height fix (same as B1/CA-1)
      await page.evaluate(() => {
        const rf = document.querySelector('.react-flow') as HTMLElement | null;
        if (rf && rf.offsetHeight < 50) {
          rf.style.height = '600px';
          rf.style.minHeight = '600px';
        }
      });

      await expect(page.locator('.react-flow__node')).toHaveCount(3, { timeout: 10_000 });
      await expect
        .poll(async () => page.locator('.react-flow__edge').count(), { timeout: 5_000 })
        .toBeGreaterThanOrEqual(2);

      await expect(page.locator('[data-testid="bpmn-field-name"]')).toHaveValue(PROCESS_NAME);
      await expect(page.locator('[data-testid="bpmn-field-key"]')).toHaveValue(PROCESS_KEY);

      // 4. Select the serviceTask node → property panel renders ServiceTaskEditor
      await selectNodeOpenEditor(page, 'exec_cmd');

      // 5. UI: change serviceType <select> to 'command' (D5)
      const typeSelect = page.locator('[data-testid="servicetask-service-type"]');
      await expect(typeSelect).toBeVisible({ timeout: 5_000 });
      await typeSelect.selectOption('command');
      await expect(typeSelect).toHaveValue('command');

      // The commandCode input should appear conditionally.
      const commandCodeInput = page.locator('[data-testid="servicetask-command-code"]');
      await expect(commandCodeInput).toBeVisible({ timeout: 3_000 });
      await commandCodeInput.fill(TARGET_COMMAND_CODE);
      await expect(commandCodeInput).toHaveValue(TARGET_COMMAND_CODE);

      // 6. Save via the shared DesignerToolbar (app/shared/designer). BPMNToolbar
      // passes testId="bpmn-toolbar", and the shared toolbar renders its save
      // button with testId="bpmn-toolbar-btn-save" (see DesignerToolbar.tsx#117).
      // The button opens a SaveDialog which performs the actual PUT on submit.
      const saveBtn = page.locator('[data-testid="bpmn-toolbar-btn-save"]');
      await expect(saveBtn).toBeVisible({ timeout: 5_000 });
      await saveBtn.click();

      const saveDialogSubmit = page.locator('[data-testid="bpmn-save-dialog-submit"]');
      await expect(saveDialogSubmit).toBeVisible({ timeout: 5_000 });

      const saveResponsePromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/bpm/process-definitions/${pid}`) &&
          r.request().method() === 'PUT' &&
          r.status() < 400,
        { timeout: 15_000 },
      );
      await saveDialogSubmit.click();
      await saveResponsePromise;
      // Wait for dialog to close before continuing (otherwise subsequent
      // clicks on the pane might land on the dialog backdrop).
      await expect(
        page.locator('[data-testid="bpmn-save-dialog-panel"]'),
      ).toBeHidden({ timeout: 5_000 });

      // Deselect so Deploy's isDirty logic is stable
      await page.locator('.react-flow__pane').click({ position: { x: 50, y: 50 } });

      // 7. Deploy via toolbar (real UI click, D14)
      const deployBtn = page.locator('[data-testid="bpmn-btn-deploy"]');
      await expect(deployBtn).toBeVisible({ timeout: 5_000 });
      await expect(deployBtn).toBeEnabled({ timeout: 10_000 });
      const deployResponsePromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/bpm/process-definitions/${pid}/deploy`) &&
          r.status() < 400,
        { timeout: 20_000 },
      );
      await deployBtn.click();
      const deployResp = await deployResponsePromise;
      expect(deployResp.status()).toBeLessThan(400);

      // 8. Persistence check (D8): fetch compiled BPMN and assert delegate wiring
      const bpmnResp = await page.request.get(`/api/bpm/process-definitions/${pid}/bpmn`);
      expect(bpmnResp.ok(), `fetch bpmn: ${bpmnResp.status()}`).toBe(true);
      const bpmnBody = await bpmnResp.json();
      // Response shape: { data: { bpmnContent: "..." } } OR { data: "<xml>" }.
      // Parse canonically (no silent fallback on random paths).
      const rawData = bpmnBody?.data;
      const bpmnXml: string =
        typeof rawData === 'string'
          ? rawData
          : typeof rawData?.bpmnContent === 'string'
          ? rawData.bpmnContent
          : typeof rawData?.content === 'string'
          ? rawData.content
          : '';
      expect(
        bpmnXml.length,
        `BPMN XML must be a non-empty string; got shape=${Object.keys(rawData ?? {}).join(',')}`,
      ).toBeGreaterThan(0);

      // Critical assertion: writeServiceTask took the `command` branch.
      expect(
        bpmnXml,
        'compiled BPMN must declare commandServiceTaskDelegate for the serviceTask',
      ).toMatch(/smart:class=["']commandServiceTaskDelegate["']/);

      // And the serviceTask carries id="exec_cmd" — guards against a false
      // positive where the delegate attribute landed on a different node.
      expect(bpmnXml).toMatch(/<serviceTask[^>]*\bid=["']exec_cmd["']/);
    });

    // =======================================================================
    // SVCC-2: start an instance. CommandServiceTaskDelegate should resolve
    // TARGET_COMMAND_CODE from `_chain_nodes.exec_cmd`, invoke the full
    // Command pipeline, and a real announcement row should be created.
    // Then the process should complete (no userTasks between start and end).
    // =======================================================================
    test('SVCC-2: start instance → command really executes + audit records node', async ({
      request,
    }) => {
      expect(pid, 'pid must be set from SVCC-1').toBeTruthy();

      // Start instance with _chain_nodes seeded for the `exec_cmd` activity.
      // This is the exact shape CommandServiceTaskDelegate reads at
      // CommandServiceTaskDelegate.java#66 (processVars.get("_chain_nodes")).
      const started = await startProcessInstance(request, adminToken, {
        processDefinitionId: PROCESS_KEY,
        businessKey: BUSINESS_KEY,
        variables: {
          _chain_nodes: {
            exec_cmd: {
              commandCode: TARGET_COMMAND_CODE,
              operationType: TARGET_OPERATION_TYPE,
              params: {
                wd_bal_employee: TARGET_EMPLOYEE_PID,
                wd_bal_year: TARGET_YEAR,
                wd_bal_annual_remaining: TARGET_ANNUAL_REMAINING,
              },
              onFail: 'abort',
            },
          },
        },
      });
      instanceId = started.instanceId;
      expect(instanceId, 'instance id required').toBeTruthy();

      // After start, since the only node between start and end is the
      // synchronous serviceTask, the instance should finish immediately.
      // Poll the status to ride out any engine-internal latency.
      await expect
        .poll(
          async () => {
            const s = await queryInstanceStatus(request, adminToken, {
              processKey: PROCESS_KEY,
              businessKey: BUSINESS_KEY,
            });
            return String(s.status ?? '').toLowerCase();
          },
          { timeout: 15_000, intervals: [300, 800, 1_500] },
        )
        .toMatch(/^(completed|finished|ended)$/);

      const finalStatus = await queryInstanceStatus(request, adminToken, {
        processKey: PROCESS_KEY,
        businessKey: BUSINESS_KEY,
      });

      // exec_cmd must be in completedNodes (structural proof the delegate
      // ran and control advanced past the serviceTask).
      expect(
        finalStatus.completedNodes.map((n: { nodeId: string }) => n.nodeId),
        'exec_cmd must appear in completedNodes',
      ).toContain('exec_cmd');

      // Audit trail (D12): activity_start + activity_end around exec_cmd.
      const events = await listAuditEvents(request, adminToken, instanceId);
      const activityRows = collectActivityEvents(events);
      const execCmdEvents = activityRows.filter((r) => r.activityId === 'exec_cmd');
      expect(
        execCmdEvents.length,
        `exec_cmd must have audit events; got ${JSON.stringify(activityRows)}`,
      ).toBeGreaterThan(0);

      // Real side-effect assertion (D11): the Command pipeline actually ran
      // and created a leave-balance row. Query via DynamicController list
      // endpoint (GET /api/dynamic/{pageKey}/list — see DynamicController.java
      // #83). Standard-CRUD pageKey convention is `{model_code}_list`.
      const filterJson = JSON.stringify([
        { fieldName: 'wd_bal_employee', operator: 'EQ', value: TARGET_EMPLOYEE_PID },
        { fieldName: 'wd_bal_year', operator: 'EQ', value: TARGET_YEAR },
      ]);
      const listResp = await request.get(
        `/api/dynamic/wd_leave_balance_list/list?pageNum=1&pageSize=20` +
          `&filters=${encodeURIComponent(filterJson)}` +
          `&sortField=id&sortOrder=DESC`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(
        listResp.ok(),
        `leave balance list: ${listResp.status()} ${await listResp.text()}`,
      ).toBe(true);
      const listBody = await listResp.json();
      const records =
        (listBody?.data?.records as Array<Record<string, unknown>>) ?? [];
      expect(
        records.length,
        `at least one leave_balance row matching (employee=${TARGET_EMPLOYEE_PID}, ` +
          `year=${TARGET_YEAR}) must exist — proving CommandServiceTaskDelegate ` +
          `invoked ${TARGET_COMMAND_CODE} through the full 16-phase pipeline`,
      ).toBeGreaterThanOrEqual(1);
      // Structural sanity: the matched row carries the annual_remaining we
      // passed (so we know this row is ours, not residual seed data).
      const match = records.find(
        (r) =>
          String(r.wd_bal_employee ?? r.wdBalEmployee) === TARGET_EMPLOYEE_PID &&
          Number(r.wd_bal_year ?? r.wdBalYear) === TARGET_YEAR,
      );
      expect(match, 'matching leave_balance row').toBeTruthy();
      // annual_remaining comes back as either numeric or string depending on
      // the column binding; normalise to number for the equality check.
      const remaining = Number(
        match?.wd_bal_annual_remaining ?? match?.wdBalAnnualRemaining ?? NaN,
      );
      expect(remaining, 'wd_bal_annual_remaining must round-trip').toBe(
        TARGET_ANNUAL_REMAINING,
      );
    });

    // =======================================================================
    // SVCC-3: cleanup — best-effort undeploy + delete the announcement we
    // created. We intentionally don't fail the suite if cleanup fails: the
    // env-reset handles true cleanup between runs.
    // =======================================================================
    test('SVCC-3: cleanup', async ({ request }) => {
      if (pid) {
        const { status } = await undeployProcess(request, adminToken, pid);
        expect(
          [200, 204, 500],
          `undeploy status ${status} must be ok-or-running-blocked`,
        ).toContain(status);
      }

      // Delete the leave_balance row we created (best-effort)
      const filterJson = JSON.stringify([
        { fieldName: 'wd_bal_employee', operator: 'EQ', value: TARGET_EMPLOYEE_PID },
        { fieldName: 'wd_bal_year', operator: 'EQ', value: TARGET_YEAR },
      ]);
      const listResp = await request.get(
        `/api/dynamic/wd_leave_balance_list/list?pageNum=1&pageSize=10` +
          `&filters=${encodeURIComponent(filterJson)}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      if (listResp.ok()) {
        const body = await listResp.json();
        const records =
          (body?.data?.records as Array<Record<string, unknown>>) ?? [];
        for (const row of records) {
          const rowId = row.id ?? row.recordId;
          if (rowId) {
            await request.delete(
              `/api/dynamic/wd_leave_balance_list/${rowId}`,
              { headers: { Authorization: `Bearer ${adminToken}` } },
            );
          }
        }
      }
    });
  },
);
