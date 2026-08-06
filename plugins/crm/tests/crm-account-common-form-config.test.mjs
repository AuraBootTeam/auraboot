import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const configRoot = new URL('../config/', import.meta.url);

async function readJson(path) {
  return JSON.parse(await readFile(new URL(path, configRoot), 'utf8'));
}

const [fields, bindings, formPage, listPage, commands, dicts, menus] = await Promise.all([
  readJson('fields/crm_account_common.json'),
  readJson('bindings/crm_account_common.json'),
  readJson('pages/crm_account_common_form.json'),
  readJson('pages/crm_account_common_list.json'),
  readJson('commands/crm_account_common.json'),
  readJson('dicts.json'),
  readJson('menus.json'),
]);

const fieldByCode = new Map(fields.map((field) => [field.code, field]));
const bindingByField = new Map(bindings.map((binding) => [binding.fieldCode, binding]));
const commandByCode = new Map(commands.map((command) => [command.code, command]));
const dictByCode = new Map(dicts.map((dict) => [dict.code, dict]));
const menuByCode = new Map(menus.map((menu) => [menu.code, menu]));

function formField(fieldCode) {
  const section = (formPage.blocks ?? []).find((block) => block.id === 'basic');
  assert.ok(section, 'account form should expose the basic section');
  const found = (section.fields ?? []).find((field) => field.field === fieldCode);
  assert.ok(found, `account form should expose ${fieldCode}`);
  return found;
}

function listColumn(fieldCode) {
  const table = (listPage.blocks ?? []).find((block) => block.id === 'crm_account_table');
  assert.ok(table, 'account list should expose the main table');
  const found = (table.columns ?? []).find((column) => column.field === fieldCode);
  assert.ok(found, `account list should expose ${fieldCode}`);
  return found;
}

function listColumnCodes() {
  const table = (listPage.blocks ?? []).find((block) => block.id === 'crm_account_table');
  assert.ok(table, 'account list should expose the main table');
  return (table.columns ?? []).map((column) => column.field);
}

test('account industry is a dictionary-backed dropdown aligned to tenant industry options', () => {
  const industry = fieldByCode.get('crm_acc_industry');
  assert.ok(industry, 'crm_acc_industry field should exist');
  assert.equal(industry.dataType, 'enum');
  assert.equal(industry.dictCode, 'crm_account_industry');

  const dict = dictByCode.get('crm_account_industry');
  assert.ok(dict, 'crm_account_industry dict should exist');
  const labelsByValue = new Map((dict.items ?? []).map((item) => [item.value, item['label:zh-CN']]));
  for (const [value, label] of [
    ['retail', '零售业'],
    ['catering', '餐饮业'],
    ['education', '教育培训'],
    ['healthcare', '医疗健康'],
    ['finance', '金融服务'],
    ['technology', '科技互联网'],
    ['manufacturing', '制造业'],
    ['logistics', '物流运输'],
    ['real_estate', '房地产'],
    ['entertainment', '文化娱乐'],
    ['other', '其他'],
  ]) {
    assert.equal(labelsByValue.get(value), label, `tenant industry ${value} should be available`);
  }

  for (const legacyValue of [
    'tech',
    'electronics',
    'automotive',
    'semiconductor',
    'consumer_electronics',
    'building_tech',
  ]) {
    assert.ok(labelsByValue.has(legacyValue), `legacy CRM industry ${legacyValue} should remain displayable`);
  }

  assert.equal(formField('crm_acc_industry').dictCode, 'crm_account_industry');
  assert.equal(listColumn('crm_acc_industry').dictCode, 'crm_account_industry');
  assert.equal(listColumn('crm_acc_industry').renderType, 'tag');
});

test('account list hides private health/contact columns and resolves owner name', () => {
  const columns = listColumnCodes();
  for (const hidden of [
    'crm_acc_phone',
    'crm_acc_rating',
    'crm_acc_health_score',
    'crm_acc_health_band',
  ]) {
    assert.equal(columns.includes(hidden), false, `${hidden} should be hidden from account list`);
  }

  const owner = listColumn('crm_acc_owner');
  assert.equal(owner.label?.['zh-CN'] ?? owner.label, '负责人');
  assert.equal(owner.refTarget?.modelCode, 'sys_user');
  assert.equal(owner.refTarget?.valueField, 'pid');
  assert.equal(owner.refTarget?.displayField, 'displayName');
});

test('account menu is exposed as an independent root menu', () => {
  const accountMenu = menuByCode.get('crm_accounts');
  assert.ok(accountMenu, 'crm_accounts menu should exist');
  assert.equal(accountMenu.parentCode, null);
  assert.equal(accountMenu['name:zh-CN'], '客户');
  assert.equal(accountMenu.path, '/p/crm_account_common');
});

test('account status is required on create form and defaults to active', () => {
  const status = fieldByCode.get('crm_acc_status');
  assert.ok(status, 'crm_acc_status field should exist');
  assert.equal(status.dataType, 'enum');
  assert.equal(status.dictCode, 'crm_account_status');
  assert.equal(status.constraints?.required, true);
  assert.equal(status.defaultValue, 'active');

  const binding = bindingByField.get('crm_acc_status');
  assert.ok(binding, 'crm_acc_status binding should exist');
  assert.equal(binding.required, true);
  assert.equal(binding.editable, true);

  const pageField = formField('crm_acc_status');
  assert.equal(pageField.required, true);
  assert.equal(pageField.defaultValue, 'active');
  assert.equal(pageField.dictCode, 'crm_account_status');

  const create = commandByCode.get('crm:create_account');
  assert.ok(create, 'crm:create_account command should exist');
  assert.ok(create.inputFields.includes('crm_acc_status'), 'create account should accept the selected status');
  assert.equal(create.autoSetFields?.crm_acc_status?.strategy, 'default_value');
  assert.equal(create.autoSetFields?.crm_acc_status?.value, 'active');
});
