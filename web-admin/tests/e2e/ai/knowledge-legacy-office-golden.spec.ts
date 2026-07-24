/**
 * The files people actually have on disk.
 *
 * .ppt and .xls predate OOXML by a decade and are still everywhere. They used to be refused at
 * upload — not because anyone decided they should be, but because poi-scratchpad (which carries
 * HSLF and HSSF) was not a dependency. That is a missing jar masquerading as a product decision.
 *
 * The fixtures are genuine binary Office 97 containers, not .pptx files with the wrong extension.
 *
 * Needs no key: these are text formats, and the assertions are about text.
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

const KB_NAME = `S2 Legacy ${uniqueId('KB')}`;

const PPT = {
  file: 'legacy-deck.ppt',
  mimeType: 'application/vnd.ms-powerpoint',
  docType: 'ppt',
  text: 'Warehouse relocation completed in Chengdu',
};

const XLS = {
  file: 'legacy-sla.xls',
  mimeType: 'application/vnd.ms-excel',
  docType: 'xls',
  text: 'Response within 90 minutes',
};

let kbPid: string;

test.describe('S2 — legacy .ppt and .xls', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(90_000);

  test.beforeAll(async ({ request }) => {
    const created = await request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2 — legacy binary Office formats',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    expect(created.ok()).toBeTruthy();
    kbPid = (await created.json()).data.pid;
  });

  for (const fixture of [PPT, XLS]) {
    test(`${fixture.docType.toUpperCase()}: uploads, parses, and its text is searchable`, async ({
      page,
    }) => {
      await page.goto(`/aurabot/knowledge/${kbPid}`);
      await page.waitForLoadState('domcontentloaded');

      const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
      await uploadTrigger.locator('input[type="file"]').setInputFiles({
        name: fixture.file,
        mimeType: fixture.mimeType,
        buffer: readFileSync(join(FIXTURES, fixture.file)),
      });

      let doc: any;
      await expect
        .poll(
          async () => {
            const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
            doc = ((await resp.json()).data ?? []).find((d: any) => d.docName === fixture.file);
            return doc?.status ?? 'missing';
          },
          { timeout: 60_000, message: `${fixture.file} never finished processing` },
        )
        .toBe('completed');

      expect(doc.docType.toLowerCase()).toBe(fixture.docType);
      expect(doc.chunkCount).toBeGreaterThan(0);

      // The text has to have come out of the binary container — the file name says none of this.
      const resp = await page.request.post('/api/ai/knowledge/retrieve', {
        data: { query: fixture.text, knowledgeBaseIds: [kbPid], topK: 5 },
      });
      expect(JSON.stringify((await resp.json()).data)).toContain(fixture.text);

      await page.screenshot({
        path: `test-results/s2-legacy-${fixture.docType}.png`,
        fullPage: true,
      });
    });
  }
});
