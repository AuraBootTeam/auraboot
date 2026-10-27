import { test, expect } from '../../fixtures';
import fs from 'node:fs';
import { authenticateBffSession } from '../../helpers/bff-auth-session';

// This is a restored-case compatibility journey, separate from MW-01's fresh generation.
// Missing case/account prerequisites are failures, never implicit seed or permission repair.
let fixture: Record<string, any>;
let editPath: string;
let detailPath: string;
test.beforeAll(() => {
  const input = process.env.CRUD_CASE_FIXTURE;
  if (!input) throw new Error('Restored CRUD case requires CRUD_CASE_FIXTURE; see CRUD regression documentation');
  fixture = JSON.parse(fs.readFileSync(input, 'utf8'));
  for (const key of ['modelCode', 'recordPid', 'name', 'customer', 'legacyRecordPid', 'readerEmail']) {
    expect(fixture[key], `Case fixture requires ${key}`).toEqual(expect.any(String));
  }
  editPath = `/p/${fixture.modelCode}/edit/${fixture.recordPid}`;
  detailPath = `/p/${fixture.modelCode}/view/${fixture.recordPid}`;
});

test('ZC-03: restored project types and child edit persist after reopening', async ({ page }) => {
  await page.goto(`/p/${fixture.modelCode}`);
  const row = page.getByRole('row').filter({ hasText: fixture.name });
  await expect(row).toContainText(fixture.customer);
  await expect(row).toContainText(Number(fixture.budget).toLocaleString('en-US', { minimumFractionDigits: 2 }));
  await row.getByRole('button', { name: '更多操作', exact: true }).click();
  await page.getByRole('menuitem', { name: '编辑', exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`${editPath}$`));
  await expect(page.locator('#zcrud_project_name')).toHaveValue(fixture.name);
  await expect(page.locator('#zcrud_project_customer')).toContainText(fixture.customer);
  await expect(page.locator('#zcrud_project_status')).toContainText('实施中');
  await expect(page.locator('#zcrud_project_start')).toHaveAttribute('type', 'date');
  await expect(page.locator('#zcrud_project_budget')).toHaveValue('680,000.00');
  const child = page.getByRole('row').filter({ hasText: '接口联调与培训' });
  await expect(child).toContainText('245,000');
  await child.getByRole('button', { name: '编辑', exact: true }).click();
  const owner = page.getByRole('textbox', { name: 'zcrud_line_owner', exact: true });
  await owner.fill('陈晨 · 复核');
  const mutation = page.waitForResponse(response => response.url().includes('/execute/zcrud:update_line')
    && response.request().method() === 'POST');
  await page.getByTestId('subtable-edit-save-0').click();
  expect((await mutation).status()).toBe(200);
  await page.reload();
  await expect(page.getByRole('row').filter({ hasText: '接口联调与培训' })).toContainText('陈晨 · 复核');
  await expect(page.getByRole('row').filter({ hasText: '合计' })).toContainText('365,000');
  await test.info().attach('ZC-03', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
  // Restore via the same UI, preserving the shared demonstration's canonical data.
  const restoredChild = page.getByRole('row').filter({ hasText: '接口联调与培训' });
  await restoredChild.getByRole('button', { name: '编辑', exact: true }).click();
  await page.getByRole('textbox', { name: 'zcrud_line_owner', exact: true }).fill('陈晨');
  const restore = page.waitForResponse(response => response.url().includes('/execute/zcrud:update_line')
    && response.request().method() === 'POST');
  await page.getByTestId('subtable-edit-save-0').click();
  expect((await restore).status()).toBe(200);
  await page.getByTestId('subtable-add-row').click();
  const add = page.getByTestId('subtable-add-form');
  const childName = `回归验证明细 ${Date.now()}`;
  await add.getByTestId('subtable-add-zcrud_line_name').fill(childName);
  await add.getByTestId('subtable-add-zcrud_line_quantity').fill('2');
  await add.getByTestId('subtable-add-zcrud_line_amount').fill('1000');
  await add.getByTestId('subtable-add-zcrud_line_owner').fill('陈晨');
  const createChild = page.waitForResponse(response => response.url().includes('/execute/zcrud:create_line')
    && response.request().method() === 'POST');
  await page.getByTestId('subtable-save-btn').click();
  const createdChild = await createChild;
  expect(createdChild.status()).toBe(200);
  expect((await createdChild.json()).code).toBe('0');
  await page.reload();
  const savedChild = page.getByRole('row').filter({ hasText: childName });
  await expect(savedChild).toContainText('1,000');
  await expect(page.getByRole('row').filter({ hasText: '合计' })).toContainText('366,000');
  const deleteChild = page.waitForResponse(response => response.url().includes('/execute/zcrud:delete_line')
    && response.request().method() === 'POST');
  await savedChild.getByRole('button', { name: '删除', exact: true }).click();
  const deletedChild = await deleteChild;
  expect(deletedChild.status()).toBe(200);
  expect((await deletedChild.json()).code).toBe('0');
  await expect(savedChild).toHaveCount(0);
  await expect(page.getByRole('row').filter({ hasText: '合计' })).toContainText('365,000');
});

test('ZC-04: reference validate cancel create and backfill retain the parent draft', async ({ page }) => {
  await page.goto(editPath);
  await page.locator('#zcrud_project_owner').fill('未保存的父表输入');
  await page.locator('#zcrud_project_customer').click();
  await page.getByRole('option', { name: '+ 新建', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  await expect(dialog.getByText(/请填写客户名称/)).toBeVisible();
  await dialog.locator('#tpm_cu_name').fill('未提交的客户草稿');
  await test.info().attach('ZC-04', { body: await page.screenshot(), contentType: 'image/png' });
  await dialog.getByRole('button', { name: '取消', exact: true }).click();
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('#zcrud_project_owner')).toHaveValue('未保存的父表输入');
  await page.reload();
  await expect(page.locator('#zcrud_project_owner')).toHaveValue('陈晨');
  await page.locator('#zcrud_project_customer').click();
  await page.getByRole('option', { name: '+ 新建', exact: true }).click();
  const newCustomer = `回归验证客户 ${Date.now()}`;
  await dialog.locator('#tpm_cu_name').fill(newCustomer);
  const create = page.waitForResponse(response => response.url().includes('/execute/zcrud:create_client')
    && response.request().method() === 'POST');
  await dialog.getByRole('button', { name: '保存', exact: true }).click();
  const created = await create;
  expect(created.status()).toBe(200);
  expect((await created.json()).code).toBe('0');
  await expect(dialog).not.toBeVisible();
  await expect(page.locator('#zcrud_project_customer')).toContainText(newCustomer);
  await page.reload();
  await expect(page.locator('#zcrud_project_customer')).toContainText(fixture.customer);
});

test('ZC-05: dedicated reader can inspect but cannot mount edit controls or write', async ({ browser, baseURL }) => {
  const password = process.env.CRUD_READER_PASSWORD;
  if (!password) throw new Error('Set the restored dedicated CRUD_READER_PASSWORD privately');
  const context = await browser.newContext({ extraHTTPHeaders: { Referer: `${baseURL}/` } });
  try {
    await authenticateBffSession(context.request, baseURL!, fixture.readerEmail, password);
    const page = await context.newPage();
    await page.goto(`/p/${fixture.modelCode}`);
    await expect(page.getByRole('row').filter({ hasText: fixture.name })).toContainText(fixture.customer);
    await expect(page.getByTestId('toolbar-btn-create')).toHaveCount(0);
    await page.getByRole('row').filter({ hasText: fixture.name }).getByRole('button', { name: '查看', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`${detailPath}$`));
    await expect(page.locator('main')).toContainText(fixture.name);
    await expect(page.getByTestId('toolbar-btn-edit')).toHaveCount(0);
    const endpoint = `/api/dynamic/${fixture.modelCode}/${fixture.recordPid}`;
    const before = await (await context.request.get(endpoint)).json();
    expect(before.code).toBe('0');
    await page.goto(editPath);
    await expect(page.locator('#zcrud_project_owner')).toHaveCount(0);
    await expect(page.getByTestId('subtable-add-row')).toHaveCount(0);
    await expect(page.locator('main')).toContainText(/权限|forbidden|access/i);
    const denied = await context.request.put(endpoint, { data: { zcrud_project_owner: '禁止写入' } });
    expect(denied.status()).toBe(403);
    const after = await (await context.request.get(endpoint)).json();
    expect(after.data).toEqual(before.data);
    await test.info().attach('reader-denial.json', {
      body: JSON.stringify({ status: denied.status(), before: before.data, after: after.data }),
      contentType: 'application/json',
    });
    await test.info().attach('ZC-05', { body: await page.screenshot(), contentType: 'image/png' });
  } finally { await context.close(); }
});

test('ZC-06: explicit legacy workspace keeps business sections', async ({ page }) => {
  await page.goto(`/p/tpm_project/edit/${fixture.legacyRecordPid}`);
  await expect(page.locator('#tpm_pj_name')).toHaveValue('华辰制造 · 既有页面兼容验证');
  await expect(page.getByText('基本信息', { exact: true })).toBeVisible();
  await expect(page.getByText('交付计划', { exact: true })).toBeVisible();
  await expect(page.getByTestId('form-action-footer')).toBeVisible();
  await test.info().attach('ZC-06', { body: await page.screenshot(), contentType: 'image/png' });
});
