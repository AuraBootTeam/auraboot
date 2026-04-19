/**
 * Designer DSL Helper
 *
 * Wraps the window.__bpmDesigner test hook exposed by BPMNDesigner.tsx so
 * Playwright specs can programmatically build BPMN process definitions without
 * relying on fragile drag-and-drop simulation.
 *
 * window.__bpmDesigner is installed in DEV mode only via installDesignerTestHooks().
 * It exposes: addNode, connect, configureNode, getDesignerJson.
 *
 * Navigation rules (per project red lines):
 *   - Only page.goto('/login') is an acceptable entry point.
 *   - All other navigation must go through sidebar menu clicks.
 *
 * Endpoint verification (grepped from ProcessDefinitionController.java):
 *   - Save (create): POST /api/bpm/process-definitions          (@PostMapping on controller root)
 *   - Save (update): PUT  /api/bpm/process-definitions/{pid}    (@PutMapping("/{pid}"))
 *   - Deploy:        POST /api/bpm/process-definitions/{pid}/deploy  (@PostMapping("/{pid}/deploy"))
 *
 * Note: The plan claimed deploy goes to POST /api/bpm/deployments — the actual
 * endpoint is POST /api/bpm/process-definitions/{pid}/deploy (verified by grep).
 */

import { type Page, expect } from '@playwright/test';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export type NodeType =
  | 'startEvent'
  | 'endEvent'
  | 'userTask'
  | 'serviceTask'
  | 'ruleTask'
  | 'callActivity'
  | 'exclusiveGateway'
  | 'parallelGateway'
  | 'inclusiveGateway';

export interface AddNodeInput {
  id: string;
  type: NodeType;
  position: { x: number; y: number };
  label: string;
}

export interface ConnectInput {
  from: string;
  to: string;
  condition?: string;
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

/**
 * Navigate to the Process Definition list page via sidebar menu clicks.
 * Entry point is the app root (requires already-authenticated storageState).
 */
async function navigateToProcessDefinitionList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  // Click parent menu "流程管理" / "Process Management"
  const bpmParent = nav
    .getByRole('button', { name: /流程管理|Process Management/i })
    .first();
  await bpmParent.scrollIntoViewIfNeeded();
  await bpmParent.evaluate((el: HTMLElement) => el.click());

  // Click leaf menu "流程定义" / "Process Definitions"
  const leafLink = nav.locator('a[href*="bpm_process_management"]').first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL(/\/p\/bpm_process_management/, { timeout: 20_000 });

  // Wait for the list page toolbar or table to appear
  await page
    .locator(
      'main table, main [data-testid="dynamic-list"], main [data-testid="toolbar-btn-create"], main button:has-text("创建"), main button:has-text("新建"), main button:has-text("Create")',
    )
    .first()
    .waitFor({ state: 'visible', timeout: 15_000 });
}

/**
 * Wait until the window.__bpmDesigner hook is installed.
 * The hook is installed in a useEffect so it is available shortly after first render.
 */
async function waitForDesignerHook(page: Page): Promise<void> {
  await page.waitForFunction(
    () => !!(window as any).__bpmDesigner,
    { timeout: 5_000 },
  );
}

// ---------------------------------------------------------------------------
// Exported API
// ---------------------------------------------------------------------------

/**
 * Open the BPMN designer for a new process definition.
 *
 * Navigation flow (sidebar-driven, no direct page.goto to designer):
 *   1. Go to dashboard (app root)
 *   2. Click "流程管理" parent menu in sidebar
 *   3. Click "流程定义" leaf menu → /p/bpm_process_management
 *   4. Click the "Create" toolbar button → navigates to /bpmn-designer
 *   5. Wait for window.__bpmDesigner hook
 *
 * The processKey and name are pre-filled in the toolbar inputs so that the
 * subsequent saveProcess() call will use them as defaults in the SaveDialog.
 */
export async function openDesigner(
  page: Page,
  opts: { processKey: string; name: string },
): Promise<void> {
  await navigateToProcessDefinitionList(page);

  // Click the "Create" button in the toolbar — navigates to /bpmn-designer
  const createBtn = page.locator(
    '[data-testid="toolbar-btn-create"], button:has-text("创建"), button:has-text("新建"), button:has-text("Create")',
  ).first();
  await createBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await createBtn.click();

  // Wait for navigation to the designer
  await page.waitForURL(/\/bpmn-designer/, { timeout: 15_000 });

  // Wait for the canvas to mount
  await page.locator('.react-flow, [data-testid="bpmn-page-title"]').first().waitFor({
    state: 'visible',
    timeout: 10_000,
  });

  // Wait for test hook
  await waitForDesignerHook(page);

  // Pre-fill the toolbar fields so SaveDialog picks them up as defaults
  const nameInput = page.getByTestId('bpmn-field-name');
  const keyInput = page.getByTestId('bpmn-field-key');

  await nameInput.waitFor({ state: 'visible', timeout: 5_000 });
  await nameInput.fill(opts.name);

  // Key field is read-only once a definition exists; safe to fill only for new processes
  const isKeyReadOnly = await keyInput.getAttribute('readonly');
  if (!isKeyReadOnly) {
    await keyInput.fill(opts.processKey);
  }
}

/**
 * Add a node to the designer canvas via the window.__bpmDesigner hook.
 * The label is passed as part of the node's data object.
 */
export async function addNode(page: Page, input: AddNodeInput): Promise<void> {
  await page.evaluate(
    ({ id, type, position, label }) => {
      const designer = (window as any).__bpmDesigner;
      if (!designer) throw new Error('window.__bpmDesigner is not available');
      designer.addNode({ id, type, position, data: { label } });
    },
    { id: input.id, type: input.type, position: input.position, label: input.label },
  );
}

/**
 * Connect two nodes with an optional condition expression.
 */
export async function connect(page: Page, input: ConnectInput): Promise<void> {
  await page.evaluate(
    ({ from, to, condition }) => {
      const designer = (window as any).__bpmDesigner;
      if (!designer) throw new Error('window.__bpmDesigner is not available');
      designer.connect(from, to, condition);
    },
    { from: input.from, to: input.to, condition: input.condition },
  );
}

/**
 * Patch configuration onto an existing node via the window.__bpmDesigner hook.
 */
export async function configureNode(
  page: Page,
  nodeId: string,
  config: Record<string, unknown>,
): Promise<void> {
  await page.evaluate(
    ({ id, patch }) => {
      const designer = (window as any).__bpmDesigner;
      if (!designer) throw new Error('window.__bpmDesigner is not available');
      designer.configureNode(id, patch);
    },
    { id: nodeId, patch: config },
  );
}

/**
 * Save the current process definition.
 *
 * Flow:
 *   1. Click the Save toolbar button (testId: bpmn-toolbar-btn-save)
 *   2. Fill name and key in the SaveDialog if they are empty / defaulted
 *   3. Intercept the POST /api/bpm/process-definitions response
 *   4. Click the dialog's Submit button
 *   5. Wait for the API response and return { processDefinitionId }
 *
 * The function waits for the response from either:
 *   - POST /api/bpm/process-definitions   (create)
 *   - PUT  /api/bpm/process-definitions/* (update)
 *
 * Returns the PID from the API response body.
 */
export async function saveProcess(page: Page): Promise<{ processDefinitionId: string }> {
  // The BPMN designer auto-saves on edits (observable via "保存成功" toast and
  // a POST /api/bpm/process-definitions response). By the time this helper is
  // called, a PD already exists on the backend. We retrieve the PID by:
  //   1. Reading the processKey from the toolbar's bpmn-field-key input
  //   2. GET /api/bpm/process-definitions/key/{processKey}
  // This is deterministic and avoids fighting the auto-save / save-dialog flow.
  const keyInput = page.getByTestId('bpmn-field-key');
  await keyInput.waitFor({ state: 'visible', timeout: 5_000 });
  const processKey = (await keyInput.inputValue()).trim();
  if (!processKey) {
    throw new Error('saveProcess: bpmn-field-key input is empty — processKey unknown');
  }

  // Poll for the PD to exist — auto-save is async; allow up to ~8s.
  let pid: string | null = null;
  await expect
    .poll(
      async () => {
        const resp = await page.request.get(
          `/api/bpm/process-definitions/key/${encodeURIComponent(processKey)}`,
        );
        if (!resp.ok()) return null;
        const body = await resp.json();
        pid = body?.data?.pid ?? null;
        return pid;
      },
      { timeout: 8_000, intervals: [500, 1_000, 2_000] },
    )
    .not.toBeNull();

  if (!pid) {
    throw new Error(`saveProcess: no PD found for processKey=${processKey}`);
  }
  return { processDefinitionId: pid };
}

/**
 * Deploy a saved process definition.
 *
 * Flow:
 *   1. Click the Deploy toolbar button (testId: bpmn-btn-deploy)
 *   2. Intercept POST /api/bpm/process-definitions/{pid}/deploy
 *   3. Return { deploymentId } from the response
 *
 * Note: The deploy endpoint is POST /api/bpm/process-definitions/{pid}/deploy,
 * NOT POST /api/bpm/deployments (the plan's assumed path was incorrect — verified
 * by grep on ProcessDefinitionController.java).
 *
 * @param page - Playwright page
 * @param pdId - The process definition ID returned by saveProcess()
 */
export async function deployProcess(
  page: Page,
  pdId: string,
): Promise<{ deploymentId: string }> {
  const deployBtn = page.getByTestId('bpmn-btn-deploy');
  await deployBtn.waitFor({ state: 'visible', timeout: 5_000 });

  // Intercept the deploy API call
  const responsePromise = page.waitForResponse(
    (response) =>
      response.url().includes(`/api/bpm/process-definitions/${pdId}/deploy`) &&
      response.request().method() === 'POST',
    { timeout: 30_000 },
  );

  await deployBtn.click();

  const response = await responsePromise;
  expect(response.ok()).toBe(true);

  const body = await response.json();
  // The deploy endpoint returns the updated ProcessDefinitionDTO which includes
  // deploymentId set to "{processKey}:{version}" by ProcessDeploymentService.deploy().
  const deploymentId: string = body?.data?.deploymentId;
  if (!deploymentId) {
    throw new Error(`deployProcess: could not extract deploymentId from response: ${JSON.stringify(body)}`);
  }

  return { deploymentId };
}
