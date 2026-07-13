/**
 * The embedding provider has to be reachable from the dialog, not just from the API.
 *
 * Every other golden in this directory creates its knowledge base over the API and names the
 * provider directly — which means the one thing a real user must do, *pick the provider from the
 * dropdown*, was never once exercised. A provider that is seeded, enabled, and unselectable is a
 * provider that does not exist.
 *
 * The model matters as much as the provider: the vector column is 1536 wide, and each provider's
 * default model is the one that produces that width. Leaving the previous provider's model behind
 * in the form yields a "model not found" at the first embed, long after the dialog is gone — so the
 * dialog is asserted to carry the model across too.
 *
 * Needs no key: nothing is embedded here. This runs on every stack.
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

const KB_NAME = `S2 Provider ${uniqueId('KB')}`;

test.describe('S2 — the embedding provider is selectable from the UI', () => {
  test('picking DashScope carries its default model, and the KB is created on it', async ({
    page,
  }) => {
    await page.goto('/aurabot/knowledge');
    await page.waitForLoadState('domcontentloaded');

    // The page is server-rendered: the button is in the HTML before React has hydrated and attached
    // its handler, so a click that lands early is simply swallowed. Retry the open until the dialog
    // actually appears — a sleep would only be guessing at how long hydration takes on a cold stack.
    const nameField = page.getByPlaceholder('e.g. Product Documentation');
    await expect(async () => {
      await page.getByRole('button', { name: /New Knowledge Base/i }).first().click();
      await expect(nameField).toBeVisible({ timeout: 2000 });
    }).toPass({ timeout: 30000 });

    await nameField.fill(KB_NAME);

    const provider = page.getByTestId('kb-provider-select');
    await expect(provider).toBeVisible();

    // The option must actually be there — the seeded provider is unusable otherwise.
    await expect(provider.locator('option[value="qianwen"]')).toHaveCount(1);
    await provider.selectOption('qianwen');

    // Switching providers must bring its model with it. text-embedding-v4 is the one that yields
    // the 1536-wide vector ab_kb_chunk.embedding requires; text-embedding-3-small left behind from
    // OpenAI would be rejected by DashScope at the first embed.
    const model = page.locator('input[value="text-embedding-v4"]');
    await expect(
      model,
      'the provider default model did not follow the provider selection',
    ).toBeVisible();

    await page.getByRole('button', { name: /^Create$/ }).click();

    // And it is really persisted — the card reads provider/model back from the server, so this is
    // no longer just form state. (Selecting the provider used to leave it on 'openai' while the
    // model changed underneath: `update` spreads a form prop that has not re-rendered, so two calls
    // in one handler made the second overwrite the first.)
    const card = page.locator('.rounded-xl', { hasText: KB_NAME });
    await expect(card).toBeVisible({ timeout: 15000 });
    await expect(card).toContainText('qianwen');
    await expect(card).toContainText('text-embedding-v4');
    await page.screenshot({ path: 'test-results/s2-provider-01-selected.png', fullPage: true });

    // The detail page reads it back too — same values, no drift between the two views.
    await card.getByRole('link', { name: /Open/i }).click();
    await expect(page.getByText('qianwen/text-embedding-v4')).toBeVisible({ timeout: 15000 });
  });
});
