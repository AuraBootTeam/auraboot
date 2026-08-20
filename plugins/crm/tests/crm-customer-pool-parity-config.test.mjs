import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const json = async (path) => JSON.parse(await readFile(new URL(path, root), 'utf8'));

test('PAR-06 customer pool exposes the complete Cordys policy denominator', async () => {
  const plugin = await json('plugin.json');
  const models = await json('config/models.json');
  const commands = await json('config/commands/crm_customer_pool.json');
  const recycleRuleCommands = await json('config/commands/crm_customer_pool_recycle_rule.json');
  const permissions = await json('config/permissions.json');
  const roles = await json('config/roles.json');
  const namedQueries = await json('config/named-queries.json');
  const pages = await Promise.all([
    'crm_customer_pool_list.json',
    'crm_customer_pool_form.json',
    'crm_customer_pool_item_list.json',
    'crm_customer_pool_batch_list.json',
    'crm_customer_capacity_list.json',
    'crm_customer_owner_history_list.json',
  ].map((file) => json(`config/pages/${file}`)));
  const recycleRulePages = await Promise.all([
    'crm_customer_pool_recycle_rule_list.json',
    'crm_customer_pool_recycle_rule_form.json',
    'crm_customer_pool_recycle_rule_detail.json',
  ].map((file) => json(`config/pages/${file}`)));
  const ownershipHistoryDetail = await json(
    'config/pages/crm_customer_owner_history_detail.json',
  );
  const poolItemDetail = await json('config/pages/crm_customer_pool_item_detail.json');
  const poolFields = await json('config/fields/crm_customer_pool_common.json');
  const recycleRuleFields = await json('config/fields/crm_customer_pool_recycle_rule_common.json');
  const itemFields = await json('config/fields/crm_customer_pool_item_common.json');
  const historyFields = await json('config/fields/crm_customer_owner_history_common.json');
  const dictionaries = await json('config/dicts.json');
  const handler = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/CustomerPoolCommandHandler.java', root),
    'utf8',
  );
  const importService = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/CustomerPoolImportService.java', root),
    'utf8',
  );
  const scheduler = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/background/CustomerPoolRecycleScheduler.java', root),
    'utf8',
  );
  const shareSync = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/CustomerPoolShareSyncHandler.java', root),
    'utf8',
  );

  assert.ok(
    plugin.dependencies.includes('com.auraboot.org-management'),
    'CRM department-scoped permissions require the organization model at runtime',
  );

  const modelCodes = new Set(models.map((model) => model.code));
  for (const code of [
    'crm_customer_pool_common',
    'crm_customer_pool_recycle_rule_common',
    'crm_customer_capacity_common',
    'crm_customer_pool_item_common',
    'crm_customer_pool_quota_common',
    'crm_customer_owner_history_common',
  ]) assert.ok(modelCodes.has(code), `missing model ${code}`);
  const ownershipHistoryModel = models.find(
    (model) => model.code === 'crm_customer_owner_history_common',
  );
  assert.equal(ownershipHistoryModel.commandOnlyCreate, true);
  assert.equal(
    ownershipHistoryModel.immutable,
    false,
    'Cordys permanent customer deletion must be able to cascade-purge ownership history',
  );
  assert.match(ownershipHistoryModel.lifecycle_description, /authorized permanent purge/);

  const poolFieldCodes = new Set(poolFields.map((field) => field.code));
  for (const code of [
    'crm_cp_member_user_ids',
    'crm_cp_admin_user_ids',
    'crm_cp_daily_pick_limit',
    'crm_cp_new_cooldown_days',
    'crm_cp_previous_owner_cooldown_days',
    'crm_cp_auto_recycle',
    'crm_cp_recycle_match_mode',
    'crm_cp_recycle_after_days',
    'crm_cp_recycle_basis',
  ]) assert.ok(poolFieldCodes.has(code), `missing pool policy ${code}`);
  for (const code of [
    'crm_cprr_pool_id',
    'crm_cprr_time_source',
    'crm_cprr_operator',
    'crm_cprr_days',
    'crm_cprr_start_at',
    'crm_cprr_end_at',
    'crm_cprr_status',
    'crm_cprr_sort_order',
  ]) assert.ok(recycleRuleFields.some((field) => field.code === code), `missing recycle condition ${code}`);
  const recyclePoolReference = recycleRuleFields.find((field) => field.code === 'crm_cprr_pool_id');
  assert.equal(recyclePoolReference.refTarget?.valueField, 'pid');
  assert.equal(recyclePoolReference.refTarget?.displayField, 'crm_cp_name');
  assert.deepEqual(
    dictionaries.find((dictionary) => dictionary.code === 'crm_customer_recycle_match_mode')
      ?.items.map((item) => item.value),
    ['all', 'any'],
  );
  assert.deepEqual(
    dictionaries.find((dictionary) => dictionary.code === 'crm_customer_recycle_time_operator')
      ?.items.map((item) => item.value),
    ['older_than_days', 'newer_than_days', 'fixed_between', 'fixed_before', 'fixed_after'],
  );
  for (const code of ['crm_cp_member_user_ids', 'crm_cp_admin_user_ids']) {
    const field = poolFields.find((candidate) => candidate.code === code);
    assert.equal(field.extension?.renderComponent, 'memberpicker');
    assert.equal(field.extension?.multiple, true);
  }

  assert.equal(
    itemFields.find((field) => field.code === 'crm_cpi_account_key')?.constraints?.unique,
    true,
    'one customer must have one concurrency-control pool projection',
  );
  assert.ok(itemFields.some((field) => field.code === 'crm_cpi_recycle_token'));
  assert.equal(
    historyFields.find((field) => field.code === 'crm_coh_operation_key')?.constraints?.unique,
    true,
    'one recycle operation must create at most one ownership-history record',
  );
  assert.deepEqual(
    ownershipHistoryDetail.extension,
    { showEdit: false, showShare: false },
    'immutable ownership evidence must expose neither an edit route nor manual share widening',
  );

  const commandCodes = new Set(commands.map((command) => command.code));
  for (const code of [
    'crm:move_customer_to_pool',
    'crm:claim_pool_customer',
    'crm:assign_pool_customer',
    'crm:update_pool_customer',
    'crm:delete_pool_customer',
    'crm:toggle_customer_pool',
    'crm:delete_customer_pool',
    'crm:run_customer_pool_recycle',
    'crm:download_customer_pool_import_template',
    'crm:precheck_customer_pool_import',
    'crm:import_customer_pool_customers',
  ]) assert.ok(commandCodes.has(code), `missing command ${code}`);

  for (const code of ['crm:create_customer_pool', 'crm:update_customer_pool']) {
    assert.ok(
      commands.find((command) => command.code === code)?.inputFields?.includes('crm_cp_recycle_match_mode'),
      `${code} must persist the selected AND/OR recycle relation`,
    );
  }

  assert.deepEqual(
    recycleRuleCommands.map((command) => command.code),
    [
      'crm:create_customer_pool_recycle_rule',
      'crm:update_customer_pool_recycle_rule',
      'crm:delete_customer_pool_recycle_rule',
      'crm:list_customer_pool_recycle_rules',
      'crm:detail_customer_pool_recycle_rule',
    ],
  );
  for (const code of ['crm:create_customer_pool_recycle_rule', 'crm:update_customer_pool_recycle_rule']) {
    const command = recycleRuleCommands.find((candidate) => candidate.code === code);
    assert.ok(command.inputFields.includes('crm_cprr_pool_id'));
    assert.ok(command.inputFields.includes('crm_cprr_operator'));
    assert.ok(command.inputFields.includes('crm_cprr_days'));
  }

  for (const code of [
    'crm:move_customer_to_pool',
    'crm:claim_pool_customer',
    'crm:assign_pool_customer',
    'crm:update_pool_customer',
    'crm:delete_pool_customer',
    'crm:toggle_customer_pool',
    'crm:delete_customer_pool',
    'crm:run_customer_pool_recycle',
    'crm:download_customer_pool_import_template',
    'crm:precheck_customer_pool_import',
    'crm:import_customer_pool_customers',
  ]) {
    const command = commands.find((candidate) => candidate.code === code);
    assert.equal(
      command.handlerParams?.dslPersistence,
      false,
      `${code} must bypass generic FIELD_MAP because its PF4J handler owns the transaction`,
    );
  }

  assert.deepEqual(
    pages.map((page) => page.pageKey),
    [
      'crm_customer_pool_list',
      'crm_customer_pool_form',
      'crm_customer_pool_item_list',
      'crm_customer_pool_batch_list',
      'crm_customer_capacity_list',
      'crm_customer_owner_history_list',
    ],
  );
  assert.deepEqual(
    recycleRulePages.map((page) => page.pageKey),
    [
      'crm_customer_pool_recycle_rule_list',
      'crm_customer_pool_recycle_rule_form',
      'crm_customer_pool_recycle_rule_detail',
    ],
  );
  assert.match(
    recycleRulePages[0].blocks.find((block) => block.id === 'crm_cprr_guidance').content['zh-CN'],
    /全部满足（AND）\/任一满足（OR）/,
  );
  const recycleConditionFields = recycleRulePages[1].blocks
    .find((block) => block.id === 'time_condition').fields;
  const poolRecycleFields = pages[1].blocks
    .find((block) => block.id === 'recycle_policy').fields;
  assert.equal(
    poolRecycleFields.find((field) => field.field === 'crm_cp_recycle_match_mode').defaultValue,
    'all',
    'the create form must submit a concrete AND/OR value even when the first enum option is chosen',
  );
  const recycleIdentityFields = recycleRulePages[1].blocks
    .find((block) => block.id === 'identity').fields;
  assert.equal(
    recycleIdentityFields.find((field) => field.field === 'crm_cprr_status').defaultValue,
    'active',
  );
  assert.equal(
    recycleIdentityFields.find((field) => field.field === 'crm_cprr_sort_order').defaultValue,
    100,
  );
  assert.match(
    recycleConditionFields.find((field) => field.field === 'crm_cprr_days').visibleWhen,
    /older_than_days/,
  );
  assert.match(
    recycleConditionFields.find((field) => field.field === 'crm_cprr_start_at').visibleWhen,
    /fixed_between/,
  );
  assert.match(
    recycleConditionFields.find((field) => field.field === 'crm_cprr_end_at').visibleWhen,
    /fixed_before/,
  );
  const operations = pages[2];
  assert.equal(operations.kind, 'detail');
  assert.equal(operations.dataSources.poolStats.queryCode, 'crm_customer_pool_ops_stats');
  assert.equal(operations.dataSources.poolQueue.queryCode, 'crm_customer_pool_ops_queue');
  assert.deepEqual(
    operations.blocks.find((block) => block.id === 'crm_customer_pool_metrics').metrics.map((metric) => metric.key),
    ['available', 'ready', 'cooldown', 'owned', 'processing'],
  );
  assert.equal(operations.blocks.find((block) => block.id === 'crm_customer_pool_metrics').density, 'compact');
  assert.equal(operations.blocks.find((block) => block.id === 'crm_customer_pool_search').density, 'compact');
  const poolQueue = operations.blocks.find((block) => block.id === 'crm_customer_pool_queue');
  assert.equal(poolQueue.maxHeight, 360);
  assert.equal(poolQueue.density, 'compact');
  assert.equal(poolQueue.selection.defaultFirst, true);
  const operationalStateColumn = poolQueue.columns.find((column) => column.field === 'operational_state');
  assert.equal(operationalStateColumn?.dictCode, 'crm_customer_pool_operational_state');
  assert.deepEqual(
    dictionaries.find((dictionary) => dictionary.code === 'crm_customer_pool_operational_state')
      ?.items.map((item) => item.value),
    ['ready', 'cooldown', 'claimed', 'assigned', 'recycling', 'recycling_retry'],
  );
  const poolActions = operations.blocks.find((block) => block.id === 'crm_customer_pool_actions').actions;
  assert.deepEqual(poolActions.map((action) => action.code), ['claim', 'assign']);
  const claimAction = poolActions.find((action) => action.code === 'claim');
  assert.equal(claimAction.permissionCode, 'crm.customer_pool.pick');
  assert.match(claimAction.onClick.args.targetRecordPid, /selectedPoolItem\.pid/);
  assert.deepEqual(claimAction.onClick.args.reload, ['poolStats', 'poolQueue']);
  const assignAction = poolActions.find((action) => action.code === 'assign');
  assert.equal(assignAction.permissionCode, 'crm.customer_pool.assign');
  assert.equal(assignAction.onClick.args.inputFields?.[0]?.field, 'crm_cpi_claimed_by');
  assert.equal(assignAction.onClick.args.inputFields?.[0]?.component, 'MemberPicker');
  assert.ok(operations.blocks.some((block) => block.blockType === 'status-banner'));
  assert.ok(operations.blocks.some((block) => block.blockType === 'evidence-panel'));
  const batchPage = pages[3];
  const batchQueue = batchPage.blocks.find((block) => block.id === 'pool_queue');
  assert.deepEqual(
    batchQueue.table.bulkActions.map((action) => action.code),
    [
      'batch_claim',
      'batch_assign',
      'batch_update_name',
      'batch_update_industry',
      'batch_update_rating',
      'batch_delete',
    ],
  );
  const batchAssign = batchQueue.table.bulkActions.find((action) => action.code === 'batch_assign');
  assert.equal(batchAssign.action.input.component, 'MemberPicker');
  const batchIndustry = batchQueue.table.bulkActions.find(
    (action) => action.code === 'batch_update_industry',
  );
  assert.equal(batchIndustry.action.command, 'crm:update_pool_customer');
  assert.equal(batchIndustry.action.input.dictCode, 'crm_account_industry');
  const batchDelete = batchQueue.table.bulkActions.find((action) => action.code === 'batch_delete');
  assert.equal(batchDelete.variant, 'danger');
  assert.deepEqual(batchDelete.action, {
    type: 'bulk_record_command',
    command: 'crm:delete_pool_customer',
    operationType: 'DELETE',
  });
  const rowActions = batchQueue.columns.find((column) => column.isActionColumn).buttons;
  assert.equal(
    rowActions.find((action) => action.code === 'quick_update').action.command,
    'crm:update_pool_customer',
  );
  assert.equal(
    rowActions.find((action) => action.code === 'delete').action.command,
    'crm:delete_pool_customer',
  );
  const poolSettingsActions = pages[0].blocks
    .find((block) => block.id === 'crm_cp_table')
    .columns.find((column) => column.isActionColumn).buttons;
  const quickPolicyUpdate = poolSettingsActions.find((action) => action.code === 'quick_update');
  assert.equal(quickPolicyUpdate.action.command, 'crm:update_customer_pool');
  const quickPolicyLimit = quickPolicyUpdate.action.inputFields.find(
    (field) => field.field === 'crm_cp_daily_pick_limit',
  );
  assert.equal(quickPolicyLimit.type, 'integer');
  assert.equal(quickPolicyLimit.defaultValue, '${row.crm_cp_daily_pick_limit}');
  const downloadTemplate = poolSettingsActions.find(
    (action) => action.code === 'download_import_template',
  );
  assert.equal(downloadTemplate.permissionCode, 'crm.customer_pool.import');
  assert.equal(downloadTemplate.action.command, 'crm:download_customer_pool_import_template');
  const precheckImport = poolSettingsActions.find((action) => action.code === 'precheck_import');
  assert.deepEqual(precheckImport.promptUpload, {
    key: 'importFileId',
    accept: '.xlsx',
    feedbackMode: 'panel',
  });
  assert.equal(precheckImport.action.command, 'crm:precheck_customer_pool_import');
  assert.equal(precheckImport.action.inputFields[0].field, 'importType');
  const formalImport = poolSettingsActions.find((action) => action.code === 'import_customers');
  assert.equal(formalImport.action.command, 'crm:import_customer_pool_customers');
  assert.equal(
    formalImport.action.inputFields.find((field) => field.field === 'skipErrors').defaultValue,
    false,
  );
  const quickCustomerRating = rowActions
    .find((action) => action.code === 'quick_update').action.inputFields
    .find((field) => field.field === 'crm_cpi_rating');
  assert.equal(quickCustomerRating.type, 'enum');
  assert.equal(quickCustomerRating.dictCode, 'crm_account_rating');
  assert.equal(quickCustomerRating.defaultValue, '${row.crm_cpi_rating}');
  assert.ok(
    operations.blocks
      .find((block) => block.id === 'crm_customer_pool_header_actions')
      .actions.some((action) => action.code === 'batch_operations'),
  );

  const statsQuery = namedQueries.find((query) => query.code === 'crm_customer_pool_ops_stats');
  const queueQuery = namedQueries.find((query) => query.code === 'crm_customer_pool_ops_queue');
  assert.equal(statsQuery.resourceCode, 'crm.customer_pool');
  assert.match(statsQuery.fromSql, /tenant_id = #\{params\.tenantId\}/);
  assert.match(statsQuery.fromSql, /params\.currentUserId/);
  assert.match(statsQuery.fromSql, /crm_cp_member_user_ids/);
  assert.match(statsQuery.fromSql, /crm_cp_admin_user_ids/);
  assert.match(statsQuery.fromSql, /crm_cpi_claim_release_at <= now\(\)/);
  assert.deepEqual(
    statsQuery.outputFields.map((field) => field.code),
    ['available_count', 'ready_count', 'cooldown_count', 'owned_count', 'processing_count'],
  );
  assert.equal(queueQuery.resourceCode, 'crm.customer_pool');
  assert.match(queueQuery.fromSql, /i\.tenant_id = #\{params\.tenantId\}/);
  assert.match(queueQuery.fromSql, /params\.currentUserId/);
  assert.match(queueQuery.fromSql, /CAST\(#\{params\.viewFilter\} AS text\) = 'ready'/);
  assert.match(queueQuery.fromSql, /CAST\(#\{params\.viewFilter\} AS text\) = 'processing'/);
  assert.ok(queueQuery.outputFields.some((field) => field.code === 'operational_state'));
  for (const code of ['crm_pool_customer_timeline', 'crm_pool_customer_owner_history']) {
    const query = namedQueries.find((candidate) => candidate.code === code);
    assert.equal(query.resourceCode, 'crm.customer_pool');
    assert.match(query.fromSql, /i\.pid = #\{params\.poolItemId\}/);
    assert.match(query.fromSql, /params\.currentUserId/);
    assert.match(query.fromSql, /crm_cp_member_user_ids/);
    assert.match(query.fromSql, /crm_cp_admin_user_ids/);
  }
  const timelineQuery = namedQueries.find(
    (candidate) => candidate.code === 'crm_pool_customer_timeline',
  );
  assert.match(timelineQuery.fromSql, /a\.crm_act_related_model = 'crm_account_common'/);
  assert.match(timelineQuery.fromSql, /r\.crm_ar_activity_id = a\.pid/);

  const mobileTabs = poolItemDetail.blocks.find(
    (block) => block.id === 'pooled_customer_mobile_tabs',
  );
  assert.equal(mobileTabs.blockType, 'tabs');
  assert.deepEqual(
    mobileTabs.tabs.map((tab) => tab.label['zh-CN']),
    ['客户信息', '跟进记录', '归属记录'],
  );
  assert.equal(
    mobileTabs.tabs[1].blocks[0].subTable.dataSource.params.datasourceId,
    'nq:crm_pool_customer_timeline',
  );
  assert.equal(
    mobileTabs.tabs[2].blocks[0].subTable.dataSource.params.datasourceId,
    'nq:crm_pool_customer_owner_history',
  );
  assert.equal(
    mobileTabs.tabs[2].blocks[0].subTable.columns.find(
      (column) => column.field === 'crm_coh_event',
    ).dictCode,
    'crm_customer_owner_event',
  );
  assert.ok(
    dictionaries
      .find((dictionary) => dictionary.code === 'crm_customer_owner_event')
      .items.some((item) => item.value === 'imported_to_pool'),
  );

  const permissionCodes = new Set(permissions.map((permission) => permission.code));
  for (const code of [
    'crm.customer_pool.read',
    'crm.customer_pool.pick',
    'crm.customer_pool.move',
    'crm.customer_pool.assign',
    'crm.customer_pool.manage',
    'crm.customer_pool.recycle',
    'crm.customer_pool.import',
  ]) assert.ok(permissionCodes.has(code), `missing permission ${code}`);
  const role = (code) => roles.find((candidate) => candidate.code === code).permissions;
  assert.ok(role('crm_sales').includes('crm.customer_pool.pick'));
  assert.ok(role('crm_sales').includes('crm.customer_pool.import'));
  assert.ok(!role('crm_sales').includes('crm.customer_pool.assign'));
  assert.ok(role('crm_sales_manager').includes('crm.customer_pool.assign'));
  assert.ok(role('crm_sales_manager').includes('crm.customer_pool.recycle'));
  assert.ok(role('crm_sales_manager').includes('crm.customer_pool.import'));
  assert.ok(role('crm_admin').includes('crm.customer_pool.import'));

  for (const proof of [
    'MAX_FILE_BYTES',
    'Uploaded file owner does not match',
    'Workbook exceeds 5000 data rows',
    'importType must be ADD or UPDATE',
    'skipErrors',
    'Imported into customer pool',
    'Customer belongs to a different customer pool',
  ]) assert.match(importService, new RegExp(proof));

  for (const proof of [
    'incrementWithinCap',
    'compareAndSet',
    'recycling_retry',
    'tryCreate',
    'Previous owner cooldown',
    'Customer capacity reached',
    'available customers and cannot be deleted',
    'related contacts or opportunities',
    'crm:update_pool_customer',
    'crm:delete_pool_customer',
    'crm_customer_owner_history',
  ]) assert.match(handler, new RegExp(proof));
  assert.match(scheduler, /@Scheduled/);
  assert.match(scheduler, /listActiveTenantIds/);
  assert.match(handler, /currentUserPid/);
  assert.match(handler, /replaceReadSharesForUsers/);
  assert.match(shareSync, /chainsAfterPrimary\(\).*true/);
  assert.match(shareSync, /crm_customer_owner_history/);

  const poolItem = models.find((model) => model.code === 'crm_customer_pool_item_common');
  assert.equal(poolItem.extension?.dataScope?.ownerField, 'crm_cpi_claimed_by');
  const capacity = models.find((model) => model.code === 'crm_customer_capacity_common');
  assert.equal(capacity.extension?.dataScope?.ownerField, 'crm_ccap_user_id');
  const quota = models.find((model) => model.code === 'crm_customer_pool_quota_common');
  assert.equal(quota.extension?.dataScope?.ownerField, 'crm_cpq_user_id');
});

test('PAR-06 workbench search fields request import-managed trigram indexes', async () => {
  const bindings = await json('config/bindings/crm_customer_pool_item_common.json');
  for (const fieldCode of ['crm_cpi_account_code', 'crm_cpi_account_name', 'crm_cpi_phone']) {
    const binding = bindings.find((candidate) => candidate.fieldCode === fieldCode);
    assert.ok(binding, `${fieldCode} binding must exist`);
    assert.equal(binding.displayConfig?.searchable, true, `${fieldCode} must drive schema search indexing`);
  }
});

test('PAR-06 config mutation proves missing claim permission turns the contract red', async () => {
  const commands = await json('config/commands/crm_customer_pool.json');
  const mutant = structuredClone(commands);
  mutant.find((command) => command.code === 'crm:claim_pool_customer').permissions = [];
  assert.throws(() => {
    assert.deepEqual(
      mutant.find((command) => command.code === 'crm:claim_pool_customer').permissions,
      ['crm.customer_pool.pick'],
    );
  });
});
