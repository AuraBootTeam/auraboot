/**
 * E2E: Automation designer validation gate (G4 / P0-4)
 *
 * Proves the save-time validation gate wired in the flow-designer-sdk:
 *  - VG-01: an automation whose action node leaves a REQUIRED field empty cannot
 *    be saved — the save is blocked, the errored node is auto-selected, and a
 *    field-level error renders in the property panel (not a raw code, not only a
 *    generic toast). Verifies it is NOT persisted.
 *  - VG-02: a fully-valid automation passes the gate and is persisted.
 *
 * Real database, NO MOCKING. The invalid/valid state is seeded directly into the
 * stored `flowConfig` so the designer loads it verbatim (no fragile canvas drag).
 *
 * @since 7.0.0
 */
import { test, expect } from '../../fixtures';
import { uniqueId } from '../helpers';
import { ErrorCodes } from '~/shared/services/http-client/types';

// Flow/BPMN designer uses a compact layout below 1600px (palette/inspector collapse
// behind toggles + a drawer backdrop intercepts canvas clicks). These specs assert the
// palette/canvas/nodes directly, so run them at the wide layout the designer targets.
// See FlowDesigner.tsx COMPACT_FLOW_DESIGNER_QUERY '(max-width: 1599px)'.
test.use({ viewport: { width: 1680, height: 1050 } });

function nameInput(page: import('@playwright/test').Page) {
  return page.getByTestId('automation-editor-name-input');
}
function saveButton(page: import('@playwright/test').Page) {
  return page.getByTestId('automation-editor-toolbar-btn-save');
}
function propertyPanel(page: import('@playwright/test').Page) {
  return page.locator('.w-96.border-l').first();
}

/**
 * Make the editor dirty and wait for the Save button to enable.
 *
 * The route loader's initialData effect can reset isDirty back to false shortly
 * after the name input is populated — racing a single edit and leaving Save
 * permanently disabled. Retry the dirtying keystroke until Save enables. Each
 * attempt appends " edited" (pressSequentially, not fill, so React's controlled
 * onChange fires per keystroke → setIsDirty(true)); the trailing token stays
 * present so callers can still assert the name contains "edited".
 */
async function makeDirtyAndEnableSave(page: import('@playwright/test').Page) {
  const save = saveButton(page);
  for (let attempt = 0; attempt < 5; attempt += 1) {
    await nameInput(page).click();
    await nameInput(page).press('End');
    await nameInput(page).pressSequentially(' edited');
    if (await save.isEnabled({ timeout: 1500 }).catch(() => false)) return;
    // initialData effect reset isDirty after our edit — settle then retry.
    await expect
      .poll(async () => await save.isEnabled().catch(() => false), {
        timeout: 400,
        intervals: [100, 100, 200],
      })
      .toBe(true)
      .catch(() => undefined);
  }
  await expect(save).toBeEnabled({ timeout: 3000 });
}

/** Create an automation with an explicit flowConfig. When `titleEmpty`, the
 *  send-notification action omits the required `title` field. */
async function createAutomation(
  page: import('@playwright/test').Page,
  opts: { titleEmpty: boolean },
): Promise<{ pid: string; name: string }> {
  const name = `ValidationGate ${uniqueId()}`;
  const actionConfig: Record<string, unknown> = {
    actionType: 'send_notification',
    notificationType: 'in_app',
    content: 'hello',
    recipients: '${trigger.assignee}',
  };
  if (!opts.titleEmpty) {
    actionConfig.title = 'Lead ${trigger.name}';
  }
  const resp = await page.request.post('/api/automations', {
    data: {
      name,
      description: 'E2E validation gate',
      triggerType: 'on_record_create',
      modelCode: 'e2et_order',
      actions: [{ type: 'send_notification', config: {}, sequence: 0, label: 'Notify' }],
      enabled: false,
      flowConfig: {
        nodes: [
          {
            id: 't1',
            type: 'trigger-record-create',
            position: { x: 80, y: 80 },
            // data.config.triggerType is required by AutomationFlowTriggerDeriver
            // (the create endpoint derives the trigger from flowConfig); omitting
            // it yields 422 "trigger node data.config.triggerType is required".
            data: {
              type: 'trigger-record-create',
              label: 'On create',
              config: { triggerType: 'on_record_create', modelCode: 'e2et_order' },
            },
          },
          {
            id: 'a1',
            type: 'action-send-notification',
            position: { x: 80, y: 280 },
            data: { type: 'action-send-notification', label: 'Notify', config: actionConfig },
          },
        ],
        edges: [{ id: 'e1', source: 't1', target: 'a1' }],
      },
    },
  });
  const body = await resp.json();
  if (String(body.code) !== ErrorCodes.SUCCESS) {
    throw new Error(`create failed: ${body.message || JSON.stringify(body)}`);
  }
  return { pid: body.data.pid, name };
}

async function deleteAutomation(page: import('@playwright/test').Page, pid: string) {
  await page.request.delete(`/api/automations/${pid}`).catch(() => {});
}

test.describe('Automation Designer — validation gate (P0-4)', () => {
  const created: string[] = [];

  test.afterAll(async ({ browser }) => {
    const context = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await context.newPage();
    for (const pid of created) await deleteAutomation(page, pid);
    await page.close();
    await context.close();
  });

  test('VG-01: required-empty config is blocked on save with a field-level error @critical', async ({
    page,
  }) => {
    const auto = await createAutomation(page, { titleEmpty: true });
    created.push(auto.pid);

    await page.goto(`/automation/${auto.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });
    // Wait until the route loader's initialData has populated the editor. Filling
    // before that races with the initialData effect that resets isDirty → the Save
    // button would never enable.
    await expect(nameInput(page)).toHaveValue(auto.name, { timeout: 10000 });

    // Make the editor dirty so the Save button enables (retries through the
    // initialData isDirty-reset race).
    await makeDirtyAndEnableSave(page);
    const save = saveButton(page);
    await save.click();

    // Gate blocks save → the errored node is auto-selected and its required
    // field renders a field-level error in the property panel.
    const panel = propertyPanel(page);
    await expect(panel.getByText('This field is required')).toBeVisible({ timeout: 5000 });

    // No raw i18n keys / raw field codes leak in the panel.
    await expect(panel.getByText('$i18n:')).toHaveCount(0);

    // Not persisted: the stored flowConfig still has an empty title.
    const after = await page.request.get(`/api/automations/${auto.pid}`);
    const body = await after.json();
    const a1 = body.data.flowConfig.nodes.find((n: { id: string }) => n.id === 'a1');
    expect(a1.data.config.title ?? '').toBe('');
    // ...and the name edit was not saved either (whole save was blocked).
    expect(body.data.name).toBe(auto.name);
  });

  test('VG-02: a fully-valid automation passes the gate and persists @critical', async ({
    page,
  }) => {
    const auto = await createAutomation(page, { titleEmpty: false });
    created.push(auto.pid);

    await page.goto(`/automation/${auto.pid}`);
    await page.waitForLoadState('domcontentloaded');
    await expect(nameInput(page)).toBeVisible({ timeout: 10000 });
    // Wait until the route loader's initialData has populated the editor. Filling
    // before that races with the initialData effect that resets isDirty → the Save
    // button would never enable.
    await expect(nameInput(page)).toHaveValue(auto.name, { timeout: 10000 });

    await makeDirtyAndEnableSave(page);
    const save = saveButton(page);
    await save.click();

    // Valid flow passes the gate → persisted.
    await expect
      .poll(
        async () => {
          const r = await page.request.get(`/api/automations/${auto.pid}`);
          const b = await r.json();
          return b.data?.name as string;
        },
        { timeout: 8000 },
      )
      .toContain('edited');

    // No field-level error in the panel.
    await expect(propertyPanel(page).getByText('This field is required')).toHaveCount(0);
  });
});
