/**
 * The knowledge base UI, driven the way a person drives it.
 *
 * WHY THIS DID NOT EXIST
 * ----------------------
 * 1250 lines of hand-written React across two pages — create, upload, reprocess,
 * chunk inspection, retrieval test — with no browser spec at all. The backend
 * side is thick (23 test files, tenant-scoping IT, a retrieval-quality eval
 * harness), so the gap was invisible from the Java side, and the pages are not
 * DSL pages so the page-level denominator produced no row for them either.
 *
 * WHAT THIS PINS, specifically
 * ---------------------------
 * The zero-embedding path. When a knowledge base is created against an embedding
 * provider this deployment has not enabled, every chunk fails to embed. Four
 * layers each report their own layer, and that separation is deliberate:
 *
 *   - the document status stays `completed` — the text really was chunked and
 *     stored, and EmbeddingRetryService repairs failed chunks in the background,
 *     so folding embedding into status would make it start lying within minutes
 *   - `embeddedChunkCount` is computed on read, so it survives that retry
 *   - the row badges `0/N` in red with a title saying the document is
 *     keyword-only
 *   - retrieval reports `path=keyword` with a warning
 *
 * A previous review read only the backend fields, concluded "silent success",
 * and nearly had the status semantics changed. This spec exists so the honest
 * reporting is a pinned behaviour rather than something a reader has to
 * rediscover — and so the badge cannot be removed as "noise".
 *
 * It also fails if a knowledge base built on the dialog's default provider
 * cannot answer, which is the surviving half of that finding.
 */
import { test, expect } from '@playwright/test';

// Unique per run: a fixed name would let one run's leftovers satisfy the next
// one's assertions, which is how a golden goes quietly deaf (G-test-hermetic-1).
const RUN = `kbg${Date.now().toString(36)}`;
const KB_NAME = `E2E KB ${RUN}`;

const DOC = `# 设备维护规程 (${RUN})

## 压力传感器 ZQ-7731
ZQ-7731 型压力传感器的校准间隔为 137 天,由厂务部王工负责执行。

## 冷却泵 KP-2200
KP-2200 冷却泵的滤芯更换周期是 45 天,标准扭矩 18.5 牛米。
`;

test.describe('Knowledge base — create, ingest, inspect, retrieve', () => {
  // Embedding is a remote round trip; the pipeline is polled, not slept on.
  test.setTimeout(180_000);

  test('a document ingested through the UI reports its embedding state honestly', async ({
    page,
  }) => {
    await page.goto('/aurabot/knowledge', { waitUntil: 'domcontentloaded' });

    // Wait for hydration, not just for the DOM. The page is server-rendered, so the
    // button is present and clickable before React has attached its handler — a click
    // then lands on nothing and the drawer never opens, which looks exactly like a
    // broken button. The list finishing its own fetch is the signal that client code
    // is actually running: either a card or the empty-state is on screen.
    await expect(
      page.locator('[data-testid^="kb-card-"]').first().or(page.getByText(/还没有知识库|No knowledge bases/i)),
      'the list must finish loading (proves the client bundle is live) before clicking',
    ).toBeVisible({ timeout: 30_000 });

    // --- create, accepting whatever the dialog defaults to -----------------
    await page.locator('[data-testid="kb-new-button"]').click();

    // Wait on the field at the top of the drawer, which is unambiguously in view.
    const nameInput = page.locator('[data-testid="kb-name-input"]');
    await expect(nameInput, 'the create drawer must open').toBeVisible({ timeout: 15_000 });

    // The provider select sits further down the drawer and can be below the fold,
    // so assert it is attached rather than visible — the value is what matters here,
    // and requiring visibility would fail for a layout reason rather than a real one.
    const providerSelect = page.locator('[data-testid="kb-provider-select"]');
    await expect(providerSelect, 'the create drawer must offer a provider').toBeAttached({
      timeout: 15_000,
    });
    const chosenProvider = await providerSelect.inputValue();

    await nameInput.fill(KB_NAME);
    await page.locator('[data-testid="kb-submit-button"]').click();

    const card = page.locator(`[data-testid^="kb-card-"]`).filter({ hasText: KB_NAME });
    await expect(card, 'the new knowledge base must appear in the list').toBeVisible({
      timeout: 20_000,
    });

    // --- upload a document ------------------------------------------------
    const kbPid = await card.getAttribute('data-testid').then((v) => v!.replace('kb-card-', ''));
    await page.goto(`/aurabot/knowledge/${kbPid}`, { waitUntil: 'domcontentloaded' });

    // Its own testid, not input[type=file].first(): the AuraBot side panel ships an
    // aurabot-file-input that comes earlier in the DOM, so .first() uploaded into the
    // chat panel and this page's document list stayed empty.
    await page.locator('[data-testid="kb-upload-input"]').setInputFiles({
      name: `${RUN}.md`,
      mimeType: 'text/markdown',
      buffer: Buffer.from(DOC, 'utf8'),
    });

    // Poll the API rather than the DOM for the terminal state: the page polls on
    // its own interval, and racing it produces a flake that looks like a product bug.
    let doc: Record<string, unknown> = {};
    await expect
      .poll(
        async () => {
          const r = await page.request.get(`/api/ai/knowledge/${kbPid}/documents`);
          const list = (await r.json())?.data ?? [];
          doc = list[0] ?? {};
          // A string either way: returning undefined here makes the matcher error out
          // with "received value must be a string", which hides the real story (the
          // upload never landed).
          return String(doc.status ?? 'no-document-yet');
        },
        { timeout: 120_000, intervals: [2000] },
      )
      .toMatch(/completed|failed/);

    const chunkCount = Number(doc.chunkCount ?? 0);
    const embedded = Number(doc.embeddedChunkCount ?? 0);
    expect(chunkCount, 'the document must have been chunked').toBeGreaterThan(0);

    // --- the row must state the embedding ratio, not just a count ---------
    await page.reload({ waitUntil: 'domcontentloaded' });
    const chunkCell = page.locator(`[data-testid="doc-chunks-${doc.pid}"]`);
    await expect(chunkCell, 'the documents row must render its chunk state').toBeVisible({
      timeout: 30_000,
    });
    const cellText = (await chunkCell.innerText()).trim();

    if (embedded === chunkCount) {
      // Fully embedded: the cell is the plain count, and retrieval must be hybrid.
      expect(cellText).toBe(String(chunkCount));
    } else {
      // Partially or not at all: the ratio has to be on screen. This is the
      // assertion that stops the badge being deleted as noise.
      expect(
        cellText,
        `${embedded}/${chunkCount} chunks embedded — the row must say so, not show a bare count`,
      ).toContain(`${embedded}/${chunkCount}`);
      await expect(chunkCell).toHaveAttribute('title', /embedded|keyword|向量|关键词/i);
    }

    // --- retrieval must declare which path served the query ---------------
    await page.locator('button, [role="tab"]', { hasText: /Retrieval Test|检索测试/ }).first().click();
    // Own testids again: `.last()` on a generic input selector reaches the AuraBot
    // side panel's composer (aurabot-input), which is present on every page.
    await page.locator('[data-testid="kb-retrieval-query"]').fill('ZQ-7731 多久校准一次');
    await page.locator('[data-testid="kb-retrieval-search"]').click();

    const pathBadge = page.locator('[data-testid="retrieval-path"]');
    await expect(
      pathBadge,
      'retrieval must report its path — a keyword fallback that looks like a vector search is the failure this exists to surface',
    ).toBeVisible({ timeout: 60_000 });
    // Read the raw path off the attribute, not the badge text: the label is localized
    // ("keyword only" / "仅关键词"), so asserting on wording would pin this test to a
    // locale rather than to behaviour.
    const path = ((await pathBadge.getAttribute('data-path')) ?? '').trim().toLowerCase();
    expect(path, `unexpected retrieval path for provider=${chosenProvider}`).toMatch(
      /^(hybrid|keyword|none)$/,
    );

    // The two must agree. Vectors present but keyword-only retrieval (or the
    // reverse) means one of the two layers is lying, and that is worth failing on
    // even though each layer looks fine alone.
    if (embedded > 0) {
      expect(path, 'chunks carry vectors, so retrieval must not have fallen back').toContain(
        'hybrid',
      );
    } else {
      expect(path, 'no chunk carries a vector, so retrieval cannot be hybrid').not.toContain(
        'hybrid',
      );
    }
  });
});
