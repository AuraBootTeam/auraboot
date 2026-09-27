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
