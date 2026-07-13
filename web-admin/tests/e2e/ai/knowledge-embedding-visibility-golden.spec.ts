/**
 * A document can report "completed" while not one of its chunks was embedded.
 *
 * "Completed" means the text was chunked and stored. Embedding is a separate remote step, and it
 * can fail on every single chunk — leaving a knowledge base that looks perfect, shows green, and
 * answers nothing: retrieval silently drops to keyword matching. The row said "3 chunks" and told
 * the user nothing about the half of the pipeline that had died.
 *
 * So the row now says how many chunks actually carry a vector.
 *
 * This runs WITHOUT a key on purpose. No embedding provider is exactly the state being surfaced:
 * the document ingests, goes green, and is not semantically searchable. If a key were present the
 * failure would not occur and the test would prove nothing.
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

const KB_NAME = `S2 Embed ${uniqueId('KB')}`;

const DOC = `Support hours.

The support hotline is open from 09:00 to 18:00 on working days.
Out of hours, use the emergency escalation channel.`;

test.describe('S2 — a document that stored but did not embed says so', () => {
  test.setTimeout(90_000);

  test.skip(
    !!process.env.DASHSCOPE_API_KEY,
    'this test needs embedding to FAIL, which it cannot do with a working key — run it on a bare stack',
  );

  test('the row shows 0/N embedded, not a bare chunk count', async ({ page }) => {
    // No provider will be configured for this base — which is the point.
    const created = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2 — embedding failure must be visible',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 200,
        chunkOverlap: 20,
      },
    });
    expect(created.ok()).toBeTruthy();
    const kbPid = (await created.json()).data.pid;

    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
    await uploadTrigger.locator('input[type="file"]').setInputFiles({
      name: 'support-hours.txt',
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
        { timeout: 60_000 },
      )
      .toBe('completed');

    // The document is green. It has chunks. And none of them can be searched by meaning.
    expect(doc.chunkCount).toBeGreaterThan(0);
    expect(doc.embeddedChunkCount, 'nothing should have embedded without a provider').toBe(0);

    // And the UI says so, in the place the user is already looking.
    const cell = page.getByTestId(`doc-chunks-${doc.pid}`);
    await expect(cell).toBeVisible({ timeout: 15_000 });
    await expect(
      cell,
      'the row shows a bare chunk count and hides that the document cannot be found by meaning',
    ).toHaveText(/0\/\d+ embedded/);

    await page.screenshot({ path: 'test-results/s2-embed-01-not-embedded.png', fullPage: true });
  });
});
