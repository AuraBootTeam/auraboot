import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const caps = JSON.parse(source('../config/capabilities.json'));
const constants = new Map([...source('../../../platform/src/main/java/com/auraboot/framework/permission/constants/MetaPermission.java')
  .matchAll(/public static final String (\w+) = "([^"]+)";/g)].map(match => [match[1], match[2]]));
const controllers = {
  'sys.cap.dashboard': 'dashboard/controller/DashboardController.java',
  'sys.cap.cloud_config': 'cloudconfig/controller/CloudConfigController.java',
  'sys.cap.notification_rule': 'notification/controller/NotificationRuleController.java',
  'sys.cap.integration': 'connector/controller/ApiConnectorController.java',
  'sys.cap.scheduled_task': 'scheduler/controller/ScheduledTaskController.java',
  'sys.cap.data_permission': 'meta/controller/config/DataPermissionController.java',
  'sys.cap.plugin': 'plugin/controller/PluginController.java',
};
test('platform management capabilities include the real controller guards', () => {
  for (const [code, file] of Object.entries(controllers)) {
    const cap = caps.find(item => item.code === code);
    const controller = source(`../../../platform/src/main/java/com/auraboot/framework/${file}`);
    const guards = [...controller.matchAll(/@RequirePermission\(MetaPermission\.(\w+)\)/g)].map(match => constants.get(match[1]));
    assert.ok(guards.length, `${file} must provide non-vacuous guard evidence`);
    for (const guard of guards) assert.ok(cap.includes.includes(guard), `${code} must include ${guard}`);
  }
  const dashboard = caps.find(item => item.code === 'sys.cap.dashboard');
  const versions = source('../../../platform/src/main/java/com/auraboot/framework/versioning/controller/VersionHistoryController.java');
  const readGuards = [...versions.matchAll(/@RequirePermission\(MetaPermission\.(DASHBOARD_READ)\)/g)];
  assert.equal(readGuards.length, 3, 'History, detail and count must require dashboard read');
  assert.match(versions, /@PostMapping\("\/\{pid\}\/versions\/\{versionPid\}\/rollback"\)\s+@RequirePermission\(MetaPermission\.DASHBOARD_MANAGE\)/);
  assert.ok(dashboard.includes.includes(constants.get('DASHBOARD_MANAGE')));
  assert.ok(!dashboard.includes.includes('dashboard.manage'), 'Do not grant public view sharing to repair version maintenance');
  const sharing = source('../../../platform/src/main/java/com/auraboot/framework/view/controller/ViewShareController.java');
  const shareGuards = [...sharing.matchAll(/@RequirePermission\(MetaPermission\.(VIEW_PUBLIC_SHARE)\)/g)];
  assert.equal(shareGuards.length, 3, 'Generate, revoke and token-bearing status require public sharing');
  assert.equal(constants.get('VIEW_PUBLIC_SHARE'), 'dashboard.manage', 'Retain the existing atomic code');
  const automation = caps.find(item => item.code === 'sys.cap.automation');
  const automationController = source('../../../platform/src/main/java/com/auraboot/framework/automation/controller/AutomationController.java');
  const automationReadGuards = [...automationController.matchAll(/@RequirePermission\(MetaPermission\.(AUTOMATION_READ)\)/g)]
    .map(match => constants.get(match[1]));
  assert.ok(automationReadGuards.length, 'Automation reads must have actual controller guards');
  for (const guard of automationReadGuards)
    assert.ok(automation.includes.includes(guard), `sys.cap.automation must include native read ${guard}`);
  const menus = JSON.parse(source('../config/menus.json'));
  const menu = menus.find(item => item.code === 'automation_menu');
  assert.equal(menu.path, '/automations');
  assert.ok(automation.includes.includes(menu.permissionCode), 'Retain the existing menu permission');
  for (const guard of [constants.get('AUTOMATION_MANAGE'), 'automation.admin'])
    assert.ok(!automation.includes.includes(guard), `Viewing automation must not grant ${guard}`);
});
test('platform DSL maintenance capabilities include model reads, CRUD and command execution', () => {
  const commands = JSON.parse(source('../config/commands.json'));
  for (const [code, models] of Object.entries({
    'sys.cap.data_permission': ['data_permission'],
    'sys.cap.integration': ['webhook', 'api_connector'],
    'sys.cap.scheduled_task': ['scheduled_task'],
  })) {
    const includes = caps.find(item => item.code === code).includes;
    assert.ok(includes.includes(constants.get('COMMAND_EXECUTE')));
    for (const model of models) {
      assert.ok(includes.includes(`model.${model}.read`));
      const modelCommands = commands.filter(command => command.modelCode === model);
      assert.ok(modelCommands.length);
      for (const command of modelCommands) assert.ok(includes.includes(`model.${model}.${command.type}`));
    }
  }
});

test('member state capabilities use the runtime-derived verb, not the command suffix', () => {
  const org = JSON.parse(source('../../org-management/config/capabilities.json'));
  const includes = org.find(cap => cap.code === 'org.cap.member').includes;
  const commands = JSON.parse(source('../config/commands.json'));
  for (const action of ['approve', 'reject', 'suspend', 'restore']) {
    assert.ok(commands.some(command => command.code === `admin:${action}_member` && command.type === 'state_transition'));
    assert.ok(includes.includes(`model.tenant_member.${action}`));
    assert.ok(!includes.includes(`model.tenant_member.${action}_member`));
  }
});

test('member provisioning preserves its selector without broad organization model grants', () => {
  const pages = JSON.parse(source('../config/pages.json'));
  const page = pages.find(item => item.pageKey === 'tenant_member_list');
  const button = page.blocks.find(block => block.id === 'toolbar').buttons
    .find(item => item.action?.command === 'admin:provision_member_from_employee');
  const selector = button.action.inputFields.find(field => field.field === 'employeePid');
  assert.equal(selector.dataSource.endpoint, '/api/org/employees/provision-options?pageNum=1&pageSize=500');
  const service = source('../../../platform/src/main/java/com/auraboot/framework/organization/service/impl/OrganizationServiceImpl.java');
  assert.match(service, /dynamicDataService\.list\(MODEL_EMPLOYEE, request\)/);
  assert.match(service, /dynamicDataService\.getById\(MODEL_DEPARTMENT, deptPid\)/);
  assert.match(service, /dynamicDataService\.getById\(MODEL_POSITION, positionPid\)/);
  const models = [...service.matchAll(/private static final String MODEL_(?:EMPLOYEE|DEPARTMENT|POSITION) = "(\w+)";/g)]
    .map(match => match[1]).sort();
  assert.deepEqual(models, ['org_department', 'org_employee', 'org_position']);
  const org = JSON.parse(source('../../org-management/config/capabilities.json'));
  const management = org.find(cap => cap.code === 'org.cap.member');
  assert.ok(management.includes.includes('model.tenant_member.provision_member_from_employee'));
  const permissions = JSON.parse(source('../config/permissions.json'));
  const provision = permissions.filter(permission =>
    permission.code === 'model.tenant_member.provision_member_from_employee');
  assert.equal(provision.length, 1,
    'cross-model provisioning must explicitly register its member permission');
  assert.equal(provision[0].resourceType, 'model');
  assert.equal(provision[0].resourceCode, 'tenant_member');
  assert.equal(provision[0].action, 'provision_member_from_employee');
  for (const model of models) {
    assert.ok(!management.includes.includes(`model.${model}.read`), `org.cap.member must not acquire unrelated model access: ${model}`);
    assert.ok(!management.includes.some(permission => new RegExp(`^model\\.${model}\\.(create|update|delete)$`).test(permission)));
    for (const code of ['org.cap.member_view', 'org.cap.member_offboarding', 'org.cap.member_remove'])
      assert.ok(!org.find(cap => cap.code === code).includes.includes(`model.${model}.read`), code);
  }
});

test('member commands enforce their action permissions at the command boundary', () => {
  const commands = JSON.parse(source('../config/commands.json'));
  const memberPage = JSON.parse(source('../config/pages.json'))
    .find(page => page.pageKey === 'tenant_member_list');
  const buttons = memberPage.blocks.flatMap(block =>
    [...(block.buttons ?? []), ...(block.rowActions ?? []), ...(block.bulkActions ?? [])]);
  const org = JSON.parse(source('../../org-management/config/capabilities.json'));
  const includes = org.find(cap => cap.code === 'org.cap.member').includes;
  const actions = {
    approve_member: 'approve',
    reject_member: 'reject',
    suspend_member: 'suspend',
    restore_member: 'restore',
    leave_member: 'leave',
    delete_member: 'delete',
    reset_member_password: 'reset_member_password',
    provision_member_from_employee: 'provision_member_from_employee',
  };
  for (const [suffix, action] of Object.entries(actions)) {
    const command = commands.find(item => item.code === `admin:${suffix}`);
    assert.ok(command, `${suffix} must exist`);
    assert.equal(command.modelCode,
      suffix === 'provision_member_from_employee' ? 'org_employee' : 'tenant_member',
      'command row scope must follow the record the handler operates on');
    assert.deepEqual(command.permissions, [`model.tenant_member.${action}`],
      `${command.code} must not rely on endpoint permission or button visibility`);
    const commandButtons = buttons.filter(button => button.action?.command === command.code);
    assert.ok(commandButtons.length, `${command.code} must have a real page entry`);
    for (const button of commandButtons)
      assert.equal(button.permissionCode, `model.tenant_member.${action}`,
        `${button.code} must hide when its command action is not authorized`);
    assert.equal(includes.includes(command.permissions[0]), !['leave', 'delete'].includes(action),
      'member capability must retain its existing action scope');
  }
});

test('organization authoring retains record reads and command endpoint dependencies', () => {
  const org = JSON.parse(source('../../org-management/config/capabilities.json'));
  for (const code of ['org.cap.hr', 'org.cap.team', 'org.cap.member'])
    assert.ok(org.find(cap => cap.code === code).includes.includes('meta.command.execute'), code);
  for (const model of ['org_department', 'org_position', 'org_employee']) {
    assert.ok(org.find(cap => cap.code === 'org.cap.hr_view').includes.includes(`model.${model}.read`));
    for (const action of ['read', 'create', 'update', 'delete'])
      assert.ok(org.find(cap => cap.code === 'org.cap.hr').includes.includes(`model.${model}.${action}`));
  }
  for (const name of ['org_create_department', 'org_update_department', 'org_delete_department', 'org_create_employee', 'org_update_employee', 'org_delete_employee', 'org_create_position', 'org_update_position', 'org_delete_position']) {
    const command = JSON.parse(source(`../../org-management/config/commands/${name}.json`));
    assert.deepEqual(command.permissions, ['org.hr.manage']);
  }
});

test('member offboarding actions have separate capabilities without widening member management', () => {
  const org = JSON.parse(source('../../org-management/config/capabilities.json'));
  const pages = JSON.parse(source('../config/pages.json'));
  const page = pages.find(item => item.pageKey === 'tenant_member_list');
  const commands = JSON.parse(source('../config/commands.json'));
  const management = org.find(cap => cap.code === 'org.cap.member');
  for (const [code, suffix, action] of [
    ['org.cap.member_offboarding', 'leave_member', 'leave'],
    ['org.cap.member_remove', 'delete_member', 'delete'],
  ]) {
    const cap = org.find(item => item.code === code);
    assert.ok(cap, `${suffix} must be configurable through an upper capability`);
    const command = commands.find(item => item.code === `admin:${suffix}`);
    assert.ok(page.blocks.some(block => block.rowActions?.some(row => row.action?.command === command.code)),
      `${suffix} must be a real member-page action`);
    assert.deepEqual(command.permissions, [`model.tenant_member.${action}`]);
    for (const permission of ['model.tenant_member.read', 'org_management', 'member_management', 'meta.command.execute', ...command.permissions])
      assert.ok(cap.includes.includes(permission), `${code} must include ${permission}`);
    assert.ok(!management.includes.includes(command.permissions[0]), 'existing member management must not expand');
    for (const other of ['leave', 'delete'].filter(value => value !== action))
      assert.ok(!cap.includes.includes(`model.tenant_member.${other}`), 'offboarding decisions stay independent');
    assert.ok(!cap.includes.includes('admin_tenant_member'), 'do not introduce the legacy broad bypass');
  }
});


test('scheduled task detail actions follow their model command permissions', () => {
  const pages = JSON.parse(source('../config/pages.json'));
  const page = pages.find(item => item.pageKey === 'scheduled_task_detail');
  assert.equal(page.modelCode, 'scheduled_task');
  const buttons = page.blocks.flatMap(block => block.buttons ?? []);
  const commands = JSON.parse(source('../config/commands.json'));
  const cap = caps.find(item => item.code === 'sys.cap.scheduled_task');
  for (const [buttonCode, action] of [['edit', 'update'], ['delete', 'delete']]) {
    const button = buttons.find(item => item.code === buttonCode);
    const command = commands.find(item => item.code === `admin:${action}_scheduled_task`);
    assert.ok(button && command, `${action} must have an actual detail entry and command`);
    assert.equal(command.modelCode, page.modelCode);
    assert.equal(command.type, action);
    assert.equal(button.permissionCode, `model.${command.modelCode}.${command.type}`);
    assert.ok(cap.includes.includes(button.permissionCode));
    if (action === 'delete') assert.equal(button.action.command, command.code);
    else assert.equal(button.action.to, 'scheduled_task_form');
  }
});


test('decision, policy and report duties preserve distinct controller authorities', () => {
  const cases = [
    ['decision_view', 'decision/controller/DecisionRuntimeController.java', 'DRT_DEFINITION_READ'],
    ['decision_manage', 'decision/controller/DecisionRuntimeController.java', 'DRT_DEFINITION_MANAGE'],
    ['decision_publish', 'decision/controller/DecisionRuntimeController.java', 'DRT_DEFINITION_PUBLISH'],
    ['decision_approve', 'decision/controller/DecisionRuntimeController.java', 'DRT_DEFINITION_APPROVE'],
    ['decision_evaluate', 'decision/controller/DecisionRuntimeController.java', 'DRT_RUNTIME_EVALUATE'],
    ['decision_rollout_manage', 'decision/controller/DecisionRuntimeController.java', 'DRT_ROLLOUT_MANAGE'],
    ['decision_rollout_promote', 'decision/controller/DecisionRuntimeController.java', 'DRT_ROLLOUT_PROMOTE'],
    ['decision_rollout_rollback', 'decision/controller/DecisionRuntimeController.java', 'DRT_ROLLOUT_ROLLBACK'],
    ['policy_view', 'eventpolicy/controller/EventPolicyController.java', 'POLICY_DEFINITION_READ'],
    ['policy_manage', 'eventpolicy/controller/EventPolicyController.java', 'POLICY_DEFINITION_MANAGE'],
    ['policy_publish', 'eventpolicy/controller/EventPolicyController.java', 'POLICY_DEFINITION_PUBLISH'],
    ['policy_run', 'eventpolicy/controller/EventPolicyController.java', 'POLICY_RUNTIME_RUN'],
    ['report_view', 'bi/controller/ReportDefinitionController.java', 'REPORT_DEFINITION_VIEW'],
    ['report_manage', 'bi/controller/ReportDefinitionController.java', 'REPORT_DEFINITION_MANAGE'],
  ];
  const duties = cases.map(([, , symbol]) => constants.get(symbol));
  assert.ok(duties.every(Boolean));
  for (const [suffix, file, symbol] of cases) {
    const cap = caps.find(cap => cap.code === `sys.cap.${suffix}`);
    assert.ok(cap, suffix);
    const controller = source(`../../../platform/src/main/java/com/auraboot/framework/${file}`);
    assert.ok(controller.includes(`@RequirePermission(MetaPermission.${symbol})`), symbol);
    assert.ok(cap.includes.includes(constants.get(symbol)), suffix);
    const reads = new Set(['DRT_DEFINITION_READ', 'POLICY_DEFINITION_READ', 'REPORT_DEFINITION_VIEW'].map(symbol => constants.get(symbol)));
    assert.deepEqual(cap.includes.filter(permission => duties.includes(permission) && !reads.has(permission)),
      reads.has(constants.get(symbol)) ? [] : [constants.get(symbol)], suffix);
    assert.ok(!cap.includes.some(permission => permission.startsWith('model.')), suffix);
    if (['decision_publish', 'decision_approve', 'decision_rollout_promote', 'decision_rollout_rollback', 'policy_publish', 'policy_run'].includes(suffix))
      assert.equal(cap.sensitive, true, suffix);
  }
});


test('metadata capabilities bind real menu entries to distinct read and management guards', () => {
  const menus = JSON.parse(source('../../core-meta/config/menus.json'));
  for (const [domain, menuCode, file, readSymbol, manageSymbol] of [
    ['model', 'model_management', 'ModelController', 'MODEL_READ', 'MODEL_MANAGE'],
    ['field', 'field_management', 'FieldController', 'FIELD_READ', 'FIELD_MANAGE'],
    ['dict', 'dict_management', 'DictController', 'DICT_READ', 'DICT_MANAGE'],
    ['named_query', 'named_query_management', 'NamedQueryController', 'QUERY_READ', 'QUERY_MANAGE'],
  ]) {
    const menu = menus.find(menu => menu.code === menuCode);
    assert.equal(menu.permissionCode, menuCode);
    const controller = source(`../../../platform/src/main/java/com/auraboot/framework/meta/controller/config/${file}.java`);
    for (const symbol of [readSymbol, manageSymbol])
      assert.ok(controller.includes(`@RequirePermission(MetaPermission.${symbol})`), symbol);
    for (const duty of ['view', 'manage']) {
      const cap = caps.find(cap => cap.code === `sys.cap.${domain}_${duty}`);
      assert.ok(cap, `${domain} ${duty}`);
      assert.ok(cap.includes.includes(menuCode));
      assert.ok(cap.includes.includes(constants.get(readSymbol)));
      assert.equal(cap.includes.includes(constants.get(manageSymbol)), duty === 'manage');
      if (duty === 'manage') assert.equal(cap.sensitive, true);
    }
  }
});

test('creating a metadata model has the same active management guard as other model writes', () => {
  const controller = source('../../../platform/src/main/java/com/auraboot/framework/meta/controller/config/ModelController.java');
  const creation = controller.slice(controller.indexOf('    @PostMapping'), controller.indexOf('public ApiResponse<MetaModelDTO> createModel'));
  assert.match(creation, /^\s*@RequirePermission\(MetaPermission\.MODEL_MANAGE\)\s*$/m);
});


test('query builder requires entry authority and target read before table access', () => {
  const controller = source('../../../platform/src/main/java/com/auraboot/framework/meta/controller/QueryBuilderController.java');
  assert.ok(/@RequirePermission\("query_builder"\)/.test(controller), "query builder entry guard");
  const execution = controller.slice(controller.indexOf('public ApiResponse<List<Map<String, Object>>> execute'),
    controller.indexOf('@GetMapping("/models")'));
  assert.ok(execution.indexOf('requireModelRead(dto.getModelCode())') >= 0);
  assert.ok(execution.indexOf('requireModelRead(dto.getModelCode())') < execution.indexOf('queryBuilderService.verifyTableExists'));
  assert.ok(controller.includes('permissionEvaluator.canAction(memberId, modelCode, "read")'));
  assert.ok(controller.includes('.filter(model -> canReadModel(model.getCode()))'));
  const fields = controller.slice(controller.indexOf('public ApiResponse<List<Map<String, Object>>> getModelFields'),
    controller.indexOf('// ==================== Internal helpers'));
  assert.ok(fields.indexOf('requireModelRead(modelCode)') >= 0);
  assert.ok(fields.indexOf('requireModelRead(modelCode)') < fields.indexOf('requireModel(modelCode)'));
});


test('query builder executes through shared source and field protection instead of the raw query seam', () => {
  const controller = source('../../../platform/src/main/java/com/auraboot/framework/meta/controller/QueryBuilderController.java');
  assert.ok(controller.includes('queryProtection.prepare(dto, fieldToColumn, tableName)'));
  assert.ok(controller.includes('queryProtection.execute(protection, sql, params)'));
  assert.ok(!controller.includes('queryBuilderService.executeRaw('));
  assert.ok(!controller.includes('clauses.add("*")'));
  assert.deepEqual(caps.find(cap => cap.code === 'sys.cap.query_builder').includes, ['query_builder']);
});


test('native tenant member administration is explicit and separate from scoped member operations', () => {
  const cap = caps.find(item => item.code === 'sys.cap.tenant_member_native_manage');
  assert.ok(cap);
  assert.equal(cap.sensitive, true);
  for (const action of ['admin_tenant_member', 'model.tenant_member.read', 'meta.command.execute'])
    assert.ok(cap.includes.includes(action), action);
  assert.ok(!cap.includes.includes('user_role.manage'));
  const controller = source('../../../platform/src/main/java/com/auraboot/framework/tenant/controller/TenantMemberController.java');
  assert.equal((controller.match(/@RequirePermission\(MetaPermission.TENANT_MEMBER_MANAGE\)/g) ?? []).length, 5);
  assert.equal(constants.get('TENANT_MEMBER_MANAGE'), 'admin_tenant_member');
  const org = JSON.parse(source('../../org-management/config/capabilities.json'));
  for (const item of org) assert.ok(!item.includes.includes('admin_tenant_member'), item.code);
});

test('dashboard management includes its actual page entry but not directory scaffolding', () => {
  const cap = caps.find(item => item.code === 'sys.cap.dashboard');
  assert.ok(cap.includes.includes('dashboard_mgmt'));
  assert.ok(!cap.includes.includes('dashboards'));
  const menu = JSON.parse(source('../../core-meta/config/menus.json'));
  assert.equal(menu.find(item => item.code === 'dashboard_mgmt').path, '/p/dashboard_management');
  assert.equal(menu.find(item => item.code === 'dashboards').type, 0);
});

test('model maintenance includes field selection reads without field-library writes', () => {
  const includes = caps.find(cap => cap.code === 'sys.cap.model_manage').includes;
  assert.ok(includes.includes(constants.get('FIELD_READ')));
  assert.ok(!includes.includes(constants.get('FIELD_MANAGE')));
});


test('page configuration capabilities cover native guards and declared model actions separately from model maintenance', () => {
  const view = caps.find(cap => cap.code === 'sys.cap.page_view');
  const manage = caps.find(cap => cap.code === 'sys.cap.page_manage');
  assert.ok(view, 'page configuration read must be selectable above atomic permissions');
  assert.ok(manage, 'page configuration writes must be selectable above atomic permissions');
  const pageController = source('../../../platform/src/main/java/com/auraboot/framework/meta/controller/PageSchemaController.java');
  const templateController = source('../../../platform/src/main/java/com/auraboot/framework/meta/template/controller/TemplateController.java');
  for (const symbol of ['PAGE_SCHEMA_READ', 'PAGE_SCHEMA_MANAGE'])
    assert.ok(pageController.includes(`@RequirePermission(MetaPermission.${symbol})`));
  assert.ok(templateController.includes('@RequirePermission(MetaPermission.PAGE_MANAGE)'));
  for (const permission of [constants.get('PAGE_READ'), constants.get('PAGE_SCHEMA_READ'),
    'pgm.page_schema.read', 'model.page_schema.read']) {
    assert.ok(view.includes.includes(permission), permission);
    assert.ok(manage.includes.includes(permission), permission);
  }
  for (const permission of [constants.get('PAGE_MANAGE'), constants.get('PAGE_SCHEMA_MANAGE'),
    'pgm.page_schema.manage', constants.get('COMMAND_EXECUTE')]) {
    assert.ok(manage.includes.includes(permission), permission);
    assert.ok(!view.includes.includes(permission), permission);
  }
  const commands = JSON.parse(source('../../page-manager/config/commands.json'));
  assert.equal(commands.length, 6);
  for (const command of commands) {
    const action = ['create', 'update', 'delete'].includes(command.type)
      ? command.type : command.code.split(':')[1].replace(/_page_schema$/, '');
    const permission = `model.page_schema.${action}`;
    assert.ok(manage.includes.includes(permission), permission);
    assert.ok(!view.includes.includes(permission), permission);
  }
  assert.equal(manage.sensitive, true);
  for (const capability of [view, manage]) {
    assert.ok(!capability.includes.includes(constants.get('MODEL_MANAGE')));
    assert.ok(!capability.includes.includes('system_management'));
    assert.ok(!capability.includes.includes('permission_management'));
  }
  const model = caps.find(cap => cap.code === 'sys.cap.model_manage');
  assert.ok(!model.includes.includes(constants.get('PAGE_MANAGE')));
  assert.ok(!model.includes.includes(constants.get('PAGE_SCHEMA_MANAGE')));
});


test('implicit platform and page commands declare their exact action permission', () => {
  const platform = JSON.parse(source('../config/commands.json'));
  const pages = JSON.parse(source('../../page-manager/config/commands.json'));
  const platformModels = new Set(['api_connector', 'data_permission', 'scheduled_task', 'webhook']);
  const commands = [...platform.filter(command => platformModels.has(command.modelCode)), ...pages];
  assert.equal(commands.length, 18, 'the audited command denominator must remain complete');
  for (const command of commands) {
    const action = command.code.slice(command.code.indexOf(':') + 1).replace(`_${command.modelCode}`, '');
    const permission = `model.${command.modelCode}.${action}`;
    assert.deepEqual(command.permissions, [permission], `${command.code} must enforce its own action`);
    assert.ok(caps.some(capability => capability.includes.includes(permission)),
      `${permission} must remain configurable through an existing upper capability`);
  }
});

const nativeControllerGuards = file => {
  const text = source(`../../../platform/src/main/java/com/auraboot/framework/${file}`)
    .replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
  const guards = [...text.matchAll(/@RequirePermission\(MetaPermission\.(\w+)\)/g)]
    .map(match => constants.get(match[1]));
  assert.ok(guards.length, `${file} must expose actual guarded actions`);
  assert.ok(guards.every(Boolean), `${file} must resolve all permission constants`);
  return new Set(guards);
};
const assertNativeCapability = (code, required, forbidden = []) => {
  const cap = caps.find(item => item.code === code);
  assert.ok(cap, `Missing upper capability ${code}`);
  for (const permission of required)
    assert.ok(cap.includes.includes(permission), `${code} must include ${permission}`);
  for (const permission of forbidden)
    assert.ok(!cap.includes.includes(permission), `${code} must not include ${permission}`);
  assert.equal(new Set(cap.includes).size, cap.includes.length);
};

test('authoring duties preserve edit, structural administration, review and publish boundaries', () => {
  const guards = nativeControllerGuards('authoring/workspace/AuthoringWorkspaceController.java');
  const read = constants.get('PAGE_DESIGNER_READ');
  const edit = constants.get('PAGE_DESIGNER_MANAGE');
  const admin = constants.get('PAGE_DESIGNER_ADMIN');
  const history = constants.get('PAGE_PUBLISH_READ');
  const review = constants.get('PAGE_PUBLISH_MANAGE');
  const publish = constants.get('PAGE_PUBLISH_ADMIN');
  for (const guard of [read, edit, admin, history, review, publish]) assert.ok(guards.has(guard));
  assertNativeCapability('sys.cap.authoring_edit', [read, edit], [admin, review, publish, 'audit.trail.admin']);
  assertNativeCapability('sys.cap.authoring_structure', [read, edit, admin], [review, publish, 'audit.trail.admin']);
  assertNativeCapability('sys.cap.authoring_review', [read, history, review], [edit, admin, publish, 'audit.trail.admin']);
  assertNativeCapability('sys.cap.authoring_publish', [read, history, publish], [edit, admin, review, 'audit.trail.admin']);
});

test('audit duties cover native field and trail guards without metadata writes', () => {
  const field = nativeControllerGuards('meta/controller/config/FieldChangeAuditController.java');
  const trail = nativeControllerGuards('meta/controller/config/AuditTrailController.java');
  const fieldRead = constants.get('META_FIELD_AUDIT_READ');
  const fieldManage = constants.get('META_FIELD_AUDIT_MANAGE');
  const trailRead = constants.get('META_AUDIT_TRAIL_READ');
  const trailAdmin = constants.get('META_AUDIT_TRAIL_ADMIN');
  assert.ok(field.has(fieldRead) && field.has(fieldManage));
  assert.ok(trail.has(trailRead) && trail.has(trailAdmin));
  const metadataWrites = ['meta.model.update', 'meta.field.update', 'meta.publish.admin'];
  assertNativeCapability('sys.cap.field_audit_view', [fieldRead], [fieldManage, trailAdmin, ...metadataWrites]);
  assertNativeCapability('sys.cap.field_audit_manage', [fieldRead, fieldManage], [trailAdmin, ...metadataWrites]);
  assertNativeCapability('sys.cap.audit_view', [trailRead], [trailAdmin, fieldManage, ...metadataWrites]);
  assertNativeCapability('sys.cap.audit_verify', [trailRead, trailAdmin], [fieldManage, ...metadataWrites]);
});

test('current report duties exclude legacy aliases and preserve source authorization', () => {
  const exports = nativeControllerGuards('bi/controller/ReportExportController.java');
  const schedules = nativeControllerGuards('bi/controller/ReportScheduleController.java');
  const exportCode = constants.get('REPORT_EXPORT_EXECUTE');
  const scheduleCode = constants.get('REPORT_SCHEDULE_MANAGE');
  assert.ok(exports.has(exportCode));
  assert.ok(schedules.has(scheduleCode));
  const templateRead = constants.get('REPORT_READ');
  const templateManage = constants.get('REPORT_MANAGE');
  const generate = constants.get('REPORT_GENERATE');
  const print = constants.get('PRINT_GENERATE');
  for (const code of ['sys.cap.report_template_view', 'sys.cap.report_template_manage', 'sys.cap.report_generate']) {
    assert.ok(!caps.some(cap => cap.code === code), `${code} has no current report consumer`);
  }
  for (const legacy of [templateRead, templateManage, generate]) {
    assert.ok(legacy, 'Legacy constants remain available for migration compatibility');
    assert.ok(!caps.some(cap => cap.includes.includes(legacy)), `${legacy} must not be offered as a current report duty`);
    assert.ok(!exports.has(legacy) && !schedules.has(legacy), 'Current guards use the clean report family');
  }
  assertNativeCapability('sys.cap.record_print', [print], [templateManage, scheduleCode]);
  assertNativeCapability('sys.cap.report_export', ['report.definition.view', exportCode], [templateManage, generate, scheduleCode]);
  assertNativeCapability('sys.cap.report_schedule', ['report.definition.view', scheduleCode], [templateManage, exportCode]);
  const renderer = source('../../../platform/src/main/java/com/auraboot/framework/bi/service/impl/ReportExportServiceImpl.java');
  assert.match(renderer, /requireReadPermission\("model\." \+ modelCode \+ "\.read"\)/);
  for (const code of ['sys.cap.record_print', 'sys.cap.report_export', 'sys.cap.report_schedule']) {
    const cap = caps.find(item => item.code === code);
    assert.ok(!cap.includes.some(permission => permission.startsWith('model.')),
      `${code} must not grant report source records`);
  }
});

test('file lifecycle duties retain ownership and target-record authorization', () => {
  const guards = nativeControllerGuards('file/controller/FileUploadController.java');
  const read = constants.get('SYS_FILE_READ');
  const remove = constants.get('SYS_FILE_DELETE');
  const relation = constants.get('SYS_FILE_RELATION_MANAGE');
  assert.ok(guards.has(read) && guards.has(remove) && guards.has(relation));
  assertNativeCapability('sys.cap.file_delete', [read, remove], [relation, 'sys.file.upload']);
  assertNativeCapability('sys.cap.file_relation', [read, relation], [remove, 'sys.file.upload']);
  const controller = source('../../../platform/src/main/java/com/auraboot/framework/file/controller/FileUploadController.java');
  assert.match(controller, /authorizeRelationTarget\(request\.getEntityType\(\), request\.getEntityId\(\), "update"\)/);
  assert.match(controller, /fileService\.deleteFile\(fileId, userId\)/);
  for (const code of ['sys.cap.file_delete', 'sys.cap.file_relation'])
    assert.ok(!caps.find(item => item.code === code).includes.some(permission => permission.startsWith('model.')));
});

const remainingNativeDutyCodes = [
  "acp.agent.approval",
  "acp.agent_run.admin",
  "acp.learning.review",
  "acp.memory.admin",
  "acp.profile.admin",
  "acp.runtime.manage",
  "ai.knowledge.manage",
  "ai.knowledge.read",
  "ai.knowledge.retrieve",
  "ai.scoring.run",
  "automation.admin",
  "automation.update",
  "billing.catalog.read",
  "billing.invoice.read",
  "billing.license.read",
  "billing.plan.read",
  "billing.quota.read",
  "billing.subscription.read",
  "billing.usage.read",
  "dashboard.announcement.manage",
  "data.edi.read",
  "data.edi.update",
  "data.ot_device.data",
  "data.ot_device.read",
  "data.ot_device.update",
  "data.reconciliation.read",
  "data.reconciliation.update",
  "data.sod.read",
  "data.sod.update",
  "iot.data_point.read",
  "meta.category.update",
  "meta.chatbi.use",
  "meta.command.update",
  "meta.cs.manage",
  "meta.cs.seat",
  "meta.decision.execute",
  "meta.decision.update",
  "meta.event_store.admin",
  "meta.filter.update",
  "meta.invariant.update",
  "meta.manufacturing.aps",
  "meta.manufacturing.oee",
  "meta.qr.manage",
  "meta.semantic.publish",
  "meta.semantic.use",
  "meta.state_graph.update",
  "notification.template.manage",
  "party.party.manage"
];
test('default native manual duties have upper owners without platform or inactive-plugin grants', () => {
  const bootstrap = JSON.parse(source('../../../platform/src/main/resources/tenant-templates/default-bootstrap.json'));
  const registered = new Set(bootstrap.permissions.map(permission => permission.code));
  for (const permission of JSON.parse(source('../config/permissions.json'))) registered.add(permission.code);
  const forbidden = ['org.permission_package.manage', 'platform.application_binding.activate',
    'qr.template.platform.read', 'qr.template.platform.manage', 'tax.einvoice.manage',
    'qr.ai.manage', 'billing.self.read', 'dashboard.manage'];
  for (const permission of remainingNativeDutyCodes) {
    assert.ok(registered.has(permission), `${permission} must already be registered`);
    const owners = caps.filter(cap => cap.includes.includes(permission));
    assert.ok(owners.length, `Missing upper owner for ${permission}`);
    for (const owner of owners) {
      assert.ok(owner['name:zh-CN'] && owner['name:en']);
      assert.ok(new Set(owner.includes).size === owner.includes.length);
      for (const code of owner.includes) assert.ok(registered.has(code), `${owner.code}: ${code}`);
      for (const code of forbidden) assert.ok(!owner.includes.includes(code), `${owner.code} must not grant ${code}`);
    }
  }
});

test('automation, knowledge and semantic duties separate maintenance from execution and publishing', () => {
  const auto = nativeControllerGuards('automation/controller/AutomationController.java');
  for (const code of ['automation.read', 'automation.update', 'automation.admin']) assert.ok(auto.has(code));
  assertNativeCapability('sys.cap.automation_manage', ['automation.read', 'automation.update'], ['automation.admin']);
  assertNativeCapability('sys.cap.automation_operate', ['automation.read', 'automation.admin'], ['automation.update']);
  const kb = nativeControllerGuards('rag/controller/KnowledgeBaseController.java');
  for (const code of ['ai.knowledge.read', 'ai.knowledge.manage', 'ai.knowledge.retrieve']) assert.ok(kb.has(code));
  assertNativeCapability('sys.cap.knowledge_view', ['ai.knowledge.read'], ['ai.knowledge.manage', 'ai.knowledge.retrieve']);
  assertNativeCapability('sys.cap.knowledge_manage', ['ai.knowledge.read', 'ai.knowledge.manage'], ['ai.knowledge.retrieve', 'sys.file.upload']);
  assertNativeCapability('sys.cap.knowledge_retrieve', ['ai.knowledge.read', 'ai.knowledge.retrieve'], ['ai.knowledge.manage']);
  const semantic = nativeControllerGuards('semantic/controller/SemanticController.java');
  assert.ok(semantic.has('meta.semantic.use') && semantic.has('meta.semantic.publish'));
  assertNativeCapability('sys.cap.semantic_query', ['meta.semantic.use'], ['meta.semantic.publish']);
  assertNativeCapability('sys.cap.semantic_publish', ['meta.semantic.use', 'meta.semantic.publish']);
});


test('public saved-view sharing has an independent upper duty without view or dashboard maintenance', () => {
  assertNativeCapability('sys.cap.view_public_share', ['dashboard.saved_view.read', 'dashboard.manage'],
    ['dashboard.saved_view.update', 'dashboard.saved_view.team.update', 'dashboard.read', 'dashboard.update']);
  assert.deepEqual(caps.filter(cap => cap.includes.includes('dashboard.manage')).map(cap => cap.code),
    ['sys.cap.view_public_share']);
  const cap = caps.find(cap => cap.code === 'sys.cap.view_public_share');
  assert.equal(cap.sensitive, true);
  const bootstrap = JSON.parse(source('../../../platform/src/main/resources/tenant-templates/default-bootstrap.json'));
  const registered = new Set(bootstrap.permissions.map(permission => permission.code));
  for (const code of cap.includes) assert.ok(registered.has(code), `${code} must already be registered`);
});


test('master-data governance has registered upper duties matching the existing combined guards', () => {
  const duties = {
    'sys.cap.governance_view': ['governance.governance.read'],
    'sys.cap.governance_submit': ['governance.governance.read', 'governance.governance.submit'],
    'sys.cap.governance_review': ['governance.governance.read', 'governance.governance.review'],
  };
  const controller = source('../../../platform/src/main/java/com/auraboot/framework/governance/controller/GovernanceController.java');
  const guards = [...controller.matchAll(/@RequirePermission\("(governance\.governance\.[a-z]+)"\)/g)]
    .map(match => match[1]);
  assert.equal(guards.length, 16);
  assert.deepEqual([...new Set(guards)].sort(), [
    'governance.governance.read', 'governance.governance.review', 'governance.governance.submit']);
  const permissions = JSON.parse(source('../../core-meta/config/permissions.json'));
  const manifest = JSON.parse(source('../../core-meta/plugin.json'));
  assert.equal(manifest.resourceDirs.permissions, 'config/permissions.json');
  for (const [code, includes] of Object.entries(duties)) {
    assertNativeCapability(code, includes);
    const cap = caps.find(item => item.code === code);
    assert.deepEqual(cap.includes, includes);
    assert.equal(cap.sensitive, true);
    for (const permission of includes) {
      const declarations = permissions.filter(item => item.code === permission);
      assert.equal(declarations.length, 1, `${permission}: exactly one legal registration`);
      assert.equal(declarations[0].resourceCode, 'governance');
      assert.equal(declarations[0].action, permission.split('.').at(-1));
    }
  }
  const review = caps.find(item => item.code === 'sys.cap.governance_review');
  assert.match(review['name:zh-CN'], /策略/);
  assert.match(review.description, /快照/);
  assert.ok(!caps.find(item => item.code === 'sys.cap.governance_submit').includes
    .includes('governance.governance.review'));
});
