import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const configRoot = new URL('../config/', import.meta.url);

async function readJson(path) {
  return JSON.parse(await readFile(new URL(path, configRoot), 'utf8'));
}

const [fields, bindings, formPage, listPage, detailPage, commands, savedViews] = await Promise.all([
  readJson('fields/crm_lead_common.json'),
  readJson('bindings/crm_lead_common.json'),
  readJson('pages/crm_lead_common_form.json'),
  readJson('pages/crm_lead_common_list.json'),
  readJson('pages/crm_lead_common_detail.json'),
  readJson('commands/crm_lead_common.json'),
  readJson('saved-views.json'),
]);

const fieldByCode = new Map(fields.map((field) => [field.code, field]));
const bindingByField = new Map(bindings.map((binding) => [binding.fieldCode, binding]));
const commandByCode = new Map(commands.map((command) => [command.code, command]));

function formField(fieldCode) {
  const section = (formPage.blocks ?? []).find((block) => block.id === 'basic');
  assert.ok(section, 'lead form should expose the basic section');
  const found = (section.fields ?? []).find((field) => field.field === fieldCode);
  assert.ok(found, `lead form should expose ${fieldCode}`);
  return found;
}

function listColumn(fieldCode) {
  const table = (listPage.blocks ?? []).find((block) => block.id === 'crm_lead_table');
  assert.ok(table, 'lead list should expose crm_lead_table');
  const found = (table.columns ?? []).find((column) => column.field === fieldCode);
  assert.ok(found, `lead list should expose ${fieldCode}`);
  return found;
}

function detailField(fieldCode) {
  const stack = [...(detailPage.blocks ?? [])];
  while (stack.length > 0) {
    const block = stack.shift();
    const found = (block.fields ?? []).find((field) => field.field === fieldCode);
    if (found) return found;
    stack.push(...(block.children ?? []));
    for (const tab of block.tabs ?? []) {
      stack.push(...(tab.blocks ?? []));
    }
  }
  assert.fail(`lead detail should expose ${fieldCode}`);
}

test('lead assignee is a real ab_user reference across lead surfaces', () => {
  const assignee = fieldByCode.get('crm_lead_assigned_to');
  assert.ok(assignee, 'crm_lead_assigned_to field should exist');
  assert.equal(assignee.dataType, 'reference');
  assert.deepEqual(assignee.refTarget, {
    targetModel: 'ab_user',
    targetField: 'user_name',
  });

  assert.ok(bindingByField.has('crm_lead_assigned_to'), 'assignee binding should remain registered');
  assert.ok(formField('crm_lead_assigned_to'));
  assert.ok(listColumn('crm_lead_assigned_to'));
  assert.ok(detailField('crm_lead_assigned_to'));

  assert.ok(commandByCode.get('crm:create_lead')?.inputFields.includes('crm_lead_assigned_to'));
  assert.ok(commandByCode.get('crm:update_lead')?.inputFields.includes('crm_lead_assigned_to'));

  const views = Array.isArray(savedViews) ? savedViews : savedViews.views ?? [];
  const kanban = views.find(
    (view) => view.modelCode === 'crm_lead_common' && view.viewType === 'kanban',
  );
  assert.ok(kanban, 'lead kanban saved view should exist');
  assert.ok(
    kanban.viewConfig?.cardFields?.some((field) => field.field === 'crm_lead_assigned_to'),
    'lead kanban should continue to use the assignee field',
  );
});
