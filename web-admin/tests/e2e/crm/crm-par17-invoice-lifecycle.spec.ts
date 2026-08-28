import { expect, test, type Page } from '../../fixtures';
import { BACKEND_URL, BASE_URL } from '../../helpers/environments';

/**
 * PAR-17 first slice (W2 chain): customer invoice create -> viewer deny 403
 * -> stats/list visibility, following the proven PAR-19 journey pattern.
 * Invoice lifecycle transitions (submit/approve/cancel) require the approval
 * workflow engine which is a separate PAR-20 scope.
 */

const RUN_ID = `par17-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';
const PERSONA_PASSWORD = 'AuraBoot2026!';

const DESKTOP = { width: 1440, height: 900 };

interface MatrixApiResult {
  ok: boolean;
  status: number;
  body: any;
  recordId: string;
}

async function loginJwt(email: string, password: string): Promise<string> {
  const resp = await fetch(`${BACKEND_URL}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
  const body: any = await resp.json().catch(() => ({}));
  expect(resp.status === 200 && Boolean(body?.data?.jwt), `login ${email}: ${JSON.stringify(body).slice(0, 150)}`).toBe(true);
  return body.data.jwt;
}

async function matrixApi(jwt: string, path: string, method: 'GET' | 'POST' | 'DELETE' = 'GET', payload?: unknown): Promise<MatrixApiResult> {
  const resp = await fetch(`${BACKEND_URL}${path}`, {
    method,
    headers: { Authorization: `Bearer ${jwt}`, 'Content-Type': 'application/json' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
  const body: any = await resp.json().catch(() => null);
  const recordId: string = body?.data?.data?.recordPid ?? body?.data?.data?.recordId ?? body?.data?.data?.pid ?? '';
  return { ok: resp.ok && body?.code === '0', status: resp.status, body, recordId };
}

async function uiLogin(page: Page, email: string, password: string): Promise<void> {
  const response = await page.request.post(`${BASE_URL}/login`, {
    form: { email, password, remember: 'on', redirectTo: '/' },
    maxRedirects: 0,
  });
  expect([302, 303], `BFF login for ${email}`).toContain(response.status());
  await page.goto(`${BASE_URL}/`, { waitUntil: 'load' });
  if (page.url().includes('tenant-selection')) {
    await page.getByRole('button', { name: /进入|选择|Enter|AuraBoot/ }).first().click();
    await page.waitForURL((url) => !url.pathname.includes('tenant-selection'));
  }
}

test('PAR-17 customer invoice: create, stats list, viewer create deny', async ({ page, browser }) => {
  test.setTimeout(600_000);
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  await uiLogin(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // ---- prereq: account ----
  const ACCOUNT = `${RUN_ID} 发票客户`;
  const account = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: ACCOUNT, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, 'account create').toBe(true);
  const accountPid = account.recordId;

  // prereq: create product + sales order + line (invoices require an order with amount > 0)
  const product = await matrixApi(adminJwt, '/api/meta/commands/execute/prod:create_product', 'POST', {
    payload: { prod_name: `${RUN_ID} 商品`, prod_unit: '台', prod_type: 'raw_material', prod_base_price: 100 },
    operationType: 'create',
  });
  expect(product.ok, 'product create').toBe(true);
  const productPid = product.recordId;

  const order = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_sales_order', 'POST', {
    payload: { sl_so_account_id: accountPid, sl_so_date: '2026-09-28', sl_so_delivery_date: '2026-10-15' },
    operationType: 'create',
  });
  expect(order.ok, `order create: ${JSON.stringify(order.body).slice(0, 200)}`).toBe(true);
  const orderPid = order.recordId;

  // add product line to the order (gives it a non-zero amount)
  const addLine = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:add_so_line', 'POST', {
    payload: { sl_sol_order_id: orderPid, sl_sol_product_id: productPid, sl_sol_qty: 5, sl_sol_price: 100 },
    operationType: 'create',
  });
  expect(addLine.ok, `add line: ${JSON.stringify(addLine.body).slice(0, 200)}`).toBe(true);

  // ---- create invoice via API (UI form has complex select interactions) ----
  const createInv = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_customer_invoice', 'POST', {
    payload: {
      sl_inv_account_id: accountPid,
      sl_inv_order_id: orderPid,
      sl_inv_issue_date: '2026-09-28',
      sl_inv_due_date: '2026-10-28',
      sl_inv_amount: 400,
      sl_inv_currency_code: 'CNY',
    },
    operationType: 'create',
  });
  expect(createInv.ok, `invoice create: HTTP ${createInv.status} ${JSON.stringify(createInv.body).slice(0, 200)}`).toBe(true);
  const qsPid = createInv.recordId;

  // ---- issue the invoice (提交开票) ----
  // get the invoice pid from the list API
  const pidResp = await fetch(`${BACKEND_URL}/api/dynamic/sl_customer_invoice_common/list?pageNum=1&pageSize=10`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const pidBody: any = await pidResp.json().catch(() => null);
  console.log('INVOICE_LIST:', JSON.stringify(pidBody?.data?.records?.map((r: any) => ({pid: r.pid, name: r.sl_inv_name || r.sl_inv_code || r.sl_inv_amount})).slice(0, 5)));
  const invRow = (pidBody?.data?.records ?? [])[0];
  expect(invRow, 'invoice record in list after save').toBeTruthy();
  const invoicePid = String(invRow.pid);
  const issueResp = await fetch(`${BACKEND_URL}/api/meta/commands/execute/sl:issue_customer_invoice`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${adminJwt}`, 'Content-Type': 'application/json' },
    body: JSON.stringify({ payload: {}, targetRecordPid: invoicePid, operationType: 'update' }),
  });
  const issueBody: any = await issueResp.json().catch(() => ({}));
  expect(issueResp.ok, `issue: HTTP ${issueResp.status} ${JSON.stringify(issueBody).slice(0, 200)}`).toBe(true);
  await page.goto('/p/sl_customer_invoice_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);
  await page.screenshot({ path: 'test-results/artifacts/par17-invoice-issued.png' });

  // ---- list stats + screenshots ----
  await page.goto('/p/sl_customer_invoice_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);
  await page.screenshot({ path: 'test-results/artifacts/par17-invoice-list.png' });
  const listResp = await fetch(`${BACKEND_URL}/api/dynamic/sl_customer_invoice_common/list?pageNum=1&pageSize=50`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const invRows: any[] = (await listResp.json().catch(() => ({})))?.data?.records ?? [];
  expect(invRows.length, 'invoice created and visible in list').toBeGreaterThan(0);

  // ---- viewer create deny ----
  const dept = await matrixApi(adminJwt, '/api/meta/commands/execute/org:create_department', 'POST', {
    payload: { org_dept_name: `${RUN_ID} 只读部门`, org_dept_order: 30, org_dept_status: 'active' },
    operationType: 'create',
  });
  const pos = await matrixApi(adminJwt, '/api/meta/commands/execute/org:create_position', 'POST', {
    payload: { org_pos_code: `${RUN_ID}-V`, org_pos_name: `${RUN_ID} 只读岗`, org_pos_dept_id: dept.recordId, org_pos_level: 'staff', org_pos_status: 'active' },
    operationType: 'create',
  });
  const emp = await matrixApi(adminJwt, '/api/org/employees', 'POST', {
    name: `${RUN_ID} 只读用户`, email: `${RUN_ID}-viewer@e2e.local`, phone: `135${Math.floor(10000000 + Math.random() * 89999999)}`, deptPid: dept.recordId, positionPid: pos.recordId,
  });
  const memberPid: string = emp.body?.data?.memberPid || emp.body?.data?.pid || '';
  expect(memberPid, 'viewer member').toBeTruthy();
  const asg = await matrixApi(adminJwt, '/api/user-roles/assign-by-code', 'POST', { memberPid, roleCodes: ['crm_viewer'] });
  expect(asg.ok, 'assign crm_viewer').toBe(true);
  const viewerJwt = await loginJwt(`${RUN_ID}-viewer@e2e.local`, PERSONA_PASSWORD);
  const viewerCreate = await matrixApi(viewerJwt, '/api/meta/commands/execute/sl:create_customer_invoice', 'POST', {
    payload: { sl_inv_account_id: accountPid, sl_inv_amount: 1 },
    operationType: 'create',
  });
  expect(viewerCreate.ok, 'viewer invoice create denied').toBe(false);
  expect(viewerCreate.status, 'viewer create deny is 403').toBe(403);
  await page.screenshot({ path: 'test-results/artifacts/par17-viewer-deny.png' });
});
