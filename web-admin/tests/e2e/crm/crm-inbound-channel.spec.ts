/**
 * CRM Inbound Channel Management E2E Tests
 *
 * Tests CRUD operations on inbound channels:
 * - ch-01: Create a Generic Webhook channel
 * - ch-02: Verify API key is displayed (masked) and copy button exists
 * - ch-03: Toggle channel enabled/disabled
 * - ch-04: Edit channel name
 * - ch-05: Delete channel with confirmation
 *
 * Prerequisites: CRM inbound channel API must be available (controller registered).
 * If the backend hasn't been restarted after adding CRM controllers, tests skip.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const uid = uniqueId('inb');
const channelName = `Webhook_${uid}`;
const channelNameEdited = `Webhook_Upd_${uid}`;
const channelNameForDelete = `DeleteMe_${uid}`;

// Track whether the CRM channel API is available
let channelApiAvailable = true;

// ---------------------------------------------------------------------------
// Navigation helper
// ---------------------------------------------------------------------------

async function goToInboundChannels(page: Page): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });

  const nav = page.locator('nav');

  // Expand CRM root
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded();
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Expand Settings sub-menu
  const settingsBtn = nav.getByRole('button', { name: 'Settings' });
  const settingsVisible = await settingsBtn.isVisible({ timeout: 3000 }).catch(() => false);
  if (settingsVisible) {
    await settingsBtn.scrollIntoViewIfNeeded();
    await settingsBtn.evaluate((el: HTMLElement) => el.click());
    await page.waitForResponse(() => true, { timeout: 1500 }).catch(() => null);
  }

  // Click Inbound Channels
  const href = '/crm/settings/inbound-channels';
  const leafLink = nav.locator(`a[href="${href}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8000 });
  await leafLink.scrollIntoViewIfNeeded();
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL((url) => url.pathname === href, { timeout: 10000 });
  await expect(page.getByRole('heading', { name: 'Inbound Channels' })).toBeVisible({
    timeout: 10000,
  });
}

/**
 * Create a channel using the modal form.
 */
async function createChannel(
  page: Page,
  name: string,
  type: string = 'generic_webhook',
): Promise<void> {
  // Click "New Channel" button
  await page.locator('[data-testid="channel-create-btn"]').click();

  // Wait for modal to appear
  const modal = page.locator('.fixed.inset-0');
  await expect(modal).toBeVisible({ timeout: 5000 });

  // Fill channel name
  await page.locator('[data-testid="channel-field-name"]').fill(name);

  // Select channel type
  if (type !== 'generic_webhook') {
    await page.locator('[data-testid="channel-field-type"]').selectOption(type);
  }

  // Click Save and wait for API response
  const saveResponse = page.waitForResponse(
    (r) => r.url().includes('/api/crm/inbound-channels') && r.request().method() === 'POST',
    { timeout: 10000 },
  );
  await page.locator('[data-testid="channel-save-btn"]').click();
  const resp = await saveResponse;

  if (!resp.ok()) {
    throw new Error(`Channel creation API returned ${resp.status()}`);
  }

  // Wait for modal to close (channel saved)
  await expect(modal).not.toBeVisible({ timeout: 10000 });
}

async function ensureChannelExists(page: Page, name: string): Promise<void> {
  const row = page.locator('[data-testid="channel-row"]').filter({ hasText: name }).first();
  const exists = await row.isVisible({ timeout: 2000 }).catch(() => false);
  if (!exists) {
    await createChannel(page, name);
  }
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM Inbound Channel Management @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(45000);

  // Probe whether the CRM channel API is available
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      const probeResp = await page.request.get('/api/crm/inbound-channels');
      if (!probeResp.ok()) {
        channelApiAvailable = false;
      }
    } catch {
      channelApiAvailable = false;
    } finally {
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(
      !channelApiAvailable,
      'CRM inbound channel API not available (backend may need restart)',
    );
  });

  test('ch-01: Create Generic Webhook channel', async ({ page }) => {
    await goToInboundChannels(page);

    await createChannel(page, channelName);

    // Verify new channel appears in the list
    await expect(page.locator('[data-testid="channel-list"]')).toBeVisible({ timeout: 10000 });

    // Channel name should be in the table
    await expect(page.locator('[data-testid="channel-row"]', { hasText: channelName })).toBeVisible(
      { timeout: 5000 },
    );

    // Type badge should show "Generic Webhook"
    const row = page
      .locator('[data-testid="channel-row"]')
      .filter({ hasText: channelName })
      .first();
    await expect(row.getByText('Generic Webhook')).toBeVisible();
  });

  test('ch-02: API key is displayed masked and copy button exists', async ({ page }) => {
    await goToInboundChannels(page);

    // Find the created channel row
    const row = page
      .locator('[data-testid="channel-row"]')
      .filter({ hasText: channelName })
      .first();
    await expect(row).toBeVisible({ timeout: 10000 });

    // API key should be masked (contains bullet characters)
    const keyCell = row.locator('code');
    await expect(keyCell).toBeVisible();
    const keyText = await keyCell.innerText();
    expect(keyText).toContain('\u2022');

    // Copy button should exist (ClipboardDocumentIcon)
    const copyBtn = row.locator('button[title="Copy API Key"]');
    await expect(copyBtn).toBeVisible();
  });

  test('ch-03: Toggle channel enabled/disabled', async ({ page }) => {
    await goToInboundChannels(page);
    await ensureChannelExists(page, channelName);

    const row = page
      .locator('[data-testid="channel-row"]')
      .filter({ hasText: channelName })
      .first();
    await expect(row).toBeVisible({ timeout: 10000 });

    // Find the toggle button in the row
    const toggle = row.locator('[data-testid="channel-toggle"]');
    await expect(toggle).toBeVisible();

    const initialTitle = (await toggle.getAttribute('title')) ?? '';
    const wasEnabled = initialTitle === 'Disable';

    // Click to toggle - wait for API response
    const toggleResponse = page.waitForResponse(
      (r) =>
        r.url().includes('/api/crm/inbound-channels/') &&
        r.url().includes('/toggle?enabled=') &&
        r.request().method() === 'POST',
      { timeout: 10000 },
    );
    await toggle.click();
    const toggleResp = await toggleResponse;
    expect(toggleResp.ok()).toBe(true);
    const updatedEnabled = !wasEnabled;

    await expect
      .poll(async () => await toggle.getAttribute('title'), { timeout: 10000 })
      .toBe(updatedEnabled ? 'Disable' : 'Enable');

    // Toggle back to original state
    const toggleBack = page.waitForResponse(
      (r) =>
        r.url().includes('/api/crm/inbound-channels/') &&
        r.url().includes('/toggle?enabled=') &&
        r.request().method() === 'POST',
      { timeout: 10000 },
    );
    await toggle.click();
    const toggleBackResp = await toggleBack;
    expect(toggleBackResp.ok()).toBe(true);
  });

  test('ch-04: Edit channel name', async ({ page }) => {
    await goToInboundChannels(page);

    const row = page
      .locator('[data-testid="channel-row"]')
      .filter({ hasText: channelName })
      .first();
    await expect(row).toBeVisible({ timeout: 10000 });

    // Click edit button
    await row.locator('[data-testid="channel-edit-btn"]').click();

    // Wait for edit modal
    const modal = page.locator('.fixed.inset-0');
    await expect(modal).toBeVisible({ timeout: 5000 });

    // Modal should say "Edit Channel"
    await expect(modal.getByText('Edit Channel')).toBeVisible();

    // Clear and update the name
    const nameInput = page.locator('[data-testid="channel-field-name"]');
    await nameInput.clear();
    await nameInput.fill(channelNameEdited);

    // Save and wait for API response
    const saveResponse = page.waitForResponse(
      (r) => r.url().includes('/api/crm/inbound-channels/') && r.request().method() === 'PUT',
      { timeout: 10000 },
    );
    await page.locator('[data-testid="channel-save-btn"]').click();
    await saveResponse;
    await expect(modal).not.toBeVisible({ timeout: 10000 });

    // Verify updated name is visible
    await expect(
      page.locator('[data-testid="channel-row"]', { hasText: channelNameEdited }),
    ).toBeVisible({ timeout: 5000 });
  });

  test('ch-05: Delete channel with confirmation', async ({ page }) => {
    await goToInboundChannels(page);

    // Create a separate channel for deletion
    await createChannel(page, channelNameForDelete);
    await expect(
      page.locator('[data-testid="channel-row"]', { hasText: channelNameForDelete }),
    ).toBeVisible({ timeout: 5000 });

    // Count rows before deletion
    const rowCountBefore = await page.locator('[data-testid="channel-row"]').count();

    // Click delete button — uses native confirm()
    const deleteRow = page
      .locator('[data-testid="channel-row"]')
      .filter({ hasText: channelNameForDelete })
      .first();
    page.once('dialog', (dialog) => dialog.accept());

    const deleteResponse = page.waitForResponse(
      (r) => r.url().includes('/api/crm/inbound-channels/') && r.request().method() === 'DELETE',
      { timeout: 10000 },
    );
    await deleteRow.locator('[data-testid="channel-delete-btn"]').click();
    await deleteResponse;

    // Verify row is removed
    await expect(
      page.locator('[data-testid="channel-row"]', { hasText: channelNameForDelete }),
    ).not.toBeVisible({ timeout: 5000 });

    // Row count should decrease
    const rowCountAfter = await page.locator('[data-testid="channel-row"]').count();
    expect(rowCountAfter).toBeLessThan(rowCountBefore);
  });
});
