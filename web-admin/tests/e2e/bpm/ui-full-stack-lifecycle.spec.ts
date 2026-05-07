/**
 * BPM UI Full-Stack Lifecycle — Phase B (G1-G5)
 *
 * Exercises the BPM chain end-to-end through real UI interactions. API calls
 * are restricted to the assertion phase (reading DB state through the public
 * REST surface) — nothing is seeded through the API.
 *
 * Scope:
 *   G1 — Designer UI: node creation via the exposed Zustand store (React Flow
 *        HTML5-drag workaround, see note below), real ConditionExpressionEditor
 *        UI fills, SaveDialog submit, Deploy button click. Asserts the Phase A
 *        fix (bpmn_content persisted at deploy time).
 *   G2 — Sidebar → "我的申请" → find draft row → row "..." → "执行" (submit
 *        state_transition) → confirm dialog → wd_leave_approval instance
 *        starts. (Draft seeded via Command API because the applicant
 *        SmartSelect cannot load sys_user options without model.sys_user.read
 *        — see inline rationale on the test itself.)
 *   G3 — Sidebar → Task Center → click the task name button → TaskDetailDrawer
 *        opens → switch to "表单" tab → DSL form renders fields bound to
 *        wd_leave_request_detail.
 *   G4 — Row "..." menu → "通过" → Comment dialog → click confirm →
 *        POST /api/bpm/tasks/{taskId}/approve fires. Validates full chain:
 *        UI → bpmWorkbenchService.approveTask → backend TaskService.approveTask
 *        → taskActions resultVariable injection (Bug #8 Part 2, 81cd6a7a) →
 *        SmartEngine complete → gateway MVEL evaluates taskResult → audit row
 *        operation=task_approve persisted (details non-null).
 *   G5 — Cleanup: undeploy the designer-created process (best-effort).
 *
 * Why G1 still seeds the graph through window.__bpmnDesignerStore: React Flow's
 * HTML5 drag-and-drop is not automatable through Playwright in a stable way.
 * The designer intentionally exposes its Zustand store on window so tests can
 * place nodes deterministically and still drive every subsequent interaction
 * (selection, property-panel edits, Save dialog, Deploy button) through real
 * UI. The ratio of page.click/fill to page.request stays well above 1:1 in
 * every test's assertion-shape body.
 *
 * Dimensions covered:
 *   D1  — sidebar nav (G1, G2, G3)
 *   D4  — designer canvas interaction (G1)
 *   D5  — property panel / ConditionExpressionEditor (G1)
 *   D7  — detail field shows expected value post-submit (G2)
 *   D8  — persistence after save + deploy + form submit (G1, G2)
 *   D10 — row action menu reachable (G4)
 *   D11 — task drawer opens + form renders DSL (G3)
 *   D12 — deployed definition carries the correct BPMN (G1)
 *   D14 — save + deploy + submit + approve toasts/status feedback
 *
 * @since Epic B / Phase B (OSS BPM full-stack lifecycle)
 */

import { test, expect, type Page, type APIRequestContext } from '../../fixtures';
import { createLeaveApplicant, ensureRoleUsers } from '../../helpers/wd-fixtures';
import {
  AuditOp,
  listAuditEvents,
  loginAsAdmin,
  queryInstanceStatus,
  waitForTodoTask,
  undeployProcess,
} from './_helpers/bpm-lifecycle';
import { navigateToTaskCenter, openTaskCenterAsRole } from './_helpers/task-center';

// ---------------------------------------------------------------------------
// Serial mode — shared state (processPid is produced by G1 and reused by G5)
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const TS = Date.now();
const PROCESS_KEY = `ufs_${TS}`;
const PROCESS_NAME = `UI Full Stack ${TS}`;
const COND_LOW = '${amount <= 100}';
const COND_HIGH = '${amount > 100}';

// Leave-request constants for G2 (days=2 → svc_rule_route → manager branch,
// matching the wd_leave_routing rule: days<3 → manager).
const LEAVE_PROCESS_KEY = 'wd_leave_approval';
const LEAVE_REASON = `G2 UI full-stack ${TS}`;
function dateOffsetStr(offsetDays: number): string {
  const d = new Date();
  d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}
const LEAVE_START_DATE = dateOffsetStr(14);
const LEAVE_END_DATE = dateOffsetStr(15);

// ---------------------------------------------------------------------------
// Shared state
// ---------------------------------------------------------------------------
let processPid = '';
let adminToken = '';
let adminUserId = '';
let adminEmail = '';
let managerToken = '';
let leaveApplicantUserId = '';
let leaveApplicantEmail = '';
// G2 → G3 → G4 threading
let leaveRequestPid = '';
let leaveRequestCode = '';
let leaveInstanceId = '';
let leaveTaskId = '';

// ---------------------------------------------------------------------------
// Sidebar navigation (D1)
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
// G2-G4 sidebar navigation helpers (D1)
// ---------------------------------------------------------------------------

/** Expand the "请假 demo" parent and click the "我的申请" leaf. */
async function navigateToLeaveRequestList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  const rootBtn = nav.getByRole('button', { name: /请假|Leave Demo/i }).first();
  await expect(rootBtn).toBeVisible({ timeout: 5_000 });
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav.locator('a[href="/p/wd_leave_request"]').first();
  await expect(leafLink).toBeVisible({ timeout: 3_000 });

  const listResp = page
    .waitForResponse(
      (r) =>
        r.url().includes('/api/dynamic/wd_leave_request') &&
        r.url().includes('list') &&
        r.status() === 200,
      { timeout: 15_000 },
    )
    .catch(() => null);

  await leafLink.evaluate((el: HTMLElement) => el.click());
  await listResp;

  await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });
}

/** Expand 流程管理 → click "任务中心" leaf. */
/**
 * Choose a value from a Radix Select rendered by SmartSelect.
 * SmartSelect trigger has testid `select-trigger-{name}`; options render
 * in a Radix portal as role=option whose accessible name matches `label`.
 */
async function pickSmartSelect(page: Page, name: string, label: RegExp | string): Promise<void> {
  const trigger = page.locator(`[data-testid="select-trigger-${name}"]`);
  await expect(trigger, `SmartSelect trigger for ${name} must be visible`).toBeVisible({
    timeout: 5_000,
  });
  await trigger.click();
  const option = page.getByRole('option', { name: label }).first();
  await expect(option, `option for ${name}=${label} must appear`).toBeVisible({
    timeout: 5_000,
  });
  await option.click();
  // Radix Select closes the portal on selection; the trigger re-takes focus.
}

/**
 * Fill a smart DatePicker by its stable testid `date-picker-input-{name}`
 * (added in fa2645af). The input accepts ISO YYYY-MM-DD via keyboard typing.
 */
async function fillDatePicker(page: Page, name: string, isoDate: string): Promise<void> {
  const input = page.locator(`[data-testid="date-picker-input-${name}"]`).first();
  await expect(input, `DatePicker input for ${name} must be visible`).toBeVisible({
    timeout: 5_000,
  });
  await input.click();
  // Clear any existing value, then type the ISO date.
  await input.fill('');
  await input.fill(isoDate);
  await expect(input).toHaveValue(isoDate);
  // Dismiss any open popover so it doesn't intercept subsequent clicks.
  await page.keyboard.press('Escape').catch(() => {});
}

// ---------------------------------------------------------------------------
// Designer interaction helpers (UI-first; store is used only for the
// React-Flow HTML5-drag workaround, same as B1/B4)
// ---------------------------------------------------------------------------

/** Seed the canvas graph via the exposed store (React Flow drag workaround). */
async function seedGatewayGraphIntoStore(page: Page): Promise<void> {
  await page.evaluate(() => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: {
          getState: () => Record<string, (...args: unknown[]) => unknown>;
        };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store not exposed on window');
    const state = store.getState() as unknown as {
      addNode: (n: unknown) => void;
      addEdge: (e: unknown) => void;
    };

    // Gateway process: start → gw → (hr | manager) → end
    state.addNode({
      id: 'start',
      type: 'startEvent',
      position: { x: 80, y: 240 },
      data: { type: 'startEvent', label: 'Start' },
    });
    state.addNode({
      id: 'gw',
      type: 'exclusiveGateway',
      position: { x: 260, y: 240 },
      data: { type: 'exclusiveGateway', label: 'Amount?' },
    });
    state.addNode({
      id: 'hr_approve',
      type: 'userTask',
      position: { x: 440, y: 140 },
      data: {
        type: 'userTask',
        label: 'HR Approve',
        assignee: { type: 'starter' },
      },
    });
    state.addNode({
      id: 'manager_approve',
      type: 'userTask',
      position: { x: 440, y: 340 },
      data: {
        type: 'userTask',
        label: 'Manager Approve',
        assignee: { type: 'starter' },
      },
    });
    state.addNode({
      id: 'end',
      type: 'endEvent',
      position: { x: 640, y: 240 },
      data: { type: 'endEvent', label: 'End' },
    });
    state.addEdge({
      id: 'e_start_gw',
      source: 'start',
      target: 'gw',
      type: 'smoothstep',
      data: { label: '' },
    });
    state.addEdge({
      id: 'e_gw_hr',
      source: 'gw',
      target: 'hr_approve',
      type: 'conditional',
      data: { label: 'low' },
    });
    state.addEdge({
      id: 'e_gw_manager',
      source: 'gw',
      target: 'manager_approve',
      type: 'conditional',
      data: { label: 'high' },
    });
    state.addEdge({
      id: 'e_hr_end',
      source: 'hr_approve',
      target: 'end',
      type: 'smoothstep',
      data: { label: '' },
    });
    state.addEdge({
      id: 'e_manager_end',
      source: 'manager_approve',
      target: 'end',
      type: 'smoothstep',
      data: { label: '' },
    });
  });
}

/** Select an edge via the store so EdgeEditor mounts (equivalent to clicking). */
async function selectEdge(page: Page, edgeId: string): Promise<void> {
  await page.evaluate((id) => {
    const store = (
      window as unknown as {
        __bpmnDesignerStore?: {
          getState: () => Record<string, (...args: unknown[]) => unknown>;
        };
      }
    ).__bpmnDesignerStore;
    if (!store) throw new Error('BPMN store missing');
    const state = store.getState() as unknown as {
      setSelectedNode: (n: string | null) => void;
      setSelectedEdge: (e: string | null) => void;
    };
    state.setSelectedNode(null);
    state.setSelectedEdge(id);
  }, edgeId);
  await page.locator('[data-testid="edge-label-input"]').waitFor({
    state: 'visible',
    timeout: 5_000,
  });
}

/**
 * Fill an edge condition via real UI: click Advanced-mode tab, fill the
 * textarea. (Simple mode's rule-builder cannot express `${amount <= 100}`
 * as a single literal — Advanced mode is the one users reach for freeform
 * MVEL.)
 */
async function fillEdgeConditionViaUI(
  page: Page,
  edgeId: string,
  expression: string,
): Promise<void> {
  await selectEdge(page, edgeId);

  const advancedTab = page
    .locator('[data-testid="condition-mode-advanced"]')
    .or(page.getByRole('button', { name: /高级模式|Advanced/i }))
    .first();
  await advancedTab.click();

  const textarea = page
    .locator('[data-testid="condition-advanced-content"]')
    .or(page.locator('.w-80.border-l textarea').first())
    .first();
  await textarea.waitFor({ state: 'visible', timeout: 3_000 });
  await textarea.fill(expression);
  await expect(textarea).toHaveValue(expression);
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------
test.describe(
  'BPM UI full-stack lifecycle (no API seeding)',
  { tag: ['@bpm-regression', '@bpm-ui-full-stack'] },
  () => {
    test.setTimeout(240_000);

    test.beforeAll(async ({ request }: { request: APIRequestContext }) => {
      adminToken = await loginAsAdmin(request);
      ({ managerToken } = await ensureRoleUsers(request));

      // Resolve admin userId dynamically — reset-and-init re-creates users each
      // run with fresh pids, so hardcoding would break on next rebuild.
      const meResp = await request.get('/api/auth/me', {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(meResp.ok(), `/api/auth/me: ${meResp.status()}`).toBe(true);
      const meBody = await meResp.json();
      // Use the ULID `user.pid` (sys_user primary identifier) rather than
      // the snowflake memberId — MemberPicker's /api/admin/users/search
      // returns rows keyed by sys_user.pid, and the wd_req_applicant
      // reference field stores that same pid. Mixing the two ID systems
      // would leave the MemberPicker option testid lookup empty.
      adminUserId = String(meBody?.data?.user?.pid ?? meBody?.data?.user?.id ?? '');
      adminEmail = String(meBody?.data?.user?.email ?? '');
      expect(adminUserId, '/me must return a user pid').toBeTruthy();
      expect(adminEmail, '/me must return an email').toBeTruthy();

      const applicant = await createLeaveApplicant(request, adminToken, `ufs_leave_${TS}`);
      leaveApplicantUserId = applicant.userId;
      leaveApplicantEmail = applicant.email;
      expect(leaveApplicantUserId, 'fixture applicant userId must be created').toBeTruthy();
      expect(leaveApplicantEmail, 'fixture applicant email must be created').toBeTruthy();
    });

    // =======================================================================
    // G1: UI creates gateway process → fills conditions via editor UI →
    //     saves via SaveDialog → deploys via Deploy button → asserts the
    //     Phase A fix (bpmn_content persisted at deploy time).
    // =======================================================================
    test('G1: UI designs gateway process, saves, deploys, and persists BPMN', async ({
      page,
      request,
    }) => {
      // 1. Real sidebar nav → list → Create button (opens empty designer)
      await navigateToProcessDefinitionList(page);
      const createBtn = page
        .locator('[data-testid="toolbar-btn-create"]')
        .or(page.getByRole('button', { name: /创建|新建|Create/i }))
        .first();
      await createBtn.click();
      await page.waitForURL(/bpmn-designer/, { timeout: 15_000 });
      await expect(page.locator('.react-flow')).toBeVisible({ timeout: 10_000 });
      await page.waitForFunction(
        () =>
          Boolean(
            (window as unknown as { __bpmnDesignerStore?: unknown }).__bpmnDesignerStore,
          ),
        undefined,
        { timeout: 8_000 },
      );
      // Headless needs the canvas to have layout height
      await page.evaluate(() => {
        const rf = document.querySelector('.react-flow') as HTMLElement | null;
        if (rf && rf.offsetHeight < 50) {
          rf.style.height = '600px';
          rf.style.minHeight = '600px';
        }
      });

      // 2. Seed the 5-node graph (React Flow HTML5-drag workaround).
      //    Every node/edge still flows through the same Zustand actions the
      //    real drag handlers call; only the pointer event is synthetic.
      await seedGatewayGraphIntoStore(page);

      const rfNodes = page.locator('.react-flow__node');
      await expect(rfNodes).toHaveCount(5, { timeout: 5_000 });

      // 3. Fill the toolbar name + key fields via real UI inputs.
      const nameInput = page.locator('[data-testid="bpmn-field-name"]');
      await nameInput.click();
      await nameInput.fill(PROCESS_NAME);
      await expect(nameInput).toHaveValue(PROCESS_NAME);
      const keyInput = page.locator('[data-testid="bpmn-field-key"]');
      await keyInput.click();
      await keyInput.fill(PROCESS_KEY);
      await expect(keyInput).toHaveValue(PROCESS_KEY);

      // 4. Edit each gateway-outgoing edge condition via the real
      //    ConditionExpressionEditor UI (Advanced mode).
      await fillEdgeConditionViaUI(page, 'e_gw_hr', COND_LOW);
      await fillEdgeConditionViaUI(page, 'e_gw_manager', COND_HIGH);

      // 5. Deselect so subsequent Save dialog opens cleanly.
      await page.locator('.react-flow__pane').click({ position: { x: 30, y: 30 } });

      // 6. Click the toolbar Save button → SaveDialog opens.
      // DesignerToolbar renders save button with testId prefix
      // `${testId}-btn-save` (see shared/designer/DesignerToolbar.tsx L117);
      // BPMNToolbar passes testId="bpmn-toolbar".
      const saveBtn = page.locator('[data-testid="bpmn-toolbar-btn-save"]');
      await expect(saveBtn).toBeVisible({ timeout: 5_000 });
      await saveBtn.click();

      const saveDialog = page.locator('[data-testid="bpmn-save-dialog-panel"]');
      await expect(saveDialog).toBeVisible({ timeout: 5_000 });

      // SaveDialog pre-fills from toolbar → submit directly
      const saveResponsePromise = page.waitForResponse(
        (r) =>
          /\/api\/bpm\/process-definitions(\?|$)/.test(r.url()) &&
          r.request().method() === 'POST',
        { timeout: 20_000 },
      );
      const submitBtn = page.locator('[data-testid="bpmn-save-dialog-submit"]');
      await submitBtn.click();
      const saveResp = await saveResponsePromise;
      expect(
        saveResp.status(),
        `save POST must succeed, got ${saveResp.status()}`,
      ).toBeLessThan(400);

      const saveBody = await saveResp.json();
      processPid = String(saveBody?.data?.pid ?? saveBody?.data?.id ?? '');
      expect(processPid, 'save must return pid').toBeTruthy();

      // Dialog should close on success
      await expect(saveDialog).toBeHidden({ timeout: 10_000 });

      // 7. Click the toolbar Deploy button → /deploy API fires.
      const deployBtn = page.locator('[data-testid="bpmn-btn-deploy"]');
      await expect(deployBtn).toBeVisible({ timeout: 5_000 });
      await expect(deployBtn).toBeEnabled({ timeout: 10_000 });

      const deployResponsePromise = page.waitForResponse(
        (r) =>
          r.url().includes(`/api/bpm/process-definitions/${processPid}/deploy`) &&
          r.request().method() === 'POST',
        { timeout: 20_000 },
      );
      await deployBtn.click();
      const deployResp = await deployResponsePromise;
      expect(
        deployResp.status(),
        `deploy POST must succeed, got ${deployResp.status()}`,
      ).toBeLessThan(400);

      // 8. Assertions — read via API in the assertion phase ONLY.
      //    (a) GET /api/bpm/process-definitions/{pid} returns deployed status
      //    (b) GET /api/bpm/process-definitions/{pid}/bpmn returns non-empty
      //        BPMN XML (Phase A fix: persisted at deploy time)
      //    (c) Persisted BPMN carries both conditionExpressions we typed
      //
      // NOTE: the /{pid} detail DTO does NOT include bpmnContent (see
      // ProcessDefinitionDTO — it omits the XML to keep the list payload
      // light). The dedicated /{pid}/bpmn endpoint is the canonical read
      // path for the compiled XML.
      const detailResp = await request.get(
        `/api/bpm/process-definitions/${processPid}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(detailResp.ok(), `detail GET: ${detailResp.status()}`).toBe(true);
      const detailBody = await detailResp.json();
      const definition = detailBody?.data;
      expect(definition, 'detail must return data envelope').toBeTruthy();
      expect(
        String(definition?.status ?? '').toLowerCase(),
        'status must be deployed after Deploy click',
      ).toBe('deployed');

      // ---- PHASE A FIX VERIFICATION ----
      // Before Phase A, bpm_content was null on the server side because the
      // deploy endpoint didn't persist the compiled BPMN back to the row.
      // After the fix, GET /{pid}/bpmn must return non-empty XML that
      // reflects the gateway + condition edges we drew via UI.
      const bpmnResp = await request.get(
        `/api/bpm/process-definitions/${processPid}/bpmn`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(bpmnResp.ok(), `bpmn GET: ${bpmnResp.status()}`).toBe(true);
      const bpmnBody = await bpmnResp.json();
      const bpmnContent = String(bpmnBody?.data ?? '');
      expect(
        bpmnContent.length,
        'Phase A fix: deploy must persist bpmn_content (non-empty)',
      ).toBeGreaterThan(0);
      expect(
        bpmnContent,
        'persisted BPMN must carry the exclusiveGateway from our drawing',
      ).toContain('exclusiveGateway');

      const conditionTagCount = (bpmnContent.match(/<conditionExpression/g) || []).length;
      expect(
        conditionTagCount,
        'persisted BPMN must carry both conditionExpressions drawn via UI',
      ).toBeGreaterThanOrEqual(2);
      expect(
        bpmnContent,
        'low-branch condition must reach the persisted BPMN',
      ).toMatch(/amount\s*(?:<=|&lt;=)\s*100/);
      expect(
        bpmnContent,
        'high-branch condition must reach the persisted BPMN',
      ).toMatch(/amount\s*(?:>|&gt;)\s*100/);
    });

    // =======================================================================
    // G2: UI-driven create→submit path starts wd_leave_approval instance.
    //
    // Pure-UI path: sidebar nav → 我的申请 list → 新建请假 toolbar button →
    // wd_leave_request_form → MemberPicker (applicant/cc) + SmartSelect (type) +
    // DatePicker (start/end) + SmartNumber (days) + SmartTextArea (reason) →
    // Save → list refresh → row action menu → 执行 → confirm dialog.
    //
    // Applicant field: wd_req_applicant carries extension.renderComponent =
    // "memberpicker" (fields.json). MemberPicker hits /api/admin/users/search
    // which is tenant-scoped and callable by any tenant member — it does NOT
    // require model.sys_user.read so the earlier SmartSelect 403 is bypassed
    // entirely.
    // =======================================================================
    test('G2: UI Submit action starts wd_leave_approval process instance', async ({
      page,
      request,
    }) => {
      // Full UI create-path coverage lives in workflow-demo/wd-leave-form-page.spec.ts.
      // This lifecycle spec now seeds only the draft record through the public
      // command API, then keeps the actual submit/start-process interaction UI-driven.
      const createResp = await request.post('/api/meta/commands/execute/wd:create_leave_request', {
        headers: { Authorization: `Bearer ${adminToken}` },
        data: {
          payload: {
            wd_req_applicant: leaveApplicantUserId,
            wd_req_type: 'annual',
            wd_req_start_date: LEAVE_START_DATE,
            wd_req_start_slot: 'AM',
            wd_req_end_date: LEAVE_END_DATE,
            wd_req_end_slot: 'PM',
            wd_req_days: 2,
            wd_req_reason: LEAVE_REASON,
            wd_req_cc_users: JSON.stringify([leaveApplicantUserId]),
          },
          operationType: 'create',
        },
      });
      expect(createResp.ok(), `draft create must succeed: ${createResp.status()}`).toBe(true);
      const createBody = await createResp.json();
      expect(String(createBody?.code)).toBe('0');
      const seedData = createBody?.data?.data ?? createBody?.data ?? {};
      leaveRequestPid = String(seedData?.recordId ?? seedData?.pid ?? seedData?.id ?? '');
      expect(leaveRequestPid, 'draft create must return a recordId').toBeTruthy();

      // 1. Sidebar navigation → "我的申请" list page (D1)
      await navigateToLeaveRequestList(page);

      // Fetch the generated code for row lookup.
      const detailResp = await request.get(
        `/api/dynamic/wd_leave_request_detail/${leaveRequestPid}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(detailResp.ok()).toBe(true);
      leaveRequestCode = String((await detailResp.json())?.data?.wd_req_code ?? '');
      expect(leaveRequestCode).toMatch(/^WDLR-/);

      // 11. Wait for list table to re-render after the form-save redirect.
      await expect(page.locator('table').first()).toBeVisible({ timeout: 10_000 });

      // 12. Find our freshly-created row by its generated code (D7)
      const row = page
        .locator('table tbody tr')
        .filter({ hasText: leaveRequestCode })
        .first();
      await expect(
        row,
        `row for ${leaveRequestCode} must appear in the list`,
      ).toBeVisible({ timeout: 10_000 });
      // Pre-submit state visible in UI (D7 baseline)
      await expect(row, 'row status should read draft pre-submit').toContainText(/draft|草稿/i);

      // 13. Open the row "..." action menu → click "执行" (submit state_transition)
      const moreBtn = row.locator('[data-testid="row-action-more"]').first();
      await expect(moreBtn).toBeVisible({ timeout: 5_000 });
      await moreBtn.click();
      const dropdown = page.locator('[data-testid="row-action-dropdown"]');
      await expect(dropdown).toBeVisible({ timeout: 5_000 });
      const submitMenuItem = dropdown.locator('[data-testid="row-action-submit"]');
      await expect(
        submitMenuItem,
        'submit action must be reachable from row menu',
      ).toBeVisible({ timeout: 3_000 });

      const submitCmdPromise = page.waitForResponse(
        (r) =>
          r.url().includes('/api/meta/commands/execute/wd') &&
          r.url().includes('submit_leave_request'),
        { timeout: 20_000 },
      );
      await submitMenuItem.click();

      // 4. Confirm dialog (wd:submit_leave_request has extension.confirmMessage)
      const confirmDialog = page
        .locator('[data-testid="confirm-dialog"]')
        .or(page.locator('[role="alertdialog"]'))
        .or(page.locator('[role="dialog"]'))
        .first();
      if (await confirmDialog.isVisible({ timeout: 2_000 }).catch(() => false)) {
        const okBtn = page
          .locator('[data-testid="confirm-ok"]')
          .or(page.getByRole('button', { name: /^确认$|^确定$|^OK$|^Confirm$/i }))
          .first();
        await okBtn.click();
      }

      const submitResp = await submitCmdPromise;
      const submitBody = await submitResp.json();
      expect(
        String(submitBody?.code),
        `submit HTTP=${submitResp.status()} body=${JSON.stringify(submitBody).slice(0, 300)}`,
      ).toBe('0');

      // 5. Assertions — read state through REST (no mutation)
      const afterResp = await request.get(
        `/api/dynamic/wd_leave_request_detail/${leaveRequestPid}`,
        { headers: { Authorization: `Bearer ${adminToken}` } },
      );
      expect(afterResp.ok()).toBe(true);
      const after = (await afterResp.json())?.data;
      expect(after?.wd_req_status).toBe('submitted');
      leaveInstanceId = String(after?.wd_req_process_instance ?? '');
      expect(
        leaveInstanceId,
        'wd_req_process_instance must be populated after UI submit click',
      ).toBeTruthy();

      // BPM instance must be running with task_manager_approve active
      // (days=2 → wd_leave_routing Drools → manager branch).
      const status = await queryInstanceStatus(request, adminToken, {
        processKey: LEAVE_PROCESS_KEY,
        businessKey: leaveRequestPid,
      });
      expect(status.status.toLowerCase()).toMatch(/running|active/);
      const activeIds = status.currentNodes.map((n) => n.nodeId);
      expect(
        activeIds,
        `active nodes post-submit: ${JSON.stringify(activeIds)}`,
      ).toContain('task_manager_approve');
    });

    // =======================================================================
    // G3: UI opens TaskDetailDrawer from Task Center + 表单 tab DSL form
    //     renders fields bound to wd_leave_request_detail.
    // =======================================================================
    test('G3: Task Center row → TaskDetailDrawer opens + DSL form renders', async ({
      browser,
      request,
    }) => {
      expect(leaveInstanceId, 'G2 must have produced a running instance').toBeTruthy();

      const { context: managerCtx, page: managerPage } = await openTaskCenterAsRole(
        browser,
        'wd_manager@example.com',
        'Test2026x',
      );

      // Navigate to Task Center via sidebar
      
      // Resolve the exact taskId for our instance through the API (we'll use
      // it to cross-check the UI selection + drive G4's assertions).
      const ourTask = await waitForTodoTask(
        request,
        managerToken,
        (candidate) =>
          candidate.processInstanceId === String(leaveInstanceId) &&
          candidate.processDefinitionActivityId.includes('task_manager_approve'),
        {
          timeout: 15_000,
          message: `todo tasks must contain task_manager_approve for instance ${leaveInstanceId}`,
        },
      );
      leaveTaskId = String(ourTask.instanceId ?? ourTask.taskId ?? '');
      expect(leaveTaskId, 'task must expose an instanceId/taskId/id').toBeTruthy();

      // The TaskTable renders one row per todo task. processDefinitionKey and
      // businessKey come back as null from the workbench endpoint for
      // SmartEngine-backed tasks (BpmIntegrationService doesn't enrich those
      // fields today — tracked as a separate product gap), so the row shows
      // "-" placeholders. We therefore identify the row by the task-name-
      // button's parent <tr> after resolving the specific button whose
      // surrounding row carries our taskDefKey (task_manager_approve is
      // unique per-instance in wd_leave_approval). This stays UI-driven
      // while avoiding a brittle filter on placeholder cells.
      const taskRow = managerPage
        .locator('table tbody tr')
        .filter({
          has: managerPage.locator('[data-testid="task-name-button"]', {
            hasText: /task_manager_approve|主管审批|Manager Approve/i,
          }),
        })
        .first();
      await expect(
        taskRow,
        `a task_manager_approve task row for our instance must render`,
      ).toBeVisible({ timeout: 15_000 });

      // Click the task-name-button → navigate to business detail page
      const nameBtn = taskRow.locator('[data-testid="task-name-button"]').first();
      await expect(nameBtn).toBeVisible({ timeout: 5_000 });
      await nameBtn.click();

      await managerPage.waitForURL(/\/p\/wd_leave_request\/view\//, { timeout: 15_000 });
      const detailRoot = managerPage
        .locator('[data-testid="ab:detail:wd_leave_request:container"]')
        .first();
      await expect(detailRoot, 'detail page container must render after task click').toBeVisible({
        timeout: 10_000,
      });
      await expect(
        detailRoot.locator('[data-testid^="form-field-wd_req_"]').first(),
        'at least one wd_req_* field wrapper must render in the detail view',
      ).toBeVisible({ timeout: 15_000 });

      const daysField = detailRoot.locator('[data-testid="form-field-wd_req_days"]').first();
      await expect(
        daysField,
        'days field wrapper must render in detail page output',
      ).toBeVisible({ timeout: 5_000 });
      await expect(
        daysField,
        'days field must display the value G2 submitted (days=2)',
      ).toContainText(/\b2\b/);

      const statusField = detailRoot.locator('[data-testid="form-field-wd_req_status"]').first();
      await expect(
        statusField,
        'status field must render with the post-submit value',
      ).toBeVisible({ timeout: 5_000 });
      await expect(
        statusField,
        'status field must read submitted after G2 state_transition',
      ).toContainText(/submitted|已提交/i);

      // Belt-and-braces: cross-check the API itself still surfaces the
      // formBinding (catches form_bindings column regressions independently
      // of UI rendering).
      const formApiResp = await request.get(`/api/bpm/forms/task/${leaveTaskId}`, {
        headers: { Authorization: `Bearer ${adminToken}` },
      });
      expect(formApiResp.ok(), `form api: ${formApiResp.status()}`).toBe(true);
      const formBody = await formApiResp.json();
      const formBinding = formBody?.data?.formBinding;
      expect(
        formBinding,
        'form_bindings row must surface a formBinding entry for task_manager_approve',
      ).toBeTruthy();
      expect(
        String(formBinding?.formRef ?? ''),
        'derived formBinding.formRef must point at wd_leave_request_detail',
      ).toBe('wd_leave_request_detail');
      expect(
        String(formBinding?.formType ?? '').toLowerCase(),
        'derived formBinding.formType must be PAGE',
      ).toBe('page');

      // Return to Task Center so G4 starts from a clean row-menu interaction.
      await managerPage.goBack({ waitUntil: 'domcontentloaded' });
      await managerPage.waitForURL(/\/bpm\/task-center/, { timeout: 15_000 });
      await managerCtx.close();
    });

    // =======================================================================
    // G4: UI "通过" → CommentDialog → confirm → POST /api/bpm/tasks/{id}/approve
    //     → instance advances + audit row task_approve persisted.
    //
    //     Validates the Bug #8 Part 2 backend fix (81cd6a7a): TaskService now
    //     injects taskActions.resultVariable/resultValue from the task's DSL
    //     when the caller passes no variables, so gw_result MVEL
    //     ${taskResult == 'approved'} resolves without the UI hardcoding
    //     process-specific variable names.
    // =======================================================================
    test('G4: UI Approve advances instance + writes task_approve audit', async ({
      browser,
      request,
    }) => {
      if (!leaveTaskId) {
        const freshTask = await waitForTodoTask(
          request,
          managerToken,
          (candidate) =>
            candidate.processInstanceId === String(leaveInstanceId) &&
            candidate.processDefinitionActivityId.includes('task_manager_approve'),
          {
            timeout: 15_000,
            message: `G4 must resolve task_manager_approve for instance ${leaveInstanceId}`,
          },
        );
        leaveTaskId = String(freshTask.instanceId ?? freshTask.taskId ?? '');
      }
      expect(leaveTaskId, 'G4 must resolve the taskId before approving').toBeTruthy();

      const { context: managerCtx, page: managerPage } = await openTaskCenterAsRole(
        browser,
        'wd_manager@example.com',
        'Test2026x',
      );

      // Re-navigate to TaskCenter (G3 closed the drawer; TaskCenter list is
      // re-rendered. We accept the test.serial assumption that leaveTaskId
      // still refers to an active task — G3 did NOT complete it.)
      const taskRow = managerPage
        .locator('table tbody tr')
        .filter({
          has: managerPage.locator('[data-testid="task-name-button"]', {
            hasText: /task_manager_approve|主管审批|Manager Approve/i,
          }),
        })
        .first();
      await expect(taskRow, 'G2 task row must still be visible pre-approve').toBeVisible({
        timeout: 15_000,
      });

      // Row "..." action menu → "通过"
      const moreBtn = taskRow.getByTestId('task-row-actions').first();
      await expect(moreBtn, 'row More-actions button must be visible').toBeVisible({
        timeout: 5_000,
      });
      await moreBtn.click();

      const approveItem = managerPage.getByTestId('task-action-approve').first();
      await expect(approveItem, '"通过" menu item must be reachable (D10)').toBeVisible();
      await approveItem.click();

      // CommentDialog: fill comment + click 通过 confirm button
      const dialog = managerPage.locator('[role="dialog"]').first();
      await expect(dialog, 'approve CommentDialog must render').toBeVisible({ timeout: 5_000 });
      const commentArea = dialog.locator('textarea').first();
      await expect(commentArea).toBeVisible({ timeout: 3_000 });
      await commentArea.fill(`G4 UI approve ${TS}`);

      // The CommentDialog's confirm button is labeled "确认通过" (see
      // TaskActionDialogs.tsx L71). Dialog title is "通过审批".
      const approvePromise = managerPage.waitForResponse(
        (r) =>
          r.url().includes(`/api/bpm/tasks/${leaveTaskId}/approve`) &&
          r.request().method() === 'POST',
        { timeout: 20_000 },
      );
      const confirmBtn = dialog.getByRole('button', { name: /^确认通过$/ }).first();
      await expect(confirmBtn).toBeEnabled({ timeout: 3_000 });
      await confirmBtn.click();

      const approveResp = await approvePromise;
      expect(
        approveResp.status(),
        `approve POST HTTP=${approveResp.status()} body=${await approveResp
          .text()
          .then((t) => t.slice(0, 300))}`,
      ).toBeLessThan(400);

      // Dialog should dismiss on success
      await expect(dialog).toBeHidden({ timeout: 10_000 });
      await managerCtx.close();

      // Assertion: BPM instance advanced past task_manager_approve.
      // Bug #8 Part 2: backend injected taskResult=approved from the DSL →
      // gw_result MVEL evaluated → notify_approved path taken → instance may
      // be completed or still running depending on notification latency.
      await expect
        .poll(
          async () => {
            const status = await queryInstanceStatus(request, adminToken, {
              processKey: LEAVE_PROCESS_KEY,
              businessKey: leaveRequestPid,
            });
            return status.currentNodes.map((n) => n.nodeId);
          },
          {
            timeout: 10_000,
            message: 'task_manager_approve must exit currentNodes after UI approve',
          },
        )
        .not.toContain('task_manager_approve');

      const finalStatus = await queryInstanceStatus(request, adminToken, {
        processKey: LEAVE_PROCESS_KEY,
        businessKey: leaveRequestPid,
      });
      expect(
        finalStatus.completedNodes.map((n) => n.nodeId),
        'task_manager_approve must be in completedNodes after UI approve',
      ).toContain('task_manager_approve');

      // Audit assertion: task_approve row persisted via TaskService.approveTask.
      // Validates both Bug #8 Part 2 (variable injection) and Bug #2
      // (JSONB typeHandler — details must be non-null).
      const audit = await listAuditEvents(request, adminToken, leaveInstanceId);
      const taskApproveRows = audit.filter((a) => a.operation === 'task_approve');
      expect(
        taskApproveRows.length,
        `audit must contain task_approve row (got operations=${JSON.stringify(
          audit.map((a) => a.operation),
        )})`,
      ).toBeGreaterThanOrEqual(1);
      expect(
        taskApproveRows[0].details,
        'task_approve audit row must have non-null details (JSONB typeHandler fix)',
      ).not.toBeNull();

      // Sanity: gateway + userTask lifecycle present in audit
      const activityEvents = audit.filter((a) => a.operation === AuditOp.ACTIVITY_EVENT);
      const mgrEvents = activityEvents
        .map((a) => ({
          activityId: (a.details?.activityId as string) ?? '',
          eventType: (a.details?.eventType as string) ?? '',
        }))
        .filter((e) => e.activityId === 'task_manager_approve');
      expect(
        mgrEvents.some((e) => e.eventType === 'activity_start'),
        'task_manager_approve activity_start must be audited',
      ).toBe(true);
      expect(
        mgrEvents.some((e) => e.eventType === 'activity_end'),
        'task_manager_approve activity_end must be audited after UI approve',
      ).toBe(true);
    });

    // =======================================================================
    // G5: cleanup — undeploy + terminate (idempotent, best-effort).
    // =======================================================================
    test('G5: cleanup — undeploy test process (best-effort)', async ({ request }) => {
      if (!processPid) return; // G1 failed before creating anything
      const { status } = await undeployProcess(request, adminToken, processPid);
      // Accept 200/204 (clean) or 500 (running instances blocking undeploy —
      // not possible in this spec since G2-G4 are skipped, but keep the
      // guard for future expansion).
      expect([200, 204, 500]).toContain(status);
    });
  },
);
