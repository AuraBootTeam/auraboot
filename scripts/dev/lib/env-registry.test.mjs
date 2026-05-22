import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { execFileSync, spawnSync } from 'node:child_process';
import test from 'node:test';

const script = path.resolve('scripts/dev/lib/env-registry.mjs');

function run(args, options = {}) {
  return execFileSync('node', [script, ...args], {
    encoding: 'utf8',
    ...options,
  });
}

function createRoot() {
  return mkdtempSync(path.join(tmpdir(), 'auraboot-env-registry-'));
}

test('upsert writes global registry, manifest, exports, and private auth root', () => {
  const registryRoot = createRoot();
  try {
    run([
      'upsert',
      '--registry-root',
      registryRoot,
      '--slug',
      'scriptcheck',
      '--mode',
      'bugfix',
      '--product',
      'enterprise',
      '--core-root',
      '/tmp/core',
      '--enterprise-root',
      '/tmp/enterprise',
      '--core-branch',
      'bugfix/core',
      '--enterprise-branch',
      'bugfix/enterprise',
      '--compose-project',
      'auraboot-scriptcheck',
      '--stack-env-file',
      '/tmp/core/.aura-stack/scriptcheck.env',
      '--status',
      'running',
      '--pg-port',
      '15432',
      '--redis-port',
      '16379',
      '--be-port',
      '16443',
      '--vite-port',
      '15173',
      '--bff-port',
      '13500',
    ]);

    const manifestPath = path.join(registryRoot, 'envs/scriptcheck/manifest.json');
    const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
    assert.equal(manifest.slug, 'scriptcheck');
    assert.equal(manifest.product, 'enterprise');
    assert.equal(manifest.coreBranch, 'bugfix/core');
    assert.equal(manifest.ports.pg, 15432);
    assert.equal(manifest.authRoot, path.join(registryRoot, 'envs/scriptcheck/auth'));

    const registry = JSON.parse(readFileSync(path.join(registryRoot, 'env-registry.json'), 'utf8'));
    assert.equal(registry.envs.scriptcheck.manifestPath, manifestPath);

    const exportsEnv = readFileSync(path.join(registryRoot, 'envs/scriptcheck/exports.env'), 'utf8');
    assert.match(exportsEnv, /PW_STORAGE_DIR=.*\/envs\/scriptcheck\/auth/);
    assert.match(exportsEnv, /PW_ADMIN_STORAGE_STATE=.*\/envs\/scriptcheck\/auth\/admin\.json/);
    assert.match(exportsEnv, /PW_OPERATOR_STORAGE_STATE=.*\/envs\/scriptcheck\/auth\/operator\.json/);
    assert.match(exportsEnv, /PW_VIEWER_STORAGE_STATE=.*\/envs\/scriptcheck\/auth\/viewer\.json/);
  } finally {
    rmSync(registryRoot, { recursive: true, force: true });
  }
});

test('upsert rejects port conflicts across different slugs', () => {
  const registryRoot = createRoot();
  try {
    run([
      'upsert',
      '--registry-root',
      registryRoot,
      '--slug',
      'one',
      '--mode',
      'bugfix',
      '--product',
      'oss',
      '--core-root',
      '/tmp/one',
      '--core-branch',
      'one',
      '--compose-project',
      'auraboot-one',
      '--status',
      'running',
      '--pg-port',
      '15432',
      '--redis-port',
      '16379',
      '--be-port',
      '16443',
      '--vite-port',
      '15173',
      '--bff-port',
      '13500',
    ]);

    const result = spawnSync(
      'node',
      [
        script,
        'upsert',
        '--registry-root',
        registryRoot,
        '--slug',
        'two',
        '--mode',
        'bugfix',
        '--product',
        'oss',
        '--core-root',
        '/tmp/two',
        '--core-branch',
        'two',
        '--compose-project',
        'auraboot-two',
        '--status',
        'running',
        '--pg-port',
        '15432',
        '--redis-port',
        '26379',
        '--be-port',
        '26443',
        '--vite-port',
        '25173',
        '--bff-port',
        '23500',
      ],
      { encoding: 'utf8' },
    );

    assert.equal(result.status, 3);
    assert.match(result.stderr, /port conflict/);
  } finally {
    rmSync(registryRoot, { recursive: true, force: true });
  }
});

test('inspect and list expose registered environments', () => {
  const registryRoot = createRoot();
  try {
    run([
      'upsert',
      '--registry-root',
      registryRoot,
      '--slug',
      'listed',
      '--mode',
      'bugfix',
      '--product',
      'oss',
      '--core-root',
      '/tmp/listed',
      '--core-branch',
      'listed',
      '--compose-project',
      'auraboot-listed',
      '--status',
      'running',
      '--pg-port',
      '15432',
      '--redis-port',
      '16379',
      '--be-port',
      '16443',
      '--vite-port',
      '15173',
      '--bff-port',
      '13500',
    ]);

    const inspected = JSON.parse(run(['inspect', '--registry-root', registryRoot, '--slug', 'listed']));
    assert.equal(inspected.slug, 'listed');
    assert.equal(inspected.composeProject, 'auraboot-listed');

    const listed = run(['list', '--registry-root', registryRoot]);
    assert.match(listed, /listed/);
    assert.match(listed, /15432/);
  } finally {
    rmSync(registryRoot, { recursive: true, force: true });
  }
});
