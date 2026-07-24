/**
 * Automation — designer/list ACTION-POINT golden (gap-closure 2026-06-18)
 *
 * Closes two action-point coverage gaps the node-type golden suite never drove:
 *   G4 — list-page **Duplicate** (the service.duplicate() + POST /{pid}/duplicate
 *        endpoint existed but had NO UI button and NO E2E; the button is added in
 *        AutomationList.tsx, this drives it end to end).
 *   G5 — designer **Undo / Redo** (FlowToolbar buttons + Ctrl+Z/Y exist and the
 *        store is unit-tested, but no browser golden drove the real action point
 *        with its canUndo/canRedo disabled-state visual feedback).
 *
 * Real UI throughout: real drag for the node, real toolbar buttons, real keyboard.
 * These cases neither enable nor fire any record-triggered automation, so they do
 * NOT take the e2et_order serialization lock and are safe to run in parallel.
 */
import fs from 'node:fs';
import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers';
import {
  dragNodeToCanvas,
  currentNodeIds,
  deleteViaApi,
} from '../_helpers/flow-designer-harness';

// Flow/BPMN designer uses a compact layout below 1600px (palette/inspector collapse
// behind toggles + a drawer backdrop intercepts canvas clicks). These specs assert the
// palette/canvas/nodes directly, so run them at the wide layout the designer targets.
// See FlowDesigner.tsx COMPACT_FLOW_DESIGNER_QUERY '(max-width: 1599px)'.
test.use({ viewport: { width: 1680, height: 1050 } });

const DESIGNER_NEW = '/automation/new';
const MODEL_CODE = 'e2et_order';
const API_OK = '0';

async function openNewDesigner(page: Page): Promise<void> {
  await page.goto(DESIGNER_NEW);
  await page
    .locator('[data-testid="automation-editor-name-input"]')
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.locator('[data-testid="flow-palette"]').waitFor({ state: 'visible', timeout: 20_000 });
  await page.locator('.react-flow__pane').waitFor({ state: 'visible', timeout: 10_000 });
}

/** Create an automation directly via the API (designer flowConfig shape). */
async function postAutomation(
  page: Page,
  body: Record<string, unknown>,
): Promise<{ ok: boolean; pid?: string; raw: unknown }> {
  const resp = await page.request.post('/api/automations', { data: body });
  const json = await resp.json().catch(() => null);
  return { ok: resp.ok() && String((json as any)?.code) === API_OK, pid: (json as any)?.data?.pid, raw: json };
}

function triggerCreateNode(id: string, modelCode: string) {
  return {
    id,
    type: 'trigger-record-create',
    position: { x: 120, y: 200 },
    data: { type: 'trigger-record-create', label: 'OnCreate', config: { triggerType: 'on_record_create', modelCode } },
  };
}
function createRecordNode(id: string, modelCode: string) {
  return {
    id,
    type: 'action-create-record',
    position: { x: 420, y: 200 },
    data: {
      type: 'action-create-record',
      label: 'CreateChild',
      config: { actionType: 'create_record', modelCode, fields: { e2et_order_title: 'dup-child' } },
    },
  };
}

// ───────────────────────── G4 — Duplicate action point ─────────────────────────

test.describe('Automation list — Duplicate action point @golden', () => {
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

  test('duplicate creates an independent clone with the same flow (happy) @golden', async ({ page }) => {
    const name = `DUP-SRC ${uniqueId()}`;
    const src = await postAutomation(page, {
      name,
      description: 'duplicate source',
      flowConfig: {
        nodes: [triggerCreateNode('t', MODEL_CODE), createRecordNode('a', MODEL_CODE)],
        edges: [{ id: 'e1', source: 't', target: 'a' }],
      },
      actions: [],
      enabled: false,
    });
    expect(src.ok, `create source: ${JSON.stringify(src.raw)}`).toBe(true);
    createdPids.push(src.pid!);

    // Drive the real list-page Duplicate button.
    await page.goto('/automations');
    const dupBtn = page.locator(`[data-testid="btn-duplicate-${src.pid}"]`);
    await dupBtn.waitFor({ state: 'visible', timeout: 15_000 });
    await expect(dupBtn).toBeEnabled({ timeout: 10_000 });

    const dupResp = page
      .waitForResponse(
        (r) => r.url().includes(`/api/automations/${src.pid}/duplicate`) && r.request().method() === 'POST',
        { timeout: 15_000 },
      )
      .then((r) => r.status())
      .catch(() => 0);
    await dupBtn.click();
    const status = await dupResp;
    expect(status, 'duplicate POST should succeed').toBeLessThan(400);
    expect(status).toBeGreaterThanOrEqual(200);

    // The handler navigates straight into the new clone's editor — capture its pid.
    await page.waitForURL(/\/automation\/[^/]+$/, { timeout: 15_000 });
    const cloneEditorPid = page.url().split('/').pop()!;
    expect(cloneEditorPid, 'clone editor pid').toBeTruthy();
    expect(cloneEditorPid, 'clone must be a NEW pid, not the source').not.toBe(src.pid);
    createdPids.push(cloneEditorPid);

    // Assert (API) the clone is an independent record carrying the SAME flow nodes.
    const listResp = await page.request.get('/api/automations', { params: { limit: 50, keyword: 'DUP-' } });
    const rows: any[] = (await listResp.json())?.data?.records ?? (await (await page.request.get('/api/automations', { params: { limit: 50 } })).json())?.data?.records ?? [];
    const clone = await (await page.request.get(`/api/automations/${cloneEditorPid}`)).json();
    const cloneData = clone?.data;
    expect(cloneData?.pid, 'clone fetched').toBe(cloneEditorPid);
    const cloneNodes = cloneData?.flowConfig?.nodes ?? [];
    expect(cloneNodes.length, `clone copied the 2-node flow: ${JSON.stringify(cloneData?.flowConfig)}`).toBe(2);
    // Independence: deleting the source must not remove the clone.
    await page.request.delete(`/api/automations/${src.pid}`);
    const afterDelete = await (await page.request.get(`/api/automations/${cloneEditorPid}`)).json();
    expect(String(afterDelete?.code), 'clone survives source deletion (independent record)').toBe(API_OK);
  });
});

// ───────────────────────── G5 — Undo / Redo action points ─────────────────────────

test.describe('Automation designer — Undo / Redo action points @golden', () => {
  test('drag a node, then Undo removes it and Redo restores it; boundary disabled states (happy + edge) @golden', async ({
    page,
  }) => {
    await openNewDesigner(page);

    const undo = page.locator('[data-testid="toolbar-btn-undo"]');
    const redo = page.locator('[data-testid="toolbar-btn-redo"]');
    await undo.waitFor({ state: 'visible', timeout: 15_000 });

    // Edge: at a pristine (empty) history, both Undo and Redo are disabled.
    await expect(undo, 'undo disabled at empty history').toBeDisabled();
    await expect(redo, 'redo disabled at empty history').toBeDisabled();
    expect((await currentNodeIds(page)).length, 'canvas starts empty').toBe(0);

    // Happy: a real drag adds a node and enables Undo.
    const nodeId = await dragNodeToCanvas(page, 'trigger-record-create', { x: 180, y: 160 });
    expect((await currentNodeIds(page)).length, 'node added by drag').toBe(1);
    await expect(undo, 'undo enabled after an edit').toBeEnabled({ timeout: 5_000 });

    // Undo via the real toolbar button → the node is removed and Redo becomes enabled.
    await undo.click();
    await expect
      .poll(async () => (await currentNodeIds(page)).length, { timeout: 5_000 })
      .toBe(0);
    await expect(redo, 'redo enabled after an undo').toBeEnabled({ timeout: 5_000 });

    // Redo via the real toolbar button → the node comes back.
    await redo.click();
    await expect
      .poll(async () => (await currentNodeIds(page)).length, { timeout: 5_000 })
      .toBe(1);
    expect((await currentNodeIds(page)), 'same node id restored').toContain(nodeId);

    // Keyboard parity: Ctrl/Cmd+Z undoes again.
    const mod = process.platform === 'darwin' ? 'Meta' : 'Control';
    await page.locator('.react-flow__pane').click({ position: { x: 40, y: 40 } });
    await page.keyboard.press(`${mod}+z`);
    await expect
      .poll(async () => (await currentNodeIds(page)).length, { timeout: 5_000 })
      .toBe(0);
  });
});

// ───────────────────────── G9 — list Export / Import action points ─────────────────────────

test.describe('Automation list — Export / Import action points @golden', () => {
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

  test('export downloads the automation JSON; importing it creates an equivalent clone (round-trip) @golden', async ({ page }, testInfo) => {
    const name = `IOEXP-SRC ${uniqueId()}`;
    const src = await postAutomation(page, {
      name,
      description: 'export source',
      flowConfig: {
        nodes: [triggerCreateNode('t', MODEL_CODE), createRecordNode('a', MODEL_CODE)],
        edges: [{ id: 'e1', source: 't', target: 'a' }],
      },
      actions: [],
      enabled: false,
    });
    expect(src.ok, `create source: ${JSON.stringify(src.raw)}`).toBe(true);
    createdPids.push(src.pid!);

    await page.goto('/automations');
    const exportBtn = page.locator(`[data-testid="btn-export-${src.pid}"]`);
    await exportBtn.waitFor({ state: 'visible', timeout: 15_000 });
    await expect(exportBtn).toBeEnabled({ timeout: 10_000 });

    // Export → a real file download whose JSON carries the name + flow.
    const [download] = await Promise.all([
      page.waitForEvent('download', { timeout: 15_000 }),
      exportBtn.click(),
    ]);
    const exportPath = testInfo.outputPath('exported-automation.json');
    await download.saveAs(exportPath);
    const exported = JSON.parse(fs.readFileSync(exportPath, 'utf8'));
    expect(exported.name, 'exported JSON carries the automation name').toBe(name);
    expect(exported.flowConfig?.nodes?.length, 'exported JSON carries the 2-node flow').toBe(2);

    // Import the exported JSON (renamed) via the real hidden file input → new automation.
    const importName = `IOEXP-IMPORTED ${uniqueId()}`;
    const importPath = testInfo.outputPath('to-import.json');
    fs.writeFileSync(importPath, JSON.stringify({ ...exported, name: importName }));
    await page.locator('[data-testid="input-import-automation"]').setInputFiles(importPath);

    // Import POSTs + revalidates → a new row with the imported name appears.
    await expect(
      page.getByRole('link', { name: importName }),
      'imported automation row appears in the list',
    ).toBeVisible({ timeout: 15_000 });

    // Verify (API) the imported automation is an independent record with the same flow.
    const list = await (await page.request.get('/api/automations', { params: { limit: 100 } })).json();
    const records: any[] = list?.data?.records ?? list?.data ?? [];
    const imported = records.find((r) => r.name === importName);
    expect(imported, 'imported automation persisted').toBeTruthy();
    createdPids.push(imported.pid);
    const full = await (await page.request.get(`/api/automations/${imported.pid}`)).json();
    expect(full?.data?.flowConfig?.nodes?.length, 'imported flow carries the 2 nodes').toBe(2);
  });
});
