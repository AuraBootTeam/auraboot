import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const source = path => readFileSync(new URL(path, import.meta.url), 'utf8');
const caps = JSON.parse(source('../config/capabilities.json'));
const constants = new Map([...source('../../../platform/src/main/java/com/auraboot/framework/permission/constants/MetaPermission.java')
  .matchAll(/public static final String (\w+) = "([^"]+)";/g)].map(match => [match[1], match[2]]));
const controllers = {
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

test('member provisioning includes the employee selector and display-name read dependencies', () => {
  const pages = JSON.parse(source('../config/pages.json'));
  const page = pages.find(item => item.pageKey === 'tenant_member_list');
  const button = page.blocks.find(block => block.id === 'toolbar').buttons
    .find(item => item.action?.command === 'admin:provision_member_from_employee');
  const selector = button.action.inputFields.find(field => field.field === 'employeePid');
  assert.equal(selector.dataSource.endpoint, '/api/org/employees?pageNum=1&pageSize=500');
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
  for (const model of models) {
    assert.ok(management.includes.includes(`model.${model}.read`), `org.cap.member: model.${model}.read`);
    assert.ok(!management.includes.some(permission => new RegExp(`^model\\.${model}\\.(create|update|delete)$`).test(permission)));
    for (const code of ['org.cap.member_view', 'org.cap.member_offboarding', 'org.cap.member_remove'])
      assert.ok(!org.find(cap => cap.code === code).includes.includes(`model.${model}.read`), code);
  }
});

test('member commands enforce their action permissions at the command boundary', () => {
  const commands = JSON.parse(source('../config/commands.json'));
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
    assert.equal(command.modelCode, 'tenant_member');
    assert.deepEqual(command.permissions, [`model.tenant_member.${action}`],
      `${command.code} must not rely on endpoint permission or button visibility`);
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
