/**
 * Can a digital employee actually hold a conversation?
 *
 * This is the question the whole feature exists to answer, and it is the one
 * that went unasked. On 2026-07-20 a colleague could be created, listed,
 * enrolled and shown in the staff directory — four green cells on the coverage
 * matrix — and could not answer a single message, because the model column
 * defaulted to a vendor the tenant had never configured. Every signal short of
 * sending it a message said the feature was finished.
 *
 * So this drives the real interface against a real stack and a real model. Not
 * a stub: a stub would answer, and answering is exactly what a mute colleague
 * fails to do.
 *
 * On the assertion, specifically. Earlier attempts at this matched page text
 * and passed three times for the wrong reason — first on the question the test
 * had just typed, then on the agent's own description rendered in the header.
 * Text on the page is not evidence that anyone replied. The only thing that
 * distinguishes a reply is the role marker on the bubble, so that is what is
 * asserted, and nothing else counts.
 */

import { test, expect } from '@playwright/test';

const UNIQUE = `talk${Date.now().toString(36)}`;
const COLLEAGUE_NAME = `E2E Talker ${UNIQUE}`;
const SHOTS = 'test-results/digital-employee';

test.describe('Digital employee — conversation', () => {
  // A live model is slow and its latency is not ours to control.
  test.setTimeout(240_000);

  test('a colleague created through the wizard answers a real question', async ({ page }) => {
    // --- create one, through the interface a person would use --------------
    const hydrated = page.waitForResponse(
      (r) => r.url().includes('/agent/providers/configured'),
      { timeout: 30_000 },
    );
    await page.goto('/ai/colleagues/new', { waitUntil: 'domcontentloaded' });
    await hydrated;
    await page.locator('[data-testid="wizard-template-skip"]').click();

    await page.locator('[data-testid="wizard-input-name"]').fill(COLLEAGUE_NAME);
    await page.locator('[data-testid="wizard-btn-next"]').click();
    await expect(page.locator('[data-testid="wizard-step-personality"]')).toBeVisible();
    await page.locator('[data-testid="wizard-btn-next"]').click();
    await expect(page.locator('[data-testid="wizard-step-review"]')).toBeVisible();

    // A colleague bound to no provider is the mute one. Fail here rather than
    // at the silent-chat assertion below, where the cause would be unclear.
    const providerSelect = page.locator('[data-testid="review-provider-select"]');
    await expect(
      providerSelect,
      'no configured provider — any colleague created now would be mute by construction',
    ).toBeVisible();

    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/agent-definition/create')),
      page.locator('[data-testid="wizard-btn-create"]').click(),
    ]);
    await expect(page).toHaveURL(/\/ai\/colleagues\/[^/]+$/, { timeout: 20_000 });

    // --- now talk to it, reached the way a person reaches it ---------------
    // Clicking through rather than constructing the chat URL: the id in the
    // address bar is not necessarily the id that route wants, and a test that
    // builds its own URL tests the test's idea of routing.
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 20_000 },
    );
    const card = page.locator('[data-testid^="agent-card-"]', { hasText: COLLEAGUE_NAME });
    await expect(card, 'the colleague just created must be listed').toBeVisible({ timeout: 20_000 });
    await card.locator('[data-testid^="agent-chat-"]').first().click();
    const chat = page.locator('[data-testid="agent-chat-page"]');
    await expect(chat).toBeVisible({ timeout: 20_000 });

    // Everything below is scoped to the chat page. The global AuraBot side
    // panel mounts a second copy of the same component, so an unscoped
    // chat-msg-agent could match a bubble from a different conversation
    // entirely — a reply, just not this colleague's.
    const input = chat.locator('[data-testid="aurabot-input"]');
    await expect(input).toBeVisible({ timeout: 20_000 });
    await input.fill('用一句话说明你能帮我做什么?');
    await chat.locator('[data-testid="aurabot-send"]').click();

    // The user bubble appearing proves the send happened, and nothing more.
    await expect(chat.locator('[data-testid="chat-msg-user"]').first()).toBeVisible({
      timeout: 20_000,
    });
    await page.screenshot({ path: `${SHOTS}/10-question-sent.png`, fullPage: true });

    // This is the assertion the feature exists for. A live model can take a
    // while; what it must not do is never answer.
    const reply = chat.locator('[data-testid="chat-msg-agent"]').first();
    await expect(
      reply,
      'a digital employee that cannot answer a question is not a digital employee',
    ).toBeVisible({ timeout: 180_000 });

    // Wait for the stream to settle, then judge what is actually in the bubble.
    await expect
      .poll(async () => (await reply.innerText()).trim().length, { timeout: 120_000 })
      .toBeGreaterThan(10);

    const answer = (await reply.innerText()).trim();
    await page.screenshot({ path: `${SHOTS}/11-agent-replied.png`, fullPage: true });

    // The reply must come from a model, not from the stub provider. Without
    // this the test passes in stub mode — which is the default for this stack
    // — and reports "the colleague can talk" on the strength of a fifteen
    // character canned string. That is precisely the shape of pass this file
    // was written to rule out, and it is what the screenshot caught the first
    // time it ran.
    expect(answer, 'stub mode proves the plumbing, not that the colleague can answer')
        .not.toContain('[stub response]');

    // An error rendered inside the assistant bubble is still an assistant
    // bubble. Visibility alone would call that a pass.
    expect(answer, 'the reply must not be a failure notice dressed as an answer').not.toMatch(
      /Failed|失败|错误|Error|未配置|not configured|Tool execution failed/i,
    );
    // Raw identifiers leaking into a user-facing reply is a §2.2 blocker.
    expect(answer, 'the reply must not leak raw command codes').not.toMatch(/\bcmd[_:][a-z0-9_:]+/i);
  });
});
