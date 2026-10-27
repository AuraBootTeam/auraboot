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
  await test.info().attach('ZC-03', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
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


test('DR-02: default project list remains readable at desktop viewport widths', async ({ page }) => {
  for (const width of [1280, 1440, 1920]) {
    await page.setViewportSize({ width, height: 1000 });
    await page.goto(`/p/${fixture.modelCode}`);
    const table = page.getByTestId(`ab:list:${fixture.modelCode}:table`);
    const row = page.getByRole('row').filter({ hasText: fixture.name });
    await expect(row).toContainText(fixture.customer);
    await expect(row).toContainText('680,000.00');
    await expect(row).toContainText('72');
    await expect.poll(() => table.evaluate(el => el.scrollWidth - el.clientWidth)).toBeLessThanOrEqual(2);
    await expect(row.locator('td')).toHaveCount(9);
    const budget = row.locator('[data-testid$="-zcrud_project_budget"]');
    const progress = row.locator('[data-testid$="-zcrud_project_progress"]');
    const actions = row.locator('[data-testid$="-actions"]');
    const budgetBox = await budget.boundingBox();
    const progressBox = await progress.boundingBox();
    const actionsBox = await actions.boundingBox();
    expect(budgetBox).not.toBeNull();
    expect(progressBox).not.toBeNull();
    expect(actionsBox).not.toBeNull();
    expect(budgetBox!.x + budgetBox!.width).toBeLessThanOrEqual(progressBox!.x + 1);
    const progressTextRight = await progress.evaluate(el => {
      const range = document.createRange();
      range.selectNodeContents(el);
      return range.getBoundingClientRect().right;
    });
    expect(progressTextRight).toBeLessThanOrEqual(actionsBox!.x);
    await expect(row.getByRole('button', { name: '查看', exact: true })).toBeVisible();
    await test.info().attach(`DR-02-${width}`, { body: await page.screenshot(), contentType: 'image/png' });
  }
});

test('DR-04 to DR-10: designer pointer drag, schema properties, history, save, publish and runtime roundtrip', async ({ page }) => {
  test.setTimeout(120_000);
  const originalResponse = await page.request.get(`/api/pages/key/${fixture.modelCode}_form`);
  expect(originalResponse.ok()).toBeTruthy();
  const original = (await originalResponse.json()).data;
  expect(original.extension?.formPresentation).toBeUndefined();
  expect(original.extension?.presentation).toBeUndefined();
  await page.goto(editPath);
  await expect(page.locator('#zcrud_project_name')).toHaveValue(fixture.name);
  await page.getByRole('button', { name: '配置此页', exact: true }).click();
  await expect(page.getByRole('note', { name: 'ChangeSet 风险与发布策略' })).toBeVisible();
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.locator('#zcrud_project_name')).toHaveValue(fixture.name);
  // Only fixture setup uses API: a fresh independent page preserves the zero-config original.
  const pageKey = `zcrud_designer_${Date.now()}`;
  const create = await page.request.post('/api/pages', { data: {
    pageKey, name: pageKey, title: 'Customer delivery layout',
    kind: original.kind, modelCode: original.modelCode, profile: original.profile,
    schemaVersion: original.schemaVersion, blocks: original.blocks,
    layout: original.layout, dataSources: original.dataSources, extension: original.extension,
  } });
  expect(create.ok(), await create.text()).toBeTruthy();
  const created = await create.json();
  expect(created.code).toBe('0');
  const pid = created.data.pid;
  const read = async () => {
    const response = await page.request.get(`/api/pages/${pid}`);
    expect(response.ok()).toBeTruthy();
    const body = await response.json(); expect(body.code).toBe('0'); return body.data;
  };
  const save = async () => {
    await expect(page.getByTestId('toolbar-save')).toBeEnabled();
    const pending = page.waitForResponse(r => r.url().endsWith(`/api/pages/${pid}`) && r.request().method() === 'PUT');
    await page.getByTestId('toolbar-save').click();
    const response = await pending;
    expect(response.status(), await response.text()).toBe(200); expect((await response.json()).code).toBe('0');
    await expect(page.getByTestId('toolbar-save')).toBeDisabled();
  };
  await page.goto(`/page-designer/${pid}`);
  const fieldCodes = () => page.locator('[data-field-code]').evaluateAll(nodes => nodes.map(n => n.getAttribute('data-field-code')));
  await expect(page.getByTestId('designer-field-zcrud_project_name')).toBeVisible();
  const initialOrder = await fieldCodes();
  await expect(page.getByTestId('field-library-item-zcrud_project_name')).toBeVisible();
  await expect(page.getByTestId('field-library-item-zcrud_project_budget')).toContainText('moneyinput');
  await test.info().attach('DR-04-model-field-library', { body: await page.screenshot(), contentType: 'image/png' });
  const from = await page.getByTestId('designer-field-zcrud_project_name').boundingBox();
  const to = await page.getByTestId('designer-field-zcrud_project_code').boundingBox();
  expect(from).not.toBeNull(); expect(to).not.toBeNull();
  await page.mouse.move(from!.x + from!.width / 2, from!.y + from!.height / 2);
  await page.mouse.down();
  await page.mouse.move(to!.x + to!.width / 2, to!.y + to!.height / 2, { steps: 16 });
  await page.mouse.up();
  await expect.poll(fieldCodes).toEqual([initialOrder[1], initialOrder[0], ...initialOrder.slice(2)]);
  // dnd-kit suppresses the synthetic click after pointer-up for 50ms.
  // Use a normal held pointer click; do not retry or invoke the handler directly.
  await test.info().attach('DR-05-pointer-drag', { body: await page.screenshot(), contentType: 'image/png' });
  await page.getByTestId('toolbar-undo').click({ delay: 80 });
  await expect.poll(fieldCodes).toEqual(initialOrder);
  await page.getByTestId('toolbar-redo').click();
  await expect.poll(fieldCodes).toEqual([initialOrder[1], initialOrder[0], ...initialOrder.slice(2)]);
  // Keep the business identity first in the published comparison page.
  await page.getByTestId('toolbar-undo').click();
  await expect.poll(fieldCodes).toEqual(initialOrder);
  await page.getByTestId('designer-field-zcrud_project_name').click();
  await expect(page.getByTestId('field-property-required-switch')).toHaveAttribute('aria-checked', 'true');
  await expect(page.getByTestId('field-property-component-select')).toHaveValue('SmartInput');
  await page.getByTestId('field-placeholder-zh').fill('填写客户认可的项目名称');
  await page.getByRole('button', { name: '布局设置', exact: true }).click();
  await page.getByTestId('field-property-span-select').selectOption('2');
  await test.info().attach('DR-06-field-properties', { body: await page.screenshot(), contentType: 'image/png' });
  await page.getByRole('button', { name: '返回 Block', exact: true }).click();
  await page.getByTestId('block-title-input-zh').fill('项目交付信息');
  await page.getByTestId('toolbar-settings').click();
  const settings = page.getByTestId('settings-panel');
  await settings.getByRole('spinbutton', { name: '默认列数', exact: true }).fill('4');
  await expect(page.getByTestId('settings-panel-save')).toBeDisabled();
  await test.info().attach('DR-11-invalid-schema-property', { body: await page.screenshot(), contentType: 'image/png' });
  await settings.getByRole('spinbutton', { name: '默认列数', exact: true }).fill('3');
  await page.getByTestId('settings-panel-save').click();
  await expect(settings).not.toBeVisible();
  await save();
  const stored = await read();
  const form = stored.blocks.find((b: any) => b.id === original.blocks[0].id);
  expect(form.fields.map((f: any) => typeof f === 'string' ? f : f.field)).toEqual(initialOrder);
  const name = form.fields.find((f: any) => f.field === 'zcrud_project_name');
  expect(name.placeholder).toEqual({ 'zh-CN': '填写客户认可的项目名称' });
  expect(name.layout.colSpan).toBe(12);
  expect(name.validation).toEqual(original.blocks[0].fields[0].validation);
  expect(stored.extension.formPresentation.columns).toBe(3);
  expect(form.title['zh-CN']).toBe('项目交付信息');
  const customer = form.fields.find((f: any) => f.field === 'zcrud_project_customer');
  expect(customer.refTarget).toEqual(original.blocks[0].fields[2].refTarget);
  expect(customer.createCommand).toBe('zcrud:create_client');
  await page.reload();
  await expect(page.getByTestId('designer-field-zcrud_project_name')).toContainText('填写客户认可的项目名称');
  await expect.poll(fieldCodes).toEqual(initialOrder);
  await test.info().attach('DR-08-designer-reopened', { body: await page.screenshot(), contentType: 'image/png' });
  // Real download must contain the edited schema, not merely produce a file.
  const downloadPending = page.waitForEvent('download');
  await page.getByTestId('toolbar-export').click();
  const download = await downloadPending;
  const downloadPath = await download.path();
  expect(downloadPath).toBeTruthy();
  const exported = JSON.parse(fs.readFileSync(downloadPath!, 'utf8'));
  expect(exported.schema.blocks).toEqual(stored.blocks);
  expect(exported.schema.extension.formPresentation.columns).toBe(3);
  await test.info().attach('DR-10-export.json', { path: downloadPath!, contentType: 'application/json' });
  // Invalid import must not replace the current page or create a dirty draft.
  await page.getByTestId('page-schema-import-input').setInputFiles({ name: 'invalid.json', mimeType: 'application/json', buffer: Buffer.from('{"schema":{"kind":"unknown"}}') });
  await expect(page.getByTestId('toolbar-save')).toBeDisabled();
  await expect(page.getByText(/缺少|不支持|invalid|unsupported/i).last()).toBeVisible();
  await page.getByTestId('designer-field-zcrud_project_name').click();
  await page.getByTestId('field-placeholder-zh').fill('导入前临时调整');
  await page.getByTestId('page-schema-import-input').setInputFiles(downloadPath!);
  await expect(page.getByTestId('designer-field-zcrud_project_name')).toContainText('填写客户认可的项目名称');
  await save();
  const publishPending = page.waitForResponse(r => r.url().includes(`/api/pages/${pid}/publish`) && r.request().method() === 'POST');
  await page.getByTestId('toolbar-publish').click();
  const published = await publishPending;
  expect(published.status()).toBe(200); expect((await published.json()).code).toBe('0');
  expect((await read()).status).toBe('published');
  const runtimePath = `/p/c/${pageKey}/edit/${fixture.recordPid}`;
  await page.goto(runtimePath);
  await expect(page.locator('#zcrud_project_name')).toHaveValue(fixture.name);
  await expect(page.locator('#zcrud_project_name')).toHaveAttribute('placeholder', '填写客户认可的项目名称');
  await expect(page.getByText('项目交付信息', { exact: true })).toBeVisible();
  await expect(page.locator('#zcrud_project_budget')).toHaveValue('680,000.00');
  await expect(page.getByRole('row').filter({ hasText: '合计' })).toContainText('365,000');
  // Actual runtime edit and readback, then restore through the same public UI.
  await page.locator('#zcrud_project_owner').fill('陈晨 · 交付复核');
  await page.getByTestId('form-action-footer').getByRole('button', { name: '保存', exact: true }).click();
  await page.goto(runtimePath);
  await expect(page.locator('#zcrud_project_owner')).toHaveValue('陈晨 · 交付复核');
  await page.locator('#zcrud_project_owner').fill('陈晨');
  await page.getByTestId('form-action-footer').getByRole('button', { name: '保存', exact: true }).click();
  await page.goto(runtimePath);
  await expect(page.locator('#zcrud_project_owner')).toHaveValue('陈晨');
  await test.info().attach('DR-09-live-refined-form', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
  await page.mouse.move(1000, 550);
  await page.mouse.wheel(0, 650);
  const total = page.getByRole('row').filter({ hasText: '合计' });
  await expect(total).toContainText('365,000');
  await expect.poll(async () => {
    const sum = await total.boundingBox();
    const footer = await page.getByTestId('form-action-footer').boundingBox();
    return !!sum && !!footer && sum.y + sum.height <= footer.y;
  }).toBe(true);
  await expect(page.getByRole('row').filter({ hasText: '接口联调与培训' })).toContainText('245,000');
  await test.info().attach('DR-09-live-child-items', { body: await page.screenshot(), contentType: 'image/png' });
  const untouched = await (await page.request.get(`/api/pages/${original.pid}`)).json();
  expect(untouched.data.blocks).toEqual(original.blocks);
  expect(untouched.data.extension).toEqual(original.extension);
  await test.info().attach('designer-roundtrip.json', { body: JSON.stringify({ pid, pageKey, runtimePath, originalPid: original.pid, stored: await read() }, null, 2), contentType: 'application/json' });
});
