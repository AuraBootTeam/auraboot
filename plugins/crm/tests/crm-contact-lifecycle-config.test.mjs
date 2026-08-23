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
const formPage = readJson('config/pages/crm_contact_common_form.json');
const detailPage = readJson('config/pages/crm_contact_common_detail.json');
const accountDetailPage = readJson('config/pages/crm_account_common_detail.json');
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
  assert.equal(commands.get('crm:delete_contact').type, 'custom');
  assert.equal(commands.get('crm:delete_contact').handler, 'crm:delete_contact');
});

test('contact ownership is resolvable, required and defaults to the current user without blocking reassignment', () => {
  const owner = fields.get('crm_ct_owner');
  assert.equal(owner.dataType, 'reference');
  assert.equal(owner.referenceModelCode, 'sys_user');
  assert.deepEqual(owner.refTarget, {
    targetModel: 'sys_user',
    valueField: 'pid',
    displayField: 'displayName',
  });
  assert.equal(bindings.get('crm_ct_owner').required, true);
  assert.equal(bindings.get('crm_ct_owner').visible, true);
  assert.equal(bindings.get('crm_ct_owner').editable, true);

  const create = commands.get('crm:create_contact');
  const update = commands.get('crm:update_contact');
  assert.equal(create.inputFields.includes('crm_ct_owner'), true);
  assert.equal(update.inputFields.includes('crm_ct_owner'), true);
  assert.deepEqual(create.autoSetFields.crm_ct_owner, {
    strategy: 'current_user_pid',
    preserveInput: true,
  });

  const identity = findBlock(formPage, 'identity_and_ownership');
  const ownerField = identity.fields.find((field) => field.field === 'crm_ct_owner');
  assert.equal(ownerField.required, true);
  assert.ok(ownerField.placeholder['zh-CN']);
  assert.match(ownerField.helpText['zh-CN'], /当前用户/);
});

test('contact saved views expose all, current-user and department ownership scopes', () => {
  const contactViews = savedViews
    .filter((view) => view.modelCode === 'crm_contact_common')
    .sort((left, right) => left.sortOrder - right.sortOrder);
  assert.deepEqual(contactViews.map((view) => view.viewKey), [
    'crm_contact_all_table',
    'crm_contact_my_table',
    'crm_contact_department_table',
  ]);
  assert.deepEqual(contactViews.map((view) => view.isDefault), [true, false, false]);
  assert.deepEqual(contactViews.slice(1).map((view) => view.quickFilterOrder), [1, 2]);
  assert.deepEqual(contactViews[1].viewConfig.filters[0], {
    fieldCode: 'crm_ct_owner',
    operator: 'eq',
    value: null,
    isExpression: true,
    expression: '#currentUser',
  });
  assert.deepEqual(contactViews[2].viewConfig.filters[0], {
    fieldCode: 'crm_ct_owner',
    operator: 'in',
    value: null,
    isExpression: true,
    expression: '#currentDepartmentOwners',
  });
});

test('contact list exposes ownership, discoverable search and explicit empty-state recovery', () => {
  const table = findBlock(listPage, 'crm_contact_table');
  assert.equal(table.columns.some((column) => column.field === 'crm_ct_owner'), true);
  assert.deepEqual(table.searchFields, [
    'crm_ct_name',
    'crm_ct_email',
    'crm_ct_phone',
    'crm_ct_mobile',
  ]);
  assert.match(table.searchPlaceholder['zh-CN'], /姓名、邮箱或电话/);
  assert.match(table.empty.description['zh-CN'], /全部 \/ 我的 \/ 本部门/);
});

test('account detail keeps the canonical editable contact related-list contract', () => {
  const tabs = findBlock(accountDetailPage, 'crm_account_tabs');
  const contacts = tabs.tabs.find((tab) => tab.key === 'contacts');
  const related = contacts.blocks.find((block) => block.id === 'block_contacts');
  assert.equal(related.blockType, 'sub-table');
  assert.equal(related.subTable.childModel, 'crm_contact_common');
  assert.equal(related.subTable.parentField, 'crm_ct_account_id');
  assert.equal(related.subTable.readOnly, false);
  assert.equal(related.subTable.columns.some((column) => column.field === 'crm_ct_owner'), true);
  assert.equal(related.subTable.columns.some((column) => column.field === 'crm_ct_status'), true);

  const actions = new Map(related.subTable.actions.map((action) => [action.code, action]));
  assert.deepEqual([...actions.keys()], ['add', 'edit', 'delete']);
  assert.equal(actions.get('add').action.command, 'crm:create_contact');
  assert.equal(actions.get('edit').action.command, 'crm:update_contact');
  assert.equal(actions.get('delete').action.command, 'crm:delete_contact');
  for (const action of actions.values()) {
    assert.equal(action.permissionCode, 'crm.contact.manage');
  }
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
  assert.equal(listActions.get('delete').action.command, 'crm:delete_contact');
  assert.equal(detailActions.get('log_activity').label['zh-CN'], '记录跟进');
  assert.equal(detailActions.get('create_task').label['zh-CN'], '新建计划');
});

test('contact list exposes governed bulk edit, delete, export and update import', () => {
  const table = findBlock(listPage, 'crm_contact_table');
  assert.equal(table.table.selection.mode, 'multiple');
  assert.equal(table.selection.type, 'checkbox');
  assert.equal(table.table.bulkCapabilities.edit.permissionCode, 'crm.contact.manage');
  assert.equal(table.table.bulkCapabilities.export.permissionCode, 'crm.contact.read');
  assert.equal(table.table.bulkCapabilities.delete, undefined);
  const deleteAction = table.table.bulkActions.find(
    (action) => action.code === 'bulk_delete_contacts',
  );
  assert.equal(deleteAction.action.type, 'bulk_record_command');
  assert.equal(deleteAction.action.command, 'crm:delete_contact');
  assert.equal(deleteAction.action.operationType, undefined);
  assert.deepEqual(listPage.extension.import.modes, ['insert', 'update']);
  assert.deepEqual(listPage.extension.import.updateKeys, ['crm_ct_email']);
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
  const deletePlan = actions.get('delete_follow_plan');
  const deleteRecord = actions.get('delete_follow_record');
  assert.equal(deletePlan.action.command, 'crm:delete_follow_plan');
  assert.equal(deleteRecord.action.command, 'crm:delete_follow_record');
  assert.match(deletePlan.visibleWhen, /=== 'task'/);
  assert.match(deleteRecord.visibleWhen, /!== 'task'/);
  assert.equal(activityCommands.get('crm:delete_follow_plan').type, 'delete');
  assert.equal(activityCommands.get('crm:delete_follow_record').type, 'delete');
  assert.equal(deletePlan.permissionCode, 'crm.activity.manage');
  assert.equal(deleteRecord.permissionCode, 'crm.activity.manage');
  assert.equal(findBlock(activityDetail, 'activity_history').subTable.parentField, 'crm_act_parent_id');
  assert.match(deletePlan.confirm['zh-CN'], /无法恢复/);
  assert.match(deleteRecord.confirm['zh-CN'], /无法恢复/);
  for (const code of ['start_task', 'complete_task', 'cancel_task']) {
    assert.match(actions.get(code).visibleWhen, /crm_act_type/);
    assert.match(actions.get(code).visibleWhen, /crm_act_status/);
  }
  assert.equal(activityCommands.get('crm:update_activity').inputFields.includes('crm_act_type'), false);
  for (const code of ['complete_task', 'cancel_task']) {
    assert.deepEqual(activityCommands.get(`crm:${code}`).preconditions[0], {
      field: 'crm_act_type',
      operator: 'EQ',
      value: 'task',
      'message:zh-CN': code === 'complete_task' ? '只有跟进计划可以完成' : '只有跟进计划可以取消',
      'message:en': code === 'complete_task'
        ? 'Only follow-up plans can be completed'
        : 'Only follow-up plans can be cancelled',
    });
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
