/**
 * Browser UI-E2E for the digital-employee WRITE + APPROVAL flow (owner gap 3):
 * a colleague proposes a governed write, the amber confirm card renders in the chat,
 * the user clicks Confirm, and the row actually lands in the CRM.
 *
 * This is the browser half the backend `AgentWriteCommandPipelineIT` (deterministic)
 * cannot cover: that the confirm_required tool surfaces as a clickable card and that
 * approving it drives the real command → DB. It complements the read golden
 * (digital-employee-skill-review) which covers the read path.
 *
 * Self-contained: seeds its own write colleague (bound to cmd:crm:create_account,
 * pointed at a configured provider). It sets `allowed_models: [crm_account]` so tool
 * discovery is scoped to the CRM model — without it, a fixture-polluted stack floods
 * the turn with unrelated ACP tools and the model picks unreliably (root cause of the
 * earlier intermittent write turns).
 *
 * Assertion discipline: the amber confirm card (role marker) is the proof the write was
 * gated (not silently executed); the DB row appearing only AFTER the click is the proof
 * the approval drove the real command. A unique per-run name makes the DB check exact.
 */

import { test, expect, type APIRequestContext } from '@playwright/test';

const COLLEAGUE_CODE = 'ops_xiaoao_crm_writer';
const COLLEAGUE_NAME = '客户录入助理·小奥';
const WRITE_COMMAND = 'cmd:crm:create_account';
const LLM_PROVIDER = 'qianwen';
const LLM_MODEL = 'qwen-plus';
const SHOTS = 'test-results/digital-employee';

async function ensureWriteColleague(request: APIRequestContext): Promise<void> {
  const list = await request.get(
    `/api/dynamic/agent-definition/list?pageNum=1&pageSize=20&keyword=${COLLEAGUE_CODE}`,
  );
  expect(list.ok(), 'agent-definition list API must be available').toBeTruthy();
  const existing = ((await list.json())?.data?.records ?? []).find(
    (r: { agent_code?: string }) => r.agent_code === COLLEAGUE_CODE,
  );
  if (existing) return;

  const created = await request.post('/api/dynamic/agent-definition/create', {
    data: {
      agent_code: COLLEAGUE_CODE,
      name: COLLEAGUE_NAME,
      description: '客户录入助理(写操作需用户确认)',
      agent_type: 'reactive',
      model: LLM_MODEL,
      system_prompt:
        '你是客户录入助理。用户给出客户信息时,调用 crm:create_account 工具创建客户;' +
        '系统会弹出确认框由用户确认,你直接调用工具即可。用简体中文回复。',
      tools: JSON.stringify([WRITE_COMMAND]),
      // Scope discovery to the CRM model so unrelated (ACP fixture) tools do not flood
      // the turn and make the model pick unreliably.
      allowed_models: JSON.stringify(['crm_account']),
      guardrails: JSON.stringify({ provider: LLM_PROVIDER }),
      status: 'active',
    },
  });
  expect(created.ok(), 'write colleague must be seedable').toBeTruthy();
}

async function crmAccountCount(request: APIRequestContext, name: string): Promise<number> {
  const filters = encodeURIComponent(
    JSON.stringify([{ fieldName: 'crm_acc_name', operator: 'EQ', value: name }]),
  );
  const resp = await request.get(
    `/api/dynamic/crm_account/list?pageNum=1&pageSize=1&filters=${filters}`,
  );
  expect(resp.ok(), 'crm_account count query must succeed').toBeTruthy();
  return (await resp.json())?.data?.total ?? 0;
}

test.describe('Digital employee — write + approval through the browser UI', () => {
  // A governed write needs a real model to emit a structured tool call — a stub
  // cannot produce the confirm card this spec asserts on. So it belongs to the
  // live tier (digital-employee-golden-run.sh --live), and self-skips under the
  // runner's default stub mode rather than failing there: a check that can only
  // ever be red buries every real failure after it. When run directly against a
  // live backend, AGENT_LLM_STUB_MODE is unset and the spec runs.
  test.skip(
    process.env.AGENT_LLM_STUB_MODE === 'true',
    'needs a live model to emit a real write tool call; run the golden with --live',
  );
  test.setTimeout(240_000);

  test('a proposed write surfaces a confirm card; approving it creates the CRM row', async ({
    page,
  }) => {
    await ensureWriteColleague(page.request);

    const recordName = `UITEST写场景-${Date.now()}`;
    expect(await crmAccountCount(page.request, recordName)).toBe(0);

    await page.goto('/ai/colleagues', { waitUntil: 'domcontentloaded' });
    await page.waitForResponse(
      (r) => r.url().includes('/agent-definition/list') && r.status() === 200,
      { timeout: 20_000 },
    );

    const card = page.locator('[data-testid^="agent-card-"]', { hasText: COLLEAGUE_NAME });
    await expect(card, 'the write colleague must be listed').toBeVisible({ timeout: 20_000 });
    await card.locator('[data-testid^="agent-chat-"]').first().click();

    const chat = page.locator('[data-testid="agent-chat-page"]');
    await expect(chat).toBeVisible({ timeout: 20_000 });

    const input = chat.locator('[data-testid="aurabot-input"]');
    await expect(input).toBeVisible({ timeout: 20_000 });
    await input.fill(`帮我新建一个客户:名称 ${recordName},行业 软件,评级 A`);
    await chat.locator('[data-testid="aurabot-send"]').click();

    await expect(chat.locator('[data-testid="chat-msg-user"]').first()).toBeVisible({
      timeout: 20_000,
    });

    // The governed write must surface as a confirm card, NOT execute silently.
    const confirmCard = chat.locator('[data-testid="aurabot-confirm-card"]').last();
    await expect(
      confirmCard,
      'a proposed write must render the amber confirm card, not run silently',
    ).toBeVisible({ timeout: 200_000 });
    // The card must show the customer name we asked to create (grounded in the real args).
    await expect(confirmCard).toContainText(recordName);

    // Not yet approved → nothing written.
    expect(await crmAccountCount(page.request, recordName)).toBe(0);

    await page.screenshot({ path: `${SHOTS}/write-confirm-card.png`, fullPage: true });

    // Approve → the real command runs → the row appears.
    await confirmCard.locator('[data-testid="aurabot-confirm-approve"]').click();

    await expect
      .poll(() => crmAccountCount(page.request, recordName), {
        timeout: 60_000,
        message: 'approving the confirm card must drive the real create command → a CRM row',
      })
      .toBe(1);

    await page.screenshot({ path: `${SHOTS}/write-approved.png`, fullPage: true });

    // Cleanup: remove the row this run created so reruns stay deterministic.
    const listResp = await page.request.get(
      `/api/dynamic/crm_account/list?pageNum=1&pageSize=1&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'crm_acc_name', operator: 'EQ', value: recordName }]),
      )}`,
    );
    const pid = (await listResp.json())?.data?.records?.[0]?.pid;
    if (pid) {
      // Delete endpoint is DELETE /api/dynamic/{pageKey}/{recordPid} (DynamicController#delete).
      await page.request.delete(`/api/dynamic/crm_account/${pid}`).catch(() => {});
    }
  });
});
