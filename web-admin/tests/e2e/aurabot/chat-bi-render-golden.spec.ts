/**
 * B1 — chat-bi browser golden: AuraBot chat renders a chart via the `chat-bi` agent tool.
 *
 * Convergence endgame §7. This closes the S5 mis-classification ("ChatBI 即席图表浏览器
 * golden 不可做"): the correct form is an AuraBot-chat golden. The agent (stub-mode →
 * deterministic scripted tool_use) calls `aurabot:chat-bi` over the REAL `crm_lead` model;
 * the resulting {records, columns, chartType} flow through AuraBotChat → ChatBiResultCard,
 * which renders an ECharts chart inline in the chat panel.
 *
 * Backbone proven elsewhere: ChatBiSkillTest (mapping), ChatBiToolIntentLiveIT (live NL→
 * params with real DeepSeek), S5 dashboard golden (the raw aggregate path live). This spec
 * pins the last mile — the browser render of the chat-bi result card.
 *
 * Runs against the admin storageState whose tenant has the seeded crm_lead model + rows.
 */
import { test, expect, type Page, type Locator } from '../../fixtures';
import { openAuraBotPanel } from './_open-panel';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const STUB_TOOL_USE_MARKER = '@@AURABOOT_STUB_TOOL_USE@@';
const AURABOT_LAST_CONVERSATION_KEY = 'aurabot:last-conversation-id';

function stubToolUse(name: string, input: Record<string, unknown>) {
  return `${STUB_TOOL_USE_MARKER} ${JSON.stringify({ name, input })}`;
}

async function ensureFreshSession(page: Page, panel: Locator) {
  // Start a clean conversation so the scripted tool_use turn is the only content.
  const historyTrigger = panel.getByTestId('aurabot-history-trigger');
  if (await historyTrigger.count()) {
    await historyTrigger.click().catch(() => {});
    const newSessionBtn = panel.getByTestId('aurabot-new-session');
    if (await newSessionBtn.count()) {
      await newSessionBtn.click().catch(() => {});
    }
  }
  await page.evaluate((key) => window.localStorage.removeItem(key), AURABOT_LAST_CONVERSATION_KEY);
  await expect(panel.locator('textarea').first()).toBeVisible({ timeout: 10000 });
}

async function sendAuraBotMessage(page: Page, panel: Locator, message: string) {
  const input = panel.locator('textarea').first();
  await expect(input).toBeEnabled({ timeout: 150000 });
  const streamPromise = page.waitForResponse(
    (r) => r.request().method() === 'POST' && r.url().includes('/api/ai/aurabot/chat/stream'),
    { timeout: 150000 },
  );
  await input.fill(message);
  await input.press('Enter');
  const resp = await streamPromise;
  expect(resp.status(), 'AuraBot chat stream should return 200').toBe(200);
  await expect(input).toBeEnabled({ timeout: 150000 });
}

test.describe('chat-bi browser golden (AuraBot chat renders a chart)', () => {
  test.describe.configure({ timeout: 180000 });

  // BLOCKED — runnable repro of a real Slice C wiring gap found 2026-06-19.
  // The DEFAULT AuraBot chat resolves tools via LLM grounding (ChatToolResolver →
  // GroundingPort → ToolDiscoveryPort), and only fill_form / execute_sql are always-on
  // (ensurePlatformTools). The chat-bi skill is REGISTERED (AuraBotSkillToolProvider,
  // code aurabot:chat-bi) but NOT in the always-available set, so a default-chat turn
  // never offers it → AuraBotChatToolRuntimeAdapter rejects the scripted tool_use as
  // "unavailable AuraBot tool aurabot:chat-bi". (Named-agent specs work because the
  // agent carries explicit tools, bypassing grounding.) Backbone is otherwise proven:
  // ChatBiSkillTest + ChatBiToolIntentLiveIT + S5 aggregate golden + the data path
  // verified live here (crm_lead 90 rows). Fix to unblock: make chat-bi reachable by
  // the default chat — add it to ChatToolResolver's always-available tools (it is
  // read-only / LOW risk like the query tools) OR make it a grounding candidate for
  // data/chart intents. Cross-cutting (offered to every chat turn) → design first.
  // Note: the marker name must then match the SANITIZED LLM name (aurabot_chat-bi).
  test.fixme('agent chat-bi tool over crm_lead renders a chart card inline', async ({ page }) => {
    await page.goto('/');
    const panel = await openAuraBotPanel(page);
    await ensureFreshSession(page, panel);

    await sendAuraBotMessage(
      page,
      panel,
      stubToolUse('aurabot:chat-bi', {
        modelCode: 'crm_lead',
        dimensions: ['crm_lead_status'],
        metrics: [{ field: 'pid', aggregation: 'count', alias: 'cnt' }],
        chartType: 'bar',
        interpretation: 'Leads by status',
      }),
    );

    // The chat-bi tool_result (records) renders as a ChatBiResultCard.
    const card = panel.getByTestId('chatbi-result-card');
    await expect(card, 'chat-bi result card should render in chat').toBeVisible({ timeout: 30000 });
    await expect(card).toHaveAttribute('data-chart-type', 'bar');

    // Real aggregate over the seeded crm_lead statuses → at least one grouped row.
    const rowCount = Number(await card.getAttribute('data-row-count'));
    expect(rowCount, 'chat-bi should return real grouped rows from crm_lead').toBeGreaterThan(0);

    // ECharts renders an SVG inside the card's chart area.
    await expect(
      panel.getByTestId('chatbi-chart-area').locator('svg').first(),
      'ECharts SVG should render',
    ).toBeVisible({ timeout: 30000 });

    // Interpretation header text flows through.
    await expect(card).toContainText('Leads by status');
  });
});
