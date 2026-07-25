/**
 * Every AI Center sidebar entry must land on a page.
 *
 * WHY THIS EXISTS, given nine green DSL page specs
 * ------------------------------------------------
 * All nine navigate by URL (`page.goto('/p/c/ai_colleagues')`). None clicks the
 * menu. When the five hand-written AI pages became DSL pages, the React routes
 * under app/pages/ai/ were deleted and resources.ts was repointed to
 * /p/c/<pageKey> — but the backend menu row still served the old /ai/colleagues.
 * The sidebar's main entry to the digital-employee product rendered
 * "Page Unavailable"; every spec stayed green.
 *
 * The sidebar is backend-driven (/api/menu/user, seeded from a plugin's
 * menus.json), so a page can be perfectly fine and still be unreachable. That
 * is a different failure than "the page is broken", and only a click finds it.
 *
 * The check is deliberately generic — it walks whatever the menu API returns
 * under AI Center rather than pinning a list — so a future page added with a
 * stale path is caught without anyone remembering to extend this file.
 */
import { test, expect } from '@playwright/test';

type MenuNode = {
  code?: string;
  name?: string;
  path?: string | null;
  type?: number;
  children?: MenuNode[];
};

/** Leaf entries (type 1) carrying a path, anywhere under the AI Center subtree. */
function collectAiLeaves(nodes: MenuNode[] | undefined, inside = false): MenuNode[] {
  const out: MenuNode[] = [];
  for (const n of nodes ?? []) {
    const here = inside || n.code === 'ai_center';
    if (here && n.path) out.push(n);
    out.push(...collectAiLeaves(n.children, here));
  }
  return out;
}

/**
 * Wait for a SETTLED content area, not merely a non-empty one.
 *
 * The first version of this helper waited for `textContent.length > 20`, which
 * the placeholder "Loading page configuration..." satisfies immediately. The
 * check then ran before the route had resolved, found no error card, and passed
 * — under a deliberately broken menu path. A weak wait turns "hasn't rendered
 * yet" into "nothing is wrong", which is the same failure as a fixed sleep.
 *
 * Note the contrast with the click test below: `expect(...).toBeVisible()` is a
 * web-first assertion and retries on its own, so it went red correctly. Only the
 * hand-written `if` needed this. Prefer polling assertions to hand-rolled reads.
 *
 * The error card counts as settled — it is one of the outcomes being judged.
 */
const LOADING_PLACEHOLDERS = /Loading page configuration|Loading\.\.\.|加载中|载入中/i;

async function waitForSettledContent(page: import('@playwright/test').Page) {
  await page.waitForFunction(
    (loadingSrc: string) => {
      const main = document.querySelector('main') ?? document.body;
      const text = (main.textContent ?? '').trim();
      return text.length > 20 && !new RegExp(loadingSrc, 'i').test(text);
    },
    LOADING_PLACEHOLDERS.source,
    { timeout: 30_000 },
  );
}

test.describe('AI Center — every sidebar entry resolves', () => {
  // One navigation per menu entry; the walk is the point, so give it room.
  test.setTimeout(180_000);

  test('no AI Center menu entry lands on "Page Unavailable"', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' });

    const resp = await page.request.get('/api/menu/user');
    expect(resp.ok(), 'the menu API must answer').toBeTruthy();
    const leaves = collectAiLeaves(((await resp.json())?.data as MenuNode[]) ?? []);

    // Guard the guard: if this user cannot see AI Center at all the walk below is
    // vacuous, and a vacuous assertion is worse than none — it reads as coverage.
    expect(
      leaves.length,
      'this user must be able to see AI Center entries, otherwise this test proves nothing',
    ).toBeGreaterThan(0);

    const broken: string[] = [];
    for (const leaf of leaves) {
      await page.goto(leaf.path!, { waitUntil: 'domcontentloaded' });
      await waitForSettledContent(page);
      const body = await page.locator('body').innerText();
      if (/Page Unavailable|no associated page configuration|页面不可用/i.test(body)) {
        broken.push(`${leaf.code ?? leaf.name} -> ${leaf.path}`);
      }
    }

    expect(
      broken,
      'a menu row points at a route that no longer exists — the page may be fine, the entry is not',
    ).toEqual([]);
  });

  test('clicking "AI 同事" in the sidebar reaches the colleagues grid', async ({ page }) => {
    await page.goto('/', { waitUntil: 'domcontentloaded' });
    await waitForSettledContent(page);

    // The click, not a goto: this is the assertion the URL-based specs cannot make.
    const entry = page.locator('a, button, [role="link"]', { hasText: /^\s*AI 同事\s*$/ }).first();
    await expect(entry, 'the sidebar must offer an AI Colleagues entry').toHaveCount(1);
    await entry.click();

    await expect(
      page.locator('[data-testid="agent-colleagues-grid"]'),
      'the sidebar entry must land on the colleagues grid',
    ).toBeVisible({ timeout: 20_000 });
    await expect(page.locator('body')).not.toContainText('Page Unavailable');
  });
});
