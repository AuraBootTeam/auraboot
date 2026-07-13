/**
 * Conversation → FAQ loop — the pages and the menu the other two goldens never open.
 *
 * The coverage audit found three:
 *
 *  - **the sidebar was never clicked.** Both other specs navigate by URL. A reachable URL is not a
 *    reachable feature: a standalone page whose menu path says /p/{key} instead of /p/c/{key} is
 *    resolved as a model, gets _list appended, and 404s — while a page.goto() straight at the
 *    right path passes happily.
 *  - **faq_candidate_detail was never opened**, and its toolbar is a *second execution path* for
 *    the same four commands the workbench row actions use. They are different code paths
 *    (toolbar vs rowAction), and they have already diverged once: the platform validator rejected
 *    update_qa on this toolbar because an UPDATE command has nowhere to collect values from, so it
 *    navigates to the form instead of firing. Nothing tested that the substitution actually works.
 *  - **faq_candidate_list / faq_candidate_form were never opened.** Publishing a model auto-creates
 *    all three kinds; a stub that renders nothing still counts as a page.
 */
import { test, expect, type Page } from '../fixtures';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const SHOTS = process.env.FAQ_GOLDEN_SHOTS || 'test-results/faq-loop-golden';

/** This spec's own conversation. The review golden works its two drafts down to nothing. */
const OWN_CONV_PID = 'faqseedsupport0000000002';

function isDevServerNoise(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504|Loading chunk|Importing a module script failed/i.test(
    text,
  );
}

function isProductError(text: string): boolean {
  if (isDevServerNoise(text)) return false;
  return /exprError|尝试调用非函数值|Maximum update depth|Invalid hook call|is not a function|Internal system error|Application Error|TypeError|ReferenceError|Page not found/i.test(
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

/** Assert on the content region, not the body — a 404 shell still has a body. */
const main = (page: Page) => page.locator('main');

async function fetchCandidates(page: Page): Promise<Record<string, any>[]> {
  const res = await page.request.get('/api/dynamic/faq_candidate_list/list?pageNum=1&pageSize=50');
  expect(res.ok(), `candidate list API responds (${res.status()})`).toBe(true);
  return (await res.json())?.data?.records ?? [];
}

test.describe('Conversation → FAQ loop — menu reachability and the remaining pages', () => {
  test.setTimeout(120_000);

  test('P1 every FAQ menu entry opens its page from the sidebar', async ({ page }) => {
    const errors = captureConsole(page);
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    // The group has to expand before its children are clickable.
    await page.getByText('知识闭环', { exact: false }).first().click();

    for (const [label, expected] of [
      ['可提炼会话', /可提炼会话|Distillable/],
      ['FAQ 审核台', /FAQ 审核台|FAQ Review/],
      ['FAQ 候选', /FAQ 候选|FAQ Candidate/],
    ] as const) {
      await page.getByRole('link', { name: label }).click();
      // The page must actually render its own content — a menu path pointing at /p/{key} for a
      // standalone page resolves as a model, appends _list, and lands on "Page not found".
      await expect(main(page), `${label} renders its page`).toContainText(expected, {
        timeout: 20000,
      });
      await expect(main(page), `${label} is not a 404`).not.toContainText(/Page not found|页面不存在/);
    }

    await page.screenshot({ path: `${SHOTS}/P1-menu-reachability.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  /**
   * This spec's material, distilled the same way everything else is — from the queue, in the
   * browser. It does not reuse the review golden's candidates: that one works them down to
   * nothing, and a spec that quietly depends on another spec's leftovers is a spec that passes
   * for the wrong reason the day the order changes.
   */
  test('P0 distil this spec\'s own conversation from the queue', async ({ page }) => {
    await page.goto('/p/c/faq_conversation_queue', { waitUntil: 'domcontentloaded' });
    const row = page.locator(`[data-testid="table-row-${OWN_CONV_PID}"]`);
    await expect(row).toBeVisible({ timeout: 20000 });

    await row.getByTestId('row-action-extract').click();
    const dialog = page.getByTestId('form-dialog');
    await expect(dialog).toBeVisible({ timeout: 10000 });
    await dialog.getByTestId('form-dialog-field-faq_target_kb_id').selectOption(process.env.FAQ_TARGET_KB_PID!);
    await dialog.getByTestId('form-dialog-submit').click();

    await expect
      .poll(async () => (await fetchCandidates(page)).filter((c) => c.faq_source_conversation_pid === OWN_CONV_PID).length, {
        timeout: 90_000,
        message: 'the distiller produced candidates from a conversation that plainly has two',
      })
      .toBeGreaterThan(0);
  });

  test('P2 the candidate list page renders real rows, not an empty stub', async ({ page }) => {
    const errors = captureConsole(page);
    await page.goto('/p/faq_candidate', { waitUntil: 'domcontentloaded' });

    // autoCreateDefaultPages generates list/form/detail stubs silently; a stub that renders
    // nothing is still a page, and still counts as "the model has a list page".
    await expect(main(page)).toContainText(/退款|发票|Q/, { timeout: 20000 });
    const rows = page.locator('[data-testid^="table-row-"]');
    await expect.poll(() => rows.count(), { timeout: 15000 }).toBeGreaterThan(0);

    // The status column must render the dict label, not the raw code — a queue showing "draft"
    // instead of 待审核 is leaking the model at the user.
    await expect(main(page)).toContainText(/待审核|已批准|已发布|已驳回/);
    await expect(main(page), 'no raw status codes leak into the UI').not.toContainText(
      /\bdraft\b|\bapproved\b/,
    );

    await page.screenshot({ path: `${SHOTS}/P2-candidate-list.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('P3 the detail page shows the candidate, and its toolbar is the second command path', async ({
    page,
  }) => {
    const errors = captureConsole(page);

    const draft = (await (async () => {
      await page.goto('/p/faq_candidate', { waitUntil: 'domcontentloaded' });
      return fetchCandidates(page);
    })()).find((c) => c.faq_status === 'draft' && c.faq_source_conversation_pid === OWN_CONV_PID);
    expect(draft, 'a draft candidate to open').toBeTruthy();
    const pid = draft!.pid as string;

    await page.goto(`/p/faq_candidate/view/${pid}`, { waitUntil: 'domcontentloaded' });

    // Provenance and review fields must render — the detail page is the only place that shows the
    // full answer and where it came from.
    await expect(main(page)).toContainText(String(draft!.faq_question).slice(0, 8), {
      timeout: 20000,
    });
    await expect(main(page)).toContainText(OWN_CONV_PID);

    // Edit Q&A on this toolbar navigates to the form rather than firing the UPDATE command — an
    // UPDATE has nowhere to collect values from on a toolbar, and the platform validator rejects
    // the plugin outright if you try. Assert the substitution actually lands somewhere usable.
    await page.getByTestId('toolbar-btn-update_qa').click();
    await expect(page, 'Edit Q&A reaches the form page').toHaveURL(/\/p\/faq_candidate.*(edit|new)/i, {
      timeout: 15000,
    });
    await expect(main(page)).toContainText(/问题|Question/);

    await page.screenshot({ path: `${SHOTS}/P3-detail-to-form.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('P4 approving from the detail toolbar persists, exactly like the row action does', async ({
    page,
  }) => {
    const errors = captureConsole(page);

    await page.goto('/p/faq_candidate', { waitUntil: 'domcontentloaded' });
    const draft = (await fetchCandidates(page)).find(
      (c) => c.faq_status === 'draft' && c.faq_source_conversation_pid === OWN_CONV_PID,
    );
    expect(draft, 'a draft candidate to approve from the detail page').toBeTruthy();
    const pid = draft!.pid as string;

    await page.goto(`/p/faq_candidate/view/${pid}`, { waitUntil: 'domcontentloaded' });
    await expect(main(page)).toContainText(String(draft!.faq_question).slice(0, 8), {
      timeout: 20000,
    });

    // Same command, different execution path. The workbench row action was proven to work; that
    // says nothing about the toolbar, and the two have already diverged once on this very page.
    await page.getByTestId('toolbar-btn-approve').click();

    await expect
      .poll(async () => (await fetchCandidates(page)).find((c) => c.pid === pid)?.faq_status, {
        timeout: 20000,
        message: 'the detail-page toolbar approve persists, not just toasts',
      })
      .toBe('approved');

    const approved = (await fetchCandidates(page)).find((c) => c.pid === pid)!;
    expect(approved.faq_reviewed_by, 'the reviewer is stamped from this path too').toBeTruthy();

    await page.screenshot({ path: `${SHOTS}/P4-detail-approve.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });
});
