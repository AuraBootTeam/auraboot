import assert from 'node:assert/strict';
import { chmodSync, existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const common = resolve(repoRoot, 'scripts/db/flyway-common.sh');
const deploy = resolve(repoRoot, 'scripts/db/deploy-migrate.sh');

// Stub boundaries verify shell policy only; real migration results are recorded separately.
function run({ action = 'migrate', edition = 'oss', override = {}, exit = 0, deployArgs } = {}) {
  const root = mkdtempSync(join(tmpdir(), 'flyway-policy-'));
  const bin = join(root, 'bin'); mkdirSync(bin);
  const log = join(root, 'calls'); const password = join(root, 'password'); const psqlLog = join(root, 'psql');
  writeFileSync(join(bin, 'flyway'), '#!/bin/bash\nprintf "%s\\n" "$@" >> "$CALL_LOG"\nprintf "%s" "$FLYWAY_PASSWORD" > "$PASSWORD_LOG"\nexit "$STUB_EXIT"\n');
  writeFileSync(join(bin, 'psql'), '#!/bin/bash\nprintf called > "$PSQL_LOG"\nprintf "20260919050000=-1983915974\\n"\n');
  for (const file of ['flyway', 'psql']) chmodSync(join(bin, file), 0o755);
  try {
    const env = { ...process.env, PATH: `${bin}:/usr/bin:/bin`, PG_DB: 'stub_db', PG_HOST: '127.0.0.1',
      PG_PORT: '5432', PG_USER: 'fixture', PG_PASSWORD: 'synthetic-test-password',
      CALL_LOG: log, PASSWORD_LOG: password, PSQL_LOG: psqlLog, STUB_EXIT: String(exit),
      AURA_FLYWAY_OUT_OF_ORDER: '0', AURA_FLYWAY_PRE1900_CORE_COMPAT: '0',
      AURA_FLYWAY_CORE_MIGRATION_DIR: '', AURA_FLYWAY_EXTRA_LOCATIONS: '', ...override };
    const args = deployArgs ? [deploy, ...deployArgs]
      : ['-c', 'source "$1"; run_flyway "$2" "$3"', 'policy-test', common, action, edition];
    const result = spawnSync('/bin/bash', args, { env, encoding: 'utf8' });
    return { status: result.status, stderr: result.stderr, args: existsSync(log) ? readFileSync(log, 'utf8').trim().split('\n') : [],
      password: existsSync(password) ? readFileSync(password, 'utf8') : null, psqlCalled: existsSync(psqlLog) };
  } finally { rmSync(root, { recursive: true, force: true }); }
}

for (const action of ['migrate', 'validate', 'info']) {
  test(`${action} keeps strict flags and never rewrites history or passes password in argv`, () => {
    const result = run({ action });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.args.at(-1), action);
    assert.ok(result.args.includes('-outOfOrder=false'));
    assert.ok(result.args.includes('-baselineOnMigrate=false'));
    assert.ok(result.args.includes('-cleanDisabled=true'));
    assert.ok(!result.args.some(arg => arg.startsWith('-password=')));
    assert.equal(result.password, 'synthetic-test-password');
    assert.equal(result.psqlCalled, false);
    assert.ok(!result.args.includes('repair'));
  });
}
for (const action of ['repair', 'clean', 'baseline', 'migrate -outOfOrder=true']) {
  test(`rejects ${action} before invoking Flyway`, () => {
    const result = run({ action });
    assert.equal(result.status, 2); assert.deepEqual(result.args, []);
    assert.match(result.stderr, /only migrate, validate and info/);
  });
}
test('Flyway failure is preserved without repair or retry', () => {
  const result = run({ exit: 7 });
  assert.equal(result.status, 7);
  assert.equal(result.args.filter(arg => arg === 'migrate').length, 1);
  assert.equal(result.psqlCalled, false);
});
for (const override of [
  { AURA_FLYWAY_OUT_OF_ORDER: '1' }, { AURA_FLYWAY_PRE1900_CORE_COMPAT: '1' },
  { AURA_FLYWAY_CORE_MIGRATION_DIR: '/tmp/retired-overlay' },
]) {
  test(`rejects retired override ${Object.keys(override)[0]}`, () => {
    const result = run({ override }); assert.equal(result.status, 2);
    assert.deepEqual(result.args, []); assert.equal(result.psqlCalled, false);
    assert.match(result.stderr, /retired/);
  });
}
test('deploy compatibility CLI fails before checking or modifying the database', () => {
  const result = run({ deployArgs: ['--edition', 'enterprise', '--pre1900-core-compat'] });
  assert.equal(result.status, 2); assert.deepEqual(result.args, []); assert.equal(result.psqlCalled, false);
  assert.match(result.stderr, /pre-1900 compatibility is retired/);
});
test('unknown edition cannot silently select OSS migrations', () => {
  const result = run({ edition: 'typo' }); assert.equal(result.status, 2); assert.deepEqual(result.args, []);
});
