import assert from 'node:assert/strict';
import { test } from 'node:test';
import { generateKeyPairSync } from 'node:crypto';
import { verifyAttestedDeploymentCapabilities } from './deployment-observation-attestation.mjs';
import { bytes, hash, id, digest, fixture } from './deployment-observation-fixture.mjs';
test('authorized signature preserves the registered decision without granting admission', () => {
  const result = verifyAttestedDeploymentCapabilities(...fixture().inputs());
  assert.equal(result.capabilitiesSatisfied, true);
  assert.equal(result.observationAttestation.keyId, 'observer-1');
  assert.equal('admitted' in result, false);
});
test('tampering with the payload or signed interval fails even with repinned payload bytes', () => {
  const args = fixture().inputs();
  args[2] = Buffer.concat([args[2], Buffer.from('\n')]); args[5].deploymentDigest = hash(args[2]);
  assert.throws(() => verifyAttestedDeploymentCapabilities(...args), /identity mismatch/);
  const changed = fixture().inputs(); const envelope = JSON.parse(changed[3]); envelope.issuedAt = 101;
  changed[3] = bytes(envelope);
  assert.throws(() => verifyAttestedDeploymentCapabilities(...changed), /signature/);
});
test('revoked, unknown, wrong deployment and wrong platform signer scopes fail closed', () => {
  for (const mutate of [f => { f.policy.keys[0].revoked = true; }, f => { f.policy.keys[0].keyId = 'other'; },
    f => { f.policy.keys[0].deploymentIds = ['01ARZ3NDEKTSV4RRFFQ69G5FAW']; }, f => { f.policy.keys[0].platformReleaseDigests = [digest]; }]) {
    const f = fixture(); mutate(f); assert.throws(() => verifyAttestedDeploymentCapabilities(...f.inputs()), /not authorized/);
  }
});
test('expired, future and overlong observations fail; generation replay fails', () => {
  for (const mutate of [f => { f.expected.now = 160; }, f => { f.expected.now = 99; }, f => { f.metadata.expiresAt = 161; }]) {
    const f = fixture(); mutate(f); assert.throws(() => verifyAttestedDeploymentCapabilities(...f.inputs()), /validity interval/);
  }
  const f = fixture(); f.expected.generation = 2;
  assert.throws(() => verifyAttestedDeploymentCapabilities(...f.inputs()), /identity mismatch/);
});
test('trust policy pins and key identity cannot be supplied by the signed envelope', () => {
  const f = fixture(); const args = f.inputs(); args[4] = Buffer.concat([args[4], Buffer.from('\n')]);
  assert.throws(() => verifyAttestedDeploymentCapabilities(...args), /Trust policy bytes/);
  const wrong = fixture(); wrong.policy.keys[0].publicKey = generateKeyPairSync('ed25519').publicKey.export({ type: 'spki', format: 'pem' });
  assert.throws(() => verifyAttestedDeploymentCapabilities(...wrong.inputs()), /signature/);
  const duplicate = fixture(); duplicate.policy.keys.push(duplicate.policy.keys[0]);
  assert.throws(() => verifyAttestedDeploymentCapabilities(...duplicate.inputs()), /duplicate trust key/);
  const extra = fixture().inputs(); const envelope = JSON.parse(extra[3]); envelope.publicKey = 'untrusted'; extra[3] = bytes(envelope);
  assert.throws(() => verifyAttestedDeploymentCapabilities(...extra), /envelope fields/);
});
test('a valid signature cannot bypass missing capabilities or unregistered providers', () => {
  const absent = fixture(); absent.deployment.capabilities = [];
  const missing = verifyAttestedDeploymentCapabilities(...absent.inputs());
  assert.equal(missing.capabilitiesSatisfied, false); assert.equal(missing.findings[0].code, 'required-capability-missing');
  const unknown = fixture(); unknown.deployment.capabilities[0].providerDigests = [`sha256:${'b'.repeat(64)}`];
  const result = verifyAttestedDeploymentCapabilities(...unknown.inputs());
  assert.equal(result.capabilitiesSatisfied, false); assert.equal(result.findings[0].code, 'provider-artifact-not-registered');
});

test('attested CLI returns the registered decision and fails on a signed missing capability', async () => {
  const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const { spawnSync } = await import('node:child_process');
  const directory = mkdtempSync(join(tmpdir(), 'deployment-attestation-'));
  try {
    const f = fixture();
    const now = Math.floor(Date.now() / 1000);
    f.metadata.issuedAt = now - 5; f.metadata.expiresAt = now + 55;
    function run(context) {
      const paths = f.inputs().map((input, index) => {
        const path = join(directory, `${index}.json`); writeFileSync(path, index === 5 ? bytes(input) : input); return path;
      });
      return spawnSync(process.execPath, ['scripts/application/deployment-observation-attestation.mjs', ...(context === undefined ? [] : ['--target-context']), ...paths],
        { encoding: 'utf8', env: { ...process.env, AURA_DEPLOYMENT_ID: '', AURA_DEPLOYMENT_GENERATION: '', ...context } });
    }
    const valid = run(); assert.equal(valid.status, 0, valid.stderr);
    assert.equal(JSON.parse(valid.stdout).capabilitiesSatisfied, true);
    assert.ok(JSON.parse(valid.stdout).observationAttestation.verifiedAt >= now);
    f.metadata.issuedAt = 100; f.metadata.expiresAt = 160;
    const stale = run(); assert.equal(stale.status, 1);
    assert.match(stale.stderr, /validity interval rejected/);
    f.metadata.issuedAt = now - 5; f.metadata.expiresAt = now + 55;
    const target = { AURA_DEPLOYMENT_ID: id, AURA_DEPLOYMENT_GENERATION: '1' };
    f.expected.generation = 999;
    const current = run(target); assert.equal(current.status, 0, current.stderr);
    assert.equal(JSON.parse(current.stdout).generation, 1);
    const staleTarget = run({ ...target, AURA_DEPLOYMENT_GENERATION: '2' });
    assert.equal(staleTarget.status, 1); assert.match(staleTarget.stderr, /identity mismatch/);
    for (const context of [{}, { ...target, AURA_DEPLOYMENT_GENERATION: '01' }, { ...target, AURA_DEPLOYMENT_GENERATION: '9007199254740992' }]) {
      const missingContext = run(context); assert.equal(missingContext.status, 1);
      assert.match(missingContext.stderr, /Trusted target deployment context required/);
    }
    f.expected.generation = 1;
    f.deployment.capabilities = [];
    const missing = run(); assert.equal(missing.status, 1, missing.stderr);
    assert.equal(JSON.parse(missing.stdout).findings[0].code, 'required-capability-missing');
  } finally { rmSync(directory, { recursive: true, force: true }); }
});
