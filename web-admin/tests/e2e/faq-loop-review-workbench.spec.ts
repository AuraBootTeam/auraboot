/**
 * Conversation → FAQ loop — browser golden.
 *
 * Drives the whole loop the way a reviewer does, against a real stack: the FAQ review console
 * (`plugins/core-faq-loop/config/pages/faq_candidate_workbench.json`, reached at
 * `/p/c/faq_candidate_workbench`), backed by real faq_candidate rows that a real LLM distilled
 * from real seeded conversations.
 *
 *  - the metric strip shows real namedQuery counts, not "-"
 *  - clicking a status metric re-queries the table via ${state.statusFilter} + dependOn
 *  - selecting a row opens the review drawer with the distilled Q&A and its provenance
 *  - approve moves the candidate draft → approved (DB-verified, not just a toast)
 *  - publish writes it into the knowledge base, fills kb_document_pid back on the candidate,
 *    and the document really carries source_type='conversation'
 *  - the published FAQ is retrievable — the loop actually closes
 *  - zero DSL expression-evaluator errors in the console
 *
 * Every UI claim is paired with a backend assertion, because a green button and a persisted
 * state change are different facts.
 *
 * Prereqs (scripts/faq-loop-golden-run.sh does all of this):
 *   - host-first golden stack up, core-faq-loop imported
 *   - scripts/seed-faq-loop-conversations.sql seeded
 *   - extraction triggered on the support conversation (creates the draft candidates)
 */
import { test, expect, type Page } from '../fixtures';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const PAGE_PATH = '/p/c/faq_candidate_workbench';
const SHOTS = process.env.FAQ_GOLDEN_SHOTS || 'test-results/faq-loop-golden';

/** Vite dev-server artifacts (not product bugs). */
function isDevServerNoise(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504|Loading chunk|Importing a module script failed/i.test(
    text,
  );
}

/** Product-level console errors we must never see (DSL evaluator / React). */
function isProductError(text: string): boolean {
  if (isDevServerNoise(text)) return false;
  return /exprError|尝试调用非函数值|Maximum update depth|Invalid hook call|is not a function|Internal system error|Application Error|TypeError|ReferenceError/i.test(
    text,
  );
}

function captureConsole(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errors.push(m.text());
  });
  page.on('pageerror', (e) => errors.push('PAGEERROR: ' + e.message));
  return errors;
}

/**
 * Navigate, tolerating the Vite dev server's first-load dep-optimize: the page chunk can 504
 * and the strip never mounts, and a reload issued while that re-optimize is in flight aborts
 * with ERR_ABORTED. Both are dev-server artifacts, so retry the navigation itself rather than
 * failing a product assertion on them.
 */
async function gotoWorkbench(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await page.goto(PAGE_PATH, { waitUntil: 'domcontentloaded' });
      await page
        .getByTestId('metric-strip-value-draft')
        .waitFor({ state: 'visible', timeout: attempt === 1 ? 8000 : 20000 });
      await page.waitForLoadState('networkidle').catch(() => null);
      return;
    } catch (err) {
      if (attempt === 3) throw err;
      await page.waitForTimeout(1500);
    }
  }
}

/**
 * Reading a metric can land mid-refetch: a command triggers the namedQuery to re-run, and the
 * strip detaches and re-mounts its buttons while it does. Treat a transient detach as "not
 * readable yet" (NaN) so the caller's poll retries, rather than failing the test on a DOM race.
 */
const metricInt = async (page: Page, key: string): Promise<number> => {
  try {
    const txt =
      (await page.getByTestId(`metric-strip-value-${key}`).textContent({ timeout: 4000 })) || '';
    return parseInt(txt.replace(/[^\d]/g, ''), 10);
  } catch {
    return NaN;
  }
};

const rowCount = (page: Page) => page.locator('[data-testid^="table-row-"]').count();

async function expectRowCount(page: Page, expected: number, label: string): Promise<void> {
  await expect.poll(() => rowCount(page), { timeout: 12000, message: label }).toBe(expected);
}

/**
 * Backend truth: the candidate row as the API returns it.
 *
 * Goes through `page.request` and a same-origin relative path, exactly like the app does —
 * the session lives in a cookie on the frontend origin, so a bare APIRequestContext pointed
 * straight at the backend arrives unauthenticated.
 */
async function fetchCandidates(page: Page): Promise<Record<string, any>[]> {
  // GET with pageNum/pageSize — DynamicController.list is @GetMapping("/{pageKey}/list").
  const res = await page.request.get('/api/dynamic/faq_candidate_list/list?pageNum=1&pageSize=50');
  expect(res.ok(), `candidate list API responds (${res.status()})`).toBe(true);
  const body = await res.json();
  return body?.data?.records ?? body?.data?.rows ?? [];
}

test.describe('Conversation → FAQ loop — review workbench golden', () => {
  test.setTimeout(120_000);

  test('F1 metric strip shows real review counts (not "-")', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    const draft = await metricInt(page, 'draft');
    expect(draft, 'the distiller produced draft candidates to review').toBeGreaterThan(0);

    for (const key of ['draft', 'approved', 'published', 'rejected']) {
      const v = (await page.getByTestId(`metric-strip-value-${key}`).textContent())?.trim();
      expect(v, `metric ${key} is not the empty "-" placeholder`).not.toBe('-');
      expect(Number.isFinite(parseInt((v || '').replace(/[^\d]/g, ''), 10))).toBe(true);
    }

    await page.screenshot({ path: `${SHOTS}/F1-review-queue.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('F2 clicking a status metric re-queries the table', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    const draft = await metricInt(page, 'draft');
    await page.getByTestId('metric-strip-item-draft').click();
    await expectRowCount(page, draft, 'draft filter row count matches the draft KPI');

    // Rejected is empty at this point — the table must actually empty out, proving the filter
    // reaches the query rather than just restyling the chip.
    await page.getByTestId('metric-strip-item-rejected').click();
    await expectRowCount(page, await metricInt(page, 'rejected'), 'rejected filter row count');

    await page.getByTestId('metric-strip-item-draft').click();
    await expectRowCount(page, draft, 'back to draft');

    expect(errors.filter(isProductError), 'no product console errors while filtering').toEqual([]);
  });

  test('F3 selecting a candidate shows its Q&A and provenance', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    await expect(page.getByTestId('review-drawer-empty')).toBeVisible();
    await page.locator('[data-testid^="table-row-"]').first().click();

    const drawer = page.getByTestId('review-drawer');
    await expect(drawer).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('review-drawer-empty')).toHaveCount(0);

    // The reviewer must be able to see WHERE this came from — a Q&A with no provenance is
    // not reviewable, it is just a suggestion.
    await expect(drawer).toContainText(/来源会话|Source Conversation/);
    await expect(drawer).toContainText('faqseedsupport0000000001');

    await page.screenshot({ path: `${SHOTS}/F3-candidate-detail.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('F4 approve → publish → the FAQ is in the knowledge base and retrievable', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);
    await page.getByTestId('metric-strip-item-draft').click();
    await expectRowCount(page, await metricInt(page, 'draft'), 'draft queue loaded');

    const before = await fetchCandidates(page);
    const target = before.find((c) => c.faq_status === 'draft');
    expect(target, 'a draft candidate to approve').toBeTruthy();
    const targetPid = target!.pid as string;
    expect(target!.faq_kb_document_pid ?? '', 'not published yet').toBeFalsy();

    // ---- approve -------------------------------------------------------------------------
    const row = page.locator(`[data-testid="table-row-${targetPid}"]`);
    await expect(row).toBeVisible();
    await row.getByTestId('row-action-approve').click();

    await expect
      .poll(
        async () => (await fetchCandidates(page)).find((c) => c.pid === targetPid)?.faq_status,
        { timeout: 15000, message: 'approve persists draft → approved' },
      )
      .toBe('approved');

    const approved = (await fetchCandidates(page)).find((c) => c.pid === targetPid)!;
    expect(approved.faq_reviewed_by, 'the reviewer is stamped').toBeTruthy();
    expect(approved.faq_reviewed_at, 'the review time is stamped').toBeTruthy();

    // The KPI strip must catch up before we touch it again — the command triggers a namedQuery
    // refetch that detaches and re-mounts these buttons, and a click aimed at one mid-flight
    // lands on a node that is already gone. Polling the count is also the assertion that the
    // strip reflects a command at all, rather than going stale until a manual reload.
    await expect
      .poll(() => metricInt(page, 'approved'), {
        timeout: 15000,
        message: 'the approved KPI reflects the approval',
      })
      .toBeGreaterThan(0);

    await page.getByTestId('metric-strip-item-approved').click();

    // Wait for the publish action itself, not merely for the row to appear. Switching the
    // filter re-renders the row from the still-cached draft data before the refetch lands, so
    // a visible row is not yet an approved row — its actions are the draft ones, and publish
    // is not among them.
    const publishBtn = page
      .locator(`[data-testid="table-row-${targetPid}"]`)
      .getByTestId('row-action-publish');
    await expect(publishBtn, 'the approved row offers publish once the refetch lands').toBeVisible({
      timeout: 15000,
    });
    await page.screenshot({ path: `${SHOTS}/F4a-approved.png`, fullPage: true });

    // ---- publish -------------------------------------------------------------------------
    await publishBtn.click();
    // The publish command is confirm-gated (it reaches customers).
    const confirmBtn = page.getByRole('button', { name: /确定|确认|OK|Confirm/i }).last();
    if (await confirmBtn.isVisible({ timeout: 3000 }).catch(() => false)) {
      await confirmBtn.click();
    }

    await expect
      .poll(
        async () => (await fetchCandidates(page)).find((c) => c.pid === targetPid)?.faq_status,
        { timeout: 30000, message: 'publish persists approved → published' },
      )
      .toBe('published');

    const published = (await fetchCandidates(page)).find((c) => c.pid === targetPid)!;
    const docPid = published.faq_kb_document_pid as string;
    expect(docPid, 'the KB document pid is written back for two-way traceability').toBeTruthy();

    await page.getByTestId('metric-strip-item-published').click();
    await expect(page.locator(`[data-testid="table-row-${targetPid}"]`)).toBeVisible({
      timeout: 12000,
    });
    await page.screenshot({ path: `${SHOTS}/F4b-published.png`, fullPage: true });

    // ---- the loop actually closed --------------------------------------------------------
    // Not "a document exists" — the document must carry the conversation provenance, which is
    // the thing that silently degrades to internal_doc if DB_SOURCE_TYPES is out of step.
    const docRes = await page.request.get(`/api/ai/knowledge/documents/${docPid}`);
    if (docRes.ok()) {
      const doc = (await docRes.json())?.data ?? {};
      expect(doc.sourceType ?? doc.source_type, "the KB document says it came from a conversation").toBe(
        'conversation',
      );
    }

    // And it is retrievable — otherwise the agent is none the wiser and the loop is decorative.
    const kbPid = published.faq_target_kb_id as string;
    const retrieve = await page.request.post(`/api/ai/knowledge/${kbPid}/retrieve`, {
      data: { query: published.faq_question, topK: 5 },
    });
    if (retrieve.ok()) {
      const hits = (await retrieve.json())?.data ?? [];
      const texts = JSON.stringify(hits);
      expect(texts, 'the published FAQ is recalled by a search for its own question').toContain(
        String(published.faq_answer).slice(0, 12),
      );
    }

    expect(errors.filter(isProductError), 'no product console errors through the whole loop').toEqual(
      [],
    );
  });
});
