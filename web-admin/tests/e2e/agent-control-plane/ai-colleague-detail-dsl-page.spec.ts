/**
 * AI colleague detail — per-agent knowledge binding golden.
 *
 * Coverage axes:
 * - surface: journey + DSL custom block
 * - dependencies: real-stack (PostgreSQL, backend, browser)
 * - authority: blocking-commit
 * - driver: browser
 *
 * Action matrix:
 * 1. Sidebar → colleagues grid → named colleague detail.
 * 2. Knowledge tab loads the tenant catalog and selects one explicit KB.
 * 3. Save request carries knowledge_base_ids; reload reads the persisted JSONB value.
 * 4. Chat sends no request-level knowledgeBaseIds, so the agent binding is the only source.
 * 5. render_prompt trace proves the bound KB reached the named-agent prompt.
 *
 * The exact keyword fixture intentionally remains retrievable when embeddings are unavailable.
 * A keyword-path result is seam evidence only; it is not counted as generation-quality evidence.
 */
import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

const UNIQUE = `agent-kb-${Date.now().toString(36)}`;
const AGENT_CODE = `agent_kb_${UNIQUE.replaceAll('-', '_')}`;
const AGENT_NAME = `Knowledge Colleague ${UNIQUE}`;
const KB_NAME = `Binding Manual ${UNIQUE}`;
const UNIQUE_FACT = `CALIBRATION-${UNIQUE.toUpperCase()}-137-DAYS`;
const SCREENSHOT_DIR = 'test-results/agent-knowledge-binding';

let agentPid = '';
let knowledgeBasePid = '';

function asObject(value: unknown): Record<string, any> {
  if (value && typeof value === 'object') return value as Record<string, any>;
  if (typeof value !== 'string') return {};
  try {
    const parsed = JSON.parse(value);
    return parsed && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

async function seedKnowledgeBase(request: APIRequestContext): Promise<string> {
  const created = await request.post('/api/ai/knowledge', {
    data: {
      name: KB_NAME,
      description: 'Per-agent knowledge binding golden fixture',
      embeddingProvider: '',
      embeddingModel: '',
      chunkSize: 300,
      chunkOverlap: 30,
    },
  });
  expect(created.ok(), 'knowledge base fixture must be created').toBeTruthy();
  const pid = (await created.json())?.data?.pid;
  expect(pid).toBeTruthy();

  const uploaded = await request.post(`/api/ai/knowledge/${pid}/documents/upload`, {
    multipart: {
      file: {
        name: `${UNIQUE}.txt`,
        mimeType: 'text/plain',
        buffer: Buffer.from(
          `Equipment handbook. The unique calibration rule is ${UNIQUE_FACT}.`,
          'utf-8',
        ),
      },
    },
  });
  expect(uploaded.ok(), 'knowledge document fixture must upload').toBeTruthy();

  await expect
    .poll(
      async () => {
        const documents = await request.get(`/api/ai/knowledge/${pid}/documents`);
        const rows = (await documents.json().catch(() => ({})))?.data ?? [];
        return `${rows[0]?.status}:${Number(rows[0]?.chunkCount ?? 0) > 0}`;
      },
      {
        timeout: 30_000,
        message: 'the uploaded knowledge document must be parsed into retrievable chunks',
      },
    )
    .toBe('completed:true');

  return pid;
}

async function seedNamedAgent(request: APIRequestContext): Promise<string> {
  const created = await request.post('/api/dynamic/agent-definition/create', {
    data: {
      agent_code: AGENT_CODE,
      name: AGENT_NAME,
      description: 'Named colleague used to prove explicit knowledge binding',
      agent_type: 'reactive',
      model: 'MiniMax-M2.5',
      system_prompt: 'Answer from the assigned handbook evidence when it is available.',
      guardrails: JSON.stringify({ provider: 'minimaxi' }),
      status: 'active',
    },
  });
  expect(
    created.ok(),
    `named agent fixture must be created: HTTP ${created.status()} ${await created.text()}`,
  ).toBeTruthy();
  const pid = (await created.json())?.data?.pid;
  expect(pid).toBeTruthy();
  return pid;
}

async function navigateToColleaguesViaSidebar(page: Page) {
  await page.goto('/', { waitUntil: 'domcontentloaded' });
  const entry = page.locator('a, button, [role="link"]', { hasText: /^\s*AI 同事\s*$/ }).first();
  await expect(entry, 'sidebar must expose the AI Colleagues entry').toHaveCount(1);
  await entry.click();
  await expect(page.locator('[data-testid="agent-colleagues-grid"]')).toBeVisible({
    timeout: 20_000,
  });
}

test.describe('AI colleague detail — explicit knowledge binding', () => {
  test.setTimeout(240_000);

  test.beforeAll(async ({ request }) => {
    knowledgeBasePid = await seedKnowledgeBase(request);
    agentPid = await seedNamedAgent(request);
  });

  test('binds one KB through UI, reloads it, and uses it as named-agent fallback', async ({
    page,
  }) => {
    await navigateToColleaguesViaSidebar(page);

    const card = page.locator(`[data-testid="agent-card-${AGENT_CODE}"]`);
    await expect(card).toContainText(AGENT_NAME);
    await card.locator(`[data-testid="agent-edit-${AGENT_CODE}"]`).click();
    await expect(page).toHaveURL(new RegExp(`agentPid=${agentPid}`));
    await expect(page.locator('[data-testid="back-to-colleagues"]')).toBeVisible({
      timeout: 20_000,
    });

    await page.locator('[data-testid="tab-knowledge"]').click();
    const option = page.locator(`[data-testid="agent-knowledge-option-${knowledgeBasePid}"]`);
    await expect(option).toContainText(KB_NAME);
    await expect(option).toHaveAttribute('aria-pressed', 'false');
    await option.click();
    await expect(option).toHaveAttribute('aria-pressed', 'true');

    const saveRequest = page.waitForRequest(
      (request) =>
        request.url().includes(`/api/dynamic/agent-definition/${agentPid}`) &&
        request.method() === 'PUT',
    );
    const saveResponse = page.waitForResponse(
      (response) =>
        response.url().includes(`/api/dynamic/agent-definition/${agentPid}`) &&
        response.request().method() === 'PUT',
    );
    await page.locator('[data-testid="agent-knowledge-save"]').click();
    const [request, response] = await Promise.all([saveRequest, saveResponse]);
    expect(response.status(), 'knowledge binding save must reach a real endpoint').toBeLessThan(
      400,
    );
    expect(request.postDataJSON()?.knowledge_base_ids).toEqual([knowledgeBasePid]);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.locator('[data-testid="tab-knowledge"]').click();
    await expect(
      page.locator(`[data-testid="agent-knowledge-option-${knowledgeBasePid}"]`),
      'reload must read the persisted JSONB binding rather than remembered component state',
    ).toHaveAttribute('aria-pressed', 'true');
    await page.locator('[data-testid="agent-knowledge-options"]').screenshot({
      path: `${SCREENSHOT_DIR}/knowledge-binding-reloaded.png`,
    });

    await page.locator('[data-testid="back-to-colleagues"]').click();
    await expect(page.locator('[data-testid="agent-colleagues-grid"]')).toBeVisible({
      timeout: 20_000,
    });
    await page
      .locator(`[data-testid="agent-card-${AGENT_CODE}"] [data-testid="agent-chat-${AGENT_CODE}"]`)
      .click();
    await expect(page.locator('[data-testid="agent-chat-page"]')).toBeVisible({
      timeout: 20_000,
    });

    const chatRequestPromise = page.waitForRequest(
      (request) =>
        request.url().includes('/api/ai/aurabot/chat/stream') && request.method() === 'POST',
      { timeout: 30_000 },
    );
    const agentChat = page.locator('[data-testid="agent-chat-page"]');
    await agentChat
      .locator('[data-testid="aurabot-input"]')
      .fill(`What is the calibration rule for ${UNIQUE_FACT}?`);
    await agentChat.locator('[data-testid="aurabot-send"]').click();
    const chatRequest = await chatRequestPromise;
    const chatPayload = chatRequest.postDataJSON();
    expect(chatPayload.agentCode).toBe(AGENT_CODE);
    expect(
      chatPayload.knowledgeBaseIds,
      'the turn must exercise the agent-binding fallback, not a request-level override',
    ).toBeUndefined();

    const sessionId = chatPayload.sessionId;
    expect(sessionId).toBeTruthy();
    await expect
      .poll(
        async () => {
          const traces = await page.request.get('/api/ai/traces', {
            params: { pageNum: 1, pageSize: 20, sessionId },
          });
          if (!traces.ok()) return '';
          const records = (await traces.json())?.records ?? [];
          return records[0]?.traceId ?? '';
        },
        { timeout: 60_000, message: 'named-agent turn must create a queryable trace' },
      )
      .not.toBe('');

    const traceList = await page.request.get('/api/ai/traces', {
      params: { pageNum: 1, pageSize: 20, sessionId },
    });
    const traces = (await traceList.json())?.records ?? [];
    const resolvedTraceId = traces[0]?.traceId;
    expect(resolvedTraceId).toBeTruthy();
    let promptOutput: Record<string, any> = {};
    await expect
      .poll(
        async () => {
          const traceDetail = await page.request.get(`/api/ai/traces/${resolvedTraceId}`);
          if (!traceDetail.ok()) return false;
          const spans = (await traceDetail.json())?.spans ?? [];
          const promptSpan = spans.find(
            (span: { name?: string }) => span.name === 'render_prompt',
          );
          promptOutput = asObject(promptSpan?.output);
          return promptOutput.hasRetrievedKnowledge;
        },
        {
          timeout: 60_000,
          message: 'render_prompt span must finish with retrieved named-agent knowledge',
        },
      )
      .toBe(true);
    const traceRetrieval = asObject(promptOutput.retrieval);
    expect(promptOutput.hasRetrievedKnowledge).toBe(true);
    expect(['hybrid', 'keyword']).toContain(traceRetrieval.path);
    expect(Number(traceRetrieval.resultCount)).toBeGreaterThan(0);
    expect(Array.isArray(traceRetrieval.scores)).toBe(true);

    const observationFilters = encodeURIComponent(
      JSON.stringify([{ fieldName: 'obs_agent_id', operator: 'EQ', value: AGENT_CODE }]),
    );
    let observationDetail: Record<string, any> = {};
    await expect
      .poll(
        async () => {
          const response = await page.request.get(
            `/api/dynamic/agent-observation/list?pageNum=1&pageSize=20&sortField=created_at&sortOrder=DESC&filters=${observationFilters}`,
          );
          if (!response.ok()) return '';
          const records = (await response.json())?.data?.records ?? [];
          const matched = records
            .map((record: { detail?: unknown }) => asObject(record.detail))
            .find(
              (detail: Record<string, any>) =>
                detail.eventType?.startsWith('turn.') &&
                String(detail.input ?? '').includes(UNIQUE_FACT),
            );
          observationDetail = matched ?? {};
          return asObject(matched?.retrieval).path ?? '';
        },
        {
          timeout: 60_000,
          message:
            'terminal observation must preserve the retrieval path for this named-agent turn',
        },
      )
      .toBe(traceRetrieval.path);
    expect(Number(asObject(observationDetail.retrieval).resultCount)).toBeGreaterThan(0);
    expect(Array.isArray(asObject(observationDetail.retrieval).scores)).toBe(true);

    await page.locator('[data-testid="agent-chat-page"]').screenshot({
      path: `${SCREENSHOT_DIR}/named-agent-turn.png`,
    });
  });
});
