import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ContributionRegistry } from '../../web-admin/app/framework/extensions/contribution-registry.ts';

test('observes actual registered contracts and invalidates availability after unload', () => {
  const registry = new ContributionRegistry();
  const contracts = ['view-v1'];
  registry.register('renderer', 'fixture.web', { id: 'fixture.view', component: () => null, supportedContracts: contracts });
  const observed = registry.observeContract('renderer', 'fixture.view', 'view-v1');
  assert.equal(observed.supported, true);
  assert.equal(observed.plugin, 'fixture.web');
  assert.equal(observed.registrationId, 'fixture.view');
  assert.equal('providerDigests' in observed, false);
  contracts.push('view-v2');
  assert.equal(registry.observeContract('renderer', 'fixture.view', 'view-v2').supported, false);
  registry.removeByPlugin('fixture.web');
  const removed = registry.observeContract('renderer', 'fixture.view', 'view-v1');
  assert.equal(removed.supported, false);
  assert.ok(removed.generation > observed.generation);
});
test('gated and legacy registrations do not prove compatibility', () => {
  const registry = new ContributionRegistry(() => false);
  registry.register('renderer', 'fixture.web', { id: 'gated', component: null, featureKey: 'paid', supportedContracts: ['v1'] });
  assert.deepEqual(registry.observeContract('renderer', 'gated', 'v1').findings, ['web-contribution-unavailable']);
  registry.register('renderer', 'fixture.web', { id: 'legacy', component: null });
  assert.deepEqual(registry.observeContract('renderer', 'legacy', 'v1').findings, ['web-contract-unavailable']);
});
test('rejects duplicate or invalid declarations before registration', () => {
  const registry = new ContributionRegistry();
  for (const supportedContracts of [['v1', 'v1'], [''], [null], 'v1']) {
    assert.throws(() => registry.register('renderer', 'fixture.web', { id: 'bad', component: null, supportedContracts }), /supported contract/);
  }
  assert.equal(registry.getRenderer('bad'), undefined);
  assert.throws(() => registry.observeContract('renderer', '', 'v1'), /Exact key/);
});
test('service provider inspection uses the exact dispatch key and keeps decorators distinct', () => {
  const registry = new ContributionRegistry();
  registry.register('service-provider', 'fixture.primary', { id: 'service', token: 'lookup', mode: 'primary', provider: {}, supportedContracts: ['v1'] });
  registry.register('service-provider', 'fixture.decorator', { id: 'trace', token: 'lookup', mode: 'decorator', provider: {}, supportedContracts: ['v2'] });
  assert.equal(registry.observeContract('service-provider', 'lookup:primary', 'v1').plugin, 'fixture.primary');
  assert.equal(registry.observeContract('service-provider', 'lookup:primary', 'v2').supported, false);
  assert.equal(registry.observeContract('service-provider', 'lookup:decorator:trace', 'v2').plugin, 'fixture.decorator');
});

test('service compatibility requires the primary and every decorator', () => {
  const registry = new ContributionRegistry();
  registry.register('service-provider', 'fixture.primary', { id: 'service', token: 'lookup', provider: {}, supportedContracts: ['v1'] });
  registry.register('service-provider', 'fixture.good', { id: 'good', token: 'lookup', mode: 'decorator', provider: {}, priority: 10, supportedContracts: ['v1'] });
  assert.equal(registry.observeServiceContract('lookup', 'v1').supported, true);
  registry.register('service-provider', 'fixture.bad', { id: 'bad', token: 'lookup', mode: 'decorator', provider: {}, supportedContracts: ['v2'] });
  const rejected = registry.observeServiceContract('lookup', 'v1');
  assert.equal(rejected.supported, false);
  assert.deepEqual(rejected.observations.map(item => item.plugin), ['fixture.primary', 'fixture.good', 'fixture.bad']);
  assert.deepEqual(rejected.findings, ['lookup:decorator:bad:web-contract-unavailable']);
  registry.removeByPlugin('fixture.bad');
  assert.equal(registry.observeServiceContract('lookup', 'v1').supported, true);
  registry.removeByPlugin('fixture.primary');
  assert.equal(registry.observeServiceContract('lookup', 'v1').supported, false);
});
test('component aliases inspect the actual selected loader and respect direct precedence', () => {
  const registry = new ContributionRegistry();
  const load = () => { throw new Error('Observation must not load components'); };
  registry.register('component-loader', 'fixture.low', { id: 'low', aliases: ['shared'], load, priority: 1, supportedContracts: ['v1'] });
  registry.register('component-loader', 'fixture.high', { id: 'high', aliases: ['shared'], componentName: 'SharedView', load, priority: 10, supportedContracts: ['v2'] });
  const alias = registry.observeComponentLoaderContract('SHARED', 'v1');
  assert.equal(alias.plugin, 'fixture.high');
  assert.equal(alias.supported, false);
  assert.equal(registry.observeComponentLoaderContract('SharedView', 'v2').supported, true);
  registry.register('component-loader', 'fixture.direct', { id: 'shared', load, priority: 0, supportedContracts: ['v1'] });
  assert.equal(registry.observeComponentLoaderContract('shared', 'v1').plugin, 'fixture.direct');
  assert.equal(registry.observeComponentLoaderContract('shared', 'v1').supported, true);
});
test('registry changes during service observation invalidate the combined result', () => {
  class ChangingRegistry extends ContributionRegistry {
    getServiceDecorators(token) {
      this.removeByPlugin('fixture.primary');
      return super.getServiceDecorators(token);
    }
  }
  const registry = new ChangingRegistry();
  registry.register('service-provider', 'fixture.primary', { id: 'service', token: 'lookup', provider: {}, supportedContracts: ['v1'] });
  const observation = registry.observeServiceContract('lookup', 'v1');
  assert.equal(observation.supported, false);
  assert.ok(observation.findings.includes('web-registry-changed'));
});

 test('batch observes exact keys, selected aliases and full service chains without claiming provenance', () => {
  const registry = new ContributionRegistry();
  registry.register('renderer', 'fixture.view', { id: 'view', component: null, supportedContracts: ['v1'] });
  registry.register('component-loader', 'fixture.loader', { id: 'loader', aliases: ['alias'], load: () => { throw Error('must not load'); }, supportedContracts: ['v1'] });
  registry.register('service-provider', 'fixture.primary', { id: 'service', token: 'lookup', provider: {}, supportedContracts: ['v1'] });
  const requirements = [{ mode: 'exact', kind: 'renderer', key: 'view', contract: 'v1' },
    { mode: 'component-loader', key: 'alias', contract: 'v1' }, { mode: 'service', key: 'lookup', contract: 'v1' }];
  const good = registry.observeContracts(requirements);
  assert.equal(good.supported, true); assert.equal(good.stable, true); assert.equal(good.observations.length, 3);
  assert.deepEqual(good.findings, []); assert.equal(good.observations[1].observation.registrationId, 'loader');
  assert.equal('providerDigests' in good, false);
  registry.register('service-provider', 'fixture.decorator', { id: 'bad', token: 'lookup', mode: 'decorator', provider: {}, supportedContracts: ['v2'] });
  const denied = registry.observeContracts(requirements);
  assert.equal(denied.supported, false); assert.equal(denied.stable, true);
  assert.equal(denied.findings[0].code, 'lookup:decorator:bad:web-contract-unavailable');
  for (const invalid of [[], [requirements[0], requirements[0]], [{ mode: 'unknown', key: 'view', contract: 'v1' }], [{ mode: 'exact', kind: 'unknown', key: 'view', contract: 'v1' }]]) {
    assert.throws(() => registry.observeContracts(invalid));
  }
});
 test('batch rejects mixed generations even when each individual observation succeeds', () => {
  class ChangingRegistry extends ContributionRegistry {
    observeContract(kind, key, contract) {
      const result = super.observeContract(kind, key, contract);
      if (key === 'first') this.removeByPlugin('fixture.first');
      return result;
    }
  }
  const registry = new ChangingRegistry();
  registry.register('renderer', 'fixture.first', { id: 'first', component: null, supportedContracts: ['v1'] });
  registry.register('renderer', 'fixture.second', { id: 'second', component: null, supportedContracts: ['v1'] });
  const result = registry.observeContracts(['first', 'second'].map(key => ({ mode: 'exact', kind: 'renderer', key, contract: 'v1' })));
  assert.ok(result.observations.every(item => item.observation.supported));
  assert.equal(result.stable, false); assert.equal(result.supported, false);
});
