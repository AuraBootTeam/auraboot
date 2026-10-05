import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, mkdirSync, rmSync, realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sourceMappings, inputMappings, resumeArgs, validateResumePlan } from './oss-stack-lifecycle.mjs';

function fixture(run) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'oss-lifecycle-unit-')));
  const core = join(root, 'core'), plugin = join(root, 'plugin');
  mkdirSync(core); mkdirSync(plugin); mkdirSync(join(plugin, 'plugin-aura'));
  try { run({ root, core, plugin }); }
  finally { rmSync(root, { recursive: true, force: true }); }
}

test('a Core-only stack explicitly binds its own source', () => fixture(({ core }) => {
  assert.deepEqual(inputMappings(core, ['--slot', '218']), [{ key: 'core', root: core }]);
}));

test('external plugin roots require an explicit source before allocation', () => fixture(({ core, plugin }) => {
  assert.throws(() => inputMappings(core, ['--extra-plugin-root', plugin]), /explicit source owner/);
  assert.deepEqual(inputMappings(core, ['--extra-plugin-root', join(plugin, 'plugin-aura'),
    '--source', `crm=${plugin}`]), [{ key: 'core', root: core }, { key: 'crm', root: plugin }]);
}));

test('the public launcher rejects an unowned plugin root before allocation or infrastructure writes', () => fixture(({ plugin }) => {
  const launcher = fileURLToPath(new URL('../oss-golden-stack.sh', import.meta.url));
  const result = spawnSync('bash', [launcher, 'up', 'unowned-plugin-unit', '--slot', '239',
    '--extra-plugin-root', plugin], { encoding: 'utf8' });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /explicit plugin source ownership validation failed/);
  assert.doesNotMatch(result.stdout, /allocate runtime|ensure infra|apply schema/);
}));

test('duplicate or overlapping source ownership fails closed', () => fixture(({ root, core, plugin }) => {
  assert.throws(() => sourceMappings(core, [], [`core=${plugin}`]), /duplicate/);
  assert.throws(() => sourceMappings(core, [plugin], [`all=${root}`, `plugin=${plugin}`]), /exactly one/);
}));

test('malformed or missing source values are rejected', () => fixture(({ core }) => {
  assert.throws(() => inputMappings(core, ['--source']), /requires a value/);
  assert.throws(() => inputMappings(core, ['--source', 'bad']), /key=path/);
}));

test('resume preserves the profile and sources but never replays DB initialization', () => {
  assert.deepEqual(resumeArgs(['--slot', '218', '--fresh-db', '--require-new-db',
    '--product-migration-root', '/first', '--product-migration-root=/second',
    '--plugin', 'quality', '--source', 'plugins=/frozen']),
  ['--slot', '218', '--plugin', 'quality', '--source', 'plugins=/frozen']);
});

test('a resume plan cannot select another runtime/root or inject environment fields', () => fixture(({ core, plugin }) => {
  const plan = { schemaVersion: 1, runtime: 'unit-runtime', core, args: ['--slot', '218'],
    environment: { MANAGEMENT_HEALTH_DB_ENABLED: 'false' } };
  assert.equal(validateResumePlan(plan, 'unit-runtime', core), plan);
  assert.throws(() => validateResumePlan(plan, 'other-runtime', core), /wrong resume runtime/);
  assert.throws(() => validateResumePlan(plan, 'unit-runtime', plugin), /wrong resume source/);
  assert.throws(() => validateResumePlan({ ...plan, args: ['--fresh-db'] }, 'unit-runtime', core), /initialization/);
  assert.throws(() => validateResumePlan({ ...plan, environment: { ...plan.environment, SECRET: 'reject' } },
    'unit-runtime', core));
}));
