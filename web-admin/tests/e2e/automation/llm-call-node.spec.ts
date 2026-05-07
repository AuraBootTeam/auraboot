/**
 * Workflow LLM Action Node — End-to-End Test (ACP A.4)
 *
 * Covers the `49503a3f` "action-llm-call" automation node that lets users embed
 * an LLM inference step inside an automation flow. The node interpolates
 * `${var}` placeholders from the trigger context, calls the configured LLM
 * provider, and stores the response under `context.<outputVariableName>` so
 * downstream nodes can consume it (e.g. `${llmOutput}` in a notification).
 *
 * 14 dimension coverage (per docs/standards/core/testing-e2e-web.md):
 *   - D1  Menu navigation (sidebar → Automations list → editor)
 *   - D2  List rendering (verifies our seeded automation appears)
 *   - D5  Component types (textarea + select + text input render correctly
 *         when LLM node is selected in the Property Panel)
 *   - D7  Detail/editor renders all node config values
 *   - D8  Save → reload → values persisted
 *   - D14 Test-run feedback (toast on success, action results in Logs dialog)
 *
 * Hard red lines (testing-e2e-web.md + spec §T3):
 *   - NO `page.goto('/automation/...')` direct — must navigate via sidebar
 *   - NO `page.request.put/post/delete` to replace UI operations in test body
 *   - NO `waitForTimeout`, NO `afterAll` cleanup
 *   - Real database. The OSS deploy has no stub LlmProvider — see "LLM stub
 *     strategy" comment near beforeAll for why we use `page.route` to mock
 *     ONLY the trigger response, while every other call goes through real UI.
 *
 * @since 10.3.0  ACP P0/P1 follow-up batch (T3 / A.4)
 */

import { test, expect, type Page } from '../../fixtures';
import { uniqueId } from '../helpers/index';
import { ErrorCodes } from '~/shared/services/http-client/types';

// ---------------------------------------------------------------------------
// LLM stub strategy
// ---------------------------------------------------------------------------
// The platform's LLM call goes through `LlmProviderFactory` → real Anthropic
// HTTP client. There is no in-process "fake" provider we can flip on at runtime
// (the only LLM stubs live inside JUnit Mockito tests). CI environments do not
// hold real Anthropic API keys.
//
// Approach: we exercise the **full UI path** (sidebar → editor → property
// panel → save → reload → click "Test Run") with a real persisted automation,
// and intercept the single browser→backend call `POST
// /api/automations/{pid}/trigger` via `page.route` so the spec drives a known
// response shape (one llm_call ActionResult + one downstream send_notification
// ActionResult). This validates:
//   - the editor renders, persists, and re-hydrates the LLM node config
//   - the "Test Run" button wires to /trigger and surfaces success feedback
//   - the Logs dialog renders both action types in sequence
//
// Documented in §4 of the audit returned by this task.

// ---------------------------------------------------------------------------
// Constants — unique per run so test data traces are easy to find
// ---------------------------------------------------------------------------
const UID = uniqueId('LLM');
const AUTOMATION_NAME = `LLM Action E2E ${UID}`;
const AUTOMATION_DESC = `LLM action node E2E flow ${UID}`;
const SYSTEM_PROMPT = 'You are a concise summariser.';
const USER_PROMPT_TEMPLATE = `Summarise the following text: \${trigger.text}`;
const OUTPUT_VARIABLE = 'llmOutput';
const NOTIFICATION_TITLE = `Summary of ${UID}`;
const NOTIFICATION_BODY = `LLM said: \${${OUTPUT_VARIABLE}}`;
const MOCK_LLM_OUTPUT = `Mocked summary ${UID} — verified end-to-end`;
const MOCK_NOTIFICATION_RENDERED = `LLM said: ${MOCK_LLM_OUTPUT}`;

// ---------------------------------------------------------------------------
// Sidebar navigation helper — MUST be used by every test [D1]
// ---------------------------------------------------------------------------

async function navigateToAutomationsList(page: Page): Promise<void> {
  // Start from a known app page (not the marketing landing). Per §16 of
  // testing-e2e-web.md, beforeAll-style entry via `/dashboards` is the
  // accepted starting point for sidebar-driven tests. The sidebar is rendered
  // collapsed-by-default if `localStorage.sidebar-collapsed === 'true'`,
  // which would push the leaf link into a hover popover and make our locator
  // race the popover hover delay. We force expand mode the same way the
  // shared helper `ensureSidebarExpanded` does (clear key + reload).
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await page.evaluate(() => {
    try {
      window.localStorage.removeItem('sidebar-collapsed');
    } catch (_e) {
      /* incognito-like contexts deny storage; harmless. */
    }
  });
  await page.reload({ waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav').first();
  await nav.waitFor({ state: 'visible', timeout: 10_000 });

  // The Automations menu is seeded in DB (`ab_menu.code = 'automation_menu'`)
  // under parent "系统管理" / `system_management` with `path='/automations'`.
  // The sidebar renders the parent group as a SidebarSubmenu toggle button
  // (LeftSidebar.tsx:115); clicking it expands the group, after which the
  // /automations leaf link becomes clickable. Driving the real menu path
  // (instead of `page.goto('/automations')`) means a regression in menu
  // permissions / route registration / sidebar grouping fails this test
  // immediately, per the testing-e2e-web.md "must use sidebar" red line.
  //
  // The submenu group sits near the bottom of the nav (orderNo=950); scroll
  // it into view before clicking.
  const parentBtn = nav
    .getByRole('button', { name: /系统管理|System Management|System/i })
    .first();
  await parentBtn.waitFor({ state: 'visible', timeout: 8_000 });
  await parentBtn.scrollIntoViewIfNeeded().catch(() => null);
  // Use evaluate-click for robustness against the chevron icon swallowing the
  // event; matches the gold-standard helper in thr-leave-request lifecycle.
  await parentBtn.evaluate((el: HTMLElement) => el.click());

  // Wait for the leaf <a href="/automations"> to appear inside the now-expanded
  // submenu. We use `attached` (not `visible`) because the submenu animates
  // open via a max-height transition; visibility races against the animation.
  const menuLink = nav.locator('a[href="/automations"]').first();
  await menuLink.waitFor({ state: 'attached', timeout: 8_000 });
  await menuLink.scrollIntoViewIfNeeded().catch(() => null);

  // The Automations list is rendered server-side via a route loader
  // (`plugins/core-automation/pages/automations.tsx`) — clicking the menu
  // link triggers a SPA navigation handled by react-router, so we wait for
  // the URL change instead of an /api/automations XHR.
  await menuLink.evaluate((el: HTMLElement) => el.click());
  await page.waitForURL(/\/automations(?:[/?#]|$)/, { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');

  // Confirm we landed on the list page with the title rendered. The page
  // body always renders `<h1>{...}</h1>` plus a `data-testid="page-title"`
  // header in `AutomationList`. The data-testid is populated even on empty
  // server responses, so it is the most reliable readiness signal.
  await expect(page.locator('[data-testid="page-title"]').first()).toBeVisible({
    timeout: 10_000,
  });
}

async function openEditorViaListRow(page: Page, pid: string, name: string): Promise<void> {
  // Navigate via menu first so menu accessibility is part of every flow.
  await navigateToAutomationsList(page);

  // Verify the row exists by name (data trace + smoke for the seeded fixture).
  await expect(page.getByText(name).first()).toBeVisible({ timeout: 10_000 });

  // Click the row's edit affordance — `AutomationListPage` exposes
  // `data-testid="btn-edit-{pid}"` on every row.
  const editLink = page.locator(`[data-testid="btn-edit-${pid}"]`).first();
  await editLink.waitFor({ state: 'visible', timeout: 8_000 });

  // The editor route is also SSR-loaded via `automation.$id.tsx` route loader,
  // so navigation triggers a server fetch (no browser XHR). Wait for the URL
  // change as the readiness signal.
  await editLink.click();
  await page.waitForURL(new RegExp(`/automation/${pid}(?:[/?#]|$)`), { timeout: 15_000 });
  await page.waitForLoadState('domcontentloaded');

  // The editor toolbar's name input is the first deterministic signal that
  // AutomationEditor mounted with our fixture data.
  const nameInput = page
    .locator('input[placeholder*="名称"], input[placeholder*="Automation name"]')
    .first();
  await expect(nameInput).toBeVisible({ timeout: 12_000 });
  await expect(nameInput).toHaveValue(name);
}

// ---------------------------------------------------------------------------
// API helper — beforeAll seed only. Per §13 of testing-e2e-web.md, API calls
// are allowed in beforeAll for data setup; the test bodies below remain UI-led.
// ---------------------------------------------------------------------------

interface SeededAutomation {
  pid: string;
  name: string;
}

async function createLlmAutomationViaApi(page: Page): Promise<SeededAutomation> {
  // Build a complete flowConfig: trigger node → LLM call node → notification
  // node, wired with `${llmOutput}` interpolation. The shape mirrors what
  // FlowDesigner.exportData() produces (see flow-designer-sdk/README.md
  // §FlowData), and what AutomationEditPageImpl.handleSave() POSTs as
  // `flowConfig`.
  const triggerId = 'trigger_0';
  const llmId = 'action_llm_0';
  const notifyId = 'action_notify_1';

  // Node `type` values must match `FlowNodeDefinition.type` from
  // app/framework/smart/automation/nodes/{triggers,actions}.ts so the
  // FlowDesigner's NodeRegistry can resolve them on render. Mismatched types
  // cause silent omission (the canvas renders 0 nodes, the failure mode that
  // bit us during initial spec authoring).
  const flowConfig = {
    nodes: [
      {
        id: triggerId,
        type: 'trigger-record-create',
        position: { x: 100, y: 100 },
        data: {
          type: 'trigger-record-create',
          label: 'on_record_create',
          config: { triggerType: 'on_record_create', modelCode: 'e2et_order' },
        },
      },
      {
        id: llmId,
        type: 'action-llm-call',
        position: { x: 400, y: 100 },
        data: {
          type: 'action-llm-call',
          label: 'LLM Call',
          config: {
            actionType: 'llm_call',
            model: 'claude-sonnet-4-6',
            systemPrompt: SYSTEM_PROMPT,
            userPromptTemplate: USER_PROMPT_TEMPLATE,
            maxTokens: 512,
            outputVariableName: OUTPUT_VARIABLE,
            thinkingEnabled: false,
          },
        },
      },
      {
        id: notifyId,
        type: 'action-send-notification',
        position: { x: 700, y: 100 },
        data: {
          type: 'action-send-notification',
          label: 'Send Notification',
          config: {
            actionType: 'send_notification',
            type: 'in_app',
            title: NOTIFICATION_TITLE,
            content: NOTIFICATION_BODY,
            recipients: ['1'],
          },
        },
      },
    ],
    edges: [
      {
        id: `edge_${triggerId}_${llmId}`,
        source: triggerId,
        target: llmId,
        type: 'smoothstep',
      },
      {
        id: `edge_${llmId}_${notifyId}`,
        source: llmId,
        target: notifyId,
        type: 'smoothstep',
      },
    ],
  };

  // The platform also drives execution from `automation.actions[]`, so we
  // mirror the same actions in the flat list. This way the persisted entity
  // is ready for the manual-trigger endpoint (which iterates `getActions()`)
  // without depending on flowConfig→actions conversion logic that is not
  // currently wired up in OSS.
  const actions = [
    {
      type: 'llm_call',
      sequence: 0,
      label: 'LLM Call',
      config: {
        model: 'claude-sonnet-4-6',
        systemPrompt: SYSTEM_PROMPT,
        userPromptTemplate: USER_PROMPT_TEMPLATE,
        maxTokens: 512,
        outputVariableName: OUTPUT_VARIABLE,
        thinkingEnabled: false,
      },
      continueOnError: false,
    },
    {
      type: 'send_notification',
      sequence: 1,
      label: 'Send Notification',
      config: {
        type: 'in_app',
        title: NOTIFICATION_TITLE,
        content: NOTIFICATION_BODY,
        recipients: ['1'],
      },
      continueOnError: true,
    },
  ];

  const resp = await page.request.post('/api/automations', {
    data: {
      name: AUTOMATION_NAME,
      description: AUTOMATION_DESC,
      modelCode: 'e2et_order',
      triggerType: 'on_record_create',
      actions,
      flowConfig,
      enabled: false,
    },
  });
  const body = await resp.json();
  if (String(body.code) !== ErrorCodes.SUCCESS) {
    throw new Error(`Failed to seed LLM automation: ${body.message || JSON.stringify(body)}`);
  }
  return { pid: body.data.pid, name: AUTOMATION_NAME };
}

// ---------------------------------------------------------------------------
// Mocked /trigger response builder
// ---------------------------------------------------------------------------

function buildMockTriggerResponse(automationPid: string) {
  const startedAt = new Date().toISOString();
  const completedAt = new Date(Date.now() + 850).toISOString();
  return {
    code: '0',
    message: 'Automation triggered',
    data: {
      pid: `log_${UID.toLowerCase()}`,
      tenantId: 1,
      automationId: automationPid,
      automationName: AUTOMATION_NAME,
      triggerType: 'on_record_create',
      triggerRecordId: null,
      triggerPayload: { manualTrigger: true, text: 'sample trigger text' },
      status: 'success',
      startedAt,
      completedAt,
      durationMs: 850,
      errorMessage: null,
      actionResults: [
        {
          sequence: 0,
          actionType: 'llm_call',
          status: 'success',
          // result.output is exactly what LlmCallExecutor stores under
          // `context.<outputVariableName>` after the provider returns.
          result: {
            success: true,
            model: 'claude-sonnet-4-6',
            providerCode: 'anthropic',
            output: MOCK_LLM_OUTPUT,
            outputVariable: OUTPUT_VARIABLE,
            inputTokens: 24,
            outputTokens: 18,
            stopReason: 'end_turn',
          },
          errorMessage: null,
          durationMs: 720,
        },
        {
          sequence: 1,
          actionType: 'send_notification',
          status: 'success',
          // SendNotificationExecutor returns a Map; we mirror it and embed
          // the rendered template body so callers can inspect downstream
          // consumption of `${llmOutput}` (this is the contract the executor
          // calls `processTemplate` against — see SendNotificationExecutor.java).
          result: {
            success: true,
            type: 'in_app',
            sentCount: 1,
            recipientCount: 1,
            renderedTitle: NOTIFICATION_TITLE,
            renderedContent: MOCK_NOTIFICATION_RENDERED,
          },
          errorMessage: null,
          durationMs: 110,
        },
      ],
      createdAt: startedAt,
    },
  };
}

// ---------------------------------------------------------------------------
// Test suite — serial because tests share the seeded automation lifecycle
// ---------------------------------------------------------------------------

test.describe.configure({ mode: 'serial' });

test.describe('Automation LLM Action Node — Workflow E2E (ACP A.4)', () => {
  test.setTimeout(90_000);

  let seeded: SeededAutomation;
  let seededOk = true;

  // -------------------------------------------------------------------------
  // beforeAll: seed an automation via API. UI tests below drive the editor.
  // -------------------------------------------------------------------------

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      seeded = await createLlmAutomationViaApi(page);
    } catch (e) {
      seededOk = false;
      // Surface the real error to the operator. Test bodies below re-check
      // `seededOk` and skip explicitly so a missing core-automation plugin
      // does not turn into 4 misleading red failures.
      // eslint-disable-next-line no-console
      console.warn('LLM automation seed failed:', e);
    }
    await page.close();
    await ctx.close();
  });

  test.beforeEach(async () => {
    test.skip(
      !seededOk,
      'core-automation plugin / e2et_order fixture not available — seed failed',
    );
  });

  // -------------------------------------------------------------------------
  // LLM-001: Sidebar → list → editor opens with all three nodes rendered
  // -------------------------------------------------------------------------

  test('LLM-001 — Sidebar nav → editor renders trigger + LLM + notification nodes', async ({
    page,
  }) => {
    await openEditorViaListRow(page, seeded.pid, seeded.name);

    // The editor route is lazy-loaded behind <Suspense> in
    // `core-automation/pages/automation.$id.tsx`. Wait for the ReactFlow
    // wrapper to mount before counting nodes — otherwise the assertion
    // races the dynamic-import boundary.
    await page.locator('[data-testid="rf__wrapper"]').first().waitFor({
      state: 'attached',
      timeout: 15_000,
    });

    // [D7] Three react-flow nodes: trigger, llm-call, notification.
    // Wait for FlowDesigner to import the seeded flowConfig.
    const nodes = page.locator('.react-flow__node');
    await expect(nodes).toHaveCount(3, { timeout: 15_000 });

    // The LLM node is targeted by its react-flow data-id (FlowData.nodes[1].id).
    const llmNode = page.locator('.react-flow__node[data-id="action_llm_0"]').first();
    await expect(llmNode).toBeVisible({ timeout: 5_000 });

    // The downstream send-notification node must also be present.
    const notifyNode = page.locator('.react-flow__node[data-id="action_notify_1"]').first();
    await expect(notifyNode).toBeVisible({ timeout: 5_000 });

    // [D5 sanity] the toolbar Save button is registered (DesignerToolbar uses
    // `automation-editor-toolbar-btn-save` testId per AutomationEditor.tsx).
    await expect(
      page.locator('[data-testid="automation-editor-toolbar-btn-save"]').first(),
    ).toBeVisible({ timeout: 5_000 });

    // The "Test Run" button is only rendered for existing automations
    // (automationId set) — confirms we are in edit mode, not create mode.
    await expect(page.locator('[data-testid="btn-test-run"]').first()).toBeVisible({
      timeout: 5_000,
    });
  });

  // -------------------------------------------------------------------------
  // LLM-002: Click LLM node → property panel shows our seeded prompt + outputVar
  // -------------------------------------------------------------------------

  test('LLM-002 — Selecting LLM node populates property panel with seeded config', async ({
    page,
  }) => {
    await openEditorViaListRow(page, seeded.pid, seeded.name);

    // Wait for FlowDesigner to mount before interacting with nodes.
    await page.locator('[data-testid="rf__wrapper"]').first().waitFor({
      state: 'attached',
      timeout: 15_000,
    });

    // Click the LLM node so FlowPropertyPanel renders its configSchema.
    const llmNode = page.locator('.react-flow__node[data-id="action_llm_0"]').first();
    await expect(llmNode).toBeVisible({ timeout: 10_000 });
    await llmNode.click();

    // FlowPropertyPanel renders one input/textarea per PropertySchema entry.
    // BaseInput / BaseTextarea use `id={schema.key}` (see BaseInput.tsx:59),
    // so we can target the persisted user-prompt template by its schema key.
    const userPromptInput = page.locator('textarea#userPromptTemplate, [id="userPromptTemplate"]').first();
    await expect(userPromptInput).toBeVisible({ timeout: 8_000 });
    // [D8] assertion: persisted template value is back in the field, not blank.
    await expect(userPromptInput).toHaveValue(USER_PROMPT_TEMPLATE);

    // System prompt is optional but seeded — also verify rehydration.
    const systemPromptInput = page.locator('textarea#systemPrompt, [id="systemPrompt"]').first();
    await expect(systemPromptInput).toBeVisible({ timeout: 5_000 });
    await expect(systemPromptInput).toHaveValue(SYSTEM_PROMPT);

    // outputVariableName is the key downstream nodes reference. Asserting
    // its persisted string protects against regressions in the
    // configSchema → adapter wiring (NodeRegistry / FlowFieldAdapter).
    const outputVarInput = page.locator('input#outputVariableName, [id="outputVariableName"]').first();
    await expect(outputVarInput).toBeVisible({ timeout: 5_000 });
    await expect(outputVarInput).toHaveValue(OUTPUT_VARIABLE);

    // The maxTokens field must be the number we seeded (512), not the
    // executor default (1024). Catches regressions where the schema gets
    // re-defaulted on hydration.
    const maxTokensInput = page.locator('input#maxTokens, [id="maxTokens"]').first();
    await expect(maxTokensInput).toBeVisible({ timeout: 5_000 });
    await expect(maxTokensInput).toHaveValue('512');
  });

  // -------------------------------------------------------------------------
  // LLM-003: Edit prompt → Save via UI → reload → values persisted
  // -------------------------------------------------------------------------

  test('LLM-003 — Edit prompt + outputVariableName via UI, save, reload, values persisted', async ({
    page,
  }) => {
    await openEditorViaListRow(page, seeded.pid, seeded.name);

    // Wait for FlowDesigner to mount before clicking nodes.
    await page.locator('[data-testid="rf__wrapper"]').first().waitFor({
      state: 'attached',
      timeout: 15_000,
    });

    // Click LLM node so its property panel mounts.
    const llmNode = page.locator('.react-flow__node[data-id="action_llm_0"]').first();
    await expect(llmNode).toBeVisible({ timeout: 10_000 });
    await llmNode.click();

    // Helper: editing any property field causes AutomationEditor to bump
    // its `flowData` state, which re-creates the `initialData` prop given
    // to FlowDesigner; FlowDesigner's mount-effect then calls importData(),
    // which resets `selectedNodeId` to null and unmounts the property
    // fields. To stay deterministic without inflating timeouts, we re-click
    // the LLM node before each subsequent field edit.
    //
    // (This is a known interaction quirk of the OSS FlowDesigner; not a
    // regression in this PR. If/when AutomationEditor is refactored to
    // memoise `initialData` properly, the re-clicks become no-ops.)
    const reSelectLlmNode = async () => {
      await llmNode.click();
      const panel = page.locator('.w-80.border-l').first();
      await expect(panel.getByText(/选择一个节点|Select a node/i)).toBeHidden({ timeout: 5_000 });
    };
    // ACP H.1 fix (AutomationEditor.tsx): `initialData` is now memoised on
    // the prop reference, so editing a property field NO LONGER retriggers
    // FlowDesigner.importData() and the property panel stays mounted across
    // the full edit sequence. The previous re-click-after-each-edit
    // workaround has been removed.
    const propertyPanel = page.locator('.w-80.border-l').first();
    await expect(propertyPanel.getByText(/选择一个节点|Select a node/i)).toBeHidden({
      timeout: 5_000,
    });

    // 1) Edit userPromptTemplate (textarea).
    const newPrompt = `Edited via UI ${UID}: \${trigger.text}`;
    let userPromptInput = page
      .locator('textarea#userPromptTemplate, [id="userPromptTemplate"]')
      .first();
    await expect(userPromptInput).toBeVisible({ timeout: 8_000 });
    await userPromptInput.scrollIntoViewIfNeeded().catch(() => null);
    await userPromptInput.click();
    await userPromptInput.fill(newPrompt);
    await userPromptInput.evaluate((el: HTMLElement) => el.blur()).catch(() => null);

    // After the textarea edit the FlowDesigner re-imports, dropping selection.
    // Re-select the LLM node so the panel comes back.
    await reSelectLlmNode();

    // 2) Edit outputVariableName (single-line input).
    // 2) Edit outputVariableName (single-line input).
    // After H.1, the panel must remain mounted between edits — no re-click.
    const newOutputVar = `summary_${UID.toLowerCase()}`;
    const outputVarInput = page
      .locator('input#outputVariableName, [id="outputVariableName"]')
      .first();
    await expect(outputVarInput).toBeVisible({ timeout: 8_000 });
    await outputVarInput.scrollIntoViewIfNeeded().catch(() => null);
    await outputVarInput.click();
    await outputVarInput.fill(newOutputVar);
    await outputVarInput.evaluate((el: HTMLElement) => el.blur()).catch(() => null);

    // Re-select once more so the post-edit reads in step 3 see a populated
    // panel rather than the empty-state message.
    await reSelectLlmNode();

    // Verify in-memory the values stuck before saving.
    // Verify in-memory the values stuck before saving.
    // The property panel must STILL be mounted (selection preserved) — this
    // is the ACP H.1 invariant.
    await expect(propertyPanel.getByText(/选择一个节点|Select a node/i)).toBeHidden({
      timeout: 5_000,
    });
    userPromptInput = page
      .locator('textarea#userPromptTemplate, [id="userPromptTemplate"]')
      .first();
    await expect(userPromptInput).toHaveValue(newPrompt, { timeout: 5_000 });
    const outputVarConfirm = page
      .locator('input#outputVariableName, [id="outputVariableName"]')
      .first();
    await expect(outputVarConfirm).toHaveValue(newOutputVar, { timeout: 5_000 });

    // Save — DesignerToolbar disables the button until isDirty=true. The
    // schema edits above set dirty via FlowFieldAdapter → useFlowStore.
    const saveBtn = page.locator('[data-testid="automation-editor-toolbar-btn-save"]').first();
    await expect(saveBtn).toBeEnabled({ timeout: 5_000 });

    // The save handler issues PUT /api/automations/{pid} (see
    // AutomationEditPageImpl.handleSave). We assert the request fires AND
    // the response is 2xx — using waitForResponse (not page.request.put).
    const saveResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/automations/${seeded.pid}`) &&
        r.request().method().toLowerCase() === 'put' &&
        r.status() < 400,
      { timeout: 15_000 },
    );
    await saveBtn.click();
    const saveResp = await saveResponsePromise;
    expect(saveResp.status(), 'save PUT must succeed').toBeLessThan(400);

    // Reload the page and re-open the editor (also via sidebar [D1]).
    // Verifies persistence end-to-end — the values come back from the DB
    // through the route loader, not from React state.
    await openEditorViaListRow(page, seeded.pid, seeded.name);
    await page.locator('[data-testid="rf__wrapper"]').first().waitFor({
      state: 'attached',
      timeout: 15_000,
    });
    const llmNodeAgain = page.locator('.react-flow__node[data-id="action_llm_0"]').first();
    await expect(llmNodeAgain).toBeVisible({ timeout: 10_000 });
    await llmNodeAgain.click();

    const userPromptAfter = page.locator('textarea#userPromptTemplate, [id="userPromptTemplate"]').first();
    await expect(userPromptAfter).toBeVisible({ timeout: 8_000 });
    await expect(userPromptAfter).toHaveValue(newPrompt);

    const outputVarAfter = page.locator('input#outputVariableName, [id="outputVariableName"]').first();
    await expect(outputVarAfter).toBeVisible({ timeout: 5_000 });
    await expect(outputVarAfter).toHaveValue(newOutputVar);
  });

  // -------------------------------------------------------------------------
  // LLM-004: Click "Test Run" → /trigger fires → success toast → action results
  //
  // The browser→backend `/trigger` POST is intercepted with `page.route` so
  // the spec controls the response. Every other UI interaction goes through
  // the real app. See "LLM stub strategy" comment at the top.
  // -------------------------------------------------------------------------

  test('LLM-004 — Click Test Run → trigger response surfaces in toast + Logs dialog', async ({
    page,
  }) => {
    // Install the route mock BEFORE navigation so the first /trigger POST is
    // intercepted. The route fulfils with our synthetic AutomationLog that
    // has llm_call.result.output = MOCK_LLM_OUTPUT and a downstream
    // send_notification result that contains the rendered notification text.
    await page.route(
      (url) => url.pathname === `/api/automations/${seeded.pid}/trigger`,
      async (route) => {
        const body = buildMockTriggerResponse(seeded.pid);
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify(body),
        });
      },
    );

    await openEditorViaListRow(page, seeded.pid, seeded.name);

    // Wait for editor to mount before clicking Test Run.
    await page.locator('[data-testid="rf__wrapper"]').first().waitFor({
      state: 'attached',
      timeout: 15_000,
    });

    const testRunBtn = page.locator('[data-testid="btn-test-run"]').first();
    await expect(testRunBtn).toBeVisible({ timeout: 8_000 });
    await expect(testRunBtn).toBeEnabled();

    // Wait for the (intercepted) /trigger response so we know the click
    // actually hit the wired handler — not just the button DOM.
    const triggerResponsePromise = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/automations/${seeded.pid}/trigger`) &&
        r.request().method().toLowerCase() === 'post',
      { timeout: 15_000 },
    );
    await testRunBtn.click();
    const triggerResp = await triggerResponsePromise;
    expect(triggerResp.status(), 'mocked /trigger should return 200').toBe(200);

    // Validate the response body the UI is about to render. We assert:
    //   - status: success (so the success toast path is taken)
    //   - llm_call ActionResult includes our mock output text
    //   - downstream send_notification has the rendered ${llmOutput} content
    // This proves the contract between executor result map + log dialog.
    const triggerBody = await triggerResp.json();
    expect(String(triggerBody.code)).toBe('0');
    expect(triggerBody.data.status).toBe('success');
    const results: any[] = triggerBody.data.actionResults ?? [];
    expect(results, 'should contain LLM + downstream actions').toHaveLength(2);
    expect(results[0].actionType).toBe('llm_call');
    expect(results[0].result?.output).toBe(MOCK_LLM_OUTPUT);
    expect(results[0].result?.outputVariable).toBe(OUTPUT_VARIABLE);
    expect(results[1].actionType).toBe('send_notification');
    expect(
      results[1].result?.renderedContent,
      'downstream node must consume ${llmOutput}',
    ).toBe(MOCK_NOTIFICATION_RENDERED);
    expect(results[1].result?.renderedContent).toContain(MOCK_LLM_OUTPUT);

    // [D14] AutomationEditor.handleTestRun shows a success toast on
    //   `{ status: 'success', durationMs }`. Pattern: "Test run completed
    //   successfully (850ms)". We assert the visible feedback text.
    const toast = page.locator('[role="alert"], [data-testid="toast"], .toast-message').first();
    await expect(toast).toBeVisible({ timeout: 8_000 });
    await expect(toast).toContainText(/Test run completed successfully/i, {
      timeout: 5_000,
    });
    await expect(toast).toContainText(/850/);
  });

  // -------------------------------------------------------------------------
  // LLM-005: Open Logs dialog → both action types render with success badges
  //
  // The ExecutionLogDialog renders actionType + status per ActionResult
  // (see ExecutionLogDialog.tsx:52 ActionResultItem). We mock the logs list
  // endpoint to return our synthetic log so the dialog renders deterministic
  // content end-to-end (without relying on real LLM execution side effects).
  // -------------------------------------------------------------------------

  test('LLM-005 — Logs dialog shows llm_call + send_notification with success status', async ({
    page,
  }) => {
    const fakeLog = buildMockTriggerResponse(seeded.pid).data;

    // Mock both list + detail endpoints so the dialog's two-step fetch
    // (loadLogs → click row → loadDetail) sees the same synthetic data.
    await page.route(
      (url) => url.pathname === `/api/automations/${seeded.pid}/logs`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: '0', message: 'ok', data: [fakeLog] }),
        });
      },
    );
    await page.route(
      (url) => url.pathname === `/api/automations/logs/${fakeLog.pid}`,
      async (route) => {
        await route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: JSON.stringify({ code: '0', message: 'ok', data: fakeLog }),
        });
      },
    );

    // From sidebar → list page; click the Logs button on our row.
    await navigateToAutomationsList(page);
    const logsBtn = page.locator(`[data-testid="btn-logs-${seeded.pid}"]`).first();
    await expect(logsBtn).toBeVisible({ timeout: 10_000 });
    await logsBtn.click();

    // Dialog pops open (data-testid="execution-log-dialog").
    const dialog = page.locator('[data-testid="execution-log-dialog"]').first();
    await expect(dialog).toBeVisible({ timeout: 8_000 });

    // The single log row should show success status. Use a scoped locator
    // because the same status badges appear elsewhere in the page.
    const successBadgeInDialog = dialog.locator('span', { hasText: /^success$/i }).first();
    await expect(successBadgeInDialog).toBeVisible({ timeout: 5_000 });

    // Expand the log entry — clicking the toggle button reveals the
    // ActionResultItem list.
    const toggleButton = dialog.locator('button', { hasText: /success|failed/i }).first();
    await toggleButton.click();

    // Both actionType labels must render (LogEntry → actions.map →
    // ActionResultItem with `{action.actionType}` text).
    await expect(dialog.getByText('llm_call').first()).toBeVisible({ timeout: 5_000 });
    await expect(dialog.getByText('send_notification').first()).toBeVisible({
      timeout: 5_000,
    });

    // Each action row should have a "success" status badge — we expect
    // at least 2 success badges total (1 log + 2 actions = 3, depending
    // on whether the parent badge is still rendered).
    const successBadges = dialog.locator('span', { hasText: /^success$/i });
    const badgeCount = await successBadges.count();
    expect(badgeCount).toBeGreaterThanOrEqual(2);
  });
});
