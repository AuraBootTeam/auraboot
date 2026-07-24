/**
 * Golden for the AI colleague creation wizard as a DSL page (Gap 1, slice 4).
 *
 * The hand-written pages/ai/colleagues.new.tsx is replaced by a DSL page (ai_colleague_new)
 * whose AgentCreateWizard custom block is the template-picker + 3-step guided creation flow.
 *
 * Proves the DSL page resolves the custom block and mounts the wizard: the template selector
 * and its template grid render (the entry step of the flow).
 */
import { test, expect } from '@playwright/test';

test.describe('AI colleague creation wizard — DSL page', () => {
  test('the custom block mounts the wizard with its template selector', async ({ page }) => {
    await page.goto('/p/c/ai_colleague_new', { waitUntil: 'domcontentloaded' });

    await expect(
      page.locator('[data-testid="wizard-template-selector"]'),
      'the DSL page must resolve and mount the AgentCreateWizard block',
    ).toBeVisible({ timeout: 20_000 });

    await expect(
      page.locator('[data-testid="wizard-template-grid"]'),
      'the template picker (step 0 of the flow) renders',
    ).toBeVisible();
  });
});
