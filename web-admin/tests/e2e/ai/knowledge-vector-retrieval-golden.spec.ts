/**
 * Real embeddings, real vector retrieval — the path M1/M2 could not exercise.
 *
 * The earlier ingestion goldens ran on a stack with no embedding key. Chunks were stored, but every
 * one of them carried embedding_status=failed, and RagRetrievalService fell back to keyword search.
 * The tests passed and said so honestly, but the vector half of the pipeline was never touched.
 *
 * With a DashScope key provisioned, this closes that hole and asserts the two things that were
 * previously unprovable:
 *
 *   1. chunks are actually embedded (status completed, not failed);
 *   2. retrieval takes the hybrid/vector path, not the keyword fallback — read back from the
 *      rag.retrieval.duration timer, which is tagged with the path the service actually took.
 *
 * Skipped when DASHSCOPE_API_KEY is absent: without a key the stack genuinely cannot embed, and a
 * test that silently "passed" in that state would be lying.
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

const KB_NAME = `S2 Vector ${uniqueId('KB')}`;

const DOC = `Refund policy for enterprise customers.

Enterprise customers may request a refund within 30 days of the invoice date.
Refunds are processed back to the original payment method within two weeks.
Usage-based overages are not refundable once the billing period has closed.`;

// A query that shares almost no words with the document — keyword search would struggle, a vector
// search should not. This is the difference the embedding key buys.
const SEMANTIC_QUERY = 'How long do I have to ask for my money back?';


let kbPid: string;

test.describe('S2 — real embeddings and vector retrieval', () => {
  test.describe.configure({ mode: 'serial' });

  test.skip(
    !process.env.DASHSCOPE_API_KEY,
    'needs DASHSCOPE_API_KEY — without it the stack cannot embed, and a green run would be a lie',
  );

  test('a knowledge base on the DashScope provider embeds its chunks', async ({ page }) => {
    const created = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2 — vector retrieval with DashScope embeddings',
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
      name: 'refund-policy.txt',
      mimeType: 'text/plain',
      buffer: Buffer.from(DOC, 'utf-8'),
    });

    let doc: any;
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          doc = ((await resp.json()).data ?? [])[0];
          return doc?.status ?? 'missing';
        },
        { timeout: 60000 },
      )
      .toBe('completed');

    // The load-bearing assertion. Without a key this reads "failed" — the chunk exists but holds no
    // vector, and nothing downstream can search it semantically.
    await page.getByRole('button', { name: /Chunks/i }).first().click();
    await page.locator('select').first().selectOption(doc.pid);

    const chunkRow = page.locator('main button', { hasText: /Chunk #/ }).first();
    await expect(chunkRow).toBeVisible({ timeout: 15000 });
    await expect(chunkRow).toContainText('completed');
    await expect(chunkRow).not.toContainText('failed');

    await page.screenshot({ path: 'test-results/s2-vec-01-chunks-embedded.png', fullPage: true });
  });

  test('retrieval takes the vector path and answers a question the words do not appear in', async ({
    page,
  }) => {
    const resp = await page.request.post('/api/ai/knowledge/retrieve', {
      data: { query: SEMANTIC_QUERY, knowledgeBaseIds: [kbPid], topK: 5 },
    });
    expect(resp.ok()).toBeTruthy();
    const outcome = (await resp.json()).data;

    // Which path served the query. Without this the test proves nothing: keyword search would also
    // return this chunk (it is the only one in the base), so a green "content found" assertion is
    // perfectly compatible with the vector half being dead.
    expect(outcome.path, 'retrieval fell back to keyword search — the query was not embedded').toBe(
      'hybrid',
    );
    expect(outcome.warnings ?? []).toHaveLength(0);

    expect(
      JSON.stringify(outcome.results),
      'a semantic query must reach the refund window even though it shares no keywords with it',
    ).toContain('within 30 days of the invoice date');
  });

  test('the retrieval test UI reports which path served the query', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.getByRole('button', { name: /Retrieval Test/i }).first().click();

    await page.getByPlaceholder(/Ask a question/i).fill(SEMANTIC_QUERY);
    await page.getByRole('button', { name: /^Search$/i }).click();

    // A user has to be able to tell a real semantic search from a keyword search wearing its
    // clothes — otherwise a broken embedding provider just quietly makes the answers worse.
    const badge = page.getByTestId('retrieval-path');
    await expect(badge).toBeVisible({ timeout: 30000 });
    await expect(badge).toHaveText(/hybrid/);

    await page.screenshot({ path: 'test-results/s2-vec-02-retrieval-path.png', fullPage: true });
  });
});
