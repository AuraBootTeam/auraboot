import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function json(relativePath) {
  return JSON.parse(await readFile(new URL(relativePath, import.meta.url), 'utf8'));
}

test('PAR-05 account relationships are a governed bidirectional customer detail surface', async () => {
  const [models, fields, commands, accountDetail] = await Promise.all([
    json('../config/models.json'),
    json('../config/fields/crm_account_relation_common.json'),
    json('../config/commands/crm_account_relation_common.json'),
    json('../config/pages/crm_account_common_detail.json'),
  ]);

  const model = models.find((candidate) => candidate.code === 'crm_account_relation_common');
  assert.equal(model?.modelCategory, 'entity');
  assert.equal(model?.extension?.dataScope?.ownerField, 'crm_acr_owner');

  const source = fields.find((field) => field.code === 'crm_acr_source_account_id');
  const target = fields.find((field) => field.code === 'crm_acr_target_account_id');
  const pairKey = fields.find((field) => field.code === 'crm_acr_pair_key');
  assert.equal(source?.referenceModelCode, 'crm_account_common');
  assert.equal(target?.referenceModelCode, 'crm_account_common');
  assert.equal(source?.refTarget?.displayField, 'crm_acc_name');
  assert.equal(target?.refTarget?.displayField, 'crm_acc_name');
  assert.equal(pairKey?.constraints?.unique, true);
  assert.equal(pairKey?.feature?.readonly, true);

  assert.deepEqual(
    commands.map((command) => command.code),
    [
      'crm:create_account_relation',
      'crm:update_account_relation',
      'crm:save_account_relation',
      'crm:delete_account_relation',
      'crm:detail_account_relation',
      'crm:list_account_relations',
    ],
  );
  assert.ok(commands.every((command) => command.modelCode === 'crm_account_relation_common'));

  const relationshipTab = accountDetail.blocks
    .find((block) => block.id === 'crm_account_tabs')
    ?.tabs?.find((tab) => tab.key === 'relationships');
  const outgoing = relationshipTab?.blocks?.find(
    (block) => block.id === 'block_outgoing_relationships',
  );
  const relationshipActions = relationshipTab?.blocks?.find(
    (block) => block.id === 'crm_account_relationship_actions',
  );
  const incoming = relationshipTab?.blocks?.find(
    (block) => block.id === 'block_incoming_relationships',
  );
  assert.equal(outgoing?.subTable?.parentField, 'crm_acr_source_account_id');
  assert.equal(outgoing?.subTable?.readOnly, false);
  assert.equal(relationshipActions?.blockType, 'toolbar');
  assert.deepEqual(relationshipActions?.buttons?.map((button) => button.code), ['add']);
  assert.equal(
    relationshipActions?.buttons?.[0]?.action?.to,
    'crm_account_relation_common_form',
  );
  assert.deepEqual(
    outgoing?.subTable?.actions?.map((action) => action.code),
    ['view', 'edit', 'delete'],
  );
  assert.equal(incoming?.subTable?.parentField, 'crm_acr_target_account_id');
  assert.equal(incoming?.subTable?.readOnly, true);
  assert.deepEqual(incoming?.subTable?.actions?.map((action) => action.code), ['view']);
});

test('PAR-05 customer plan and compact relationship hierarchy stay DSL-first', async () => {
  const [accountDetail, relationList] = await Promise.all([
    json('../config/pages/crm_account_common_detail.json'),
    json('../config/pages/crm_account_relation_common_list.json'),
  ]);
  const tabs = new Map(accountDetail.blocks.find((block) => block.id === 'crm_account_tabs')
    ?.tabs?.map((tab) => [tab.key, tab]));
  const plans = tabs.get('follow_plans');
  const planTable = plans?.blocks?.find((block) => block.id === 'block_account_follow_plans');
  assert.equal(planTable?.subTable?.dataSource?.params?.objectType, 'account');
  assert.deepEqual(planTable?.subTable?.actions?.map((action) => action.code),
    ['view_task', 'start_task', 'complete_task', 'cancel_task']);

  const relationColumns = relationList.blocks.find((block) => block.id === 'relationship_table')
    ?.columns?.filter((column) => !column.isActionColumn);
  const saveAction = relationList.blocks.find((block) => block.id === 'relationship_toolbar')
    ?.buttons?.find((button) => button.code === 'save');
  assert.equal(saveAction?.action?.command, 'crm:save_account_relation');
  assert.ok(relationColumns.reduce((sum, column) => sum + column.width, 0) <= 1000);
  assert.ok(!relationColumns.some((column) => column.field === 'crm_acr_owner'));
  assert.ok(relationColumns.some((column) => column.field === 'crm_acr_effective_to'));
});

test('PAR-05 relationship invariant rejects self, invalid-window and duplicate edges', async () => {
  const source = await readFile(
    new URL(
      '../backend/src/main/java/com/auraboot/plugins/crm/handler/AccountRelationInvariantHandler.java',
      import.meta.url,
    ),
    'utf8',
  );
  assert.match(source, /sourceId\.equals\(targetId\)/);
  assert.match(source, /Effective-to date cannot precede effective-from date/);
  assert.match(source, /crm_acr_pair_key/);
  assert.match(source, /This account relationship already exists/);
  assert.match(source, /chainsAfterPrimary\(\)/);
});
