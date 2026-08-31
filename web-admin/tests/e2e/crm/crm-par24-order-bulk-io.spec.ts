import { expect, test, type Page } from '../../fixtures';
import fs from 'node:fs';
import path from 'node:path';
import * as XLSX from 'xlsx';
import { BACKEND_URL } from '../../helpers/environments';

/**
 * PAR-24 sales-order slice. Draft mutations go through the guarded order
 * commands once per selected PID; selected export uses the read-only platform
 * contract and is parsed back to its exact PID set.
 */

const RUN_ID = `par24-order-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const EVIDENCE_ROOT = process.env.AURA_EVIDENCE_ROOT
  ? path.join(process.env.AURA_EVIDENCE_ROOT, 'par24-order-bulk-io')
  : path.resolve(
      process.cwd(),
      '..',
      '.workspace',
      'evidence',
      'par24-order-bulk-io-s76',
      'par24-order-bulk-io',
    );

interface Order {
  pid: string;
  code: string;
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

async function injectJwt(page: Page, jwt: string): Promise<void> {
  await page.addInitScript((token) => {
    try {
      localStorage.setItem('jwtToken', token);
    } catch {
      // Missing CSR auth fails visibly on navigation.
    }
  }, jwt);
}

async function openOrders(page: Page, jwt: string): Promise<void> {
  await injectJwt(page, jwt);
  await page.goto('/p/sl_sales_order_common', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('tbody tr').filter({ hasText: 'SO-' }).first()).toBeVisible({
    timeout: 15_000,
  });
}

async function selectOrder(page: Page, code: string): Promise<void> {
  const row = page.locator('tbody tr').filter({ hasText: code }).first();
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.locator('[data-testid^="row-checkbox-"]').check();
  await expect(page.getByTestId('bulk-clear-selection-btn')).toBeVisible();
}

async function listOrders(jwt: string): Promise<any[]> {
  const result = await api(jwt, `/api/dynamic/sl_sales_order_common/list?pageNum=1&pageSize=100`);
  expect(result.ok, `list orders: ${JSON.stringify(result.body).slice(0, 240)}`).toBe(true);
  return result.body?.data?.records ?? [];
}

let adminJwt = '';
let accountPid = '';
let updateA: Order;
let updateB: Order;
let deleteA: Order;
let deleteB: Order;

test.beforeAll(async () => {
  adminJwt = await loginJwt();

  const account = await api(adminJwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: {
      crm_acc_name: `${RUN_ID} bulk customer`,
      crm_acc_industry: 'technology',
      crm_acc_status: 'active',
    },
    operationType: 'create',
  });
  accountPid = String(
    account.body?.data?.data?.recordPid ??
      account.body?.data?.data?.pid ??
      account.body?.data?.data?.recordId ??
      '',
  );
  expect(
    account.ok && Boolean(accountPid),
    `create account: ${JSON.stringify(account.body).slice(0, 240)}`,
  ).toBe(true);

  async function createOrder(tag: string): Promise<Order> {
    const result = await api(adminJwt, '/api/meta/commands/execute/sl:create_sales_order', 'POST', {
      payload: {
        sl_so_account_id: accountPid,
        sl_so_date: '2026-09-20',
        sl_so_delivery_date: '2026-09-25',
      },
      operationType: 'create',
    });
    const pid = String(
      result.body?.data?.data?.recordPid ??
        result.body?.data?.data?.pid ??
        result.body?.data?.data?.recordId ??
        '',
    );
    expect(
      result.ok && Boolean(pid),
      `create order ${tag}: ${JSON.stringify(result.body).slice(0, 240)}`,
    ).toBe(true);
    const detail = await api(adminJwt, `/api/dynamic/sl_sales_order_common/${pid}`);
    const code = String(detail.body?.data?.sl_so_code ?? '');
    expect(detail.ok, `read order ${tag}: ${JSON.stringify(detail.body).slice(0, 240)}`).toBe(true);
    expect(code, 'auto-generated order code').toContain('SO-');
    return { pid, code };
  }

  updateA = await createOrder('update A');
  updateB = await createOrder('update B');
  deleteA = await createOrder('delete A');
  deleteB = await createOrder('delete B');
});

test('PAR-24 order bulk remark update executes the guarded update command', async ({ page }) => {
  test.setTimeout(300_000);

  await openOrders(page, adminJwt);
  await selectOrder(page, updateA.code);
  await selectOrder(page, updateB.code);
  await shot(page, '01-before-command-bulk-update');

  await page.getByTestId('bulk-action-bulk_update_order_remark').click();
  const dialog = page.getByTestId('bulk-field-command-dialog');
  await expect(dialog).toBeVisible();
  await page.locator('#sl_so_remark').fill(`${RUN_ID} bulk remark`);
  await shot(page, '02-command-bulk-update-form');
  await page.getByTestId('bulk-field-command-submit').click();
  await expect(dialog).toBeHidden({ timeout: 15_000 });

  const records = await listOrders(adminJwt);
  const remarkByPid = new Map(
    records.map((record) => [String(record.pid), String(record.sl_so_remark ?? '')]),
  );
  expect(remarkByPid.get(updateA.pid)).toBe(`${RUN_ID} bulk remark`);
  expect(remarkByPid.get(updateB.pid)).toBe(`${RUN_ID} bulk remark`);
  expect(remarkByPid.get(deleteA.pid)).toBe('');
  expect(remarkByPid.get(deleteB.pid)).toBe('');
  await shot(page, '03-command-bulk-update-complete');
});

test('PAR-24 order selected export downloads exactly selected records', async ({ page }) => {
  test.setTimeout(300_000);

  await openOrders(page, adminJwt);
  await selectOrder(page, deleteA.code);
  await selectOrder(page, deleteB.code);
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
  const rows = XLSX.utils.sheet_to_json(workbook.Sheets[workbook.SheetNames[0]], {
    defval: '',
  }) as Array<Record<string, unknown>>;
  expect(rows.map((row) => String(row.pid)).sort()).toEqual([deleteA.pid, deleteB.pid].sort());
  await shot(page, '05-selected-export-complete');
});

test('PAR-24 order bulk delete executes guarded command per selected row', async ({ page }) => {
  test.setTimeout(300_000);

  await openOrders(page, adminJwt);
  await selectOrder(page, deleteA.code);
  await selectOrder(page, deleteB.code);
  await shot(page, '06-before-command-bulk-delete');

  await page.getByTestId('bulk-more-actions-btn').click();
  await page.getByTestId('bulk-action-bulk_delete_draft_orders').click();
  const confirm = page.getByTestId('confirm-dialog');
  await expect(confirm).toBeVisible();
  await page.getByTestId('confirm-ok').click();

  await expect(page.locator('tbody tr').filter({ hasText: deleteA.code })).toHaveCount(0, {
    timeout: 15_000,
  });
  await expect(page.locator('tbody tr').filter({ hasText: deleteB.code })).toHaveCount(0, {
    timeout: 15_000,
  });

  const survivorIds = (await listOrders(adminJwt)).map((record) => String(record.pid));
  expect(survivorIds).not.toContain(deleteA.pid);
  expect(survivorIds).not.toContain(deleteB.pid);
  expect(survivorIds).toContain(updateA.pid);
  expect(survivorIds).toContain(updateB.pid);
  await shot(page, '07-command-bulk-delete-complete');
});
