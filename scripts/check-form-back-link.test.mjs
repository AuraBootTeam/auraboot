import test from 'node:test';
import assert from 'node:assert/strict';
import { classifyBackTo, derivedListPageKey, findBrokenBackLinks } from './check-form-back-link.mjs';

const FILE = 'plugins/demo/config/pages.json';
const entry = (page) => ({ file: FILE, page });
const listPage = (pageKey) => entry({ pageKey, kind: 'list' });
const formPage = (pageKey, extension) => entry({ pageKey, kind: 'form', ...(extension ? { extension } : {}) });

test('a CRUD form backed by its own list page is clean', () => {
  const problems = findBrokenBackLinks([listPage('crm_account_list'), formPage('crm_account_form')]);
  assert.deepEqual(problems, []);
});

test('a command-entry form with no derived list page is flagged', () => {
  // The production bug: /p/bom_start_conversion/new linked back to a pageKey nobody created.
  const problems = findBrokenBackLinks([formPage('bom_start_conversion_form')]);
  assert.equal(problems.length, 1);
  assert.equal(problems[0].pageKey, 'bom_start_conversion_form');
  assert.match(problems[0].reason, /bom_start_conversion_list/);
});

test('declaring a reachable pageKey clears the flag', () => {
  const problems = findBrokenBackLinks([
    listPage('bom_conversion_task_pcba_workbench_list'),
    formPage('bom_start_conversion_form', { backTo: 'bom_conversion_task_pcba_workbench_list' }),
  ]);
  assert.deepEqual(problems, []);
});

test('a typo in a declared pageKey fails here instead of in front of a user', () => {
  const problems = findBrokenBackLinks([
    listPage('bom_conversion_task_pcba_workbench_list'),
    formPage('bom_start_conversion_form', { backTo: 'bom_conversion_task_workbench_list' }),
  ]);
  assert.equal(problems.length, 1);
  assert.match(problems[0].reason, /does not exist/);
});

test('a custom-route absolute target must name a real page', () => {
  const broken = findBrokenBackLinks([formPage('enterprise_info_form', { backTo: '/p/c/enterprise_info_detail' })]);
  assert.equal(broken.length, 1);

  const clean = findBrokenBackLinks([
    entry({ pageKey: 'enterprise_info_detail', kind: 'detail' }),
    formPage('enterprise_info_form', { backTo: '/p/c/enterprise_info_detail' }),
  ]);
  assert.deepEqual(clean, []);
});

test('"none" is accepted for a page with no parent', () => {
  const problems = findBrokenBackLinks([formPage('system_preferences_form', { backTo: 'none' })]);
  assert.deepEqual(problems, []);
});

test('targets outside the DSL page set are left alone', () => {
  const problems = findBrokenBackLinks([
    formPage('a_form', { backTo: '/dashboards/view/sales' }),
    formPage('b_form', { backTo: 'dashboard:sales' }),
  ]);
  assert.deepEqual(problems, []);
});

test('the allowlist exempts a named page', () => {
  const problems = findBrokenBackLinks([formPage('legacy_form')], new Set(['legacy_form']));
  assert.deepEqual(problems, []);
});

test('non-form pages are not gated', () => {
  const problems = findBrokenBackLinks([entry({ pageKey: 'anything_detail', kind: 'detail' })]);
  assert.deepEqual(problems, []);
});

test('classifyBackTo maps each dialect to what must exist', () => {
  assert.deepEqual(classifyBackTo('none'), { kind: 'none' });
  assert.deepEqual(classifyBackTo('x_list'), { kind: 'pageKey', pageKey: 'x_list' });
  assert.deepEqual(classifyBackTo('/p/c/x_detail'), { kind: 'pageKey', pageKey: 'x_detail' });
  assert.deepEqual(classifyBackTo('/p/x'), { kind: 'pageKey', pageKey: 'x_list' });
  assert.deepEqual(classifyBackTo('/bpmn-designer'), { kind: 'external' });
  assert.deepEqual(classifyBackTo('automation:123'), { kind: 'external' });
});

test('derivedListPageKey mirrors the router suffix rule', () => {
  assert.equal(derivedListPageKey('crm_account_form'), 'crm_account_list');
  assert.equal(derivedListPageKey('enterprise_info_form'), 'enterprise_info_list');
});
