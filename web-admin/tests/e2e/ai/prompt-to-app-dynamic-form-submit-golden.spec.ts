/**
 * Prompt-to-App / dynamic form submit golden.
 *
 * Covers the platform regression where canonicalization dropped a form button's
 * legacy commandCode when the same button also declared action:"save". The real
 * browser then fell through to ActionRegistry("save"), which is not registered.
 *
 * The tests prove both affected surfaces:
 * 1. A shipped dynamic form with the legacy shape (asset category).
 * 2. A Prompt-to-App synthesized form generated through nl-modeling apply.
 */

import { resolve } from 'node:path';
import { test, expect, type Page } from '../../fixtures';
import { fillControlledInput, waitForDynamicPageLoad, waitForFormReady } from '../helpers/index';

test.describe.configure({ mode: 'serial' });

const runSuffix = Date.now().toString(36).slice(-8);

function assertApiSuccess(body: any, context: string): void {
  const success =
    body?.success === true ||
    String(body?.code ?? '') === '0' ||
    body?.data?.success === true ||
    String(body?.data?.status ?? '').toUpperCase() === 'SUCCESS';
  expect(success, `${context} should succeed: ${JSON.stringify(body).slice(0, 1000)}`).toBe(true);
}

async function importPluginDirectory(page: Page, pluginDir: string): Promise<void> {
  const response = await page.request.post('/api/plugins/import/import-directory-sync', {
    data: {
      path: pluginDir,
      conflictStrategy: 'OVERWRITE',
      autoPublishModels: true,
      autoPublishFields: true,
      autoPublishCommands: true,
      autoPublishPages: true,
    },
    timeout: 60_000,
  });
  const body = await response.json().catch(() => ({}));
  expect(response.ok(), `Import ${pluginDir} failed: ${JSON.stringify(body).slice(0, 1000)}`).toBe(
    true,
  );
  assertApiSuccess(body, `Import ${pluginDir}`);
}

async function openListFromSidebar(page: Page, modelCode: string): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded', timeout: 30_000 });
  await page.evaluate(() => localStorage.removeItem('sidebar-collapsed'));
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 30_000 });

  const navLink = page.locator(`nav a[href="/p/${modelCode}"]`).first();
  await navLink.waitFor({ state: 'attached', timeout: 20_000 });
  const listResponse = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/dynamic/${modelCode}`) &&
      r.url().includes('/list') &&
      r.status() === 200,
    { timeout: 30_000 },
  );
  await navLink.evaluate((el: HTMLElement) => el.click());
  await listResponse;
  await waitForDynamicPageLoad(page, 20_000);
  await expect(page.locator('main, [role="main"]').first()).not.toContainText(
    /加载失败|Page not found/i,
  );
}

async function clickCreateFromList(page: Page): Promise<void> {
  const createButton = page.getByRole('button', { name: /新建|创建|Create|Add/i }).first();
  await createButton.waitFor({ state: 'visible', timeout: 15_000 });
  await expect(createButton).toBeEnabled({ timeout: 10_000 });
  await createButton.click();
  await waitForFormReady(page, 20_000);
  await expect(page).toHaveURL(/\/new(?:\?.*)?$/, { timeout: 15_000 });
}

async function submitAndExpectCommand(
  page: Page,
  commandCode: string,
  expectedOperationType: 'create' | 'update' = 'create',
): Promise<any> {
  const commandResponse = page.waitForResponse(
    (r) =>
      r.url().includes(`/api/meta/commands/execute/${commandCode}`) &&
      r.request().method().toLowerCase() === 'post',
    { timeout: 30_000 },
  );

  const submitButton = page.locator('[data-testid="form-btn-submit"]').first();
  await submitButton.waitFor({ state: 'visible', timeout: 10_000 });
  await expect(submitButton).toBeEnabled({ timeout: 10_000 });
  await submitButton.click();

  const response = await commandResponse;
  const requestBody = response.request().postDataJSON();
  expect(requestBody?.operationType).toBe(expectedOperationType);

  const body = await response.json().catch(() => ({}));
  expect(
    response.ok(),
    `Command ${commandCode} HTTP ${response.status()}: ${JSON.stringify(body)}`,
  ).toBe(true);
  expect(String(body?.code ?? ''), `Command ${commandCode} should return code 0`).toBe('0');
  return body;
}

async function expectRowVisible(page: Page, modelCode: string, text: string): Promise<void> {
  await page.waitForURL(new RegExp(`/p/${modelCode}$`), { timeout: 20_000 });
  await waitForDynamicPageLoad(page, 20_000);
  await expect(page.getByText(text).first()).toBeVisible({ timeout: 20_000 });
}

test.describe('Dynamic form submit command dispatch @golden', () => {
  test('shipped legacy form action save + commandCode creates an asset category', async ({
    page,
  }, testInfo) => {
    test.setTimeout(120_000);

    await importPluginDirectory(page, resolve(process.cwd(), '../plugins/asset-management'));
    await openListFromSidebar(page, 'tasset_category');
    await clickCreateFromList(page);

    const categoryName = `Golden Category ${runSuffix}`;
    await fillControlledInput(
      page.locator('[data-testid="form-field-tasset_ct_name"] input').first(),
      categoryName,
    );
    await fillControlledInput(
      page.locator('[data-testid="form-field-tasset_ct_description"] textarea').first(),
      `Created by dynamic form submit golden ${runSuffix}`,
    );

    await submitAndExpectCommand(page, 'tasset:create_category');
    await expectRowVisible(page, 'tasset_category', categoryName);
    await testInfo.attach('asset-category-list', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });
  });

  test('Prompt-to-App synthesized list/form drives create submit and row appears', async ({
    page,
  }, testInfo) => {
    test.setTimeout(120_000);

    const pluginCode = `p2a_gold_${runSuffix}`;
    const modelCode = `p2a_lead_${runSuffix}`;
    const leadName = `Generated Lead ${runSuffix}`;

    const applyResponse = await page.request.post('/api/agent/nl-modeling/apply', {
      data: {
        pluginCode,
        resources: {
          models: [
            {
              code: modelCode,
              'displayName:en': 'Generated Lead',
              'displayName:zh-CN': 'Generated Lead',
            },
          ],
          fields: [
            {
              code: 'lead_name',
              dataType: 'string',
              constraints: { required: true, maxLength: 120 },
              'displayName:en': 'Lead Name',
              'displayName:zh-CN': 'Lead Name',
            },
            {
              code: 'company',
              dataType: 'string',
              'displayName:en': 'Company',
              'displayName:zh-CN': 'Company',
            },
            {
              code: 'phone',
              dataType: 'string',
              'displayName:en': 'Phone',
              'displayName:zh-CN': 'Phone',
            },
          ],
        },
      },
      timeout: 60_000,
    });
    const applyBody = await applyResponse.json().catch(() => ({}));
    expect(
      applyResponse.ok(),
      `nl-modeling apply failed: ${JSON.stringify(applyBody).slice(0, 1000)}`,
    ).toBe(true);
    assertApiSuccess(applyBody, 'nl-modeling apply');

    await openListFromSidebar(page, modelCode);

    const main = page.locator('main, [role="main"]').first();
    await expect(main).not.toContainText(/加载失败|Page not found/i);
    await expect(main.getByText(/Lead Name/i).first()).toBeVisible({ timeout: 15_000 });
    await expect(main.getByText(/Company/i).first()).toBeVisible({ timeout: 15_000 });

    await clickCreateFromList(page);
    await fillControlledInput(
      page.locator('[data-testid="form-field-lead_name"] input').first(),
      leadName,
    );
    await fillControlledInput(
      page.locator('[data-testid="form-field-company"] input').first(),
      `Generated Company ${runSuffix}`,
    );
    await fillControlledInput(
      page.locator('[data-testid="form-field-phone"] input').first(),
      `555-${runSuffix.slice(-4)}`,
    );

    await submitAndExpectCommand(page, `${pluginCode}:create_${modelCode}`);
    await expectRowVisible(page, modelCode, leadName);
    await testInfo.attach('prompt-to-app-generated-list', {
      body: await page.screenshot({ fullPage: true }),
      contentType: 'image/png',
    });
  });
});
