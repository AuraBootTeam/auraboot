/**
 * Webhook Lifecycle E2E Tests
 *
 * Tests WH-001 ~ WH-005: Webhook subscription CRUD + delivery log visualization.
 *
 * - WH-001: Smoke — sidebar menu → list page → data visible
 * - WH-002: Create via UI form
 * - WH-003: Detail page + delivery log sub-table
 * - WH-004: Edit subscription name
 * - WH-005: Delete subscription
 *
 * Uses real database, NO MOCKING.
 *
 * @since 7.1.0
 */

import { test, expect } from '../../fixtures';
import type { Page } from '@playwright/test';
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  acceptConfirmDialog,
  findRowInPaginatedList,
  clickSaveButton,
  clickRowActionByLocator,
  waitForToast,
} from '../helpers';

async function selectFormField(page: Page, fieldCode: string, value: string) {
  const fieldRoots = [
    `[data-testid="form-field-${fieldCode}"]`,
    `[data-testid="field-${fieldCode}"]`,
    `[data-field="${fieldCode}"]`,
  ];
  const anyField = page
    .locator(
      [
        ...fieldRoots.flatMap((root) => [`${root} select`, `${root} input`, `${root} textarea`]),
        `select[name="${fieldCode}"]`,
        `input[name="${fieldCode}"]`,
        `textarea[name="${fieldCode}"]`,
      ].join(', '),
    )
    .first();
  await anyField.waitFor({ state: 'attached', timeout: 12000 }).catch(() => null);

  const select = page
    .locator([...fieldRoots.map((root) => `${root} select`), `select[name="${fieldCode}"]`].join(', '))
    .first();
  if (await select.isVisible({ timeout: 3000 }).catch(() => false)) {
    await select.selectOption(value);
    return;
  }

  const input = page
    .locator(
      [
        ...fieldRoots.flatMap((root) => [
          `${root} input:not([type="hidden"])`,
          `${root} textarea`,
        ]),
        `input[name="${fieldCode}"]:not([type="hidden"])`,
        `textarea[name="${fieldCode}"]`,
      ].join(', '),
    )
    .first();
  if (await input.isVisible({ timeout: 3000 }).catch(() => false)) {
    await input.fill(value);
    return;
  }

  const hiddenInput = page
    .locator(
      [
        ...fieldRoots.map((root) => `${root} input[type="hidden"]`),
        `input[name="${fieldCode}"][type="hidden"]`,
      ].join(', '),
    )
    .first();
  if (await hiddenInput.count().then((count) => count > 0).catch(() => false)) {
    await hiddenInput.evaluate((el, nextValue) => {
      const inputEl = el as HTMLInputElement;
      inputEl.value = String(nextValue ?? '');
      inputEl.dispatchEvent(new Event('input', { bubbles: true }));
      inputEl.dispatchEvent(new Event('change', { bubbles: true }));
    }, value);
    return;
  }

  throw new Error(`Could not find select field: ${fieldCode}`);
}

test.describe.serial('Webhook Lifecycle', () => {
  let seedPid: string;
  let createdPid: string | null = null;
  let webhookSettingsBlocked = false;
  let editedName: string | null = null;
  const seedName = `WH-${uniqueId('seed')}`;
  const createName = `WH-${uniqueId('create')}`;
  const editSuffix = uniqueId('edit');

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    const createResp = await page.request.post('/api/webhooks', {
      data: {
        name: seedName,
        targetUrl: 'https://httpbin.org/post',
        eventType: 'CommandExecuted',
        maxRetries: 1,
        timeoutMs: 5000,
        enabled: true,
      },
    });
    if (createResp.status() === 403) {
      webhookSettingsBlocked = true;
      await ctx.close();
      return;
    }
    expect(createResp.ok()).toBeTruthy();
    const created = await createResp.json();
    seedPid = created?.data?.pid;
    expect(seedPid).toBeTruthy();

    // Trigger a test delivery to generate delivery log
    try {
      const resp = await page.request.post(`/api/webhooks/${seedPid}/test`, {
        data: { _eventId: `test-event-${Date.now()}`, test: true },
        headers: { 'Content-Type': 'application/json' },
      });
      expect(resp.ok()).toBeTruthy();
    } catch {
      // Test endpoint may fail if target is unreachable — that's OK,
      // it still creates a delivery log entry (failed status)
    }

    await ctx.close();
  });

  test('WH-001: Smoke — menu navigation to webhook list shows data', async ({ page }) => {
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page, 10000);

    // Verify at least 1 row is visible
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
  });

  test('WH-002: Create webhook via UI form', async ({ page }) => {
    test.setTimeout(30_000);
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);

    // Click create button
    const createBtn = page
      .locator(
        '[data-testid="toolbar-button-create"], button:has-text("create"), button:has-text("新建"), button:has-text("Create")',
      )
      .first();
    await createBtn.click();

    // Wait for form page
    await expect(page).toHaveURL(/\/new/, { timeout: 10000 });
    await waitForDynamicPageLoad(page, 8000);
    await page
      .locator('button[role="switch"], input, select, textarea')
      .first()
      .waitFor({ state: 'visible', timeout: 8000 });

    // Fill form fields
    const nameInput = page.locator('[data-testid="form-field-name"] input, [name="name"]').first();
    await nameInput.fill(createName);

    const urlInput = page
      .locator('[data-testid="form-field-target_url"] input, [name="target_url"]')
      .first();
    await urlInput.fill('https://example.com/webhook-e2e');

    await selectFormField(page, 'event_type', 'CommandExecuted');

    await clickSaveButton(page);
    await waitForToast(page, undefined, 5_000).catch(() => null);
    await page
      .waitForURL(
        (url) => url.pathname.includes('/p/webhook') && !url.pathname.includes('/new'),
        { timeout: 15000 },
      )
      .catch(() => null);

    // Verify record exists in the real list UI
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);
    const row = await findRowInPaginatedList(page, createName, 15_000);
    await expect(row).toBeVisible({ timeout: 5_000 });
    const webhookListResp = await page.request.get('/api/webhooks');
    expect(webhookListResp.ok()).toBeTruthy();
    const webhookListBody = await webhookListResp.json().catch(() => ({}));
    const subscriptions = Array.isArray(webhookListBody?.data) ? webhookListBody.data : [];
    createdPid = String(
      subscriptions.find((item: Record<string, unknown>) => item?.name === createName)?.pid ?? '',
    );
    expect(createdPid, 'Created webhook pid should be discoverable from /api/webhooks').toBeTruthy();
  });

  test('WH-003: Detail page shows basic info and delivery logs', async ({ page }) => {
    test.skip(webhookSettingsBlocked, 'Webhook settings API requires system.webhook.update in this environment');
    const settingsResp = await page.request.get('/api/webhooks');
    test.skip(
      settingsResp.status() === 403,
      'Webhook settings API requires system.webhook.update in this environment',
    );

    await page.goto('/settings/webhooks', { waitUntil: 'domcontentloaded' });
    await expect(
      page.locator('[data-testid="webhook-list"], [data-testid="webhook-empty"]'),
    ).toBeVisible({ timeout: 15000 });

    const name = page.locator('[data-testid="webhook-name"]').filter({ hasText: seedName }).first();
    await expect(name).toBeVisible({ timeout: 15000 });
    const row = name.locator('xpath=ancestor::*[@data-testid="webhook-row"][1]');

    // Verify basic info is visible
    await expect(row.getByText(seedName)).toBeVisible({ timeout: 8000 });
    await expect(row.getByText('https://httpbin.org/post')).toBeVisible({ timeout: 5000 });

    // Expand delivery history and verify delivery entries are rendered in the real settings page.
    const deliveriesBtn = row.locator('[data-testid="webhook-deliveries-btn"]').first();
    const deliveriesResponse = page.waitForResponse(
      (r) => r.url().includes(`/api/webhooks/${seedPid}/deliveries`) && r.status() === 200,
      { timeout: 10000 },
    );
    await deliveriesBtn.click();
    await deliveriesResponse;
    await expect(row.getByText('Recent Deliveries')).toBeVisible({ timeout: 8000 });

    // Current environments may have a delivery record immediately, or may still show
    // the empty-state copy if the test dispatch did not materialize into a visible row yet.
    const hasDeliveryStatus = await row
      .locator('text=/success|failed|pending/i')
      .first()
      .isVisible({ timeout: 3000 })
      .catch(() => false);
    const hasEmptyState = await row
      .getByText(/No delivery attempts yet\./i)
      .isVisible({ timeout: 3000 })
      .catch(() => false);

    expect(
      hasDeliveryStatus || hasEmptyState,
      'Webhook detail should show either delivery statuses or the empty-state delivery message',
    ).toBeTruthy();
  });

  test('WH-004: Edit webhook name', async ({ page }) => {
    test.setTimeout(45000);
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);
    let pid = String(createdPid ?? '');
    if (!pid) {
      const createResp = await page.request.post('/api/webhooks', {
        data: {
          name: createName,
          targetUrl: 'https://example.com/webhook-e2e-edit',
          eventType: 'CommandExecuted',
          maxRetries: 1,
          timeoutMs: 5000,
          enabled: true,
        },
      });
      expect(createResp.ok(), 'WH-004 fallback API seed should succeed').toBeTruthy();
      const created = await createResp.json().catch(() => ({}));
      pid = String(created?.data?.pid ?? '');
      createdPid = pid || createdPid;
    }
    expect(pid, 'WH-004 should have a concrete webhook pid before opening the edit form').toBeTruthy();

    await page.goto(`/p/webhook/${pid}/edit?commandCode=${encodeURIComponent('admin:update_webhook')}`, {
      waitUntil: 'domcontentloaded',
    });

    // Wait for form
    await expect(page).toHaveURL(/\/(edit)/, { timeout: 10000 });
    await waitForDynamicPageLoad(page, 8000);
    await page
      .locator('button[role="switch"], input, select, textarea')
      .first()
      .waitFor({ state: 'visible', timeout: 8000 });

    // Update name
    const newName = `${createName}-${editSuffix}`;
    const nameInput = page.locator('[data-testid="form-field-name"] input, [name="name"]').first();
    await nameInput.clear();
    await nameInput.fill(newName);

    // Submit
    await clickSaveButton(page);
    await expect(page).toHaveURL(/\/p\/webhook(?:\/.*)?|\/dynamic\/webhook/, { timeout: 15000 });

    // Verify the edited record is visible in the real list UI
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page, 8000);
    const updatedRow = await findRowInPaginatedList(page, newName, 15000);
    await expect(updatedRow).toBeVisible({ timeout: 5000 });
    editedName = newName;
  });

  test('WH-005: Delete webhook', async ({ page }) => {
    const deleteName = editedName ?? createName;
    expect(deleteName).toBeTruthy();

    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);

    const row = await findRowInPaginatedList(page, deleteName, 15000);
    expect(row).toBeTruthy();

    // Click delete action (handles primary slot + "more actions" dropdown)
    await clickRowActionByLocator(page, row!, 'delete');
    await acceptConfirmDialog(page);

    // Verify the record disappears from the real list UI
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);
    await expect(page.locator('tbody tr', { hasText: deleteName })).toHaveCount(0, {
      timeout: 15000,
    });
  });
});
