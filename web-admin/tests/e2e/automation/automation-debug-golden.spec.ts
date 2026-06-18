/**
 * Automation — DEBUG-SESSION golden (gap-closure 2026-06-18, G2)
 *
 * The step-debugger (AutomationDebugger + DebugToolbar + variable/log panels, SSE
 * events) had FULL backend unit coverage but ZERO browser E2E, and — because the
 * debugger walks the flat `actions[]` while a designer automation stores its steps
 * in `flowConfig` — clicking Debug on a designer-built flow showed "0 actions"
 * (fixed in DebugSessionServiceImpl: derive an ordered action list from flowConfig
 * when actions[] is empty). This golden drives the real debugger UI on a real
 * designer-built automation and asserts every action point + visual-feedback state:
 *   - entry (Debug button → debugger renders), status badge, progress N/total
 *   - Step (executes one action; row → ✓ success icon; progress advances; vars panel)
 *   - reaching completed (all rows terminal, step disabled)
 *   - Restart (reset to paused, progress 0/N)
 *   - Continue (runs to completion)
 *   - Stop (session stopped, controls disabled)
 *
 * Real backend throughout: each Step/Continue runs the real CompositeActionExecutor
 * against the real DB (the actions create real e2et_order records). No e2et_order
 * automation is enabled (debug needs no enable), so no serialization lock is needed.
 */
import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import { deleteViaApi } from '../_helpers/flow-designer-harness';

const MODEL_CODE = 'e2et_order';
const API_OK = '0';

async function postAutomation(page: Page, body: Record<string, unknown>): Promise<{ ok: boolean; pid?: string; raw: unknown }> {
  const resp = await page.request.post('/api/automations', { data: body });
  const json = await resp.json().catch(() => null);
  return { ok: resp.ok() && String((json as any)?.code) === API_OK, pid: (json as any)?.data?.pid, raw: json };
}

/** A create-record action carrying ALL required e2et_order fields so it succeeds. */
function createRecordNode(id: string, title: string, x: number) {
  return {
    id,
    type: 'action-create-record',
    position: { x, y: 200 },
    data: {
      type: 'action-create-record',
      label: `Create ${id}`,
      config: {
        actionType: 'create_record',
        modelCode: MODEL_CODE,
        fields: {
          e2et_order_title: title,
          e2et_order_date: '2026-06-18',
          e2et_order_type: 'normal',
          e2et_order_amount: 100,
          e2et_order_status: 'draft',
        },
      },
    },
  };
}

test.describe('Automation debugger — step/continue/restart/stop golden @golden', () => {
  test.describe.configure({ mode: 'serial', timeout: 120_000 });
  const createdPids: string[] = [];

  test.afterAll(async ({ browser }) => {
    if (!createdPids.length) return;
    const ctx = await browser.newContext({
      storageState:
        process.env.PW_ADMIN_STORAGE_STATE ||
        (process.env.PW_STORAGE_DIR ? `${process.env.PW_STORAGE_DIR}/admin.json` : 'tests/storage/admin.json'),
    });
    const page = await ctx.newPage();
    for (const pid of createdPids) await deleteViaApi(page, pid);
    await page.close();
    await ctx.close();
  });

  test('debug a designer-built flow: step through 2 derived actions, continue, restart, stop @golden', async ({ page }) => {
    // A designer automation: trigger → create-record → create-record. Flat actions[]
    // is empty (designer mode); the debugger must DERIVE the 2 actions from flowConfig.
    const create = await postAutomation(page, {
      name: `DEBUG-GOLDEN ${uniqueId()}`,
      flowConfig: {
        nodes: [
          { id: 't', type: 'trigger-record-create', position: { x: 120, y: 200 },
            data: { type: 'trigger-record-create', label: 'OnCreate', config: { triggerType: 'on_record_create', modelCode: MODEL_CODE } } },
          createRecordNode('a0', `dbg-child-0 ${uniqueId()}`, 380),
          createRecordNode('a1', `dbg-child-1 ${uniqueId()}`, 640),
        ],
        edges: [
          { id: 'e0', source: 't', target: 'a0' },
          { id: 'e1', source: 'a0', target: 'a1' },
        ],
      },
      actions: [],
      enabled: false,
    });
    expect(create.ok, `create: ${JSON.stringify(create.raw)}`).toBe(true);
    createdPids.push(create.pid!);

    // Open the editor.
    await page.goto(`/automation/${create.pid}`);
    // Wait for hydration: the name input must carry the loaded automation name
    // (proves the editor component hydrated + applied loader data) before the
    // Debug button's onClick handler is reliably attached.
    await expect(page.locator('[data-testid="automation-editor-name-input"]'))
      .toHaveValue(/DEBUG-GOLDEN/, { timeout: 30_000 });

    const progress = page.locator('[data-testid="automation-debug-progress"]');
    const status = page.locator('[data-testid="automation-debug-status"]');
    const stepBtn = page.locator('[data-testid="automation-debug-step"]');
    const continueBtn = page.locator('[data-testid="automation-debug-continue"]');
    const restartBtn = page.locator('[data-testid="automation-debug-restart"]');
    const stopBtn = page.locator('[data-testid="automation-debug-stop"]');
    const rows = page.locator('[data-testid="automation-debug-action-row"]');
    const debugBtn = page.locator('[data-testid="btn-debug-automation"]');
    await debugBtn.waitFor({ state: 'visible', timeout: 30_000 });

    // Enter debug mode. Retry the click until the debugger toolbar appears — absorbs
    // the SSR-hydration race where the button is visible but its onClick handler is
    // not yet attached (a single early click is silently dropped → stays in editor).
    await expect(async () => {
      if (await debugBtn.isVisible().catch(() => false)) {
        await debugBtn.click().catch(() => {});
      }
      await expect(status).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 30_000, intervals: [500, 1_000, 2_000] });

    // Debugger renders: toolbar + 2 action rows derived from the flow (the G2 fix —
    // without it this would be 0 rows / "0/0" for a designer-built automation).
    await expect(rows, 'debugger derived 2 action rows from flowConfig (the G2 fix)').toHaveCount(2);
    await expect(progress, 'starts at 0/2 paused').toHaveText('0/2');
    await expect(stepBtn, 'step enabled while paused').toBeEnabled();
    // Variable + event panels render (visual-feedback surfaces). Headers are i18n
    // (zh-CN "变量"/"事件"); assert locale-robustly + the seeded context variable.
    await expect(page.getByText(/变量|Variables/).first(), 'variables panel renders').toBeVisible();
    await expect(page.getByText(/事件|Events/).first(), 'events panel renders').toBeVisible();
    await expect(page.getByText('automationPid').first(), 'seeded debug context variable shown').toBeVisible();

    // Step 1 → first action executes (real backend), row 0 → ✓ success, progress 1/2.
    await stepBtn.click();
    await expect(progress, 'progress advances after step').toHaveText('1/2', { timeout: 15_000 });
    await expect(rows.nth(0), 'row 0 shows the success ✓ icon after a real execution').toContainText('✓', { timeout: 10_000 });

    // Step 2 → last action executes → session completed; step disabled (not paused).
    await stepBtn.click();
    await expect(progress, 'progress reaches 2/2').toHaveText('2/2', { timeout: 15_000 });
    await expect(rows.nth(1), 'row 1 shows the success ✓ icon').toContainText('✓', { timeout: 10_000 });
    await expect(stepBtn, 'step disabled once completed (not paused)').toBeDisabled({ timeout: 10_000 });
    await expect(status, 'status badge reads Completed').toContainText(/Completed|已完成|completed/i, { timeout: 10_000 });

    // Restart → reset to paused at 0/2, step re-enabled.
    await restartBtn.click();
    await expect(progress, 'restart resets progress to 0/2').toHaveText('0/2', { timeout: 10_000 });
    await expect(stepBtn, 'step re-enabled after restart').toBeEnabled({ timeout: 10_000 });

    // Continue → runs all remaining actions to completion in one shot.
    await continueBtn.click();
    await expect(progress, 'continue runs to 2/2').toHaveText('2/2', { timeout: 20_000 });
    await expect(status, 'completed after continue').toContainText(/Completed|已完成|completed/i, { timeout: 10_000 });

    // Restart → paused, then Stop → ends the session and exits debug mode back to
    // the editor (useDebugSession.stop sets isDebugMode=false → the debugger
    // unmounts and the editor's Debug button returns). Covers the Stop action point.
    await restartBtn.click();
    await expect(progress).toHaveText('0/2', { timeout: 10_000 });
    await expect(stopBtn, 'stop enabled while active').toBeEnabled({ timeout: 10_000 });
    await stopBtn.click();
    await expect(status, 'debugger toolbar gone after Stop (exited debug mode)').toBeHidden({ timeout: 10_000 });
    await expect(debugBtn, 'editor restored after Stop').toBeVisible({ timeout: 10_000 });
  });

  test('set a breakpoint via the gutter toggle → Continue pauses at it (breakpoint action point) @golden', async ({ page }) => {
    const create = await postAutomation(page, {
      name: `DEBUG-BP ${uniqueId()}`,
      flowConfig: {
        nodes: [
          { id: 't', type: 'trigger-record-create', position: { x: 120, y: 200 },
            data: { type: 'trigger-record-create', label: 'OnCreate', config: { triggerType: 'on_record_create', modelCode: MODEL_CODE } } },
          createRecordNode('a0', `bp-child-0 ${uniqueId()}`, 380),
          createRecordNode('a1', `bp-child-1 ${uniqueId()}`, 640),
        ],
        edges: [
          { id: 'e0', source: 't', target: 'a0' },
          { id: 'e1', source: 'a0', target: 'a1' },
        ],
      },
      actions: [],
      enabled: false,
    });
    expect(create.ok, `create: ${JSON.stringify(create.raw)}`).toBe(true);
    createdPids.push(create.pid!);

    await page.goto(`/automation/${create.pid}`);
    await expect(page.locator('[data-testid="automation-editor-name-input"]'))
      .toHaveValue(/DEBUG-BP/, { timeout: 30_000 });

    const status = page.locator('[data-testid="automation-debug-status"]');
    const progress = page.locator('[data-testid="automation-debug-progress"]');
    const continueBtn = page.locator('[data-testid="automation-debug-continue"]');
    const stepBtn = page.locator('[data-testid="automation-debug-step"]');
    const debugBtn = page.locator('[data-testid="btn-debug-automation"]');
    await debugBtn.waitFor({ state: 'visible', timeout: 30_000 });
    await expect(async () => {
      if (await debugBtn.isVisible().catch(() => false)) await debugBtn.click().catch(() => {});
      await expect(status).toBeVisible({ timeout: 3_000 });
    }).toPass({ timeout: 30_000, intervals: [500, 1_000, 2_000] });
    await expect(progress).toHaveText('0/2', { timeout: 10_000 });

    // Set a breakpoint on action index 1 via the gutter toggle (the G8 affordance —
    // previously updateBreakpoints had no UI caller, so breakpoints were unreachable).
    const bp1 = page.locator('[data-testid="automation-debug-breakpoint-toggle"][data-action-index="1"]');
    await bp1.click();
    await expect(bp1, 'breakpoint toggled ON (aria-pressed)').toHaveAttribute('aria-pressed', 'true', { timeout: 10_000 });

    // Continue from index 0 → runs action 0 → PAUSES at the breakpoint on index 1
    // (NOT run to completion). Proves set-breakpoint + continue-until-breakpoint wiring.
    await continueBtn.click();
    await expect(progress, 'continue paused at the breakpoint (1/2), not 2/2').toHaveText('1/2', { timeout: 20_000 });
    await expect(status, 'still paused at the breakpoint').toContainText(/Paused|已暂停|paused/i, { timeout: 10_000 });
    await expect(stepBtn, 'step re-enabled at the breakpoint pause').toBeEnabled({ timeout: 10_000 });

    // Clear the breakpoint + Continue → now runs to completion.
    await bp1.click();
    await expect(bp1, 'breakpoint cleared (aria-pressed=false)').toHaveAttribute('aria-pressed', 'false', { timeout: 10_000 });
    await continueBtn.click();
    await expect(progress, 'after clearing the bp, continue runs to 2/2').toHaveText('2/2', { timeout: 20_000 });
    await expect(status, 'completed').toContainText(/Completed|已完成|completed/i, { timeout: 10_000 });
  });
});
