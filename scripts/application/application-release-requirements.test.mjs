import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { test } from 'node:test';
import { extractApplicationReleaseRequirements } from './application-release-requirements.mjs';
import { verifyRegisteredDeploymentCapabilities } from './deployment-capability-verifier.mjs';
const hash = bytes => `sha256:${createHash('sha256').update(bytes).digest('hex')}`;
const digest = `sha256:${'a'.repeat(64)}`;
const id = '01ARZ3NDEKTSV4RRFFQ69G5FAV';
const handler = { kind: 'handler', key: 'edu.enroll', contract: 'v1' };
const web = { kind: 'web', key: 'edu.enroll', contract: 'v2' };
function fixture() {
  return { schemaVersion: 1, releaseId: id, releaseSequence: 1, application: 'aura-edu', compatibilityEpoch: 1,
    sourceLockIdentity: digest, platformCompatibility: { runtime: 'v1', pluginApi: 'v1', dslSchema: 1 },
    components: [handler, web].map((requirement, index) => ({ key: `component-${index}`, type: 'definition', version: '1.0.0', digest,
      compatibilityContract: { schemaVersion: 1, requiredCapabilities: [requirement] } })) };
}
function extract(value) { const bytes = Buffer.from(JSON.stringify(value)); return extractApplicationReleaseRequirements(bytes, hash(bytes)); }
test('extracts every component requirement with exact registered release identity', () => {
  const value = fixture(); const result = extract(value);
  assert.deepEqual(result.requiredCapabilities, [handler, web]);
  assert.equal(result.applicationRelease.digest, hash(Buffer.from(JSON.stringify(value))));
  assert.equal(result.applicationRelease.releaseId, id);
  assert.deepEqual(result.platformContracts, value.platformCompatibility);
});
test('does not interpret missing or unknown declarations as no requirements', () => {
  for (const contract of [undefined, {}, { schemaVersion: 2, requiredCapabilities: [] }, { schemaVersion: 1, requiredCapabilities: [], typo: true }]) {
    const value = fixture(); value.components[1].compatibilityContract = contract;
    assert.throws(() => extract(value), /Explicit versioned/);
  }
  const value = fixture(); value.components.forEach(c => { c.compatibilityContract.requiredCapabilities = []; });
  assert.deepEqual(extract(value).requiredCapabilities, []);
});
test('preserves cross-component union and rejects local duplication or malformed entries', () => {
  const value = fixture(); value.components[1].compatibilityContract.requiredCapabilities.push(handler);
  assert.deepEqual(extract(value).requiredCapabilities, [handler, web]);
  value.components.reverse(); assert.deepEqual(extract(value).requiredCapabilities, [handler, web]);
  value.components[0].compatibilityContract.requiredCapabilities.push(handler);
  assert.throws(() => extract(value), /Duplicate requirement/);
  for (const bad of [null, { ...handler, extra: true }, { ...handler, kind: 'typo' }]) {
    const value = fixture(); value.components[0].compatibilityContract.requiredCapabilities = [bad]; assert.throws(() => extract(value), /Invalid component/);
  }
});
test('rejects mutable components, invalid identities and incomplete registered releases', () => {
  for (const mutate of [v => { v.components = []; }, v => { v.components.push(v.components[0]); }, v => { v.components[0].version = 'latest'; },
    v => { v.releaseId = 'wrong'; }, v => { v.releaseSequence = 0; }, v => { v.platformCompatibility.extra = true; }, v => { delete v.platformCompatibility.pluginApi; }]) {
    const value = fixture(); mutate(value); assert.throws(() => extract(value));
  }
});
test('requires externally pinned raw bytes before extracting any requirement', () => {
  const bytes = Buffer.from(JSON.stringify(fixture()));
  assert.throws(() => extractApplicationReleaseRequirements(bytes, digest), /pinned digest/);
  assert.throws(() => extractApplicationReleaseRequirements(Buffer.concat([bytes, Buffer.from('\n')]), hash(bytes)), /pinned digest/);
});
test('feeds the capability verifier and missing component requirement blocks compatibility', () => {
  const manifest = Buffer.from(JSON.stringify(fixture()));
  const requirements = extractApplicationReleaseRequirements(manifest, hash(manifest));
  const deployment = { schemaVersion: 1, deploymentId: id, generation: 1, platformRelease: { releaseId: id, digest },
    platformContracts: { runtime: ['v1'], pluginApi: ['v1'], dslSchema: [1] }, supportedApplications: [{ application: 'aura-edu', compatibilityEpochs: [1] }],
    capabilities: [handler, web].map(item => ({ ...item, providerDigests: [digest] })) };
  function verify() {
    const req = Buffer.from(`${JSON.stringify(requirements, null, 2)}\n`), dep = Buffer.from(JSON.stringify(deployment));
    return verifyRegisteredDeploymentCapabilities(manifest, dep, { requirementsDigest: hash(req), deploymentDigest: hash(dep), applicationReleaseDigest: requirements.applicationRelease.digest,
      platformReleaseDigest: digest, deploymentId: id, generation: 1 });
  }
  assert.equal(verify().capabilitiesSatisfied, true);
  deployment.capabilities.pop();
  assert.deepEqual(verify().findings, [{ code: 'required-capability-missing', ...web }]);
  requirements.requiredCapabilities.pop();
  assert.throws(() => verify(), /Requirements bytes differ/);
});

test('registered CLI emits the complete decision and returns failure for missing capability', async () => {
  const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { spawnSync } = await import('node:child_process');
  const dir = mkdtempSync(join(tmpdir(), 'registered-requirements-'));
  try {
    const manifest = Buffer.from(JSON.stringify(fixture()));
    writeFileSync(join(dir, 'manifest.json'), manifest);
    const compiler = spawnSync(process.execPath, ['scripts/application/application-release-requirements.mjs', join(dir, 'manifest.json'), hash(manifest)], { encoding: 'utf8' });
    assert.equal(compiler.status, 0, compiler.stderr);
    assert.deepEqual(JSON.parse(compiler.stdout).requiredCapabilities, [handler, web]);
    const deployment = { schemaVersion: 1, deploymentId: id, generation: 1, platformRelease: { releaseId: id, digest },
      platformContracts: { runtime: ['v1'], pluginApi: ['v1'], dslSchema: [1] }, supportedApplications: [{ application: 'aura-edu', compatibilityEpochs: [1] }],
      capabilities: [handler, web].map(item => ({ ...item, providerDigests: [digest] })) };
    function run() {
      const bytes = JSON.stringify(deployment);
      writeFileSync(join(dir, 'deployment.json'), bytes);
      writeFileSync(join(dir, 'expected.json'), JSON.stringify({ requirementsDigest: hash(compiler.stdout), deploymentDigest: hash(bytes), applicationReleaseDigest: hash(manifest), platformReleaseDigest: digest, deploymentId: id, generation: 1 }));
      return spawnSync(process.execPath, ['scripts/application/deployment-capability-verifier.mjs', '--registered', join(dir, 'manifest.json'), join(dir, 'deployment.json'), join(dir, 'expected.json')], { encoding: 'utf8' });
    }
    const good = run(); assert.equal(good.status, 0, good.stderr); assert.equal(JSON.parse(good.stdout).capabilitiesSatisfied, true);
    deployment.capabilities.pop();
    const denied = run(); assert.equal(denied.status, 1, denied.stderr); assert.deepEqual(JSON.parse(denied.stdout).findings, [{ code: 'required-capability-missing', ...web }]);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
