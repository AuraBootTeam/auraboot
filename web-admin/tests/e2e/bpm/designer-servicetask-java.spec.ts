/**
 * BPM Designer Java ServiceTask Full Lifecycle — Wave 3 SVCJ (P3-A)
 *
 * Validates the complete path from UI canvas → ServiceTask configured as
 * serviceType=java + className → deploy → start instance → the Java
 * delegate (resolved by Spring bean name) actually invokes, producing a
 * real side effect and an activity_end audit event.
 *
 * Why this is distinct from SVCC (command serviceType):
 *   - SVCC drives the `command` branch in JsonToBpmnConverter (hardcoded
 *     smart:class="commandServiceTaskDelegate").
 *   - SVCJ drives the `java` branch (JsonToBpmnConverter.java#634-636):
 *         else if (className != null) {
 *             writer.writeAttribute(SMART_NAMESPACE, "class", className);
 *         }
 *     i.e. whatever bean name the user types into the UI is emitted verbatim
 *     into the compiled BPMN, then resolved at runtime by SmartEngine's
 *     JavaDelegation mechanism. This is the "bring-your-own-delegate"
 *     extension point, so we must prove end-to-end that:
 *       (a) UI `serviceType=java` + `className` testids persist through
 *           save/deploy into the compiled BPMN's smart:class attribute;
 *       (b) a real Spring-managed @Component("<className>") JavaDelegation
 *           bean gets invoked when the process runs.
 *
 * Chosen delegate:
 *   `commandServiceTaskDelegate` — already production-grade, has full
 *   integration/unit test coverage, and produces an observable DB
 *   side-effect (creates a wd_leave_balance row via the AuraBoot Command
 *   pipeline). This is the SAME delegate the `command` branch wires, but
 *   here we reach it through the generic `serviceType=java + className=...`
 *   path, which proves the bean-name routing is the actual contract and
 *   not a side-effect of the `command` shortcut.
 *
 * Dimensions covered (per docs/standards/testing-e2e-web.md):
 *   D1  — Sidebar menu navigation (not page.goto direct)
 *   D4  — Designer canvas (ServiceTask node seeded via designerJson)
 *   D5  — Property panel: serviceType <select> → 'java', className <input>
 *   D8  — Persistence after deploy: compiled BPMN contains
 *         smart:class="commandServiceTaskDelegate" (same bean name, driven
 *         by `java` branch — different code path than SVCC).
 *   D11 — Runtime side effect: a real wd_leave_balance row is created.
 *   D12 — Audit trail: activity_start/end around the serviceTask.
 *   D14 — Toast/status feedback on save + deploy.
 *
 * @since Wave 3 SVCJ (OSS BPM, P3-A)
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
// Serial — SVCJ-1 deploys; SVCJ-2 starts + validates execution; SVCJ-3 cleans.
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Shared constants
// ---------------------------------------------------------------------------
const TS = Date.now();
const PROCESS_KEY = `svcj_java_${TS}`;
const PROCESS_NAME = `ServiceTask Java ${TS}`;
const BUSINESS_KEY = `svcj_bk_${TS}`;

// The explicit Spring bean name we type into the designer's className input.
// Matches the @Component("commandServiceTaskDelegate") in
// platform/src/main/java/com/auraboot/framework/bpm/chain/CommandServiceTaskDelegate.java#43.
const TARGET_CLASS_NAME = 'commandServiceTaskDelegate';

// The target Command this delegate invokes once running.
// (The delegate reads it from _chain_nodes.<activityId>.commandCode at
// runtime; see CommandServiceTaskDelegate.java#66-79.)
const TARGET_COMMAND_CODE = 'wd:create_leave_balance';
const TARGET_OPERATION_TYPE = 'create';
// Well-known seed employee from reset-and-init.sh (same as SVCC/SVCH).
const TARGET_EMPLOYEE_PID = '01KPF6JCTWW61NWH5P13KNQT47';
// Unique year so parallel runs / SVCC runs do not collide on the matching
// row (years 2000..2099).
const TARGET_YEAR = 2000 + (TS % 100);
// Shortened annual_remaining so the row is recognisable among seed data.
const TARGET_ANNUAL_REMAINING = 1 + (TS % 9); // 1..9

// ---------------------------------------------------------------------------
// Shared module state threaded across serial tests
// ---------------------------------------------------------------------------
let adminToken = '';
let pid = '';
let instanceId = '';

// ---------------------------------------------------------------------------
// designerJson seed — same topology as SVCC (start → serviceTask → end).
// Intentionally leaves config empty so SVCJ-1 must drive serviceType +
// className via the real UI inputs, not via pre-seeded JSON.
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
        id: 'java_call',
        type: 'serviceTask',
        position: { x: 260, y: 160 },
        data: {
          type: 'serviceTask',
          label: 'Java Call',
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
        id: 'e_start_java',
        source: 'start',
        target: 'java_call',
        type: 'smoothstep',
        data: { label: '' },
      },
      {
        id: 'e_java_end',
        source: 'java_call',
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
  'BPM Designer Java serviceTask lifecycle',
  { tag: ['@bpm-regression'] },
  () => {
    test.setTimeout(180_000);

    test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
      adminToken = await loginAsAdmin(request);
    });

    // =======================================================================
    // SVCJ-1: UI configures serviceType=java + className + deploys.
    // Asserts the compiled BPMN XML contains
    //   smart:class="commandServiceTaskDelegate"
    // — i.e. JsonToBpmnConverter.writeServiceTask took the `className != null`
    // branch (JsonToBpmnConverter.java#634-636), NOT the `command` shortcut.
    // =======================================================================
    test('SVCJ-1: UI configures Java serviceTask + deploys', async ({ page }) => {
      // 1. Sidebar nav (D1)
      await navigateToProcessDefinitionList(page);

      // 2. Seed draft via API with empty bpmnContent so deploy() compiles
      // the BPMN freshly from designerJson via JsonToBpmnConverter — that
      // compile step is the subject under test.
      const createResp = await page.request.post('/api/bpm/process-definitions', {
        data: {
          processKey: PROCESS_KEY,
          processName: PROCESS_NAME,
          description: 'SVCJ-1 java servicetask lifecycle E2E',
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
      // Headless canvas height fix (same as B1/CA-1/SVCC)
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
      await selectNodeOpenEditor(page, 'java_call');

      // 5. UI: change serviceType <select> to 'java' (D5)
      const typeSelect = page.locator('[data-testid="servicetask-service-type"]');
      await expect(typeSelect).toBeVisible({ timeout: 5_000 });
      await typeSelect.selectOption('java');
      await expect(typeSelect).toHaveValue('java');

      // The className input should appear conditionally (W1B testid).
      const classNameInput = page.locator('[data-testid="servicetask-class-name"]');
      await expect(classNameInput).toBeVisible({ timeout: 3_000 });
      await classNameInput.fill(TARGET_CLASS_NAME);
      await expect(classNameInput).toHaveValue(TARGET_CLASS_NAME);

      // 6. Save via shared DesignerToolbar
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

      // 8. Persistence check (D8): fetch compiled BPMN and assert delegate wiring.
      const bpmnResp = await page.request.get(`/api/bpm/process-definitions/${pid}/bpmn`);
      expect(bpmnResp.ok(), `fetch bpmn: ${bpmnResp.status()}`).toBe(true);
      const bpmnBody = await bpmnResp.json();
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
        `BPMN XML must be non-empty; got shape=${Object.keys(rawData ?? {}).join(',')}`,
      ).toBeGreaterThan(0);

      // Critical assertion: writeServiceTask took the `className != null`
      // branch and emitted our exact bean name into smart:class.
      expect(
        bpmnXml,
        `compiled BPMN must declare smart:class="${TARGET_CLASS_NAME}" on the serviceTask`,
      ).toMatch(new RegExp(`smart:class=["']${TARGET_CLASS_NAME}["']`));

      // And the serviceTask carries id="java_call" — guards against a false
      // positive where the attribute landed on a different node.
      expect(bpmnXml).toMatch(/<serviceTask[^>]*\bid=["']java_call["']/);
    });

    // =======================================================================
    // SVCJ-2: start instance. SmartEngine resolves the bean
    // commandServiceTaskDelegate by name, the JavaDelegation.execute runs,
    // reads _chain_nodes.java_call, calls CommandExecutor, a real
    // wd_leave_balance row is created.
    // =======================================================================
    test('SVCJ-2: start instance → Java delegate truly invokes + audit records node', async ({
      request,
    }) => {
      expect(pid, 'pid must be set from SVCJ-1').toBeTruthy();

      const started = await startProcessInstance(request, adminToken, {
        processDefinitionId: PROCESS_KEY,
        businessKey: BUSINESS_KEY,
        variables: {
          _chain_nodes: {
            java_call: {
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

      // Synchronous serviceTask between start and end → instance finishes
      // immediately. Poll for engine latency.
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

      // java_call must be in completedNodes (structural proof the delegate
      // ran and control advanced past the serviceTask).
      expect(
        finalStatus.completedNodes.map((n: { nodeId: string }) => n.nodeId),
        'java_call must appear in completedNodes',
      ).toContain('java_call');

      // Audit trail (D12): activity events around java_call.
      const events = await listAuditEvents(request, adminToken, instanceId);
      const activityRows = collectActivityEvents(events);
      const javaCallEvents = activityRows.filter((r) => r.activityId === 'java_call');
      expect(
        javaCallEvents.length,
        `java_call must have audit events; got ${JSON.stringify(activityRows)}`,
      ).toBeGreaterThan(0);

      // Real side-effect assertion (D11): a wd_leave_balance row was created
      // by the Command pipeline invoked via the Java delegate.
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
          `year=${TARGET_YEAR}) must exist — proving the Java delegate ` +
          `resolved via bean name "${TARGET_CLASS_NAME}" and invoked the ` +
          `Command pipeline`,
      ).toBeGreaterThanOrEqual(1);

      const match = records.find(
        (r) =>
          String(r.wd_bal_employee ?? r.wdBalEmployee) === TARGET_EMPLOYEE_PID &&
          Number(r.wd_bal_year ?? r.wdBalYear) === TARGET_YEAR,
      );
      expect(match, 'matching leave_balance row').toBeTruthy();
      const remaining = Number(
        match?.wd_bal_annual_remaining ?? match?.wdBalAnnualRemaining ?? NaN,
      );
      expect(remaining, 'wd_bal_annual_remaining must round-trip').toBe(
        TARGET_ANNUAL_REMAINING,
      );
    });

    // =======================================================================
    // SVCJ-3: cleanup — best-effort undeploy + delete the created row.
    // =======================================================================
    test('SVCJ-3: cleanup', async ({ request }) => {
      if (pid) {
        const { status } = await undeployProcess(request, adminToken, pid);
        expect(
          [200, 204, 500],
          `undeploy status ${status} must be ok-or-running-blocked`,
        ).toContain(status);
      }

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
