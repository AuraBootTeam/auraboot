/**
 * E2E Test: Shared SavedView local draft and actions
 *
 * Covers:
 *   SV-P0-02 — Shared view changes become local drafts first
 *   SV-P0-05 — Save/copy actions follow backend actions/effective permissions
 *
 * Uses e2et_order and real UI interactions:
 *   dashboards menu → e2et_order list → View Selector → Column Settings.
 */

import { expect, test, type APIResponse, type Browser, type Page } from '@playwright/test';
import { mkdir, readFile } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { acceptConfirmDialog, uniqueId } from '../helpers';

const MODEL_CODE = 'e2et_order';
const SAVED_VIEW_PAGE_KEY = 'e2et_order_list';
const ADMIN_STORAGE_STATE = process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json';
const VIEWER_STORAGE_STATE = process.env.PW_VIEWER_STORAGE_STATE || 'tests/storage/viewer.json';
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173';
const SCREENSHOT_DIR = 'test-results/saved-view-vnext';

async function expectApiJson(response: APIResponse, label: string) {
  const bodyText = await response.text();
  expect(
    response.ok(),
    `${label} failed: ${response.status()} ${response.statusText()} ${bodyText}`,
  ).toBe(true);
  return bodyText ? JSON.parse(bodyText) : {};
}

function storagePath(path: string): string {
  return isAbsolute(path) ? path : resolve(process.cwd(), path);
}

async function expectStorageStateReady(path: string, label: string): Promise<void> {
  const raw = await readFile(storagePath(path), 'utf-8');
  const state = JSON.parse(raw);
  const cookieCount = Array.isArray(state.cookies) ? state.cookies.length : 0;
  expect(
    cookieCount,
    `${label} storage state is empty. Run auth with PW_ROLE_PROJECTS=1 before role-matrix E2E.`,
  ).toBeGreaterThan(0);
}

async function withRolePage<T>(
  browser: Browser,
  storageState: string,
  action: (page: Page) => Promise<T>,
): Promise<T> {
  const context = await browser.newContext({ baseURL: BASE_URL, storageState });
  const page = await context.newPage();
  try {
    return await action(page);
  } finally {
    await context.close();
  }
}

async function navigateToOrderList(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 10_000 });

  const rootBtn = nav
    .getByRole('button', { name: /E2E测试|E2E Test|移动端测试/i })
    .first();
  await rootBtn.scrollIntoViewIfNeeded();
  await rootBtn.evaluate((el: HTMLElement) => el.click());

  const leafLink = nav.locator(`a[href="/p/${MODEL_CODE}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8_000 });

  const listResponsePromise = page.waitForResponse(
    (r) =>
      r.url().includes('/api/dynamic/e2et_order') &&
      r.url().includes('list') &&
      r.status() === 200,
    { timeout: 20_000 },
  );
  await Promise.all([
    page.waitForURL((url) => url.pathname === `/p/${MODEL_CODE}`, { timeout: 10_000 }),
    leafLink.click(),
  ]);
  await listResponsePromise;

  await expect(page.locator('table, [data-testid="dynamic-list"]').first()).toBeVisible({
    timeout: 15_000,
  });
  await expect(page.getByTestId('view-selector-trigger')).toBeVisible({ timeout: 15_000 });
}

async function openViewSelectorDropdown(page: Page) {
  const trigger = page.getByTestId('view-selector-trigger');
  await expect(trigger).toBeVisible({ timeout: 10_000 });
  await trigger.click();
  const dropdown = page.locator('[role="listbox"]').first();
  await expect(dropdown).toBeVisible({ timeout: 5_000 });
  return dropdown;
}

async function selectSavedViewByName(page: Page, name: string): Promise<void> {
  const dropdown = await openViewSelectorDropdown(page);
  const option = dropdown.getByRole('option').filter({ hasText: name }).first();
  await expect(option).toBeVisible({ timeout: 8_000 });
  await option.click();
  await expect(dropdown).not.toBeVisible({ timeout: 5_000 });
  await expect(page.getByTestId('view-selector-trigger')).toContainText(name, {
    timeout: 8_000,
  });
}

async function openColumnSettingsPanel(page: Page) {
  await expect(page.getByTestId('column-settings-btn')).toBeVisible({ timeout: 8_000 });
  await page.getByTestId('column-settings-btn').click();
  const panel = page.getByTestId('column-settings-panel');
  await expect(panel).toBeVisible({ timeout: 5_000 });
  return panel;
}

function compactText(text: string): string {
  return text.replace(/\s+/g, '').trim();
}

function headersContainLabel(headers: string[], label: string): boolean {
  const needle = compactText(label);
  return Boolean(needle) && headers.some((header) => compactText(header).includes(needle));
}

async function hideFirstVisibleColumnThroughSettings(page: Page) {
  const headersBefore = await page.locator('thead th').allTextContents();
  const panel = await openColumnSettingsPanel(page);
  const columnItems = panel.locator('div[draggable="true"]');
  const itemCount = await columnItems.count();
  expect(itemCount).toBeGreaterThan(2);

  let targetIdx = -1;
  let targetLabel = '';
  for (let i = 0; i < itemCount; i += 1) {
    const item = columnItems.nth(i);
    const checkbox = item.locator('input[type="checkbox"]');
    const label = (await item.locator('span.truncate, span.flex-1').textContent())?.trim() ?? '';
    if ((await checkbox.isChecked()) && headersContainLabel(headersBefore, label)) {
      targetIdx = i;
      targetLabel = label;
      break;
    }
  }

  expect(
    targetIdx,
    `No visible configurable column found in headers: ${headersBefore.join(' | ')}`,
  ).toBeGreaterThanOrEqual(0);

  const targetItem = columnItems.nth(targetIdx);
  const rowTestId = await targetItem.getAttribute('data-testid');
  const targetFieldCode = rowTestId?.replace('column-settings-row-', '') ?? '';
  expect(targetFieldCode).toBeTruthy();

  const checkbox = targetItem.locator('input[type="checkbox"]');
  await checkbox.uncheck();
  await expect(checkbox).not.toBeChecked();

  await page.getByTestId('column-settings-save').click();
  await expect(panel).not.toBeVisible({ timeout: 5_000 });

  return { targetFieldCode, targetLabel };
}

function trackSourceWrites(page: Page, sourceViewPid: string): string[] {
  const writes: string[] = [];
  page.on('request', (request) => {
    const url = new URL(request.url());
    const method = request.method();
    const isSourceMutation =
      url.pathname === `/api/views/${sourceViewPid}` && ['PUT', 'PATCH', 'DELETE'].includes(method);
    const isImplicitAutosave =
      url.pathname === '/api/views/auto-save' && ['POST', 'PUT', 'PATCH'].includes(method);
    if (isSourceMutation || isImplicitAutosave) {
      writes.push(`${method} ${url.pathname} ${request.postData() ?? ''}`);
    }
  });
  return writes;
}

async function createGlobalOrderView(page: Page, name: string): Promise<string> {
  const resp = await page.request.post('/api/views', {
    data: {
      name,
      modelCode: MODEL_CODE,
      pageKey: SAVED_VIEW_PAGE_KEY,
      viewType: 'table',
      scope: 'global',
      viewConfig: {
        rowHeight: 'medium',
        columns: [
          { fieldCode: 'e2et_order_title', visible: true, order: 0 },
          { fieldCode: 'e2et_order_status', visible: true, order: 1 },
          { fieldCode: 'e2et_order_type', visible: true, order: 2 },
        ],
      },
    },
  });
  const body = await expectApiJson(resp, 'create global order saved view');
  const pid = body?.data?.pid;
  expect(pid).toBeTruthy();
  return pid;
}

async function seedOrderRecord(page: Page, prefix: string): Promise<void> {
  const resp = await page.request.post(`/api/dynamic/${MODEL_CODE}/create`, {
    data: {
      e2et_order_title: `${prefix}_${uniqueId()}`,
      e2et_order_type: 'normal',
      e2et_order_status: 'draft',
      e2et_order_urgent: false,
    },
  });
  const body = await expectApiJson(resp, 'create shared draft order record');
  expect(body.code).toBe('0');
}

async function screenshot(page: Page, fileName: string): Promise<void> {
  await mkdir(SCREENSHOT_DIR, { recursive: true });
  await page.screenshot({ path: `${SCREENSHOT_DIR}/${fileName}`, fullPage: true });
}

test.describe('SavedView shared local draft actions', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async ({ browser }) => {
    await expectStorageStateReady(ADMIN_STORAGE_STATE, 'admin');
    await expectStorageStateReady(VIEWER_STORAGE_STATE, 'viewer');
    await withRolePage(browser, ADMIN_STORAGE_STATE, async (page) => {
      await seedOrderRecord(page, 'SharedDraft');
    });
  });

  test('SV-SD-001: viewer keeps global view changes local and can copy draft to personal', async ({
    browser,
  }) => {
    let sourceViewPid = '';
    const viewName = `SV_Global_ReadOnly_${uniqueId()}`;
    await withRolePage(browser, ADMIN_STORAGE_STATE, async (page) => {
      sourceViewPid = await createGlobalOrderView(page, viewName);
    });

    await withRolePage(browser, VIEWER_STORAGE_STATE, async (page) => {
      await navigateToOrderList(page);
      await selectSavedViewByName(page, viewName);

      const sourceWrites = trackSourceWrites(page, sourceViewPid);
      const { targetFieldCode } = await hideFirstVisibleColumnThroughSettings(page);

      await expect(page.getByTestId('shared-view-draft-banner')).toBeVisible({ timeout: 5_000 });
      await expect(page.getByTestId('shared-view-save-disabled')).toBeVisible();
      await expect(page.getByTestId('shared-view-save-disabled')).toBeDisabled();
      await expect(page.getByTestId('shared-view-copy-to-personal')).toBeEnabled();
      expect(sourceWrites, 'viewer must not mutate source global view or implicit autosave').toEqual(
        [],
      );

      const copyResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes(`/api/views/${sourceViewPid}/copy-to-personal`) &&
          response.request().method() === 'POST',
        { timeout: 10_000 },
      );
      await page.getByTestId('shared-view-copy-to-personal').click();
      const copyResponse = await copyResponsePromise;
      const copyBody = await expectApiJson(copyResponse, 'copy shared draft to personal');
      expect(copyBody?.data?.scope).toBe('personal');
      expect(copyBody?.data?.viewConfig?.columns).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ fieldCode: targetFieldCode, visible: false }),
        ]),
      );

      await expect(page.getByTestId('shared-view-draft-banner')).not.toBeVisible({
        timeout: 5_000,
      });
      await expect(page.getByTestId('view-selector-trigger')).toContainText('My Copy', {
        timeout: 8_000,
      });
      expect(sourceWrites, 'copying a local draft still must not mutate the source view').toEqual([]);
      await screenshot(page, 'shared-draft-actions-viewer.png');
    });
  });

  test('SV-SD-002: admin saves global view changes only after shared-save confirmation', async ({
    browser,
  }) => {
    let sourceViewPid = '';
    const viewName = `SV_Global_Admin_${uniqueId()}`;
    await withRolePage(browser, ADMIN_STORAGE_STATE, async (page) => {
      sourceViewPid = await createGlobalOrderView(page, viewName);
    });

    await withRolePage(browser, ADMIN_STORAGE_STATE, async (page) => {
      await navigateToOrderList(page);
      await selectSavedViewByName(page, viewName);

      const sourceWrites = trackSourceWrites(page, sourceViewPid);
      const { targetFieldCode } = await hideFirstVisibleColumnThroughSettings(page);

      await expect(page.getByTestId('shared-view-draft-banner')).toBeVisible({ timeout: 5_000 });
      await expect(page.getByTestId('shared-view-save')).toBeVisible();
      await expect(page.getByTestId('shared-view-save')).toBeEnabled();
      expect(sourceWrites, 'admin column-setting save must stage a draft before confirmation').toEqual(
        [],
      );

      const updateResponsePromise = page.waitForResponse(
        (response) =>
          response.url().includes(`/api/views/${sourceViewPid}`) &&
          response.request().method() === 'PUT',
        { timeout: 10_000 },
      );
      await page.getByTestId('shared-view-save').click();
      await acceptConfirmDialog(page);
      const updateResponse = await updateResponsePromise;
      const updateBody = await expectApiJson(updateResponse, 'save shared global draft');
      expect(updateBody?.data?.pid).toBe(sourceViewPid);
      expect(updateBody?.data?.viewConfig?.columns).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ fieldCode: targetFieldCode, visible: false }),
        ]),
      );

      await expect(page.getByTestId('shared-view-draft-banner')).not.toBeVisible({
        timeout: 5_000,
      });
      expect(sourceWrites.filter((entry) => entry.startsWith('PUT '))).toHaveLength(1);
      await screenshot(page, 'shared-draft-actions-admin.png');
    });
  });
});
