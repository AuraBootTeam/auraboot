import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function json(path) {
  return JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
}

const [
  detail,
  opportunityList,
  leadDesk,
  opportunityWorkspace,
  forecastCockpit,
  serviceDesk,
  savedViews,
  namedQueries,
  models,
] = await Promise.all([
  json('../config/pages/crm_opportunity_common_detail.json'),
  json('../config/pages/crm_opportunity_common_list.json'),
  json('../config/pages/crm_lead_desk_workbench.json'),
  json('../config/pages/crm_opportunity_workspace.json'),
  json('../config/pages/crm_forecast_cockpit.json'),
  json('../config/pages/crm_activity_service_desk.json'),
  json('../config/saved-views.json'),
  json('../config/named-queries.json'),
  json('../config/models.json'),
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

test('opportunity daily-efficiency surface shares one saved-view fact across list and kanban', () => {
  assert.equal(opportunityList.extension?.enableMultiView, true);
  const pipelineBoard = savedViews.find(
    (view) => view.modelCode === 'crm_opportunity_common' && view.viewType === 'kanban',
  );
  assert.ok(pipelineBoard, 'opportunity kanban preset should exist');
  assert.equal(pipelineBoard.pageKey, opportunityList.pageKey);
  assert.equal(pipelineBoard.viewKey, 'crm_opportunity_pipeline_board');
  assert.equal(pipelineBoard.viewConfig.groupByField, 'crm_opp_stage');
  assert.equal(pipelineBoard.viewConfig.titleField, 'crm_opp_name');

  const planAndQuotes = detail.blocks
    .find((block) => block.id === 'crm_opportunity_tabs')
    ?.tabs?.find((tab) => tab.key === 'plan_and_quotes');
  assert.equal(planAndQuotes?.label?.['zh-CN'], '报价与计划');
  const plan = findBlock(planAndQuotes?.blocks, 'block_opportunity_plan');
  const quotes = findBlock(planAndQuotes?.blocks, 'block_opportunity_quotes');
  const actions = findBlock(planAndQuotes?.blocks, 'crm_opp_plan_quote_actions');
  assert.equal(plan?.subTable?.dataSource?.params?.objectType, 'opportunity');
  assert.ok(plan?.subTable?.columns?.some((column) => column.field === 'crm_act_due_date'));
  assert.equal(quotes?.subTable?.parentField, 'crm_qs_opportunity_id');
  assert.deepEqual(
    actions?.buttons?.map((button) => button.code),
    ['create_plan_task', 'log_plan_activity', 'create_quote_summary'],
  );
});

test('forecast cockpit separates outcome hierarchy from execution status', () => {
  const primary = findBlock(forecastCockpit.blocks, 'crm_forecast_metrics');
  assert.deepEqual(
    primary.metrics.map((metric) => metric.valueField),
    ['commit_amount', 'best_case_amount', 'weighted_forecast', 'total_pipeline'],
  );
  const execution = findBlock(forecastCockpit.blocks, 'crm_forecast_execution_metrics');
  assert.equal(execution.variant, 'chips');
  assert.deepEqual(
    execution.metrics.map((metric) => metric.valueField),
    ['open_deals', 'draft_submissions', 'submitted_count'],
  );
  const forecastStats = namedQueries.find((query) => query.code === 'crm_forecast_cockpit_stats');
  assert.ok(forecastStats.outputFields.some((field) => field.code === 'commit_amount'));
  assert.ok(forecastStats.outputFields.some((field) => field.code === 'best_case_amount'));
  assert.match(forecastStats.fromSql, /crm_opp_forecast_category = 'commit'/);
});

test('command-owned QDP evidence models opt out of empty generated detail shells', () => {
  for (const code of [
    'crm_file_package_common',
    'crm_requirement_version_common',
    'crm_customer_confirmation_common',
  ]) {
    const model = models.find((candidate) => candidate.code === code);
    assert.equal(model?.commandOnlyCreate, true, `${code} should stay command-owned`);
    assert.equal(model?.extension?.skipDetailPage, true, `${code} should not expose an empty shell`);
  }
});

test('CRM workbenches expose business owner labels and a lead conversion receipt', () => {
  const leadQueue = findBlock(leadDesk.blocks, 'crm_lead_desk_queue');
  assert.ok(leadQueue.columns.some((column) => column.field === 'owner_name'));
  const leadActions = findBlock(leadDesk.blocks, 'crm_lead_lifecycle_actions');
  const convert = leadActions.actions.find((action) => action.code === 'convert_lead');
  assert.equal(convert.resultReceipt?.title?.['zh-CN'], '线索转化完成');
  assert.equal(convert.resultReceipt?.links?.length, 4);

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
