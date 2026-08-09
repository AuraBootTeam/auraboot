import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function json(path) {
  return JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
}

const [detail, leadDesk, opportunityWorkspace, serviceDesk] = await Promise.all([
  json('../config/pages/crm_opportunity_common_detail.json'),
  json('../config/pages/crm_lead_desk_workbench.json'),
  json('../config/pages/crm_opportunity_workspace.json'),
  json('../config/pages/crm_activity_service_desk.json'),
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

test('opportunity detail is a stage-led workspace with one primary transition and inline activity', () => {
  assert.equal(detail.extension?.headerTitleField, 'crm_opp_name');
  assert.equal(detail.extension?.preserveListContext, true);

  const rail = findBlock(detail.blocks, 'crm_opp_stage_rail');
  assert.equal(rail?.blockType, 'stage-rail');
  assert.equal(rail?.stageField, 'crm_opp_stage');
  assert.deepEqual(
    rail?.stages?.map((stage) => stage.value),
    ['discovery', 'qualification', 'proposal', 'negotiation', 'closed_won'],
  );
  assert.deepEqual(rail?.terminalStages?.map((stage) => stage.value), ['closed_lost']);

  const recentActivities = findBlock(detail.blocks, 'block_recent_activities');
  assert.equal(recentActivities?.blockType, 'sub-table');
  assert.equal(recentActivities?.subTable?.dataSource?.params?.objectType, 'opportunity');

  const toolbar = findBlock(detail.blocks, 'crm_opp_detail_toolbar');
  const transitions = new Map(toolbar.buttons.map((button) => [button.code, button]));
  for (const code of ['qualify', 'advance_proposal', 'advance_negotiation', 'win']) {
    assert.equal(transitions.get(code)?.primary, true, `${code} should be the stage primary action`);
    assert.ok(transitions.get(code)?.visibleWhen, `${code} should only show in its source stage`);
  }
  assert.equal(transitions.get('log_activity')?.label?.['zh-CN'], '记录活动');
  assert.equal(transitions.get('create_task')?.label?.['zh-CN'], '创建任务');
});

test('CRM workbenches expose business owner labels and a lead conversion receipt', () => {
  const leadQueue = findBlock(leadDesk.blocks, 'crm_lead_desk_queue');
  assert.ok(leadQueue.columns.some((column) => column.field === 'owner_name'));
  const leadActions = findBlock(leadDesk.blocks, 'crm_lead_lifecycle_actions');
  const convert = leadActions.actions.find((action) => action.code === 'convert_lead');
  assert.equal(convert.onClick.args.resultReceipt?.title?.['zh-CN'], '线索转化完成');
  assert.equal(convert.onClick.args.resultReceipt?.links?.length, 4);

  const opportunityOwner = findBlock(opportunityWorkspace.blocks, 'crm_opportunity_attention')
    .summaryFields.find((field) => field.label?.['zh-CN'] === '负责人');
  assert.equal(opportunityOwner.field, 'owner_name');
  const opportunityQueue = findBlock(opportunityWorkspace.blocks, 'crm_opportunity_queue');
  assert.ok(opportunityQueue.columns.some((column) => column.field === 'owner_name'));
  const opportunityActions = findBlock(
    opportunityWorkspace.blocks,
    'crm_opportunity_header_actions',
  );
  const openOpportunity = opportunityActions.actions.find(
    (action) => action.code === 'open_opportunity_record',
  );
  assert.equal(openOpportunity.onClick.args.context.state.searchKeyword, '${state.searchKeyword}');
  assert.equal(openOpportunity.onClick.args.context.state.viewFilter, '${state.viewFilter}');

  const serviceQueue = findBlock(serviceDesk.blocks, 'crm_activity_service_queue');
  assert.ok(serviceQueue.columns.some((column) => column.field === 'owner_name'));
});
