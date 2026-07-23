/**
 * "实时生效" — the last mile.
 *
 * Every other golden in this directory stops at the Retrieval Test tab: it proves the document was
 * chunked, embedded and is findable through /api/ai/knowledge/retrieve. That is a debug panel. The
 * requirement says the uploaded knowledge takes effect — and what a user means by that is that
 * **AuraBot answers with it**.
 *
 * The seam is real (AuraBotChatService.resolveRagContext → RagContextProvider), but it had never
 * been driven end to end. So this uploads a document, opens the assistant, selects the knowledge
 * base, asks a question, and asserts the answer carries a fact from that document.
 *
 * The fact is chosen so the model cannot produce it any other way. "47 days" and "ZX-9137" are
 * invented; no pre-training could supply them, and the question never mentions them. If they come
 * back in the answer, they came through RAG — there is no other path.
 *
 * Needs DASHSCOPE_API_KEY: an assistant with no LLM behind it has nothing to answer with.
 */

import { test, expect } from '@playwright/test';
import { openAuraBotPanel } from '../aurabot/_open-panel';
import { uniqueId } from '../helpers';

// App defaults to zh-CN (localStorage 'locale' / cookie); these KB specs assert the
// English UI. Force the en-US locale cookie so SSR renders English strings.
test.beforeEach(async ({ context }) => {
  await context.addCookies([{ name: 'locale', value: 'en-US', domain: '127.0.0.1', path: '/' }]);
});

const KB_NAME = `S2 Bot ${uniqueId('KB')}`;

// Facts that exist nowhere but in this document.
const REFUND_DAYS = '47';
const POLICY_CODE = 'ZX-9137';

const DOC = `AuraBoot enterprise refund policy (internal).

Policy code ${POLICY_CODE}.

Enterprise customers may request a refund within ${REFUND_DAYS} days of the invoice date.
This window is deliberately longer than the standard consumer window.
Refunds are processed back to the original payment method.`;

// Never names the number, the code, or the file.
const QUESTION = 'How many days do enterprise customers have to request a refund?';

let kbPid: string;

test.describe('S2 — an uploaded document takes effect in AuraBot itself', () => {
  test.describe.configure({ mode: 'serial' });

  // Upload + embed + a streamed LLM turn. The 15s suite default is not a budget for that.
  test.setTimeout(180_000);

  test.skip(
    !process.env.DASHSCOPE_API_KEY,
    'needs DASHSCOPE_API_KEY — an assistant with no model behind it has nothing to answer with',
  );

  test('upload a document, then have AuraBot answer from it', async ({ page }) => {
    const created = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2 — does the assistant actually use it',
        embeddingProvider: 'qianwen',
        embeddingModel: 'text-embedding-v4',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    expect(created.ok()).toBeTruthy();
    kbPid = (await created.json()).data.pid;

    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
    await uploadTrigger.locator('input[type="file"]').setInputFiles({
      name: 'enterprise-refund-policy.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from(DOC, 'utf-8'),
    });

    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          return ((await resp.json()).data ?? [])[0]?.status ?? 'missing';
        },
        { timeout: 90_000, message: 'the policy document never finished processing' },
      )
      .toBe('completed');

    // ---- the part nobody had tested: ask the assistant ----

    const panel = await openAuraBotPanel(page);

    // Point the conversation at this knowledge base. The selector exists in the chat and had no
    // coverage either — an affordance nobody drives is an affordance nobody knows is broken.
    await panel.getByTestId('kb-selector-trigger').click();
    // By pid, not by name: the dropdown truncates long names, so matching on text would be
    // matching on however much of the name happened to fit.
    await panel.getByTestId(`kb-option-${kbPid}`).click();
    await expect(panel.getByTestId('kb-selected-chips')).toBeVisible();

    await panel.getByPlaceholder(/Type a message/i).fill(QUESTION);
    await panel.getByTestId('aurabot-send').click();

    // The question does not contain "47" and neither does anything else on this page. If the panel
    // ends up saying it, the assistant read it out of the document that was uploaded a moment ago.
    await expect(
      panel,
      'AuraBot answered without the knowledge base — the document was indexed but never reached the conversation',
    ).toContainText(REFUND_DAYS, { timeout: 120_000 });

    await page.screenshot({ path: 'test-results/s2-bot-01-answered.png', fullPage: true });
  });
});
