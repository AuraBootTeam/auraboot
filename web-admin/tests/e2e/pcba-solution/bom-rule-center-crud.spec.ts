import * as XLSX from 'xlsx';
import * as fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import type { Page } from '@playwright/test';
import { test, expect } from '../../fixtures';
import { ensureSidebarExpanded, clickRowActionByLocator } from '../helpers';
import {
  pollAsyncTaskResult,
  executeCommand,
  readDynamicRecord,
  queryDynamicRecords,
  searchBusinessList,
  makeQuoteRoleUser,
  ensureQuoteRoleUser,
  openQuoteRolePage,
  expectCommandDenied,
  dynamicCreate,
} from './quote-e2e-helpers';

type Choice = { field: string; label: string; value: string };
type RuleCase = {
  model: string;
  command: string;
  key: string;
  requiredField?: string;
  choices?: Choice[];
  enabled?: string;
  remark: string;
  text?: { field: string; value: string };
  extraTexts?: { field: string; value: string }[];
  select?: Choice;
  extraSelect?: Choice;
  allowDelete?: boolean;
};

const cases: RuleCase[] = [
  {
    model: 'bom_validation_rule',
    command: 'validation_rule',
    key: 'bom_vr_default_value',
    requiredField: 'bom_vr_category',
    enabled: 'bom_vr_enabled',
    remark: 'bom_vr_remark',
    choices: [
      { field: 'bom_vr_category', label: '电阻', value: 'resistor' },
      { field: 'bom_vr_check_item', label: '阻值', value: 'resistance' },
      { field: 'bom_vr_missing_severity', label: '黄色 (复核)', value: 'yellow' },
    ],
  },
  {
    model: 'bom_customer_part_map',
    command: 'customer_part_map',
    key: 'bom_cpm_customer_id',
    remark: 'bom_cpm_remark',
    extraTexts: [
      { field: 'bom_cpm_customer_pn_raw', value: 'GATE-PN-RAW' },
      { field: 'bom_cpm_customer_pn_norm', value: 'GATE-PN-NORM' },
      { field: 'bom_cpm_material_code', value: 'GATE-MATERIAL' },
    ],
    select: { field: 'bom_cpm_status', label: '待生效', value: 'pending' },
  },
  {
    model: 'bom_setting',
    command: 'setting',
    key: 'bom_setting_key',
    remark: 'bom_setting_value',
    allowDelete: false,
  },
  {
    model: 'bom_material_rule_dict',
    command: 'material_rule_dict',
    key: 'bom_mrd_match_key',
    enabled: 'bom_mrd_enabled',
    remark: 'bom_mrd_remark',
    text: { field: 'bom_mrd_normalized_value', value: 'GATEBRAND' },
    select: { field: 'bom_mrd_rule_type', label: '品牌别名', value: 'brand_alias' },
  },
  {
    model: 'bom_category_rule',
    command: 'category_rule',
    key: 'bom_cr_keyword',
    enabled: 'bom_cr_enabled',
    remark: 'bom_cr_remark',
    select: { field: 'bom_cr_category', label: '电阻', value: 'resistor' },
    extraSelect: { field: 'bom_cr_keyword_type', label: '名称', value: 'name' },
  },
  {
    model: 'bom_header_alias',
    command: 'header_alias',
    key: 'bom_ha_alias',
    enabled: 'bom_ha_enabled',
    remark: 'bom_ha_remark',
    select: { field: 'bom_ha_system_field', label: 'MPN/原料号', value: 'mpn' },
  },
  {
    model: 'bom_unit_rule',
    command: 'unit_rule',
    key: 'bom_ur_raw',
    enabled: 'bom_ur_enabled',
    remark: 'bom_ur_remark',
    text: { field: 'bom_ur_standard', value: 'ohm' },
    select: { field: 'bom_ur_category', label: '电阻', value: 'resistor' },
  },
  {
    model: 'bom_package_rule',
    command: 'package_rule',
    key: 'bom_pr_package_code',
    enabled: 'bom_pr_enabled',
    remark: 'bom_pr_remark',
  },
];

async function submit(page: Page, command: string) {
  const pending = page.waitForResponse(
    (r) =>
      decodeURIComponent(r.url()).includes(`/api/meta/commands/execute/${command}`) &&
      r.request().method() === 'POST',
  );
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const response = await pending;
  expect(response.status()).toBe(200);
  const body = await response.json();
  expect(String(body.code), JSON.stringify(body).slice(0, 800)).toBe('0');
  return response.request().postDataJSON();
}

for (const c of cases) {
  test(`${c.model}: menu, required validation, create, edit, reload and ${c.allowDelete === false ? 'retain' : 'delete'}`, async ({
    page,
  }, info) => {
    test.setTimeout(120_000);
    const marker = `E2ERULE${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
    const field = (name: string) => page.getByTestId(`form-field-${name}`);
    const records = () =>
      queryDynamicRecords(page, c.model, [{ fieldName: c.key, operator: 'EQ', value: marker }]);
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    const sidebar = page.getByTestId('sidebar');
    const link = sidebar.locator(`a[href="/p/${c.model}"]`);
    if (!(await link.isVisible())) {
      const center = sidebar.getByRole('button', { name: '规则中心', exact: true });
      if (!(await center.isVisible()))
        await sidebar.getByRole('button', { name: 'BOM转化工具', exact: true }).click();
      await center.click();
    }
    await link.click();
    await expect(page).toHaveURL(new RegExp(`/p/${c.model}$`));
    await page.locator('main').getByRole('button', { name: '新建', exact: true }).click();
    await expect(page.getByTestId('dynamic-page-form')).toBeVisible();
    let createRequests = 0;
    page.on('request', (r) => {
      if (
        decodeURIComponent(r.url()).includes(
          `/api/meta/commands/execute/bom:create_${c.command}`,
        ) &&
        r.method() === 'POST'
      )
        createRequests++;
    });
    await page.getByRole('button', { name: '保存', exact: true }).click();
    await expect(field(c.requiredField || c.key)).toContainText(/必填|不能为空|请输入|请填写|请选择|required/i);
    expect(createRequests, 'invalid form must not dispatch its create command').toBe(0);
    expect(await records(), 'invalid form must not write a record').toEqual([]);
    await info.attach('rule-required-browser', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    await info.attach('rule-required-persistence', { body: JSON.stringify({ model: c.model, marker, createRequests, records: [] }), contentType: 'application/json' });
    await field(c.key).getByRole('textbox').fill(marker);
    for (const value of [c.text, ...(c.extraTexts || [])].filter(Boolean))
      await field(value!.field).getByRole('textbox').fill(value!.value);
    for (const choice of [c.select, c.extraSelect, ...(c.choices || [])].filter(Boolean)) {
      await field(choice!.field).getByRole('combobox').first().click();
      await page.getByRole('option', { name: choice!.label, exact: true }).click();
    }
    if (c.enabled) {
      const toggle = field(c.enabled).getByRole('switch');
      if ((await toggle.getAttribute('aria-checked')) !== 'true') await toggle.click();
      await toggle.click();
      await expect(toggle).toHaveAttribute('aria-checked', 'false');
    }
    await field(c.remark).getByRole('textbox').fill('rule-gate-created');
    const payload = await submit(page, `bom:create_${c.command}`);
    expect(payload.payload[c.key]).toBe(marker);
    if (c.enabled) expect(payload.payload[c.enabled]).toBe(false);
    await expect.poll(async () => (await records()).length).toBe(1);
    const saved = (await records())[0];
    if (c.enabled) expect(saved[c.enabled]).toBe(false);
    for (const choice of [c.select, c.extraSelect, ...(c.choices || [])].filter(Boolean))
      expect(saved[choice!.field]).toBe(choice!.value);
    for (const value of [c.text, ...(c.extraTexts || [])].filter(Boolean))
      expect(saved[value!.field]).toBe(value!.value);
    info.annotations.push({
      type: 'created-rule',
      description: JSON.stringify({ model: c.model, pid: saved.pid, marker }),
    });
    if (c.model === 'bom_customer_part_map') {
      // Customer identity is a scope, not a one-record-per-customer limit.
      await searchBusinessList(page, `/p/${c.model}`, marker);
      await page.locator('main').getByRole('button', { name: '新建', exact: true }).click();
      await field(c.key).getByRole('textbox').fill(marker);
      for (const value of c.extraTexts || [])
        await field(value.field).getByRole('textbox').fill(`${value.value}-SECOND`);
      await field(c.select!.field).getByRole('combobox').first().click();
      await page.getByRole('option', { name: c.select!.label, exact: true }).click();
      await field(c.remark).getByRole('textbox').fill('second-part-for-same-customer');
      await submit(page, `bom:create_${c.command}`);
      await expect.poll(async () => (await records()).length).toBe(2);
      expect((await records()).map((r) => r.bom_cpm_customer_pn_norm).sort())
        .toEqual(['GATE-PN-NORM', 'GATE-PN-NORM-SECOND']);
      await searchBusinessList(page, `/p/${c.model}`, marker);
      const secondRow = page.getByRole('row').filter({ hasText: 'GATE-PN-RAW-SECOND' });
      await expect(secondRow).toHaveCount(1);
      await page.screenshot({ path: info.outputPath('multiple-parts.png') });
      await clickRowActionByLocator(page, secondRow, 'delete', '删除');
      await page.getByTestId('confirm-ok').click();
      await expect.poll(async () => (await records()).length).toBe(1);
      expect((await records())[0].pid).toBe(saved.pid);
    }
    const beforeDenied = await records();
    const deniedUser = makeQuoteRoleUser('rule-denied-proc', marker.toLowerCase(), [
      'qo_procurement',
    ]);
    await ensureQuoteRoleUser(page, deniedUser);
    const denied = await openQuoteRolePage(page.context().browser()!, deniedUser);
    try {
      await denied.page.goto(`/p/${c.model}/new?commandCode=bom:create_${c.command}`, {
        waitUntil: 'domcontentloaded',
      });
      await expect(denied.page.locator('main')).toContainText(
        /Page Unavailable|Access forbidden|Access denied|无权限|未授权|权限不足/i,
      );
      await expect(denied.page.getByTestId('dynamic-page-form')).toHaveCount(0);
      await denied.page.screenshot({ path: info.outputPath('denied.png') });
      for (const operation of c.allowDelete === false ? ['create', 'update'] : ['create', 'update', 'delete']) {
        await expectCommandDenied(
          denied.page,
          `bom:${operation}_${c.command}`,
          payload.payload,
          operation === 'create' ? undefined : String(saved.pid),
          operation,
        );
      }
    } finally {
      await denied.context.close();
    }
    const afterDenied = await records();
    expect(afterDenied, 'denied CRUD must preserve the complete stored record').toEqual(beforeDenied);
    const unchanged = afterDenied[0];
    expect(unchanged[c.remark]).toBe('rule-gate-created');
    if (c.enabled) expect(unchanged[c.enabled]).toBe(false);
    await searchBusinessList(page, `/p/${c.model}`, marker);
    let row = page.getByRole('row').filter({ hasText: marker });
    await expect(row).toHaveCount(1);
    await expect(row).toContainText(marker);
    await clickRowActionByLocator(page, row, 'edit', '编辑');
    await expect(field(c.key).getByRole('textbox')).toHaveValue(marker);
    await field(c.remark).getByRole('textbox').fill('rule-gate-edited');
    await submit(page, `bom:update_${c.command}`);
    await expect.poll(async () => (await records())[0]?.[c.remark]).toBe('rule-gate-edited');
    await searchBusinessList(page, `/p/${c.model}`, marker);
    row = page.getByRole('row').filter({ hasText: marker });
    await clickRowActionByLocator(page, row, 'edit', '编辑');
    await expect(field(c.remark).getByRole('textbox')).toHaveValue('rule-gate-edited');
    const edited = (await records())[0];
    expect(edited[c.key]).toBe(marker);
    expect(edited[c.remark]).toBe('rule-gate-edited');
    await info.attach('rule-edited-browser', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    await info.attach('rule-edited-persistence', { body: JSON.stringify({ model: c.model, marker, saved, beforeDenied, afterDenied, edited }), contentType: 'application/json' });
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/${c.model}$`));
    await expect(page.getByTestId('dynamic-page-form')).toHaveCount(0);
    await searchBusinessList(page, `/p/${c.model}`, marker);
    row = page.getByRole('row').filter({ hasText: marker });
    if (c.allowDelete === false) {
      await expect(row).toContainText('rule-gate-edited');
      await expect(row.locator('[data-testid="row-action-delete"]')).toHaveCount(0);
      await expect(row.getByRole('button', { name: '删除', exact: true })).toHaveCount(0);
      expect(await records()).toHaveLength(1);
      const retained = await records();
      expect(retained).toEqual([edited]);
      await info.attach('rule-terminal-browser', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
      await info.attach('rule-terminal-persistence', { body: JSON.stringify({ model: c.model, marker, edited, retained, deletionSupported: false }), contentType: 'application/json' });
      return;
    }
    await clickRowActionByLocator(page, row, 'delete', '删除');
    await expect(page.getByTestId('confirm-dialog')).toBeVisible();
    await page.getByTestId('confirm-cancel').click();
    const afterCancel = await records();
    expect(afterCancel, 'cancel preserves the exact record').toEqual([edited]);
    await clickRowActionByLocator(page, row, 'delete', '删除');
    const deleted = page.waitForResponse(
      (r) =>
        decodeURIComponent(r.url()).includes(
          `/api/meta/commands/execute/bom:delete_${c.command}`,
        ) && r.request().method() === 'POST',
    );
    await page.getByTestId('confirm-ok').click();
    const response = await deleted;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    await expect.poll(async () => (await records()).length).toBe(0);
    await searchBusinessList(page, `/p/${c.model}`, marker);
    await expect(page.getByRole('row').filter({ hasText: marker })).toHaveCount(0);
    const afterDelete = await records();
    expect(afterDelete).toEqual([]);
    await info.attach('rule-terminal-browser', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    await info.attach('rule-terminal-persistence', { body: JSON.stringify({ model: c.model, marker, edited, afterCancel, afterDelete, deletionSupported: true }), contentType: 'application/json' });
  });
}


test('category metadata: duplicate category fails safely without changing the existing definition', async ({ page }, info) => {
  const records = () => queryDynamicRecords(page, 'bom_category_meta', [
    { fieldName: 'bom_cm_category', operator: 'EQ', value: 'resistor' },
  ]);
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  const before = await records();
  expect(before).toHaveLength(1);
  await page.goto('/p/bom_category_meta', { waitUntil: 'domcontentloaded' });
  await page.locator('main').getByRole('button', { name: '新建', exact: true }).click();
  await page.getByTestId('form-field-bom_cm_category').getByRole('combobox').first().click();
  await page.getByRole('option', { name: '电阻', exact: true }).click();
  await page.getByTestId('form-field-bom_cm_group').getByRole('combobox').first().click();
  await page.getByRole('option', { name: '阻性', exact: true }).click();
  const failure = page.waitForResponse((r) =>
    decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:create_category_meta') &&
    r.request().method() === 'POST');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const response = await failure;
  expect(response.status()).toBe(500);
  const body = await response.json();
  expect(String(body.code)).not.toBe('0');
  expect(JSON.stringify(body)).not.toMatch(/INSERT INTO|SQL:|DuplicateKeyException|Mapper\.xml|mt_bom_category_meta|duplicate key/i);
  await expect(page.getByTestId('dynamic-page-form')).toBeVisible();
  await expect(page.locator('body')).toContainText(/unexpected error|Internal system error|发生错误|操作失败|保存失败/i);
  await expect(page.locator('body')).not.toContainText(/INSERT INTO|Mapper\.xml|mt_bom_category_meta|duplicate key/i);
  expect(await records()).toEqual(before);
  await page.screenshot({ path: info.outputPath('duplicate-category-safe-error.png') });
});


test('header alias enabled/disabled affects new BOM conversions and preserves prior task snapshots with governed format-profile lifecycle', async ({ page }, info) => {
  test.setTimeout(360_000);
  const marker = `ZX${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
  const brand = 'GATERULEBRAND';
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
    ['物料名称', '规格', '位号', '数量', marker],
    ['贴片电阻', '10kΩ ±1% 0603', 'R1', 1, brand],
  ]), 'BOM');
  const file = info.outputPath('rule-effect.xlsx');
  fs.writeFileSync(file, XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }));
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  // Setup creates only customer/project; all rule changes and conversion starts use UI.
  const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
  const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
  expect(customerId).toBeTruthy();
  const project = await executeCommand(page, 'bom:create_project', {
    bom_project_name: marker, bom_project_customer_id: customerId, bom_pcba_code: marker,
    bom_project_library_source: 'excel_current_library',
  }, undefined, 'create');
  const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
  expect(projectId).toBeTruthy();
  const field = (name: string) => page.getByTestId(`form-field-${name}`);
  await ensureSidebarExpanded(page);
  const sidebar = page.getByTestId('sidebar');
  const ruleLink = sidebar.locator('a[href="/p/bom_header_alias"]');
  if (!(await ruleLink.isVisible())) {
    const center = sidebar.getByRole('button', { name: '规则中心', exact: true });
    if (!(await center.isVisible())) await sidebar.getByRole('button', { name: 'BOM转化工具', exact: true }).click();
    await center.click();
  }
  await ruleLink.click();
  await expect(page).toHaveURL(/\/p\/bom_header_alias$/);
  await page.locator('main').getByRole('button', { name: '新建', exact: true }).click();
  await field('bom_ha_alias').getByRole('textbox').fill(marker);
  await field('bom_ha_system_field').getByRole('combobox').first().click();
  await page.getByRole('option', { name: '品牌/厂家', exact: true }).click();
  const toggle = field('bom_ha_enabled').getByRole('switch');
  if (await toggle.getAttribute('aria-checked') !== 'true') await toggle.click();
  await toggle.click();
  await submit(page, 'bom:create_header_alias');
  const aliasRows = () => queryDynamicRecords(page, 'bom_header_alias', [{ fieldName: 'bom_ha_alias', operator: 'EQ', value: marker }]);
  expect(await aliasRows()).toHaveLength(1);
  expect((await aliasRows())[0].bom_ha_enabled).toBe(false);

  async function setEnabled(enabled: boolean) {
    await searchBusinessList(page, '/p/bom_header_alias', marker);
    await clickRowActionByLocator(page, page.getByRole('row').filter({ hasText: marker }), 'edit', '编辑');
    const control = field('bom_ha_enabled').getByRole('switch');
    if ((await control.getAttribute('aria-checked') === 'true') !== enabled) await control.click();
    await submit(page, 'bom:update_header_alias');
    expect((await aliasRows())[0].bom_ha_enabled).toBe(enabled);
  }
  const snapshots: { taskId: string; raw: Record<string, unknown>[]; standard: Record<string, unknown>[] }[] = [];
  async function convert(label: string) {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/p/bom_conversion_task_pcba_workbench"]').click();
    await expect(page.getByTestId('toolbar-btn-upload_bom')).toBeVisible();
    await page.getByTestId('toolbar-btn-upload_bom').click();
    for (const [name, id] of [['bom_task_customer_id', customerId], ['bom_task_project_id', projectId]]) {
      await page.getByTestId(`select-trigger-${name}`).click();
      await page.locator(`[role="option"][data-value="${id}"]`).first().click();
    }
    const upload = page.waitForResponse(r => r.url().includes('/api/file/upload') && r.request().method() === 'POST');
    await field('bom_task_raw_file_id').locator('input[type="file"]').first().setInputFiles(file);
    const uploaded = await upload;
    expect(uploaded.ok()).toBe(true);
    expect(String((await uploaded.json()).code)).toBe('0');
    const admission = page.waitForResponse(r => r.url().includes('/api/meta/commands/execute/bom:start_conversion') && r.request().method() === 'POST');
    await page.getByTestId('form-btn-start_conversion').click();
    const response = await admission;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    await page.waitForURL(/\/p\/bom_conversion_task_pcba_workbench\/view\/[^/?#]+/, { timeout: 30_000 });
    const taskId = new URL(page.url()).pathname.split('/').at(-1)!;
    await expect.poll(async () => {
      const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      return task.bom_task_status;
    }, { timeout: 90_000, intervals: [1000, 2000] }).toBe('completed');
    const raw = await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: taskId }]);
    const standard = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(raw).toHaveLength(1);
    expect(standard).toHaveLength(1);
    expect(raw[0].bom_raw_refdes).toBe('R1');
    expect(Number(raw[0].bom_raw_qty)).toBe(1);
    await expect(page.locator('main')).toContainText('BOM 匹配已完成', { timeout: 20_000 });
    const resultRow = page.getByRole('row').filter({ hasText: 'R1' }).first();
    await resultRow.scrollIntoViewIfNeeded();
    await expect(resultRow).toBeVisible();
    if (label === 'enabled') {
      await resultRow.click();
      const brandValue = page.getByTestId('review-drawer-tab-compare').getByText(brand, { exact: true }).first();
      await brandValue.scrollIntoViewIfNeeded();
      await expect(brandValue).toBeVisible();
    }
    await page.screenshot({ path: info.outputPath(`${label}.png`) });
    if (label === 'enabled') {
      await page.getByRole('button', { name: /关闭复核浮层|Close Review/i }).click();
      await expect(page.getByTestId('review-drawer-tab-compare')).toHaveCount(0);
    }
    const result = { taskId, raw, standard };
    snapshots.push(result);
    fs.writeFileSync(info.outputPath('rule-effect-evidence.json'), JSON.stringify({ marker, snapshots }, null, 2));
    return result;
  }
  const disabled = await convert('disabled');
  expect(disabled.raw[0].bom_raw_brand ?? '').toBe('');
  await setEnabled(true);
  const enabled = await convert('enabled');
  expect(enabled.raw[0].bom_raw_brand).toBe(brand);
  expect(enabled.standard[0].bom_std_brand).toBe(brand);
  await setEnabled(false);
  const disabledAgain = await convert('disabled-again');
  expect(disabledAgain.raw[0].bom_raw_brand ?? '').toBe('');
  for (const snapshot of snapshots) {
    const rows = await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: snapshot.taskId }]);
    expect(rows).toEqual(snapshot.raw);
  }
  info.annotations.push({ type: 'rule-effect', description: JSON.stringify({ marker, taskIds: snapshots.map(s => s.taskId) }) });

  // Real completed task -> candidate profile -> guarded lifecycle. No synthetic passing baseline.
  const lifecycle: Record<string, unknown>[] = [];
  async function profileAction(label: string, command: string, values: Record<string, string> | null, confirm = false, success = true) {
    const button = page.getByRole('button', { name: label, exact: true });
    await expect(button).toBeEnabled({ timeout: 20_000 });
    const pending = page.waitForResponse(r => decodeURIComponent(r.url()).includes(`/api/meta/commands/execute/${command}`) && r.request().method() === 'POST', { timeout: 30_000 });
    await button.click();
    if (confirm) await page.getByTestId('confirm-ok').click();
    if (values !== null) {
      const dialog = page.getByTestId('form-dialog');
      await expect(dialog).toBeVisible();
      for (const [key, value] of Object.entries(values))
        await dialog.getByTestId(`form-dialog-field-${key}`).fill(value);
      await dialog.getByTestId('form-dialog-submit').click();
    }
    const response = await pending;
    const body = await response.json();
    lifecycle.push({ command, status: response.status(), body });
    fs.writeFileSync(info.outputPath('profile-lifecycle-evidence.json'), JSON.stringify({ marker, lifecycle }, null, 2));
    if (success) {
      expect(response.status(), JSON.stringify(body)).toBe(200);
      expect(String(body.code), JSON.stringify(body)).toBe('0');
    } else {
      expect(response.status()).toBeGreaterThanOrEqual(400);
      expect(String(body.code)).not.toBe('0');
    }
    const commandData = body.data?.data ?? {};
    if (success && commandData.async === true) {
      expect(commandData.taskCode).toBeTruthy();
      const terminal = await pollAsyncTaskResult(page, String(commandData.taskCode));
      await expect(page.getByText(`${label}已完成`, { exact: true })).toBeVisible({ timeout: 20_000 });
      await page.getByRole('button', { name: '关闭', exact: true }).click();
      await expect(page.getByText(`${label}已完成`, { exact: true })).toHaveCount(0);
      lifecycle.push({ command, taskCode: commandData.taskCode, terminal });
      fs.writeFileSync(info.outputPath('profile-lifecycle-evidence.json'), JSON.stringify({ marker, lifecycle }, null, 2));
    }
    return body;
  }
  await profileAction('保存此格式供后续复用', 'bom:save_applied_parse_plan_profile', null, true);
  const profiles = () => queryDynamicRecords(page, 'bom_source_format_profile', [{ fieldName: 'bom_sfp_customer_id', operator: 'EQ', value: customerId }]);
  expect(await profiles()).toHaveLength(1);
  let profile = (await profiles())[0];
  let profileId = String(profile.pid);
  expect(profile.bom_sfp_status).toBe('candidate');
  expect(profile.bom_sfp_enabled).toBe(false);
  expect(profile.bom_sfp_auto_apply).toBe(false);
  await profileAction('保存此格式供后续复用', 'bom:save_applied_parse_plan_profile', null, true);
  expect(await profiles()).toHaveLength(1);
  expect((await profiles())[0].pid).toBe(profileId);
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  await ensureSidebarExpanded(page);
  const formatLink = page.getByTestId('sidebar').locator('a[href="/p/bom_source_format_profile"]');
  if (!(await formatLink.isVisible())) await page.getByTestId('sidebar').getByRole('button', { name: '规则中心', exact: true }).click();
  await formatLink.click();
  await searchBusinessList(page, '/p/bom_source_format_profile', String(profile.bom_sfp_code));
  let profileRow = page.getByRole('row').filter({ hasText: String(profile.bom_sfp_code) });
  await clickRowActionByLocator(page, profileRow, 'edit', '编辑');
  await field('bom_sfp_remark').getByRole('textbox').fill(`candidate edit ${marker}`);
  await submit(page, 'bom:update_source_format_profile');
  expect((await profiles())[0].bom_sfp_remark).toBe(`candidate edit ${marker}`);
  await searchBusinessList(page, '/p/bom_source_format_profile', String(profile.bom_sfp_code));
  profileRow = page.getByRole('row').filter({ hasText: String(profile.bom_sfp_code) });
  await clickRowActionByLocator(page, profileRow, 'edit', '编辑');
  await expect(field('bom_sfp_remark').getByRole('textbox')).toHaveValue(`candidate edit ${marker}`);
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await expect(page).toHaveURL(/\/p\/bom_source_format_profile$/);
  await searchBusinessList(page, '/p/bom_source_format_profile', String(profile.bom_sfp_code));
  profileRow = page.getByRole('row').filter({ hasText: String(profile.bom_sfp_code) });
  await clickRowActionByLocator(page, profileRow, 'delete', '删除');
  await page.getByTestId('confirm-cancel').click();
  expect((await profiles())[0].pid).toBe(profileId);
  await clickRowActionByLocator(page, profileRow, 'delete', '删除');
  const deletion = page.waitForResponse(r => r.url().includes('/api/meta/commands/execute/bom:delete_source_format_profile') && r.request().method() === 'POST');
  await page.getByTestId('confirm-ok').click();
  const deleted = await deletion;
  expect(deleted.status()).toBe(200);
  expect(String((await deleted.json()).code)).toBe('0');
  expect(await profiles()).toHaveLength(0);
  await expect(profileRow).toHaveCount(0);
  // A fresh explicit save after deleting an ungoverned candidate creates a new identity.
  await page.goto(`/p/bom_conversion_task_pcba_workbench/view/${disabledAgain.taskId}`, { waitUntil: 'domcontentloaded' });
  await profileAction('保存此格式供后续复用', 'bom:save_applied_parse_plan_profile', null, true);
  expect(await profiles()).toHaveLength(1);
  profile = (await profiles())[0];
  expect(String(profile.pid)).not.toBe(profileId);
  profileId = String(profile.pid);
  await searchBusinessList(page, '/p/bom_source_format_profile', String(profile.bom_sfp_code));
  await clickRowActionByLocator(page, page.getByRole('row').filter({ hasText: String(profile.bom_sfp_code) }), 'view', '查看');
  await expect(page).toHaveURL(new RegExp(`/view/${profileId}$`));
  const rejected = await profileAction('晋升为生效版本', 'bom:promote_source_format_profile', {}, false, false);
  expect(JSON.stringify(rejected)).toMatch(/regression|回归/i);
  expect((await profiles())[0].bom_sfp_status).toBe('candidate');
  if (await page.getByTestId('form-dialog').isVisible()) await page.getByTestId('form-dialog').getByRole('button', { name: '取消', exact: true }).click();
  await profileAction('运行历史样本回归', 'bom:run_source_format_profile_regression', { sampleLimit: '10', minPassRate: '100' }, true);
  profile = (await profiles())[0];
  expect(profile.bom_sfp_last_regression_run_id).toBeTruthy();
  // Three unconfirmed synthetic source files do not establish approved truth.
  expect(profile.bom_sfp_last_regression_status).toBe('insufficient_evidence');
  expect(Number(profile.bom_sfp_regression_case_count)).toBe(3);
  const regressionCases = await queryDynamicRecords(page, 'bom_profile_regression_case', [{ fieldName: 'bom_prc_run_id', operator: 'EQ', value: profile.bom_sfp_last_regression_run_id }]);
  expect(regressionCases).toHaveLength(3);
  expect(regressionCases.map(r => r.bom_prc_task_id).sort()).toEqual(snapshots.map(r => r.taskId).sort());
  expect(regressionCases.map(r => r.bom_prc_status)).toEqual(['passed', 'passed', 'passed']);
  lifecycle.push({ regressionRunId: profile.bom_sfp_last_regression_run_id, profile, regressionCases });
  fs.writeFileSync(info.outputPath('profile-lifecycle-evidence.json'), JSON.stringify({ marker, lifecycle }, null, 2));

  expect(profile.bom_sfp_status).toBe('candidate');
  await expect(page.getByText('回归证据不足，尚不能自动晋升', { exact: true })).toBeVisible();
  await expect(page.getByText(/解析通过率不代表确认依据充足/)).toBeVisible();
  await page.screenshot({ path: info.outputPath('profile-regression-blocked.png') });
  await profileAction('晋升为生效版本', 'bom:promote_source_format_profile', { overrideReason: `LOCAL E2E ${marker}: exercise explicit override on isolated customer only` });
  profile = (await profiles())[0];
  expect(profile.bom_sfp_status).toBe('active');
  expect(profile.bom_sfp_enabled).toBe(true);
  expect(profile.bom_sfp_auto_apply).toBe(true);
  await expect(page.getByRole('button', { name: '编辑候选修订', exact: true })).toHaveCount(0);
  const reused = await convert('profile-active');
  const reusedTask = await readDynamicRecord(page, 'bom_conversion_task_pcba', reused.taskId);
  const reusedDecision = JSON.parse(String(reusedTask.bom_task_header_mapping)).profileDecision;
  expect(reusedDecision.selectedProfileId).toBe(profileId);
  expect(reusedDecision.autoApplied).toBe(true);
  expect(reusedDecision.route).toBe('profile_auto_applied');
  lifecycle.push({ taskId: reused.taskId, profileDecision: reusedDecision });
  await page.goto(`/p/bom_source_format_profile/view/${profileId}`, { waitUntil: 'domcontentloaded' });

  await profileAction('隔离', 'bom:quarantine_source_format_profile', { reason: `E2E quarantine ${marker}` });
  profile = (await profiles())[0];
  expect(profile.bom_sfp_status).toBe('quarantined');
  expect(profile.bom_sfp_enabled).toBe(false);
  expect(profile.bom_sfp_auto_apply).toBe(false);
  const quarantined = await convert('profile-quarantined');
  const quarantinedTask = await readDynamicRecord(page, 'bom_conversion_task_pcba', quarantined.taskId);
  const quarantinedDecision = JSON.parse(String(quarantinedTask.bom_task_header_mapping)).profileDecision;
  expect(quarantinedDecision.selectedProfileId).not.toBe(profileId);
  expect(quarantinedDecision.autoApplied).toBe(false);
  expect(quarantinedDecision.route).toBe('no_profile');
  lifecycle.push({ taskId: quarantined.taskId, profileDecision: quarantinedDecision });
  await page.goto(`/p/bom_source_format_profile/view/${profileId}`, { waitUntil: 'domcontentloaded' });
  await profileAction('废弃修订', 'bom:deprecate_source_format_profile', { reason: `E2E deprecate ${marker}` });
  profile = (await profiles())[0];
  expect(profile.bom_sfp_status).toBe('deprecated');
  expect(profile.bom_sfp_enabled).toBe(false);
  await expect(page.getByRole('button', { name: '运行历史样本回归', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '晋升为生效版本', exact: true })).toHaveCount(0);
  await page.reload();
  await expect(page.locator('main')).toContainText('废弃');
  await page.screenshot({ path: info.outputPath('profile-deprecated.png') });
  for (const snapshot of snapshots)
    expect(await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: snapshot.taskId }])).toEqual(snapshot.raw);
  const deniedUser = makeQuoteRoleUser('profile-denied-proc', marker.toLowerCase(), ['qo_procurement']);
  await ensureQuoteRoleUser(page, deniedUser);
  const denied = await openQuoteRolePage(page.context().browser()!, deniedUser);
  try {
    await denied.page.goto(`/p/bom_source_format_profile/view/${profileId}`, { waitUntil: 'domcontentloaded' });
    await expect(denied.page.locator('main')).toContainText(/Page Unavailable|Access forbidden|Access denied|无权限|未授权|权限不足/i);
    for (const action of ['update', 'promote', 'run_source_format_profile_regression', 'quarantine', 'deprecate', 'delete']) {
      const command = action === 'run_source_format_profile_regression' ? `bom:${action}` : `bom:${action}_source_format_profile`;
      await expectCommandDenied(denied.page, command, { reason: 'unauthorized', overrideReason: 'unauthorized' }, profileId, action === 'delete' ? 'delete' : 'update');
    }
    expect((await profiles())[0]).toEqual(profile);
    await denied.page.screenshot({ path: info.outputPath('profile-denied.png') });
  } finally { await denied.context.close(); }
  info.annotations.push({ type: 'profile-lifecycle', description: JSON.stringify({ profileId, finalStatus: profile.bom_sfp_status }) });

});

for (const kind of ['unit','package'] as const) {
test(`${kind} rule enabled/disabled changes extracted attributes in real BOM conversions`, async ({ page }, info) => {
  test.setTimeout(360_000);
  const marker = `ZX${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
  const rawUnit = marker;
  const model=kind==='unit'?'bom_unit_rule':'bom_package_rule';
  const key=kind==='unit'?'bom_ur_raw':'bom_pr_package_code';
  const enabledField=kind==='unit'?'bom_ur_enabled':'bom_pr_enabled';
  const expected=kind==='unit'?'10Ω':marker;
  const actual=(row:Record<string,unknown>)=>kind==='unit'
    ? JSON.parse(String(row.bom_std_attributes_json)).resistance
    : JSON.parse(String(row.bom_std_attributes_json)).package;
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
    ['物料名称', '规格', '位号', '数量','封装'],
    ['贴片电阻', kind==='unit'?`10${rawUnit} ±1% 0603`:'10kΩ ±1%', 'R1', 1,kind==='unit'?'0603':`${marker}ALIAS`],
  ]), 'BOM');
  const file = info.outputPath('rule-effect.xlsx');
  fs.writeFileSync(file, XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }));
  await page.goto('/home', { waitUntil: 'domcontentloaded' });
  // Setup creates only customer/project; all rule changes and conversion starts use UI.
  const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
  const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
  expect(customerId).toBeTruthy();
  const project = await executeCommand(page, 'bom:create_project', {
    bom_project_name: marker, bom_project_customer_id: customerId, bom_pcba_code: marker,
    bom_project_library_source: 'excel_current_library',
  }, undefined, 'create');
  const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
  expect(projectId).toBeTruthy();
  const field = (name: string) => page.getByTestId(`form-field-${name}`);
  await ensureSidebarExpanded(page);
  const sidebar = page.getByTestId('sidebar');
  const ruleLink = sidebar.locator(`a[href="/p/${model}"]`);
  if (!(await ruleLink.isVisible())) {
    const center = sidebar.getByRole('button', { name: '规则中心', exact: true });
    if (!(await center.isVisible())) await sidebar.getByRole('button', { name: 'BOM转化工具', exact: true }).click();
    await center.click();
  }
  await ruleLink.click();
  await expect(page).toHaveURL(new RegExp(`/p/${model}$`));
  await page.locator('main').getByRole('button', { name: '新建', exact: true }).click();
  await field(key).getByRole('textbox').fill(marker);
  if(kind==='unit') {
    await field('bom_ur_standard').getByRole('textbox').fill('Ω');
    await field('bom_ur_category').getByRole('combobox').first().click();
    await page.getByRole('option', {name:'电阻',exact:true}).click();
  } else {
    await field('bom_pr_aliases').getByRole('textbox').fill(`${marker}ALIAS`);
    await field('bom_pr_categories').getByRole('textbox').fill('["resistor"]');
  }
  const toggle = field(enabledField).getByRole('switch');
  if (await toggle.getAttribute('aria-checked') !== 'true') await toggle.click();
  await toggle.click();
  await submit(page, `bom:create_${kind}_rule`);
  const aliasRows = () => queryDynamicRecords(page, model, [{ fieldName: key, operator: 'EQ', value: marker }]);
  expect(await aliasRows()).toHaveLength(1);
  expect((await aliasRows())[0][enabledField]).toBe(false);

  async function setEnabled(enabled: boolean) {
    await searchBusinessList(page, `/p/${model}`, marker);
    await clickRowActionByLocator(page, page.getByRole('row').filter({ hasText: marker }), 'edit', '编辑');
    const control = field(enabledField).getByRole('switch');
    if ((await control.getAttribute('aria-checked') === 'true') !== enabled) await control.click();
    await submit(page, `bom:update_${kind}_rule`);
    expect((await aliasRows())[0][enabledField]).toBe(enabled);
  }
  const snapshots: { taskId: string; raw: Record<string, unknown>[]; standard: Record<string, unknown>[] }[] = [];
  async function convert(label: string) {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/p/bom_conversion_task_pcba_workbench"]').click();
    await expect(page.getByTestId('toolbar-btn-upload_bom')).toBeVisible();
    await page.getByTestId('toolbar-btn-upload_bom').click();
    for (const [name, id] of [['bom_task_customer_id', customerId], ['bom_task_project_id', projectId]]) {
      await page.getByTestId(`select-trigger-${name}`).click();
      await page.locator(`[role="option"][data-value="${id}"]`).first().click();
    }
    const upload = page.waitForResponse(r => r.url().includes('/api/file/upload') && r.request().method() === 'POST');
    await field('bom_task_raw_file_id').locator('input[type="file"]').first().setInputFiles(file);
    const uploaded = await upload;
    expect(uploaded.ok()).toBe(true);
    expect(String((await uploaded.json()).code)).toBe('0');
    const admission = page.waitForResponse(r => r.url().includes('/api/meta/commands/execute/bom:start_conversion') && r.request().method() === 'POST');
    await page.getByTestId('form-btn-start_conversion').click();
    const response = await admission;
    expect(response.status()).toBe(200);
    expect(String((await response.json()).code)).toBe('0');
    await page.waitForURL(/\/p\/bom_conversion_task_pcba_workbench\/view\/[^/?#]+/, { timeout: 30_000 });
    const taskId = new URL(page.url()).pathname.split('/').at(-1)!;
    await expect.poll(async () => {
      const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      return task.bom_task_status;
    }, { timeout: 90_000, intervals: [1000, 2000] }).toBe('completed');
    const raw = await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: taskId }]);
    const standard = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(raw).toHaveLength(1);
    expect(standard).toHaveLength(1);
    expect(raw[0].bom_raw_refdes).toBe('R1');
    expect(Number(raw[0].bom_raw_qty)).toBe(1);
    await expect(page.locator('main')).toContainText('BOM 匹配已完成', { timeout: 20_000 });
    const resultRow = page.getByRole('row').filter({ hasText: 'R1' }).first();
    await resultRow.scrollIntoViewIfNeeded();
    await expect(resultRow).toBeVisible();
    await info.attach(`${kind}-rule-${label}`,{body:await page.screenshot({fullPage:true}),contentType:'image/png'});
    const result = { taskId, raw, standard };
    snapshots.push(result);
    fs.writeFileSync(info.outputPath('rule-effect-evidence.json'), JSON.stringify({ marker, snapshots }, null, 2));
    return result;
  }
  const disabled = await convert('disabled');
  expect(actual(disabled.standard[0]) ?? '').not.toBe(expected);
  await setEnabled(true);
  const enabled = await convert('enabled');
  expect(actual(enabled.standard[0])).toBe(expected);
  if (kind === 'package') {
    expect(enabled.raw[0].bom_raw_package).toBe(`${marker}ALIAS`);
    expect(enabled.standard[0].bom_std_package).toBe(`${marker}ALIAS`);
  }
  await setEnabled(false);
  const disabledAgain = await convert('disabled-again');
  expect(actual(disabledAgain.standard[0]) ?? '').not.toBe(expected);
  for (const snapshot of snapshots) {
    const rows = await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: snapshot.taskId }]);
    expect(rows).toEqual(snapshot.raw);
    expect(await queryDynamicRecords(page,'bom_standard_line_pcba',[{fieldName:'bom_std_task_id',operator:'EQ',value:snapshot.taskId}])).toEqual(snapshot.standard);
  }
  await info.attach(`${kind}-rule-results`,{body:JSON.stringify({marker,snapshots}),contentType:'application/json'});
  info.annotations.push({ type: 'rule-effect', description: JSON.stringify({ marker, taskIds: snapshots.map(s => s.taskId) }) });
});

}

for (const kind of ['aml', 'avl'] as const) {
  test(`${kind} governed relation: UI lifecycle, duplicate rejection and permission boundary`, async ({ page }, info) => {
    test.setTimeout(180_000);
    const marker = `REL${Date.now()}${Math.random().toString(16).slice(2, 6)}`;
    const model = `bom_${kind}_relation`;
    const prefix = `bom_${kind}_`;
    const field = (name: string) => page.getByTestId(`form-field-${prefix}${name}`);
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    // Isolated identity fixtures only; relation creation and every state change use UI.
    const item = await executeCommand(page, 'bom:create_item_master', {
      bom_im_item_code: marker, bom_im_item_name: marker, bom_im_category: 'resistor',
      bom_im_spec: '10kΩ 0603', bom_im_enabled: true,
    }, undefined, 'create');
    const mp = await executeCommand(page, 'bom:create_manufacturer_part', {
      bom_mp_mpn: `${marker}MP`, bom_mp_manufacturer_name: marker, bom_mp_enabled: true,
    }, undefined, 'create');
    const identity = (value: Record<string, unknown>) => String(value.recordId ?? value.pid ?? value.id ?? '');
    const itemId = identity(item);
    let sourceId = identity(mp);
    const sourceField = kind === 'aml' ? 'manufacturer_part_id' : 'supplier_part_id';
    if (kind === 'avl') {
      const supplier = await executeCommand(page, 'bom:create_supplier_part', {
        bom_sp_supplier_sku: `${marker}SP`, bom_sp_supplier_name: marker,
        bom_sp_manufacturer_part_id: sourceId, bom_sp_enabled: true,
      }, undefined, 'create');
      sourceId = identity(supplier);
    }
    expect(itemId).toBeTruthy(); expect(sourceId).toBeTruthy();
    const records = () => queryDynamicRecords(page, model,
      [{ fieldName: `${prefix}item_id`, operator: 'EQ', value: itemId }]);
    async function createRelation() {
      await page.goto(`/p/${model}`, { waitUntil: 'domcontentloaded' });
      await page.locator('main').getByRole('button', { name: '新增', exact: true }).click();
      await page.getByRole('button', { name: '保存', exact: true }).click();
      await expect(field('item_id').getByText('请选择内部物料', { exact: true })).toBeVisible();
      expect(await records()).toHaveLength(snapshots.length);
      for (const [name, id] of [['item_id', itemId], [sourceField, sourceId]]) {
        await page.getByTestId(`select-trigger-${prefix}${name}`).click();
        await page.locator(`[role="option"][data-value="${id}"]`).first().click();
      }
      await field('remark').getByRole('textbox').fill(`${marker}-${snapshots.length}`);
      await submit(page, `bom:create_${kind}_relation`);
      const rows = await records();
      expect(rows).toHaveLength(snapshots.length + 1);
      const created = rows.find(r => !snapshots.some(s => s.pid === r.pid))!;
      expect(created).toBeTruthy();
      expect(created[`${prefix}status`]).toBe('pending');
      snapshots.push(created);
      return String(created.pid);
    }
    async function edit(id: string, status: string) {
      await page.goto(`/p/${model}`, { waitUntil: 'domcontentloaded' });
      const saved = (await records()).find(r => r.pid === id)!;
      const statusLabel = ({ approved: '已批准', pending: '待审核', rejected: '已拒绝' } as Record<string, string>)[String(saved[`${prefix}status`])];
      expect(statusLabel).toBeTruthy();
      const row = page.getByRole('row').filter({ hasText: marker })
        .filter({ has: page.getByText(statusLabel, { exact: true }) });
      await expect(row).toHaveCount(1);
      await clickRowActionByLocator(page, row, 'edit', '编辑');
      await field('status').getByRole('combobox').first().click();
      await page.getByRole('option', { name: status, exact: true }).click();
    }
    const snapshots: Record<string, unknown>[] = [];
    const first = await createRelation();
    await edit(first, '已批准');
    await submit(page, `bom:update_${kind}_relation`);
    const approved = await readDynamicRecord(page, model, first);
    expect(approved[`${prefix}status`]).toBe('approved');
    const second = await createRelation();
    await edit(second, '已批准');
    const rejectedRequest = page.waitForResponse(r => r.url().includes(`/api/meta/commands/execute/bom:update_${kind}_relation`) && r.request().method() === 'POST');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const rejected = await rejectedRequest;
    const rejection = await rejected.json();
    expect(String(rejection.code)).not.toBe('0');
    expect(JSON.stringify(rejection)).toContain('Conflicting effective governed relation');
    expect((await readDynamicRecord(page, model, second))[`${prefix}status`]).toBe('pending');
    expect(await readDynamicRecord(page, model, first)).toEqual(approved);
    await page.getByRole('button', { name: '取消', exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/p/${model}$`));
    await edit(first, '已拒绝');
    await submit(page, `bom:update_${kind}_relation`);
    expect((await readDynamicRecord(page, model, first))[`${prefix}status`]).toBe('rejected');
    await edit(second, '已批准');
    await submit(page, `bom:update_${kind}_relation`);
    expect((await readDynamicRecord(page, model, second))[`${prefix}status`]).toBe('approved');
    const rejectedRecord = await readDynamicRecord(page, model, first);
    await edit(first, '已批准');
    const invalidTransition = page.waitForResponse(r => r.url().includes(`/api/meta/commands/execute/bom:update_${kind}_relation`) && r.request().method() === 'POST');
    await page.getByRole('button', { name: '保存', exact: true }).click();
    const invalidBody = await (await invalidTransition).json();
    expect(String(invalidBody.code)).not.toBe('0');
    expect(JSON.stringify(invalidBody)).toContain('rejected -> approved');
    expect(await readDynamicRecord(page, model, first)).toEqual(rejectedRecord);
    await page.getByRole('button', { name: '取消', exact: true }).click();
    const approvedRow = page.getByRole('row').filter({ hasText: marker })
      .filter({ has: page.getByText('已批准', { exact: true }) });
    await clickRowActionByLocator(page, approvedRow, 'view', '查看');
    await expect(page).toHaveURL(new RegExp(`/p/${model}/view/${second}$`));
    await expect(page.getByRole('heading', { name: `${kind.toUpperCase()} 关系详情`, exact: true })).toBeVisible();
    await expect(page.locator('main').getByText(`${marker}-1`, { exact: true })).toBeVisible();
    await expect(page.locator('main').getByText('已批准', { exact: true })).toBeVisible();
    await info.attach(`${kind}-approved-detail`, { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
    const beforeDenied = await records();
    const user = makeQuoteRoleUser('relation-denied-proc', marker.toLowerCase(), ['qo_procurement']);
    await ensureQuoteRoleUser(page, user);
    const denied = await openQuoteRolePage(page.context().browser()!, user);
    try {
      await expectCommandDenied(denied.page, `bom:create_${kind}_relation`, {
        [`${prefix}item_id`]: itemId, [`${prefix}${sourceField}`]: sourceId,
      }, undefined, 'create');
      await expectCommandDenied(denied.page, `bom:update_${kind}_relation`, {
        [`${prefix}status`]: 'rejected',
      }, second, 'update');
    } finally { await denied.context.close(); }
    expect(await records()).toEqual(beforeDenied);
    await info.attach(`${kind}-relation-evidence`, { body: JSON.stringify({ marker, first, second, rejection, invalidBody, records: beforeDenied }), contentType: 'application/json' });
  });
}

for (const kind of ['aml', 'avl'] as const) {
  test(`${kind} governance issue: review rejects missing or ineffective relation and persists an effective resolution`, async ({ page }, info) => {
    test.setTimeout(120_000);
    await page.goto('/home');
    const marker = `ISSUE${Date.now()}${kind}`;
    const created: { model: string; pid: string }[] = [];
    // Isolated source fixture: this test covers review, not automatic issue detection.
    const taskId = await dynamicCreate(page, 'bom_conversion_task_pcba', {
      bom_task_no: marker, bom_task_status: 'completed',
    }, created);
    // Issues are engine-owned records with no operator create command. Seed
    // only this input, preserving the task tenant/owner; never grant CRUD rights.
    const issueId = marker;
    const host = process.env.POSTGRES_HOST || '127.0.0.1';
    expect(['127.0.0.1', 'localhost', '::1']).toContain(host);
    expect(process.env.POSTGRES_DB).toMatch(/^enterprise_\d+$/);
    const sqlValue = (value: string) => `'${value.replaceAll("'", "''")}'`;
    execFileSync('psql', ['-X', '-v', 'ON_ERROR_STOP=1', '-c',
      `INSERT INTO mt_bom_relation_governance_issue
       (id,pid,tenant_id,created_by,bom_rgi_task_id,bom_rgi_raw_row_no,bom_rgi_relation_type,
        bom_rgi_identity_kind,bom_rgi_source_value,bom_rgi_normalized_value,bom_rgi_status)
       SELECT ${Date.now()},${sqlValue(issueId)},tenant_id,created_by,pid,2,${sqlValue(kind)},
        ${sqlValue(kind === 'aml' ? 'mpn' : 'supplier_part_no')},${sqlValue(marker)},${sqlValue(marker)},'pending'
       FROM mt_bom_conversion_task_pcba WHERE pid=${sqlValue(taskId)}`], {
      env: { ...process.env, PGHOST: host, PGPORT: process.env.POSTGRES_PORT,
        PGDATABASE: process.env.POSTGRES_DB, PGUSER: process.env.POSTGRES_USER,
        PGPASSWORD: process.env.POSTGRES_PASSWORD }, stdio: 'pipe',
    });
    expect((await readDynamicRecord(page, 'bom_relation_governance_issue', issueId)).bom_rgi_source_value).toBe(marker);
    const item = await executeCommand(page, 'bom:create_item_master', {
      bom_im_item_code: marker, bom_im_item_name: marker, bom_im_category: 'resistor',
      bom_im_spec: '10kΩ 0603', bom_im_enabled: true,
    }, undefined, 'create');
    const mp = await executeCommand(page, 'bom:create_manufacturer_part', {
      bom_mp_mpn: marker, bom_mp_manufacturer_name: marker, bom_mp_enabled: true,
    }, undefined, 'create');
    const id = (v: Record<string, unknown>) => String(v.recordId ?? v.pid ?? v.id ?? '');
    let sourceId = id(mp);
    if (kind === 'avl') sourceId = id(await executeCommand(page, 'bom:create_supplier_part', {
      bom_sp_supplier_sku: marker, bom_sp_supplier_name: marker,
      bom_sp_manufacturer_part_id: sourceId, bom_sp_enabled: true,
    }, undefined, 'create'));
    const prefix = `bom_${kind}_`;
    const relation = await executeCommand(page, `bom:create_${kind}_relation`, {
      [`${prefix}item_id`]: id(item),
      [`${prefix}${kind === 'aml' ? 'manufacturer_part_id' : 'supplier_part_id'}`]: sourceId,
      [`${prefix}status`]: 'pending',
    }, undefined, 'create');
    const relationId = id(relation); expect(relationId).toBeTruthy();
    await page.goto('/p/bom_relation_governance_issue');
    const row = page.getByRole('row').filter({ hasText: marker });
    await expect(row).toHaveCount(1);
    await clickRowActionByLocator(page, row, 'view', '查看证据');
    await expect(page.getByRole('heading', { name: '物料关系治理证据', exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: `打开 ${kind.toUpperCase()} 关系`, exact: true })).toBeVisible();
    await page.getByRole('button', { name: '审核处理', exact: true }).click();
    const field = (name: string) => page.getByTestId(`form-field-bom_rgi_${name}`);
    await expect(page).toHaveURL(new RegExp(`/edit/${issueId}`));
    await expect(field('source_value').getByRole('textbox')).toHaveValue(marker);
    await field('status').getByRole('combobox').click();
    await page.getByRole('option', { name: '已批准', exact: true }).click();
    const before = await readDynamicRecord(page, 'bom_relation_governance_issue', issueId);
    async function save() {
      const pending = page.waitForResponse(r => decodeURIComponent(r.url()).includes('/api/meta/commands/execute/bom:resolve_relation_governance_issue') && r.request().method() === 'POST');
      await page.getByRole('button', { name: '保存审核结果', exact: true }).click();
      return (await pending).json();
    }
    const missing = await save();
    expect(String(missing.code)).not.toBe('0');
    expect(JSON.stringify(missing)).toContain('resolution relation id is required');
    expect(await readDynamicRecord(page, 'bom_relation_governance_issue', issueId)).toEqual(before);
    await field('resolution_relation_id').getByRole('textbox').fill(relationId);
    const ineffective = await save();
    expect(String(ineffective.code)).not.toBe('0');
    expect(JSON.stringify(ineffective)).toContain('not effective');
    expect(await readDynamicRecord(page, 'bom_relation_governance_issue', issueId)).toEqual(before);
    await executeCommand(page, `bom:update_${kind}_relation`, { [`${prefix}status`]: 'approved' }, relationId, 'update');
    await field('resolution_remark').getByRole('textbox').fill(`Reviewed ${marker}`);
    await field('reviewed_by').getByRole('textbox').fill('Local governance reviewer');
    const accepted = await save(); expect(String(accepted.code)).toBe('0');
    const after = await readDynamicRecord(page, 'bom_relation_governance_issue', issueId);
    expect(after.bom_rgi_status).toBe('approved');
    expect(after.bom_rgi_resolution_relation_id).toBe(relationId);
    expect(after.bom_rgi_reviewed_by).toBe('Local governance reviewer');
    expect(after.bom_rgi_reviewed_at).toBeTruthy();
    expect(after.bom_rgi_source_value).toBe(marker);
    await page.goto('/p/bom_relation_governance_issue');
    await expect(row).toHaveCount(1);
    await clickRowActionByLocator(page, row, 'view', '查看证据');
    await expect(page.getByRole('heading', { name: '物料关系治理证据', exact: true })).toBeVisible();
    await expect(page.getByText(`Reviewed ${marker}`, { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: '审核处理', exact: true })).toHaveCount(0);
    await info.attach(`${kind}-issue-resolution`, { body: JSON.stringify({ before, missing, ineffective, after }), contentType: 'application/json' });
  });
}

// B04-04: 格式档案 — 保存后刷新与列表一致;必填缺失不发命令(编辑表单必填项清空后提交)。
test('B04-04 format profile: required-missing edit fires no command; saved rename survives reload in the list', async ({ page }, info) => {
  test.setTimeout(180_000);
  const marker = `SFP-B0404-${Date.now()}`;
  const field = (name: string) => page.getByTestId(`form-field-${name}`);
  const profileRows = () => queryDynamicRecords(page, 'bom_source_format_profile', [
    { fieldName: 'bom_sfp_code', operator: 'EQ', value: marker },
  ]);
  // 治理型页面无直接新建:经 API 种子(生命周期治理由 :340 用例覆盖)
  await dynamicCreate(page, 'bom_source_format_profile', {
    bom_sfp_code: marker,
    bom_sfp_name: `E2E 格式档案 ${marker}`,
    bom_sfp_status: 'candidate',
    bom_sfp_enabled: false,
    bom_sfp_auto_apply: false,
    bom_sfp_priority: 10,
    bom_sfp_revision: 1,
  }, []);

  let updateCalls = 0;
  page.on('request', (req) => {
    if (req.url().includes('/api/meta/commands/execute/bom:update_source_format_profile')) updateCalls += 1;
  });
  await page.goto('/p/bom_source_format_profile', { waitUntil: 'domcontentloaded' });
  await searchBusinessList(page, '/p/bom_source_format_profile', marker);
  const row = page.getByRole('row').filter({ hasText: marker });
  await expect(row.first()).toBeVisible({ timeout: 20_000 });
  await clickRowActionByLocator(page, row.first(), 'edit', '编辑');
  await field('bom_sfp_name').getByRole('textbox').fill('');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await page.waitForTimeout(1_500);
  expect(updateCalls, 'required-missing submit must not fire the update command').toBe(0);
  await expect(field('bom_sfp_name')).toContainText(/必填|不能为空|请输入|请填写|required/i);
  await info.attach('B04-04-required-missing', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });

  const newName = `E2E 格式档案改名 ${marker}`;
  await field('bom_sfp_name').getByRole('textbox').fill(newName);
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await page.waitForTimeout(1_500);
  expect(updateCalls, 'valid save fires the update command exactly once').toBe(1);
  expect((await profileRows())[0].bom_sfp_name).toBe(newName);

  await page.reload({ waitUntil: 'domcontentloaded' });
  await searchBusinessList(page, '/p/bom_source_format_profile', marker);
  await expect(page.locator('main')).toContainText(newName, { timeout: 20_000 });
  await info.attach('B04-04-rename-survives-reload', { body: await page.screenshot({ fullPage: true }), contentType: 'image/png' });
});

// B18-03: 客户料号映射 — 映射生效于新任务;历史任务快照不变;重复映射不产生第二份生效映射。
// CPN 召回(K1)输入 = 客户料号列(bom_raw_customer_part_no);根修(backlog 2026-07-25 §5.1)
// 后含该列的上传可复现分析期复合表头,通过签名守卫。
test('B18-03 part map: mapping applies to a new conversion, prior snapshots stay intact, duplicate stays single', async ({ page }, info) => {
  test.setTimeout(600_000);
  const marker = `B18-${Date.now()}`;
  const customerPn = `CUSTPNB18${Date.now()}`;
  // V2 召回查冻结算力投影:映射目标必须是库内已存在的标准料号(生产语义:
  // 客户料号 → 内部标准料号),新建料号不在冻结投影中不可召回
  const libraryMaterial = (
    await queryDynamicRecords(page, 'bom_material_master', [
      { fieldName: 'bom_mm_enabled', operator: 'EQ', value: true },
    ])
  ).find((row) => String(row.bom_mm_material_code ?? '').startsWith('10'))
    ?? (await queryDynamicRecords(page, 'bom_material_master', [
      { fieldName: 'bom_mm_enabled', operator: 'EQ', value: true },
    ]))[0];
  const mappedMaterial = String(libraryMaterial.bom_mm_material_code);
  const workbook = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(workbook, XLSX.utils.aoa_to_sheet([
    ['物料名称', '规格', '位号', '数量', 'MPN', '客户料号'],
    ['贴片电容', '100nF 50V 0402', 'C1', 1, customerPn, customerPn],
  ]), 'BOM');
  const file = info.outputPath('part-map-bom.xlsx');
  fs.writeFileSync(file, XLSX.write(workbook, { type: 'buffer', bookType: 'xlsx' }));

  const account = await executeCommand(page, 'crm:create_account', { crm_acc_name: marker }, undefined, 'create');
  const customerId = String(account.recordId ?? account.pid ?? account.id ?? '');
  const project = await executeCommand(page, 'bom:create_project', {
    bom_project_name: marker, bom_project_customer_id: customerId, bom_pcba_code: marker,
    bom_project_library_source: 'excel_current_library',
  }, undefined, 'create');
  const projectId = String(project.recordId ?? project.pid ?? project.projectId ?? '');
  const field = (name: string) => page.getByTestId(`form-field-${name}`);
  async function convert(): Promise<{ taskId: string; raw: Record<string, unknown>[]; standard: Record<string, unknown>[] }> {
    await page.goto('/home', { waitUntil: 'domcontentloaded' });
    await ensureSidebarExpanded(page);
    await page.getByTestId('sidebar').locator('a[href="/p/bom_conversion_task_pcba_workbench"]').click();
    await expect(page.getByTestId('toolbar-btn-upload_bom')).toBeVisible();
    await page.getByTestId('toolbar-btn-upload_bom').click();
    for (const [name, id] of [['bom_task_customer_id', customerId], ['bom_task_project_id', projectId]]) {
      await page.getByTestId(`select-trigger-${name}`).click();
      await page.locator(`[role="option"][data-value="${id}"]`).first().click();
    }
    const upload = page.waitForResponse(r => r.url().includes('/api/file/upload') && r.request().method() === 'POST');
    await field('bom_task_raw_file_id').locator('input[type="file"]').first().setInputFiles(file);
    expect((await upload).ok()).toBe(true);
    const admission = page.waitForResponse(r => r.url().includes('/api/meta/commands/execute/bom:start_conversion') && r.request().method() === 'POST');
    await page.getByTestId('form-btn-start_conversion').click();
    expect((await admission).status()).toBe(200);
    await page.waitForURL(/\/p\/bom_conversion_task_pcba_workbench\/view\/[^/?#]+/, { timeout: 30_000 });
    const taskId = new URL(page.url()).pathname.split('/').at(-1)!;
    await expect.poll(async () => {
      const task = await readDynamicRecord(page, 'bom_conversion_task_pcba', taskId);
      return task.bom_task_status;
    }, { timeout: 90_000, intervals: [1000, 2000] }).toBe('completed');
    const raw = await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: taskId }]);
    const standard = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: taskId }]);
    expect(raw).toHaveLength(1);
    expect(standard).toHaveLength(1);
    return { taskId, raw, standard };
  }

  const before = await convert();
  expect(String(before.standard[0].bom_std_material_code ?? '')).not.toBe(mappedMaterial);

  await page.goto('/p/bom_customer_part_map', { waitUntil: 'domcontentloaded' });
  await page.locator('main').getByRole('button', { name: '新建', exact: true }).first().click();
  await field('bom_cpm_customer_id').getByRole('textbox').fill(customerId);
  await field('bom_cpm_customer_pn_raw').getByRole('textbox').fill(customerPn);
  await field('bom_cpm_customer_pn_norm').getByRole('textbox').fill(customerPn);
  await field('bom_cpm_material_code').getByRole('textbox').fill(mappedMaterial);
  await field('bom_cpm_status').getByRole('combobox').first().click();
  await page.getByRole('option', { name: '生效', exact: true }).click();
  await submit(page, 'bom:create_customer_part_map');
  const mappings = await queryDynamicRecords(page, 'bom_customer_part_map', [
    { fieldName: 'bom_cpm_customer_pn_norm', operator: 'EQ', value: customerPn },
  ]);
  expect(mappings, 'mapping persisted').toHaveLength(1);
  // 映射目标已在冻结物料库中,无需建料(物料库投影按快照绑定)
  // V2 召回查物料投影而非主档:建料后刷新投影,新料才进入 K1 召回视野
  await executeCommand(page, 'bom:refresh_material_snapshot', {});

  const after = await convert();
  // 上传含客户料号列的任务通过签名守卫并完成转换(根修 backlog 2026-07-25 §5.1 生效)。
  expect(after.taskId, 'post-mapping conversion completes').toBeTruthy();
  // “映射生效”验收 K1 的可审计决策证据。默认 auto-green 关闭时黄态不得把候选
  // 写进 bom_std_material_code；首候选/decision candidate 才是此处的权威输出。
  expect(String(after.standard[0].bom_std_candidate_codes ?? '').split(',')[0]).toBe(mappedMaterial);
  const mappedEvidence = await queryDynamicRecords(page, 'bom_match_evidence', [
    { fieldName: 'bom_me_task_id', operator: 'EQ', value: after.taskId },
    { fieldName: 'bom_me_material_code', operator: 'EQ', value: mappedMaterial },
  ]);
  expect(mappedEvidence, 'mapped material has one auditable match-evidence row').toHaveLength(1);
  const evidence = JSON.parse(String(mappedEvidence[0].bom_me_evidence_json ?? '{}'));
  expect(evidence.lanes).toContain('K1_CPN_ACTIVE');
  expect(evidence.decisionCandidateCode).toBe(mappedMaterial);
  expect(evidence.isDecisionCandidate).toBe(true);
  expect(evidence.identityOrderingPriority).toBe(3);

  const rawBefore = await queryDynamicRecords(page, 'bom_raw_line_pcba', [{ fieldName: 'bom_raw_task_id', operator: 'EQ', value: before.taskId }]);
  const standardBefore = await queryDynamicRecords(page, 'bom_standard_line_pcba', [{ fieldName: 'bom_std_task_id', operator: 'EQ', value: before.taskId }]);
  expect(rawBefore).toEqual(before.raw);
  expect(standardBefore).toEqual(before.standard);

  await page.goto('/p/bom_customer_part_map', { waitUntil: 'domcontentloaded' });
  await page.locator('main').getByRole('button', { name: '新建', exact: true }).first().click();
  await field('bom_cpm_customer_id').getByRole('textbox').fill(customerId);
  await field('bom_cpm_customer_pn_raw').getByRole('textbox').fill(customerPn);
  await field('bom_cpm_customer_pn_norm').getByRole('textbox').fill(customerPn);
  await field('bom_cpm_material_code').getByRole('textbox').fill(`${mappedMaterial}DUP`);
  await field('bom_cpm_status').getByRole('combobox').first().click();
  await page.getByRole('option', { name: '生效', exact: true }).click();
  const dupResponse = page.waitForResponse(r => r.url().includes('/api/meta/commands/execute/bom:create_customer_part_map') && r.request().method() === 'POST');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  const duplicate = await dupResponse;
  expect(duplicate.status()).toBe(500);
  const dupBody = await duplicate.json().catch(() => ({}));
  expect(String(dupBody.code)).not.toBe('0');
  expect(JSON.stringify(dupBody)).not.toMatch(/INSERT INTO|SQL:|DuplicateKeyException|Mapper\.xml|mt_bom_customer_part_map|duplicate key/i);
  const finalMappings = await queryDynamicRecords(page, 'bom_customer_part_map', [
    { fieldName: 'bom_cpm_customer_pn_norm', operator: 'EQ', value: customerPn },
  ]);
  expect(finalMappings, 'duplicate mapping is rejected without creating another row').toHaveLength(1);
  expect(finalMappings[0].bom_cpm_status).toBe('active');
  expect(finalMappings[0].bom_cpm_material_code).toBe(mappedMaterial);
  await info.attach('B18-03-part-map-evidence', {
    body: JSON.stringify({
      marker,
      before: before.taskId,
      after: after.taskId,
      mappedMaterial,
      lanes: evidence.lanes,
      decisionCandidateCode: evidence.decisionCandidateCode,
      mappings: finalMappings.length,
      duplicateStatus: duplicate.status(),
    }),
    contentType: 'application/json',
  });
});
