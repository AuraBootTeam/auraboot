/**
 * S2/M2 — Knowledge ingestion golden: add a page by URL.
 *
 * The backend really fetches over HTTP here. The spec stands up a throwaway HTTP server on loopback
 * and pastes its URL into the UI, so the whole seam runs for real: SSRF validation → pinned fetch →
 * Jsoup content extraction → chunk + index → retrieval.
 *
 * Why the loopback server needs an allowlist: SsrfValidator rejects loopback outright — that is the
 * point of it. The golden stack is therefore started with
 * `AURA_SSRF_ALLOWED_PRIVATE_HOSTS=127.0.0.1`, which is the operator-facing escape hatch that exists
 * for exactly this. The refusal test below deliberately uses 169.254.169.254, which is *not* on that
 * allowlist, so the negative case stays honest: it proves the guard is live in the same process that
 * just accepted the loopback fetch.
 */

import { test, expect } from '@playwright/test';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { uniqueId } from '../helpers';

/** The page the backend will fetch. Chrome (nav/footer) is here so we can prove it gets dropped. */
const PAGE_HTML = `<!doctype html>
<html>
  <head><title>Acme Support — Refund Policy</title></head>
  <body>
    <nav><a href="/">Home</a> <a href="/pricing">Pricing</a> <a href="/careers">Careers</a></nav>
    <header>Acme Corporation</header>
    <main>
      <h1>Refund Policy</h1>
      <p>Enterprise customers may request a refund within 30 days of the invoice date.</p>
      <p>Refunds are processed back to the original payment method within two weeks.</p>
    </main>
    <aside>Sign up for our newsletter and never miss an update</aside>
    <footer>Copyright 2026 Acme Corporation. All rights reserved.</footer>
  </body>
</html>`;

const CONTENT_SENTENCE = 'Enterprise customers may request a refund within 30 days';
const CHROME_SENTENCE = 'All rights reserved';

// 169.254.169.254 is the cloud instance-metadata address — the canonical SSRF target. On AWS/GCP/
// Azure it hands out instance credentials to anything that can reach it.
const CLOUD_METADATA_URL = 'http://169.254.169.254/latest/meta-data/';

const KB_NAME = `S2 URL ${uniqueId('KB')}`;

let server: Server;
let pageUrl: string;
let kbPid: string;

test.describe('S2 knowledge ingestion — URL', () => {
  test.describe.configure({ mode: 'serial' });

  test.beforeAll(async () => {
    server = createServer((req, res) => {
      if (req.url?.startsWith('/refund-policy')) {
        res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
        res.end(PAGE_HTML);
        return;
      }
      res.writeHead(404);
      res.end('not found');
    });

    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    pageUrl = `http://127.0.0.1:${port}/refund-policy`;
  });

  test.afterAll(async () => {
    await new Promise<void>((resolve) => server.close(() => resolve()));
  });

  test('a pasted URL is fetched, stripped of chrome, and indexed', async ({ page }) => {
    const created = await page.request.post('/api/ai/knowledge', {
      data: {
        name: KB_NAME,
        description: 'S2/M2 golden — URL ingestion',
        embeddingProvider: 'openai',
        embeddingModel: 'text-embedding-3-small',
        chunkSize: 300,
        chunkOverlap: 30,
      },
    });
    expect(created.ok()).toBeTruthy();
    kbPid = (await created.json()).data.pid;

    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    await page.getByTestId('kb-url-input').fill(pageUrl);
    await page.getByTestId('kb-url-add-button').click();

    // The document lands already parsed — this path ingests synchronously rather than handing off
    // to the async file pipeline.
    let doc: any;
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          const body = await resp.json().catch(() => ({}));
          doc = (body.data ?? [])[0];
          return doc?.status ?? 'missing';
        },
        { timeout: 30000, message: 'the fetched page never became a document' },
      )
      .toBe('completed');

    // The page's <title>, not the raw URL — that is what makes the row readable in the list.
    expect(doc.docName).toBe('Acme Support — Refund Policy');
    expect(doc.chunkCount).toBeGreaterThan(0);

    // Scope to the table: the same title is also in the success toast.
    await expect(
      page.locator('table').getByText('Acme Support — Refund Policy'),
    ).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: 'test-results/s2-url-01-added.png', fullPage: true });

    // Chunks tab: the article text is there, the site chrome is not.
    await page.getByRole('button', { name: /Chunks/i }).first().click();
    await page.locator('select').first().selectOption(doc.pid);

    const toggles = page.locator('main button', { hasText: /Chunk #/ });
    await expect(toggles.first()).toBeVisible({ timeout: 15000 });
    const count = await toggles.count();
    for (let i = 0; i < count; i++) {
      await toggles.nth(i).click();
    }

    const main = page.locator('main');
    await expect(main.getByText(CONTENT_SENTENCE, { exact: false })).toBeVisible({ timeout: 15000 });
    await expect(main.getByText(CHROME_SENTENCE, { exact: false })).toHaveCount(0);
    await expect(main.getByText('Careers', { exact: false })).toHaveCount(0);
    await page.screenshot({ path: 'test-results/s2-url-02-chunks.png', fullPage: true });
  });

  test('retrieval recalls the page content', async ({ page }) => {
    const resp = await page.request.post('/api/ai/knowledge/retrieve', {
      data: { query: 'refund within 30 days', knowledgeBaseIds: [kbPid], topK: 5 },
    });
    expect(resp.ok()).toBeTruthy();

    const recalled = JSON.stringify((await resp.json()).data);
    expect(recalled, 'the fetched page must be recallable, not just stored').toContain(
      CONTENT_SENTENCE,
    );
  });

  test('re-adding the same URL refreshes it instead of duplicating it', async ({ page }) => {
    const before = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
    const countBefore = ((await before.json()).data ?? []).length;

    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.getByTestId('kb-url-input').fill(pageUrl);
    await page.getByTestId('kb-url-add-button').click();

    await expect
      .poll(
        async () => {
          const resp = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          return ((await resp.json()).data ?? []).length;
        },
        { timeout: 30000 },
      )
      .toBe(countBefore);
  });

  test('the cloud metadata address is refused, with the reason shown to the user', async ({
    page,
  }) => {
    await page.goto(`/aurabot/knowledge/${kbPid}`);
    await page.waitForLoadState('domcontentloaded');

    const docsBefore = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
    const countBefore = ((await docsBefore.json()).data ?? []).length;

    await page.getByTestId('kb-url-input').fill(CLOUD_METADATA_URL);
    await page.getByTestId('kb-url-add-button').click();

    // The user has to be told *why*, or they cannot tell a blocked target from a broken server.
    const toast = page.getByText(/link-local|not allowed|private|refus/i).first();
    await expect(toast).toBeVisible({ timeout: 15000 });
    await page.screenshot({ path: 'test-results/s2-url-03-ssrf-refused.png', fullPage: true });

    // And nothing was ingested.
    const docsAfter = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
    expect(((await docsAfter.json()).data ?? []).length).toBe(countBefore);
  });

  test('a private network address is refused too', async ({ page }) => {
    const resp = await page.request.post(`/api/ai/knowledge/${kbPid}/documents/from-url`, {
      data: { url: 'http://10.0.0.1/internal-wiki' },
    });

    // The API signals refusal with a non-zero code and a null payload (there is no `success` field
    // on the wire — the http-client derives that for callers).
    const body = await resp.json();
    expect(body.data, `10.0.0.1 must be refused, got: ${JSON.stringify(body)}`).toBeNull();
    expect(body.code).not.toBe('0');
    expect(String(body.message)).toMatch(/private/i);
  });
});
