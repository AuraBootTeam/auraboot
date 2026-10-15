import { test, expect, type Page } from '@playwright/test';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const matrix = JSON.parse(readFileSync('../scripts/performance/acceptance.json', 'utf8'));
const evidence = process.env.PERF_EVIDENCE_DIR;
if (!evidence) throw new Error('PERF_EVIDENCE_DIR is required');
const model = 'showcase_all_fields';
const command = '/api/meta/commands/execute/';
const fixtureName = 'PERF baseline 00999';

test.use({
  locale: 'zh-CN',
  viewport: { width: 1440, height: 1000 },
  storageState: 'tests/storage/admin.json',
});

async function capture(page: Page, id: string) {
  const scenario = matrix.scenarios.find((row: { id: string }) => row.id === id);
  expect(scenario, `registered scenario ${id}`).toBeTruthy();
  mkdirSync(evidence!, { recursive: true });
  await expect(page.getByText('Page Unavailable', { exact: true })).toHaveCount(0);
  await page.screenshot({ path: join(evidence!, scenario.screenshot), fullPage: true });
  writeFileSync(
    join(evidence!, `${id}.json`),
    JSON.stringify(
      { ...scenario, verdict: 'executed', url: page.url(), screenshot: scenario.screenshot },
      null,
      2,
    ),
  );
}

async function menuList(page: Page) {
  const sidebar = page.getByTestId('sidebar');
  const group = sidebar.getByRole('button', { name: '字段展示', exact: true });
  if ((await group.getAttribute('aria-expanded')) !== 'true') await group.click();
  await sidebar.locator('a[href="/p/showcase_all_fields"]').click();
  await expect(page.getByTestId('dynamic-list')).toBeVisible();
}
async function search(page: Page, keyword: string) {
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/dynamic/${model}/list` &&
      new URL(r.url()).searchParams.get('keyword') === keyword &&
      r.status() === 200,
  );
  await page.getByTestId('list-search-input').fill(keyword);
  await page.getByTestId('list-search-input').press('Enter');
  const body = await (await response).json();
  expect(body.code).toBe('0');
  return body.data;
}

test('PERF journey: home, menu, search, pagination and persisted CRUD @critical', async ({
  page,
}) => {
  test.setTimeout(120_000);
  mkdirSync(evidence!, { recursive: true });
  writeFileSync(join(evidence!, 'acceptance-matrix.json'), JSON.stringify(matrix, null, 2));
  const configurationRequests: string[] = [];
  const traceIds: Record<string, string>[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === '/api/dashboards/workbench')
      configurationRequests.push(request.url());
  });
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname;
    const traceId = response.headers()['x-trace-id'];
    if (path.startsWith('/api/') && traceId) traceIds.push({ path, traceId });
  });
  await page.goto('/home');
  await expect(page.getByTestId('workbench-open-in-dashboard')).toBeVisible();
  await expect(page.locator('.react-grid-item').first()).toBeVisible();
  expect(configurationRequests).toEqual([]);
  await capture(page, 'PERF-01');

  const listResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === `/api/dynamic/${model}/list` && r.status() === 200,
  );
  await menuList(page);
  const initial = (await (await listResponse).json()).data;
  expect(initial.total).toBe(1000);
  expect(initial.records).toHaveLength(20);
  await expect(
    page.getByTestId('dynamic-list').getByText(fixtureName, { exact: true }),
  ).toBeVisible();
  await capture(page, 'PERF-02');

  const empty = await search(page, 'PERF-no-record-with-this-name');
  expect(empty.total).toBe(0);
  expect(empty.records).toEqual([]);
  await expect(
    page.getByTestId('dynamic-list').getByText(fixtureName, { exact: true }),
  ).toHaveCount(0);
  await capture(page, 'PERF-03');
  const all = await search(page, 'PERF');
  expect(all.total).toBe(1000);
  const nextResponse = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === `/api/dynamic/${model}/list` &&
      new URL(r.url()).searchParams.get('pageNum') === '2' &&
      r.status() === 200,
  );
  await page.getByRole('button', { name: '下一页', exact: true }).click();
  const next = (await (await nextResponse).json()).data;
  expect(next.records).toHaveLength(20);
  expect(
    next.records
      .map((row: { pid: string }) => row.pid)
      .filter((pid: string) => all.records.some((row: { pid: string }) => row.pid === pid)),
  ).toEqual([]);
  await capture(page, 'PERF-04');

  await page.getByTestId('toolbar-btn-create').click();
  const nameInput = page.getByTestId('form-field-sc_name').locator('input');
  await expect(nameInput).toBeVisible();
  const saves: string[] = [];
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === `${command}sc:create_showcase`)
      saves.push(request.url());
  });
  await page.getByTestId('form-btn-submit').click();
  await expect(page.getByTestId('form-error-summary')).toBeVisible();
  expect(saves).toEqual([]);
  await capture(page, 'PERF-05');

  const recordName = `PERF-UI-${test.info().testId}-${Date.now()}`;
  await nameInput.fill(recordName);
  await page.getByTestId('form-field-sc_quantity').locator('input').fill('37');
  const createdResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === `${command}sc:create_showcase` && r.status() === 200,
  );
  await page.getByTestId('form-btn-submit').click();
  const created = await (await createdResponse).json();
  expect(created.code).toBe('0');
  await menuList(page);
  const createdPage = await search(page, recordName);
  expect(createdPage.records).toHaveLength(1);
  expect(createdPage.records[0]).toMatchObject({ sc_name: recordName, sc_quantity: 37 });
  const record = createdPage.records[0];
  const row = page
    .getByTestId('dynamic-list')
    .getByRole('row')
    .filter({ has: page.getByText(recordName, { exact: true }) });
  await expect(row).toHaveCount(1);
  await expect(row).toContainText(recordName);
  await capture(page, 'PERF-06');
  await row.getByTestId('row-action-view').click();
  await expect(page.getByText(recordName, { exact: true }).first()).toBeVisible();
  expect(page.url()).toContain(record.pid);
  await capture(page, 'PERF-07');
  await menuList(page);
  await search(page, recordName);
  await row.getByTestId('row-action-edit').click();
  await expect(nameInput).toHaveValue(recordName);
  await capture(page, 'PERF-08');
  const editedName = `${recordName}-edited`;
  await nameInput.fill(editedName);
  const updatedResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === `${command}sc:update_showcase` && r.status() === 200,
  );
  await page.getByTestId('form-btn-submit').click();
  expect((await (await updatedResponse).json()).code).toBe('0');
  await menuList(page);
  await search(page, editedName);
  await page.reload();
  const reloaded = await search(page, editedName);
  expect(reloaded.records).toHaveLength(1);
  expect(reloaded.records[0].sc_name).toBe(editedName);
  const editedRow = page
    .getByTestId('dynamic-list')
    .getByRole('row')
    .filter({ has: page.getByText(editedName, { exact: true }) });
  await expect(editedRow).toHaveCount(1);
  await capture(page, 'PERF-09');
  await editedRow.getByTestId('row-action-delete').click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await capture(page, 'PERF-10');
  const deletedResponse = page.waitForResponse(
    (r) => new URL(r.url()).pathname === `${command}sc:delete_showcase` && r.status() === 200,
  );
  await dialog.getByRole('button', { name: /删除|确认/ }).click();
  expect((await (await deletedResponse).json()).code).toBe('0');
  await page.reload();
  const removed = await search(page, editedName);
  expect(removed.total).toBe(0);
  expect(removed.records).toEqual([]);
  await expect(editedRow).toHaveCount(0);
  await capture(page, 'PERF-11');
  writeFileSync(join(evidence!, 'trace-ids.json'), JSON.stringify(traceIds, null, 2));
});

test('PERF auth: anonymous home requires login', async ({ browser }) => {
  const context = await browser.newContext({
    locale: 'zh-CN',
    viewport: { width: 1440, height: 1000 },
  });
  try {
    const page = await context.newPage();
    await page.goto('/home');
    await expect(page).toHaveURL(/\/login\?redirectTo=/);
    await expect(page.locator('input[type="password"]')).toBeVisible();
    await capture(page, 'PERF-12');
  } finally {
    await context.close();
  }
});
