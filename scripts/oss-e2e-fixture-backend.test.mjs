import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync, execFileSync } from 'node:child_process';

const root = mkdtempSync(join(tmpdir(), 'aura-e2e-fixture-launcher-'));
const fixture = join(root, 'fixture.jar');
const boot = join(root, 'boot.jar');
const receipt = join(root, 'java-receipt.json');
const bin = join(root, 'bin');
mkdirSync(bin);
mkdirSync(join(root, 'plugins'));
writeFileSync(boot, 'hermetic engine placeholder');
// These are admission fixtures, not application bytecode or a real-stack result.
execFileSync('python3', ['-c', `import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'w') as z:
 for name in ['controller/TestFixtureController','dto/FixtureRequest','dto/FixtureResult']:
  z.writestr('com/auraboot/framework/test/'+name+'.class',b'fixture-placeholder')`, fixture]);
writeFileSync(join(bin, 'java'), `#!/usr/bin/env node\nrequire('node:fs').writeFileSync(process.env.JAVA_RECEIPT,JSON.stringify(process.argv.slice(2)));\n`, { mode: 0o755 });
const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');
const base = {
  ...process.env, PATH: `${bin}:${process.env.PATH}`, JAVA_RECEIPT: receipt,
  AURA_E2E_FIXTURE_ENABLED: 'true', AURA_E2E_BOOT_JAR: boot,
  AURA_E2E_BOOT_SHA256: sha(boot), AURA_E2E_FIXTURE_JAR: fixture,
  AURA_E2E_FIXTURE_SHA256: sha(fixture), AURA_E2E_PLUGINS_DIR: join(root, 'plugins'),
};
const run = (env = {}, args = []) => spawnSync('bash', ['scripts/oss-e2e-fixture-backend.sh', ...args], { env: { ...base, ...env }, encoding: 'utf8' });
after(() => rmSync(root, { recursive: true, force: true }));

test('refuses implicit activation', () => {
  const r = run({ AURA_E2E_FIXTURE_ENABLED: '' });
  assert.equal(r.status, 2); assert.match(r.stderr, /explicit.*true/);
});
test('refuses a fixture digest mismatch', () => {
  const r = run({ AURA_E2E_FIXTURE_SHA256: '0'.repeat(64) });
  assert.equal(r.status, 2); assert.match(r.stderr, /fixture SHA256 mismatch/);
});
test('refuses an engine digest mismatch', () => {
  const r = run({ AURA_E2E_BOOT_SHA256: '0'.repeat(64) });
  assert.equal(r.status, 2); assert.match(r.stderr, /boot SHA256 mismatch/);
});
test('refuses profile or public-interface overrides', () => {
  for (const arg of ['--spring.profiles.active=prod', '--spring.profiles.include=prod', '--spring.profiles.group.test=prod', '--server.address=0.0.0.0']) {
    const r = run({}, [arg]); assert.equal(r.status, 2); assert.match(r.stderr, /owned by this test launcher/);
  }
});
test('rejects an incomplete support archive', () => {
  const incomplete = join(root, 'incomplete.jar');
  execFileSync('python3', ['-c', "import sys,zipfile;z=zipfile.ZipFile(sys.argv[1],'w');z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0');z.close()", incomplete]);
  const r = run({ AURA_E2E_FIXTURE_JAR: incomplete, AURA_E2E_FIXTURE_SHA256: sha(incomplete) });
  assert.equal(r.status, 2); assert.match(r.stderr, /fixture jar lacks/);
});
test('keeps the engine separate and forces test profile with loopback binding', () => {
  const r = run({}, ['--server.port=6519']); assert.equal(r.status, 0, r.stderr);
  const args = JSON.parse(readFileSync(receipt, 'utf8'));
  assert.deepEqual(args, [`-Daura.plugins.dir=${base.AURA_E2E_PLUGINS_DIR}`, `-Dloader.path=${fixture}`, '-cp', boot,
    'org.springframework.boot.loader.launch.PropertiesLauncher', '--server.port=6519',
    '--spring.profiles.active=community,test', '--server.address=127.0.0.1']);
  assert.equal(sha(boot), base.AURA_E2E_BOOT_SHA256);
});
