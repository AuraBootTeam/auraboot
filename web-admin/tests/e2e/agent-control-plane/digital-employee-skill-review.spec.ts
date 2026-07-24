/**
 * Browser E2E for the skill asset layer (OSS #1440): a digital employee, in the
 * real chat UI, uses its BOUND skill's governed tool to do real work.
 *
 * The backend fixes (#1440 + the named-agent skill→tool wiring and list:/get:
 * execution routing) make a bound builtin skill contribute its governed DSL tool
 * (list:crm_account) to a NAMED-AGENT (colleague) turn, and let that tool actually
 * execute. This closes the loop at the interface a person uses: the colleague
 * "客户运营助理·小奥" is bound to crm_quarterly_review and, asked for a review, must
 * reach into the CRM and answer from real data — not a generic reply, not silence,
 * and NOT fabrication (before the fix a skills-only colleague got no tool and
 * hallucinated "1,247 customers").
 *
 * Self-contained: the test seeds its own colleague (bound to the skill, pointed at
 * a configured LLM provider) so the golden does not depend on a colleague someone
 * created by hand — the reason the earlier version was born-red on any fresh stack.
 *
 * Assertion discipline (mirrors ai-colleague-can-talk.spec.ts): text on the page is
 * not proof of a reply — only the agent role marker on the bubble is. And a reply is
 * not proof the skill worked; the grounded facts are — the real customer count AND a
 * real customer NAME read from the CRM, neither of which a generic/hallucinated
 * answer can produce. The count alone would be hollow on a 1-customer stack (a
 * fabricated "1,247" also contains "1"); the real name is the load-bearing check.
 * Both are read from the CRM at test time, so the golden holds on any seed size.
 */

import { test, expect, type APIRequestContext } from '@playwright/test';

const COLLEAGUE_CODE = 'ops_xiaoao_crm_review';
const COLLEAGUE_NAME = '客户运营助理·小奥';
const REVIEW_SKILL = 'crm_quarterly_review';
// The digital-employee golden runs --live against oss-golden-stack, which seeds the
// qianwen LLM provider (Cloud Config) from the DASHSCOPE key the runner requires.
// A named agent resolves its LLM via guardrails.provider + model, NOT the env key
// the generic AuraBot uses.
const LLM_PROVIDER = 'qianwen';
const LLM_MODEL = 'qwen-plus';
const SHOTS = 'test-results/digital-employee';

/** Seed the ops colleague if it is not already present (idempotent). */
async function ensureOpsColleague(request: APIRequestContext): Promise<void> {
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
      description: '季度客户结构复盘助理(只读,不自动执行)',
      agent_type: 'reactive',
      model: LLM_MODEL,
      system_prompt:
        '你是客户运营助理。用户请求季度复盘时,必须用 list:crm_account 拉取真实客户数据,' +
        '按行业与评级分析结构,给出结构化复盘并提议拓客动作。只读,不自动执行写操作。用简体中文回复。',
      // The bound skill contributes its governed read tool (list:crm_account) to the turn.
      skills: JSON.stringify([REVIEW_SKILL]),
      guardrails: JSON.stringify({ provider: LLM_PROVIDER }),
      status: 'active',
    },
  });
  expect(created.ok(), 'ops colleague must be seedable').toBeTruthy();
}

test.describe('Digital employee — bound skill drives a real customer review', () => {
  // A live model is slow and its latency is not ours to control.
  test.setTimeout(240_000);

  test('the ops colleague uses its bound skill to read customers and answer with grounded facts', async ({
    page,
  }) => {
    // Self-seed the colleague so the golden is not born-red on a fresh stack.
    await ensureOpsColleague(page.request);

    // Read the real customer population the skill will ground on, so the assertions
    // are not tied to a fixed seed size. Goes through the same BFF the app uses
    // (session cookie from storageState), so it sees exactly what the colleague sees.
    const listResp = await page.request.get(
      '/api/dynamic/crm_account/list?pageNum=1&pageSize=5',
    );
    expect(listResp.ok(), 'customer list query must succeed').toBeTruthy();
    const listData = (await listResp.json())?.data ?? {};
    const customerTotal: number = listData.total ?? 0;
    const records: Array<{ crm_acc_name?: string }> = listData.records ?? [];
    expect(
      customerTotal,
      'the stack must have seeded customers for the review to ground on (env precondition)',
    ).toBeGreaterThan(0);
    const sampleName = records[0]?.crm_acc_name;
    expect(sampleName, 'a seeded customer must have a name to ground on').toBeTruthy();

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

    // The load-bearing grounding check: the review must name a REAL customer from the
    // CRM read. A generic or hallucinated answer cannot produce the seeded record's
    // exact name — this is what fails if the bound skill's read tool ever breaks again.
    await expect(
      reply,
      `the review must be grounded in the CRM read (real customer "${sampleName}"), not fabricated`,
    ).toContainText(String(sampleName), { timeout: 200_000 });
    // And it must reflect the real population size and read like a structure review.
    await expect(reply).toContainText(String(customerTotal));
    await expect(reply).toContainText(/客户|复盘|行业|评级/);

    await page.screenshot({ path: `${SHOTS}/skill-review-ui.png`, fullPage: true });
  });
});
