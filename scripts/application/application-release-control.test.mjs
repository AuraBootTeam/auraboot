import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
  generateOperationId,
  loadOrCreateReleasePlan,
  normalizeBaseUrl,
  publishApplicationRelease,
  upgradeTenantApplicationRelease,
} from './application-release-control.mjs';

const digest = (character) => `sha256:${character.repeat(64)}`;

function registration() {
  return {
    registrationKey: `lock:${'a'.repeat(64)}`,
    content: {
      compatibilityEpoch: 1,
      displayVersion: '1.0.0',
      sourceLockIdentity: digest('a'),
      platformCompatibility: { runtime: '1.0.0' },
      components: [{ key: 'edu-core', type: 'definition', version: '1.0.0', digest: digest('b') }],
    },
  };
}

test('operation IDs are valid ULIDs and base URLs reject embedded credentials', () => {
  assert.match(generateOperationId(1_000, Buffer.alloc(10, 1)), /^[0-9A-HJKMNP-TV-Z]{26}$/);
  assert.equal(normalizeBaseUrl('https://example.test/'), 'https://example.test');
  assert.throws(() => normalizeBaseUrl('https://user:secret@example.test'), /must not contain credentials/);
});

test('release plan is create-once and rejects changed inputs', () => {
  const directory = mkdtempSync(join(tmpdir(), 'application-release-plan-'));
  const receiptPath = join(directory, 'receipt.json');
  const input = {
    receiptPath,
    registrationText: JSON.stringify(registration()),
    applicationCode: 'aura-edu',
    baseUrl: 'https://release.example.test/',
    expectedStableVersion: 3,
    now: 1_000,
    entropy: () => Buffer.alloc(10, 1),
  };
  const first = loadOrCreateReleasePlan(input);
  const second = loadOrCreateReleasePlan({ ...input, now: 2_000, entropy: () => Buffer.alloc(10, 2) });
  assert.deepEqual(second.operations, first.operations);
  assert.throws(() => loadOrCreateReleasePlan({ ...input, expectedStableVersion: 4 }), /expectedStableVersion differs/);
});

test('controlled release performs create, register, publish, and stable with one durable receipt', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'application-release-control-'));
  const registrationPath = join(directory, 'registration.json');
  const receiptPath = join(directory, 'receipt.json');
  writeFileSync(registrationPath, JSON.stringify(registration()));
  const calls = [];
  const responses = [
    { code: '0', data: { id: 9, code: 'aura-edu', name: 'Aura EDU' } },
    { code: '0', data: { releaseId: '01K6ABCDE00000000000000000', sequence: 1, digest: digest('c') } },
    { code: '0', data: { releaseId: '01K6ABCDE00000000000000000', operationId: 'ignored' } },
    { code: '0', data: { channel: 'stable', releaseId: '01K6ABCDE00000000000000000', version: 4 } },
  ];
  const fetchImpl = async (url, options) => {
    calls.push({ url, options, body: JSON.parse(options.body) });
    const payload = responses.shift();
    return { ok: true, status: 200, text: async () => JSON.stringify(payload) };
  };
  const receipt = await publishApplicationRelease({
    applicationCode: 'aura-edu',
    applicationName: 'Aura EDU',
    baseUrl: 'https://release.example.test',
    expectedStableVersion: 3,
    fetchImpl,
    receiptPath,
    registrationPath,
    token: 'secret-token',
  });
  assert.equal(receipt.status, 'completed');
  assert.equal(calls.length, 4);
  assert.equal(calls[3].body.expectedVersion, 3);
  assert.equal(calls[0].options.headers.authorization, 'Bearer secret-token');
  assert.ok(!readFileSync(receiptPath, 'utf8').includes('secret-token'));
  assert.equal(JSON.parse(readFileSync(receiptPath, 'utf8')).stable.version, 4);
});

test('completed receipt makes reruns side-effect free', async () => {
  const directory = mkdtempSync(join(tmpdir(), 'application-release-completed-'));
  const registrationPath = join(directory, 'registration.json');
  const receiptPath = join(directory, 'receipt.json');
  const registrationText = JSON.stringify(registration());
  writeFileSync(registrationPath, registrationText);
  const plan = loadOrCreateReleasePlan({
    receiptPath,
    registrationText,
    applicationCode: 'aura-edu',
    baseUrl: 'https://release.example.test',
  });
  plan.status = 'completed';
  writeFileSync(receiptPath, JSON.stringify(plan));
  const receipt = await publishApplicationRelease({
    applicationCode: 'aura-edu',
    baseUrl: 'https://release.example.test',
    fetchImpl: async () => { throw new Error('fetch must not run'); },
    receiptPath,
    registrationPath,
    token: 'secret-token',
  });
  assert.equal(receipt.status, 'completed');
});

function upgradeInput(directory) {
  return { applicationCode: 'aura-edu', tenantId: 42,
    expectedCurrentReleaseId: '01K60000000000000000000001', expectedVersion: 1,
    targetReleaseId: '01K60000000000000000000002', targetDigest: digest('c'),
    baseUrl: 'http://127.0.0.1:7105', token: 'private-token', receiptPath: join(directory, 'upgrade.json') };
}
function upgradedBinding(input) {
  return { tenantId: input.tenantId, applicationId: 9, releaseId: input.targetReleaseId,
    releaseDigest: input.targetDigest, status: 'active', version: input.expectedVersion + 1 };
}
test('binding upgrade retains one exact operation across a lost response and validates the committed transition', async () => {
  const input = upgradeInput(mkdtempSync(join(tmpdir(), 'binding-upgrade-')));
  let seen;
  await assert.rejects(upgradeTenantApplicationRelease({ ...input, fetchImpl: async (_url, options) => {
    seen = JSON.parse(options.body).operationId; throw new Error('response lost');
  } }), /response lost/);
  const receipt = await upgradeTenantApplicationRelease({ ...input, fetchImpl: async (url, options) => {
    assert.ok(url.endsWith('/aura-edu/tenant-bindings/42/upgrade'));
    assert.equal(JSON.parse(options.body).operationId, seen);
    return { ok: true, status: 200, text: async () => JSON.stringify({ code: '0', data: upgradedBinding(input) }) };
  } });
  assert.equal(receipt.status, 'completed');
  assert.equal(receipt.binding.version, 2);
  assert.ok(!readFileSync(input.receiptPath, 'utf8').includes('private-token'));
  await assert.rejects(upgradeTenantApplicationRelease({ ...input, expectedVersion: 2 }), /input differs/);
  assert.equal((await upgradeTenantApplicationRelease({ ...input,
    fetchImpl: async () => { throw new Error('completed receipt must not issue a new operation'); } })).status, 'completed');
});
test('binding upgrade refuses denied and mismatched responses without completing the receipt', async () => {
  for (const denied of [true, false]) {
    const input = upgradeInput(mkdtempSync(join(tmpdir(), 'binding-upgrade-deny-')));
    await assert.rejects(upgradeTenantApplicationRelease({ ...input, fetchImpl: async () => ({
      ok: !denied, status: denied ? 403 : 200, text: async () => JSON.stringify(denied
        ? { code: '403' } : { code: '0', data: { ...upgradedBinding(input), tenantId: 99 } }),
    }) }), denied ? /rejected/ : /differs/);
    assert.equal(JSON.parse(readFileSync(input.receiptPath)).status, 'planned');
  }
});
test('binding upgrade rejects a forged completed receipt and invalid source identity', async () => {
  const input = upgradeInput(mkdtempSync(join(tmpdir(), 'binding-upgrade-forged-')));
  await assert.rejects(upgradeTenantApplicationRelease({ ...input, expectedVersion: 0 }), /source binding version/);
  await assert.rejects(upgradeTenantApplicationRelease({ ...input, fetchImpl: async () => { throw new Error('offline'); } }), /offline/);
  const receipt = JSON.parse(readFileSync(input.receiptPath));
  receipt.status = 'completed'; receipt.binding = { ...upgradedBinding(input), version: 88 };
  writeFileSync(input.receiptPath, JSON.stringify(receipt));
  await assert.rejects(upgradeTenantApplicationRelease(input), /invalid binding/);
});

test('binding upgrade preserves full 64-bit decimal tenant IDs and refuses rounded JSON numbers', async () => {
  const input = {...upgradeInput(mkdtempSync(join(tmpdir(), 'binding-upgrade-id-'))), tenantId: '369448397205278720'};
  const receipt = await upgradeTenantApplicationRelease({...input, fetchImpl: async url => {
    assert.ok(url.includes('/tenant-bindings/369448397205278720/upgrade'));
    return {ok: true, status: 200, text: async () => JSON.stringify({code: '0', data: upgradedBinding(input)})};
  }});
  assert.equal(receipt.binding.tenantId, '369448397205278720');
  await assert.rejects(upgradeTenantApplicationRelease({...input, tenantId: Number(input.tenantId)}), /unsafe numeric tenant ID/);
});
