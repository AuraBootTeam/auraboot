/**
 * Conversation → FAQ loop, M2 — the entry point and the provenance, in a real browser.
 *
 * M1 shipped the manual trigger as an API endpoint with no button behind it: the only way to
 * start the loop was to already know a conversation's pid. This drives what a reviewer actually
 * does — open the queue, look at what a conversation says, distil it, and then check in the
 * review console that the pairs came from where they claim to.
 *
 *  - the queue lists conversations with how many candidates each has already produced
 *  - selecting one shows its transcript (no block reads ab_im_message on its own — this goes
 *    through a pid-keyed read endpoint and a plain table)
 *  - "Distil FAQ" runs the real LLM through the command pipeline and the queue's count moves
 *  - the chit-chat conversation yields nothing — the anti-fabrication gate, driven from the UI
 *  - back in the review console, selecting a candidate shows the conversation it came from
 *
 * Prereqs: scripts/faq-loop-golden-run.sh brings the stack up, imports core-faq-loop and seeds
 * the two conversations. It does NOT pre-distil for this spec — distilling is what we are testing.
 */
import { test, expect, type Page } from '../fixtures';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const QUEUE = '/p/c/faq_conversation_queue';
const WORKBENCH = '/p/c/faq_candidate_workbench';
const SHOTS = process.env.FAQ_GOLDEN_SHOTS || 'test-results/faq-loop-golden';

const SUPPORT_PID = 'faqseedsupport0000000001';
const CHITCHAT_PID = 'faqseedchitchat0000000001';

function isDevServerNoise(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504|Loading chunk|Importing a module script failed/i.test(
    text,
  );
}

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

/** Retry the navigation itself: a first-load Vite re-optimize can 504 or abort a reload. */
async function goto(page: Page, path: string, readyTestId: string): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await page.goto(path, { waitUntil: 'domcontentloaded' });
      await page
        .locator(`[data-testid^="${readyTestId}"]`)
        .first()
        .waitFor({ state: 'visible', timeout: attempt === 1 ? 10000 : 20000 });
      await page.waitForLoadState('networkidle').catch(() => null);
      return;
    } catch (err) {
      if (attempt === 3) throw err;
      await page.waitForTimeout(1500);
    }
  }
}

/** The conversation queue row, keyed by conversation pid. */
const convRow = (page: Page, pid: string) => page.locator(`[data-testid="table-row-${pid}"]`);

async function candidateCount(page: Page): Promise<number> {
  const res = await page.request.get('/api/faq/conversations?pageNum=1&pageSize=50');
  expect(res.ok(), `conversation queue API responds (${res.status()})`).toBe(true);
  const rows = (await res.json())?.data?.records ?? [];
  const support = rows.find((r: any) => r.pid === SUPPORT_PID);
  return support?.candidateCount ?? -1;
}

test.describe('Conversation → FAQ loop — queue, distil, provenance', () => {
  test.setTimeout(180_000);

  test('M2-1 the queue lists conversations and shows what has been mined', async ({ page }) => {
    const errors = captureConsole(page);
    await goto(page, QUEUE, 'table-row-');

    await expect(convRow(page, SUPPORT_PID)).toBeVisible();
    await expect(convRow(page, CHITCHAT_PID)).toBeVisible();

    // The row must carry the conversation's name, not a raw pid — a queue of opaque ids is
    // not something a human can pick from.
    await expect(convRow(page, SUPPORT_PID)).toContainText(/退款|发票/);

    await page.screenshot({ path: `${SHOTS}/M2-1-conversation-queue.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('M2-2 selecting a conversation shows its transcript', async ({ page }) => {
    const errors = captureConsole(page);
    await goto(page, QUEUE, 'table-row-');

    await convRow(page, SUPPORT_PID).click();

    // The transcript is what makes the queue reviewable: you decide whether a conversation is
    // worth distilling by reading it, not by its title.
    const transcript = page.getByTestId('table-block').last();
    await expect(transcript).toContainText('3-5 个工作日', { timeout: 15000 });
    await expect(transcript).toContainText('Support');
    await expect(transcript).toContainText('Customer');

    await page.screenshot({ path: `${SHOTS}/M2-2-transcript.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('M2-3 "Distil FAQ" runs the real LLM and the queue reflects it', async ({ page }) => {
    const errors = captureConsole(page);
    await goto(page, QUEUE, 'table-row-');

    expect(await candidateCount(page), 'nothing distilled yet').toBe(0);

    await convRow(page, SUPPORT_PID).getByTestId('row-action-extract').click();

    // faq:extract declares inputFields, so the platform asks for the target knowledge base.
    const dialog = page.getByTestId('form-dialog');
    await expect(dialog, 'the command asks where to publish before running').toBeVisible({
      timeout: 10000,
    });
    // The target KB is a native <select> populated from /api/ai/knowledge (FormDialog renders a
    // real <select> for type:select) — a reviewer picks a knowledge base, they do not type a ULID.
    const kbPid = process.env.FAQ_TARGET_KB_PID;
    expect(kbPid, 'FAQ_TARGET_KB_PID is set by the runner').toBeTruthy();
    await dialog.getByTestId('form-dialog-field-faq_target_kb_id').selectOption(kbPid!);
    await page.screenshot({ path: `${SHOTS}/M2-3a-distil-dialog.png`, fullPage: true });
    await dialog.getByTestId('form-dialog-submit').click();

    // The LLM call is real, so give it room. What we assert is the persisted effect, not a toast.
    await expect
      .poll(() => candidateCount(page), {
        timeout: 90_000,
        message: 'the distiller produced candidates from a conversation that plainly has two',
      })
      .toBeGreaterThan(0);

    await page.reload({ waitUntil: 'domcontentloaded' });
    await expect(convRow(page, SUPPORT_PID)).toContainText(/[1-9]/, { timeout: 15000 });
    await page.screenshot({ path: `${SHOTS}/M2-3b-distilled.png`, fullPage: true });

    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('M2-4 distilling the chit-chat conversation invents nothing', async ({ page }) => {
    await goto(page, QUEUE, 'table-row-');

    await convRow(page, CHITCHAT_PID).getByTestId('row-action-extract').click();
    const dialog = page.getByTestId('form-dialog');
    await expect(dialog).toBeVisible({ timeout: 10000 });
    await dialog.getByTestId('form-dialog-field-faq_target_kb_id').selectOption(process.env.FAQ_TARGET_KB_PID!);
    await dialog.getByTestId('form-dialog-submit').click();

    // Give the command time to actually run, then assert nothing appeared. This is the
    // fabrication gate driven the way a reviewer would trip it: pointing the distiller at a
    // conversation that has nothing in it.
    await page.waitForTimeout(20_000);
    const res = await page.request.get('/api/faq/conversations?pageNum=1&pageSize=50');
    const rows = (await res.json())?.data?.records ?? [];
    const chitchat = rows.find((r: any) => r.pid === CHITCHAT_PID);
    expect(
      chitchat?.candidateCount,
      'the model invented FAQ(s) from a conversation containing none — unsafe to publish',
    ).toBe(0);
  });

  test('M2-5 the review console shows the conversation a candidate came from', async ({ page }) => {
    const errors = captureConsole(page);
    await goto(page, WORKBENCH, 'metric-strip-value-draft');

    await page.locator('[data-testid^="table-row-"]').first().click();

    // Provenance is the point: a reviewer approving a Q&A must be able to see the turns it was
    // distilled from, or they are rubber-stamping the model.
    const transcript = page.getByTestId('table-block').last();
    await expect(transcript).toContainText('Support', { timeout: 15000 });
    await expect(transcript).toContainText(/3-5 个工作日|30 天内/);

    await page.screenshot({ path: `${SHOTS}/M2-5-candidate-provenance.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });
  test('M2-6 distilling the same conversation twice does not pile up a second copy', async ({ page }) => {
    const errors = captureConsole(page);
    await goto(page, QUEUE, 'table-row-');

    const before = await candidateCount(page);
    expect(before, 'M2-3 already distilled this conversation').toBeGreaterThan(0);

    // Nothing stops a reviewer pressing the button twice — and the M2 auto-trigger, once
    // conversations have a closed state, would fire on every close. A second run must replace
    // this service's own drafts, not hand the reviewer two copies of every pair to decide twice.
    await convRow(page, SUPPORT_PID).getByTestId('row-action-extract').click();
    const dialog = page.getByTestId('form-dialog');
    await expect(dialog).toBeVisible({ timeout: 10000 });
    await dialog.getByTestId('form-dialog-field-faq_target_kb_id').selectOption(process.env.FAQ_TARGET_KB_PID!);
    await dialog.getByTestId('form-dialog-submit').click();

    // Wait for the second distillation to actually land, then assert the count held. Polling for
    // "unchanged" would pass instantly, so wait out the LLM call first.
    await page.waitForTimeout(45_000);
    const after = await candidateCount(page);
    expect(after, 'a second distillation must replace the drafts, not double them').toBe(before);

    await page.screenshot({ path: `${SHOTS}/M2-6-redistilled.png`, fullPage: true });
    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });
});
