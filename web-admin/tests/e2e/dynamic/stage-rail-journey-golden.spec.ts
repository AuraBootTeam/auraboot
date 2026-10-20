import { test, expect, type Page } from '@playwright/test';
import { executeCommandViaApi, uniqueId, findRowInPaginatedList } from '../helpers';

/**
 * Platform stage-rail fixture journey: list search, browser form creation,
 * versioned update and detail navigation through the generic dynamic runtime.
 * The historical e2et_crm_opp fixture vocabulary is retained; this spec neither
 * loads the CRM product nor certifies its business pipeline implementation.
 */

const LIST_PATH = '/p/e2et_crm_opp';
const MODEL = 'e2et_crm_opp';

const STAGE_LABELS: Record<string, RegExp> = {
  discovery: /需求发现|Discovery/,
  qualification: /资格确认|Qualification/,
  proposal: /方案报价|Proposal/,
  negotiation: /商务谈判|Negotiation/,
  closed_won: /赢单|Won/,
  closed_lost: /已丢单|Lost/,
};

interface Opp {
  pid: string;
  name: string;
  stage: string;
}

const created: Opp[] = [];

async function createOpp(page: Page, name: string, stage: string, amount: number): Promise<Opp> {
  const result = await executeCommandViaApi(page, 'e2et_crm_opp.create', {
    e2et_crm_opp_name: name,
    e2et_crm_opp_stage: stage,
    e2et_crm_opp_amount: amount,
  });
  expect(result.code, `create ${name}`).toBe('0');
  const opp = { pid: result.recordId, name, stage };
  created.push(opp);
  return opp;
}

async function readRowVersion(page: Page, pid: string): Promise<number> {
  const resp = await page.request.get(`/api/dynamic/${MODEL}/${pid}`);
  expect(resp.ok(), `read ${pid}`).toBe(true);
  const body = await resp.json();
  return Number(body?.data?.row_version ?? 1);
}

async function openList(page: Page): Promise<void> {
  const listResponse = page.waitForResponse(
    (r) => r.url().includes(`/${MODEL}/list`) && r.status() === 200,
    { timeout: 15_000 },
  );
  await page.goto(LIST_PATH, { waitUntil: 'domcontentloaded' });
  const resp = await listResponse;
  expect(resp.ok()).toBe(true);
}

test.describe('Platform stage-rail fixture journey', () => {
  test.describe.configure({ mode: 'serial' });
  test.setTimeout(60_000);

  const uid = uniqueId('pipe');
  const PREFIX = `Pipeline ${uid}`;
  let oppA: Opp;
  let oppB: Opp;
  let oppC: Opp;

  test.beforeAll(async ({ request }) => {
    // Seed through the page-backed request context (carries the admin session).
    const page = { request } as unknown as Page;
    oppA = await createOpp(page, `${PREFIX} A`, 'discovery', 1200);
    oppB = await createOpp(page, `${PREFIX} B`, 'qualification', 3500);
    oppC = await createOpp(page, `${PREFIX} C`, 'proposal', 8800);
  });

  test.afterAll(async ({ request }) => {
    for (const opp of created) {
      await request.delete(`/api/dynamic/${MODEL}/${opp.pid}`).catch(() => null);
    }
  });

  test('list renders seeded pipeline rows and keyword search narrows them', async ({ page }) => {
    await openList(page);
    const rowA = await findRowInPaginatedList(page, `${PREFIX} A`, 15_000);
    await expect(rowA).toBeVisible();
    await expect(rowA).toContainText(STAGE_LABELS.discovery);

    const searchInput = page.getByTestId('list-search-input');
    await searchInput.fill(`${PREFIX} B`);
    await searchInput.press('Enter');
    await expect(page.locator('tbody tr', { hasText: `${PREFIX} B` })).toBeVisible();
    await expect(page.locator('tbody tr', { hasText: `${PREFIX} A` })).toHaveCount(0);

    await searchInput.fill('');
    await searchInput.press('Enter');
    await expect(page.locator('tbody tr', { hasText: `${PREFIX} C` })).toBeVisible();
  });

  test('form page creates an opportunity through the browser', async ({ page }) => {
    await openList(page);
    // The flat v4 toolbar renders the create action without the toolbar-btn
    // testid (dynamic list chrome); target it by its label.
    await page.getByRole('button', { name: /新建|New/i }).first().click();
    await expect(page.getByTestId('dynamic-form')).toBeVisible({ timeout: 15_000 });

    const name = `${PREFIX} UI`;
    await page.getByTestId('form-field-e2et_crm_opp_name').getByRole('textbox').fill(name);
    // stage renders as a radix combobox: open the trigger, pick the option.
    await page.getByTestId('form-field-e2et_crm_opp_stage').getByRole('combobox').click();
    await page.getByRole('option', { name: STAGE_LABELS.negotiation }).click();
    await page
      .getByTestId('form-field-e2et_crm_opp_amount')
      .locator('input')
      .fill('6600');

    const submit = page.getByTestId('form-btn-submit');
    await submit.click();
    // Success returns to the list (or shows a success toast then navigates).
    await page.waitForURL(new RegExp(LIST_PATH), { timeout: 20_000 }).catch(async () => {
      await openList(page);
    });

    const row = await findRowInPaginatedList(page, name, 15_000);
    await expect(row).toBeVisible();
    await expect(row).toContainText(STAGE_LABELS.negotiation);
  });

  test('stage advance persists through the update command with CAS version', async ({ page }) => {
    const version = await readRowVersion(page, oppB.pid);
    const advanced = await executeCommandViaApi(
      page,
      'e2et_crm_opp.update',
      {
        e2et_crm_opp_name: oppB.name,
        e2et_crm_opp_stage: 'closed_won',
        e2et_crm_opp_amount: 3500,
      },
      oppB.pid,
      'update',
      { expectedVersion: version },
    );
    expect(advanced.code, 'CAS stage advance').toBe('0');
    oppB.stage = 'closed_won';

    await openList(page);
    const row = await findRowInPaginatedList(page, oppB.name, 15_000);
    await expect(row).toBeVisible();
    await expect(row).toContainText(STAGE_LABELS.closed_won);

    // Backend readback: the stored stage is the advanced value.
    const resp = await page.request.get(`/api/dynamic/${MODEL}/${oppB.pid}`);
    const body = await resp.json();
    expect(body?.data?.e2et_crm_opp_stage).toBe('closed_won');
  });

  test('row deep-link opens the detail page for the opportunity', async ({ page }) => {
    await openList(page);
    const row = await findRowInPaginatedList(page, oppC.name, 15_000);
    // detailNavigation 'page': clicking anywhere on the row deep-links to the
    // detail page for the record.
    await row.click();
    await page.waitForURL(new RegExp('/view/'), { timeout: 15_000 });
    await expect(page.locator('main').first()).toContainText(oppC.name);
    await expect(page.locator('main').first()).toContainText(STAGE_LABELS.proposal);
  });
});
