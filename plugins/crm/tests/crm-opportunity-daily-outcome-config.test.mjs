import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function json(path) {
  return JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
}

const [workspace, commands, savedViews] = await Promise.all([
  json('../config/pages/crm_opportunity_workspace.json'),
  json('../config/commands/crm_opportunity_common.json'),
  json('../config/saved-views.json'),
]);

function findBlock(blocks, id) {
  for (const block of blocks ?? []) {
    if (block.id === id) return block;
    for (const tab of block.tabs ?? []) {
      const found = findBlock(tab.blocks, id);
      if (found) return found;
    }
  }
  return null;
}

test('sales daily workbench keeps tasks, opportunity record, and next step in one journey', () => {
  const shortcuts = findBlock(workspace.blocks, 'crm_opportunity_daily_shortcuts');
  const actions = new Map(shortcuts.buttons.map((action) => [action.code, action]));

  assert.equal(actions.get('open_my_tasks')?.permissionCode, 'crm.activity.read');
  assert.equal(actions.get('open_my_tasks')?.action?.to, 'crm_my_tasks_list');
  assert.equal(actions.get('create_next_step')?.permissionCode, 'crm.activity.manage');
  assert.match(actions.get('create_next_step')?.visibleWhen ?? '', /selectedOpportunity/);
  assert.match(
    actions.get('create_next_step')?.action?.to ?? '',
    /command=crm:create_opp_task&sourceRecordPid=\$\{state\.selectedOpportunity\.pid\}/,
  );
});

test('table and kanban remain projections of the same opportunity model and stage dictionary', () => {
  const opportunityViews = savedViews.filter(
    (view) => view.modelCode === 'crm_opportunity_common',
  );
  const table = opportunityViews.find((view) => view.viewType === 'table' && view.isDefault);
  const kanban = opportunityViews.find((view) => view.viewType === 'kanban');

  assert.equal(table?.pageKey, 'crm_opportunity_common_list');
  assert.equal(kanban?.pageKey, table?.pageKey);
  assert.equal(kanban?.viewConfig?.groupByField, 'crm_opp_stage');
  assert.equal(kanban?.viewConfig?.groupByDictCode, 'crm_opp_stage');
  assert.equal(kanban?.viewConfig?.idField, 'pid');
});

test('won and lost commands enforce closing rules in the command pipeline', () => {
  const byCode = new Map(commands.map((command) => [command.code, command]));
  const win = byCode.get('crm:win_opportunity');
  const lose = byCode.get('crm:lose_opportunity');

  assert.deepEqual(
    win.preconditions.map(({ field, operator, value }) => ({ field, operator, value })),
    [
      { field: 'crm_opp_expected_amount', operator: 'GT', value: 0 },
      { field: 'crm_opp_expected_close_date', operator: 'NOT_NULL', value: undefined },
    ],
  );
  assert.ok(lose.inputFields.includes('crm_opp_lost_reason_code'));
  assert.ok(lose.preconditions.some((rule) => rule.field === 'crm_opp_lost_reason_code'));

  const stageActions = findBlock(workspace.blocks, 'crm_opportunity_stage_actions');
  const loseAction = stageActions.actions.find((action) => action.code === 'lose_opportunity');
  const inputFields = loseAction.onClick.args.inputFields;
  assert.deepEqual(
    inputFields.map((field) => field.field),
    ['crm_opp_lost_reason_code', 'crm_opp_competitor', 'crm_opp_lost_reason'],
  );
  assert.equal(inputFields[0].required, true);
  assert.ok(inputFields.every((field) => field.placeholder && field.helpText));
  assert.ok(loseAction.onClick.args.feedback.errorMessage['zh-CN'].includes('检查商机状态'));
});

test('read and manage permissions keep personal shortcuts and close actions role-safe', () => {
  const shortcuts = findBlock(workspace.blocks, 'crm_opportunity_daily_shortcuts');
  const stageActions = findBlock(workspace.blocks, 'crm_opportunity_stage_actions');
  assert.equal(
    shortcuts.buttons.find((action) => action.code === 'open_my_tasks').permissionCode,
    'crm.activity.read',
  );
  assert.equal(
    stageActions.actions.find((action) => action.code === 'lose_opportunity').permissionCode,
    'crm.opportunity.manage',
  );
});
