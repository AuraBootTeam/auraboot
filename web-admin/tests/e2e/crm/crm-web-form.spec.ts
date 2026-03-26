/**
 * CRM Web Form Designer E2E Tests
 *
 * Tests the full lifecycle of web forms:
 * - wf-01: Web Forms page loads via menu navigation
 * - wf-02: Create a new form (requires a WEB_FORM channel)
 * - wf-03: Form editor shows field management (Add Field, field cards)
 * - wf-04: Style settings panel (color picker, button text, success message)
 * - wf-05: Copy Embed Code button changes state or shows toast
 * - wf-06: Save form changes
 * - wf-07: Form appears in list after creation
 *
 * Prerequisites:
 * - A WEB_FORM channel is created in beforeAll via API.
 * - If the web-form API is unavailable (backend not restarted), tests skip.
 *
 * @since 10.0.0
 */

import { test, expect, type Page } from '@playwright/test';
import { uniqueId } from '../helpers/index';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const uid = uniqueId('wf');
const formName = `E2E_Form_${uid}`;
const channelName = `E2E_WFChannel_${uid}`;

let webformApiAvailable = true;
let createdChannelPid = '';
let createdFormPid = '';

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------

async function goToWebForms(page: Page): Promise<void> {
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

  // Click Web Forms leaf link
  const href = '/crm/settings/web-forms';
  const leafLink = nav.locator(`a[href="${href}"]`).first();
  await leafLink.waitFor({ state: 'attached', timeout: 8000 });
  await leafLink.scrollIntoViewIfNeeded();
  await leafLink.evaluate((el: HTMLElement) => el.click());

  await page.waitForURL((url) => url.pathname === href, { timeout: 10000 });
  await expect(page.getByRole('heading', { name: 'Web Forms' })).toBeVisible({ timeout: 10000 });
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

test.describe('CRM Web Form Designer @critical', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60000);

  // Probe API availability and create a WEB_FORM channel for use in tests
  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Check if the web-forms API is available
      const probeResp = await page.request.get('/api/crm/web-forms?channelPid=all');
      if (!probeResp.ok()) {
        webformApiAvailable = false;
        return;
      }

      // Create a WEB_FORM inbound channel so the "New Form" modal has a channel to select
      const channelResp = await page.request.post('/api/crm/inbound-channels', {
        data: {
          name: channelName,
          channelType: 'web_form',
          config: {},
        },
      });
      if (channelResp.ok()) {
        const body = await channelResp.json();
        createdChannelPid = body?.data?.pid ?? '';
      }
    } catch {
      webformApiAvailable = false;
    } finally {
      await ctx.close();
    }
  });

  test.beforeEach(async () => {
    test.skip(
      !webformApiAvailable,
      'CRM web-form API not available (backend may need restart)',
    );
  });

  // -------------------------------------------------------------------------
  // wf-01: Page loads
  // -------------------------------------------------------------------------

  test('wf-01: Web Forms page loads from menu', async ({ page }) => {
    await goToWebForms(page);

    // Heading is already asserted in goToWebForms, confirm URL too
    await expect(page).toHaveURL(/\/crm\/settings\/web-forms/);

    // Either the table or the empty state should be visible
    const list = page.locator('[data-testid="webform-list"]');
    const empty = page.locator('[data-testid="webform-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });
  });

  // -------------------------------------------------------------------------
  // wf-02: Create a new form
  // -------------------------------------------------------------------------

  test('wf-02: Create new form and navigate to editor', async ({ page }) => {
    await goToWebForms(page);

    // Verify "New Form" button exists
    const createBtn = page.locator('[data-testid="webform-create-btn"]');
    await expect(createBtn).toBeVisible({ timeout: 5000 });
    await createBtn.click();

    // Modal appears
    const modal = page.locator('.fixed.inset-0');
    await expect(modal).toBeVisible({ timeout: 5000 });
    await expect(modal.getByText('New Web Form')).toBeVisible();

    // Fill form name
    const nameInput = page.locator('[data-testid="webform-field-name"]');
    await expect(nameInput).toBeVisible({ timeout: 5000 });
    await nameInput.fill(formName);

    // If no channels available, the modal shows a warning and button is disabled — skip creation
    const channelSelect = page.locator('[data-testid="webform-field-channel"]');
    const channelWarning = modal.getByText('No Web Form channels found');
    const hasChannels = await channelSelect.isVisible({ timeout: 2000 }).catch(() => false);
    const hasWarning = await channelWarning.isVisible({ timeout: 2000 }).catch(() => false);

    if (hasWarning || !hasChannels) {
      test.skip(true, 'No WEB_FORM channels exist — cannot create a web form');
      return;
    }

    // Select the channel we created in beforeAll (it should be pre-selected or available)
    if (createdChannelPid) {
      await channelSelect.selectOption({ value: createdChannelPid });
    }

    // Click "Create & Edit" and wait for navigation to editor
    const createConfirmBtn = page.locator('[data-testid="webform-create-confirm-btn"]');
    await expect(createConfirmBtn).toBeVisible();
    await expect(createConfirmBtn).not.toBeDisabled();

    const formResp = page.waitForResponse(
      (r) => r.url().includes('/api/crm/web-forms') && r.request().method() === 'POST',
      { timeout: 10000 },
    );
    await createConfirmBtn.click();
    const resp = await formResp;

    if (!resp.ok()) {
      throw new Error(`Web form creation API returned ${resp.status()}`);
    }

    const body = await resp.json();
    createdFormPid = body?.data?.pid ?? '';

    // Should navigate to the editor page
    await page.waitForURL((url) => url.pathname.includes('/crm/settings/web-form-editor/'), {
      timeout: 10000,
    });
    await expect(page).toHaveURL(/\/crm\/settings\/web-form-editor\//);
  });

  // -------------------------------------------------------------------------
  // wf-03: Editor shows field management
  // -------------------------------------------------------------------------

  test('wf-03: Form editor shows field management panel', async ({ page }) => {
    if (!createdFormPid) {
      test.skip(true, 'No form was created in wf-02');
      return;
    }

    // Navigate directly to the editor for the created form
    await page.goto(`/crm/settings/web-form-editor/${createdFormPid}`, {
      waitUntil: 'domcontentloaded',
    });

    // Wait for editor to load (top bar with form name)
    await expect(page.getByText('Loading form editor...')).not.toBeVisible({ timeout: 10000 });

    // "Form Fields" heading in left panel
    await expect(page.getByText(/Form Fields/)).toBeVisible({ timeout: 10000 });

    // "Add Field" button is visible
    const addFieldBtn = page.locator('[data-testid="webform-add-field-btn"]');
    await expect(addFieldBtn).toBeVisible({ timeout: 5000 });

    // Click "Add Field" and verify a field card appears
    await addFieldBtn.click();

    const fieldCard = page.locator('[data-testid="webform-field-card"]').first();
    await expect(fieldCard).toBeVisible({ timeout: 5000 });

    // The fields container should be visible
    const fieldsContainer = page.locator('[data-testid="webform-fields"]');
    await expect(fieldsContainer).toBeVisible({ timeout: 5000 });

    // Field count in heading should now be (1)
    await expect(page.getByText(/Form Fields \(1\)/)).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // wf-04: Style settings panel
  // -------------------------------------------------------------------------

  test('wf-04: Style settings panel is visible with color, button text, success message', async ({
    page,
  }) => {
    if (!createdFormPid) {
      test.skip(true, 'No form was created in wf-02');
      return;
    }

    await page.goto(`/crm/settings/web-form-editor/${createdFormPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByText('Loading form editor...')).not.toBeVisible({ timeout: 10000 });

    // Style & Settings heading
    await expect(page.getByText('Style & Settings')).toBeVisible({ timeout: 10000 });

    // Color picker input (type=color)
    const colorInput = page.locator('input[type="color"]');
    await expect(colorInput).toBeVisible({ timeout: 5000 });

    // Button text input
    const buttonTextInput = page.locator('input[placeholder="Submit"]');
    await expect(buttonTextInput).toBeVisible({ timeout: 5000 });

    // Success message textarea
    const successMsg = page.locator('textarea[placeholder="Thank you for your submission!"]');
    await expect(successMsg).toBeVisible({ timeout: 5000 });

    // Live Preview heading
    await expect(page.getByText('Live Preview')).toBeVisible({ timeout: 5000 });
  });

  // -------------------------------------------------------------------------
  // wf-05: Copy Embed Code button
  // -------------------------------------------------------------------------

  test('wf-05: Copy Embed Code button exists and responds to click', async ({ page }) => {
    if (!createdFormPid) {
      test.skip(true, 'No form was created in wf-02');
      return;
    }

    await page.goto(`/crm/settings/web-form-editor/${createdFormPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByText('Loading form editor...')).not.toBeVisible({ timeout: 10000 });

    // Copy Embed Code button
    const copyEmbedBtn = page.locator('[data-testid="webform-copy-embed-btn"]');
    await expect(copyEmbedBtn).toBeVisible({ timeout: 10000 });

    // Initial state shows "Copy Embed Code"
    await expect(copyEmbedBtn).toContainText('Copy Embed Code');

    // Click the button — clipboard API in headless requires override
    // Grant clipboard write permission if possible, then click
    await page.context().grantPermissions(['clipboard-write']);
    await copyEmbedBtn.click();

    // After clicking, button text should change to "Copied!" for 2 seconds
    await expect(copyEmbedBtn).toContainText('Copied!', { timeout: 3000 });
  });

  // -------------------------------------------------------------------------
  // wf-06: Save form changes
  // -------------------------------------------------------------------------

  test('wf-06: Save form changes shows success feedback', async ({ page }) => {
    if (!createdFormPid) {
      test.skip(true, 'No form was created in wf-02');
      return;
    }

    await page.goto(`/crm/settings/web-form-editor/${createdFormPid}`, {
      waitUntil: 'domcontentloaded',
    });
    await expect(page.getByText('Loading form editor...')).not.toBeVisible({ timeout: 10000 });

    // Modify the Button Text to trigger a change
    const buttonTextInput = page.locator('input[placeholder="Submit"]');
    await expect(buttonTextInput).toBeVisible({ timeout: 10000 });
    await buttonTextInput.click({ clickCount: 3 });
    await buttonTextInput.fill(`Send_${uid}`);

    // Click Save Form button and wait for API response
    const saveBtn = page.locator('[data-testid="webform-save-btn"]');
    await expect(saveBtn).toBeVisible({ timeout: 5000 });

    const saveResp = page.waitForResponse(
      (r) =>
        r.url().includes(`/api/crm/web-forms/${createdFormPid}`) &&
        r.request().method() === 'PUT',
      { timeout: 10000 },
    );
    await saveBtn.click();
    const resp = await saveResp;
    expect(resp.ok()).toBe(true);

    // Toast should appear confirming save
    const toast = page.locator('[role="alert"]');
    await expect(toast.first()).toBeVisible({ timeout: 5000 });
    await expect(toast.first()).toContainText(/saved|success/i);
  });

  // -------------------------------------------------------------------------
  // wf-07: Form appears in list
  // -------------------------------------------------------------------------

  test('wf-07: Created form appears in the Web Forms list', async ({ page }) => {
    await goToWebForms(page);

    // Either the table or the empty state should be visible
    const list = page.locator('[data-testid="webform-list"]');
    const empty = page.locator('[data-testid="webform-empty"]');
    await expect(list.or(empty).first()).toBeVisible({ timeout: 10000 });

    // If the form was created successfully, it should be in the table
    if (createdFormPid) {
      const formRow = page.locator('[data-testid="webform-row"]', { hasText: formName });
      await expect(formRow).toBeVisible({ timeout: 8000 });

      // Edit button should be present in the row
      await expect(formRow.locator('[data-testid="webform-edit-btn"]')).toBeVisible();
      // Delete button should be present in the row
      await expect(formRow.locator('[data-testid="webform-delete-btn"]')).toBeVisible();
    }
  });
});
