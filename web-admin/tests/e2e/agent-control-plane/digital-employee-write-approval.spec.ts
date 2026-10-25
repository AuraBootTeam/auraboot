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
 * pointed at a configured provider). It sets `allowed_models: [crm_account_common]` so tool
 * discovery is scoped to the CRM model — without it, a fixture-polluted stack floods
 * the turn with unrelated ACP tools and the model picks unreliably (root cause of the
 * earlier intermittent write turns).
 *
 * Assertion discipline: the amber confirm card (role marker) is the proof the write was
 * gated (not silently executed); the DB row appearing only AFTER the click is the proof
 * the approval drove the real command. A unique per-run name makes the DB check exact.
 */

import { test, expect, type APIRequestContext } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { Client } from 'pg';
import { LoginPage } from '../../pages/LoginPage';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';

const approvalFixturePath = process.env.AURA_APPROVAL_JOURNEY_FIXTURE;
const approvalFixture = approvalFixturePath
  ? JSON.parse(readFileSync(approvalFixturePath, 'utf8'))
  : null;

const COLLEAGUE_CODE = 'ops_xiaoao_crm_writer';
const COLLEAGUE_NAME = '客户录入助理·小奥';
const WRITE_COMMAND = 'cmd:crm:create_account';
const SHOTS = 'test-results/digital-employee';

async function ensureWriteColleague(request: APIRequestContext): Promise<void> {
  const llmProvider = process.env.AURA_LIVE_LLM_PROVIDER;
  const llmModel = process.env.AURA_LIVE_LLM_MODEL;
  expect(llmProvider, 'AURA_LIVE_LLM_PROVIDER must select the live adapter').toBeTruthy();
  expect(llmModel, 'AURA_LIVE_LLM_MODEL must select the live model').toBeTruthy();

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
      model: llmModel,
      system_prompt:
        '你是客户录入助理。用户给出客户信息时,调用 crm:create_account 工具创建客户;' +
        '系统会弹出确认框由用户确认,你直接调用工具即可。用简体中文回复。',
      tools: JSON.stringify([WRITE_COMMAND]),
      // Scope discovery to the CRM model so unrelated (ACP fixture) tools do not flood
      // the turn and make the model pick unreliably.
      allowed_models: JSON.stringify(['crm_account_common']),
      guardrails: JSON.stringify({ provider: llmProvider }),
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
    `/api/dynamic/crm_account_common/list?pageNum=1&pageSize=1&filters=${filters}`,
  );
  expect(resp.ok(), 'crm_account_common count query must succeed').toBeTruthy();
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

    await page.goto('/p/c/ai_colleagues', { waitUntil: 'domcontentloaded' });
    await expect(page.locator('[data-testid="agent-colleagues-grid"]')).toBeVisible({
      timeout: 20_000,
    });

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
      `/api/dynamic/crm_account_common/list?pageNum=1&pageSize=1&filters=${encodeURIComponent(
        JSON.stringify([{ fieldName: 'crm_acc_name', operator: 'EQ', value: recordName }]),
      )}`,
    );
    const pid = (await listResp.json())?.data?.records?.[0]?.pid;
    if (pid) {
      // Delete endpoint is DELETE /api/dynamic/{pageKey}/{recordPid} (DynamicController#delete).
      await page.request.delete(`/api/dynamic/crm_account_common/${pid}`).catch(() => {});
    }
  });
});

if (approvalFixture) {
  test.use({
    storageState: { cookies: [], origins: [] },
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });
  test(
    approvalFixture.mode && approvalFixture.mode !== 'approve'
      ? `a real model L3 write approval boundary ${approvalFixture.mode} preserves zero business writes across reload`
      : 'a real model L3 write waits for UI approval and persists exactly once across reload',
    async ({ page, baseURL }, info) => {
      test.setTimeout(180_000);
      const fixture = approvalFixture;
      const output = process.env.AURA_AGENT_ACCEPTANCE_DIR;
      const database = process.env.AURA_APPROVAL_DATABASE_URL;
      expect(output, 'pin the scenario evidence directory').toBeTruthy();
      expect(database, 'pin the isolated approval database').toBeTruthy();
      expect(process.env.AGENT_LLM_STUB_MODE).not.toBe('true');
      for (const identifier of [fixture.model, fixture.field])
        expect(identifier).toMatch(/^[a-z][a-z0-9_]+$/);
      const db = new Client({ connectionString: database });
      await db.connect();
      const marker = `审批验收-${randomUUID().replaceAll('-', '')}`;
      const recordRows = async () =>
        (
          await db.query(
            `SELECT pid, "${fixture.field}" AS marker FROM "mt_${fixture.model}" WHERE tenant_id=$1 AND "${fixture.field}"=$2`,
            [fixture.tenantId, marker],
          )
        ).rows;
      const approvalRows = async () =>
        (
          await db.query(
            `SELECT pid, approval_status, consumed_at, expires_at, approver_id, run_id, task_id, request_data FROM ab_agent_approval WHERE tenant_id=$1 AND request_data::jsonb->>$2=$3`,
            [fixture.tenantId, fixture.field, marker],
          )
        ).rows;
      mkdirSync(output!, { recursive: true });
      try {
        expect(await recordRows()).toEqual([]);
        expect(await approvalRows()).toEqual([]);
        const login = new LoginPage(page);
        await login.goto();
        await login.waitForFormReady();
        await login.emailInput.fill(DEFAULT_TEST_ACCOUNT.email);
        await login.passwordInput.fill(DEFAULT_TEST_ACCOUNT.password);
        await login.submitButton.click();
        await expect(page.getByTestId('ai-panel-toggle')).toBeVisible();
        await page
          .getByTestId('sidebar')
          .getByRole('link', { name: fixture.menuName, exact: true })
          .click();
        await expect(page.getByTestId('agent-colleagues-grid')).toBeVisible();
        const agentCard = page.getByTestId(`agent-card-${fixture.agentCode}`);
        await expect(agentCard).toContainText(fixture.agentName);
        await agentCard.getByTestId(`agent-chat-${fixture.agentCode}`).click();
        const chat = page.getByTestId('agent-chat-page');
        await expect(chat).toBeVisible();
        await chat
          .getByTestId('aurabot-input')
          .fill(
            `请创建一条审批验收记录，名称必须精确为：${marker}。请实际调用绑定的创建工具，等待我确认后再写入。`,
          );
        const proposed = page.waitForResponse(
          (r) =>
            new URL(r.url()).pathname === '/api/ai/aurabot/chat/stream' &&
            r.request().method() === 'POST',
        );
        await chat.getByTestId('aurabot-send').click();
        const stream = await proposed;
        expect(stream.status()).toBe(200);
        expect(stream.request().postDataJSON().agentCode).toBe(fixture.agentCode);
        const confirm = chat.getByTestId('aurabot-confirm-card');
        await expect(confirm).toBeVisible({ timeout: 120_000 });
        await expect(confirm).toContainText(marker);
        await expect(confirm).toContainText('审批业务操作');
        await expect(chat).toContainText('该操作需要审批');
        await expect(confirm).not.toContainText('This tool requires');
        await expect(confirm).not.toContainText(fixture.toolCode.replaceAll(':', '_'));
        await expect(confirm).not.toContainText(fixture.field);
        await expect(confirm).not.toContainText('{');
        await expect(chat.getByTestId('chat-msg-agent').filter({ hasText: /^\s*$/ })).toHaveCount(0);
        const pending = await approvalRows();
        expect(pending).toHaveLength(1);
        expect(fixture.mode === 'expired' ? ['pending', 'expired'] : ['pending']).toContain(
          pending[0].approval_status,
        );
        expect(pending[0].consumed_at).toBeNull();
        expect(await recordRows()).toEqual([]);
        if (fixture.mode && fixture.mode !== 'approve') {
          const mode = fixture.mode;
          expect(['cancel', 'expired', 'unauthorized', 'unauthorized-cancel']).toContain(mode);
          const isCancel = mode === 'cancel' || mode === 'unauthorized-cancel';
          const screenshot = `APPROVAL-${mode.toUpperCase()}`;
          await page.screenshot({ path: `${output}/${screenshot}-PENDING.png`, fullPage: true });
          const decisionResponse = page.waitForResponse(
            (r) =>
              new URL(r.url()).pathname === '/api/ai/aurabot/execute' &&
              r.request().method() === 'POST',
          );
          await confirm
            .getByTestId(isCancel ? 'aurabot-confirm-cancel' : 'aurabot-confirm-approve')
            .click();
          const decision = await decisionResponse;
          expect(decision.status()).toBe(200);
          const payload = decision.request().postDataJSON();
          expect(payload).toMatchObject({
            pendingTurnId: pending[0].pid,
            confirmed: !isCancel,
          });
          const responseText = await decision.text();
          if (mode === 'cancel') {
            await expect
              .poll(async () => (await approvalRows())[0].approval_status)
              .toBe('rejected');
            await expect(chat).toContainText('操作已取消');
            await expect(chat.getByTestId('chat-tool-cancelled')).toHaveAttribute('role', 'status');
            await expect(chat.getByTestId('chat-tool-cancelled')).toBeVisible();
            const task = (
              await db.query(
                'SELECT task_status FROM ab_agent_task WHERE tenant_id=$1 AND pid=$2',
                [fixture.tenantId, pending[0].task_id],
              )
            ).rows;
            expect(task).toHaveLength(1);
            expect(task[0].task_status).toBe('failed');
          } else {
            if (mode === 'expired') {
              expect(responseText).toMatch(/expired|no longer pending/);
              const expired = (
                await db.query(
                  'SELECT expires_at <= NOW() AS expired FROM ab_agent_approval WHERE tenant_id=$1 AND pid=$2',
                  [fixture.tenantId, pending[0].pid],
                )
              ).rows;
              expect(expired).toEqual([{ expired: true }]);
              await expect(chat).toContainText('该审批已过期或已处理，请刷新后重新查看。');
            } else {
              expect(responseText).toContain('Insufficient permission to decide this approval');
              expect(String(fixture.approverUserId)).not.toBe(String(fixture.userId));
              await expect(chat).toContainText('你无权处理这项审批，请联系指定审批人。');
            }
            await expect(chat).not.toContainText(pending[0].pid);
            await expect(chat).not.toContainText('Insufficient permission');
          }
          await expect(chat).not.toContainText('正在执行...');
          await expect(chat).not.toContainText('审批通过，操作已完成。');
          await expect(chat.getByTestId('aurabot-confirm-approve')).toHaveCount(0);
          const terminal = await approvalRows();
          expect(terminal).toHaveLength(1);
          expect(terminal[0].consumed_at).toBeNull();
          expect(terminal[0].approval_status).not.toBe('approved');
          if (mode.startsWith('unauthorized')) expect(terminal[0].approval_status).toBe('pending');
          expect(await recordRows()).toEqual([]);
          await page.screenshot({ path: `${output}/${screenshot}.png`, fullPage: true });
          await page.reload();
          const restored = page.getByTestId('agent-chat-page');
          await expect(restored).toContainText(marker);
          await expect(restored.getByTestId('aurabot-confirm-approve')).toHaveCount(0);
          expect(await recordRows()).toEqual([]);
          await page.screenshot({ path: `${output}/${screenshot}-RELOADED.png`, fullPage: true });
          const replay = await page.request.post('/api/ai/aurabot/execute', {
            headers: { Origin: baseURL! },
            data: { ...payload, confirmed: true },
          });
          expect(await replay.text()).toMatch(
            mode.startsWith('unauthorized')
              ? /Insufficient permission/
              : /expired|no longer pending/,
          );
          expect(await recordRows()).toEqual([]);
          await info.attach('approval-boundary-identities', {
            body: JSON.stringify({
              mode,
              marker,
              pending,
              terminal,
              payload,
              replayStatus: replay.status(),
            }),
            contentType: 'application/json',
          });
          return;
        }
        const initialUrl = page.url();
        await page.screenshot({ path: `${output}/APPROVAL-PENDING.png`, fullPage: true });
        const execution = page.waitForResponse(
          (r) =>
            new URL(r.url()).pathname === '/api/ai/aurabot/execute' &&
            r.request().method() === 'POST',
        );
        await confirm.getByTestId('aurabot-confirm-approve').click();
        const executed = await execution;
        expect(executed.status()).toBe(200);
        expect(executed.request().postDataJSON()).toMatchObject({
          pendingTurnId: pending[0].pid,
          confirmed: true,
        });
        await expect.poll(recordRows, { timeout: 30_000 }).toHaveLength(1);
        await expect(confirm.getByTestId('aurabot-confirm-approve')).toHaveCount(0);
        await expect(chat).toContainText('审批通过，操作已完成。');
        await expect(chat).not.toContainText('正在执行...');
        const approved = await approvalRows();
        expect(approved).toHaveLength(1);
        expect(approved[0].approval_status).toBe('approved');
        expect(approved[0].consumed_at).not.toBeNull();
        const conversationId = await page.evaluate(() =>
          localStorage.getItem('aurabot.lastConversationId'),
        );
        expect(conversationId).toMatch(/^\d+$/);
        const outboundRows = async () =>
          (
            await db.query(
              "SELECT id,sender_id,content FROM ab_im_message WHERE tenant_id=$1 AND conversation_id=$2 AND sender_type='agent' AND content='Approved tool executed.'",
              [fixture.tenantId, conversationId],
            )
          ).rows;
        await expect.poll(outboundRows, { timeout: 15_000 }).toHaveLength(1);
        const outbound = await outboundRows();
        const expectedAgent = (
          await db.query('SELECT id FROM ab_agent_definition WHERE tenant_id=$1 AND pid=$2', [
            fixture.tenantId,
            fixture.agentPid,
          ])
        ).rows;
        expect(expectedAgent).toHaveLength(1);
        expect(String(outbound[0].sender_id)).toBe(String(expectedAgent[0].id));
        const persisted = await recordRows();
        expect(persisted[0].marker).toBe(marker);
        await page.screenshot({ path: `${output}/APPROVAL-COMPLETED.png`, fullPage: true });
        await page.reload();
        await expect(page.getByTestId('agent-chat-page')).toBeVisible();
        expect(page.url()).toBe(initialUrl);
        await expect(page.getByTestId('agent-chat-page')).toContainText(marker);
        await expect(
          page.getByTestId('agent-chat-page').getByTestId('chat-msg-agent'),
        ).toContainText('审批通过，操作已完成。');
        await expect(page.getByTestId('aurabot-confirm-approve')).toHaveCount(0);
        expect(await recordRows()).toEqual(persisted);
        await page.screenshot({ path: `${output}/APPROVAL-RELOADED.png`, fullPage: true });
        // Replay the captured UI action only as a negative probe; it must not create a second record.
        const repeated = await page.request.post('/api/ai/aurabot/execute', {
          headers: { Origin: baseURL! },
          data: executed.request().postDataJSON(),
        });
        const repeatedBody = await repeated.text();
        expect(repeatedBody).toContain('no longer pending');
        expect(await recordRows()).toEqual(persisted);
        await info.attach('approval-journey-identities', {
          body: JSON.stringify({
            marker,
            conversationId,
            outbound,
            pending,
            approved,
            persisted,
            chatUrl: initialUrl,
            repeatedStatus: repeated.status(),
          }),
          contentType: 'application/json',
        });
      } finally {
        await db.end();
      }
    },
  );
}
