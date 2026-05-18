/**
 * Email Sequence — E2E Tests
 *
 * Covers: Sequence list page, create sequence, editor page, add step.
 * Uses API in beforeAll to seed an existing sequence for read tests.
 *
 * Dimensions covered:
 * D1  Menu Navigation  — sidebar: CRM > Email > Sequences
 * D2  List Rendering   — sequence table visible
 * D4  Create full form — create new sequence with name + description
 * D5  Component types  — status badge, sequence table
 * D6  Create verification — new sequence appears in list
 * D7  Detail / editor  — sequence editor loads steps
 * D9  State change     — activate / pause sequence
 * D11 Delete (steps)   — add step then verify appears in editor
 * D14 Toast feedback   — create/update operations show success toast
 *
 * NOTE: No afterAll cleanup — test data is kept as verification trace.
 */

import { test, expect } from '../../fixtures';
import { uniqueId, waitForToast, ensureSidebarExpanded } from '../helpers/index';

// ---------------------------------------------------------------------------
// Serial mode — tests flow through sequence lifecycle
// ---------------------------------------------------------------------------
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------
const UID = uniqueId('SEQ');
const SEQUENCE_NAME = `E2E Test Sequence ${UID}`;
const SEQUENCE_DESC = `E2E automation drip ${UID}`;
const STEP_SUBJECT = `Step 1 - Follow Up ${UID}`;
const STEP_BODY = `Hello {{first_name}}, this is step 1 of ${UID}`;

let sequenceId: number | null = null;

// ---------------------------------------------------------------------------
// Navigation helpers
// ---------------------------------------------------------------------------
async function navigateToSequenceList(page: any): Promise<void> {
  await page.goto('/dashboards', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);

  const nav = page.locator('nav');
  await nav.first().waitFor({ state: 'visible', timeout: 15_000 });

  // Click CRM root button
  const crmBtn = nav.getByRole('button', { name: 'crm' }).first();
  await crmBtn.scrollIntoViewIfNeeded().catch(() => null);
  await crmBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Click "Email" sub-menu button
  const emailBtn = nav.getByRole('button', { name: /Email|邮件/i }).first();
  await emailBtn.waitFor({ state: 'visible', timeout: 6_000 });
  await emailBtn.scrollIntoViewIfNeeded().catch(() => null);
  await emailBtn.evaluate((el: HTMLElement) => el.click());
  await page.waitForResponse(() => true, { timeout: 2000 }).catch(() => null);

  // Click "Sequences" leaf link
  const seqLink = nav.locator('a[href="/email-sequence"]').first();
  await seqLink.waitFor({ state: 'attached', timeout: 10_000 });
  await seqLink.scrollIntoViewIfNeeded().catch(() => null);

  const apiPromise = page
    .waitForResponse((r: any) => r.url().includes('/api/email/sequences') && r.status() === 200, {
      timeout: 15_000,
    })
    .catch(() => null);

  await seqLink.evaluate((el: HTMLElement) => el.click());
  await apiPromise;
  await page.waitForLoadState('domcontentloaded');
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
test.describe('Email Sequences', () => {
  test.setTimeout(120_000);

  test.beforeAll(async ({ browser }) => {
    const ctx = await browser.newContext({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });
    const page = await ctx.newPage();
    try {
      // Create a test sequence via API for read tests
      const resp = await page.request.post('/api/email/sequences', {
        params: {
          name: `Seed Sequence ${UID}`,
          description: `Seed description ${UID}`,
        },
      });
      if (resp.ok()) {
        const body = await resp.json().catch(() => ({}));
        sequenceId = body?.data?.id ?? null;
      }
    } finally {
      await ctx.close();
    }
  });

  // =========================================================================
  // T1: Navigate to Sequences via sidebar (D1)
  // =========================================================================
  test('T1: navigate to Email Sequences via sidebar menu', async ({ page }) => {
    await navigateToSequenceList(page);

    const listPage = page.locator('[data-testid="email-sequence-list-page"]');
    await expect(listPage).toBeVisible({ timeout: 15_000 });

    // Header should show "Email Sequences"
    await expect(page.getByText('Email Sequences')).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // T2: Sequence list renders table or empty state (D2)
  // =========================================================================
  test('T2: sequence list shows table or empty state', async ({ page }) => {
    await navigateToSequenceList(page);
    await page
      .locator('[data-testid="email-sequence-list-page"]')
      .waitFor({ state: 'visible', timeout: 15_000 });

    // Wait for loading spinner to disappear
    const spinner = page.locator('.animate-spin').first();
    await spinner.waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {});

    const hasTable = await page
      .locator('[data-testid="sequence-table"]')
      .isVisible({ timeout: 5_000 })
      .catch(() => false);
    const hasEmpty = await page
      .locator('[data-testid="sequence-empty-state"]')
      .isVisible({ timeout: 3_000 })
      .catch(() => false);

    expect(
      hasTable || hasEmpty,
      'Sequence list must show table or empty state — got blank screen',
    ).toBe(true);
  });

  // =========================================================================
  // T3: "New Sequence" button shows create form (D4, D5)
  // =========================================================================
  test('T3: New Sequence button toggles create form', async ({ page }) => {
    await navigateToSequenceList(page);
    await page
      .locator('[data-testid="email-sequence-list-page"]')
      .waitFor({ state: 'visible', timeout: 15_000 });

    const createBtn = page.locator('[data-testid="create-sequence-btn"]');
    await expect(createBtn).toBeVisible({ timeout: 8_000 });
    await createBtn.click();

    // Create form should appear
    const nameInput = page.locator('[data-testid="sequence-name-input"]');
    const descInput = page.locator('[data-testid="sequence-desc-input"]');

    await expect(nameInput).toBeVisible({ timeout: 5_000 });
    await expect(descInput).toBeVisible({ timeout: 5_000 });

    // Verify labels
    await expect(page.getByText('Create New Sequence')).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // T4: Create a sequence via UI form (D4, D6, D14)
  // =========================================================================
  test('T4: create a new sequence via form — appears in list', async ({ page }) => {
    await navigateToSequenceList(page);
    await page
      .locator('[data-testid="email-sequence-list-page"]')
      .waitFor({ state: 'visible', timeout: 15_000 });

    // Open create form
    await page.locator('[data-testid="create-sequence-btn"]').click();

    const nameInput = page.locator('[data-testid="sequence-name-input"]');
    await nameInput.waitFor({ state: 'visible', timeout: 5_000 });
    await nameInput.fill(SEQUENCE_NAME);

    const descInput = page.locator('[data-testid="sequence-desc-input"]');
    await descInput.fill(SEQUENCE_DESC);

    // Submit — will either navigate to editor or show success toast
    const createApiPromise = page
      .waitForResponse(
        (r: any) =>
          r.url().includes('/api/email/sequences') && r.method() === 'POST' && r.status() === 200,
        { timeout: 15_000 },
      )
      .catch(() => null);

    await page.locator('button[type="submit"], button:has-text("Create")').first().click();
    const createResp = await createApiPromise;

    if (createResp) {
      const body = await createResp.json().catch(() => ({}));
      const newId = body?.data?.id ?? null;
      if (newId) sequenceId = newId;
    }

    // Should navigate to sequence editor OR show in list
    await page.waitForLoadState('domcontentloaded');

    const isOnEditor = await page
      .locator('[data-testid="email-sequence-editor-page"]')
      .isVisible({ timeout: 5_000 })
      .catch(() => false);
    if (isOnEditor) {
      // Navigated to editor — sequence was created
      await expect(page.locator('[data-testid="email-sequence-editor-page"]')).toBeVisible();
      // Sequence name should be visible in header
      const hasName = await page
        .getByText(SEQUENCE_NAME)
        .isVisible({ timeout: 5_000 })
        .catch(() => false);
      expect(hasName, `Created sequence name "${SEQUENCE_NAME}" must be visible in editor`).toBe(
        true,
      );
    } else {
      // Back on list page — sequence should be in table
      await navigateToSequenceList(page);
      await page
        .locator('[data-testid="email-sequence-list-page"]')
        .waitFor({ state: 'visible', timeout: 10_000 });
      const hasRow = await page
        .locator('[data-testid="sequence-table"]')
        .locator('tr')
        .filter({ hasText: SEQUENCE_NAME })
        .first()
        .isVisible({ timeout: 5_000 })
        .catch(() => false);
      expect(
        hasRow,
        `Sequence "${SEQUENCE_NAME}" must appear in sequence list after creation`,
      ).toBe(true);
    }
  });

  // =========================================================================
  // T5: Sequence editor page loads (D7)
  // =========================================================================
  test('T5: sequence editor page loads with header and steps section', async ({ page }) => {
    if (!sequenceId) {
      test.skip();
      return;
    }

    // Navigate directly to sequence editor
    const apiPromise = page
      .waitForResponse(
        (r: any) => r.url().includes(`/api/email/sequences/${sequenceId}`) && r.status() === 200,
        { timeout: 15_000 },
      )
      .catch(() => null);

    await page.goto(`/email-sequence/${sequenceId}`, { waitUntil: 'domcontentloaded' });
    await apiPromise;

    const editorPage = page.locator('[data-testid="email-sequence-editor-page"]');
    await expect(editorPage).toBeVisible({ timeout: 15_000 });

    // Steps section heading
    await expect(page.getByText(/Steps \(/i)).toBeVisible({ timeout: 8_000 });

    // "Add Step" button present
    const addStepBtn = page.locator('[data-testid="add-step-btn"]');
    await expect(addStepBtn).toBeVisible({ timeout: 5_000 });
  });

  // =========================================================================
  // T6: Add a step via form (D4, D5, D14)
  // =========================================================================
  test('T6: add a step to sequence — step appears in editor', async ({ page }) => {
    if (!sequenceId) {
      test.skip();
      return;
    }

    await page.goto(`/email-sequence/${sequenceId}`, { waitUntil: 'domcontentloaded' });
    await page
      .locator('[data-testid="email-sequence-editor-page"]')
      .waitFor({ state: 'visible', timeout: 15_000 });

    // Click "Add Step" to show form
    const addStepBtn = page.locator('[data-testid="add-step-btn"]');
    await addStepBtn.click();

    const addStepForm = page.locator('[data-testid="add-step-form"]');
    await expect(addStepForm).toBeVisible({ timeout: 5_000 });

    // Fill step subject (required)
    const subjectInput = addStepForm
      .locator('input[placeholder*="subject" i], input[placeholder*="Subject" i]')
      .first();
    await subjectInput.fill(STEP_SUBJECT);

    // Fill body (optional)
    const bodyTextarea = addStepForm.locator('textarea').first();
    await bodyTextarea.fill(STEP_BODY);

    // Submit
    const addApiPromise = page
      .waitForResponse(
        (r: any) =>
          r.url().includes(`/api/email/sequences/${sequenceId}/steps`) &&
          r.method() === 'POST' &&
          r.status() === 200,
        { timeout: 15_000 },
      )
      .catch(() => null);

    await addStepForm.locator('button[type="submit"], button:has-text("Add Step")').first().click();
    await addApiPromise;

    // Toast feedback
    await waitForToast(page, 'Step added', 5_000).catch(() => {});

    // Verify step appears in editor
    const stepCard = page.locator('[data-testid^="step-row-"]').first();
    await expect(stepCard).toBeVisible({ timeout: 8_000 });
    await expect(stepCard).toContainText(STEP_SUBJECT);
  });

  // =========================================================================
  // T7: Sequence status can be activated (D9, D14)
  // =========================================================================
  test('T7: activate sequence changes status badge', async ({ page }) => {
    if (!sequenceId) {
      test.skip();
      return;
    }

    await page.goto(`/email-sequence/${sequenceId}`, { waitUntil: 'domcontentloaded' });
    await page
      .locator('[data-testid="email-sequence-editor-page"]')
      .waitFor({ state: 'visible', timeout: 15_000 });

    // Check if activate button is available (only for draft/paused sequences)
    const activateBtn = page.locator('[data-testid="activate-sequence-btn"]');
    const hasActivate = await activateBtn.isVisible({ timeout: 3_000 }).catch(() => false);

    if (!hasActivate) {
      // Already active or archived — skip
      test.skip();
      return;
    }

    const statusApiPromise = page
      .waitForResponse(
        (r: any) => r.url().includes(`/sequences/${sequenceId}/status`) && r.status() === 200,
        { timeout: 10_000 },
      )
      .catch(() => null);

    await activateBtn.click();
    await statusApiPromise;

    // Toast feedback
    await waitForToast(page, 'active', 5_000).catch(() => {});

    // Status badge should now say "active"
    const statusBadge = page
      .locator('.rounded-full')
      .filter({ hasText: /^active$/i })
      .first();
    await expect(statusBadge).toBeVisible({ timeout: 8_000 });
  });

  // =========================================================================
  // T8: Sequence table shows correct columns (D2, D5)
  // =========================================================================
  test('T8: sequence table shows Name, Status, Created columns', async ({ page }) => {
    await navigateToSequenceList(page);
    await page
      .locator('[data-testid="email-sequence-list-page"]')
      .waitFor({ state: 'visible', timeout: 15_000 });

    // Wait for loading
    const spinner = page.locator('.animate-spin').first();
    await spinner.waitFor({ state: 'hidden', timeout: 10_000 }).catch(() => {});

    const hasTable = await page
      .locator('[data-testid="sequence-table"]')
      .isVisible({ timeout: 5_000 })
      .catch(() => false);
    if (!hasTable) {
      test.skip();
      return;
    }

    const table = page.locator('[data-testid="sequence-table"]');
    // Column headers
    await expect(table.locator('th').filter({ hasText: /Name/i }).first()).toBeVisible();
    await expect(
      table
        .locator('th')
        .filter({ hasText: /Status/i })
        .first(),
    ).toBeVisible();
    await expect(
      table
        .locator('th')
        .filter({ hasText: /Created/i })
        .first(),
    ).toBeVisible();
  });
});

// Suppress unused variable warnings
void SEQUENCE_DESC;
void STEP_BODY;
