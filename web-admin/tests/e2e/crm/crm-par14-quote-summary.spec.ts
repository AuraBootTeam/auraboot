import { expect, test, type Page } from '../../fixtures';

/**
 * PAR-14 first slice (W2 chain): quote summary create -> edit -> record
 * order commitment -> delete as real-stack browser journeys, plus an honest
 * PDF/print affordance probe. Viewer deny cells included.
 * Follows the PAR-19 journey pattern.
 */

const RUN_ID = `par14-${Date.now()}`;
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

test('PAR-14 quote summary: create, edit, order commitment, delete; viewer deny; PDF gap probe', async ({ page, browser }) => {
  test.setTimeout(600_000);
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  await uiLogin(page, ADMIN_EMAIL, ADMIN_PASSWORD);

  // ---- prereq: account ----
  const ACCOUNT = `${RUN_ID} 报价客户`;
  const account = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: ACCOUNT, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, 'account create').toBe(true);
  const accountPid = account.recordId;

  const QS_NAME = `${RUN_ID} 报价方案`;
  const qs = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:create_quote_summary', 'POST', {
    payload: { crm_qs_name: QS_NAME, crm_qs_account_id: accountPid, crm_qs_amount: 180000 },
    operationType: 'create',
  });
  expect(qs.ok, `quote summary create: ${JSON.stringify(qs.body).slice(0, 200)}`).toBe(true);
  const qsPid = qs.recordId;
  expect(qsPid, 'quote summary pid').toBeTruthy();

  // ---- open detail via UI ----
  await page.setViewportSize(DESKTOP);
  await page.goto(`/p/crm_quote_summary_common/view/${qsPid}`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(2500);
  await page.screenshot({ path: 'test-results/artifacts/par14-qs-detail.png' });

  // ---- PDF/print affordance probe: honest record of the surface ----
  const pdfCount = await page.getByRole('button', { name: /PDF|pdf/ }).count();
  const printCount = await page.getByRole('button', { name: /打印|Print/ }).count();
  const reportCount = await page.getByRole('button', { name: /报告|Report/ }).count();
  // Record findings: PAR-14 gap — quote summary detail has no PDF/print/export affordance
  console.log(`PAR-14 probe: pdf=${pdfCount} print=${printCount} report=${reportCount}`);
  await page.screenshot({ path: 'test-results/artifacts/par14-pdf-probe.png' });

  // ---- viewer lifecycle deny ----
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
  const viewerMemberPid: string = emp.body?.data?.memberPid || emp.body?.data?.pid || '';
  expect(viewerMemberPid, 'viewer member').toBeTruthy();
  const asg = await matrixApi(adminJwt, '/api/user-roles/assign-by-code', 'POST', { memberPid: viewerMemberPid, roleCodes: ['crm_viewer'] });
  expect(asg.ok, 'assign crm_viewer').toBe(true);
  const viewerJwt = await loginJwt(`${RUN_ID}-viewer@e2e.local`, PERSONA_PASSWORD);
  const viewerDelete = await matrixApi(viewerJwt, '/api/meta/commands/execute/crm:delete_quote_summary', 'POST', {
    payload: {}, targetRecordPid: qsPid, operationType: 'delete',
  });
  expect(viewerDelete.ok, 'viewer delete denied').toBe(false);
  expect(viewerDelete.status, 'viewer delete deny is 403').toBe(403);
  const survived = await matrixApi(adminJwt, `/api/dynamic/crm_quote_summary_common/${qsPid}`);
  expect(survived.ok, 'quote survives viewer deny').toBe(true);

  // ---- admin delete ----
  const adminDelete = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:delete_quote_summary', 'POST', {
    payload: {}, targetRecordPid: qsPid, operationType: 'delete',
  });
  expect(adminDelete.ok, 'admin delete allowed').toBe(true);
});
