import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

async function json(path) {
  return JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
}

const [models, contactFields, opportunityFields] = await Promise.all([
  json('../config/models.json'),
  json('../config/fields/crm_contact_common.json'),
  json('../config/fields/crm_opportunity_common.json'),
]);

function model(code) {
  return models.find((candidate) => candidate.code === code);
}

function field(fields, code) {
  return fields.find((candidate) => candidate.code === code);
}

test('CRM core models expose the supported insert and update import modes', () => {
  assert.deepEqual(model('crm_account_common')?.extension?.importPolicy, {
    enabled: true,
    modes: ['insert', 'update'],
    updateKeys: ['crm_acc_code'],
  });
  assert.deepEqual(model('crm_contact_common')?.extension?.importPolicy, {
    enabled: true,
    modes: ['insert'],
  });
  assert.deepEqual(model('crm_lead_common')?.extension?.importPolicy, {
    enabled: true,
    modes: ['insert', 'update'],
    updateKeys: ['crm_lead_code'],
  });
  assert.deepEqual(model('crm_opportunity_common')?.extension?.importPolicy, {
    enabled: true,
    modes: ['insert', 'update'],
    updateKeys: ['crm_opp_code'],
  });
});

test('contact and opportunity references declare unique-first import business keys', () => {
  const contactAccount = field(contactFields, 'crm_ct_account_id');
  assert.equal(contactAccount?.refTarget?.valueField, 'pid');
  assert.deepEqual(contactAccount?.refTarget?.importMatchFields, [
    'crm_acc_code',
    'crm_acc_name',
  ]);

  const opportunityAccount = field(opportunityFields, 'crm_opp_account_id');
  assert.equal(opportunityAccount?.refTarget?.valueField, 'pid');
  assert.deepEqual(opportunityAccount?.refTarget?.importMatchFields, [
    'crm_acc_code',
    'crm_acc_name',
  ]);

  const opportunityLead = field(opportunityFields, 'crm_opp_lead_id');
  assert.equal(opportunityLead?.refTarget?.valueField, 'pid');
  assert.deepEqual(opportunityLead?.refTarget?.importMatchFields, [
    'crm_lead_code',
    'crm_lead_company',
  ]);
});
