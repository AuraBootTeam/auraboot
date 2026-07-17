import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';

const repoRoot = path.resolve(import.meta.dirname, '../..');
const script = path.join(repoRoot, 'scripts/dev/plugin-runtime-import-guard.mjs');

function pluginFixture(pluginType) {
  const root = mkdtempSync(path.join(tmpdir(), `plugin-runtime-guard-${pluginType}-`));
  mkdirSync(path.join(root, 'backend', 'build', 'libs'), { recursive: true });
  writeFileSync(
    path.join(root, 'plugin.json'),
    JSON.stringify(
      {
        pluginId: `com.example.${pluginType}`,
        pluginType,
        backend: { jarPath: 'backend/build/libs/missing-plugin.jar' },
      },
      null,
      2,
    ),
  );
  return root;
}

function runGuard(pluginRoot) {
  return spawnSync(process.execPath, [script, '--plugin', pluginRoot, '--offline-metadata-only', '--json'], {
    cwd: repoRoot,
    encoding: 'utf8',
  });
}

test('config plugins skip missing backend jar in offline metadata checks', () => {
  const result = runGuard(pluginFixture('config'));
  assert.equal(result.status, 0, result.stderr || result.stdout);
  const evidence = JSON.parse(result.stdout);
  assert.equal(evidence.ok, true);
  assert.equal(evidence.pluginType, 'config');
  assert.equal(evidence.localJar, null);
  assert.deepEqual(evidence.failures, []);
  assert.deepEqual(evidence.skippedChecks, ['config plugin backend jar not required']);
});

test('hybrid plugins still fail when backend jar is missing', () => {
  const result = runGuard(pluginFixture('hybrid'));
  assert.equal(result.status, 1);
  const evidence = JSON.parse(result.stdout);
  assert.equal(evidence.ok, false);
  assert.equal(evidence.pluginType, 'hybrid');
  assert.match(evidence.failures.join('\n'), /backend jar not found/);
});

test('existing script stays syntactically valid', () => {
  assert.doesNotThrow(() => {
    execFileSync(process.execPath, ['--check', script], { cwd: repoRoot, stdio: 'pipe' });
  });
});
