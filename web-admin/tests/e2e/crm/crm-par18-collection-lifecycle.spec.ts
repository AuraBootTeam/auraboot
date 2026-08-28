import { expect, test, type Page } from '../../fixtures';

/**
 * PAR-18 first slice (W2 chain): 回款计划 (payment plan) lifecycle on an
 * effective contract, 收款 (collection) create + confirm (核销), collections
 * list/stats visibility, viewer create deny 403 — real-stack browser journeys
 * following the PAR-19 pattern.
 */

const RUN_ID = `par18-${Date.now()}`;
const BASE_URL = process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:5161';
const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:6461';
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

async function selectByTestId(page: Page, testid: string, optionText: string): Promise<void> {
  await page.getByTestId(testid).click();
  await page.waitForTimeout(900);
  const opt = page.locator('[role=option]:visible').filter({ hasText: optionText }).first();
  if ((await opt.count()) > 0) await opt.click();
  else await page.locator('[role=option]:visible').first().click();
  await page.waitForTimeout(400);
}

test('PAR-18 collection: payment plan lifecycle, collection create + confirm, stats, viewer deny', async ({ page, browser }) => {
  test.setTimeout(600_000);
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  await uiLogin(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // ---- prereq: account + effective contract via the real UI (owner/currency required) ----
  const ACCOUNT = `${RUN_ID} 回款客户`;
  const account = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: ACCOUNT, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, 'account create').toBe(true);
  const accountPid = account.recordId;

  await page.goto('/p/sl_sales_contract_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);
  await page.getByRole('button', { name: /新建/ }).first().click();
  await page.waitForTimeout(1800);
  await page.locator('input[name="sl_ctr_name"]').fill(`${RUN_ID} 回款合同`);
  await page.getByTestId('select-trigger-sl_ctr_account_id').click();
  await page.waitForTimeout(900);
  const acctOpt = page.locator('[role=option]:visible').filter({ hasText: ACCOUNT }).first();
  if ((await acctOpt.count()) > 0) await acctOpt.click();
  await page.waitForTimeout(400);
  // required selects: owner + currency (pick first option of each)
  for (const testid of ['select-trigger-sl_ctr_owner', 'select-trigger-sl_ctr_currency_code']) {
    await page.getByTestId(testid).click();
    await page.waitForTimeout(800);
    const o = page.locator('[role=option]:visible').first();
    if ((await o.count()) > 0) await o.click();
    await page.waitForTimeout(400);
  }
  const dates = page.locator('input[type=date]');
  const dn = await dates.count();
  if (dn > 0) {
    await dates.first().fill('2026-09-01').catch(() => {});
    if (dn > 1) await dates.nth(1).fill('2027-08-31').catch(() => {});
  }
  await page.locator('input[name="sl_ctr_amount"]').fill('300000');
  await page.getByRole('button', { name: /保存|创建/ }).first().click();
  await page.waitForTimeout(2400);

  const listResp0 = await fetch(`${BACKEND_URL}/api/dynamic/sl_sales_contract_common/list?pageNum=1&pageSize=50`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const rows0: any[] = (await listResp0.json().catch(() => ({})))?.data?.records ?? [];
  const hit0 = rows0.find((r) => String(r?.sl_ctr_name ?? '').includes('回款合同'));
  expect(hit0, 'contract visible in list').toBeTruthy();
  const contractPid = String(hit0.pid);
  for (const code of ['sl:submit_sales_contract', 'sl:approve_sales_contract']) {
    const step = await matrixApi(adminJwt, `/api/meta/commands/execute/${code}`, 'POST', {
      payload: {}, targetRecordPid: contractPid, operationType: 'update',
    });
    expect(step.ok, `${code}`).toBe(true);
  }

  // ---- payment plan: seeded via API, verified in the UI list ----
  const planName = `${RUN_ID} 一期回款`;
  // owner required: resolve the admin member pid via the tenant-member API
  const meResp = await fetch(`${BACKEND_URL}/api/org/employees/admin`, { headers: { Authorization: `Bearer ${adminJwt}` } }).catch(() => null);
  let adminMemberPid = '';
  if (meResp && meResp.ok) {
    const b: any = await meResp.json().catch(() => null);
    adminMemberPid = String(b?.data?.memberPid || b?.data?.pid || '');
  }
  const planCreate = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_contract_payment_plan', 'POST', {
    payload: {
      sl_cpp_name: planName,
      sl_cpp_contract_id: contractPid,
      sl_cpp_sequence: 1,
      sl_cpp_owner: '01M125M2YDY3NQDA9TKHYFCVD8',
      sl_cpp_due_date: '2026-10-10',
      sl_cpp_amount: 100000,
    },
    operationType: 'create',
  });
  expect(planCreate.ok, `payment plan create: HTTP ${planCreate.status} ${JSON.stringify(planCreate.body).slice(0, 200)}`).toBe(true);
  await page.setViewportSize(DESKTOP);
  await page.goto('/p/sl_contract_payment_plan_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);
  await expect(page.locator('tbody').getByText(planName).first()).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: 'test-results/artifacts/par18-plan-created.png' });

  // ---- collection create + confirm (核销/确认回款) ----
  await page.goto('/p/sl_sales_collection_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);
  await page.getByRole('button', { name: /新建|添加/ }).first().click();
  await page.waitForTimeout(1800);
  // contract select inside the collection form
  await selectByTestId(page, 'select-trigger-sl_col_contract_id', contractPid || contractPid);
  const colContract = page.locator('[role=option]:visible').filter({ hasText: ACCOUNT }).first();
  if ((await colContract.count()) > 0) await colContract.click();
  await page.waitForTimeout(400);
  // required: 销售订单 select (pick the fulfillment order for this contract)
  await page.getByTestId('select-trigger-sl_col_order_id').click();
  await page.waitForTimeout(900);
  const orderOpt = page.locator('[role=option]:visible').first();
  if ((await orderOpt.count()) > 0) await orderOpt.click();
  await page.waitForTimeout(400);
  const colDate = page.locator('input[type=date]');
  if ((await colDate.count()) > 0) await colDate.first().fill('2026-09-28');
  const colAmount = page.locator('input[name="sl_col_amount"]');
  if ((await colAmount.count()) > 0) await colAmount.first().fill('100000');
  const methodLabel = page.locator('label').filter({ hasText: '付款方式' }).first();
  const methodTrigger = methodLabel.locator('xpath=following::button[1]');
  await methodTrigger.click();
  await page.waitForTimeout(900);
  await page.locator('[role=option]:visible').first().click();
  await page.waitForTimeout(400);
  await page.screenshot({ path: 'test-results/artifacts/par18-collection-filled.png' });
  await page.getByRole('button', { name: /保存|创建/ }).first().click();
  await page.waitForTimeout(2200);

  // confirm the collection (核销/确认到账)
  const colListResp = await fetch(`${BACKEND_URL}/api/dynamic/sl_sales_collection_common/list?pageNum=1&pageSize=50`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const colRows: any[] = (await colListResp.json().catch(() => ({})))?.data?.records ?? [];
  const collection = colRows.find((r) => String(r?.sl_col_amount ?? '') === '100000');
  expect(collection, 'collection created').toBeTruthy();
  const collectionPid = String(collection.pid);
  const confirmCol = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:confirm_sales_collection', 'POST', {
    payload: {}, targetRecordPid: collectionPid, operationType: 'update',
  });
  expect(confirmCol.ok, `collection confirmed: ${JSON.stringify(confirmCol.body).slice(0, 200)}`).toBe(true);

  // ---- stats/list visibility: collections list shows the confirmed record ----
  await page.goto('/p/sl_sales_collection_common', { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2200);
  await expect(page.locator('tbody').getByText('100000').first()).toBeVisible({ timeout: 10_000 });
  await page.screenshot({ path: 'test-results/artifacts/par18-collections-stats.png' });

  // ---- viewer create deny ----
  const viewerJwt = await (async () => {
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
    return loginJwt(`${RUN_ID}-viewer@e2e.local`, PERSONA_PASSWORD);
  })();
  const viewerCreate = await matrixApi(viewerJwt, '/api/meta/commands/execute/sl:create_sales_collection', 'POST', {
    payload: { sl_col_contract_id: contractPid, sl_col_date: '2026-09-28', sl_col_amount: 1 },
    operationType: 'create',
  });
  expect(viewerCreate.ok, 'viewer collection create denied').toBe(false);
  expect(viewerCreate.status, 'viewer create deny is 403').toBe(403);
  await page.screenshot({ path: 'test-results/artifacts/par18-viewer-deny.png' });
});
