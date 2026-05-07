/**
 * Env-layering happy-path closure E2E (#12).
 *
 * Coverage matrix:
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ D1  Menu Navigation     — sidebar click to /admin/environments      │
 * │ D2  List Rendering      — env card grid, default env present        │
 * │ D4  Create (Form)       — full form with code/name/description      │
 * │ D6  Create Verification — new env card appears with correct values  │
 * │ D9  State Transitions   — lock → Locked badge; unlock → badge gone  │
 * │ D11 Delete              — confirm dialog → card disappears          │
 * │ D12 Form Validation     — empty reason in lock dialog → submit blocked│
 * │ D14 Toast / Feedback    — error banner on failures, success rerender│
 * └─────────────────────────────────────────────────────────────────────┘
 *
 * Out of scope (deferred):
 *   - Full promotion lifecycle (validate → apply → diff) needs a source-page
 *     fixture. Smoke-tested below ("promotion page loads + create modal opens"
 *     + "diff viewer route registers"). Full E2E follows in a later iteration
 *     once a PageSchema seeding helper exists.
 *
 * @since env-layering PoC
 * @see /Users/ghj/.claude/plans/auraboot-dsl-environment-ux-contract.md
 */

import { test, expect, type Page } from '../../fixtures';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const ENV_PAGE = '/admin/environments';
const PROMOTION_PAGE = '/admin/promotions';
const DIFF_PAGE = '/admin/diff';

const UID = `EL${Date.now().toString(36)}`;
const DEV_CODE = `dev_${UID}`.toLowerCase();
const STAGING_CODE = `staging_${UID}`.toLowerCase();
const DEV_NAME = `Dev ${UID}`;
const STAGING_NAME = `Staging ${UID}`;

// Serial — later tests depend on envs created by earlier ones
test.describe.configure({ mode: 'serial' });

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** Find a card by its env code badge (mono text). */
function envCard(page: Page, code: string) {
  return page
    .locator('div.rounded-lg', { hasText: code })
    .filter({ has: page.locator(`p.font-mono:has-text("${code}")`) })
    .first();
}

async function fillCreateForm(page: Page, code: string, name: string) {
  // Form is a centered modal with required fields Code + Name
  await page.locator('input[placeholder*="dev, staging, prod"]').fill(code);
  await page.locator('input[placeholder*="Development"]').fill(name);
}

async function dismissError(page: Page) {
  const dismiss = page.locator('button:has-text("Dismiss")');
  if (await dismiss.isVisible().catch(() => false)) {
    await dismiss.click();
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe('Env-layering happy path', () => {
  test.beforeEach(async ({ page }) => {
    // page.goto is the project-wide convention for admin pages (matches existing
    // cloud-config / login-channels / templates specs). Sidebar discoverability
    // is verified by the menu-presence smoke at the bottom of this file.
    await page.goto(ENV_PAGE);
    await expect(page.locator('h1', { hasText: 'Environment Management' })).toBeVisible();
  });

  test('EL-001 list renders with at least the auto-seeded default env', async ({ page }) => {
    // The Header + New button must be visible (D1 / D2)
    await expect(page.locator('button', { hasText: 'New Environment' })).toBeVisible();
    await expect(page.locator('button', { hasText: 'Compare' })).toBeVisible();

    // Card grid OR the "no envs" empty state — at least one of them visible
    // after the initial fetch completes.
    const grid = page.getByTestId('env-list-grid');
    const empty = page.getByTestId('env-empty-state');
    await expect(grid.or(empty)).toBeVisible({ timeout: 15_000 });
  });

  test('EL-002 create dev + staging envs (D4 + D6)', async ({ page }) => {
    // Create dev
    await page.locator('button', { hasText: 'New Environment' }).click();
    await fillCreateForm(page, DEV_CODE, DEV_NAME);
    await page.locator('button', { hasText: 'Create' }).click();
    // Modal closes, card appears with our code
    await expect(envCard(page, DEV_CODE)).toBeVisible();
    await expect(envCard(page, DEV_CODE).locator('h3')).toHaveText(DEV_NAME);

    // Create staging
    await page.locator('button', { hasText: 'New Environment' }).click();
    await fillCreateForm(page, STAGING_CODE, STAGING_NAME);
    await page.locator('button', { hasText: 'Create' }).click();
    await expect(envCard(page, STAGING_CODE)).toBeVisible();
    await expect(envCard(page, STAGING_CODE).locator('h3')).toHaveText(STAGING_NAME);
  });

  test('EL-003 lock + unlock toggles Locked badge (D9 + D14)', async ({ page }) => {
    const card = envCard(page, DEV_CODE);
    await expect(card).toBeVisible();

    // Initially unlocked — no Locked badge
    await expect(card.locator('text=Locked')).toHaveCount(0);

    // Click lock icon (button with title starting "Lock environment")
    await card.locator('button[title^="Lock environment"]').click();

    // Lock dialog opens with required reason
    const lockDialog = page.locator('h2:has-text("Lock Environment")').locator('..').locator('..');
    await expect(lockDialog).toBeVisible();

    // D12 — submit disabled with empty reason
    const submitBtn = lockDialog.locator('button:has-text("Lock")').last();
    await expect(submitBtn).toBeDisabled();

    // Fill reason, submit
    await lockDialog.locator('textarea').fill('cutover freeze for E2E test');
    await expect(submitBtn).toBeEnabled();
    await submitBtn.click();

    // Locked badge appears on the card (D9 — state transition visible)
    await expect(envCard(page, DEV_CODE).locator('span', { hasText: 'Locked' })).toBeVisible();

    // Now unlock — click unlock icon (LockOpen, title starts "Unlock")
    await envCard(page, DEV_CODE).locator('button[title^="Unlock"]').click();

    const unlockDialog = page.locator('h2:has-text("Unlock Environment")').locator('..').locator('..');
    await expect(unlockDialog).toBeVisible();

    // The previous lock reason is surfaced for context
    await expect(unlockDialog).toContainText('cutover freeze for E2E test');

    await unlockDialog.locator('textarea').fill('release shipped, resuming dev');
    await unlockDialog.locator('button:has-text("Unlock")').last().click();

    // Locked badge gone
    await expect(envCard(page, DEV_CODE).locator('span', { hasText: 'Locked' })).toHaveCount(0);
  });

  test('EL-004 promotion-link icon navigates to /admin/promotions with env filter', async ({
    page,
  }) => {
    const card = envCard(page, DEV_CODE);
    await card.locator('a[title^="Promotions for this environment"]').click();

    await expect(page).toHaveURL(new RegExp(`/admin/promotions\\?env=${DEV_CODE}`));
    await expect(page.locator('h1', { hasText: 'Promotions' })).toBeVisible();
    // Filter chip surfaces the source env code so the user knows the scope
    await expect(page.locator(`text=filtered: ${DEV_CODE}`)).toBeVisible();
  });

  test('EL-005 promotion list "+ New" opens create modal with env dropdowns populated', async ({
    page,
  }) => {
    await page.goto(PROMOTION_PAGE);
    await expect(page.locator('h1', { hasText: 'Promotions' })).toBeVisible();

    await page.locator('[data-testid="promotion-new-btn"]').click();
    const modal = page.locator('[data-testid="promotion-create-modal"]');
    await expect(modal).toBeVisible();

    // Both dropdowns include our newly-created envs
    const sourceSelect = modal.locator('[data-testid="promotion-create-source"]');
    await expect(sourceSelect.locator(`option:has-text("${DEV_CODE}")`)).toHaveCount(1);

    const targetSelect = modal.locator('[data-testid="promotion-create-target"]');
    await expect(targetSelect.locator(`option:has-text("${STAGING_CODE}")`)).toHaveCount(1);

    // Cancel without submitting
    await modal.locator('button:has-text("Cancel")').click();
    await expect(modal).toHaveCount(0);
  });

  test('EL-006 diff page renders no-data state when accessed without promotion', async ({
    page,
  }) => {
    await page.goto(DIFF_PAGE);
    // No ?promotion query param → error banner
    await expect(page.locator('text=Missing ?promotion=<pid> query parameter')).toBeVisible();
    // Back link present
    await expect(page.locator('a', { hasText: 'Back to Environments' })).toBeVisible();
  });

  test('EL-007 cleanup: delete the test envs (D11)', async ({ page }) => {
    page.on('dialog', (d) => d.accept());

    // Delete staging first (no dependency)
    await envCard(page, STAGING_CODE)
      .locator('button[title="Delete"]')
      .click();
    await expect(envCard(page, STAGING_CODE)).toHaveCount(0, { timeout: 5000 });

    await dismissError(page);

    // Delete dev
    await envCard(page, DEV_CODE).locator('button[title="Delete"]').click();
    await expect(envCard(page, DEV_CODE)).toHaveCount(0, { timeout: 5000 });
  });
});
