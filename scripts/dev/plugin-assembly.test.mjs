import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pluginAssemblyDecision } from './plugin-assembly.mjs';
const discovery = state => ({ schemaVersion: 1, agent: { state } });
const ai = { applicationModules: ['agent'] };
test('ordinary profiles import without depending on discovery', () => {
  for (const state of ['installed', 'not_installed', 'unknown']) assert.equal(pluginAssemblyDecision({}, discovery(state), true), 'import');
});
test('full assembly preserves AI profiles and explicit imports', () => {
  for (const fromProfile of [true, false]) assert.equal(pluginAssemblyDecision(ai, discovery('installed'), fromProfile), 'import');
});
test('base profile omits AI; explicit AI selection is refused', () => {
  assert.equal(pluginAssemblyDecision(ai, discovery('not_installed'), true), 'omit');
  assert.throws(() => pluginAssemblyDecision(ai, discovery('not_installed'), false), /not installed/);
});
test('unknown, unavailable and unsupported discovery cannot select a base profile', () => {
  for (const contract of [{}, discovery('unknown'), { schemaVersion: '1', agent: { state: 'installed' } }, { schemaVersion: 2, agent: { state: 'not_installed' } }]) {
    assert.throws(() => pluginAssemblyDecision(ai, contract, true), /Cannot verify/);
  }
});
test('unknown module requirements fail closed instead of being ignored', () => {
  for (const applicationModules of ['agent', ['future'], [null]]) assert.throws(() => pluginAssemblyDecision({ applicationModules }, discovery('installed'), true), /Unsupported/);
});
