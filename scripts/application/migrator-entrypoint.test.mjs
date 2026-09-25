import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { inventoryPackage } from './schema-ownership-audit.mjs';
import { stagePayload } from './migration-payload.mjs';
import { main, migrationInvocation } from './migrator-entrypoint.mjs';

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'migrator-source-'));
  writeFileSync(join(root, 'V1__owned.sql'), 'CREATE TABLE core_record(id bigint);');
  const contract = { ...inventoryPackage(root, 'core'), dependencies: [], objects: {
    core_record: { owner: 'core', lifecycle: 'static-schema', evidence: 'fixture' },
  } };
  Object.assign(contract.migrations[0].statements[0], { review: 'approved', rationale: 'fixture', objects: [{ name: 'core_record', operation: 'create' }] });
  writeFileSync(join(root, 'review.json'), JSON.stringify(contract));
  const composition = join(root, 'composition.json');
  writeFileSync(composition, JSON.stringify({ schemaVersion: 1, packages: [{ root: '.', contract: 'review.json' }] }));
  const output = join(mkdtempSync(join(tmpdir(), 'migrator-image-')), 'payload');
  const receipt = stagePayload(composition, output);
  return { root: output, digest: receipt.manifestSha256, action: 'migrate', host: 'db.internal', port: '5432',
    database: 'aura_release', user: 'migration_user', password: 'fixture-secret', sslMode: 'verify-full', home: '/tmp/isolated-migrator' };
}

test('valid actions use one fixed history and packaged locations without secret argv', () => {
  const input = fixture();
  for (const action of ['info', 'validate', 'migrate']) {
    const invocation = migrationInvocation({ ...input, action });
    assert.equal(invocation.executable, '/flyway/flyway');
    assert.equal(invocation.args.at(-1), action);
    for (const flag of ['-table=ab_flyway_schema_history', '-baselineOnMigrate=false', '-cleanDisabled=true',
      '-outOfOrder=false', '-validateOnMigrate=true', '-placeholderReplacement=false', '-configFiles=/dev/null']) {
      assert.ok(invocation.args.includes(flag), flag);
    }
    assert.ok(invocation.args.includes(`-locations=filesystem:${input.root}/packages/core/sql`));
    assert.equal(invocation.args.join(' ').includes(input.password), false);
    assert.equal(invocation.env.FLYWAY_PASSWORD, input.password);
    assert.equal(invocation.cwd, input.home);
  }
});

test('repair, baseline, clean, arbitrary options and extra actions are rejected before engine access', () => {
  for (const action of ['repair', 'baseline', 'clean', '-baselineOnMigrate=true', 'migrate repair']) {
    assert.throws(() => main([action], {}), /usage/);
  }
  assert.throws(() => main(['migrate', '-outOfOrder=true'], {}), /usage/);
  assert.throws(() => main([], {}), /usage/);
});

test('ambient Flyway, Java and loader settings never enter the invocation', () => {
  const input = fixture();
  const invocation = migrationInvocation({ ...input, env: { FLYWAY_BASELINE_ON_MIGRATE: 'true', JAVA_ARGS: '-javaagent:bad.jar', LD_PRELOAD: 'bad.so' } });
  assert.deepEqual(Object.keys(invocation.env).sort(), ['FLYWAY_PASSWORD', 'FLYWAY_USER', 'HOME', 'JAVA_HOME', 'LANG', 'PATH']);
});

test('payload drift and connection option injection are rejected', () => {
  const input = fixture();
  assert.throws(() => migrationInvocation({ ...input, digest: '0'.repeat(64) }), /digest mismatch/);
  for (const override of [{ host: 'db?socketFactory=bad' }, { database: 'aura?user=other' },
    { port: '5432 -cleanDisabled=false' }, { port: 65536 }, { user: 'bad\nuser' },
    { password: '' }, { sslMode: 'prefer' }, { home: 'relative' }]) {
    assert.throws(() => migrationInvocation({ ...input, ...override }));
  }
});
