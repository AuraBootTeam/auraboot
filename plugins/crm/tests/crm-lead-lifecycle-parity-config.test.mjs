import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function json(path) {
  return JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
}

const [commands, poolCommands, assignmentCommands, scoreCommands, listPage, detailPage, savedViews] = await Promise.all([
  json('../config/commands/crm_lead_common.json'),
  json('../config/commands/crm_lead_pool.json'),
  json('../config/commands/crm_assignment_rule.json'),
  json('../config/commands/crm_lead_score_rule.json'),
  json('../config/pages/crm_lead_common_list.json'),
  json('../config/pages/crm_lead_common_detail.json'),
  json('../config/saved-views.json'),
]);

const commandByCode = new Map(
  [...commands, ...poolCommands, ...assignmentCommands, ...scoreCommands]
    .map((command) => [command.code, command]),
);
const table = listPage.blocks.find((block) => block.id === 'crm_lead_table');
const bulkByCode = new Map(table.table.bulkActions.map((action) => [action.code, action]));
const rowByCode = new Map(
  table.columns.find((column) => column.isActionColumn).buttons.map((action) => [action.code, action]),
);

test('customer-only conversion is distinct from full opportunity conversion', () => {
  const fullCommand = commandByCode.get('crm:convert_lead');
  assert.equal(fullCommand?.executionConfig?.executionMode, 'confirm_dialog');
  const fullDetailAction = detailPage.blocks
    .flatMap((block) => block.buttons ?? [])
    .find((button) => button.action?.command === 'crm:convert_lead');
  assert.equal(fullDetailAction?.visibleWhen, "record.crm_lead_status == 'qualified'");

  const command = commandByCode.get('crm:convert_lead_to_customer');
  assert.equal(command?.handler, 'crm:convert_lead_to_customer');
  assert.equal(command?.executionConfig?.executionMode, 'confirm_dialog');
  assert.deepEqual(command?.permissions, ['crm.lead.manage']);
  assert.deepEqual(command?.preconditions?.[0]?.value, ['qualified']);
  assert.match(command?.extension?.['confirmMessage:zh-CN'] ?? '', /不会创建商机或客户需求/);

  const action = rowByCode.get('convert_to_customer');
  assert.equal(action?.permissionCode, 'crm.lead.manage');
  assert.equal(action?.visibleWhen, "row.crm_lead_status == 'qualified'");
  assert.equal(action?.action?.command, 'crm:convert_lead_to_customer');
});

test('terminal leads do not retain assignment, pool, or scoring capabilities', () => {
  const activeStatuses = ['new', 'contacted', 'qualified'];
  for (const code of [
    'crm:update_lead',
    'crm:auto_assign_lead',
    'crm:move_lead_to_pool',
    'crm:rescore_lead',
  ]) {
    const command = commandByCode.get(code);
    const statusGuard = command?.preconditions?.find(
      (condition) => condition.field === 'crm_lead_status',
    );
    assert.deepEqual(statusGuard, {
      field: 'crm_lead_status',
      operator: 'IN',
      value: activeStatuses,
    });
  }
  const poolGuard = commandByCode.get('crm:move_lead_to_pool')?.preconditions?.find(
    (condition) => condition.field === 'crm_lead_pool_state',
  );
  assert.deepEqual(poolGuard, {
    field: 'crm_lead_pool_state',
    operator: 'EQ',
    value: 'owned',
  });
});

test('lead list exposes guarded transfer, update, delete, pool, import, and export capabilities', () => {
  assert.equal(table.columns.find((column) => column.field === 'crm_lead_company')?.ellipsis, true);
  assert.equal(
    table.columns.find((column) => column.field === 'crm_lead_contact_name')?.ellipsis,
    true,
  );
  assert.deepEqual(table.table.bulkCapabilities, {
    edit: { permissionCode: 'crm.lead.manage' },
    delete: { permissionCode: 'crm.lead.manage' },
    export: { permissionCode: 'crm.lead.read' },
  });
  assert.equal(listPage.extension?.import?.enabled, true);
  assert.deepEqual(listPage.extension?.import?.modes, ['insert', 'update']);

  assert.equal(
    bulkByCode.get('bulk_transfer_owner')?.action?.input?.component,
    'MemberPicker',
  );
  assert.equal(
    bulkByCode.get('bulk_update_source')?.action?.input?.dictCode,
    'crm_lead_source',
  );
  assert.deepEqual(bulkByCode.get('bulk_delete_leads')?.action, {
    type: 'bulk_record_command',
    command: 'crm:delete_lead',
    operationType: 'DELETE',
  });
  assert.equal(
    bulkByCode.get('bulk_move_to_pool')?.action?.command,
    'crm:move_lead_to_pool',
  );
  assert.deepEqual(bulkByCode.get('bulk_move_to_pool')?.action?.input?.refTarget, {
    targetModel: 'crm_lead_pool_common',
    valueField: 'pid',
    displayField: 'crm_lp_name',
  });
  assert.deepEqual(rowByCode.get('move_to_pool')?.action?.inputFields?.[0]?.dataSource, {
    type: 'api',
    endpoint: '/api/dynamic/crm_lead_pool_common/list',
    params: { pageNum: 1, pageSize: 200 },
    valueField: 'pid',
    labelField: 'crm_lp_name',
  });
  assert.deepEqual(rowByCode.get('delete')?.action, {
    type: 'command',
    command: 'crm:delete_lead',
    operationType: 'DELETE',
  });
});

test('lead list configuration provides localized table and chart views', () => {
  const views = savedViews
    .filter((view) => view.modelCode === 'crm_lead_common')
    .sort((left, right) => left.sortOrder - right.sortOrder);

  assert.deepEqual(
    views.map((view) => [view.viewKey, view.viewType]),
    [
      ['crm_lead_all_table', 'table'],
      ['crm_lead_my_table', 'table'],
      ['crm_lead_pipeline_board', 'kanban'],
    ],
  );
  assert.equal(views[0].isDefault, true);
  assert.equal(views[1].viewConfig.filters[0].expression, '#currentUser');
  assert.equal(views[2].viewConfig.groupByField, 'crm_lead_status');
  for (const view of views) {
    assert.match(view.name, /^\$i18n:/);
    assert.match(view.description, /^\$i18n:/);
  }
});
