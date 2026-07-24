/**
 * Golden for the AI colleague detail as a DSL page (Gap 1, slice 3).
 *
 * The hand-written pages/ai/colleagues.$agentPid.tsx (2081-line, 5-tab config) is replaced by
 * a DSL page (ai_colleague_detail) whose AgentDetailTabs custom block renders the tabbed agent
 * configuration surface. The agent comes from ?agentPid= (the /p/c/ route has no path param).
 *
 * Proves the DSL page resolves the custom block and renders the detail for a real agent
 * (AuraBot, resolved via the API): the back control and the agent's name/official marker render.
 */
import { test, expect } from '@playwright/test';

test.describe('AI colleague detail — DSL page', () => {
  test('the custom block resolves ?agentPid= and renders the agent detail', async ({ page }) => {
    const list = await page.request.get(
      '/api/dynamic/agent-definition/list?pageNum=1&pageSize=100&keyword=aurabot',
    );
    expect(list.ok()).toBeTruthy();
    const records = (await list.json())?.data?.records ?? [];
    const aurabot = records.find((r: { agent_code?: string }) => r.agent_code === 'aurabot');
    expect(aurabot?.pid, 'the built-in aurabot agent must exist').toBeTruthy();

    await page.goto(`/p/c/ai_colleague_detail?agentPid=${aurabot.pid}`, {
      waitUntil: 'domcontentloaded',
    });

    await expect(
      page.locator('[data-testid="back-to-colleagues"]'),
      'the DSL page must resolve and render the AgentDetailTabs block',
    ).toBeVisible({ timeout: 20_000 });

    // The detail loaded the requested agent (AuraBot) — its name renders in the header.
    await expect(page.getByText(String(aurabot.name)).first()).toBeVisible();
  });
});
