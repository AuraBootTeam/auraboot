/**
 * Automation Designer — Deep E2E Tests
 *
 * Tests palette categories, trigger/action node properties, name/description inputs,
 * save/enable/disable cycle, and debug/backend verification.
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import type { APIResponse, Page, Response as PlaywrightResponse } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { uniqueId } from '../helpers';
import { loginAs } from '../../helpers/wd-fixtures';
import { BACKEND_URL } from '../../helpers/environments';

/* ------------------------------------------------------------------ */
/*  Helpers                                                           */
/* ------------------------------------------------------------------ */

async function waitForDesignerLoad(page: Page) {
  await page.waitForLoadState('networkidle').catch(() => {});
  await page
    .locator('.animate-spin')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
  await page
    .locator('text=Loading page...')
    .waitFor({ state: 'hidden', timeout: 10000 })
    .catch(() => {});
}

const autoName = uniqueId('aud');
let pid: string;
let adminToken: string | undefined;

async function automationApiHeaders(page: Page): Promise<Record<string, string>> {
  adminToken = adminToken || (await loginAs(page.request, 'admin@auraboot.com', 'Test2026x'));
  return {
    Authorization: `Bearer ${adminToken}`,
    'Content-Type': 'application/json',
  };
}

async function readAutomationApi<T = any>(
  response: APIResponse | PlaywrightResponse,
  label: string,
): Promise<T> {
  const text = await response.text();
  let body: T;
  try {
    body = JSON.parse(text) as T;
  } catch {
    throw new Error(`${label}: expected JSON but got HTTP ${response.status()} ${text.slice(0, 120)}`);
  }
  expect(response.status(), `${label}: HTTP status`).toBeLessThan(400);
  return body;
}

async function createAndOpenAutomation(page: Page): Promise<string> {
  if (pid) {
    await page.goto(`/automation/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    return pid;
  }
  const resp = await page.request.post(`${BACKEND_URL}/api/automations`, {
    headers: await automationApiHeaders(page),
    data: {
      name: autoName,
      description: 'Automation deep E2E',
      triggerType: 'on_record_create',
      modelCode: 'ab_user',
      actions: [
        {
          type: 'send_notification',
          config: { message: 'deep test' },
          sequence: 0,
          label: 'Notify',
        },
        { type: 'update_record', config: { fields: {} }, sequence: 1, label: 'Update' },
      ],
      enabled: false,
    },
  });
  const body = await readAutomationApi(resp, 'create automation');
  pid = body.data?.pid || body.data?.id;
  expect(pid).toBeTruthy();
  await page.goto(`/automation/${pid}`, { waitUntil: 'domcontentloaded' });
  await waitForDesignerLoad(page);
  return pid;
}

async function createRuntimeAutomation(page: Page, prefix: string): Promise<string> {
  const name = uniqueId(prefix);
  const resp = await page.request.post(`${BACKEND_URL}/api/automations`, {
    headers: await automationApiHeaders(page),
    data: {
      name,
      description: 'Automation runtime E2E',
      triggerType: 'on_record_create',
      modelCode: 'ab_user',
      actions: [
        {
          type: 'send_notification',
          config: {
            notificationType: 'in_app',
            title: 'Runtime E2E',
            content: 'Runtime E2E notification',
            recipients: [],
          },
          sequence: 0,
          label: 'Runtime Notify',
        },
      ],
      enabled: false,
    },
  });
  const body = await readAutomationApi(resp, 'create runtime automation');
  const runtimePid = body.data?.pid || body.data?.id;
  expect(runtimePid).toBeTruthy();
  const id = String(runtimePid);
  await readAutomationApi(
    await page.request.post(`${BACKEND_URL}/api/automations/${id}/enable`, {
      headers: await automationApiHeaders(page),
    }),
    'enable runtime automation',
  );
  return id;
}

/* ================================================================== */
/*  1. Palette Categories                                             */
/* ================================================================== */

test.describe('Palette Categories', () => {
  test('AUD-PC-01: Draggable nodes exist in palette', async ({ page }) => {
    await createAndOpenAutomation(page);
    expect(await page.locator('[draggable="true"]').count()).toBeGreaterThan(0);
  });

  test('AUD-PC-02: React Flow canvas present', async ({ page }) => {
    await createAndOpenAutomation(page);
    // React Flow wrapper should be in the DOM (may have 0 height in some layouts)
    const canvas = page
      .locator('.react-flow, [data-testid="flow-canvas"], [data-testid="rf__wrapper"]')
      .first();
    await expect(canvas).toBeAttached({ timeout: 8000 });
  });
});

/* ================================================================== */
/*  2. Trigger Node Properties                                        */
/* ================================================================== */

async function waitForFlowNodes(page: Page) {
  const nodes = page.locator('.react-flow__node');
  await nodes.first().waitFor({ state: 'visible', timeout: 10000 });
}

test.describe('Trigger Node Properties', () => {
  test('AUD-TR-01: Select trigger → shows trigger info', async ({ page }) => {
    await createAndOpenAutomation(page);
    await waitForFlowNodes(page);
    await page.locator('.react-flow__node').first().click();
    await expect(page.locator('text=/trigger|触发|ON_RECORD/i').first()).toBeVisible({
      timeout: 5000,
    });
  });

  test('AUD-TR-02: Trigger type displayed', async ({ page }) => {
    await createAndOpenAutomation(page);
    await waitForFlowNodes(page);
    await page.locator('.react-flow__node').first().click();
    await expect(page.locator('text=/ON_RECORD_CREATE|记录创建/i').first()).toBeVisible({
      timeout: 5000,
    });
  });
});

/* ================================================================== */
/*  3. Action Node Properties                                         */
/* ================================================================== */

test.describe('Action Node Properties', () => {
  test('AUD-AC-01: Action nodes visible (trigger + action)', async ({ page }) => {
    await createAndOpenAutomation(page);
    await waitForFlowNodes(page);
    expect(await page.locator('.react-flow__node').count()).toBe(3);
  });

  test('AUD-AC-02: Select action → panel shows config', async ({ page }) => {
    await createAndOpenAutomation(page);
    await waitForFlowNodes(page);
    const nodes = page.locator('.react-flow__node');
    const actionNode = nodes
      .filter({ hasText: /Notify|通知|SEND_NOTIFICATION/i })
      .first()
      .or(nodes.nth(1));
    await actionNode.click();
    await expect(
      page.locator('text=/SEND_NOTIFICATION|发送通知|Notification|action/i').first(),
    ).toBeVisible({ timeout: 5000 });
  });
});

/* ================================================================== */
/*  4. Name/Description Inputs                                        */
/* ================================================================== */

test.describe('Name/Description Inputs', () => {
  test('AUD-NM-01: Name input editable', async ({ page }) => {
    await createAndOpenAutomation(page);
    const nameInput = page
      .locator(
        'input[placeholder*="名称"], input[placeholder*="name"], input[placeholder*="Automation"]',
      )
      .first();
    await expect(nameInput).toBeVisible({ timeout: 8000 });
    const newName = uniqueId('AUD_Renamed');
    await nameInput.click();
    await nameInput.clear();
    await nameInput.type(newName, { delay: 10 });
    await expect(nameInput).toHaveValue(newName);
  });

  test('AUD-NM-02: Description input editable', async ({ page }) => {
    await createAndOpenAutomation(page);
    const descInput = page
      .locator(
        'input[placeholder*="描述"], input[placeholder*="Description"], input[placeholder*="description"]',
      )
      .first();
    if (await descInput.isVisible({ timeout: 5000 }).catch(() => false)) {
      await descInput.click();
      await descInput.clear();
      await descInput.type('Updated description', { delay: 10 });
      await expect(descInput).toHaveValue('Updated description');
    }
  });
});

/* ================================================================== */
/*  5. Save + Enable/Disable Cycle                                    */
/* ================================================================== */

test.describe('Save + Enable/Disable Cycle', () => {
  test('AUD-LC-01: Save via API', async ({ page }) => {
    await createAndOpenAutomation(page);
    const resp = await page.request.put(`${BACKEND_URL}/api/automations/${pid}`, {
      headers: await automationApiHeaders(page),
      data: { name: `${autoName}_saved` },
    });
    expect(resp.status()).toBeLessThan(400);
  });

  test('AUD-LC-02: Enable → enabled=true', async ({ page }) => {
    await createAndOpenAutomation(page);
    await readAutomationApi(
      await page.request.post(`${BACKEND_URL}/api/automations/${pid}/enable`, {
        headers: await automationApiHeaders(page),
      }),
      'enable automation',
    );
    const { data } = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automations/${pid}`, {
        headers: await automationApiHeaders(page),
      }),
      'get automation after enable',
    );
    expect(data.enabled).toBe(true);
  });

  test('AUD-LC-03: Disable → enabled=false', async ({ page }) => {
    await createAndOpenAutomation(page);
    await readAutomationApi(
      await page.request.post(`${BACKEND_URL}/api/automations/${pid}/disable`, {
        headers: await automationApiHeaders(page),
      }),
      'disable automation',
    );
    const { data } = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automations/${pid}`, {
        headers: await automationApiHeaders(page),
      }),
      'get automation after disable',
    );
    expect(data.enabled).toBe(false);
  });

  test('AUD-LC-04: Toggle flips enabled', async ({ page }) => {
    await createAndOpenAutomation(page);
    const before = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automations/${pid}`, {
        headers: await automationApiHeaders(page),
      }),
      'get automation before toggle',
    );
    const wasBefore = before.data.enabled;
    await readAutomationApi(
      await page.request.post(`${BACKEND_URL}/api/automations/${pid}/toggle`, {
        headers: await automationApiHeaders(page),
      }),
      'toggle automation',
    );
    const after = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automations/${pid}`, {
        headers: await automationApiHeaders(page),
      }),
      'get automation after toggle',
    );
    expect(after.data.enabled).toBe(!wasBefore);
  });
});

/* ================================================================== */
/*  6. Debug & Backend                                                */
/* ================================================================== */

test.describe('Debug & Backend', () => {
  test('AUD-DB-01: Export downloads current automation flow JSON', async ({ page }, testInfo) => {
    await createAndOpenAutomation(page);
    const exportBtn = page.getByTestId('btn-export-automation');
    await expect(exportBtn).toBeVisible({ timeout: 8000 });
    await expect(exportBtn).toBeEnabled();
    const currentName = await page.getByTestId('automation-editor-name-input').inputValue();
    expect([autoName, `${autoName}_saved`]).toContain(currentName);

    const downloadPromise = page.waitForEvent('download');
    await exportBtn.click();
    const download = await downloadPromise;

    expect(download.suggestedFilename()).toBe(`automation-${currentName.toLowerCase()}.json`);
    const savedPath = path.join(testInfo.outputDir, download.suggestedFilename());
    await download.saveAs(savedPath);

    const exported = JSON.parse(await readFile(savedPath, 'utf-8'));
    expect(exported.name).toBe(currentName);
    expect(exported.description).toBe('Automation deep E2E');
    expect(exported.flowConfig.nodes.map((node: { id: string }) => node.id).sort()).toEqual([
      'action_0',
      'action_1',
      'trigger_0',
    ]);
    expect(exported.flowConfig.edges.map((edge: { id: string }) => edge.id).sort()).toEqual([
      'edge_action_0_action_1',
      'edge_trigger_0_action_0',
    ]);
    expect(exported.flowConfig.nodes.find((node: { id: string }) => node.id === 'trigger_0').data)
      .toMatchObject({
        type: 'trigger',
        label: 'on_record_create',
        config: { triggerType: 'on_record_create', modelCode: 'ab_user' },
      });
    expect(exported.flowConfig.nodes.find((node: { id: string }) => node.id === 'action_0').data)
      .toMatchObject({
        type: 'action',
        label: 'Notify',
        config: { actionType: 'send_notification', message: 'deep test' },
      });
  });

  test('AUD-DB-02: GET verify name/triggerType/actions', async ({ page }) => {
    await createAndOpenAutomation(page);
    const { data } = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automations/${pid}`, {
        headers: await automationApiHeaders(page),
      }),
      'get automation for backend verification',
    );
    expect(data.name).toBeTruthy();
    expect(data.triggerType).toBe('on_record_create');
    expect(data.actions.map((action: { type: string }) => action.type)).toEqual([
      'send_notification',
      'update_record',
    ]);
  });

  test('AUD-DB-03: Logs endpoint returns < 500', async ({ page }) => {
    await createAndOpenAutomation(page);
    const resp = await page.request.get(`${BACKEND_URL}/api/automations/${pid}/logs`, {
      headers: await automationApiHeaders(page),
    });
    expect(resp.status()).toBeLessThan(400);
  });

  test('AUD-DB-04: Test Run button creates an execution log with backend result', async ({
    page,
  }) => {
    const runtimePid = await createRuntimeAutomation(page, 'aud_run');
    await page.goto(`/automation/${runtimePid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    const triggerResponsePromise = page.waitForResponse((response) => {
      return (
        response.url().includes(`/api/automations/${runtimePid}/trigger`) &&
        response.request().method() === 'POST'
      );
    });
    await page.getByTestId('btn-test-run').click();
    const triggerResponse = await triggerResponsePromise;
    const triggerBody = await readAutomationApi(triggerResponse, 'automation test run');
    const log = triggerBody.data;

    expect(log.automationId).toBe(runtimePid);
    expect(log.triggerType).toBe('on_record_create');
    expect(log.triggerPayload).toMatchObject({ manualTrigger: true });
    expect(log.status).toBe('success');
    expect(log.errorMessage ?? null).toBeNull();

    const logsBody = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automations/${runtimePid}/logs`, {
        headers: await automationApiHeaders(page),
      }),
      'get automation logs after test run',
    );
    expect(logsBody.data[0].pid).toBe(log.pid);
    expect(logsBody.data[0].status).toBe('success');
  });

  test('AUD-DB-05: Debug button creates a session and Step records action result', async ({
    page,
  }) => {
    const runtimePid = await createRuntimeAutomation(page, 'aud_debug');
    await page.goto(`/automation/${runtimePid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);

    const createSessionPromise = page.waitForResponse((response) => {
      return (
        response.url().includes(`/api/automation/${runtimePid}/debug/sessions`) &&
        response.request().method() === 'POST'
      );
    });
    await page.getByTestId('btn-debug-automation').click();
    const createSessionResponse = await createSessionPromise;
    const createSessionBody = await readAutomationApi(createSessionResponse, 'create debug session');
    const session = createSessionBody.data;

    expect(session.automationId).toBe(runtimePid);
    expect(session.status).toBe('paused');
    expect(session.currentActionIndex).toBe(0);
    expect(session.totalActions).toBe(1);
    expect(session.actionResults).toEqual([]);
    await expect(page.getByTestId('automation-debug-progress')).toHaveText('0/1');
    await expect(page.getByTestId('automation-debug-action-row')).toHaveCount(1);

    const stepPromise = page.waitForResponse((response) => {
      return (
        response.url().includes(`/api/automation/debug/sessions/${session.pid}/step`) &&
        response.request().method() === 'POST'
      );
    });
    await page.getByTestId('automation-debug-step').click();
    const stepResponse = await stepPromise;
    const stepBody = await readAutomationApi(stepResponse, 'step debug session');

    expect(stepBody.data.status).toBe('completed');
    expect(stepBody.data.currentActionIndex).toBe(1);
    expect(stepBody.data.totalActions).toBe(1);
    expect(stepBody.data.actionResults).toHaveLength(1);
    expect(stepBody.data.actionResults[0]).toMatchObject({
      sequence: 0,
      actionType: 'send_notification',
      status: 'success',
    });
    await expect(page.getByTestId('automation-debug-progress')).toHaveText('1/1');

    const sessionBody = await readAutomationApi(
      await page.request.get(`${BACKEND_URL}/api/automation/debug/sessions/${session.pid}`, {
        headers: await automationApiHeaders(page),
      }),
      'get debug session after step',
    );
    expect(sessionBody.data.status).toBe('completed');
    expect(sessionBody.data.actionResults[0].status).toBe('success');
  });
});
