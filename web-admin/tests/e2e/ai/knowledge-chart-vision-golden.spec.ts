/**
 * S2/M3 — a chart uploaded to a knowledge base becomes findable by what it shows.
 *
 * The requirement was "charts", and a chart is not a document with small text in it: the fact lives
 * in the geometry. Nobody searches for "q3-review.png". They search for "why did East China drop in
 * Q3", and that only lands if what got indexed is a *reading* of the chart.
 *
 * So this uploads a bar chart with a known shape — a steep Q3 trough — and then asks for it in
 * natural language, never naming the file. Passing means the vision model read the bars, the
 * description was chunked and embedded, and retrieval found it.
 *
 * Skipped without DASHSCOPE_API_KEY: with no model behind it there is nothing to understand the
 * image, and a green run would mean nothing.
 */

import { test, expect } from '@playwright/test';
import { uniqueId } from '../helpers';

// App defaults to zh-CN (localStorage 'locale' / cookie); these KB specs assert the
// English UI. Force the en-US locale cookie so SSR renders English strings.
test.beforeEach(async ({ context }) => {
  await context.addCookies([{ name: 'locale', value: 'en-US', domain: '127.0.0.1', path: '/' }]);
});

const KB_NAME = `S2 Chart ${uniqueId('KB')}`;
const CHART_FILE = 'east-china-quarterly-revenue.png';

// Asked without a single word from the file name.
const SEMANTIC_QUERY = 'Which quarter did revenue collapse in?';

let kbPid: string;
let chartPng: Buffer;

test.describe('S2 knowledge ingestion — charts', () => {
  test.describe.configure({ mode: 'serial' });

  // A vision model reads the chart, and that takes seconds, not milliseconds — measured at 8-10s
  // end to end. The suite-wide default of 15s covers that only when nothing else is running; under
  // the parallel load of a full golden run the test is killed mid-ingest and reports the document
  // as stuck in 'processing' when it is merely still working.
  test.setTimeout(120_000);

  test.skip(
    !process.env.DASHSCOPE_API_KEY,
    'needs DASHSCOPE_API_KEY — nothing can understand the image without a vision model',
  );

  test.beforeAll(async ({ browser }) => {
    // Draw the chart in a real canvas so the bytes are a genuine PNG, not a fixture whose contents
    // we could have quietly got wrong.
    const page = await browser.newPage();
    const dataUrl = await page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 440;
      canvas.height = 280;
      const ctx = canvas.getContext('2d')!;
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, 440, 280);
      ctx.fillStyle = '#000000';
      ctx.font = '15px sans-serif';
      ctx.fillText('East China revenue by quarter (10k CNY)', 70, 26);

      const bars: [string, number][] = [
        ['Q1', 150],
        ['Q2', 140],
        ['Q3', 60],
        ['Q4', 70],
      ];
      let x = 50;
      for (const [label, value] of bars) {
        ctx.fillStyle = '#3b82f6';
        ctx.fillRect(x, 240 - value, 55, value);
        ctx.fillStyle = '#000000';
        ctx.fillText(label, x + 16, 258);
        ctx.fillText(String(value), x + 12, 232 - value);
        x += 95;
      }
      return canvas.toDataURL('image/png');
    });
    await page.close();

    chartPng = Buffer.from(dataUrl.split(',')[1], 'base64');
  });

  test('a chart is understood, not just stored', async ({ page }) => {
    const created = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2/M3 golden — chart understanding',
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

    // The picker has to offer images at all — the fourth doc_type sync point.
    const fileInput = page.locator('label input[type="file"]').first();
    expect(await fileInput.getAttribute('accept')).toContain('.png');

    await fileInput.setInputFiles({
      name: CHART_FILE,
      mimeType: 'image/png',
      buffer: chartPng,
    });

    let doc: any;
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          doc = ((await resp.json()).data ?? [])[0];
          return doc?.status ?? 'missing';
        },
        { timeout: 90000, message: 'the chart never finished processing' },
      )
      .toBe('completed');

    expect(doc.docType.toLowerCase()).toBe('image');
    expect(doc.chunkCount).toBeGreaterThan(0);
    await page.screenshot({ path: 'test-results/s2-chart-01-completed.png', fullPage: true });

    // What got indexed is the model's reading of the chart. The numbers are only on the bars —
    // nothing in the file name or the bytes says "150".
    await page.getByRole('button', { name: /Chunks/i }).first().click();
    await page.locator('select').first().selectOption(doc.pid);

    const toggles = page.locator('main button', { hasText: /Chunk #/ });
    await expect(toggles.first()).toBeVisible({ timeout: 15000 });
    const count = await toggles.count();
    for (let i = 0; i < count; i++) {
      await toggles.nth(i).click();
    }

    const main = page.locator('main');
    await expect(main).toContainText('150');
    await expect(main).toContainText('60');
    await page.screenshot({ path: 'test-results/s2-chart-02-chunks.png', fullPage: true });
  });

  test('the chart is findable by what it shows, without naming the file', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.getByRole('button', { name: /Retrieval Test/i }).first().click();

    await page.getByPlaceholder(/Ask a question/i).fill(SEMANTIC_QUERY);
    await page.getByRole('button', { name: /^Search$/i }).click();

    // The whole point of the milestone: a question about the business fact reaches a picture.
    const results = page.locator('main');
    await expect(results).toContainText(CHART_FILE, { timeout: 30000 });
    await expect(page.getByTestId('retrieval-path')).toHaveText(/hybrid/);
    await page.screenshot({ path: 'test-results/s2-chart-03-retrieved.png', fullPage: true });
  });
});
