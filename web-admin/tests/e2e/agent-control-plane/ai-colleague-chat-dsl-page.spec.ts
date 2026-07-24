/**
 * Golden for the AI colleague chat as a DSL page (Gap 1, slice 5).
 *
 * The hand-written pages/ai/colleagues.$agentPid.chat.tsx is replaced by a DSL page
 * (ai_colleague_chat) whose AgentChatEmbed custom block wraps the existing AuraBotChat. The
 * agent comes from ?agentPid= (the DSL /p/c/ route has no path param).
 *
 * Proves the DSL page resolves the custom block and mounts the chat surface for a real agent
 * (AuraBot, resolved via the API) — header, back button, and the chat area all render.
 */
import { test, expect } from '@playwright/test';

test.describe('AI colleague chat — DSL page', () => {
  test('the custom block resolves ?agentPid= and mounts the chat surface', async ({ page }) => {
    // AuraBot is the built-in agent; resolve its pid via the API.
    const list = await page.request.get(
      '/api/dynamic/agent-definition/list?pageNum=1&pageSize=100&keyword=aurabot',
    );
    expect(list.ok(), 'agent-definition list API must be available').toBeTruthy();
    const records = (await list.json())?.data?.records ?? [];
    const aurabot = records.find((r: { agent_code?: string }) => r.agent_code === 'aurabot');
    expect(aurabot?.pid, 'the built-in aurabot agent must exist').toBeTruthy();

    await page.goto(`/p/c/ai_colleague_chat?agentPid=${aurabot.pid}`, {
      waitUntil: 'domcontentloaded',
    });

    const chat = page.locator('[data-testid="agent-chat-page"]');
    await expect(chat, 'the DSL page must resolve and mount the AgentChatEmbed block').toBeVisible({
      timeout: 20_000,
    });
    // Header controls of the chat surface render (proves the block mounted for the agent).
    await expect(page.locator('[data-testid="agent-chat-back-btn"]')).toBeVisible();
    await expect(page.locator('[data-testid="agent-chat-new-session-btn"]')).toBeVisible();
  });
});
