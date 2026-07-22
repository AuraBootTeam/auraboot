/**
 * Stopping one colleague, from the interface.
 *
 * The backend could already do this — both engines resolve agent definitions
 * with {@code status = 'active'}, so suspending closes chat, dispatch and
 * delegation at once — but nothing in the interface could ask for it. The only
 * lever an operator actually had was the global switch that silences every
 * agent in the deployment.
 *
 * The assertion that matters is not the badge. A button that recolours itself
 * and a badge that appears are both things the page can do entirely on its own.
 * What has to be true is that the colleague stops answering, so that is what is
 * checked, and the badge is only the operator's evidence that it happened.
 */

import { test, expect, type Page } from '@playwright/test';

const UNIQUE = `susp${Date.now().toString(36)}`;
const COLLEAGUE_NAME = `E2E Suspendable ${UNIQUE}`;
const SHOTS = 'test-results/digital-employee';

async function createColleague(page: Page): Promise<void> {
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
  await Promise.all([
    page.waitForResponse((r) => r.url().includes('/agent-definition/create')),
    page.locator('[data-testid="wizard-btn-create"]').click(),
  ]);
  // Excluding /new explicitly: it matches "one path segment under colleagues"
  // just as well as a pid does, so the plain pattern is satisfied the instant
  // the wizard opens and hands back the wizard's own URL as the detail URL.
  await expect(page).toHaveURL(/\/ai\/colleagues\/(?!new$)[^/]+$/, { timeout: 20_000 });
}

/** Opens this colleague's chat the way a person does — from its card. */
async function openChat(page: Page) {
  await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
  await page.waitForResponse(
    (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
    { timeout: 20_000 },
  );
  const card = page.locator('[data-testid^="agent-card-"]', { hasText: COLLEAGUE_NAME });
  await expect(card).toBeVisible({ timeout: 20_000 });
  await card.locator('[data-testid^="agent-chat-"]').first().click();
  const chat = page.locator('[data-testid="agent-chat-page"]');
  await expect(chat).toBeVisible({ timeout: 20_000 });
  return chat;
}

test.describe('Digital employee — suspend and resume', () => {
  test.setTimeout(300_000);

  test('a suspended colleague stops answering, and resuming brings it back', async ({ page }) => {
    await createColleague(page);
    const detailUrl = page.url();

    // --- it answers to begin with ----------------------------------------
    // Without this the suspension assertion proves nothing: a colleague that
    // never answered would satisfy it for the wrong reason.
    let chat = await openChat(page);
    await chat.locator('[data-testid="aurabot-input"]').fill('在吗?');
    await chat.locator('[data-testid="aurabot-send"]').click();
    await expect(
      chat.locator('[data-testid="chat-msg-agent"]').first(),
      'the colleague must answer before suspension, or the test proves nothing',
    ).toBeVisible({ timeout: 180_000 });

    // --- suspend it from the interface ------------------------------------
    await page.goto(detailUrl, { waitUntil: 'domcontentloaded' });
    const suspendBtn = page.locator('[data-testid="agent-suspend-btn"]');
    await expect(
      suspendBtn,
      'an operator must be able to stop one colleague without the global switch',
    ).toBeVisible({ timeout: 20_000 });
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/suspend') && r.request().method() === 'POST'),
      suspendBtn.click(),
    ]);

    // The operator has to be able to see the state, not just trust the click.
    await expect(page.locator('[data-testid="agent-suspended-badge"]')).toBeVisible({
      timeout: 20_000,
    });
    await page.screenshot({ path: `${SHOTS}/20-suspended.png`, fullPage: true });

    // It survives a reload — the page is reporting stored state, not its own
    // optimism about a click it made.
    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="agent-suspended-badge"]')).toBeVisible({
      timeout: 20_000,
    });

    // --- and now it must not answer ---------------------------------------
    chat = await openChat(page);
    await chat.locator('[data-testid="aurabot-input"]').fill('还在吗?');
    await chat.locator('[data-testid="aurabot-send"]').click();
    // Give it as long as a real answer took above. Asserting "not visible"
    // immediately would pass simply because nothing had arrived yet.
    await page.waitForTimeout(20_000);
    await expect(
      chat.locator('[data-testid="chat-msg-agent"]'),
      'a suspended colleague must not keep taking work',
    ).toHaveCount(0);
    await page.screenshot({ path: `${SHOTS}/21-suspended-no-reply.png`, fullPage: true });

    // Refusing is only half of it — the operator has to be told something they
    // can act on. The message used to read "Agent not found or inactive:
    // e2e_suspendable_..." which both leaked an internal identifier (§2.2) and
    // sent the reader looking for a deleted record rather than the Resume
    // button they themselves need.
    const notice = chat.getByText(/suspended|停用|no longer available|不再可用/i).first();
    await expect(notice, 'the page must say why nothing happened').toBeVisible({ timeout: 20_000 });
    const noticeText = await notice.innerText();
    expect(noticeText, 'a user-facing message must not carry the raw agent code')
        .not.toMatch(/e2e_suspendable[a-z0-9_]*/i);

    // --- resume, and it works again ---------------------------------------
    await page.goto(detailUrl, { waitUntil: 'domcontentloaded' });
    const resumeBtn = page.locator('[data-testid="agent-resume-btn"]');
    await expect(resumeBtn).toBeVisible({ timeout: 20_000 });
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/resume') && r.request().method() === 'POST'),
      resumeBtn.click(),
    ]);
    await expect(page.locator('[data-testid="agent-suspended-badge"]')).toBeHidden({
      timeout: 20_000,
    });

    chat = await openChat(page);
    await chat.locator('[data-testid="aurabot-input"]').fill('现在呢?');
    await chat.locator('[data-testid="aurabot-send"]').click();
    await expect(
      chat.locator('[data-testid="chat-msg-agent"]').first(),
      'resuming must actually restore the colleague, not only the badge',
    ).toBeVisible({ timeout: 180_000 });
    await page.screenshot({ path: `${SHOTS}/22-resumed.png`, fullPage: true });
  });
});
