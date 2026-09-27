import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { verifyDeploymentCapabilities } from './deployment-capability-verifier.mjs';
const hash = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
const digest = char => `sha256:${char.repeat(64)}`;
const id = '01ARZ3NDEKTSV4RRFFQ69G5FAV';
function fixture() {
  const platformContracts = { runtime: 'runtime-v2', pluginApi: 'api-v1', dslSchema: 4 };
  const requiredCapabilities = ['handler', 'web', 'schema'].map(kind => ({ kind, key: `edu.${kind}`, contract: 'v1' }));
  return {
    requirements: { schemaVersion: 1, applicationRelease: { releaseId: id, digest: digest('a'), application: 'aura-edu', compatibilityEpoch: 1 }, platformContracts, requiredCapabilities },
    deployment: { schemaVersion: 1, deploymentId: id, generation: 8, platformRelease: { releaseId: id, digest: digest('b') }, platformContracts: Object.fromEntries(Object.entries(platformContracts).map(([key, value]) => [key, [value]])), supportedApplications: [{ application: 'aura-edu', compatibilityEpochs: [1, 2] }], capabilities: requiredCapabilities.map(item => ({ ...item, providerDigests: [digest('c')] })) },
  };
}
function inputs(value) {
  const requirements = Buffer.from(JSON.stringify(value.requirements));
  const deployment = Buffer.from(JSON.stringify(value.deployment));
  return [requirements, deployment, { requirementsDigest: hash(requirements), deploymentDigest: hash(deployment), applicationReleaseDigest: digest('a'), platformReleaseDigest: digest('b'), deploymentId: id, generation: 8 }];
}
test('resolves exact contracts and records actual provider and platform identities', () => {
  const result = verifyDeploymentCapabilities(...inputs(fixture()));
  assert.equal(result.capabilitiesSatisfied, true);
  assert.equal(result.resolved.length, 3);
  assert.deepEqual(result.resolved[0].providerDigests, [digest('c')]);
  assert.equal(result.platformRelease.digest, digest('b'));
  assert.equal('admitted' in result, false);
});
test('missing Handler, Web or schema capability each blocks the decision', () => {
  for (const kind of ['handler', 'web', 'schema']) {
    const value = fixture(); value.deployment.capabilities = value.deployment.capabilities.filter(item => item.kind !== kind);
    const result = verifyDeploymentCapabilities(...inputs(value));
    assert.equal(result.capabilitiesSatisfied, false);
    assert.deepEqual(result.findings, [{ code: 'required-capability-missing', kind, key: `edu.${kind}`, contract: 'v1' }]);
  }
});
test('epoch and every platform contract dimension are checked independently', () => {
  for (const dimension of ['runtime', 'pluginApi', 'dslSchema']) {
    const value = fixture(); value.deployment.platformContracts[dimension] = dimension === 'dslSchema' ? [5] : ['other'];
    assert.equal(verifyDeploymentCapabilities(...inputs(value)).findings[0].dimension, dimension);
  }
  const value = fixture(); value.deployment.supportedApplications[0].compatibilityEpochs = [2];
  assert.equal(verifyDeploymentCapabilities(...inputs(value)).findings[0].code, 'application-epoch-unsupported');
});
test('pins bytes, platform, application, generation and deployment identity', () => {
  for (const key of ['requirementsDigest', 'deploymentDigest', 'applicationReleaseDigest', 'platformReleaseDigest', 'generation', 'deploymentId']) {
    const args = inputs(fixture()); args[2][key] = key === 'generation' ? 7 : key === 'deploymentId' ? '01ARZ3NDEKTSV4RRFFQ69G5FAW' : digest('d');
    assert.throws(() => verifyDeploymentCapabilities(...args));
  }
  const args = inputs(fixture()); args[1] = Buffer.concat([args[1], Buffer.from('\n')]);
  assert.throws(() => verifyDeploymentCapabilities(...args), /bytes differ/);
});
test('unknown fields and duplicate capabilities or application owners are rejected', () => {
  for (const mutate of [v => { v.deployment.extra = true; }, v => { v.requirements.requiredCapabilities.push(v.requirements.requiredCapabilities[0]); }, v => { v.deployment.capabilities.push(v.deployment.capabilities[0]); }, v => { v.deployment.supportedApplications.push(v.deployment.supportedApplications[0]); }]) {
    const value = fixture(); mutate(value); assert.throws(() => verifyDeploymentCapabilities(...inputs(value)));
  }
});
test('a matching key with a different contract does not satisfy the requirement', () => {
  const value = fixture(); value.deployment.capabilities[0].contract = 'v2';
  const result = verifyDeploymentCapabilities(...inputs(value));
  assert.equal(result.capabilitiesSatisfied, false);
  assert.equal(result.resolved.length, 2);
});

test('one exact deployed platform may explicitly support multiple application contracts', () => {
  const value = fixture();
  value.deployment.platformContracts.runtime.push('runtime-v3');
  const first = verifyDeploymentCapabilities(...inputs(value));
  value.requirements.platformContracts.runtime = 'runtime-v3';
  const second = verifyDeploymentCapabilities(...inputs(value));
  assert.equal(first.capabilitiesSatisfied, true);
  assert.equal(second.capabilitiesSatisfied, true);
  assert.deepEqual(first.platformRelease, second.platformRelease);
});

test('execution chains retain every provider digest and reject empty provenance', () => {
  const value = fixture(); value.deployment.capabilities[0].providerDigests = [digest('d'), digest('c')];
  assert.deepEqual(verifyDeploymentCapabilities(...inputs(value)).resolved[0].providerDigests, [digest('c'), digest('d')]);
  value.deployment.capabilities[0].providerDigests = [];
  assert.throws(() => verifyDeploymentCapabilities(...inputs(value)), /schema invalid/);
});
