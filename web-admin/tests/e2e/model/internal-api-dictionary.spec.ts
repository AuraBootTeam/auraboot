import { test, expect, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs/promises';
import { LoginPage } from '../../pages/LoginPage';
import { DEFAULT_TEST_ACCOUNT } from '../../helpers/test-accounts';
import { uniqueId } from '../helpers';

const catalog = JSON.parse(
  await fs.readFile(
    new URL('../../../../scripts/internal-api/catalog.json', import.meta.url),
    'utf8',
  ),
);
const scenarios = new Map<string, { screenshot: string; test: string }>(
  catalog.scenarios.map((row: { id: string; screenshot: string; test: string }) => [row.id, row]),
);

test.use({
  storageState: { cookies: [], origins: [] },
  locale: 'zh-CN',
  timezoneId: 'Asia/Shanghai',
  viewport: { width: 1440, height: 1000 },
});

async function capture(id: string, page: Page, info: TestInfo) {
  const scenario = scenarios.get(id);
  expect(scenario, `Unregistered scenario ${id}`).toBeDefined();
  expect(scenario!.test).toBe(info.title);
  await expect(page.locator('body')).not.toContainText('Page Unavailable');
  const file = info.outputPath(scenario!.screenshot);
  await page.screenshot({ path: file, fullPage: true });
  await info.attach(id, { path: file, contentType: 'image/png' });
  info.annotations.push({ type: 'internal-api-scenario', description: id });
}

test('generated dictionary operations preserve the UI journey', async ({ page }, info) => {
  test.setTimeout(90_000);
  const login = new LoginPage(page);
  await login.gotoRoot();
  await login.waitForFormReady();
  await login.login(DEFAULT_TEST_ACCOUNT.email, DEFAULT_TEST_ACCOUNT.password);
  await login.expectLoggedIn();
  await page.locator('nav a[href="/meta/dict"]').click();
  await expect(page.getByTestId('dictionary-list')).toBeVisible();
  await expect(page.locator('tbody')).toContainText('org_team_status');
  const controlledRow = page.locator('tbody tr').filter({ hasText: 'org_team_status' });
  await expect(controlledRow.getByRole('link', { name: '查看', exact: true })).toBeInViewport();
  await capture('DICT-01', page, info);

  // Force a deterministic second page, then inspect the CSR operation query.
  await page.goto('/meta/dict?size=1');
  const pagedResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === '/api/meta/dict' && url.searchParams.get('pageNum') === '2';
  });
  await page.getByRole('button', { name: '下一页', exact: true }).filter({ visible: true }).click();
  const paged = await pagedResponse;
  const query = new URL(paged.url()).searchParams;
  expect(query.get('pageSize')).toBe('1');
  expect(query.has('page')).toBe(false);
  expect(query.has('size')).toBe(false);
  const pageBody = await paged.json();
  expect(pageBody.code).toBe('0');
  expect(pageBody.data.current).toBe(2);
  expect(pageBody.data.records).toHaveLength(1);

  await page.getByTestId('toolbar-btn-create').click();
  await expect(page.getByRole('heading', { name: '创建字典', exact: true })).toBeVisible();
  const codeInput = page.getByPlaceholder('例如: user_status');
  const nameInput = page.getByPlaceholder('例如: 用户状态');
  await page.locator('main form button[type="submit"]').click();
  expect(await codeInput.evaluate((node: HTMLInputElement) => node.validity.valueMissing)).toBe(
    true,
  );
  await expect(page).toHaveURL(/\/meta\/dict\/new$/);
  await capture('DICT-02', page, info);

  const code = uniqueId('e2e_contract_status');
  const name = `契约状态 ${code.split('_').at(-1)}`;
  await codeInput.fill(code);
  await nameInput.fill(name);
  const createdResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/meta/dict' &&
      response.request().method() === 'POST',
  );
  await page.locator('main form button[type="submit"]').click();
  const created = await createdResponse;
  expect(created.status()).toBe(200);
  expect(created.request().postDataJSON()).toMatchObject({
    code,
    name,
    dictType: 'simple',
    sourceType: 'static',
  });
  const createdBody = await created.json();
  expect(createdBody.code).toBe('0');
  expect(createdBody.data.code).toBe(code);
  expect(typeof createdBody.data.pid).toBe('string');
  const pid: string = createdBody.data.pid;
  await expect(page).toHaveURL((url) => url.pathname === `/meta/dict/${pid}`);
  await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
  const createdAt = createdBody.data.createdAt;
  expect(typeof createdAt).toBe('string');
  const instant = new Date(/[Zz]|[+-]\d\d:\d\d$/.test(createdAt) ? createdAt : `${createdAt}Z`);
  const expectedDate = new Intl.DateTimeFormat('sv-SE', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(instant);
  await expect(page.getByTestId('dict-created-at')).toHaveText(expectedDate);
  await capture('DICT-03', page, info);

  await page.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByRole('heading', { name: '编辑字典', exact: true })).toBeVisible();
  const updatedName = `Updated ${name}`;
  await page.getByPlaceholder('例如: 用户状态').fill(updatedName);
  const updatedResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/meta/dict/${pid}` &&
      response.request().method() === 'PUT',
  );
  await page.locator('main form button[type="submit"]').click();
  const updated = await updatedResponse;
  expect(updated.status()).toBe(200);
  expect(updated.request().postDataJSON()).toMatchObject({ name: updatedName });
  expect((await updated.json()).code).toBe('0');
  await expect(page).toHaveURL((url) => url.pathname === `/meta/dict/${pid}`);
  await page.reload();
  await expect(page.getByRole('heading', { name: updatedName, exact: true })).toBeVisible();
  await capture('DICT-04', page, info);

  await page.getByTestId('dict-tab-items').click();
  await page.getByTestId('dict-add-root-item').click();
  const itemRow = page.getByTestId('dict-item-row-0');
  await itemRow.locator('input').nth(0).fill('active');
  await itemRow.locator('input').nth(1).fill('启用');
  const itemResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/meta/dict/${pid}/items` &&
      response.request().method() === 'PUT',
  );
  await page.getByTestId('dict-save-items').click();
  const savedItems = await itemResponse;
  expect(savedItems.request().postDataJSON()).toEqual([
    { value: 'active', label: '启用', sortOrder: 0, disabled: false },
  ]);
  expect((await savedItems.json()).code).toBe('0');
  await expect(page.getByTestId('dict-save-items')).toBeDisabled();
  await page.reload();
  await page.getByTestId('dict-tab-items').click();
  await expect(page.getByTestId('dict-item-row-0').locator('input').nth(0)).toHaveValue('active');
  await expect(page.getByTestId('dict-item-row-0').locator('input').nth(1)).toHaveValue('启用');
  await capture('DICT-07', page, info);

  const unpublishResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/meta/dict/${pid}/unpublish` &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '取消发布', exact: true }).click();
  expect((await (await unpublishResponse).json()).data.status).toBe('deprecated');
  await expect(page.getByRole('button', { name: '发布', exact: true })).toBeVisible();
  await page.getByTestId('dict-tab-basic').click();
  await expect(page.locator('main')).toContainText('已废弃');
  await capture('DICT-10', page, info);
  const publishResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/meta/dict/${pid}/publish` &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '发布', exact: true }).click();
  expect((await (await publishResponse).json()).data.status).toBe('published');
  await page.reload();
  await expect(page.getByRole('button', { name: '取消发布', exact: true })).toBeVisible();
  await page.getByTestId('dict-tab-versions').click();
  await expect(
    page.getByRole('heading', { name: `版本 ${createdBody.data.version}`, exact: true }),
  ).toBeVisible();
  await expect(page.getByTestId(`dict-version-created-at-${pid}`)).toHaveText(expectedDate);
  await capture('DICT-08', page, info);

  await page.locator('nav a[href="/meta/dict"]').click();
  await expect(page.getByTestId('dictionary-list')).toBeVisible();
  const missingCode = uniqueId('e2e_contract_missing');
  await page.getByPlaceholder('输入字典编码').fill(missingCode);
  // The search is a native GET form: its loader calls the API on the server.
  const filteredResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === '/meta/dict' && url.searchParams.get('code') === missingCode;
  });
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  expect((await filteredResponse).status()).toBe(200);
  await expect(page).toHaveURL((url) => url.searchParams.get('code') === missingCode);
  await expect(page.locator('tbody')).toContainText('暂无数据');
  await capture('DICT-05', page, info);

  // Delete only this attempt's own record; dictionary deletion is a soft disable.
  await page.goto(`/meta/dict/${pid}`);
  const deprecateBeforeDelete = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/meta/dict/${pid}/unpublish` &&
      response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '取消发布', exact: true }).click();
  expect((await (await deprecateBeforeDelete).json()).data.status).toBe('deprecated');
  await page.getByRole('button', { name: '删除', exact: true }).click();
  await expect(page.getByTestId('confirm-dialog')).toContainText(updatedName);
  await capture('DICT-09', page, info);
  const deleteResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === `/api/meta/dict/${pid}` &&
      response.request().method() === 'DELETE',
  );
  await page.getByTestId('confirm-ok').click();
  expect((await (await deleteResponse).json()).code).toBe('0');
  await expect(page).toHaveURL((url) => url.pathname === '/meta/dict');
  const removed = await page.request.get(`/api/meta/dict/${pid}`);
  const removedBody = await removed.json();
  expect(removedBody.code).toBe('0');
  expect(removedBody.data.status).toBe('disabled');
  expect(removedBody.data.pid).toBe(pid);
});

test('anonymous dictionary navigation and API access are refused', async ({ page }, info) => {
  await page.goto('/meta/dict');
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
  await expect(page.locator('[name="identifier"]')).toBeVisible();
  const denied = await page.request.get('/api/meta/dict');
  expect([401, 403]).toContain(denied.status());
  await capture('DICT-06', page, info);
});
