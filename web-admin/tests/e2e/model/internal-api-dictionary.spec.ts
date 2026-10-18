import { test, expect, type Page, type TestInfo } from '@playwright/test';
import fs from 'node:fs/promises';
import { performLogin } from '../../workflows/login.workflow';
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
  await performLogin(page);
  await page.locator('nav a[href="/meta/dict"]').click();
  await expect(page.getByTestId('dictionary-list')).toBeVisible();
  await expect(page.locator('tbody')).toContainText('org_team_status');
  await capture('DICT-01', page, info);

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
  const name = `Contract status ${code}`;
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

  await page.locator('nav a[href="/meta/dict"]').click();
  await expect(page.getByTestId('dictionary-list')).toBeVisible();
  const missingCode = uniqueId('e2e_contract_missing');
  await page.getByPlaceholder('输入字典编码').fill(missingCode);
  const filteredResponse = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === '/api/meta/dict' &&
      response.request().method() === 'GET' &&
      new URL(response.url()).searchParams.get('code') === missingCode,
  );
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const filtered = await filteredResponse;
  const query = new URL(filtered.url()).searchParams;
  expect(query.get('pageNum')).toBe('1');
  expect(query.get('pageSize')).toBe('20');
  expect(query.has('page')).toBe(false);
  expect(query.has('size')).toBe(false);
  const filteredBody = await filtered.json();
  expect(filteredBody.code).toBe('0');
  expect(filteredBody.data.records).toEqual([]);
  await expect(page.locator('tbody')).toContainText('暂无数据');
  await capture('DICT-05', page, info);
});

test('anonymous dictionary navigation and API access are refused', async ({ page }, info) => {
  await page.goto('/meta/dict');
  await expect(page).toHaveURL(/\/login(?:\?|$)/);
  await expect(page.locator('[name="identifier"]')).toBeVisible();
  const denied = await page.request.get('/api/meta/dict');
  expect([401, 403]).toContain(denied.status());
  await capture('DICT-06', page, info);
});
