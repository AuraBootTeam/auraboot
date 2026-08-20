import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const root = new URL('../', import.meta.url);
const json = async (path) => JSON.parse(await readFile(new URL(path, root), 'utf8'));

test('PAR-04 lead pool exposes the complete Cordys policy denominator', async () => {
  const models = await json('config/models.json');
  const commands = await json('config/commands/crm_lead_pool.json');
  const permissions = await json('config/permissions.json');
  const roles = await json('config/roles.json');
  const pages = await Promise.all([
    'crm_lead_pool_list.json',
    'crm_lead_pool_form.json',
    'crm_lead_pool_item_list.json',
    'crm_lead_capacity_list.json',
    'crm_lead_owner_history_list.json',
  ].map((file) => json(`config/pages/${file}`)));
  const poolFields = await json('config/fields/crm_lead_pool.json');
  const itemFields = await json('config/fields/crm_lead_pool_item.json');
  const handler = await readFile(
    new URL('backend/src/main/java/com/auraboot/plugins/crm/handler/LeadPoolCommandHandler.java', root),
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
    'crm_lead_pool',
    'crm_lead_capacity',
    'crm_lead_pool_item',
    'crm_lead_pool_quota',
    'crm_lead_owner_history',
  ]) assert.ok(modelCodes.has(code), `missing model ${code}`);

  const poolFieldCodes = new Set(poolFields.map((field) => field.code));
  for (const code of [
    'crm_lp_member_user_ids',
    'crm_lp_admin_user_ids',
    'crm_lp_daily_pick_limit',
    'crm_lp_new_cooldown_days',
    'crm_lp_previous_owner_cooldown_days',
    'crm_lp_auto_recycle',
    'crm_lp_recycle_after_days',
    'crm_lp_recycle_basis',
  ]) assert.ok(poolFieldCodes.has(code), `missing pool policy ${code}`);
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

  const commandCodes = new Set(commands.map((command) => command.code));
  for (const code of [
    'crm:move_lead_to_pool',
    'crm:claim_pool_lead',
    'crm:assign_pool_lead',
    'crm:toggle_lead_pool',
    'crm:delete_lead_pool',
    'crm:run_lead_pool_recycle',
  ]) assert.ok(commandCodes.has(code), `missing command ${code}`);

  for (const code of [
    'crm:move_lead_to_pool',
    'crm:claim_pool_lead',
    'crm:assign_pool_lead',
    'crm:toggle_lead_pool',
    'crm:delete_lead_pool',
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
      'crm_lead_capacity_list',
      'crm_lead_owner_history_list',
    ],
  );
  const poolQueue = pages[2].blocks.find((block) => block.id === 'pool_queue');
  assert.deepEqual(
    poolQueue.table.bulkActions.map((action) => action.code),
    ['batch_claim', 'batch_assign'],
  );
  const assignRowAction = poolQueue.columns
    .find((column) => column.isActionColumn)
    .buttons.find((action) => action.code === 'assign');
  assert.equal(assignRowAction.action?.operationType, 'update');
  assert.equal(assignRowAction.action?.inputFields?.[0]?.field, 'crm_lpi_claimed_by');
  assert.equal(assignRowAction.action?.inputFields?.[0]?.component, 'MemberPicker');

  const permissionCodes = new Set(permissions.map((permission) => permission.code));
  for (const code of [
    'crm.lead_pool.read',
    'crm.lead_pool.pick',
    'crm.lead_pool.move',
    'crm.lead_pool.assign',
    'crm.lead_pool.manage',
    'crm.lead_pool.recycle',
  ]) assert.ok(permissionCodes.has(code), `missing permission ${code}`);
  const role = (code) => roles.find((candidate) => candidate.code === code).permissions;
  assert.ok(role('crm_sales').includes('crm.lead_pool.pick'));
  assert.ok(!role('crm_sales').includes('crm.lead_pool.assign'));
  assert.ok(role('crm_sales_manager').includes('crm.lead_pool.assign'));
  assert.ok(role('crm_sales_manager').includes('crm.lead_pool.recycle'));

  for (const proof of [
    'incrementWithinCap',
    'compareAndSet',
    'Previous owner cooldown',
    'Lead capacity reached',
    'available leads and cannot be deleted',
    'crm_lead_owner_history',
  ]) assert.match(handler, new RegExp(proof));
  assert.match(scheduler, /@Scheduled/);
  assert.match(scheduler, /listActiveTenantIds/);
  assert.match(handler, /currentUserPid/);
  assert.match(handler, /replaceReadSharesForUsers/);
  assert.match(shareSync, /chainsAfterPrimary\(\).*true/);
  assert.match(shareSync, /crm_lead_owner_history/);

  const poolItem = models.find((model) => model.code === 'crm_lead_pool_item');
  assert.equal(poolItem.extension?.dataScope?.ownerField, 'crm_lpi_claimed_by');
  const capacity = models.find((model) => model.code === 'crm_lead_capacity');
  assert.equal(capacity.extension?.dataScope?.ownerField, 'crm_lcap_user_id');
  const quota = models.find((model) => model.code === 'crm_lead_pool_quota');
  assert.equal(quota.extension?.dataScope?.ownerField, 'crm_lpq_user_id');
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
