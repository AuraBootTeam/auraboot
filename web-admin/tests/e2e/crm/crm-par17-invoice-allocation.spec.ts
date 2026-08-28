import { expect, test } from '../../fixtures';
import { BACKEND_URL } from '../../helpers/environments';

/**
 * PAR-17 receivables backend chain: invoice → collection → allocation →
 * post → reverse. API-level integration journey; the browser entry evidence
 * lives in crm-par17-invoice-lifecycle.spec.ts.
 */

const RUN_ID = `par17alc-${Date.now()}`;
const ADMIN_EMAIL = 'admin@auraboot.com';
const ADMIN_PASSWORD = 'Test2026x';

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
  expect(resp.status === 200 && Boolean(body?.data?.jwt), `login ${email}`).toBe(true);
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

test('PAR-17 invoice allocation lifecycle: collection → allocation → post → reverse', async () => {
  test.setTimeout(600_000);
  const adminJwt = await loginJwt(ADMIN_EMAIL, ADMIN_PASSWORD);
  const meResp = await fetch(`${BACKEND_URL}/api/auth/me`, {
    headers: { Authorization: `Bearer ${adminJwt}` },
  });
  const meBody: any = await meResp.json().catch(() => null);
  const adminPid = String(meBody?.data?.user?.pid ?? '');
  expect(adminPid, 'admin pid from /api/auth/me').toBeTruthy();

  // ---- prereq: account + effective contract + order with line + invoice ----
  const ACCOUNT = `${RUN_ID} 核销客户`;
  const account = await matrixApi(adminJwt, '/api/meta/commands/execute/crm:create_account', 'POST', {
    payload: { crm_acc_name: ACCOUNT, crm_acc_industry: 'tech' },
    operationType: 'create',
  });
  expect(account.ok, 'account create').toBe(true);
  const accountPid = account.recordId;

  const contract = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_sales_contract', 'POST', {
    payload: {
      sl_ctr_name: `${RUN_ID} 核销合同`,
      sl_ctr_account_id: accountPid,
      sl_ctr_start_date: '2026-09-01',
      sl_ctr_end_date: '2027-08-31',
      sl_ctr_amount: 50000,
      sl_ctr_currency_code: 'CNY',
      sl_ctr_owner: adminPid,
    },
    operationType: 'create',
  });
  expect(contract.ok, `contract create: ${JSON.stringify(contract.body).slice(0, 200)}`).toBe(true);
  const contractPid = contract.recordId;
  for (const code of ['sl:submit_sales_contract', 'sl:approve_sales_contract']) {
    const step = await matrixApi(adminJwt, `/api/meta/commands/execute/${code}`, 'POST', {
      payload: {}, targetRecordPid: contractPid, operationType: 'update',
    });
    expect(step.ok, `${code}: ${JSON.stringify(step.body).slice(0, 200)}`).toBe(true);
  }

  // contract approval auto-creates + links the draft fulfillment order
  const approved = await matrixApi(adminJwt, `/api/dynamic/sl_sales_contract_common/${contractPid}`);
  expect(approved.ok, 'approved contract readable').toBe(true);
  const orderPid = String(approved.body?.data?.sl_ctr_order_id ?? '');
  expect(orderPid, 'fulfillment order linked at approval').toBeTruthy();

  const invoice = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_customer_invoice', 'POST', {
    payload: {
      sl_inv_account_id: accountPid,
      sl_inv_order_id: orderPid,
      sl_inv_issue_date: '2026-09-28',
      sl_inv_due_date: '2026-10-28',
      sl_inv_amount: 1000,
      sl_inv_currency_code: 'CNY',
    },
    operationType: 'create',
  });
  expect(invoice.ok, `invoice create: ${JSON.stringify(invoice.body).slice(0, 200)}`).toBe(true);
  const invoicePid = invoice.recordId;

  // issue the invoice
  const issue = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:issue_customer_invoice', 'POST', {
    payload: {}, targetRecordPid: invoicePid, operationType: 'update',
  });
  expect(issue.ok, `invoice issue: ${JSON.stringify(issue.body).slice(0, 200)}`).toBe(true);

  // ---- collection (收款) linked to the invoice ----
  const collection = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_sales_collection', 'POST', {
    payload: {
      sl_col_order_id: orderPid,
      sl_col_contract_id: contractPid,
      sl_col_date: '2026-09-28',
      sl_col_amount: 1000,
      sl_col_method: 'bank_transfer',
    },
    operationType: 'create',
  });
  expect(collection.ok, `collection create: ${JSON.stringify(collection.body).slice(0, 200)}`).toBe(true);
  const collectionPid = collection.recordId;

  // confirm the collection
  const confirmCol = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:confirm_sales_collection', 'POST', {
    payload: {}, targetRecordPid: collectionPid, operationType: 'update',
  });
  expect(confirmCol.ok, `collection confirm: ${JSON.stringify(confirmCol.body).slice(0, 200)}`).toBe(true);

  // ---- allocation (核销): link collection to invoice ----
  const allocation = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:create_invoice_allocation', 'POST', {
    payload: {
      sl_ia_invoice_id: invoicePid,
      sl_ia_collection_id: collectionPid,
      sl_ia_date: '2026-09-28',
      sl_ia_amount: 1000,
    },
    operationType: 'create',
  });
  expect(allocation.ok, `allocation create: ${JSON.stringify(allocation.body).slice(0, 200)}`).toBe(true);

  // ---- post the allocation ----
  const post = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:post_invoice_allocation', 'POST', {
    payload: {}, targetRecordPid: allocation.recordId, operationType: 'update',
  });
  expect(post.ok, 'allocation post').toBe(true);

  // ---- reverse (红冲) ----
  const reverse = await matrixApi(adminJwt, '/api/meta/commands/execute/sl:reverse_invoice_allocation', 'POST', {
    payload: {}, targetRecordPid: allocation.recordId, operationType: 'update',
  });
  expect(reverse.ok, 'allocation reverse').toBe(true);
});
