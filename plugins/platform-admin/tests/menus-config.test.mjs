import { strict as assert } from 'node:assert';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const __dirname = dirname(fileURLToPath(import.meta.url));
const menus = JSON.parse(readFileSync(join(__dirname, '../config/menus.json'), 'utf8'));
const permissions = JSON.parse(readFileSync(join(__dirname, '../config/permissions.json'), 'utf8'));
const commands = JSON.parse(readFileSync(join(__dirname, '../config/commands.json'), 'utf8'));
const pages = JSON.parse(readFileSync(join(__dirname, '../config/pages.json'), 'utf8'));
const bindingRules = JSON.parse(readFileSync(join(__dirname, '../config/bindingRules.json'), 'utf8'));

test('platform-admin pages keep import-compatible top-level names', () => {
  for (const page of pages) {
    if (page.name !== undefined) {
      assert.equal(
        typeof page.name,
        'string',
        `${page.pageKey} top-level name must be a string for PageSchemaDTO import`,
      );
    }
  }
});

test('platform-admin declares the LLM provider menu permission locally', () => {
  const permissionCodes = new Set(permissions.map((permission) => permission.code));
  const llmProviderMenu = menus.find((menu) => menu.code === 'llm_provider_settings');

  assert.ok(llmProviderMenu, 'llm_provider_settings menu must exist');
  assert.ok(
    permissionCodes.has(llmProviderMenu.permissionCode),
    `missing local permission: ${llmProviderMenu.permissionCode}`,
  );
});

test('platform-admin exposes LLM provider settings under system management', () => {
  const menu = menus.find((item) => item.code === 'llm_provider_settings');

  assert.ok(menu, 'llm_provider_settings menu must exist');
  assert.equal(menu.parentCode, 'system_management');
  assert.equal(menu.path, '/aurabot/providers');
  assert.equal(menu.permissionCode, 'ai_center');
  assert.equal(menu.visible, true);
});

test('platform-admin exposes account security policy under system management', () => {
  const menu = menus.find((item) => item.code === 'account_security_policy');
  const page = pages.find((item) => item.pageKey === 'account_security_policy_detail');
  const dataSource = page?.extension?.dataSource;

  assert.ok(menu, 'account_security_policy menu must exist');
  assert.equal(menu.parentCode, 'system_management');
  assert.equal(menu.path, '/p/c/account_security_policy_detail');
  assert.equal(menu.pageKey, 'account_security_policy_detail');
  assert.equal(menu.permissionCode, 'system_management');
  assert.equal(menu.visible, true);
  assert.ok(page, 'account_security_policy_detail page must exist');
  assert.equal(page.kind, 'detail');
  assert.equal(dataSource?.type, 'api');
  assert.equal(dataSource?.method, 'get');
  assert.equal(dataSource?.endpoint, '/api/admin/account-security-policy');
});

test('platform-admin exposes system preferences as a DSL form page', () => {
  const menu = menus.find((item) => item.code === 'system_preferences');
  const page = pages.find((item) => item.pageKey === 'system_preferences_form');
  const recordSource = page?.recordSource ?? page?.extension?.recordSource;
  const submitEndpoint = page?.extension?.submitEndpoint;
  const preferenceSection = page?.blocks?.find((block) => block.id === 'system_preferences_display');
  const datetimeField = preferenceSection?.fields?.find((field) => field.field === 'datetimeFormat');
  const timezoneField = preferenceSection?.fields?.find((field) => field.field === 'timezone');

  assert.ok(menu, 'system_preferences menu must exist');
  assert.equal(menu.parentCode, 'system_management');
  assert.equal(menu.path, '/p/c/system_preferences_form');
  assert.equal(menu.pageKey, 'system_preferences_form');
  assert.equal(menu.permissionCode, 'system_management');
  assert.equal(menu.visible, true);
  assert.ok(page, 'system_preferences_form page must exist');
  assert.equal(page.kind, 'form');
  assert.equal(recordSource?.mode, 'singleton');
  assert.equal(recordSource?.method, 'get');
  assert.equal(recordSource?.endpoint, '/api/admin/system-preferences');
  assert.equal(page?.extension?.recordSource?.endpoint, '/api/admin/system-preferences');
  assert.equal(submitEndpoint?.method, 'put');
  assert.equal(submitEndpoint?.endpoint, '/api/admin/system-preferences');
  assert.equal(preferenceSection?.extension?.displayVariant, 'settings-card');
  assert.equal(datetimeField?.component, 'SmartInput');
  assert.equal(datetimeField?.props?.['data-testid'], 'system-datetime-format-input');
  assert.equal(timezoneField?.component, 'TimezoneSelect');
  assert.equal(timezoneField?.props?.['data-testid'], 'system-timezone-select');
});

test('platform-admin routes enterprise profile through DSL pages', () => {
  const detailMenu = menus.find((item) => item.code === 'enterprise_info');
  const editMenu = menus.find((item) => item.code === 'enterprise_info_edit');
  const detailPage = pages.find((item) => item.pageKey === 'enterprise_info_detail');
  const formPage = pages.find((item) => item.pageKey === 'enterprise_info_form');
  const editButton = detailPage?.blocks
    ?.find((block) => block.id === 'actions')
    ?.buttons?.find((button) => button.code === 'edit');
  const cancelButton = formPage?.blocks
    ?.find((block) => block.id === 'buttons')
    ?.buttons?.find((button) => button.code === 'cancel');
  const submitButton = formPage?.blocks
    ?.find((block) => block.id === 'buttons')
    ?.buttons?.find((button) => button.code === 'submit');
  const detailSections =
    detailPage?.blocks?.filter((block) => block.blockType === 'form-section') ?? [];
  const formBasicFields =
    formPage?.blocks?.find((block) => block.id === 'basic')?.fields ?? [];
  const tenantCodeField = formBasicFields.find((field) => field.field === 'name');
  const displayNameField = formBasicFields.find((field) => field.field === 'displayName');
  const statusField = formBasicFields.find((field) => field.field === 'status');

  assert.ok(detailMenu, 'enterprise_info menu must exist');
  assert.equal(detailMenu.path, '/p/c/enterprise_info_detail');
  assert.equal(detailMenu.pageKey, 'enterprise_info_detail');
  assert.ok(editMenu, 'enterprise_info_edit menu must exist');
  assert.equal(editMenu.path, '/p/c/enterprise_info_form');
  assert.equal(editMenu.pageKey, 'enterprise_info_form');
  assert.ok(detailPage, 'enterprise_info_detail page must exist');
  assert.equal(detailPage.kind, 'detail');
  assert.equal(detailPage.layout?.type, 'grid');
  assert.equal(editButton?.action?.to, '/p/c/enterprise_info_form');
  assert.equal(detailSections.length, 3);
  assert.deepEqual(
    detailSections.map((block) => block.id),
    ['basic', 'contact', 'description'],
  );
  assert.ok(formPage, 'enterprise_info_form page must exist');
  assert.equal(formPage.extension?.afterSubmitRedirect, '/p/c/enterprise_info_detail');
  assert.equal(formPage.extension?.recordSource?.endpoint, '/api/tenant/info');
  assert.equal(formPage.extension?.recordSource?.mode, 'singleton');
  assert.equal(formPage.extension?.submitEndpoint?.endpoint, '/api/tenant/{pid}');
  assert.equal(tenantCodeField?.readOnly, true);
  assert.equal(tenantCodeField?.props?.placeholder?.['zh-CN'], '系统生成，不可编辑');
  assert.equal(displayNameField?.props?.placeholder?.['zh-CN'], '请输入企业名称');
  assert.equal(statusField?.dataType, 'enum');
  assert.equal(statusField?.props?.placeholder?.['zh-CN'], '请选择状态');
  assert.deepEqual(
    statusField?.props?.options?.map((option) => option.value),
    ['active', 'inactive'],
  );
  assert.equal(submitButton?.action, 'save');
  assert.equal(cancelButton?.action?.to, '/p/c/enterprise_info_detail');
});

test('platform-admin exposes account page provisioning from existing employees', () => {
  const command = commands.find((item) => item.code === 'admin:provision_member_from_employee');
  const resetCommand = commands.find((item) => item.code === 'admin:reset_member_password');
  const page = pages.find((item) => item.pageKey === 'tenant_member_list');
  const toolbar = page?.blocks?.find((block) => block.id === 'toolbar');
  const button = toolbar?.buttons?.find((item) => item.code === 'provision_from_employee');
  const inputField = button?.action?.inputFields?.find((field) => field.field === 'employeePid');
  const handlerRule = bindingRules.find(
    (rule) => rule.commandCode === 'admin:provision_member_from_employee' && rule.ruleType === 'handler',
  );

  assert.ok(command, 'admin:provision_member_from_employee command must exist');
  assert.equal(command.modelCode, 'tenant_member');
  assert.deepEqual(command.permissions, ['admin_tenant_member']);
  assert.deepEqual(resetCommand?.permissions, ['admin_tenant_member']);
  assert.equal(
    command.inputFields,
    undefined,
    'handler-only employeePid must stay on page action inputFields, not command inputFields',
  );
  assert.ok(page, 'tenant_member_list page must exist');
  assert.ok(button, 'tenant_member_list must expose provision_from_employee toolbar action');
  assert.equal(button.action.command, 'admin:provision_member_from_employee');
  assert.equal(button.action.operationType, 'create');
  assert.equal(inputField?.type, 'select');
  assert.equal(inputField?.dataSource?.endpoint, '/api/org/employees?pageNum=1&pageSize=500');
  assert.equal(inputField?.dataSource?.valueField, 'pid');
  assert.equal(inputField?.dataSource?.labelField, 'name');
  assert.ok(handlerRule, 'admin:provision_member_from_employee must have a handler bindingRule');
  assert.equal(handlerRule.handlerClass, 'tenantMemberCommandHandler');
  assert.equal(handlerRule.enabled, true);
});
