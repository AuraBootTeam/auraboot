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

function nameInput(page: import('@playwright/test').Page) {
  return page.getByTestId('automation-editor-name-input');
}
function saveButton(page: import('@playwright/test').Page) {
  return page.getByTestId('automation-editor-toolbar-btn-save');
}
function propertyPanel(page: import('@playwright/test').Page) {
  return page.locator('.w-80.border-l').first();
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
            data: { label: 'On create', config: { modelCode: 'e2et_order' } },
          },
          {
            id: 'a1',
            type: 'action-send-notification',
            position: { x: 80, y: 280 },
            data: { label: 'Notify', config: actionConfig },
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

    // Make the editor dirty so the Save button enables.
    await nameInput(page).fill(`${auto.name} edited`);
    const save = saveButton(page);
    await expect(save).toBeEnabled({ timeout: 5000 });
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

    const editedName = `${auto.name} edited`;
    await nameInput(page).fill(editedName);
    const save = saveButton(page);
    await expect(save).toBeEnabled({ timeout: 5000 });
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
      .toBe(editedName);

    // No field-level error in the panel.
    await expect(propertyPanel(page).getByText('This field is required')).toHaveCount(0);
  });
});
