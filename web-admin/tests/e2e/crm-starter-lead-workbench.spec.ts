/**
 * CRM Lead Console (workbench) — browser golden (S6).
 *
 * Validates the seeded-model-backed workbench page `crm_lead_workbench`
 * (plugins/crm-starter/config/pages/crm_lead_workbench.json), reached at
 * `/p/c/crm_lead_workbench`. Unlike the synthetic static-data workbench runtime
 * test, this drives the REAL data loop over the seeded `crm_lead` model:
 *
 *  - metric-strip KPIs show real namedQuery counts (not "-")
 *  - clicking a metric chip re-queries the table via `${state.statusFilter}` +
 *    `dependOn` — the visible row count actually changes (real SQL filter)
 *  - review-drawer opens side-by-side with the selected lead (deferred dataSource)
 *  - a row status command (crm:contact_lead) persists to the DB and the
 *    workbench reflects the change after refetch
 *  - zero DSL expression-evaluator errors in the console
 *
 * Pairs UI evidence with backend evidence (DB status assertion via the dynamic
 * list API) per the page-golden contract.
 */
import { test, expect, type Page } from '../fixtures';

test.use({ storageState: process.env.PW_ADMIN_STORAGE_STATE || 'tests/storage/admin.json' });

const PAGE_PATH = '/p/c/crm_lead_workbench';

/** Vite dev-server artifacts (not product bugs) — re-optimize 504 + chunk fetch. */
function isDevServerNoise(text: string): boolean {
  return /Outdated Optimize Dep|Failed to fetch dynamically imported module|504|Loading chunk|Importing a module script failed/i.test(
    text,
  );
}

/** Product-level console errors we must never see (DSL evaluator / React). */
function isProductError(text: string): boolean {
  if (isDevServerNoise(text)) return false;
  return /exprError|尝试调用非函数值|Maximum update depth|Invalid hook call|Cannot read properp|is not a function|Internal system error|Application Error|TypeError|ReferenceError/i.test(
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

/** Navigate with one reload guard for the first-load Vite dep-optimize 504. */
async function gotoWorkbench(page: Page): Promise<void> {
  await page.goto(PAGE_PATH, { waitUntil: 'domcontentloaded' });
  // If the dynamic page chunk 504'd on first optimize, the metric strip won't
  // mount — reload once to pick up the re-optimized bundle.
  const stripReady = await page
    .getByTestId('metric-strip-value-total')
    .waitFor({ state: 'visible', timeout: 8000 })
    .then(() => true)
    .catch(() => false);
  if (!stripReady) {
    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.getByTestId('metric-strip-value-total').waitFor({ state: 'visible', timeout: 15000 });
  }
  await page.waitForLoadState('networkidle').catch(() => null);
}

async function tableRowCount(page: Page): Promise<number> {
  return page.locator('[data-testid^="table-row-"]').count();
}

/** Wait until the table row count settles to an expected value (refetch race). */
async function expectRowCount(page: Page, expected: number, label: string): Promise<void> {
  await expect
    .poll(() => tableRowCount(page), { timeout: 12000, message: label })
    .toBe(expected);
}

const metricInt = async (page: Page, key: string): Promise<number> => {
  const txt = (await page.getByTestId(`metric-strip-value-${key}`).textContent()) || '';
  return parseInt(txt.replace(/[^\d]/g, ''), 10);
};

test.describe('CRM Lead Console — workbench golden', () => {
  test.setTimeout(90_000);

  test('S6-1 KPI metric-strip shows real counts (not "-")', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    const total = await metricInt(page, 'total');
    expect(total, 'total leads KPI is a real positive number').toBeGreaterThan(0);
    for (const key of ['new', 'contacted', 'qualified', 'converted', 'lost']) {
      const v = (await page.getByTestId(`metric-strip-value-${key}`).textContent())?.trim();
      expect(v, `metric ${key} is not the empty "-" placeholder`).not.toBe('-');
      expect(Number.isFinite(parseInt((v || '').replace(/[^\d]/g, ''), 10))).toBe(true);
    }
    // status buckets sum to the total (namedQuery integrity)
    const sum =
      (await metricInt(page, 'new')) +
      (await metricInt(page, 'contacted')) +
      (await metricInt(page, 'qualified')) +
      (await metricInt(page, 'converted')) +
      (await metricInt(page, 'lost'));
    expect(sum, 'status buckets sum to total').toBe(total);

    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('S6-2 clicking a metric chip re-queries the table (row count changes)', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    const qualifiedCount = await metricInt(page, 'qualified');
    const lostCount = await metricInt(page, 'lost');
    expect(qualifiedCount).toBeGreaterThan(0);
    expect(lostCount).toBeGreaterThan(0);
    expect(qualifiedCount).not.toBe(lostCount);

    // Filter to qualified → table rows must equal the qualified KPI count.
    await page.getByTestId('metric-strip-item-qualified').click();
    await expectRowCount(page, qualifiedCount, 'qualified filter row count');

    // Filter to lost → rows change to the lost KPI count (real re-query, not cosmetic).
    await page.getByTestId('metric-strip-item-lost').click();
    await expectRowCount(page, lostCount, 'lost filter row count');

    // Back to all → rows return to first-page size (>= a single bucket).
    await page.getByTestId('metric-strip-item-total').click();
    await expect
      .poll(() => tableRowCount(page), { timeout: 12000 })
      .toBeGreaterThan(lostCount);

    expect(errors.filter(isProductError), 'no product console errors during filtering').toEqual([]);
  });

  test('S6-3 selecting a row opens the review drawer with the lead', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    await expect(page.getByTestId('review-drawer-empty')).toBeVisible();
    const firstRow = page.locator('[data-testid^="table-row-"]').first();
    await firstRow.click();

    const drawer = page.getByTestId('review-drawer');
    await expect(drawer).toBeVisible({ timeout: 10000 });
    await expect(page.getByTestId('review-drawer-empty')).toHaveCount(0);
    // drawer shows the lead summary (company/status fields render)
    await expect(drawer).toContainText(/Company|公司/);

    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });

  test('S6-4 row status command persists and the workbench reflects it', async ({ page }) => {
    const errors = captureConsole(page);
    await gotoWorkbench(page);

    // Focus the "new" bucket so a contact_lead transition is valid.
    await page.getByTestId('metric-strip-item-new').click();
    const newBefore = await metricInt(page, 'new');
    const contactedBefore = await metricInt(page, 'contacted');
    await expectRowCount(page, newBefore, 'new filter row count');
    expect(newBefore).toBeGreaterThan(0);

    // Capture the target lead pid from the first row, then run its contact action.
    const firstRow = page.locator('[data-testid^="table-row-"]').first();
    const rowTestId = (await firstRow.getAttribute('data-testid')) || '';
    const leadPid = rowTestId.replace('table-row-', '');
    expect(leadPid).not.toBe('');

    await firstRow.getByTestId('row-action-contact').click();
    // confirm dialog if any
    const confirmBtn = page.getByRole('button', { name: /确定|确认|Confirm|OK/ }).first();
    if (await confirmBtn.isVisible().catch(() => false)) await confirmBtn.click();

    // Backend evidence: the lead's status is now 'contacted' in the DB.
    await expect
      .poll(
        async () => {
          const resp = await page.request.get(
            `/api/dynamic/crm_lead_list/list?pageNum=1&pageSize=1&filters=${encodeURIComponent(
              JSON.stringify([{ fieldName: 'pid', operator: 'EQ', value: leadPid }]),
            )}`,
          );
          if (!resp.ok()) return 'http-' + resp.status();
          const body = await resp.json();
          const rows = body?.data?.records ?? body?.data?.rows ?? body?.data ?? [];
          return Array.isArray(rows) && rows[0] ? rows[0].crm_lead_status : 'no-row';
        },
        { timeout: 15000, message: 'lead status transitions to contacted in DB' },
      )
      .toBe('contacted');

    // UI evidence: after refetch the new bucket dropped by 1, contacted rose by 1.
    await gotoWorkbench(page);
    await expect.poll(() => metricInt(page, 'new'), { timeout: 12000 }).toBe(newBefore - 1);
    expect(await metricInt(page, 'contacted')).toBe(contactedBefore + 1);

    expect(errors.filter(isProductError), 'no product console errors').toEqual([]);
  });
});
