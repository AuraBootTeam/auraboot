/** Deep NamedQuery contracts against owned business records and the real database. */
import { test, expect } from '../../fixtures';
import type { APIRequestContext, APIResponse, Page, Response, TestInfo } from '@playwright/test';
import { NamedQueryPage } from '../../pages/NamedQueryPage';

type Query = { pid: string; code: string };
const suffix = `nqd_${Date.now().toString(36)}`;
const customerName = `NamedQuery customer ${suffix}`;
const titles = [`${suffix}_first`, `${suffix}_second`];

async function success(response: APIResponse | Response) {
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(String(body.code)).toBe('0');
  return body.data;
}

async function status(request: APIRequestContext, query: Query, next: string) {
  const result = await success(await request.put(`/api/meta/named-queries/${query.pid}/status`, {
    data: { status: next },
  }));
  expect(result).toMatchObject({ pid: query.pid, code: query.code, status: next });
}

async function createThroughUi(page: Page, code: string, sql: string): Promise<Query> {
  const nq = new NamedQueryPage(page);
  await nq.gotoNew();
  await nq.fillCreateForm(code, code, 'Owned business data; no metadata-table exemption', sql);
  const result = await success(await nq.submitCreate());
  expect(result).toMatchObject({ code, status: 'draft' });
  expect(result.pid).toMatch(/^[0-9A-Z]{26}$/);
  await expect(page).toHaveURL(new RegExp(`/meta/named-queries/${result.pid}(?:[?#]|$)`));
  return { pid: result.pid, code };
}

async function tab(page: Page, query: Query, name: 'basic' | 'fields' | 'test' | 'policy' | 'versions') {
  const nq = new NamedQueryPage(page);
  await nq.gotoEdit(query.pid);
  // Monaco is created by this route's client effect, unlike its SSR tab buttons.
  await expect(page.locator('.monaco-editor').first()).toBeVisible();
  await nq.clickTab(name);
  return nq;
}

async function capture(page: Page, info: TestInfo, name: string) {
  await expect(page.getByText('Page Unavailable', { exact: true })).toHaveCount(0);
  await page.screenshot({ path: info.outputPath(`${name}.png`), fullPage: true });
}

test.describe('Named Query Deep', () => {
  test.describe.configure({ mode: 'serial', timeout: 30000 });
  let base: Query;
  let joinSql: string;
  let simpleSql: string;
  const orderPids: string[] = [];

  test.beforeAll(async ({ request }) => {
    const customer = await success(await request.post('/api/dynamic/e2et_customer/create', {
      data: { e2et_cust_code: suffix, e2et_cust_name: customerName, e2et_cust_region: 'north' },
    }));
    expect(customer.pid).toMatch(/^[0-9A-Z]{26}$/);
    for (const [index, amount] of [11, 29].entries()) {
      const row = await success(await request.post('/api/dynamic/e2et_order/create', {
        data: { e2et_order_title: titles[index], e2et_order_type: 'normal',
          e2et_order_status: 'draft', e2et_order_urgent: false,
          e2et_order_customer: customer.pid, e2et_order_amount: amount },
      }));
      expect(row.pid).toMatch(/^[0-9A-Z]{26}$/);
      orderPids.push(row.pid);
    }
    const ids = orderPids.map(pid => `'${pid}'`).join(',');
    simpleSql = `SELECT pid, e2et_order_title AS title FROM mt_e2et_order WHERE pid IN (${ids})`;
    joinSql = `SELECT o.pid, o.e2et_order_title AS title, o.e2et_order_amount AS amount, c.e2et_cust_name AS customer_name FROM mt_e2et_order o JOIN mt_e2et_customer c ON o.e2et_order_customer = c.pid WHERE o.pid IN (${ids})`;
  });

  test('NQ-D01: Create named query with multi-table JOIN', async ({ page }, info) => {
    base = await createThroughUi(page, `${suffix}_join`, joinSql);
    const result = await success(await page.request.post(`/api/meta/named-queries/${base.pid}/test`, {
      data: { page: 1, size: 5 },
    }));
    expect(result.success).toBe(true);
    expect(result.sampleData).toHaveLength(2);
    expect(result.sampleData).toEqual(expect.arrayContaining(orderPids.map((pid, index) =>
      expect.objectContaining({ pid, title: titles[index], customer_name: customerName, amount: [11, 29][index] }))));
    await capture(page, info, 'NQ-D01-owned-join');
  });

  test('NQ-D02: Aggregate functions SUM/COUNT/AVG', async ({ page }, info) => {
    const sql = `SELECT COUNT(*) AS total_count, SUM(e2et_order_amount) AS total_amount, AVG(e2et_order_amount) AS average_amount FROM mt_e2et_order WHERE pid IN (${orderPids.map(pid => `'${pid}'`).join(',')})`;
    const aggregate = await createThroughUi(page, `${suffix}_aggregate`, sql);
    const result = await success(await page.request.post(`/api/meta/named-queries/${aggregate.pid}/test`, {
      data: { page: 1, size: 5 },
    }));
    expect(result.success).toBe(true);
    expect(result.sampleData).toHaveLength(1);
    expect(result.sampleData[0]).toMatchObject({ total_count: 2, total_amount: 40, average_amount: 20 });
    await tab(page, aggregate, 'test');
    await capture(page, info, 'NQ-D02-aggregate');
  });

  test('NQ-D03: Parameterized query with search fields', async ({ page }, info) => {
    for (const field of ['pid', 'title']) {
      const added = await success(await page.request.post(`/api/meta/named-queries/${base.code}/fields`, {
        data: { fieldCode: field, columnExpr: field, dataType: 'string',
          operators: ['eq'], searchable: true, sortable: true },
      }));
      expect(added).toMatchObject({ fieldCode: field, columnExpr: field, searchable: true });
    }
    await status(page.request, base, 'testing');
    await status(page.request, base, 'published');
    const result = await success(await page.request.post(`/api/meta/named-queries/${base.code}/execute`, {
      data: { whereConditions: [{ field: 'title', operator: 'eq', value: titles[0] }], page: 1, size: 5 },
    }));
    expect(result.total).toBe(1);
    expect(result.records).toEqual([{ pid: orderPids[0], title: titles[0] }]);
    await tab(page, base, 'fields');
    await expect(page.locator('table tbody tr').filter({ hasText: 'title' })).toHaveCount(1);
    await capture(page, info, 'NQ-D03-searchable-fields');
  });

  test('NQ-D04: DataSource nq: prefix reference', async ({ page }, info) => {
    const query = await createThroughUi(page, `${suffix}_datasource`, simpleSql);
    for (const field of ['pid', 'title']) {
      await success(await page.request.post(`/api/meta/named-queries/${query.code}/fields`, {
        data: { fieldCode: field, columnExpr: field, dataType: 'string', searchable: true, sortable: true },
      }));
    }
    await status(page.request, query, 'testing');
    await status(page.request, query, 'published');
    const options = await success(await page.request.get('/api/datasource/list', {
      params: { datasourceId: `nq:${query.code}`, valueField: 'pid', labelField: 'title', maxItems: 20 },
    }));
    expect(options).toHaveLength(2);
    expect(options).toEqual(expect.arrayContaining(orderPids.map((value, index) =>
      expect.objectContaining({ value, label: titles[index] }))));
    await tab(page, query, 'basic');
    await capture(page, info, 'NQ-D04-published-datasource');
  });

  test('NQ-D05: Editor test execution', async ({ page }, info) => {
    await tab(page, base, 'test');
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/meta/named-queries/${base.pid}/test`
      && r.request().method() === 'POST');
    await page.getByRole('button', { name: '执行查询', exact: true }).click();
    const result = await success(await response);
    expect(result.success).toBe(true);
    expect(result.sampleData).toHaveLength(2);
    expect(result.sampleData.map((r: { pid: string }) => r.pid).sort()).toEqual([...orderPids].sort());
    for (const title of titles) await expect(page.locator('table tbody tr').filter({ hasText: title })).toBeVisible();
    await capture(page, info, 'NQ-D05-exact-ui-results');
  });

  test('NQ-D06: Version history displays entries', async ({ page }, info) => {
    const versions = await success(await page.request.get(`/api/meta/named-queries/${base.code}/versions`));
    expect(versions).toHaveLength(1);
    expect(versions[0].versionNo).toBe(1);
    await tab(page, base, 'versions');
    await expect(page.getByText('v1', { exact: true }).first()).toBeVisible();
    await capture(page, info, 'NQ-D06-version-one');
  });

  test('NQ-D07: Strategy config via policy tab', async ({ page }, info) => {
    const nq = await tab(page, base, 'policy');
    await expect(nq.policyMaxRows).toHaveValue('5000');
    await nq.policyMaxRows.fill('2000');
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/meta/named-queries/${base.pid}`
      && r.request().method() === 'PUT');
    await nq.savePolicyButton.click();
    expect((await success(await response)).policy.maxRows).toBe(2000);
    await page.reload();
    await expect(nq.policyMaxRows).toHaveValue('2000');
    expect((await success(await page.request.get(`/api/meta/named-queries/${base.pid}`))).policy.maxRows).toBe(2000);
    await capture(page, info, 'NQ-D07-policy-reloaded');
  });

  test('NQ-D08: Status flow transitions', async ({ page }, info) => {
    await status(page.request, base, 'deprecated');
    await tab(page, base, 'basic');
    await expect(page.getByText('已废弃', { exact: true }).first()).toBeVisible();
    await capture(page, info, 'NQ-D08-deprecated');
  });

  test('NQ-D09: Field management in fields tab', async ({ page }, info) => {
    await status(page.request, base, 'archived');
    await status(page.request, base, 'draft');
    await tab(page, base, 'fields');
    await page.getByRole('button', { name: '添加字段', exact: true }).click();
    await page.getByPlaceholder('e.g. user_name', { exact: true }).fill('customer_name');
    await page.getByPlaceholder('e.g. u.user_name', { exact: true }).fill('customer_name');
    const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/meta/named-queries/${base.code}/fields`
      && r.request().method() === 'POST');
    await page.getByRole('button', { name: '添加', exact: true }).click();
    expect(await success(await response)).toMatchObject({ fieldCode: 'customer_name', columnExpr: 'customer_name' });
    await page.reload();
    await expect(page.locator('table tbody tr').filter({ hasText: 'customer_name' })).toHaveCount(1);
    const fields = await success(await page.request.get(`/api/meta/named-queries/${base.code}/fields`));
    expect(fields.map((f: { fieldCode: string }) => f.fieldCode).sort()).toEqual(['customer_name', 'pid', 'title']);
    await capture(page, info, 'NQ-D09-ui-added-field');
  });

  test('NQ-D10: SQL injection protection', async ({ page }, info) => {
    await status(page.request, base, 'testing');
    await status(page.request, base, 'published');
    const execute = (value: string) => page.request.post(`/api/meta/named-queries/${base.code}/execute`, {
      data: { whereConditions: [{ field: 'title', operator: 'eq', value }], page: 1, size: 5 },
    });
    const positive = await success(await execute(titles[0]));
    expect(positive.total).toBe(1);
    expect(positive.records[0]).toMatchObject({ pid: orderPids[0], title: titles[0], customer_name: customerName });
    const injected = await success(await execute(`${titles[0]}' OR 1=1; --`));
    expect(injected.total).toBe(0);
    expect(injected.records).toEqual([]);
    const unchanged = await success(await execute(titles[0]));
    expect(unchanged.records).toEqual(positive.records);
    await tab(page, base, 'test');
    await capture(page, info, 'NQ-D10-bound-input');
  });

  test('NQ-D11: Published state — fields frozen', async ({ page }, info) => {
    await tab(page, base, 'basic');
    await expect(page.getByText('(只读 — 查询已冻结)', { exact: true })).toBeVisible();
    const denied = await page.request.post(`/api/meta/named-queries/${base.code}/fields`, {
      data: { fieldCode: 'forbidden_field', columnExpr: 'title', dataType: 'string' },
    });
    expect(denied.status()).toBe(400);
    const fields = await success(await page.request.get(`/api/meta/named-queries/${base.code}/fields`));
    expect(fields.map((f: { fieldCode: string }) => f.fieldCode).sort()).toEqual(['customer_name', 'pid', 'title']);
    expect((await success(await page.request.get(`/api/meta/named-queries/${base.pid}`))).fromSql).toBe(joinSql);
    await capture(page, info, 'NQ-D11-frozen');
  });

  test('NQ-D12: Deprecate and archive lifecycle', async ({ page }, info) => {
    await status(page.request, base, 'deprecated');
    await status(page.request, base, 'archived');
    const nq = new NamedQueryPage(page);
    await nq.gotoList();
    await expect(page.locator('header[data-hydrated="true"]')).toBeVisible();
    await page.locator('select').first().selectOption('archived');
    await page.getByPlaceholder('输入编码或标题搜索', { exact: true }).fill(base.code);
    const filteredResponse = page.waitForResponse(r => {
      const url = new URL(r.url());
      return url.pathname === '/api/meta/named-queries' && r.request().method() === 'GET'
        && url.searchParams.get('status') === 'archived' && url.searchParams.get('keyword') === base.code;
    });
    await page.getByRole('button', { name: '搜索', exact: true }).click();
    const filtered = await success(await filteredResponse);
    expect(filtered.total).toBe(1);
    expect(filtered.records).toEqual([expect.objectContaining({ pid: base.pid, code: base.code, status: 'archived' })]);
    await expect(nq.queryTable.locator('tbody tr')).toHaveCount(1);
    const row = nq.queryTable.locator('tbody tr').filter({ hasText: base.code });
    await expect(row).toBeVisible();
    await expect(row).toContainText('已归档');
    expect((await success(await page.request.get(`/api/meta/named-queries/${base.pid}`))).status).toBe('archived');
    await capture(page, info, 'NQ-D12-archived');
  });
});
