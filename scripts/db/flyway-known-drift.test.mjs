import assert from 'node:assert/strict';
import { chmodSync, mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const flywayCommon = resolve(repoRoot, 'scripts/db/flyway-common.sh');

/**
 * Runs `run_flyway migrate oss` against stub `flyway`/`psql` binaries and returns the
 * sequence of flyway commands the script executed plus what the psql stub answered.
 */
function runMigrate({ psqlRows, withPsqlStub = true, skipRepairEnv = false,
  coreOverlay = false, outOfOrder = false }) {
  const work = mkdtempSync(join(tmpdir(), 'flyway-known-drift-'));
  const bin = join(work, 'bin');
  mkdirSync(bin);

  const flywayLog = join(work, 'flyway-commands.log');
  const flywayStub = join(bin, 'flyway');
  writeFileSync(flywayStub, '#!/bin/bash\nprintf "%s\\n" "$*" >> "$FLYWAY_LOG"\nexit 0\n');
  chmodSync(flywayStub, 0o755);

  if (withPsqlStub) {
    const psqlStub = join(bin, 'psql');
    writeFileSync(psqlStub, '#!/bin/bash\nprintf "%s\\n" "$STUB_PSQL_ROWS"\nexit 0\n');
    chmodSync(psqlStub, 0o755);
  }

  // flyway-common.sh resolves its own location with dirname at source time.
  symlinkSync('/usr/bin/dirname', join(bin, 'dirname'));

  const env = {
    ...process.env,
    PATH: bin,
    FLYWAY_LOG: flywayLog,
    STUB_PSQL_ROWS: psqlRows ?? '',
    PG_DB: 'stub_db',
    PG_HOST: '127.0.0.1',
    PG_PORT: '5432',
    PG_USER: 'auraboot',
    PG_PASSWORD: 'stub',
  };
  if (skipRepairEnv) {
    env.AURA_FLYWAY_SKIP_KNOWN_DRIFT_REPAIR = '1';
  }
  if (coreOverlay) {
    const overlay = join(work, 'overlay');
    mkdirSync(overlay);
    env.AURA_FLYWAY_CORE_MIGRATION_DIR = overlay;
  }
  if (outOfOrder) {
    env.AURA_FLYWAY_OUT_OF_ORDER = '1';
  }

  // Absolute path: overriding PATH in the child env would otherwise break the
  // lookup of the shell itself.
  const result = spawnSync(
    '/bin/bash',
    ['-c', `source '${flywayCommon}' && run_flyway migrate oss`],
    { env, encoding: 'utf8' },
  );

  const commandLines = readFileSync(flywayLog, 'utf8').trim().length
    ? readFileSync(flywayLog, 'utf8').trim().split('\n')
    : [];
  const commands = commandLines.map((line) => line.split(' ').pop());

  rmSync(work, { recursive: true, force: true });
  return { status: result.status, stderr: result.stderr, commands, commandLines };
}

test('fresh database: no repair, migrate runs directly', () => {
  const { status, commands } = runMigrate({ psqlRows: '' });
  assert.equal(status, 0);
  assert.deepEqual(commands, ['migrate']);
});

test('database records the known pre-change checksum: repair runs before migrate', () => {
  const { status, commands } = runMigrate({
    psqlRows: '20260919050000=-1983915974',
  });
  assert.equal(status, 0);
  assert.deepEqual(commands, ['repair', 'migrate']);
});

test('a subset of known-drift versions is enough to trigger the realignment', () => {
  const { commands } = runMigrate({
    psqlRows: '20260919052000=450260653',
  });
  assert.deepEqual(commands, ['repair', 'migrate']);
});

test('already-current checksums: no repair', () => {
  const { commands } = runMigrate({
    psqlRows: [
      '20260919050000=1720749224',
      '20260919051000=42271767',
      '20260919052000=-134450473',
    ].join('\n'),
  });
  assert.deepEqual(commands, ['migrate']);
});

test('unknown checksum drift is never repaired — migrate must fail validation loudly', () => {
  const { commands } = runMigrate({
    psqlRows: '20260919050000=1234567890',
  });
  assert.deepEqual(commands, ['migrate']);
});

test('AURA_FLYWAY_SKIP_KNOWN_DRIFT_REPAIR=1 disables the realignment', () => {
  const { commands } = runMigrate({
    psqlRows: '20260919050000=-1983915974',
    skipRepairEnv: true,
  });
  assert.deepEqual(commands, ['migrate']);
});

test('missing psql binary: preflight skips instead of blocking migrate', () => {
  const { status, commands } = runMigrate({
    psqlRows: '20260919050000=-1983915974',
    withPsqlStub: false,
  });
  assert.equal(status, 0);
  assert.deepEqual(commands, ['migrate']);
});

test('pre-1900 opt-in selects the staged core and out-of-order without repair', () => {
  const { status, commands, commandLines } = runMigrate({
    psqlRows: '20260919050000=-1983915974',
    skipRepairEnv: true,
    coreOverlay: true,
    outOfOrder: true,
  });
  assert.equal(status, 0);
  assert.deepEqual(commands, ['migrate']);
  assert.match(commandLines[0], /-locations=filesystem:.*\/overlay/);
  assert.match(commandLines[0], /-outOfOrder=true/);
});
