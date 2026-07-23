/**
 * S2/M1 — Knowledge ingestion golden: PPTX + XLSX.
 *
 * Drives the real UI for each new format: upload → status reaches completed → Chunks tab shows the
 * text that was inside the file → retrieval recalls that text.
 *
 * The load-bearing assertion is that retrieval returns **words from inside the deck**, not the file
 * name. A parser that returned nothing but "q3-review-deck.pptx" would still produce a document in
 * "completed" with chunks attached, and every status-only assertion would pass.
 *
 * Fixtures are real OOXML containers (built with the same POI version the backend parses with) and
 * live in tests/e2e/fixtures/knowledge-ingestion/.
 *
 * Note on the retrieval path: this stack has no embedding key, so RagRetrievalService falls back to
 * keyword/BM25 search (its documented degraded path). That still proves the slide text reached the
 * chunk index — which is what this milestone changed. The vector path is unchanged by S2/M1.
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

const PPTX = {
  file: 'q3-review-deck.pptx',
  mimeType: 'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  // Text that exists only *inside* the deck.
  slideText: 'Q3 East China revenue fell 12 percent',
  notesText: 'Channel conflict with the Hangzhou distributor',
  query: 'East China revenue',
};

const XLSX = {
  file: 'support-sla.xlsx',
  mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  cellText: 'Guaranteed response within 4 hours',
  query: 'Enterprise response time',
};

const KB_NAME = `S2 Ingestion ${uniqueId('KB')}`;

let kbPid: string;

/**
 * Open the Chunks tab for one document and expand every chunk. Chunk bodies are collapsed by
 * default and are not rendered at all until expanded, so asserting on the text without this would
 * time out even when the parse was perfect.
 */
async function openChunks(page: any, docPid: string) {
  await page.getByRole('button', { name: /Chunks/i }).first().click();
  await page.locator('select').first().selectOption(docPid);

  const chunkToggles = page.locator('main button', { hasText: /Chunk #/ });
  await expect(chunkToggles.first()).toBeVisible({ timeout: 15000 });

  const count = await chunkToggles.count();
  for (let i = 0; i < count; i++) {
    await chunkToggles.nth(i).click();
  }
}

async function uploadFixture(page: any, fixture: { file: string; mimeType: string }) {
  const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
  await expect(uploadTrigger).toBeVisible({ timeout: 10000 });

  const fileInput = uploadTrigger.locator('input[type="file"]');
  await fileInput.setInputFiles({
    name: fixture.file,
    mimeType: fixture.mimeType,
    buffer: readFileSync(join(FIXTURES, fixture.file)),
  });
}

/** Poll the documents API until this file leaves the non-terminal states. */
async function waitForDoc(page: any, fileName: string) {
  let doc: any;
  await expect
    .poll(
      async () => {
        const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
        const body = await resp.json().catch(() => ({}));
        doc = (body.data ?? []).find((d: any) => d.docName === fileName);
        return doc?.status ?? 'missing';
      },
      { timeout: 60000, message: `document ${fileName} never reached a terminal state` },
    )
    .toMatch(/completed|failed/);

  expect(doc.status, `parse failed: ${doc.errorMessage}`).toBe('completed');
  return doc;
}

test.describe('S2 knowledge ingestion — PPTX / XLSX', () => {
  test.describe.configure({ mode: 'serial' });

  test('upload dialog offers the new office formats', async ({ page }) => {
    const resp = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2/M1 golden — office document ingestion',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    expect(resp.ok()).toBeTruthy();
    kbPid = (await resp.json()).data.pid;

    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    const fileInput = page.locator('label input[type="file"]').first();
    const accept = await fileInput.getAttribute('accept');

    // The accept list is the fourth of the four doc_type sync points: a format the backend supports
    // but the picker filters out is unreachable for the user.
    expect(accept).toContain('.pptx');
    expect(accept).toContain('.xlsx');

    // Legacy binary .ppt / .xls are offered too, now that poi-scratchpad is a dependency. They are
    // what a lot of people actually have on disk, and refusing them was a consequence of a missing
    // jar, not a product decision.
    expect(accept).toContain('.ppt,');
    expect(accept).toContain('.xls,');

    // .doc is still absent, and that is deliberate: POI reads one but cannot create one, so no
    // fixture can be built and the parser cannot be tested. An untested binary parser would ingest
    // a document, report completed, and index whatever HWPF made of it.
    expect(accept).not.toContain('.doc,');
    expect(accept).not.toContain('.doc"');

    await page.screenshot({ path: 'test-results/s2-01-upload-accept.png', fullPage: true });
  });

  test('PPTX: upload → completed → chunks hold real slide text (incl. speaker notes)', async ({
    page,
  }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await uploadFixture(page, PPTX);

    const doc = await waitForDoc(page, PPTX.file);
    expect(doc.docType.toLowerCase()).toBe('pptx');
    expect(doc.chunkCount).toBeGreaterThan(0);
    expect(doc.charCount).toBeGreaterThan(0);

    await expect(page.getByText(PPTX.file)).toBeVisible({ timeout: 15000 });
    await expect(page.getByTestId(`doc-status-${doc.pid}`)).toHaveText(/completed/i, {
      timeout: 15000,
    });
    await page.screenshot({ path: 'test-results/s2-02-pptx-completed.png', fullPage: true });

    // Chunks tab — the words must have come out of the slide, not out of the file name.
    await openChunks(page, doc.pid);

    const chunkPanel = page.locator('main');
    await expect(chunkPanel.getByText(PPTX.slideText, { exact: false })).toBeVisible({
      timeout: 15000,
    });
    await expect(chunkPanel.getByText(PPTX.notesText, { exact: false })).toBeVisible();
    await page.screenshot({ path: 'test-results/s2-03-pptx-chunks.png', fullPage: true });
  });

  test('PPTX: retrieval recalls the slide text, not the file name', async ({ page }) => {
    const resp = await page.request.post('/api/ai/knowledge/retrieve', {
      data: { query: PPTX.query, knowledgeBaseIds: [kbPid], topK: 5 },
    });
    expect(resp.ok()).toBeTruthy();

    const outcome = (await resp.json()).data;
    const recalled = JSON.stringify(outcome);

    expect(
      recalled,
      'retrieval must return the sentence from inside the deck — a parser that only indexed the ' +
        'file name would still return a hit here',
    ).toContain(PPTX.slideText);
  });

  test('XLSX: upload → completed → chunks hold real cell text → retrieval recalls it', async ({
    page,
  }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await uploadFixture(page, XLSX);

    const doc = await waitForDoc(page, XLSX.file);
    expect(doc.docType.toLowerCase()).toBe('xlsx');
    expect(doc.chunkCount).toBeGreaterThan(0);

    await expect(page.getByTestId(`doc-status-${doc.pid}`)).toHaveText(/completed/i, {
      timeout: 15000,
    });
    await page.screenshot({ path: 'test-results/s2-04-xlsx-completed.png', fullPage: true });

    await openChunks(page, doc.pid);
    await expect(page.locator('main').getByText(XLSX.cellText, { exact: false })).toBeVisible({
      timeout: 15000,
    });
    await page.screenshot({ path: 'test-results/s2-05-xlsx-chunks.png', fullPage: true });

    const resp = await page.request.post('/api/ai/knowledge/retrieve', {
      data: { query: XLSX.query, knowledgeBaseIds: [kbPid], topK: 5 },
    });
    expect(JSON.stringify((await resp.json()).data)).toContain(XLSX.cellText);
  });

  test('a document that fails to parse can be reprocessed from the UI', async ({ page }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);

    // A .pptx that is not actually OOXML — the parser must reject it and say so.
    const uploadTrigger = page.locator('label', { hasText: /Upload Files|Uploading/i }).first();
    await uploadTrigger.locator('input[type="file"]').setInputFiles({
      name: 'corrupt-deck.pptx',
      mimeType: PPTX.mimeType,
      buffer: Buffer.from('this is not a pptx container', 'utf-8'),
    });

    let doc: any;
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          const body = await resp.json().catch(() => ({}));
          doc = (body.data ?? []).find((d: any) => d.docName === 'corrupt-deck.pptx');
          return doc?.status ?? 'missing';
        },
        { timeout: 60000 },
      )
      .toBe('failed');

    // The failure has to be visible and recoverable — before this milestone the only way out of a
    // failed parse was to delete the document and upload it again.
    await expect(page.getByTestId(`doc-error-${doc.pid}`)).toBeVisible({ timeout: 15000 });
    const reprocess = page.getByTestId(`doc-reprocess-${doc.pid}`);
    await expect(reprocess).toBeVisible();
    await page.screenshot({ path: 'test-results/s2-06-failed-with-reprocess.png', fullPage: true });

    await reprocess.click();

    // It fails again (the bytes are still corrupt) — what we are proving is that the button really
    // re-runs the pipeline: the attempt counter is reset and the row goes back through processing.
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          const body = await resp.json().catch(() => ({}));
          const row = (body.data ?? []).find((d: any) => d.pid === doc.pid);
          return row?.processStartedAt !== doc.processStartedAt;
        },
        { timeout: 30000, message: 'reprocess did not re-run the parse pipeline' },
      )
      .toBe(true);

    await page.screenshot({ path: 'test-results/s2-07-after-reprocess.png', fullPage: true });
  });
});
