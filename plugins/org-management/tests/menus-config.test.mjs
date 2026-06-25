import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it } from 'node:test';

const __dirname = dirname(fileURLToPath(import.meta.url));
const pluginManifest = JSON.parse(readFileSync(join(__dirname, '../plugin.json'), 'utf8'));
const menus = JSON.parse(readFileSync(join(__dirname, '../config/menus.json'), 'utf8'));
const bindingRules = JSON.parse(readFileSync(join(__dirname, '../config/bindingRules.json'), 'utf8'));
const employeeBindings = JSON.parse(readFileSync(join(__dirname, '../config/bindings/org_employee.json'), 'utf8'));
const employeeUserField = JSON.parse(readFileSync(join(__dirname, '../config/fields/org_emp_user_id.json'), 'utf8'));
const employeeCreateCommand = JSON.parse(readFileSync(join(__dirname, '../config/commands/org_create_employee.json'), 'utf8'));
const employeeOpenAccountCommand = JSON.parse(readFileSync(join(__dirname, '../config/commands/org_open_employee_account.json'), 'utf8'));
const employeeFormPage = JSON.parse(readFileSync(join(__dirname, '../config/pages/org_employee_form.json'), 'utf8'));
const employeeListPage = JSON.parse(readFileSync(join(__dirname, '../config/pages/org_employee_list.json'), 'utf8'));

function menuByCode(code) {
  const menu = menus.find((item) => item.code === code);
  assert.ok(menu, `menu ${code} should exist`);
  return menu;
}

describe('org-management RBAC menu configuration', () => {
  it('keeps the minimal permission management entries discoverable and ordered', () => {
    assert.deepEqual(
      [
        'org_departments',
        'org_positions',
        'org_employees',
        'org_teams',
        'member_management',
        'permission_roles',
        'permission_management',
      ].map(
        (code) => {
          const menu = menuByCode(code);
          return {
            code: menu.code,
            parentCode: menu.parentCode,
            name: menu['name:zh-CN'] ?? menu.name,
            path: menu.path,
            permissionCode: menu.permissionCode,
            orderNo: menu.orderNo,
            visible: menu.visible,
          };
        },
      ),
      [
        {
          code: 'org_departments',
          parentCode: 'org_management',
          name: '组织架构',
          path: '/p/org_department',
          permissionCode: 'org.hr.read',
          orderNo: 10,
          visible: true,
        },
        {
          code: 'org_positions',
          parentCode: 'org_management',
          name: '职位',
          path: '/p/org_position',
          permissionCode: 'org.hr.read',
          orderNo: 20,
          visible: true,
        },
        {
          code: 'org_employees',
          parentCode: 'org_management',
          name: '人员',
          path: '/p/org_employee',
          permissionCode: 'org.hr.read',
          orderNo: 30,
          visible: true,
        },
        {
          code: 'org_teams',
          parentCode: 'org_management',
          name: '团队',
          path: '/organization/teams',
          permissionCode: 'org_teams',
          orderNo: 40,
          visible: true,
        },
        {
          code: 'member_management',
          parentCode: 'org_management',
          name: '账号',
          path: '/p/tenant_member',
          permissionCode: 'member_management',
          orderNo: 50,
          visible: true,
        },
        {
          code: 'permission_roles',
          parentCode: 'org_management',
          name: '角色',
          path: '/enterprise/permissions',
          permissionCode: 'permission_management',
          orderNo: 60,
          visible: true,
        },
        {
          code: 'permission_management',
          parentCode: 'org_management',
          name: '权限/授权关系',
          path: '/enterprise/permissions',
          permissionCode: 'permission_management',
          orderNo: 70,
          visible: true,
        },
      ],
    );
  });

  it('allows creating employee records before opening login accounts', () => {
    const userBinding = employeeBindings.find((binding) => binding.fieldCode === 'org_emp_user_id');
    assert.equal(userBinding.required, false);
    assert.equal(employeeUserField.constraints.required, false);
    assert.ok(employeeCreateCommand.inputFields.includes('org_emp_user_id'));
    assert.notEqual(employeeCreateCommand.inputFields[0], 'org_emp_user_id');

    const userField = employeeFormPage.blocks
      .flatMap((block) => block.fields ?? [])
      .find((field) => field.field === 'org_emp_user_id');
    assert.equal(userField.required, false);
  });

  it('wires employee open-account command into the list row actions', () => {
    assert.equal(pluginManifest.resourceDirs.bindingRules, 'config/bindingRules.json');
    assert.equal(employeeOpenAccountCommand.code, 'org:open_employee_account');
    assert.equal(employeeOpenAccountCommand.type, 'custom');
    assert.equal(employeeOpenAccountCommand.modelCode, 'org_employee');

    assert.ok(bindingRules.some((rule) =>
      rule.commandCode === 'org:open_employee_account'
      && rule.ruleType === 'handler'
      && rule.handlerClass === 'orgEmployeeCommandHandler'
      && rule.enabled === true,
    ));

    const actionButtons = employeeListPage.blocks
      .find((block) => block.id === 'block_emp_table')
      .columns.find((column) => column.field === 'actions')
      .buttons;
    const openAccountButton = actionButtons.find((button) => button.code === 'open_account');
    assert.equal(openAccountButton.action.type, 'command');
    assert.equal(openAccountButton.action.command, 'org:open_employee_account');
    assert.equal(openAccountButton.label['zh-CN'], '开通账号');
  });
});
