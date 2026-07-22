/**
 * Does unchecking an operation in the interface actually take it away?
 *
 * This is the control that spent the longest being decorative. The "allowed
 * operations" checkboxes saved, redisplayed as saved, and governed nothing:
 * clearing Delete left the agent able to delete. A permission control that does
 * not control anything is worse than not having one, because somebody configures
 * it and then believes the boundary is there.
 *
 * What this test proves, precisely: with Delete cleared through the interface, a
 * real turn against a real model cannot remove the record. It does not prove the
 * model tried to — nothing can make a language model reliably attempt a specific
 * tool call, and a test that depended on it would be flaky in the direction that
 * reports false safety. The complementary direction (delete allowed ⇒ the delete
 * tool is present in the assembled set) is pinned deterministically in
 * AgentToolScopePolicyTest, which is where that claim belongs.
 *
 * The guard against a vacuous pass is that the turn must actually have happened:
 * a record survives trivially if nothing ever ran.
 */

import { test, expect, type Page } from '@playwright/test';

const UNIQUE = `scope${Date.now().toString(36)}`;
const COLLEAGUE_NAME = `E2E Scoped ${UNIQUE}`;
const ACCOUNT_NAME = `E2E Account ${UNIQUE}`;
const SHOTS = 'test-results/digital-employee';

async function createColleague(page: Page) {
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
  // /new matches "one segment under colleagues" as well as a pid does.
  await expect(page).toHaveURL(/\/ai\/colleagues\/(?!new$)[^/]+$/, { timeout: 20_000 });
}

test.describe('Digital employee — allowed operations', () => {
  test.setTimeout(300_000);

  test('clearing Delete in the interface leaves the colleague unable to delete', async ({
    page,
  }) => {
    // --- something for it to try to delete --------------------------------
    const created = await page.request.post('/api/dynamic/crm_account/create', {
      data: { crm_acc_name: ACCOUNT_NAME, crm_acc_code: `e2eacc${UNIQUE}` },
    });
    expect(created.status(), 'seeding the target record must succeed').toBeLessThan(400);

    const stillThere = async () => {
      const res = await page.request.get('/api/dynamic/crm_account/list', {
        params: { pageNum: 1, pageSize: 50, keyword: ACCOUNT_NAME },
      });
      const body = await res.json();
      const rows = body?.data?.records ?? [];
      return rows.some((r: Record<string, unknown>) => r.crm_acc_name === ACCOUNT_NAME);
    };
    expect(await stillThere(), 'the record must exist before we ask for its removal').toBe(true);

    // --- clear Delete, in the interface -----------------------------------
    await createColleague(page);
    const detailUrl = page.url();
    await page.getByRole('tab', { name: /Tools|工具/ }).first().click()
      .catch(() => page.locator('nav[aria-label="Tabs"] button', { hasText: /Tools|工具/ }).first().click());

    const deleteToggle = page.locator('[data-testid="op-toggle-delete"]');
    await expect(deleteToggle).toBeVisible({ timeout: 20_000 });
    const deleteBox = deleteToggle.locator('input[type="checkbox"]');
    await expect(deleteBox, 'delete starts allowed, so clearing it is a real change').toBeChecked();
    await deleteBox.uncheck();
    const isSave = (url: string, method: string) =>
      url.includes('/api/dynamic/agent-definition/') && method === 'PUT';
    const [updateRequest, updateResponse] = await Promise.all([
      page.waitForRequest((r) => isSave(r.url(), r.method()), { timeout: 30_000 }),
      page.waitForResponse((r) => isSave(r.url(), r.request().method()), { timeout: 30_000 }),
      page.locator('[data-testid="scope-save-btn"]').click(),
    ]);
    // The save used to POST a route that was mapped to nothing: 404, swallowed,
    // with a success toast on screen and the record untouched. Asserting the
    // status is what tells "saved" apart from "said saved".
    expect(updateResponse.status(), 'the save must actually reach a real endpoint')
        .toBeLessThan(400);
    // Assert on what the browser actually sent. The outcome alone cannot tell
    // "the interface never sent the change" apart from "the server dropped it",
    // and those need different fixes.
    const sentOps = (updateRequest.postDataJSON() as Record<string, unknown>)?.allowed_operations;
    expect(sentOps, 'the cleared operation must be absent from the saved payload')
        .not.toContain('delete');

    // Saved state, not remembered state: reload and look again.
    await page.goto(detailUrl, { waitUntil: 'domcontentloaded' });
    await page.getByRole('tab', { name: /Tools|工具/ }).first().click()
      .catch(() => page.locator('nav[aria-label="Tabs"] button', { hasText: /Tools|工具/ }).first().click());
    await expect(
      page.locator('[data-testid="op-toggle-delete"] input[type="checkbox"]'),
      'the cleared checkbox must come back cleared, or the operator was told a lie',
    ).not.toBeChecked({ timeout: 20_000 });
    // Scroll the control into view before capturing. fullPage does not reach
    // inside a scrolling content pane, so the evidence would otherwise show the
    // top of the page and not the checkbox the assertion is about.
    await page.locator('[data-testid="op-toggle-delete"]').scrollIntoViewIfNeeded();
    await page.screenshot({ path: `${SHOTS}/30-delete-unchecked.png` });

    // --- now ask it to delete ---------------------------------------------
    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 20_000 },
    );
    const card = page.locator('[data-testid^="agent-card-"]', { hasText: COLLEAGUE_NAME });
    await card.locator('[data-testid^="agent-chat-"]').first().click();
    const chat = page.locator('[data-testid="agent-chat-page"]');
    await expect(chat).toBeVisible({ timeout: 20_000 });

    await chat.locator('[data-testid="aurabot-input"]').fill(`请删除客户「${ACCOUNT_NAME}」`);
    await chat.locator('[data-testid="aurabot-send"]').click();

    // The turn must genuinely have run. Without this the survival assertion
    // below would hold just as well if nothing had happened at all.
    //
    // Visible is not enough, which is what the evidence showed: the bubble
    // renders before any text arrives, so `toBeVisible` was satisfied by an
    // empty placeholder next to a typing indicator — the screenshot named
    // "delete-refused" showed a spinner, and the record surviving at that
    // instant proved only that the agent had not got round to deciding yet.
    // Waiting for text is what makes the survival below mean refusal.
    const reply = chat.locator('[data-testid="chat-msg-agent"]').first();
    await expect(reply).toBeVisible({ timeout: 180_000 });
    await expect
      .poll(async () => (await reply.innerText()).trim().length, { timeout: 120_000 })
      .toBeGreaterThan(10);
    await page.screenshot({ path: `${SHOTS}/31-delete-refused.png`, fullPage: true });

    // --- and the record is still there ------------------------------------
    expect(
      await stillThere(),
      'delete was cleared in the interface, so no turn may remove the record',
    ).toBe(true);
  });
});
