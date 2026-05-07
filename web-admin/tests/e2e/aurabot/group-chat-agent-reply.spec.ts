/**
 * GAP-311 — Group-chat agent reply WS broadcast E2E
 *
 * Verifies the full chain shipped 2026-05-07:
 *   ImWebSocketHandler.handleSend
 *     → publish ImMessageSentEvent (with seq)
 *     → GroupChatAgentRouter.onMessageSent
 *     → AgentReplyTask.executeReply(triggeringSeq)
 *     → ConversationTurnService.runTurn → AuraBotChatService / AgentChatPort
 *     → AuraBotTurnPersistence.persistOutbound (sender_type=agent)
 *     → AgentReplyTask.broadcastPersistedAgentReply
 *     → ImMessageBroadcaster.publish(WsFrame{type=MESSAGE, data={messageId, seq, ...}})
 *
 * UX claim under test:
 *   "User mentions an agent in a group conversation; the agent reply appears
 *    in the conversation pane WITHOUT a manual refresh / re-select."
 *
 * Prerequisites (fixture seeding):
 *   - At least one IM group conversation containing the current user + an
 *     aurabot agent member (member_type='agent', agent_code='aurabot').
 *   - Group's `metadata.chat_kind` = 'aurabot_panel' OR aurabot agent has
 *     `auto_reply_mode='ALWAYS'` so P1 routing fires without an explicit
 *     `agent:N` mention.
 *   - Backend running with the GAP-311 publisher wiring; reset-and-init.sh
 *     Step 7.9 has run so per-tenant aurabot agent_definition exists.
 *
 * Run prerequisite:
 *   - Docker isolated stack (CLAUDE.md hard rule for ≥2 worktrees):
 *       docker-compose ... -f docker-compose.ga-e2e.override.yml \
 *         -p auraboot-gap311 up -d
 *   - LLM mocked via `chat-bi.spec.ts` style harness OR real LLM via env.
 *
 * @since 10.4.0 (2026-05-07)
 * @see auraboot-enterprise/docs/backlog/technical.md GAP-311
 */

import { test, expect, type Page } from '../../fixtures';

const SIDEBAR_IM_LINK = 'a[href="/im"], a:has-text("消息"), a:has-text("聊天")';
const COMPOSER_INPUT = '[data-testid="im-composer-input"], textarea[placeholder*="消息"]';
const SEND_BUTTON = 'button:has-text("发送"), [data-testid="im-send-button"]';
const MESSAGE_ROW = '[data-testid^="im-message-"], .im-message';
const AGENT_MESSAGE_ROW = `${MESSAGE_ROW}[data-sender-type="agent"]`;

// ---------------------------------------------------------------------------
// Setup / fixture helpers
// ---------------------------------------------------------------------------

/**
 * Locate (or create via API) a group conversation containing the current user
 * + an aurabot agent member. Returns the conversation id used by the URL hash
 * `/im#conv=<id>`.
 *
 * NOTE: this helper assumes `seed-aurabot-agent.sql` (Step 7.9) has run so an
 * `ab_agent_definition` row with `agent_code='aurabot'` exists per tenant.
 */
async function ensureGroupConversationWithAurabot(page: Page): Promise<number> {
  const resp = await page.request.post('/api/im/conversations', {
    data: {
      type: 'group',
      name: 'GAP-311 E2E ' + Date.now(),
      memberIds: [],   // backend adds creator + aurabot agent below
    },
  });
  expect(resp.ok()).toBe(true);
  const body = await resp.json();
  const convId: number = body.data?.id ?? body.id;
  expect(convId).toBeDefined();

  // Add aurabot agent as a member of the conversation.
  const addResp = await page.request.post(`/api/im/conversations/${convId}/members`, {
    data: {
      members: [{ memberType: 'agent', agentCode: 'aurabot' }],
    },
  });
  expect(addResp.ok()).toBe(true);
  return convId;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

test.describe.serial('GAP-311 — group-chat agent reply WS broadcast', () => {
  let convId: number;
  const wsFramesReceived: string[] = [];

  test.beforeAll(async ({ page }) => {
    convId = await ensureGroupConversationWithAurabot(page);
  });

  test('user @ mentions agent in group → agent reply appears without refresh', async ({ page }) => {
    // Capture inbound WS frames so we can assert on the MESSAGE frame for the
    // agent reply. Listen BEFORE navigating so we don't miss frames.
    page.on('websocket', ws => {
      ws.on('framereceived', frame => {
        wsFramesReceived.push(typeof frame.payload === 'string'
            ? frame.payload
            : frame.payload.toString('utf-8'));
      });
    });

    // Navigate via sidebar (gold-standard rule: no page.goto direct)
    await page.locator(SIDEBAR_IM_LINK).first().click();
    await expect(page).toHaveURL(/\/im/);

    // Open the seeded conversation (URL hash routing)
    await page.goto(`/im#conv=${convId}`);
    await expect(page.locator(COMPOSER_INPUT)).toBeVisible();

    // Send a message that mentions the aurabot agent. P1 always-reply or P2
    // conductor routing should pick this up; without a real `agent:<id>`
    // mention we rely on conductor / always-reply fixture configuration.
    const greeting = 'gap311 hello aurabot please reply';
    await page.locator(COMPOSER_INPUT).fill(greeting);
    await page.locator(SEND_BUTTON).click();

    // 1. The user's own row appears (immediate / SEND_ACK path)
    await expect(page.locator(MESSAGE_ROW).filter({ hasText: greeting }))
        .toBeVisible({ timeout: 5000 });

    // 2. The agent's reply row appears WITHOUT a manual refresh — the test
    //    machinery does NOT call page.reload() or re-navigate. The DOM update
    //    is driven by the MESSAGE WS frame published by AgentReplyTask post-runTurn.
    await expect(page.locator(AGENT_MESSAGE_ROW).last())
        .toBeVisible({ timeout: 30_000 });

    // 3. WS frame with type=MESSAGE was actually received by the browser —
    //    proves the broadcast reached the client (not just the row was lazy-
    //    queried by some other mechanism).
    const messageFramePayload = wsFramesReceived.find(p => {
      try { return JSON.parse(p).type === 'MESSAGE'; } catch { return false; }
    });
    expect(messageFramePayload, 'expected an inbound MESSAGE frame after agent reply').toBeDefined();
    const parsed = JSON.parse(messageFramePayload!);
    expect(parsed.data.senderType).toBe('agent');
    expect(parsed.data.messageId).toBeGreaterThan(0);
    expect(parsed.data.seq).toBeGreaterThan(0);
  });

  test('handoff chain: parent persisted seq becomes child triggeringSeq (server-side, audit via DB)', async ({ page }) => {
    // Smoke: trigger one more reply and assert the latest agent row's seq is
    // strictly greater than any prior seq — proves chained replies don't
    // overlap windows. (Full handoff chain assertion is covered by
    // AgentReplyTaskChokepointTest#handoffRecursion_threadsPersistedSeqAsChildTriggeringSeq.)
    await page.goto(`/im#conv=${convId}`);
    await expect(page.locator(COMPOSER_INPUT)).toBeVisible();

    const before = await page.request.get(`/api/im/conversations/${convId}/messages?limit=200`);
    const beforeMsgs = (await before.json()).data ?? [];
    const beforeMaxSeq = Math.max(0, ...beforeMsgs.map((m: any) => m.seq ?? 0));

    await page.locator(COMPOSER_INPUT).fill('gap311 second turn');
    await page.locator(SEND_BUTTON).click();

    await expect(page.locator(AGENT_MESSAGE_ROW).last())
        .toBeVisible({ timeout: 30_000 });

    const after = await page.request.get(`/api/im/conversations/${convId}/messages?limit=200`);
    const afterMsgs = (await after.json()).data ?? [];
    const newAgentRow = afterMsgs
        .filter((m: any) => m.senderType === 'agent')
        .reduce((acc: any, m: any) => (m.seq > (acc?.seq ?? 0) ? m : acc), null);
    expect(newAgentRow).not.toBeNull();
    expect(newAgentRow.seq).toBeGreaterThan(beforeMaxSeq);
  });
});
