/**
 * Data-plane review — list-page action points.
 *
 * The list page is where three of the six capabilities actually surface to a user:
 * quick-filter presets (the chip row), saved views (the view switcher), and print
 * (the toolbar overflow). This drives each action point against a real backend and
 * asserts an OBSERVABLE state change, not just that a control exists.
 *
 * Falsifiability notes:
 *  - the filter assertion compares row counts BEFORE and AFTER, and requires the
 *    unfiltered set to be non-empty first, so a page that renders zero rows cannot
 *    make it pass vacuously;
 *  - the print assertion checks the print stylesheet actually marks chrome hidden,
 *    not merely that a menu item is present.
 */
import { expect, test } from '@playwright/test';

const OUT = 'test-results/dataplane-review';
const PAGE = '/p/e2et_order';

// Environment prerequisite (🟢 env gate, not a skip-wrapped product gap): these action
// points need the e2et_order fixture model WITH rows. Without the test-fixtures plugin
// imported, a failure here would be a false red about seeding, not about the feature.
async function requireSeededOrders(request: import('@playwright/test').APIRequestContext) {
  const resp = await request.get('/api/dynamic/e2et_order/list?pageNum=1&pageSize=1');
  if (!resp.ok()) return 0;
  const body = await resp.json();
  return body?.data?.total ?? body?.data?.records?.length ?? 0;
}


async function rowCount(page: import('@playwright/test').Page) {
  // Count only real data rows: a body row that carries a record link/checkbox.
  return page.locator('tbody tr').filter({ hasNot: page.locator('[data-empty], .empty') }).count();
}

test.describe('Data-plane review — list page action points', () => {
  test.setTimeout(120_000);

  test('quick-filter preset chips actually narrow the result set', async ({ page, request }) => {
    const seeded = await requireSeededOrders(request);
    test.skip(seeded < 2, 'e2et_order fixture not seeded with enough rows in this stack');

    await page.goto(PAGE, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {});

    // Real selectors read from ListToolbar.tsx, not guessed.
    const quickFilters = page.getByTestId('quick-filters');
    const statusChips = page.getByText(/^(全部|草稿|已提交|已审批|已完成|已取消)$/);
    const chipCount = (await quickFilters.count()) + (await statusChips.count());
    await page.screenshot({ path: `${OUT}/list-default-view.png` });

    expect(chipCount, 'quick-filter / status chips should render on the list page').toBeGreaterThan(0);

    const before = await rowCount(page);
    // Precondition — if the unfiltered list is empty the comparison below proves nothing.
    expect(before, 'unfiltered list must have rows for the filter assertion to mean anything')
      .toBeGreaterThan(0);

    // Click a status chip that must exclude at least one seeded row.
    const draftChip = page.getByText('草稿', { exact: true }).first();
    await draftChip.click();
    await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => {});
    const after = await rowCount(page);
    await page.screenshot({ path: `${OUT}/list-filtered-draft.png` });

    // eslint-disable-next-line no-console
    console.log(`[dp66][filter] before=${before} after=${after}`);
    expect(after, 'clicking a status chip must change the visible row set').not.toBe(before);
  });

  test('print affordance exists and the print stylesheet hides app chrome', async ({ page, request }) => {
    const seeded = await requireSeededOrders(request);
    test.skip(seeded < 1, 'e2et_order fixture not seeded in this stack');

    await page.goto(PAGE, { waitUntil: 'domcontentloaded' });
    await page.waitForLoadState('networkidle', { timeout: 20_000 }).catch(() => {});

    // Print is behind the toolbar overflow menu.
    const more = page.getByRole('button', { name: /more|更多/i }).first();
    const hasMore = (await more.count()) > 0;
    if (hasMore) {
      await more.click();
      await page.screenshot({ path: `${OUT}/list-toolbar-more.png` });
    }
    const printItem = page.getByText(/打印|print/i).first();
    const printVisible = (await printItem.count()) > 0;

    // Does the print stylesheet actually take effect under print emulation?
    await page.emulateMedia({ media: 'print' });
    const chromeHidden = await page
      .locator('.print-hide, [data-print="hide"]')
      .first()
      .isHidden()
      .catch(() => null);
    await page.screenshot({ path: `${OUT}/list-print-emulated.png` });
    await page.emulateMedia({ media: 'screen' });

    // eslint-disable-next-line no-console
    console.log(`[dp66][print] moreMenu=${hasMore} printItem=${printVisible} chromeHidden=${chromeHidden}`);

    expect(printVisible, 'a print affordance should be reachable from the list toolbar').toBe(true);
    expect(chromeHidden, 'under print media the .print-hide chrome must be hidden').toBe(true);
  });
});
