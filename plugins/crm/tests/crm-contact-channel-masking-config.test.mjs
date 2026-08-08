import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const testDir = path.dirname(fileURLToPath(import.meta.url));
const crmRoot = path.resolve(testDir, '..');
const configRoot = path.join(crmRoot, 'config');

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(crmRoot, relativePath), 'utf8'));
}

function readJsonDirectory(relativePath) {
  const directory = path.join(crmRoot, relativePath);
  return fs
    .readdirSync(directory)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .flatMap((name) => JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8')));
}

const fieldMasks = readJson('config/fieldMasks.json');
const capabilities = readJson('config/capabilities.json');
const permissions = readJson('config/permissions.json');
const roles = readJson('config/roles.json');
const fields = readJsonDirectory('config/fields');
const bindings = readJsonDirectory('config/bindings');
const plugin = readJson('plugin.json');

const UNMASK_PERMISSION = 'crm.account.contact_unmask';
const EXPECTED_MASKS = new Map([
  ['crm_account_common.crm_acc_phone', 'PHONE'],
  ['crm_contact_common.crm_ct_email', 'EMAIL'],
  ['crm_contact_common.crm_ct_phone', 'PHONE'],
  ['crm_contact_common.crm_ct_mobile', 'PHONE'],
  ['crm_lead_common.crm_lead_contact_phone', 'PHONE'],
  ['crm_lead_common.crm_lead_contact_email', 'EMAIL'],
]);

test('CRM contact-channel masks cover account, contact, and lead PII in every read context', () => {
  const actual = new Map(
    fieldMasks.map((mask) => [`${mask.modelCode}.${mask.fieldCode}`, mask]),
  );

  assert.equal(actual.size, fieldMasks.length, 'field-mask declarations should not contain duplicates');
  for (const [fieldKey, maskType] of EXPECTED_MASKS) {
    const mask = actual.get(fieldKey);
    assert.ok(mask, `${fieldKey} should have a field-mask declaration`);
    assert.equal(mask.maskType, maskType, `${fieldKey} should use ${maskType}`);
    assert.equal(mask.enabled, true, `${fieldKey} should be enabled`);
    assert.equal(mask.applyToList, true, `${fieldKey} should be masked in list responses`);
    assert.equal(mask.applyToDetail, true, `${fieldKey} should be masked in detail responses`);
    assert.equal(mask.applyToExport, true, `${fieldKey} should be masked in exports`);
    assert.equal(mask.exemptPermissionCodes, UNMASK_PERMISSION);
  }
});

test('the sensitive capability and permission describe exactly the fields they unmask', () => {
  assert.equal(plugin.resourceDirs.fieldMasks, 'config/fieldMasks.json');
  assert.equal(plugin.resourceDirs.capabilities, 'config/capabilities.json');

  const permission = permissions.find((candidate) => candidate.code === UNMASK_PERMISSION);
  assert.ok(permission, `${UNMASK_PERMISSION} should be importable`);
  assert.equal(permission.resourceType, 'data');

  const capability = capabilities.find(
    (candidate) => candidate.code === 'crm.cap.account_contact_full',
  );
  assert.ok(capability, 'contact-channel unmask capability should exist');
  assert.equal(capability.sensitive, true);
  assert.deepEqual(capability.includes, [UNMASK_PERMISSION]);
  assert.deepEqual([...capability.unmasksFields].sort(), [...EXPECTED_MASKS.keys()].sort());
});

test('every masked field is a real field bound to the declared CRM model', () => {
  const fieldCodes = new Set(fields.map((field) => field.code));
  const bindingKeys = new Set(
    bindings.map((binding) => `${binding.modelCode}.${binding.fieldCode}`),
  );

  for (const fieldKey of EXPECTED_MASKS.keys()) {
    const fieldCode = fieldKey.slice(fieldKey.indexOf('.') + 1);
    assert.ok(fieldCodes.has(fieldCode), `${fieldCode} should exist in fields`);
    assert.ok(bindingKeys.has(fieldKey), `${fieldKey} should be bound to its model`);
  }
});

test('only explicitly trusted CRM roles receive the contact-channel unmask permission', () => {
  const permissionsByRole = new Map(
    roles.map((role) => [role.code, new Set(role.permissions ?? [])]),
  );

  for (const roleCode of ['crm_admin', 'crm_sales_manager', 'crm_sales']) {
    assert.equal(
      permissionsByRole.get(roleCode)?.has(UNMASK_PERMISSION),
      true,
      `${roleCode} should see full contact channels`,
    );
  }
  for (const roleCode of ['crm_service', 'crm_viewer']) {
    assert.equal(
      permissionsByRole.get(roleCode)?.has(UNMASK_PERMISSION),
      false,
      `${roleCode} should receive masked contact channels`,
    );
  }
});
