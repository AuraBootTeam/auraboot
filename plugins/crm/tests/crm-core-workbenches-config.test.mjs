import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const pluginRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const readJson = (relativePath) => JSON.parse(
  fs.readFileSync(path.join(pluginRoot, 'config', relativePath), 'utf8'),
);

const pagesDir = path.join(pluginRoot, 'config', 'pages');
const pages = new Map(
  fs.readdirSync(pagesDir)
    .filter((name) => name.endsWith('.json'))
    .map((name) => {
      const page = JSON.parse(fs.readFileSync(path.join(pagesDir, name), 'utf8'));
      return [page.pageKey, page];
    }),
);
const menus = new Map(readJson('menus.json').map((menu) => [menu.code, menu]));
const dicts = new Map(readJson('dicts.json').map((dict) => [dict.code, dict]));
const namedQueries = new Map(readJson('named-queries.json').map((query) => [query.code, query]));
const roles = new Map(readJson('roles.json').map((role) => [role.code, role]));

const commandDir = path.join(pluginRoot, 'config', 'commands');
const commands = new Map(
  fs.readdirSync(commandDir)
    .filter((name) => name.endsWith('.json'))
    .flatMap((name) => JSON.parse(fs.readFileSync(path.join(commandDir, name), 'utf8')))
    .map((command) => [command.code, command]),
);

function allBlocks(page) {
  const blocks = [];
  const visit = (candidate) => {
    if (!candidate || typeof candidate !== 'object') return;
    if (candidate.blockType) blocks.push(candidate);
    for (const nested of candidate.blocks ?? []) visit(nested);
    for (const tab of candidate.tabs ?? []) {
      for (const nested of tab.blocks ?? []) visit(nested);
    }
  };
  for (const block of page.blocks ?? []) visit(block);
  return blocks;
}

const workbenches = [
  {
    pageKey: 'crm_customer_360_workbench',
    menu: 'crm_customer_360_workspace',
    permission: 'crm.account.read',
    queryCodes: ['crm_customer_360_stats', 'crm_customer_360_queue'],
  },
  {
    pageKey: 'crm_lead_desk_workbench',
    menu: 'crm_lead_desk_workspace',
    permission: 'crm.lead.read',
    queryCodes: ['crm_lead_desk_stats', 'crm_lead_desk_queue'],
  },
  {
    pageKey: 'crm_opportunity_workspace',
    menu: 'crm_opportunity_workspace',
    permission: 'crm.opportunity.read',
    queryCodes: ['crm_opportunity_workspace_stats', 'crm_opportunity_workspace_queue'],
  },
  {
    pageKey: 'crm_forecast_cockpit',
    menu: 'crm_forecast_cockpit_workspace',
    permission: 'crm.forecast.read',
    queryCodes: ['crm_forecast_cockpit_stats', 'crm_sales_forecast_by_owner'],
  },
  {
    pageKey: 'crm_activity_service_desk',
    menu: 'crm_activity_service_workspace',
    permission: 'crm.activity.read',
    queryCodes: ['crm_activity_service_stats', 'crm_activity_service_queue'],
  },
];

test('five core workbenches are standalone DSL pages reachable from the primary CRM navigation', () => {
  for (const [index, expected] of workbenches.entries()) {
    const page = pages.get(expected.pageKey);
    assert.ok(page, `${expected.pageKey} must exist`);
    assert.equal(page.kind, 'detail');
    assert.equal(page.schemaVersion, 4);
    assert.equal(page.layout?.type, 'stack');
    assert.equal(page.extension?.showEdit, false);

    const menu = menus.get(expected.menu);
    assert.ok(menu, `${expected.menu} must exist`);
    assert.equal(menu.parentCode, 'crm_root');
    assert.equal(menu.visible, true);
    assert.equal(menu.permissionCode, expected.permission);
    assert.equal(menu.pageKey, expected.pageKey);
    assert.equal(menu.path, `/p/c/${expected.pageKey}`);
    assert.equal(menu.orderNo, index + 1);

    const blocks = allBlocks(page);
    for (const blockType of ['metric-strip', 'table', 'status-banner', 'evidence-panel']) {
      assert.ok(blocks.some((block) => block.blockType === blockType),
        `${expected.pageKey} must include ${blockType}`);
    }
    assert.ok(blocks.some((block) => block.blockType === 'workbench-action-bar'),
      `${expected.pageKey} must expose an explicit next-action surface`);

    for (const queryCode of expected.queryCodes) {
      const query = namedQueries.get(queryCode);
      assert.ok(query, `${queryCode} must exist`);
      assert.match(query.fromSql, /#\{params\.tenantId\}/,
        `${queryCode} must remain tenant-scoped`);
      assert.ok(Array.isArray(query.outputFields) && query.outputFields.length > 0,
        `${queryCode} must declare its output contract`);
    }
  }
});

test('record queues declare the governed model/action used by role data scopes', () => {
  const expected = {
    crm_customer_360_queue: 'crm_account_common',
    crm_lead_desk_queue: 'crm_lead_common',
    crm_opportunity_workspace_queue: 'crm_opportunity_common',
    crm_activity_service_queue: 'crm_activity_common',
  };
  for (const [queryCode, resourceCode] of Object.entries(expected)) {
    const query = namedQueries.get(queryCode);
    assert.equal(query.resourceCode, resourceCode, queryCode);
    assert.equal(query.actionCode, 'read', queryCode);
  }
  assert.match(namedQueries.get('crm_activity_service_queue').fromSql, /AS crm_act_owner/,
    'the unified queue must retain the governed task-owner column for outer DataScope filtering');
});

test('core workbench lifecycle buttons reference real CRM commands', () => {
  const referenced = new Set();
  const actions = [];
  for (const expected of workbenches) {
    for (const block of allBlocks(pages.get(expected.pageKey))) {
      for (const action of block.actions ?? []) {
        actions.push(action);
        assert.ok(action.permissionCode, `${expected.pageKey}.${action.code} must be permission-gated`);
        const command = action.onClick?.action === 'command.execute'
          ? action.onClick?.args?.command
          : undefined;
        if (command) {
          referenced.add(command);
          assert.ok(
            commands.get(command)?.permissions?.includes(action.permissionCode),
            `${expected.pageKey}.${action.code} permission must match ${command}`,
          );
        }
      }
    }
  }
  assert.equal(actions.length, 26, 'RG-1 denominator must contain exactly 26 semantic actions');
  assert.equal(referenced.size, 15, 'workbenches should drive every declared lifecycle command');
  for (const commandCode of referenced) {
    assert.ok(commands.has(commandCode), `${commandCode} must be declared by CRM`);
  }
});

test('formal CRM roles align the five workbench menu and action contracts', () => {
  const required = {
    crm_admin: [
      'crm.account.manage', 'crm.lead.manage', 'crm.opportunity.manage',
      'crm.forecast.manage', 'crm.activity.manage', 'crm.complaint.manage',
    ],
    crm_sales: [
      'crm.account.manage', 'crm.lead.manage', 'crm.opportunity.manage',
      'crm.forecast.manage', 'crm.activity.manage',
    ],
    crm_sales_manager: [
      'crm.account.manage', 'crm.lead.manage', 'crm.opportunity.manage',
      'crm.forecast.manage', 'crm.activity.manage',
    ],
    crm_qdp_release_manager: [
      'crm.qdp.release', 'crm.qdp.read', 'crm.quote_summary.manage',
      'crm.order_commitment.manage',
    ],
    crm_service: ['crm.account.read', 'crm.activity.manage', 'crm.complaint.manage'],
    crm_viewer: [
      'crm.account.read', 'crm.lead.read', 'crm.opportunity.read',
      'crm.forecast.read', 'crm.activity.read', 'crm.complaint.read', 'crm.qdp.read',
    ],
  };
  for (const [roleCode, permissions] of Object.entries(required)) {
    const role = roles.get(roleCode);
    assert.ok(role, `${roleCode} must exist`);
    for (const permission of permissions) {
      assert.ok(role.permissions.includes(permission), `${roleCode} is missing ${permission}`);
    }
  }
  assert.ok(!roles.get('crm_viewer').permissions.some((permission) => permission.endsWith('.manage')),
    'crm_viewer must stay read-only');
  assert.ok(!roles.get('crm_service').permissions.includes('crm.forecast.read'),
    'crm_service must not gain unrelated forecast access');
});

test('CRM navigation prioritizes workspaces and contains record breadth below secondary groups', () => {
  assert.equal(menus.get('crm_records')?.parentCode, 'crm_root');
  assert.equal(menus.get('crm_operations')?.parentCode, 'crm_root');
  for (const code of ['crm_accounts', 'crm_contacts', 'crm_leads', 'crm_opportunities',
    'crm_customer_requests', 'crm_quote_summaries', 'crm_tasks', 'crm_activities',
    'crm_complaints', 'crm_forecast_submit', 'crm_approval_case_menu',
    'crm_review_common_menu', 'crm_risk_common_menu', 'crm_clarification_common_menu']) {
    assert.equal(menus.get(code)?.parentCode, 'crm_records', `${code} belongs under business records`);
  }
  for (const code of ['crm_dashboard', 'crm_sales_forecast', 'crm_sales_workbench',
    'crm_manager_workbench']) {
    assert.equal(menus.get(code)?.visible, false, `${code} legacy entry must not compete with workspaces`);
  }
  assert.equal(menus.get('crm_qdp_release_center')?.orderNo, 6);
});

test('workbench-only decision labels are dictionary-backed and localized', () => {
  for (const code of [
    'crm_customer_attention_reason',
    'crm_lead_next_action',
    'crm_opportunity_next_action',
    'crm_forecast_status',
    'crm_work_item_kind',
    'crm_work_item_status',
    'crm_work_attention_reason',
  ]) {
    const dict = dicts.get(code);
    assert.ok(dict, `${code} must exist`);
    assert.ok(dict.items.length >= 2, `${code} must expose localized values`);
    for (const item of dict.items) {
      assert.ok(item.label);
      assert.ok(item['label:zh-CN']);
    }
  }
});

test('status-banner summaries never expose CRM lifecycle storage codes', () => {
  const expectedMaps = [
    ['crm_lead_desk_workbench', 'crm_lead_status', ['new', 'contacted', 'qualified', 'converted', 'lost']],
    ['crm_opportunity_workspace', 'crm_opp_stage', [
      'discovery', 'qualification', 'proposal', 'negotiation', 'closed_won', 'closed_lost',
    ]],
    ['crm_activity_service_desk', 'item_kind', ['task', 'complaint']],
    ['crm_activity_service_desk', 'item_status', [
      'open', 'in_progress', 'done', 'cancelled', 'investigating', 'resolved', 'closed',
    ]],
  ];
  for (const [pageKey, fieldCode, values] of expectedMaps) {
    const banner = allBlocks(pages.get(pageKey))
      .find((block) => block.blockType === 'status-banner');
    const summary = banner?.summaryFields?.find((field) => field.field === fieldCode);
    assert.ok(summary?.valueMap, `${pageKey}.${fieldCode} must map storage values`);
    for (const value of values) {
      assert.ok(summary.valueMap[value]?.['zh-CN'], `${pageKey}.${fieldCode}.${value} needs zh-CN`);
      assert.ok(summary.valueMap[value]?.en, `${pageKey}.${fieldCode}.${value} needs en`);
    }
  }
});

test('activity and service workbench maps priority storage codes in every visible surface', () => {
  const page = pages.get('crm_activity_service_desk');
  const blocks = allBlocks(page);
  const queue = blocks.find((block) => block.id === 'crm_activity_service_queue');
  const priorityColumn = queue?.columns?.find((column) => column.field === 'item_priority');
  assert.equal(priorityColumn?.dictCode, 'crm_task_priority');

  const context = blocks.find((block) => block.id === 'crm_activity_service_context');
  const prioritySection = context?.sections?.find((section) => section.field === 'item_priority');
  for (const value of ['critical', 'high', 'medium', 'low']) {
    assert.ok(prioritySection?.valueMap?.[value]?.['zh-CN'], `${value} needs zh-CN evidence label`);
    assert.ok(prioritySection?.valueMap?.[value]?.en, `${value} needs en evidence label`);
  }
});
