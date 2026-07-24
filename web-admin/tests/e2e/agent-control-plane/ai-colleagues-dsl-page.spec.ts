/**
 * Golden for the AI Colleagues list as a DSL page (Gap 1, slice 2).
 *
 * The hand-written pages/ai/colleagues.tsx is replaced by a DSL page (ai_colleagues,
 * kind:detail) whose single `custom` block renders the AgentColleaguesGrid component. The
 * agent-specific presentation the generic card-grid cannot express — AuraBot pinned first as
 * an "official" card, a create button, per-card actions — is preserved by the ported block.
 *
 * Proves the DSL page resolves and renders the custom block end to end: the grid mounts,
 * AuraBot appears as the pinned official card, and the create entry point is present.
 */
import { test, expect } from '@playwright/test';

test.describe('AI Colleagues — DSL custom-block page', () => {
  test('the custom block renders the agent grid with AuraBot pinned and a create button', async ({
    page,
  }) => {
    await page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' });

    const grid = page.locator('[data-testid="agent-colleagues-grid"]');
    await expect(grid, 'the DSL page must resolve and render the AgentColleaguesGrid custom block').toBeVisible(
      { timeout: 20_000 },
    );

    // AuraBot is the built-in agent and must render as the pinned, official card.
    await expect(
      page.locator('[data-testid="aurabot-card"]'),
      'AuraBot must render as the pinned official card',
    ).toBeVisible({ timeout: 20_000 });

    // The create entry point (a real action point of the page) is present.
    await expect(page.locator('[data-testid="create-agent-btn"]')).toBeVisible();
  });
});
