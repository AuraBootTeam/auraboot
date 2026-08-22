import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const pluginRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const readJson = (relativePath) =>
  JSON.parse(fs.readFileSync(path.join(pluginRoot, relativePath), 'utf8'));

const fields = new Map(
  readJson('config/fields/crm_contact_common.json').map((field) => [field.code, field]),
);
const bindings = new Map(
  readJson('config/bindings/crm_contact_common.json').map((binding) => [binding.fieldCode, binding]),
);
const commands = new Map(
  readJson('config/commands/crm_contact_common.json').map((command) => [command.code, command]),
);
const dicts = new Map(readJson('config/dicts.json').map((dict) => [dict.code, dict]));
const listPage = readJson('config/pages/crm_contact_common_list.json');
const detailPage = readJson('config/pages/crm_contact_common_detail.json');
const activityList = readJson('config/pages/crm_activity_common_list.json');
const activityDetail = readJson('config/pages/crm_activity_common_detail.json');
const activityCommands = new Map(
  readJson('config/commands/crm_activity_common.json').map((command) => [command.code, command]),
);
const savedViews = readJson('config/saved-views.json');

const findBlock = (page, id) => page.blocks.find((block) => block.id === id);

test('contacts expose an explicit active/inactive lifecycle instead of overloading deletion', () => {
  const status = fields.get('crm_ct_status');
  assert.equal(status.dataType, 'enum');
  assert.equal(status.dictCode, 'crm_contact_status');
  assert.equal(status.defaultValue, 'active');
  assert.equal(bindings.get('crm_ct_status').editable, false);
  assert.equal(fields.get('crm_ct_primary_account_key').constraints.unique, true);
  assert.equal(bindings.get('crm_ct_primary_account_key').visible, false);
  assert.equal(bindings.get('crm_ct_primary_account_key').editable, false);

  assert.deepEqual(
    dicts.get('crm_contact_status').items.map((item) => item.value),
    ['active', 'inactive'],
  );
  assert.deepEqual(commands.get('crm:disable_contact').fromStates, ['active']);
  assert.equal(commands.get('crm:disable_contact').toState, 'inactive');
  assert.deepEqual(commands.get('crm:enable_contact').fromStates, ['inactive']);
  assert.equal(commands.get('crm:enable_contact').toState, 'active');
  assert.equal(
    commands.get('crm:create_contact').autoSetFields.crm_ct_status.value,
    'active',
  );
  assert.equal(commands.get('crm:set_primary_contact').type, 'custom');
  assert.equal(commands.get('crm:set_primary_contact').handler, 'crm:set_primary_contact');
});

test('primary-contact and lifecycle actions are available from list and detail with state guards', () => {
  const table = findBlock(listPage, 'crm_contact_table');
  const listActions = new Map(
    table.columns.find((column) => column.field === 'actions').buttons.map((button) => [
      button.code,
      button,
    ]),
  );
  const toolbar = findBlock(detailPage, 'crm_contact_detail_toolbar');
  const detailActions = new Map(toolbar.buttons.map((button) => [button.code, button]));

  for (const actions of [listActions, detailActions]) {
    assert.equal(actions.get('set_primary').action.command, 'crm:set_primary_contact');
    assert.match(actions.get('set_primary').visibleWhen, /crm_ct_is_primary/);
    assert.equal(actions.get('disable').action.command, 'crm:disable_contact');
    assert.match(actions.get('disable').visibleWhen, /active/);
    assert.equal(actions.get('enable').action.command, 'crm:enable_contact');
    assert.match(actions.get('enable').visibleWhen, /inactive/);
  }
  assert.equal(detailActions.get('log_activity').label['zh-CN'], '记录跟进');
  assert.equal(detailActions.get('create_task').label['zh-CN'], '新建计划');
});

test('follow-up list distinguishes plans from records and detail exposes governed deletion', () => {
  const tabs = new Map(findBlock(activityList, 'crm_act_tabs').tabs.map((tab) => [tab.key, tab]));
  assert.equal(tabs.get('follow_plans').filter.value, 'task');
  assert.equal(tabs.get('follow_plans').filter.operator, 'EQ');
  assert.deepEqual(tabs.get('follow_records').filter.value, [
    'call',
    'email',
    'meeting',
    'note',
    'visit',
  ]);
  assert.equal(tabs.get('follow_records').filter.operator, 'IN');

  const toolbar = findBlock(activityDetail, 'crm_activity_detail_toolbar');
  const actions = new Map(toolbar.buttons.map((button) => [button.code, button]));
  assert.equal(actions.get('delete').action.command, undefined);
  assert.equal(actions.get('delete').action.type, 'command');
  assert.equal(activityCommands.get('crm:delete_activity').type, 'delete');
  assert.equal(actions.get('delete').permissionCode, 'crm.activity.manage');
  assert.ok(actions.get('delete').confirm['zh-CN']);
  for (const code of ['start_task', 'complete_task', 'cancel_task']) {
    assert.match(actions.get(code).visibleWhen, /crm_act_type/);
    assert.match(actions.get(code).visibleWhen, /crm_act_status/);
  }
});

test('activity defaults to a valid table view and keeps a renderable calendar alternative', () => {
  const activityViews = savedViews.filter((view) => view.modelCode === 'crm_activity_common');
  const table = activityViews.find((view) => view.viewType === 'table');
  const calendar = activityViews.find((view) => view.viewType === 'calendar');
  assert.equal(table.isDefault, true);
  assert.equal(table.viewConfig.sorts[0].fieldCode, 'crm_act_date');
  assert.equal(calendar.isDefault, false);
  assert.equal(calendar.viewConfig.calendarDateField, 'crm_act_date');
  assert.doesNotMatch(JSON.stringify(activityViews), /crm_act_activity_date/);
});
