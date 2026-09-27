import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, copyFileSync, chmodSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, isAbsolute } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { bytes, hash, fixture } from './deployment-observation-fixture.mjs';

const quoteRoot = process.env.AURA_QUOTE_SOURCE_ROOT;
assert.ok(quoteRoot && isAbsolute(quoteRoot), 'Explicit absolute AURA_QUOTE_SOURCE_ROOT required');
const python = process.env.AURA_TEST_PYTHON;
assert.ok(python && isAbsolute(python), 'Explicit absolute AURA_TEST_PYTHON required');
const shellQuote = value => `'${value.replaceAll("'", "'\\''")}'`;

test('target reservation drives signed observation identity through the actual protected executor', () => {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'target-attestation-')));
  try {
    const bundle = join(root, 'releases', 'fixture-release', 'quote-bom-docker');
    for (const dir of ['scripts/python', 'env', 'release', 'observation']) mkdirSync(join(bundle, dir), { recursive: true });
    const source = join(quoteRoot, 'deploy/quote-bom-docker/scripts/python');
    for (const name of ['deployment_reservation.py', 'remote_data_stage.py']) copyFileSync(join(source, name), join(bundle, 'scripts/python', name));
    mkdirSync(join(root, 'shared/config'), { recursive: true });
    const config = join(root, 'shared/config/a.env'); writeFileSync(config, 'DEPLOY_TARGET=a\n'); chmodSync(config, 0o600);
    for (const name of ['env/a.env', 'compose.yaml', 'compose.a.yaml']) writeFileSync(join(bundle, name), name + '\n');
    const sha = path => hash(readFileSync(path)).slice(7);
    const profile = { target: 'a', verdict: 'PASS', managed_config_sha256: sha(config), env_sha256: sha(join(bundle, 'env/a.env')),
      base_compose_sha256: sha(join(bundle, 'compose.yaml')), overlay_sha256: sha(join(bundle, 'compose.a.yaml')) };
    writeFileSync(join(bundle, 'release/environment-profile.env'), Object.entries(profile).map(([key, value]) => `${key}=${value}\n`).join(''));
    const reservation = join(bundle, 'scripts/python/deployment_reservation.py');
    function reserve(action, attempt) {
      return spawnSync(python, [reservation, action, '--root', root, '--target', 'a', '--release-id', 'fixture-release',
        '--attempt-id', attempt, '--identity-sha256', 'a'.repeat(64), '--target-config-sha256', sha(config), '--controller-nonce', 'c'.repeat(64)], { encoding: 'utf8' });
    }
    const acquired = reserve('acquire', 'attempt-1'); assert.equal(acquired.status, 0, acquired.stderr);
    const identity = JSON.parse(acquired.stdout).deployment;
    const f = fixture();
    f.deployment.deploymentId = identity.deploymentId; f.deployment.generation = identity.generation;
    f.policy.keys[0].deploymentIds = [identity.deploymentId];
    // The identity file deliberately contains old values; target context must replace them.
    const now = Math.floor(Date.now() / 1000); f.metadata.issuedAt = now - 1; f.metadata.expiresAt = now + 59;
    const names = ['application', 'platform', 'deployment', 'attestation', 'policy', 'expected'];
    const paths = names.map(name => join(bundle, 'observation', `${name}.json`));
    function publishInputs() { f.inputs().forEach((input, index) => writeFileSync(paths[index], index === 5 ? bytes(input) : input)); }
    publishInputs();
    const verifier = fileURLToPath(new URL('./deployment-observation-attestation.mjs', import.meta.url));
    writeFileSync(join(bundle, 'scripts/preflight.sh'), '#!/bin/bash\nset -eu\nexec ' +
      [process.execPath, verifier, '--target-context', ...paths].map(shellQuote).join(' ') + '\n');
    function execute(attempt) {
      const request = { contractVersion: 2, target: 'a', releaseId: 'fixture-release', attemptId: attempt,
        targetConfigSha256: sha(config), identitySha256: 'a'.repeat(64), controllerNonce: 'c'.repeat(64), operation: 'preflight',
        adminEmail: '', adminPassword: '', importTimeout: 60, policy: {} };
      return spawnSync(python, [join(bundle, 'scripts/python/remote_data_stage.py'), 'execute'], {
        encoding: 'utf8', input: JSON.stringify(request), env: { ...process.env, AURA_DEPLOYMENT_ID: 'forged', AURA_DEPLOYMENT_GENERATION: '999' }, timeout: 15000 });
    }
    const good = execute('attempt-1'); assert.equal(good.status, 0, good.stderr);
    const decision = JSON.parse(good.stdout); assert.equal(decision.capabilitiesSatisfied, true);
    assert.equal(decision.deploymentId, identity.deploymentId); assert.equal(decision.generation, 1); assert.equal('admitted' in decision, false);
    const receiptDir = join(root, 'shared/evidence/release-attempts'); mkdirSync(receiptDir, { recursive: true });
    const receipt = { SCHEMA_VERSION: '2', STATUS: 'complete', TARGET: 'a', RELEASE_ID: 'fixture-release', ATTEMPT_ID: 'attempt-1',
      TARGET_CONFIG_SHA256: sha(config), DEPLOYMENT_IDENTITY_SHA256: 'a'.repeat(64), DEPLOYMENT_CONTROLLER_NONCE: 'c'.repeat(64) };
    writeFileSync(join(receiptDir, 'attempt-1.env'), Object.entries(receipt).map(([key, value]) => `${key}=${value}\n`).join(''));
    const finished = reserve('finish', 'attempt-1'); assert.equal(finished.status, 0, finished.stderr);
    const second = reserve('acquire', 'attempt-2'); assert.equal(second.status, 0, second.stderr);
    assert.deepEqual(JSON.parse(second.stdout).deployment, { ...identity, generation: 2 });
    const replay = execute('attempt-2'); assert.equal(replay.status, 1); assert.match(replay.stderr, /Signed observation identity mismatch/);
    f.deployment.generation = 2; publishInputs();
    const current = execute('attempt-2'); assert.equal(current.status, 0, current.stderr);
    assert.equal(JSON.parse(current.stdout).generation, 2);
    f.policy.keys[0].revoked = true; publishInputs();
    const revoked = execute('attempt-2'); assert.equal(revoked.status, 1); assert.match(revoked.stderr, /not authorized/);
    const wrongOwner = execute('attempt-1'); assert.equal(wrongOwner.status, 2); assert.match(wrongOwner.stderr, /owner mismatch/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
