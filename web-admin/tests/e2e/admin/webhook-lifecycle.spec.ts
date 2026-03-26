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
import {
  navigateToDynamicPage,
  waitForDynamicPageLoad,
  uniqueId,
  acceptConfirmDialog,
  findRowInPaginatedList,
  clickSaveButton,
  clickRowActionByLocator,
} from '../helpers';
import { ModelTestHelper } from '../../helpers/model-test-helper';
import { ADMIN_WEBHOOK_CONFIG } from '../../helpers/configs/admin-webhook.config';

const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://127.0.0.1:5173';

test.describe.serial('Webhook Lifecycle', () => {
  let helper: ModelTestHelper;
  let seedPid: string;
  const seedName = `WH-${uniqueId('seed')}`;
  const createName = `WH-${uniqueId('create')}`;
  const editSuffix = uniqueId('edit');

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({
      storageState: './tests/storage/admin.json',
    });
    const page = await ctx.newPage();
    helper = new ModelTestHelper(page, ADMIN_WEBHOOK_CONFIG);

    // Create a seed webhook subscription via API
    seedPid = await helper.createViaApi({
      name: seedName,
      target_url: 'https://httpbin.org/post',
      event_type: 'record_created',
      max_retries: 1,
      timeout_ms: 5000,
      enabled: true,
    });

    // Trigger a test delivery to generate delivery log
    try {
      const resp = await page.request.post(
        `${BASE_URL}/api/webhooks/${seedPid}/test`,
        {
          data: { _eventId: `test-event-${Date.now()}`, test: true },
          headers: { 'Content-Type': 'application/json' },
        },
      );
      expect(resp.ok()).toBeTruthy();
    } catch {
      // Test endpoint may fail if target is unreachable — that's OK,
      // it still creates a delivery log entry (failed status)
    }

    await ctx.close();
  });

  test('WH-001: Smoke — menu navigation to webhook list shows data', async ({
    page,
  }) => {
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page, 10000);

    // Verify at least 1 row is visible
    const rows = page.locator('tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 10000 });
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(1);
  });

  test('WH-002: Create webhook via UI form', async ({ page }) => {
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);

    // Click create button
    const createBtn = page.locator(
      '[data-testid="toolbar-button-create"], button:has-text("create"), button:has-text("新建"), button:has-text("Create")',
    ).first();
    await createBtn.click();

    // Wait for form page
    await expect(page).toHaveURL(/\/new/, { timeout: 10000 });
    await waitForDynamicPageLoad(page, 8000);
    await page
      .locator('button[role="switch"], input, select, textarea')
      .first()
      .waitFor({ state: 'visible', timeout: 8000 });

    // Fill form fields
    const nameInput = page.locator(
      '[data-testid="form-field-name"] input, [name="name"]',
    ).first();
    await nameInput.fill(createName);

    const urlInput = page.locator(
      '[data-testid="form-field-target_url"] input, [name="target_url"]',
    ).first();
    await urlInput.fill('https://example.com/webhook-e2e');

    // Select event type
    const eventTypeSelect = page.locator(
      '[data-testid="form-field-event_type"]',
    ).first();
    if (await eventTypeSelect.isVisible({ timeout: 2000 }).catch(() => false)) {
      const nativeSelect = eventTypeSelect.locator('select').first();
      if (await nativeSelect.count()) {
        await nativeSelect.selectOption('record_created');
      } else {
        await eventTypeSelect.locator('.ant-select').first().click();
        await page
          .locator(
            '[data-testid="option-RECORD_CREATED"], .ant-select-item:has-text("record_created"), .ant-select-item:has-text("Record Created"), .ant-select-item:has-text("记录创建")',
          )
          .first()
          .click();
      }
    }

    await clickSaveButton(page);
    await page.waitForURL(
      (url) => url.pathname.includes('/dynamic/webhook') && !url.pathname.includes('/new'),
      { timeout: 15000 },
    ).catch(() => null);

    // Verify record exists in the real list UI
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);
    await expect(
      page.locator('tbody tr', { hasText: createName }).first(),
    ).toBeVisible({ timeout: 15000 });
  });

  test('WH-003: Detail page shows basic info and delivery logs', async ({
    page,
  }) => {
    await page.goto('/settings/webhooks', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="webhook-list"], [data-testid="webhook-empty"]')).toBeVisible({ timeout: 15000 });

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
    await expect(row).toContainText(/success|failed|pending/, { timeout: 8000 });
  });

  test('WH-004: Edit webhook name', async ({ page }) => {
    await navigateToDynamicPage(page, 'webhook');
    await waitForDynamicPageLoad(page);

    const row = await findRowInPaginatedList(page, seedName, 15000);
    expect(row).toBeTruthy();

    // Click edit action (handles primary slot + "more actions" dropdown)
    await clickRowActionByLocator(page, row!, 'edit');

    // Wait for form
    await expect(page).toHaveURL(/\/(edit)/, { timeout: 10000 });
    await waitForDynamicPageLoad(page, 8000);
    await page
      .locator('button[role="switch"], input, select, textarea')
      .first()
      .waitFor({ state: 'visible', timeout: 8000 });

    // Update name
    const newName = `${seedName}-${editSuffix}`;
    const nameInput = page.locator(
      '[data-testid="form-field-name"] input, [name="name"]',
    ).first();
    await nameInput.clear();
    await nameInput.fill(newName);

    // Submit
    await clickSaveButton(page);
    await expect(page).toHaveURL(/\/dynamic\/webhook/, { timeout: 15000 });

    // Verify the edited record is visible in the real list UI
    await navigateToDynamicPage(page, 'webhook');
    await expect(
      page.locator('tbody tr', { hasText: newName }).first(),
    ).toBeVisible({ timeout: 15000 });
  });

  test('WH-005: Delete webhook', async ({ page }) => {
    // Create a disposable webhook to delete
    const deleteName = `WH-${uniqueId('del')}`;
    const tempCtx = await page.context().browser()!.newContext({
      storageState: './tests/storage/admin.json',
    });
    const tempPage = await tempCtx.newPage();
    const tempHelper = new ModelTestHelper(tempPage, ADMIN_WEBHOOK_CONFIG);
    const deletePid = await tempHelper.createViaApi({
      name: deleteName,
      target_url: 'https://example.com/delete-test',
      event_type: 'record_deleted',
      max_retries: 0,
      timeout_ms: 5000,
      enabled: false,
    });
    await tempPage.close();
    await tempCtx.close();

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
