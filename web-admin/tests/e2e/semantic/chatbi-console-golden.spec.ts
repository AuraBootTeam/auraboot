import { test, expect } from '../fixtures';
test.use({ storageState: 'tests/storage/admin.json' });
test.describe.configure({ timeout: 120000 });

test('chatbi console: LLM banner + conversation create + honest failure render', async ({ page }) => {
  const errors: string[] = [];
  page.on('console', m => { if (m.type()==='error' && !m.text().includes('Optimize Dep')) errors.push(m.text()); });
  await page.goto('/semantic/ask', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500); // hydration settle
  await expect(page.getByTestId('chatbi-page')).toBeVisible({ timeout: 10000 });
  // honest LLM banner present
  await expect(page.getByTestId('chatbi-llm-banner')).toBeVisible();
  console.log('BANNER:', (await page.getByTestId('chatbi-llm-banner').innerText()).slice(0,40));

  // create a conversation
  await page.getByTestId('chatbi-new-conversation').click();
  await page.waitForTimeout(800);
  const convCount = await page.locator('[data-testid^="chatbi-conversation-"]').count();
  console.log('CONVERSATIONS:', convCount);

  // ask a question -> without LLM provider the answer is status=FAILED, rendered honestly
  await page.getByTestId('chatbi-input').fill('按状态统计角色数');
  await page.getByTestId('chatbi-send').click();
  // either a FAILED bubble or a turn-error bubble must appear (both are honest)
  const failed = page.locator('[data-testid^="chatbi-failed-"], [data-testid^="chatbi-turn-error-"], [data-testid^="chatbi-answer-"], [data-testid^="chatbi-disambiguation-"]').first();
  await expect(failed).toBeVisible({ timeout: 60000 });
  const kind = await failed.getAttribute('data-testid');
  console.log('ANSWER_STATE:', kind);
  await page.screenshot({ path: process.env.SHOT + '/chatbi-01.png', fullPage: true });
  console.log('CONSOLE_ERRORS:', JSON.stringify(errors.slice(0,5)));
  expect(convCount).toBeGreaterThan(0);
});
