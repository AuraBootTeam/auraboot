import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import { BACKEND_URL } from '../../helpers/environments';

/**
 * PAR-24 product-object slice: strict bulk edit, selected export, and bulk
 * delete on the real product list. Existing PAR-13 coverage remains the
 * authority for full export and template-driven import.
 */

const RUN_ID = `par24-product-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par24-product-bulk-io')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par24-product-bulk-io-s73',
      'par24-product-bulk-io',
    );

interface SeededProduct {
  pid: string;
  name: string;
}

test.describe.configure({ mode: 'serial' });

function shot(page: Page, name: string): void {
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  void page.screenshot({
    path: path.join(EVIDENCE_ROOT, `${name}.png`),
    fullPage: false,
  });
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

async function createProduct(jwt: string, name: string): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/prod:create_product`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({
      payload: {
        prod_name: name,
        prod_unit: 'piece',
        prod_type: 'raw_material',
      },
      operationType: 'create',
    }),
  });
  const body: any = await resp.json().catch(() => ({}));
  const pid = body?.data?.data?.recordPid ?? body?.data?.data?.pid ?? '';
  expect(
    resp.status === 200 && Boolean(pid),
    `create ${name}: ${JSON.stringify(body).slice(0, 240)}`,
  ).toBe(true);
  return String(pid);
}

async function listProducts(jwt: string): Promise<any[]> {
  const resp = await fetch(
    `${BACKEND_URL}/api/dynamic/prod_product/list?pageNum=1&pageSize=500&keyword=${encodeURIComponent(RUN_ID)}`,
    { headers: { Authorization: `Bearer ${jwt}` } },
  );
  const body: any = await resp.json().catch(() => ({}));
  expect(resp.ok && Array.isArray(body?.data?.records), `list products: HTTP ${resp.status}`).toBe(
    true,
  );
  return body.data.records;
}

async function injectJwt(page: Page, jwt: string): Promise<void> {
  await page.addInitScript((token) => {
    try {
      localStorage.setItem('jwtToken', token);
    } catch {
      // A missing CSR token makes the journey fail visibly.
    }
  }, jwt);
}

async function openProductList(page: Page, jwt: string): Promise<void> {
  await injectJwt(page, jwt);
  await page.goto('/p/prod_product', { waitUntil: 'domcontentloaded' });
  await page
    .getByPlaceholder(/查询|Search/)
    .first()
    .fill(RUN_ID);
  await page
    .getByPlaceholder(/查询|Search/)
    .first()
    .press('Enter');
  await expect(page.locator('tbody tr').filter({ hasText: RUN_ID }).first()).toBeVisible({
    timeout: 15_000,
  });
}

async function selectProduct(page: Page, name: string): Promise<void> {
  const row = page.locator('tbody tr').filter({ hasText: name }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.locator('[data-testid^="row-checkbox-"]').check();
  await expect(page.getByTestId('bulk-clear-selection-btn')).toBeVisible();
}

let adminJwt = '';
let updateA: SeededProduct;
let updateB: SeededProduct;
let deleteA: SeededProduct;
let deleteB: SeededProduct;
let control: SeededProduct;

test.beforeAll(async () => {
  adminJwt = await loginJwt();
  const suffix = () => ` ${Date.now()}-${Math.random().toString(16).slice(2, 8)}`;
  updateA = {
    pid: await createProduct(adminJwt, `${RUN_ID} update A${suffix()}`),
    name: `${RUN_ID} update A`,
  };
  updateB = {
    pid: await createProduct(adminJwt, `${RUN_ID} update B${suffix()}`),
    name: `${RUN_ID} update B`,
  };
  deleteA = {
    pid: await createProduct(adminJwt, `${RUN_ID} delete A${suffix()}`),
    name: `${RUN_ID} delete A`,
  };
  deleteB = {
    pid: await createProduct(adminJwt, `${RUN_ID} delete B${suffix()}`),
    name: `${RUN_ID} delete B`,
  };
  control = {
    pid: await createProduct(adminJwt, `${RUN_ID} control${suffix()}`),
    name: `${RUN_ID} control`,
  };
});

test('PAR-24 bulk update changes exactly the selected product rows', async ({ page }) => {
  test.setTimeout(300_000);

  await openProductList(page, adminJwt);
  await expect(page.locator('tbody tr').filter({ hasText: updateA.name })).toBeVisible();
  await selectProduct(page, updateA.name);
  await selectProduct(page, updateB.name);
  await shot(page, '01-before-bulk-update');

  await page.getByTestId('bulk-edit-btn').click();
  const dialog = page.getByTestId('bulk-edit-dialog');
  await expect(dialog).toBeVisible();
  await page.getByTestId('bulk-edit-field').selectOption({ label: '商品名称' });
  await page.getByTestId('bulk-edit-value').fill(`${RUN_ID} bulk renamed`);
  await shot(page, '02-bulk-update-form');
  await dialog.getByRole('button', { name: /更新 2 条记录|Update 2 records/ }).click();
  await expect(dialog).toBeHidden({ timeout: 15_000 });

  const renamed = (await listProducts(adminJwt)).filter((record) =>
    String(record.prod_name).includes(`${RUN_ID} bulk renamed`),
  );
  expect(renamed.map((record) => record.pid).sort(), 'both selected records renamed').toEqual(
    [updateA.pid, updateB.pid].sort(),
  );
  const controlRecord = (await listProducts(adminJwt)).find((record) => record.pid === control.pid);
  expect(String(controlRecord?.prod_name)).toContain(`${RUN_ID} control`);
  await expect(page.locator('tbody tr').filter({ hasText: `${RUN_ID} bulk renamed` })).toHaveCount(
    2,
    { timeout: 15_000 },
  );
  await shot(page, '03-bulk-update-complete');
});

test('PAR-24 selected export downloads only selected records', async ({ page }) => {
  test.setTimeout(300_000);

  await openProductList(page, adminJwt);
  await selectProduct(page, deleteA.name);
  await selectProduct(page, deleteB.name);
  await shot(page, '04-before-selected-export');

  const downloadPromise = page.waitForEvent('download', { timeout: 30_000 });
  await page.getByTestId('bulk-more-actions-btn').click();
  await page.getByTestId('bulk-export-selected-btn').click();
  const download = await downloadPromise;
  const exportPath = path.join(EVIDENCE_ROOT, 'selected-export.xlsx');
  fs.mkdirSync(EVIDENCE_ROOT, { recursive: true });
  await download.saveAs(exportPath);

  const bytes = fs.readFileSync(exportPath);
  expect(bytes.length, 'selected export is non-empty OOXML').toBeGreaterThan(1_000);
  expect([bytes[0], bytes[1]], 'XLSX ZIP signature').toEqual([0x50, 0x4b]);
  const workbook = XLSX.read(bytes);
  const exportedText = JSON.stringify(
    XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], { defval: '' }),
  );
  expect(exportedText).toContain(deleteA.name);
  expect(exportedText).toContain(deleteB.name);
  expect(exportedText).not.toContain(control.name);
  expect(exportedText).not.toContain(`${RUN_ID} bulk renamed`);
  await shot(page, '05-selected-export-complete');
});

test('PAR-24 bulk delete removes exactly the selected records', async ({ page }) => {
  test.setTimeout(300_000);

  await openProductList(page, adminJwt);
  await selectProduct(page, deleteA.name);
  await selectProduct(page, deleteB.name);
  await expect(page.getByTestId('bulk-clear-selection-btn')).toBeVisible();
  await expect(page.getByTestId('bulk-more-actions-btn')).toBeEnabled();
  await shot(page, '06-before-bulk-delete');

  await page.getByTestId('bulk-more-actions-btn').click();
  await page.getByTestId('bulk-delete-btn').click();
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

  const survivors = await listProducts(adminJwt);
  const survivorIds = survivors.map((record) => String(record.pid));
  expect(survivorIds).not.toContain(deleteA.pid);
  expect(survivorIds).not.toContain(deleteB.pid);
  expect(survivorIds).toContain(control.pid);
  expect(survivorIds).toContain(updateA.pid);
  expect(survivorIds).toContain(updateB.pid);
  await shot(page, '07-bulk-delete-complete');
});
