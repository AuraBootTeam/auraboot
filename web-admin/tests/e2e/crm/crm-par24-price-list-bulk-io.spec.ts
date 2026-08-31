import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import { BACKEND_URL } from '../../helpers/environments';

/**
 * PAR-24 price-list slice. Mutations intentionally go through the guarded
 * sales commands once per selected PID; selected export uses the platform
 * read-only export contract.
 */

const RUN_ID = `par24-price-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par24-price-list-bulk-io')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par24-price-list-bulk-io-s74',
      'par24-price-list-bulk-io',
    );

interface PriceList {
  pid: string;
  name: string;
}

test.describe.configure({ mode: 'serial' });

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  void page.screenshot({ path: path.join(EVIDENCE_ROOT, `${name}.png`), fullPage: false });
}

async function loginJwt(): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }),
  });
  const body: any = await resp.json().catch(() => ({}));
  expect(resp.status === 200 && Boolean(body?.data?.jwt), 'admin login').toBe(true);
  return body.data.jwt;
}

async function api(
  jwt: string,
  apiPath: string,
  method: 'GET' | 'POST' = 'GET',
  payload?: unknown,
) {
  const resp = await fetch(`${BACKEND_URL}${apiPath}`, {
    method,
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body: any = await resp.json().catch(() => ({}));
  return { ok: resp.ok && body?.code === '0', status: resp.status, body };
}

async function createPriceList(jwt: string, name: string): Promise<string> {
  const result = await api(jwt, '/api/meta/commands/execute/sl:create_price_list', 'POST', {
    payload: {
      sl_pl_name: name,
      sl_pl_currency: 'CNY',
      sl_pl_priority: 100,
      sl_pl_description: `${RUN_ID} controlled journey record`,
    },
    operationType: 'create',
  });
  const pid = result.body?.data?.data?.recordPid ?? result.body?.data?.data?.pid ?? '';
  expect(
    result.ok && Boolean(pid),
    `create ${name}: ${JSON.stringify(result.body).slice(0, 240)}`,
  ).toBe(true);
  return String(pid);
}

async function listPriceLists(jwt: string): Promise<any[]> {
  const result = await api(
    jwt,
    `/api/dynamic/sl_price_list_common/list?pageNum=1&pageSize=500&keyword=${encodeURIComponent(RUN_ID)}`,
  );
  expect(result.ok, `list price lists: ${JSON.stringify(result.body).slice(0, 240)}`).toBe(true);
  return result.body?.data?.records ?? [];
}

async function injectJwt(page: Page, jwt: string): Promise<void> {
  await page.addInitScript((token) => {
    try {
      localStorage.setItem('jwtToken', token);
    } catch {
      // Missing CSR auth will fail visibly.
    }
  }, jwt);
}

async function openPriceLists(page: Page, jwt: string): Promise<void> {
  await injectJwt(page, jwt);
  await page.goto('/p/sl_price_list_common', { waitUntil: 'domcontentloaded' });
  const search = page.getByPlaceholder(/查询|Search/).first();
  await search.fill(RUN_ID);
  await search.press('Enter');
  await expect(page.locator('tbody tr').filter({ hasText: RUN_ID }).first()).toBeVisible({
    timeout: 15_000,
  });
}

async function selectPriceList(page: Page, name: string): Promise<void> {
  const row = page.locator('tbody tr').filter({ hasText: name }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.locator('[data-testid^="row-checkbox-"]').check();
  await expect(page.getByTestId('bulk-clear-selection-btn')).toBeVisible();
}

let adminJwt = '';
let updateA: PriceList;
let updateB: PriceList;
let deleteA: PriceList;
let deleteB: PriceList;
let control: PriceList;

test.beforeAll(async () => {
  adminJwt = await loginJwt();
  const unique = () => ` ${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
  updateA = {
    pid: await createPriceList(adminJwt, `${RUN_ID} update A${unique()}`),
    name: `${RUN_ID} update A`,
  };
  updateB = {
    pid: await createPriceList(adminJwt, `${RUN_ID} update B${unique()}`),
    name: `${RUN_ID} update B`,
  };
  deleteA = {
    pid: await createPriceList(adminJwt, `${RUN_ID} delete A${unique()}`),
    name: `${RUN_ID} delete A`,
  };
  deleteB = {
    pid: await createPriceList(adminJwt, `${RUN_ID} delete B${unique()}`),
    name: `${RUN_ID} delete B`,
  };
  control = {
    pid: await createPriceList(adminJwt, `${RUN_ID} control${unique()}`),
    name: `${RUN_ID} control`,
  };
});

test('PAR-24 price-list bulk rename executes the guarded update command', async ({ page }) => {
  test.setTimeout(300_000);

  await openPriceLists(page, adminJwt);
  await selectPriceList(page, updateA.name);
  await selectPriceList(page, updateB.name);
  await shot(page, '01-before-command-bulk-update');

  await page.getByTestId('bulk-action-bulk_update_price_list_name').click();
  const dialog = page.getByTestId('bulk-field-command-dialog');
  await expect(dialog).toBeVisible();
  await page.locator('#sl_pl_name').fill(`${RUN_ID} bulk renamed`);
  await shot(page, '02-command-bulk-update-form');
  await page.getByTestId('bulk-field-command-submit').click();
  await expect(dialog).toBeHidden({ timeout: 15_000 });

  const records = await listPriceLists(adminJwt);
  const renamed = records.filter((record) =>
    String(record.sl_pl_name).includes(`${RUN_ID} bulk renamed`),
  );
  expect(renamed.map((record) => record.pid).sort()).toEqual([updateA.pid, updateB.pid].sort());
  const controlRecord = records.find((record) => record.pid === control.pid);
  expect(String(controlRecord?.sl_pl_name)).toContain(`${RUN_ID} control`);
  await expect(page.locator('tbody tr').filter({ hasText: `${RUN_ID} bulk renamed` })).toHaveCount(
    2,
    {
      timeout: 15_000,
    },
  );
  await shot(page, '03-command-bulk-update-complete');
});

test('PAR-24 price-list selected export downloads exactly selected records', async ({ page }) => {
  test.setTimeout(300_000);

  await openPriceLists(page, adminJwt);
  await selectPriceList(page, deleteA.name);
  await selectPriceList(page, deleteB.name);
  await shot(page, '04-before-selected-export');

  const downloadPromise = page.waitForEvent('download', { timeout: 30_000 });
  await page.getByTestId('bulk-more-actions-btn').click();
  await page.getByTestId('bulk-export-selected-btn').click();
  const download = await downloadPromise;
  const exportPath = path.join(EVIDENCE_ROOT, 'selected-export.xlsx');
  await download.saveAs(exportPath);

  const bytes = fs.readFileSync(exportPath);
  expect(bytes.length).toBeGreaterThan(1_000);
  expect([bytes[0], bytes[1]]).toEqual([0x50, 0x4b]);
  const workbook = XLSX.read(bytes);
  const exportedRows = XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], {
    defval: '',
  }) as Array<Record<string, unknown>>;
  expect(exportedRows.map((row) => String(row.pid)).sort()).toEqual(
    [deleteA.pid, deleteB.pid].sort(),
  );
  const exportedText = JSON.stringify(
    XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], { defval: '' }),
  );
  expect(exportedText).toContain(deleteA.name);
  expect(exportedText).toContain(deleteB.name);
  await shot(page, '05-selected-export-complete');
});

test('PAR-24 price-list bulk delete executes guarded command per selected row', async ({
  page,
}) => {
  test.setTimeout(300_000);

  await openPriceLists(page, adminJwt);
  await selectPriceList(page, deleteA.name);
  await selectPriceList(page, deleteB.name);
  await shot(page, '06-before-command-bulk-delete');

  await page.getByTestId('bulk-more-actions-btn').click();
  await page.getByTestId('bulk-action-bulk_delete_draft_price_lists').click();
  const confirm = page.getByTestId('confirm-dialog');
  await expect(confirm).toBeVisible();
  await page.getByTestId('confirm-ok').click();

  await expect(page.locator('tbody tr').filter({ hasText: deleteA.name })).toHaveCount(0, {
    timeout: 15_000,
  });
  await expect(page.locator('tbody tr').filter({ hasText: deleteB.name })).toHaveCount(0, {
    timeout: 15_000,
  });
  await expect(page.locator('tbody tr').filter({ hasText: control.name })).toBeVisible();

  const survivorIds = (await listPriceLists(adminJwt)).map((record) => String(record.pid));
  expect(survivorIds).not.toContain(deleteA.pid);
  expect(survivorIds).not.toContain(deleteB.pid);
  expect(survivorIds).toContain(control.pid);
  expect(survivorIds).toContain(updateA.pid);
  expect(survivorIds).toContain(updateB.pid);
  await shot(page, '07-command-bulk-delete-complete');
});
