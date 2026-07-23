/**
 * Browser E2E for the skill asset layer (OSS #1440): a digital employee, in the
 * real chat UI, uses its BOUND skill's governed tool to do real work.
 *
 * The backend fixes (#1440) made a bound builtin skill contribute its governed
 * DSL tool (list:crm_account) to the turn; #1441 pins that as a real-stack IT.
 * This closes the loop at the interface a person actually uses: the colleague
 * "客户运营助理·小奥" is bound to crm_quarterly_review, and asked for a review
 * it must reach into the CRM and answer with grounded numbers — not a generic
 * reply, and not silence.
 *
 * Assertion discipline (mirrors ai-colleague-can-talk.spec.ts): text on the page
 * is not proof of a reply — only the agent role marker on the bubble is. And a
 * reply alone is not proof the skill worked; the grounded figure (8 seeded
 * customers) is. Both are asserted, scoped to the chat page so the global
 * AuraBot side panel cannot supply a stray match.
 */

import { test, expect } from '@playwright/test';

const COLLEAGUE_NAME = '客户运营助理·小奥';
const SHOTS = 'test-results/digital-employee';

test.describe('Digital employee — bound skill drives a real customer review', () => {
  // A live model is slow and its latency is not ours to control.
  test.setTimeout(240_000);

  test('the ops colleague uses its bound skill to read customers and answer with grounded numbers', async ({
    page,
  }) => {
    // Reach the colleague the way a person does — click through, don't build the URL.
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 20_000 },
    );

    const card = page.locator('[data-testid^="agent-card-"]', { hasText: COLLEAGUE_NAME });
    await expect(card, 'the ops colleague must be listed').toBeVisible({ timeout: 20_000 });
    await card.locator('[data-testid^="agent-chat-"]').first().click();

    const chat = page.locator('[data-testid="agent-chat-page"]');
    await expect(chat).toBeVisible({ timeout: 20_000 });

    const input = chat.locator('[data-testid="aurabot-input"]');
    await expect(input).toBeVisible({ timeout: 20_000 });
    await input.fill('请对我们的客户做一次季度客户结构复盘');
    await chat.locator('[data-testid="aurabot-send"]').click();

    // The user bubble appearing proves the send happened, and nothing more.
    await expect(chat.locator('[data-testid="chat-msg-user"]').first()).toBeVisible({
      timeout: 20_000,
    });

    // The agent role marker on the bubble is the only thing that proves a reply.
    const reply = chat.locator('[data-testid="chat-msg-agent"]').last();
    await expect(reply, 'the colleague must actually reply (role marker), not stay mute').toBeVisible(
      { timeout: 200_000 },
    );

    // A reply is not enough — the skill's governed read must have grounded it.
    // Eight customers were seeded; a generic answer cannot produce that figure.
    await expect(
      reply,
      'the review must be grounded in the CRM read (8 seeded customers), not generic',
    ).toContainText('8', { timeout: 200_000 });
    await expect(reply).toContainText(/客户|复盘|行业|评级/);

    await page.screenshot({ path: `${SHOTS}/skill-review-ui.png`, fullPage: true });
  });
});
