import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import { verifyRegistrationPayload } from './verify-release-registration-payload.mjs';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'registration-payload-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const release = join(root, 'release'); const image = join(root, 'image');
  mkdirSync(release); mkdirSync(image);
  return { release, image, put: (directory, value) => writeFileSync(join(directory, 'release-registration.json'), value) };
}

test('an undeclared registration may be absent on both sides', (t) => {
  const { release, image } = fixture(t);
  assert.deepEqual(verifyRegistrationPayload(release, image, false), { registration: 'not-declared' });
  assert.throws(() => verifyRegistrationPayload(release, image, true), /missing/);
});

test('one-sided registration is rejected even when not required', (t) => {
  for (const side of ['release', 'image']) {
    const f = fixture(t); f.put(f[side], '{}');
    assert.throws(() => verifyRegistrationPayload(f.release, f.image, false), /missing/);
  }
});

test('exact bytes are required for provided registration', (t) => {
  const f = fixture(t); f.put(f.release, '{"lock":"owned"}'); f.put(f.image, '{"lock":"owned"}');
  for (const required of [false, true]) {
    assert.deepEqual(verifyRegistrationPayload(f.release, f.image, required), { registration: 'identical' });
    f.put(f.image, '{"lock":"mutated"}');
    assert.throws(() => verifyRegistrationPayload(f.release, f.image, required), /differs/);
    f.put(f.image, '{"lock":"owned"}');
  }
});

test('directories cannot replace registration files', (t) => {
  const f = fixture(t); f.put(f.release, '{}'); mkdirSync(join(f.image, 'release-registration.json'));
  assert.throws(() => verifyRegistrationPayload(f.release, f.image, false), /missing/);
});

test('CLI propagates failed parity and rejects invalid declaration', (t) => {
  const f = fixture(t); const script = join(import.meta.dirname, 'verify-release-registration-payload.mjs');
  const run = (required) => spawnSync(process.execPath, [script, f.release, f.image, required], { encoding: 'utf8' });
  assert.equal(run('0').status, 0);
  assert.equal(run('1').status, 1);
  assert.equal(run('invalid').status, 1);
  f.put(f.release, '{}'); f.put(f.image, '{}');
  assert.equal(run('1').status, 0);
  f.put(f.image, '{"changed":true}');
  assert.equal(run('0').status, 1);
});
