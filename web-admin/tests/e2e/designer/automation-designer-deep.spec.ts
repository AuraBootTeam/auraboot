/**
 * Automation Designer — Deep E2E Tests
 *
 * Tests palette categories, trigger/action node properties, name/description inputs,
 * save/enable/disable cycle, and debug/backend verification.
 *
 * @since 6.0.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import { uniqueId } from '../helpers';

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

async function createAndOpenAutomation(page: Page): Promise<string> {
  if (pid) {
    await page.goto(`/automation/${pid}`, { waitUntil: 'domcontentloaded' });
    await waitForDesignerLoad(page);
    return pid;
  }
  const resp = await page.request.post('/api/automations', {
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
  const body = await resp.json();
  pid = body.data?.pid || body.data?.id;
  expect(pid).toBeTruthy();
  await page.goto(`/automation/${pid}`, { waitUntil: 'domcontentloaded' });
  await waitForDesignerLoad(page);
  return pid;
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
    expect(await page.locator('.react-flow__node').count()).toBeGreaterThanOrEqual(2);
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
    const resp = await page.request.put(`/api/automations/${pid}`, {
      data: { name: `${autoName}_saved` },
    });
    expect(resp.status()).toBeLessThan(400);
  });

  test('AUD-LC-02: Enable → enabled=true', async ({ page }) => {
    await createAndOpenAutomation(page);
    await page.request.post(`/api/automations/${pid}/enable`);
    const { data } = await (await page.request.get(`/api/automations/${pid}`)).json();
    expect(data.enabled).toBe(true);
  });

  test('AUD-LC-03: Disable → enabled=false', async ({ page }) => {
    await createAndOpenAutomation(page);
    await page.request.post(`/api/automations/${pid}/disable`);
    const { data } = await (await page.request.get(`/api/automations/${pid}`)).json();
    expect(data.enabled).toBe(false);
  });

  test('AUD-LC-04: Toggle flips enabled', async ({ page }) => {
    await createAndOpenAutomation(page);
    const before = await (await page.request.get(`/api/automations/${pid}`)).json();
    const wasBefore = before.data.enabled;
    await page.request.post(`/api/automations/${pid}/toggle`);
    const after = await (await page.request.get(`/api/automations/${pid}`)).json();
    expect(after.data.enabled).toBe(!wasBefore);
  });
});

/* ================================================================== */
/*  6. Debug & Backend                                                */
/* ================================================================== */

test.describe('Debug & Backend', () => {
  test('AUD-DB-01: Debug/Test Run button or Export button visible', async ({ page }) => {
    await createAndOpenAutomation(page);
    // The editor shows either Test Run, Debug, or Export buttons depending on state
    const btn = page
      .locator(
        '[data-testid="btn-test-run"], [data-testid="btn-export-automation"], button:has-text("Debug"), button:has-text("Test Run"), button:has-text("Export"), button:has-text("导出")',
      )
      .first();
    await expect(btn).toBeVisible({ timeout: 8000 });
  });

  test('AUD-DB-02: GET verify name/triggerType/actions', async ({ page }) => {
    await createAndOpenAutomation(page);
    const resp = await page.request.get(`/api/automations/${pid}`);
    const { data } = await resp.json();
    expect(data.name).toBeTruthy();
    expect(data.triggerType).toBe('on_record_create');
    expect(data.actions.length).toBeGreaterThanOrEqual(1);
  });

  test('AUD-DB-03: Logs endpoint returns < 500', async ({ page }) => {
    await createAndOpenAutomation(page);
    const resp = await page.request.get(`/api/automations/${pid}/logs`);
    expect(resp.status()).toBeLessThan(400);
  });
});
