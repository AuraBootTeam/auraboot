/**
 * API Connector Lifecycle E2E Tests
 *
 * Covers the platform-admin DSL page `/p/api_connector` with real UI actions
 * and backend verification. The edit test guards JSON/JSONB form round-trips:
 * loaded PGobject envelopes must be shown as business JSON and must not be
 * saved back as nested `{type,value}` wrapper objects.
 */

import { test, expect } from '../../fixtures';
import type { Locator, Page } from '@playwright/test';
import {
  acceptConfirmDialog,
  clickRowActionByLocator,
  extractRecordId,
  fillControlledInput,
  findRowInPaginatedList,
  navigateToDynamicPage,
  uniqueId,
  waitForDynamicPageLoad,
} from '../helpers';

const PAGE_KEY = 'api-connector';
const CREATE_COMMAND = 'admin:create_api_connector';
const UPDATE_COMMAND = 'admin:update_api_connector';

async function waitForFormReady(page: Page) {
  await expect(page).toHaveURL(/\/(new|edit)/, { timeout: 10000 });
  await waitForDynamicPageLoad(page, 8000);
  await page
    .locator('button[role="switch"], input, select, textarea')
    .first()
    .waitFor({ state: 'visible', timeout: 8000 });
}

async function clickCreateButton(page: Page) {
  const createBtn = page.locator('[data-testid="toolbar-btn-create"]').first();
  await createBtn.waitFor({ state: 'visible', timeout: 5000 });
  await createBtn.click();
}

async function fillTextField(page: Page, fieldCode: string, value: string) {
  const input = page
    .locator(
      [
        `[data-testid="form-field-${fieldCode}"] input:visible`,
        `[data-field="${fieldCode}"] input:visible`,
        `input[name="${fieldCode}"]:visible`,
      ].join(', '),
    )
    .first();
  await fillControlledInput(input, value);
}

async function fillTextarea(page: Page, fieldCode: string, value: string) {
  const textarea = page
    .locator(
      [
        `[data-testid="form-field-${fieldCode}"] textarea:visible`,
        `[data-field="${fieldCode}"] textarea:visible`,
        `textarea[name="${fieldCode}"]:visible`,
      ].join(', '),
    )
    .first();
  await fillControlledInput(textarea, value);
}

async function selectAuthType(page: Page, value: string) {
  const nativeSelect = page
    .locator('[data-testid="form-field-auth_type"] select, select[name="auth_type"]')
    .first();
  if (await nativeSelect.isVisible({ timeout: 500 }).catch(() => false)) {
    await nativeSelect.selectOption(value);
    return;
  }

  const trigger = page.locator('[data-testid="select-trigger-auth_type"]').first();
  await trigger.waitFor({ state: 'visible', timeout: 5000 });
  await trigger.click();
  const option = page.locator(`[role="option"][data-value="${value}"]`).first();
  await option.waitFor({ state: 'visible', timeout: 5000 });
  await option.click();
  await expect(trigger).not.toHaveText(/请选择|Please select/i, { timeout: 5000 });
}

async function clickSaveAndWait(page: Page, expectedCommandCode: string) {
  const saveBtn = page
    .locator(
      '[data-testid="form-btn-submit"], [data-testid="form-btn-save"], button:has-text("保存"), button:has-text("Save")',
    )
    .first();
  await saveBtn.waitFor({ state: 'visible', timeout: 5000 });
  const respPromise = page.waitForResponse(
    (r) =>
      r.url().includes(`/commands/execute/${expectedCommandCode}`) &&
      r.request().method() === 'POST',
    { timeout: 15000 },
  );
  await saveBtn.click();
  const resp = await respPromise;
  const body = await resp.json().catch(async () => ({ raw: await resp.text().catch(() => '') }));
  expect(resp.ok(), `Command ${expectedCommandCode} failed: ${JSON.stringify(body)}`).toBe(true);
  expect(String(body?.code ?? ''), JSON.stringify(body)).toBe('0');
  return body;
}

async function fetchConnectorByPid(page: Page, pid: string): Promise<Record<string, any>> {
  const resp = await page.request.get(`/api/dynamic/api_connector/${pid}`);
  expect(resp.ok()).toBeTruthy();
  const body = await resp.json().catch(() => ({}));
  expect(String(body?.code ?? ''), JSON.stringify(body)).toBe('0');
  return body.data ?? {};
}

function parseJsonField(value: unknown): unknown {
  if (value && typeof value === 'object' && 'value' in value) {
    const raw = (value as Record<string, unknown>).value;
    return typeof raw === 'string' ? JSON.parse(raw) : raw;
  }
  return typeof value === 'string' ? JSON.parse(value) : value;
}

function expectBusinessJson(value: unknown, expected: Record<string, unknown>) {
  const parsed = parseJsonField(value);
  expect(parsed).toEqual(expected);
  expect(parsed).not.toHaveProperty('type');
  expect(parsed).not.toHaveProperty('value');
}

async function connectorRow(page: Page, name: string): Promise<Locator> {
  await navigateToDynamicPage(page, PAGE_KEY);
  await waitForDynamicPageLoad(page, 10000);
  return findRowInPaginatedList(page, name, 15000);
}

test.describe.serial('API Connector Lifecycle', () => {
  test.describe.configure({ timeout: 60000 });

  let connectorName = `API-${uniqueId('conn')}`;
  let connectorPid = '';

  test('AC-001: list page layout and create button are available @smoke', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEY);
    await waitForDynamicPageLoad(page, 10000);

    await expect(page.locator('[data-testid="dynamic-page-list"]')).toBeVisible();
    await expect(page.locator('[data-testid="toolbar-btn-create"]')).toBeVisible();
    await expect(page.locator('[data-testid="table-header-name"]')).toBeVisible();
    await expect(page.locator('[data-testid="table-header-auth_type"]')).toBeVisible();
  });

  test('AC-002: create connector via UI and verify backend JSON fields', async ({ page }) => {
    await navigateToDynamicPage(page, PAGE_KEY);
    await waitForDynamicPageLoad(page, 10000);
    await clickCreateButton(page);
    await waitForFormReady(page);

    await fillTextField(page, 'name', connectorName);
    await fillTextField(page, 'base_url', 'https://api.codex.local');
    await selectAuthType(page, 'none');
    await fillTextField(page, 'timeout_ms', '8000');
    await fillTextarea(page, 'default_headers', '{"X-Codex-QA":"true"}');
    await fillTextarea(page, 'retry_policy', '{"maxRetries":2}');

    const body = await clickSaveAndWait(page, CREATE_COMMAND);
    connectorPid = extractRecordId(body);
    expect(connectorPid).toBeTruthy();

    const created = await fetchConnectorByPid(page, connectorPid);
    expect(created.name).toBe(connectorName);
    expect(created.auth_type).toBe('none');
    expect(created.timeout_ms).toBe(8000);
    expectBusinessJson(created.default_headers, { 'X-Codex-QA': 'true' });
    expectBusinessJson(created.retry_policy, { maxRetries: 2 });

    const row = await connectorRow(page, connectorName);
    await expect(row).toBeVisible();
  });

  test('AC-003: edit connector preserves JSON fields without nested wrapper', async ({ page }) => {
    const row = await connectorRow(page, connectorName);
    await clickRowActionByLocator(page, row, 'edit');
    await waitForFormReady(page);

    await expect(page.locator('textarea[name="default_headers"]')).toHaveValue(
      /"X-Codex-QA": "true"|"X-Codex-QA":"true"/,
      { timeout: 10000 },
    );
    await expect(page.locator('textarea[name="retry_policy"]')).toHaveValue(
      /"maxRetries": 2|"maxRetries":2/,
      { timeout: 10000 },
    );

    await fillTextField(page, 'timeout_ms', '9100');
    await clickSaveAndWait(page, UPDATE_COMMAND);

    const updated = await fetchConnectorByPid(page, connectorPid);
    expect(updated.timeout_ms).toBe(9100);
    expectBusinessJson(updated.default_headers, { 'X-Codex-QA': 'true' });
    expectBusinessJson(updated.retry_policy, { maxRetries: 2 });
  });

  test('AC-004: delete connector via row action and verify backend removal', async ({ page }) => {
    const row = await connectorRow(page, connectorName);
    await clickRowActionByLocator(page, row, 'delete');
    await acceptConfirmDialog(page);

    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/dynamic/api_connector/${connectorPid}`);
          if (resp.status() === 404) return 'deleted';
          const body = await resp.json().catch(() => ({}));
          return body?.data?.deleted_flag === true || body?.code !== '0' ? 'deleted' : 'exists';
        },
        { timeout: 15000, intervals: [500, 1000, 1500] },
      )
      .toBe('deleted');

    await navigateToDynamicPage(page, PAGE_KEY);
    await waitForDynamicPageLoad(page, 10000);
    await expect(page.locator('tbody tr', { hasText: connectorName })).toHaveCount(0, {
      timeout: 15000,
    });
  });
});
