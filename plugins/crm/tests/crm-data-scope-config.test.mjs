import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const configUrl = new URL('../config/', import.meta.url);

async function readJson(name) {
  return JSON.parse(await readFile(new URL(name, configUrl), 'utf8'));
}

test('CRM roles declare an explicit default data scope', async () => {
  const roles = await readJson('roles.json');
  const scopes = Object.fromEntries(roles.map((role) => [role.code, role.defaultDataScopeType]));

  assert.deepEqual(scopes, {
    crm_admin: 'all',
    crm_sales: 'self',
    crm_sales_manager: 'all',
    crm_qdp_release_manager: 'all',
    crm_service: 'all',
    crm_viewer: 'all',
  });
});

test('sales-owned CRM models expose owner fields used by the self scope', async () => {
  const models = await readJson('models.json');
  const byCode = new Map(models.map((model) => [model.code, model]));
  const expectedOwnerFields = {
    crm_account_common: 'crm_acc_owner',
    crm_contact_common: 'crm_ct_owner',
    crm_lead_common: 'crm_lead_assigned_to',
    crm_opportunity_common: 'crm_opp_owner',
    crm_forecast_submission: 'crm_fcst_owner',
    crm_activity_common: 'crm_act_owner',
  };

  for (const [modelCode, ownerField] of Object.entries(expectedOwnerFields)) {
    assert.equal(
      byCode.get(modelCode)?.extension?.dataScope?.ownerField,
      ownerField,
      `${modelCode} must keep a resolvable owner field`,
    );
  }
});

test('sales manager narrows opportunity reads to the current department hierarchy', async () => {
  const roles = await readJson('roles.json');
  const manager = roles.find((role) => role.code === 'crm_sales_manager');

  assert.deepEqual(manager?.dataScopes, [{
    permissionCode: 'model.crm_opportunity_common.read',
    scopeType: 'dept_and_sub',
    mergeStrategy: 'MAX',
  }]);
});

test('sales role can read every core model governed by its self scope', async () => {
  const roles = await readJson('roles.json');
  const sales = roles.find((role) => role.code === 'crm_sales');
  const requiredReadPermissions = [
    'model.crm_account_common.read',
    'model.crm_lead_common.read',
    'model.crm_opportunity_common.read',
    'model.crm_forecast_submission.read',
    'model.crm_activity_common.read',
  ];

  assert.ok(sales, 'crm_sales role must exist');
  for (const permission of requiredReadPermissions) {
    assert.ok(sales.permissions.includes(permission), `crm_sales is missing ${permission}`);
  }
});

test('explicit model permissions retain the resource/action metadata used by data scopes', async () => {
  const permissions = await readJson('permissions.json');
  const modelPermissions = permissions.filter((permission) => permission.code?.startsWith('model.'));

  assert.ok(modelPermissions.length > 0, 'CRM must declare explicit model permissions');
  for (const permission of modelPermissions) {
    const parts = permission.code.split('.');
    const expectedAction = parts.pop();
    parts.shift();

    assert.equal(permission.resourceType, 'model', `${permission.code} must be a model permission`);
    assert.equal(
      permission.resourceCode,
      parts.join('.'),
      `${permission.code} must identify its governed model`,
    );
    assert.equal(
      permission.action,
      expectedAction,
      `${permission.code} must identify its governed action`,
    );
  }
});
