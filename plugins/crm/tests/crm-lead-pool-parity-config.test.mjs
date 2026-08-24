import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const json = async (path) => JSON.parse(await readFile(new URL(path, root), 'utf8'));

test('PAR-04 lead pool exposes the complete Cordys policy denominator', async () => {
  const models = await json('config/models.json');
  const commands = await json('config/commands/crm_lead_pool.json');
  const menus = await json('config/menus.json');
  const recycleRuleCommands = await json('config/commands/crm_lead_pool_recycle_rule.json');
  const permissions = await json('config/permissions.json');
  const roles = await json('config/roles.json');
  const namedQueries = await json('config/named-queries.json');
  const pages = await Promise.all([
    'crm_lead_pool_list.json',
    'crm_lead_pool_form.json',
    'crm_lead_pool_item_list.json',
    'crm_lead_pool_batch_list.json',
    'crm_lead_capacity_list.json',
    'crm_lead_owner_history_list.json',
  ].map((file) => json(`config/pages/${file}`)));
  const recycleRulePages = await Promise.all([
    'crm_lead_pool_recycle_rule_list.json',
    'crm_lead_pool_recycle_rule_form.json',
    'crm_lead_pool_recycle_rule_detail.json',
  ].map((file) => json(`config/pages/${file}`)));
  const poolFields = await json('config/fields/crm_lead_pool.json');
  const recycleRuleFields = await json('config/fields/crm_lead_pool_recycle_rule.json');
  const itemFields = await json('config/fields/crm_lead_pool_item.json');
  const historyFields = await json('config/fields/crm_lead_owner_history.json');
  const dictionaries = await json('config/dicts.json');
  const handler = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/LeadPoolCommandHandler.java', root),
    'utf8',
  );
  const importService = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/LeadPoolImportService.java', root),
    'utf8',
  );
  const scheduler = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/background/LeadPoolRecycleScheduler.java', root),
    'utf8',
  );
  const shareSync = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/LeadPoolShareSyncHandler.java', root),
    'utf8',
  );

  const modelCodes = new Set(models.map((model) => model.code));
  for (const code of [
    'crm_lead_pool_common',
    'crm_lead_pool_recycle_rule_common',
    'crm_lead_capacity_common',
    'crm_lead_pool_item_common',
    'crm_lead_pool_quota_common',
    'crm_lead_owner_history_common',
  ]) assert.ok(modelCodes.has(code), `missing model ${code}`);

  const providedModels = new Set(
    (await json('plugin.json')).provides
      .filter((entry) => entry.type === 'model')
      .map((entry) => entry.code),
  );
  for (const code of [
    'crm_lead_pool_common',
    'crm_lead_pool_recycle_rule_common',
    'crm_lead_capacity_common',
    'crm_lead_pool_item_common',
    'crm_lead_pool_quota_common',
    'crm_lead_owner_history_common',
  ]) assert.ok(providedModels.has(code), `missing provided model ${code}`);

  const poolFieldCodes = new Set(poolFields.map((field) => field.code));
  for (const code of [
    'crm_lp_member_user_ids',
    'crm_lp_admin_user_ids',
    'crm_lp_daily_pick_limit',
    'crm_lp_new_cooldown_days',
    'crm_lp_previous_owner_cooldown_days',
    'crm_lp_auto_recycle',
    'crm_lp_recycle_match_mode',
    'crm_lp_recycle_after_days',
    'crm_lp_recycle_basis',
  ]) assert.ok(poolFieldCodes.has(code), `missing pool policy ${code}`);
  for (const code of [
    'crm_lprr_pool_id',
    'crm_lprr_time_source',
    'crm_lprr_operator',
    'crm_lprr_days',
    'crm_lprr_start_at',
    'crm_lprr_end_at',
    'crm_lprr_status',
    'crm_lprr_sort_order',
  ]) assert.ok(recycleRuleFields.some((field) => field.code === code), `missing recycle condition ${code}`);
  const recyclePoolReference = recycleRuleFields.find((field) => field.code === 'crm_lprr_pool_id');
  assert.equal(recyclePoolReference.refTarget?.valueField, 'pid');
  assert.equal(recyclePoolReference.refTarget?.displayField, 'crm_lp_name');
  assert.deepEqual(
    dictionaries.find((dictionary) => dictionary.code === 'crm_lead_recycle_match_mode')
      ?.items.map((item) => item.value),
    ['all', 'any'],
  );
  assert.deepEqual(
    dictionaries.find((dictionary) => dictionary.code === 'crm_lead_recycle_time_operator')
      ?.items.map((item) => item.value),
    ['older_than_days', 'newer_than_days', 'fixed_between', 'fixed_before', 'fixed_after'],
  );
  for (const code of ['crm_lp_member_user_ids', 'crm_lp_admin_user_ids']) {
    const field = poolFields.find((candidate) => candidate.code === code);
    assert.equal(field.extension?.renderComponent, 'memberpicker');
    assert.equal(field.extension?.multiple, true);
  }

  assert.equal(
    itemFields.find((field) => field.code === 'crm_lpi_lead_key')?.constraints?.unique,
    true,
    'one lead must have one concurrency-control pool projection',
  );
  assert.ok(itemFields.some((field) => field.code === 'crm_lpi_recycle_token'));
  assert.equal(
    historyFields.find((field) => field.code === 'crm_loh_operation_key')?.constraints?.unique,
    true,
    'one recycle operation must create at most one ownership-history record',
  );

  const commandCodes = new Set(commands.map((command) => command.code));
  for (const code of [
    'crm:move_lead_to_pool',
    'crm:claim_pool_lead',
    'crm:assign_pool_lead',
    'crm:update_pool_lead',
    'crm:delete_pool_lead',
    'crm:toggle_lead_pool',
    'crm:delete_lead_pool',
    'crm:download_lead_pool_import_template',
    'crm:precheck_lead_pool_import',
    'crm:import_lead_pool_leads',
    'crm:run_lead_pool_recycle',
  ]) assert.ok(commandCodes.has(code), `missing command ${code}`);

  assert.equal(
    menus.find((menu) => menu.code === 'crm_lead_owner_history_common')?.path,
    '/p/c/crm_lead_owner_history_list',
    'the ownership-history menu must address its explicit pageKey instead of a derived auto-created stub',
  );

  for (const code of ['crm:create_lead_pool', 'crm:update_lead_pool']) {
    assert.ok(
      commands.find((command) => command.code === code)?.inputFields?.includes('crm_lp_recycle_match_mode'),
      `${code} must persist the selected AND/OR recycle relation`,
    );
  }

  assert.deepEqual(
    recycleRuleCommands.map((command) => command.code),
    [
      'crm:create_lead_pool_recycle_rule',
      'crm:update_lead_pool_recycle_rule',
      'crm:delete_lead_pool_recycle_rule',
      'crm:list_lead_pool_recycle_rules',
      'crm:detail_lead_pool_recycle_rule',
    ],
  );
  for (const code of ['crm:create_lead_pool_recycle_rule', 'crm:update_lead_pool_recycle_rule']) {
    const command = recycleRuleCommands.find((candidate) => candidate.code === code);
    assert.ok(command.inputFields.includes('crm_lprr_pool_id'));
    assert.ok(command.inputFields.includes('crm_lprr_operator'));
    assert.ok(command.inputFields.includes('crm_lprr_days'));
  }

  for (const code of [
    'crm:move_lead_to_pool',
    'crm:claim_pool_lead',
    'crm:assign_pool_lead',
    'crm:update_pool_lead',
    'crm:delete_pool_lead',
    'crm:toggle_lead_pool',
    'crm:delete_lead_pool',
    'crm:download_lead_pool_import_template',
    'crm:precheck_lead_pool_import',
    'crm:import_lead_pool_leads',
    'crm:run_lead_pool_recycle',
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
      'crm_lead_pool_list',
      'crm_lead_pool_form',
      'crm_lead_pool_item_list',
      'crm_lead_pool_batch_list',
      'crm_lead_capacity_list',
      'crm_lead_owner_history_list',
    ],
  );
  assert.deepEqual(
    recycleRulePages.map((page) => page.pageKey),
    [
      'crm_lead_pool_recycle_rule_list',
      'crm_lead_pool_recycle_rule_form',
      'crm_lead_pool_recycle_rule_detail',
    ],
  );
  assert.match(
    recycleRulePages[0].blocks.find((block) => block.id === 'crm_lprr_guidance').content['zh-CN'],
    /全部满足（AND）\/任一满足（OR）/,
  );
  const recycleConditionFields = recycleRulePages[1].blocks
    .find((block) => block.id === 'time_condition').fields;
  const poolRecycleFields = pages[1].blocks
    .find((block) => block.id === 'recycle_policy').fields;
  assert.equal(
    poolRecycleFields.find((field) => field.field === 'crm_lp_recycle_match_mode').defaultValue,
    'all',
    'the create form must submit a concrete AND/OR value even when the first enum option is chosen',
  );
  const recycleIdentityFields = recycleRulePages[1].blocks
    .find((block) => block.id === 'identity').fields;
  assert.equal(
    recycleIdentityFields.find((field) => field.field === 'crm_lprr_status').defaultValue,
    'active',
  );
  assert.equal(
    recycleIdentityFields.find((field) => field.field === 'crm_lprr_sort_order').defaultValue,
    100,
  );
  assert.match(
    recycleConditionFields.find((field) => field.field === 'crm_lprr_days').visibleWhen,
    /older_than_days/,
  );
  assert.match(
    recycleConditionFields.find((field) => field.field === 'crm_lprr_start_at').visibleWhen,
    /fixed_between/,
  );
  assert.match(
    recycleConditionFields.find((field) => field.field === 'crm_lprr_end_at').visibleWhen,
    /fixed_before/,
  );
  const operations = pages[2];
  assert.equal(operations.kind, 'detail');
  assert.equal(operations.dataSources.poolStats.queryCode, 'crm_lead_pool_ops_stats');
  assert.equal(operations.dataSources.poolQueue.queryCode, 'crm_lead_pool_ops_queue');
  assert.deepEqual(
    operations.blocks.find((block) => block.id === 'crm_lead_pool_metrics').metrics.map((metric) => metric.key),
    ['available', 'ready', 'cooldown', 'owned', 'processing'],
  );
  assert.equal(operations.blocks.find((block) => block.id === 'crm_lead_pool_metrics').density, 'compact');
  assert.equal(operations.blocks.find((block) => block.id === 'crm_lead_pool_search').density, 'compact');
  const poolQueue = operations.blocks.find((block) => block.id === 'crm_lead_pool_queue');
  assert.equal(poolQueue.maxHeight, 360);
  assert.equal(poolQueue.density, 'compact');
  assert.equal(poolQueue.selection.defaultFirst, true);
  const operationalStateColumn = poolQueue.columns.find((column) => column.field === 'operational_state');
  assert.equal(operationalStateColumn?.dictCode, 'crm_lead_pool_operational_state');
  assert.deepEqual(
    dictionaries.find((dictionary) => dictionary.code === 'crm_lead_pool_operational_state')
      ?.items.map((item) => item.value),
    ['ready', 'cooldown', 'quota_blocked', 'capacity_blocked', 'claimed', 'assigned', 'recycling', 'recycling_retry'],
  );
  const poolActions = operations.blocks.find((block) => block.id === 'crm_lead_pool_actions').actions;
  assert.deepEqual(poolActions.map((action) => action.code), ['claim', 'assign']);
  const claimAction = poolActions.find((action) => action.code === 'claim');
  assert.equal(claimAction.permissionCode, 'crm.lead_pool.pick');
  assert.match(claimAction.onClick.args.targetRecordPid, /selectedPoolItem\.pid/);
  assert.deepEqual(claimAction.onClick.args.reload, ['poolStats', 'poolQueue']);
  const assignAction = poolActions.find((action) => action.code === 'assign');
  assert.equal(assignAction.permissionCode, 'crm.lead_pool.assign');
  assert.equal(assignAction.onClick.args.inputFields?.[0]?.field, 'crm_lpi_claimed_by');
  assert.equal(assignAction.onClick.args.inputFields?.[0]?.component, 'MemberPicker');
  assert.ok(operations.blocks.some((block) => block.blockType === 'status-banner'));
  assert.ok(operations.blocks.some((block) => block.blockType === 'evidence-panel'));
  const batchPage = pages[3];
  const batchQueue = batchPage.blocks.find((block) => block.id === 'pool_queue');
  assert.deepEqual(
    batchQueue.table.bulkActions.map((action) => action.code),
    ['batch_claim', 'batch_assign', 'batch_update_source', 'batch_update_score', 'batch_delete'],
  );
  const batchAssign = batchQueue.table.bulkActions.find((action) => action.code === 'batch_assign');
  assert.equal(batchAssign.action.input.component, 'MemberPicker');
  assert.equal(
    batchQueue.table.bulkActions.find((action) => action.code === 'batch_update_source').action.command,
    'crm:update_pool_lead',
  );
  assert.deepEqual(
    batchQueue.table.bulkActions.find((action) => action.code === 'batch_delete').action,
    { type: 'bulk_record_command', command: 'crm:delete_pool_lead', operationType: 'DELETE' },
  );
  const rowActions = batchQueue.columns.find((column) => column.isActionColumn).buttons;
  assert.equal(rowActions.find((action) => action.code === 'quick_update').action.command, 'crm:update_pool_lead');
  assert.equal(rowActions.find((action) => action.code === 'delete').action.command, 'crm:delete_pool_lead');
  const capacityDelete = pages[4].blocks
    .find((block) => block.id === 'table').columns
    .find((column) => column.isActionColumn).buttons
    .find((action) => action.code === 'delete');
  assert.equal(capacityDelete.action.command, 'crm:delete_lead_capacity');
  const poolRowActions = pages[0].blocks
    .find((block) => block.id === 'crm_lp_table').columns
    .find((column) => column.isActionColumn).buttons;
  assert.equal(poolRowActions.find((action) => action.code === 'quick_update').action.command, 'crm:update_lead_pool');
  for (const code of ['download_import_template', 'precheck_import', 'import_leads']) {
    assert.ok(poolRowActions.some((action) => action.code === code), `missing pool action ${code}`);
  }
  assert.equal(poolRowActions.find((action) => action.code === 'precheck_import').action.inputFields[1].type, 'file');
  assert.ok(
    operations.blocks
      .find((block) => block.id === 'crm_lead_pool_header_actions')
      .actions.some((action) => action.code === 'batch_operations'),
  );

  const statsQuery = namedQueries.find((query) => query.code === 'crm_lead_pool_ops_stats');
  const queueQuery = namedQueries.find((query) => query.code === 'crm_lead_pool_ops_queue');
  assert.equal(statsQuery.resourceCode, 'crm.lead_pool');
  assert.match(statsQuery.fromSql, /tenant_id = #\{params\.tenantId\}/);
  assert.match(statsQuery.fromSql, /params\.currentUserId/);
  assert.match(statsQuery.fromSql, /crm_lp_member_user_ids/);
  assert.match(statsQuery.fromSql, /crm_lp_admin_user_ids/);
  assert.match(statsQuery.fromSql, /crm_lpi_claim_release_at <= now\(\)/);
  assert.deepEqual(
    statsQuery.outputFields.map((field) => field.code),
    ['available_count', 'ready_count', 'cooldown_count', 'owned_count', 'processing_count'],
  );
  assert.equal(queueQuery.resourceCode, 'crm.lead_pool');
  assert.match(queueQuery.fromSql, /i\.tenant_id = #\{params\.tenantId\}/);
  assert.match(queueQuery.fromSql, /params\.currentUserId/);
  assert.match(queueQuery.fromSql, /CAST\(#\{params\.viewFilter\} AS text\) = 'ready'/);
  assert.match(queueQuery.fromSql, /CAST\(#\{params\.viewFilter\} AS text\) = 'processing'/);
  assert.ok(queueQuery.outputFields.some((field) => field.code === 'operational_state'));

  const permissionCodes = new Set(permissions.map((permission) => permission.code));
  for (const code of [
    'crm.lead_pool.read',
    'crm.lead_pool.pick',
    'crm.lead_pool.move',
    'crm.lead_pool.assign',
    'crm.lead_pool.manage',
    'crm.lead_pool.recycle',
    'crm.lead_pool.import',
  ]) assert.ok(permissionCodes.has(code), `missing permission ${code}`);
  const role = (code) => roles.find((candidate) => candidate.code === code).permissions;
  assert.ok(role('crm_sales').includes('crm.lead_pool.pick'));
  assert.ok(role('crm_sales').includes('crm.lead_pool.import'));
  assert.ok(!role('crm_sales').includes('crm.lead_pool.assign'));
  assert.ok(role('crm_sales_manager').includes('crm.lead_pool.assign'));
  assert.ok(role('crm_sales_manager').includes('crm.lead_pool.recycle'));
  assert.ok(role('crm_sales_manager').includes('crm.lead_pool.import'));

  for (const proof of [
    'incrementWithinCap',
    'compareAndSet',
    'recycling_retry',
    'tryCreate',
    'Previous owner cooldown',
    'Lead capacity reached',
    'available leads and cannot be deleted',
    'crm_lead_owner_history_common',
  ]) assert.match(handler, new RegExp(proof));
  assert.match(scheduler, /@Scheduled/);
  assert.match(scheduler, /listActiveTenantIds/);
  assert.match(handler, /currentUserPid/);
  assert.match(handler, /replaceReadSharesForUsers/);
  assert.match(shareSync, /chainsAfterPrimary\(\).*true/);
  assert.match(shareSync, /crm_lead_owner_history_common/);
  for (const proof of [
    'crm-lead-pool-import-template.xlsx',
    'importFileId is required',
    'Uploaded file owner does not match',
    'crm_lead_pool_item_common',
    'crm_lead_pool_state',
  ]) assert.match(importService, new RegExp(proof));

  const poolItem = models.find((model) => model.code === 'crm_lead_pool_item_common');
  assert.equal(poolItem.extension?.dataScope?.ownerField, 'crm_lpi_claimed_by');
  const capacity = models.find((model) => model.code === 'crm_lead_capacity_common');
  assert.equal(capacity.extension?.dataScope?.ownerField, 'crm_lcap_user_id');
  const quota = models.find((model) => model.code === 'crm_lead_pool_quota_common');
  assert.equal(quota.extension?.dataScope?.ownerField, 'crm_lpq_user_id');
});

test('PAR-04 workbench search fields request import-managed trigram indexes', async () => {
  const bindings = await json('config/bindings/crm_lead_pool_item.json');
  for (const fieldCode of ['crm_lpi_lead_code', 'crm_lpi_company', 'crm_lpi_contact_name']) {
    const binding = bindings.find((candidate) => candidate.fieldCode === fieldCode);
    assert.ok(binding, `${fieldCode} binding must exist`);
    assert.equal(binding.displayConfig?.searchable, true, `${fieldCode} must drive schema search indexing`);
  }
});

test('PAR-04 mobile routes expose governed move-to-pool and pooled-lead detail journeys', async () => {
  const [menus, plugin, leadFields, leadList, moveForm, poolList, poolDetail] = await Promise.all([
    json('config/menus.json'),
    json('plugin.json'),
    json('config/fields/crm_lead_common.json'),
    json('config/pages/crm_lead_common_list.json'),
    json('config/pages/crm_lead_move_to_pool_form.json'),
    json('config/pages/crm_lead_pool_mobile_list.json'),
    json('config/pages/crm_lead_pool_mobile_detail.json'),
  ]);

  const webMenu = menus.find((menu) => menu.code === 'crm_lead_pool_queue');
  const mobileMenu = menus.find((menu) => menu.code === 'crm_lead_pool_mobile_queue');
  assert.deepEqual(webMenu.extension.platforms, ['web']);
  assert.deepEqual(mobileMenu.extension.platforms, ['mobile']);
  assert.equal(mobileMenu.pageKey, 'crm_lead_pool_mobile_list');

  const providedPages = new Set(
    plugin.provides.filter((entry) => entry.type === 'page').map((entry) => entry.code),
  );
  for (const pageKey of [
    'crm_lead_move_to_pool_form',
    'crm_lead_pool_mobile_list',
    'crm_lead_pool_mobile_detail',
  ]) assert.ok(providedPages.has(pageKey), `missing mobile page ${pageKey}`);

  const targetPool = leadFields.find((field) => field.code === 'crm_lead_target_pool_id');
  assert.equal(targetPool.referenceModelCode, 'crm_lead_pool_common');
  assert.equal(targetPool.refTarget.displayField, 'crm_lp_name');
  const leadActions = leadList.blocks
    .find((block) => block.id === 'crm_lead_table').columns
    .find((column) => column.isActionColumn).buttons;
  assert.equal(leadActions.find((action) => action.code === 'move_to_pool').mobile.disabled, true);
  assert.equal(
    leadActions.find((action) => action.code === 'move_to_pool_mobile').action.to,
    'crm_lead_move_to_pool_form',
  );
  assert.equal(
    moveForm.blocks.find((block) => block.id === 'target_pool').fields[0].field,
    'crm_lead_target_pool_id',
  );
  assert.equal(
    moveForm.blocks.find((block) => block.id === 'buttons').buttons[0].action.command,
    'crm:move_lead_to_pool',
  );

  assert.equal(poolList.kind, 'list');
  assert.equal(poolList.mobileUx.list.card.title.field, 'crm_lpi_company');
  const claim = poolList.blocks[0].columns
    .find((column) => column.isActionColumn).buttons[0];
  assert.equal(claim.action.command, 'crm:claim_pool_lead');
  assert.equal(claim.permissionCode, 'crm.lead_pool.pick');
  assert.equal(poolDetail.kind, 'detail');
  assert.equal(poolDetail.mobileUx.detail.header.title.field, 'crm_lpi_company');
  assert.ok(
    poolDetail.mobileUx.detail.sections.some((section) =>
      section.fields?.includes('crm_lpi_claim_release_at')),
  );
});

test('PAR-04 config mutation proves missing claim permission turns the contract red', async () => {
  const commands = await json('config/commands/crm_lead_pool.json');
  const mutant = structuredClone(commands);
  mutant.find((command) => command.code === 'crm:claim_pool_lead').permissions = [];
  assert.throws(() => {
    assert.deepEqual(
      mutant.find((command) => command.code === 'crm:claim_pool_lead').permissions,
      ['crm.lead_pool.pick'],
    );
  });
});
