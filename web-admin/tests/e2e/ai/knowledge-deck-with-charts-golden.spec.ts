/**
 * The scenario the requirement actually describes: someone uploads the quarterly deck.
 *
 * A quarterly review *is* charts. Its text frames hold a title and a footer; the fact — "East China
 * collapsed in Q3" — lives in a picture. Extracting only the text frames indexes the packaging and
 * throws away the contents, and the user never learns that: the document says "completed", the
 * chunks look plausible, and the one question they wanted to ask returns nothing.
 *
 * So this builds a deck whose fact exists ONLY inside an embedded chart image — the slide's text
 * says nothing about the numbers — and then asks for that fact in natural language, never naming the
 * file. Passing means the picture was pulled out of the deck, read by a vision model, and indexed.
 *
 * Skipped without DASHSCOPE_API_KEY: with no vision model there is nothing to read the chart, and a
 * green run would mean nothing.
 */

import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { uniqueId } from '../helpers';

// App defaults to zh-CN (localStorage 'locale' / cookie); these KB specs assert the
// English UI. Force the en-US locale cookie so SSR renders English strings.
test.beforeEach(async ({ context }) => {
  await context.addCookies([{ name: 'locale', value: 'en-US', domain: '127.0.0.1', path: '/' }]);
});

const FIXTURES = join(
  dirname(fileURLToPath(import.meta.url)),
  '..',
  'fixtures',
  'knowledge-ingestion',
);

const DECK = 'q3-review-with-charts.pptx';
const KB_NAME = `S2 Deck ${uniqueId('KB')}`;

// Nothing in the deck's *text* says this. It is drawn on the chart, and nowhere else.
const SEMANTIC_QUERY = 'Which region collapsed in Q3, and by how much?';

let kbPid: string;

test.describe('S2 — a deck whose charts are pictures', () => {
  test.describe.configure({ mode: 'serial' });

  // A vision call per picture, several seconds each — the 15s suite default is not a budget for that.
  test.setTimeout(180_000);

  test.skip(
    !process.env.DASHSCOPE_API_KEY,
    'needs DASHSCOPE_API_KEY — nothing can read the chart inside the deck without a vision model',
  );

  test('a chart embedded in a slide is read, not dropped', async ({ page }) => {
    const created = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2 — deck with embedded charts',
        embeddingProvider: 'qianwen',
        embeddingModel: 'text-embedding-v4',
        chunkSize: 500,
        chunkOverlap: 50,
      },
    });
    expect(created.ok()).toBeTruthy();
    kbPid = (await created.json()).data.pid;

    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
    await uploadTrigger.locator('input[type="file"]').setInputFiles({
      name: DECK,
      mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
      buffer: readFileSync(join(FIXTURES, DECK)),
    });

    let doc: any;
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          doc = ((await resp.json()).data ?? [])[0];
          return doc?.status ?? 'missing';
        },
        { timeout: 150000, message: 'the deck never finished processing' },
      )
      .toBe('completed');

    expect(doc.docType.toLowerCase()).toBe('pptx');

    // The chunks must carry what the picture showed — numbers that appear nowhere in the deck's text.
    await page.getByRole('button', { name: /Chunks/i }).first().click();
    await page.locator('select').first().selectOption(doc.pid);

    const toggles = page.locator('main button', { hasText: /Chunk #/ });
    await expect(toggles.first()).toBeVisible({ timeout: 20000 });
    const count = await toggles.count();
    for (let i = 0; i < count; i++) {
      await toggles.nth(i).click();
    }

    const main = page.locator('main');
    // The slide's own text never mentions a number. These come off the chart.
    await expect(main).toContainText('150');
    await expect(main).toContainText('60');
    // And the description is anchored to where it came from, so a reader knows which slide.
    await expect(main).toContainText('Slide 2');

    await page.screenshot({ path: 'test-results/s2-deck-01-chart-read.png', fullPage: true });
  });

  test('the deck answers a question that only its chart can answer', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.getByRole('button', { name: /Retrieval Test/i }).first().click();

    await page.getByPlaceholder(/Ask a question/i).fill(SEMANTIC_QUERY);
    await page.getByRole('button', { name: /^Search$/i }).click();

    // The whole point: the deck comes back for a question its words cannot answer.
    const results = page.locator('main');
    await expect(results).toContainText(DECK, { timeout: 30000 });
    await expect(page.getByTestId('retrieval-path')).toHaveText(/hybrid/);

    await page.screenshot({ path: 'test-results/s2-deck-02-retrieved.png', fullPage: true });
  });
});
