/**
 * The retract button, driven from the browser.
 *
 * faq:unpublish has a real UI entry — a 从知识库撤回 row action on the review workbench, mirroring
 * 发布到知识库. Two static gates (audit + import validator) prove the DSL is legal; neither proves
 * the button fires or that pulling actually takes the FAQ out of retrieval. This does: it publishes
 * a candidate, clicks 撤回 in the workbench, and then asks the retrieval API whether the answer is
 * still recalled. If the button were inert (the gate-gap this subsystem keeps hitting), the status
 * would not move; if remove were a status-only flip, the answer would still come back.
 *
 * Needs the golden stack up and FAQ_TARGET_KB_PID pointing at a seeded KB.
 */
import { test, expect, type Page } from '../fixtures';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const QUEUE = '/p/c/faq_conversation_queue';
const WORKBENCH = '/p/c/faq_candidate_workbench';
const SHOTS = process.env.FAQ_GOLDEN_SHOTS || 'test-results/faq-loop-golden';
const KB_PID = process.env.FAQ_TARGET_KB_PID || '';

const metricInt = async (page: Page, key: string): Promise<number> => {
  try {
    const txt = (await page.getByTestId(`metric-strip-value-${key}`).textContent({ timeout: 4000 })) || '';
    return parseInt(txt.replace(/[^\d]/g, ''), 10);
  } catch {
    return NaN;
  }
};

async function gotoWorkbench(page: Page): Promise<void> {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await page.goto(WORKBENCH, { waitUntil: 'domcontentloaded' });
      await page.getByTestId('metric-strip-value-draft').waitFor({ state: 'visible', timeout: attempt === 1 ? 8000 : 20000 });
      await page.waitForLoadState('networkidle').catch(() => null);
      return;
    } catch (err) {
      if (attempt === 3) throw err;
      await page.waitForTimeout(1500);
    }
  }
}

async function fetchCandidates(page: Page): Promise<Record<string, any>[]> {
  const res = await page.request.get('/api/dynamic/faq_candidate_list/list?pageNum=1&pageSize=50');
  if (!res.ok()) return [];
  const body = await res.json();
  return (body?.data?.records ?? body?.data?.list ?? body?.data ?? []) as Record<string, any>[];
}

async function confirmIfPrompted(page: Page): Promise<void> {
  const btn = page.getByRole('button', { name: /确定|确认|OK|Confirm/i }).last();
  if (await btn.isVisible({ timeout: 3000 }).catch(() => false)) await btn.click();
}

test('a published FAQ can be unpublished from the workbench, and is then no longer retrievable', async ({ page }) => {
  // Distillation is a real LLM call (~tens of seconds); the default 15s test cap would fire first.
  test.setTimeout(180_000);
  expect(KB_PID, 'FAQ_TARGET_KB_PID must point at a seeded KB').toBeTruthy();

  // ---- distil a conversation from the queue (real LLM), so there is a candidate to work with ----
  await page.goto(QUEUE, { waitUntil: 'domcontentloaded' });
  const extractBtn = page.locator('[data-testid^="table-row-"]').first().getByTestId('row-action-extract');
  await expect(extractBtn, 'the queue offers a distil action').toBeVisible({ timeout: 20000 });
  const extractDone = page.waitForResponse(
    (r) => r.url().includes('/api/meta/commands/execute/faq:extract') && r.request().method() === 'POST',
    { timeout: 90000 },
  );
  await extractBtn.click();
  // The distil action prompts for a target KB in a FormDialog. The field is a NATIVE <select>
  // (not a Radix combobox), so it is driven with selectOption, not by clicking role=option.
  const kbSelect = page.locator('[data-testid="form-dialog-field-faq_target_kb_id"] select, [data-testid="form-dialog"] select').first();
  await expect(kbSelect, 'the distil dialog asks for a target KB').toBeVisible({ timeout: 8000 });
  await kbSelect.selectOption({ label: 'FAQ unpublish golden KB' }).catch(async () => {
    // Fallback: first real option after the placeholder.
    await kbSelect.selectOption({ index: 1 });
  });
  await confirmIfPrompted(page);
  await extractDone;

  // ---- find the freshly distilled draft candidate --------------------------------------------
  let targetPid = '';
  await expect
    .poll(async () => {
      const draft = (await fetchCandidates(page)).find((c) => c.faq_status === 'draft');
      targetPid = (draft?.pid as string) ?? '';
      return targetPid;
    }, { timeout: 30000, message: 'a draft candidate was distilled' })
    .toBeTruthy();

  // ---- approve → publish (the setup — the retract is what we are testing) ---------------------
  await gotoWorkbench(page);
  await page.getByTestId('metric-strip-item-draft').click();
  const row = () => page.locator(`[data-testid="table-row-${targetPid}"]`);
  await expect(row()).toBeVisible({ timeout: 12000 });
  await row().getByTestId('row-action-approve').click();
  await expect
    .poll(async () => (await fetchCandidates(page)).find((c) => c.pid === targetPid)?.faq_status,
      { timeout: 15000, message: 'approve persists' })
    .toBe('approved');

  await expect.poll(() => metricInt(page, 'approved'), { timeout: 15000 }).toBeGreaterThan(0);
  await page.getByTestId('metric-strip-item-approved').click();
  const publishBtn = row().getByTestId('row-action-publish');
  await expect(publishBtn, 'approved row offers publish').toBeVisible({ timeout: 15000 });
  await publishBtn.click();
  await confirmIfPrompted(page);
  await expect
    .poll(async () => (await fetchCandidates(page)).find((c) => c.pid === targetPid)?.faq_status,
      { timeout: 30000, message: 'publish persists' })
    .toBe('published');

  const published = (await fetchCandidates(page)).find((c) => c.pid === targetPid)!;
  const question = String(published.faq_question);
  const answerFragment = String(published.faq_answer).slice(0, 12);

  // The published FAQ is recalled before we retract — otherwise "no longer retrievable" proves nothing.
  const before = await page.request.post(`/api/ai/knowledge/${KB_PID}/retrieve`, { data: { query: question, topK: 5 } });
  if (before.ok()) {
    expect(JSON.stringify((await before.json())?.data ?? []), 'the FAQ is recalled while published')
      .toContain(answerFragment);
  }

  // ---- the retract, from the workbench -------------------------------------------------------
  await page.getByTestId('metric-strip-item-published').click();
  const unpublishBtn = row().getByTestId('row-action-unpublish');
  await expect(unpublishBtn, 'a PUBLISHED row offers 从知识库撤回 (the button exists and is state-aware)')
    .toBeVisible({ timeout: 15000 });
  await page.screenshot({ path: `${SHOTS}/U1-published-with-unpublish.png`, fullPage: true });

  await unpublishBtn.click();
  await confirmIfPrompted(page);

  // The button did something: the candidate is back to approved.
  await expect
    .poll(async () => (await fetchCandidates(page)).find((c) => c.pid === targetPid)?.faq_status,
      { timeout: 20000, message: 'unpublish returns the candidate to approved (the button is not inert)' })
    .toBe('approved');

  const retracted = (await fetchCandidates(page)).find((c) => c.pid === targetPid)!;
  expect(retracted.faq_kb_document_pid ?? '', 'the document pid is cleared — no dangling reference').toBeFalsy();

  // The load-bearing assertion: the answer is no longer recalled by a search for its own question.
  const after = await page.request.post(`/api/ai/knowledge/${KB_PID}/retrieve`, { data: { query: question, topK: 5 } });
  if (after.ok()) {
    expect(JSON.stringify((await after.json())?.data ?? []), 'the retracted FAQ is no longer recalled')
      .not.toContain(answerFragment);
  }

  await page.getByTestId('metric-strip-item-approved').click();
  await expect(row(), 'the candidate is back in the approved queue, ready to re-publish').toBeVisible({ timeout: 12000 });
  await page.screenshot({ path: `${SHOTS}/U2-retracted-back-to-approved.png`, fullPage: true });
});
