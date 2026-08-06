import assert from 'node:assert/strict';
import { readdir, readFile } from 'node:fs/promises';
import test from 'node:test';

const configRoot = new URL('../config/', import.meta.url);
const pagesRoot = new URL('../config/pages/', import.meta.url);
const fieldsRoot = new URL('../config/fields/', import.meta.url);
const bindingsRoot = new URL('../config/bindings/', import.meta.url);
const commandsRoot = new URL('../config/commands/', import.meta.url);

async function readJson(url) {
  return JSON.parse(await readFile(url, 'utf8'));
}

async function readJsonFiles(root) {
  const files = (await readdir(root)).filter((file) => file.endsWith('.json')).sort();
  const groups = await Promise.all(files.map((file) => readJson(new URL(file, root))));
  return groups.flatMap((group) => (Array.isArray(group) ? group : [group]));
}

const [models, fields, bindings, commands, pages, menus, dicts, namedQueries] = await Promise.all([
  readJson(new URL('models.json', configRoot)),
  readJsonFiles(fieldsRoot),
  readJsonFiles(bindingsRoot),
  readJsonFiles(commandsRoot),
  readJsonFiles(pagesRoot),
  readJson(new URL('menus.json', configRoot)),
  readJson(new URL('dicts.json', configRoot)),
  readJson(new URL('named-queries.json', configRoot)),
]);

const modelByCode = new Map(models.map((model) => [model.code, model]));
const fieldByCode = new Map(fields.map((field) => [field.code, field]));
const commandByCode = new Map(commands.map((command) => [command.code, command]));
const pageByKey = new Map(pages.map((page) => [page.pageKey, page]));
const menuByCode = new Map(menus.map((menu) => [menu.code, menu]));
const dictByCode = new Map(dicts.map((dict) => [dict.code, dict]));
const namedQueryByCode = new Map(namedQueries.map((query) => [query.code, query]));
const boundFieldsByModel = new Map();
for (const binding of bindings) {
  if (!boundFieldsByModel.has(binding.modelCode)) {
    boundFieldsByModel.set(binding.modelCode, new Set());
  }
  boundFieldsByModel.get(binding.modelCode).add(binding.fieldCode);
}

function page(pageKey) {
  const found = pageByKey.get(pageKey);
  assert.ok(found, `page ${pageKey} should exist`);
  return found;
}

function command(code) {
  const found = commandByCode.get(code);
  assert.ok(found, `command ${code} should exist`);
  return found;
}

function findBlock(blocks, blockId) {
  for (const item of blocks ?? []) {
    if (item.id === blockId) return item;
    const nested = [
      ...(item.blocks ?? []),
      ...(item.children ?? []),
      ...(item.tabs ?? []).flatMap((tab) => tab.blocks ?? []),
    ];
    const found = findBlock(nested, blockId);
    if (found) return found;
  }
  return null;
}

function block(pageKey, blockId) {
  const found = findBlock(page(pageKey).blocks, blockId);
  assert.ok(found, `${pageKey} should expose block ${blockId}`);
  return found;
}

function tableColumns(pageKey, blockId) {
  const found = block(pageKey, blockId);
  const columns = found.columns ?? found.table?.columns;
  assert.ok(Array.isArray(columns), `${pageKey}.${blockId} should define table columns`);
  return new Set(columns.map((column) => column.field));
}

function detailFields(pageKey, blockId) {
  const found = block(pageKey, blockId);
  assert.ok(Array.isArray(found.fields), `${pageKey}.${blockId} should define detail fields`);
  return new Set(found.fields.map((field) => field.field));
}

function subTable(pageKey, blockId) {
  const found = block(pageKey, blockId);
  assert.equal(found.blockType, 'sub-table', `${pageKey}.${blockId} should be a sub-table`);
  assert.ok(found.subTable, `${pageKey}.${blockId} should define subTable`);
  return found.subTable;
}

test('CRM exposes a cross-domain approval case object and dictionary contract', () => {
  const model = modelByCode.get('crm_approval_case_common');
  assert.ok(model, 'crm_approval_case_common model should exist');
  assert.equal(model.extension?.titleField, 'crm_apc_code');
  assert.equal(model.extension?.subtitleField, 'crm_apc_subject');

  const bound = boundFieldsByModel.get('crm_approval_case_common');
  assert.ok(bound, 'crm_approval_case_common should have field bindings');
  for (const field of [
    'crm_apc_code',
    'crm_apc_subject',
    'crm_apc_domain',
    'crm_apc_case_type',
    'crm_apc_reason',
    'crm_apc_status',
    'crm_apc_priority',
    'crm_apc_source_model',
    'crm_apc_source_id',
    'crm_apc_source_code',
    'crm_apc_account_id',
    'crm_apc_customer_request_id',
    'crm_apc_amount',
    'crm_apc_margin_pct',
    'crm_apc_discount_pct',
    'crm_apc_reason',
    'crm_apc_policy_snapshot',
    'crm_apc_submitted_at',
    'crm_apc_decided_at',
    'crm_apc_decision',
    'crm_apc_decision_note',
  ]) {
    assert.ok(fieldByCode.has(field), `${field} field should exist`);
    assert.ok(bound.has(field), `crm_approval_case_common should bind ${field}`);
  }

  assert.equal(fieldByCode.get('crm_apc_account_id')?.referenceModelCode, 'crm_account_common');
  assert.equal(fieldByCode.get('crm_apc_customer_request_id')?.referenceModelCode, 'crm_customer_request_common');
  assert.ok(
    dictByCode.get('crm_approval_case_status')?.items?.some((item) => item.value === 'pending'),
    'approval case status dict should include pending',
  );
  assert.ok(
    dictByCode.get('crm_approval_case_decision')?.items?.some((item) => item.value === 'approved'),
    'approval case decision dict should include approved',
  );
});

test('CRM approval case CRUD commands include every user-editable and synced field', () => {
  const create = command('crm:create_approval_case');
  assert.equal(create.modelCode, 'crm_approval_case_common');
  assert.equal(create.type, 'create');
  assert.equal(create.autoSetFields?.crm_apc_code?.pattern, 'APC-{yyyyMMdd}-{seq}');

  const update = command('crm:update_approval_case');
  assert.equal(update.modelCode, 'crm_approval_case_common');
  assert.equal(update.type, 'update');

  for (const field of [
    'crm_apc_subject',
    'crm_apc_domain',
    'crm_apc_case_type',
    'crm_apc_status',
    'crm_apc_priority',
    'crm_apc_source_model',
    'crm_apc_source_id',
    'crm_apc_source_code',
    'crm_apc_account_id',
    'crm_apc_customer_request_id',
    'crm_apc_amount',
    'crm_apc_margin_pct',
    'crm_apc_discount_pct',
    'crm_apc_reason',
    'crm_apc_policy_snapshot',
    'crm_apc_submitted_at',
    'crm_apc_decided_at',
    'crm_apc_decision',
    'crm_apc_decision_note',
  ]) {
    assert.ok(create.inputFields.includes(field), `create approval case should accept ${field}`);
    assert.ok(update.inputFields.includes(field), `update approval case should accept ${field}`);
  }

  assert.equal(command('crm:delete_approval_case').type, 'delete');
  assert.equal(command('crm:list_approval_cases').type, 'query');
});

test('CRM approval case pages expose a unified queue and audit detail', () => {
  const menu = menuByCode.get('crm_approval_case_menu');
  assert.ok(menu, 'CRM should expose approval case menu');
  assert.equal(menu.path, '/p/crm_approval_case_common');
  assert.equal(menu.permissionCode, 'crm.approval_case.read');

  const list = page('crm_approval_case_common_list');
  assert.equal(list.modelCode, 'crm_approval_case_common');
  assert.equal(list.kind, 'list');
  const columns = tableColumns('crm_approval_case_common_list', 'crm_apc_table');
  for (const field of [
    'crm_apc_code',
    'crm_apc_subject',
    'crm_apc_domain',
    'crm_apc_case_type',
    'crm_apc_status',
    'crm_apc_priority',
    'crm_apc_source_code',
    'crm_apc_amount',
    'crm_apc_margin_pct',
    'crm_apc_discount_pct',
    'crm_apc_submitted_at',
  ]) {
    assert.ok(columns.has(field), `approval case list should show ${field}`);
  }

  const detail = page('crm_approval_case_common_detail');
  assert.equal(detail.modelCode, 'crm_approval_case_common');
  assert.equal(detail.kind, 'detail');
  const facts = detailFields('crm_approval_case_common_detail', 'crm_apc_facts');
  assert.ok(facts.has('crm_apc_reason'), 'approval detail should show reason');
  assert.ok(facts.has('crm_apc_policy_snapshot'), 'approval detail should show policy snapshot');
  assert.ok(facts.has('crm_apc_decision_note'), 'approval detail should show decision note');
});

test('CRM activity exposes direct related-object anchors for workspace sub-tables', () => {
  const bound = boundFieldsByModel.get('crm_activity_common');
  assert.ok(bound, 'crm_activity_common should have field bindings');
  for (const field of ['crm_act_related_model', 'crm_act_related_id']) {
    assert.ok(fieldByCode.has(field), `${field} field should exist`);
    assert.ok(bound.has(field), `crm_activity_common should bind ${field}`);
    assert.ok(command('crm:create_activity').inputFields.includes(field), `create activity should accept ${field}`);
    assert.ok(command('crm:update_activity').inputFields.includes(field), `update activity should accept ${field}`);
  }

  const detail = page('crm_activity_common_detail');
  const relatedSection = detail.blocks?.find((block) => block.id === 'section_related_context');
  assert.ok(relatedSection, 'activity detail should expose related context section');
  const relatedFields = new Set(relatedSection.fields?.map((field) => field.field));
  assert.ok(relatedFields.has('crm_act_related_model'), 'activity detail should show related model');
  assert.ok(relatedFields.has('crm_act_related_id'), 'activity detail should show related id');
});

test('CRM exposes direct workspace activity named queries for task and timeline tabs', () => {
  const tasks = namedQueryByCode.get('crm_direct_tasks_by_related_object');
  assert.ok(tasks, 'direct task named query should exist');
  assert.match(tasks.fromSql, /crm_act_related_model = #\{params\.relatedModel\}/);
  assert.match(tasks.fromSql, /crm_act_related_id = #\{params\.relatedId\}/);
  assert.match(tasks.fromSql, /crm_act_type = 'task'/);
  assert.match(tasks.fromSql, /tenant_id = #\{params\.tenantId\}/);

  const taskFields = new Set(tasks.outputFields?.map((field) => field.code));
  for (const field of [
    'pid',
    'crm_act_type',
    'crm_act_subject',
    'crm_act_status',
    'crm_act_priority',
    'crm_act_due_date',
    'crm_act_assignee',
    'crm_act_related_model',
    'crm_act_related_id',
  ]) {
    assert.ok(taskFields.has(field), `direct task query should expose ${field}`);
  }

  const timeline = namedQueryByCode.get('crm_direct_timeline_by_related_object');
  assert.ok(timeline, 'direct timeline named query should exist');
  assert.match(timeline.fromSql, /crm_act_related_model = #\{params\.relatedModel\}/);
  assert.match(timeline.fromSql, /crm_act_related_id = #\{params\.relatedId\}/);
  assert.match(timeline.fromSql, /tenant_id = #\{params\.tenantId\}/);
  assert.doesNotMatch(timeline.fromSql, /crm_act_type = 'task'/);

  const timelineFields = new Set(timeline.outputFields?.map((field) => field.code));
  for (const field of [
    'pid',
    'crm_act_type',
    'crm_act_subject',
    'crm_act_date',
    'crm_act_owner',
    'crm_act_source',
    'crm_act_status',
    'crm_act_related_model',
    'crm_act_related_id',
  ]) {
    assert.ok(timelineFields.has(field), `direct timeline query should expose ${field}`);
  }
});

test('CRM exposes task comment named query anchored by activity parent id', () => {
  const comments = namedQueryByCode.get('crm_activity_comments_by_related_object');
  assert.ok(comments, 'task comment named query should exist');
  assert.match(comments.fromSql, /JOIN mt_crm_activity_common p ON p\.pid = c\.crm_act_parent_id/);
  assert.match(comments.fromSql, /p\.crm_act_related_model = #\{params\.relatedModel\}/);
  assert.match(comments.fromSql, /p\.crm_act_related_id = #\{params\.relatedId\}/);
  assert.match(comments.fromSql, /p\.crm_act_type = 'task'/);
  assert.match(comments.fromSql, /c\.tenant_id = #\{params\.tenantId\}/);

  const fields = new Set(comments.outputFields?.map((field) => field.code));
  for (const field of [
    'pid',
    'crm_act_type',
    'crm_act_subject',
    'crm_act_content',
    'crm_act_date',
    'crm_act_owner',
    'crm_act_source',
    'crm_act_parent_id',
    'crm_act_files',
    'crm_act_related_model',
    'crm_act_related_id',
    'parent_task_subject',
    'parent_task_status',
  ]) {
    assert.ok(fields.has(field), `task comment query should expose ${field}`);
  }
});

test('CRM manager workbench exposes pipeline risk and approval review queue', () => {
  const menu = menuByCode.get('crm_manager_workbench');
  assert.ok(menu, 'CRM should expose manager workbench menu');
  assert.equal(menu.path, '/p/crm_manager_workbench');
  assert.equal(menu.pageKey, 'crm_manager_workbench_list');
  assert.equal(menu.permissionCode, 'crm.customer_request.read');

  const list = page('crm_manager_workbench_list');
  assert.equal(list.modelCode, 'crm_customer_request_common');
  assert.equal(list.kind, 'list');
  assert.equal(list.extension?.queryCode, 'crm_manager_workbench_queue');
  assert.equal(list.extension?.dataSource?.type, 'namedQuery');
  assert.equal(list.extension?.dataSource?.queryCode, 'crm_manager_workbench_queue');

  const requestBoundFields = boundFieldsByModel.get('crm_customer_request_common');
  assert.ok(requestBoundFields, 'crm_customer_request_common should have field bindings');
  for (const field of [
    'crm_mw_opp_name',
    'crm_mw_opp_stage',
    'crm_mw_opp_expected_amount',
    'crm_mw_opp_expected_close_date',
    'crm_mw_quote_count',
    'crm_mw_quote_amount',
    'crm_mw_latest_quote_valid_until',
    'crm_mw_pending_approval_count',
    'crm_mw_open_task_count',
    'crm_mw_overdue_task_count',
    'crm_mw_risk_reason',
    'crm_mw_age_days',
  ]) {
    assert.ok(fieldByCode.has(field), `manager workbench should define field ${field}`);
    assert.ok(requestBoundFields.has(field), `crm_customer_request_common should bind ${field}`);
  }

  const riskDict = dictByCode.get('crm_manager_workbench_risk_reason');
  assert.ok(riskDict, 'manager workbench risk reason dictionary should exist');
  assert.deepEqual(
    riskDict.items?.map((item) => item.value),
    ['route_failed', 'approval_pending', 'task_overdue', 'request_overdue', 'quote_missing', 'watch'],
  );

  const tabs = block('crm_manager_workbench_list', 'crm_manager_workbench_tabs');
  const tabByKey = new Map(tabs.tabs?.map((tab) => [tab.key, tab]));
  for (const key of ['all', 'high_priority', 'route_failed', 'in_progress', 'quoted']) {
    assert.ok(tabByKey.has(key), `manager workbench should expose ${key} tab`);
  }
  assert.equal(tabByKey.get('high_priority')?.filter?.operator, 'IN');
  assert.deepEqual(tabByKey.get('high_priority')?.filter?.value, ['urgent', 'high']);
  assert.equal(tabByKey.get('route_failed')?.filter?.field, 'crm_cr_route_status');
  assert.equal(tabByKey.get('route_failed')?.filter?.value, 'failed');

  const toolbarButtons = new Map(
    block('crm_manager_workbench_list', 'crm_manager_workbench_toolbar').buttons?.map((button) => [button.code, button]),
  );
  assert.equal(toolbarButtons.get('open_pipeline_board')?.action?.to, '/p/crm_opportunity_common');
  assert.equal(toolbarButtons.get('open_approval_cases')?.action?.to, '/p/crm_approval_case_common');
  assert.equal(toolbarButtons.get('open_sales_workbench')?.action?.to, '/p/crm_sales_workbench');

  const columns = tableColumns('crm_manager_workbench_list', 'crm_manager_workbench_table');
  for (const field of [
    'crm_cr_code',
    'crm_cr_title',
    'crm_cr_account_id',
    'crm_cr_priority',
    'crm_cr_status',
    'crm_cr_route_status',
    'crm_mw_opp_stage',
    'crm_mw_opp_expected_amount',
    'crm_mw_quote_count',
    'crm_mw_quote_amount',
    'crm_mw_pending_approval_count',
    'crm_mw_open_task_count',
    'crm_mw_overdue_task_count',
    'crm_mw_risk_reason',
    'actions',
  ]) {
    assert.ok(columns.has(field), `manager workbench list should show ${field}`);
  }
  const riskColumn = block('crm_manager_workbench_list', 'crm_manager_workbench_table')
    .table?.columns?.find((column) => column.field === 'crm_mw_risk_reason');
  assert.equal(riskColumn?.dictCode, 'crm_manager_workbench_risk_reason');

  const actionColumn = block('crm_manager_workbench_list', 'crm_manager_workbench_table')
    .table?.columns?.find((column) => column.field === 'actions');
  assert.ok(actionColumn, 'manager workbench should define row actions');
  const rowActions = new Map(actionColumn.buttons?.map((button) => [button.code, button]));
  assert.equal(rowActions.get('open_review')?.action?.to, 'crm_manager_workbench_detail');
  assert.equal(rowActions.get('open_request')?.action?.to, 'crm_customer_request_common_detail');

  const detail = page('crm_manager_workbench_detail');
  assert.equal(detail.modelCode, 'crm_customer_request_common');
  assert.equal(detail.kind, 'detail');
  const detailToolbar = new Map(
    block('crm_manager_workbench_detail', 'crm_manager_workbench_toolbar').buttons?.map((button) => [button.code, button]),
  );
  assert.equal(detailToolbar.get('back_to_manager_queue')?.action?.to, '/p/crm_manager_workbench');
  assert.equal(detailToolbar.get('open_pipeline_board')?.action?.to, '/p/crm_opportunity_common');
  assert.equal(detailToolbar.get('open_approval_cases')?.action?.to, '/p/crm_approval_case_common');

  const facts = detailFields('crm_manager_workbench_detail', 'crm_manager_request_facts');
  for (const field of [
    'crm_cr_code',
    'crm_cr_title',
    'crm_cr_account_id',
    'crm_cr_opportunity_id',
    'crm_cr_priority',
    'crm_cr_status',
    'crm_cr_route_status',
    'crm_cr_summary',
    'crm_cr_route_error',
  ]) {
    assert.ok(facts.has(field), `manager review facts should show ${field}`);
  }

  const quotes = subTable('crm_manager_workbench_detail', 'crm_manager_quote_summaries');
  assert.equal(quotes.childModel, 'crm_quote_summary_common');
  assert.equal(quotes.parentField, 'crm_qs_customer_request_id');
  assert.ok(quotes.columns?.some((column) => column.field === 'crm_qs_approval_status'));

  const approvals = subTable('crm_manager_workbench_detail', 'crm_manager_approval_cases');
  assert.equal(approvals.childModel, 'crm_approval_case_common');
  assert.equal(approvals.parentField, 'crm_apc_customer_request_id');
  assert.ok(approvals.columns?.some((column) => column.field === 'crm_apc_subject'));

  const tasks = subTable('crm_manager_workbench_detail', 'crm_manager_direct_tasks');
  assert.equal(tasks.childModel, 'crm_activity_common');
  assert.equal(tasks.parentField, 'crm_act_related_id');
  assert.equal(tasks.dataSource?.params?.datasourceId, 'nq:crm_direct_tasks_by_related_object');
  assert.equal(tasks.dataSource?.params?.relatedModel, 'crm_customer_request_common');
  assert.equal(tasks.dataSource?.params?.relatedId, '${recordId}');
  const taskActions = new Set(tasks.actions?.map((action) => action.action?.command));
  assert.ok(taskActions.has('crm:start_task'), 'manager task table should allow starting tasks');
  assert.ok(taskActions.has('crm:complete_task'), 'manager task table should allow completing tasks');

  const managerQuery = namedQueryByCode.get('crm_manager_workbench_queue');
  assert.ok(managerQuery, 'manager workbench named query should exist');
  assert.match(managerQuery.fromSql, /mt_crm_customer_request_common cr/);
  assert.match(managerQuery.fromSql, /mt_crm_quote_summary_common/);
  assert.match(managerQuery.fromSql, /mt_crm_approval_case_common/);
  assert.match(managerQuery.fromSql, /mt_crm_activity_common/);
  assert.match(managerQuery.fromSql, /crm_mw_risk_reason/);
  assert.match(managerQuery.fromSql, /cr\.crm_cr_route_status = 'failed'/);
  assert.match(managerQuery.fromSql, /crm_apc_status = 'pending'/);
  assert.match(managerQuery.fromSql, /crm_act_ext->>'status' IN \('open', 'in_progress'\)/);

  const queryFields = new Map(managerQuery.outputFields?.map((field) => [field.code, field]));
  for (const field of [
    'pid',
    'crm_cr_code',
    'crm_cr_title',
    'crm_cr_account_id',
    'crm_cr_opportunity_id',
    'crm_cr_priority',
    'crm_cr_status',
    'crm_cr_route_status',
    'crm_cr_expected_date',
    'crm_cr_owner',
    'crm_mw_opp_name',
    'crm_mw_opp_stage',
    'crm_mw_opp_expected_amount',
    'crm_mw_opp_expected_close_date',
    'crm_mw_quote_count',
    'crm_mw_quote_amount',
    'crm_mw_latest_quote_valid_until',
    'crm_mw_pending_approval_count',
    'crm_mw_open_task_count',
    'crm_mw_overdue_task_count',
    'crm_mw_risk_reason',
    'crm_mw_age_days',
  ]) {
    assert.ok(queryFields.has(field), `manager query should expose ${field}`);
  }
  assert.equal(queryFields.get('crm_mw_pending_approval_count')?.dataType, 'number');
  assert.equal(queryFields.get('crm_mw_open_task_count')?.dataType, 'number');
  assert.equal(queryFields.get('crm_mw_quote_amount')?.dataType, 'number');
});
