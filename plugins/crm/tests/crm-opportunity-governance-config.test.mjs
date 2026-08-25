import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';

const read = (path) => JSON.parse(readFileSync(new URL(path, import.meta.url), 'utf8'));
const models = read('../config/models.json');
const commands = read('../config/commands/crm_opportunity_governance.json');
const opportunityCommands = read('../config/commands/crm_opportunity_common.json');
const permissions = read('../config/permissions.json');
const roles = read('../config/roles.json');
const menus = read('../config/menus.json');

test('PAR09 exposes tenant stage and structured close-rule resources', () => {
  assert.ok(models.some(({ code }) => code === 'crm_opportunity_stage_config'));
  assert.ok(models.some(({ code }) => code === 'crm_opportunity_close_rule'));
  assert.equal(commands.length, 10);
  assert.deepEqual(
    new Set(commands.map(({ modelCode }) => modelCode)),
    new Set(['crm_opportunity_stage_config', 'crm_opportunity_close_rule']),
  );
  assert.ok(menus.some(({ pageKey }) => pageKey === 'crm_opportunity_stage_config_list'));
  assert.ok(menus.some(({ pageKey }) => pageKey === 'crm_opportunity_close_rule_list'));
});

test('only CRM administrators receive opportunity configuration authority', () => {
  assert.ok(permissions.some(({ code }) => code === 'crm.opportunity.configure'));
  const admin = roles.find(({ code }) => code === 'crm_admin');
  assert.ok(admin.permissions.includes('crm.opportunity.configure'));
  for (const role of roles.filter(({ code }) => code !== 'crm_admin')) {
    assert.equal(role.permissions.includes('crm.opportunity.configure'), false, role.code);
  }
});

test('close rules are runtime authoritative rather than duplicated preconditions', () => {
  const win = opportunityCommands.find(({ code }) => code === 'crm:win_opportunity');
  const lose = opportunityCommands.find(({ code }) => code === 'crm:lose_opportunity');
  assert.equal(win.handler, 'crm:close_opportunity');
  assert.equal(lose.handler, 'crm:close_opportunity');
  assert.equal('preconditions' in win, false);
  assert.equal('preconditions' in lose, false);
  assert.deepEqual(win.fromStates, ['discovery', 'qualification', 'proposal', 'negotiation']);
});
