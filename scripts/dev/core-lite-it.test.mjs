import assert from 'node:assert/strict';
import { test } from 'node:test';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const SH = resolve(HERE, 'core-lite-it.sh');

test('harness delegates stack start to OSS start-isolated.sh (core-lite, not enterprise full stack)', () => {
  const s = readFileSync(SH, 'utf8');
  assert.match(s, /start-isolated\.sh/);
  assert.doesNotMatch(s, /docker-compose\.mobile-e2e\.yml|reset-and-init\.sh/);
});

test('harness accepts --slug / --plugin / --jars-dir', () => {
  const s = readFileSync(SH, 'utf8');
  for (const flag of ['--slug', '--plugin', '--jars-dir']) {
    // Plain substring check — these flags are literals; avoid building a RegExp from them.
    assert.ok(s.includes(flag), `${flag} parsed`);
  }
});

test('harness reads BE_PORT from .aura-stack/<slug>.env (never hardcodes a port)', () => {
  const s = readFileSync(SH, 'utf8');
  assert.match(s, /\.aura-stack\/.*\.env/);
  assert.match(s, /BE_PORT/);
});

test('harness fails fast when the stack is not healthy (no silent fallback)', () => {
  const s = readFileSync(SH, 'utf8');
  assert.match(s, /actuator\/health/);
  assert.match(s, /exit 1/);
});
