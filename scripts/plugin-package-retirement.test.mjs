import assert from 'node:assert/strict';
import test from 'node:test';
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';

test('all development profiles use receivers instead of retired wrappers', () => {
  const profiles = JSON.parse(readFileSync('scripts/dev/plugin-import-profiles.json', 'utf8'));
  for (const names of Object.values(profiles)) {
    for (const retired of ['platform-admin-ee', 'd7-knowledge-wiki', 'ai-employees']) assert.ok(!names.includes(retired));
  }
  assert.ok(profiles['enterprise-demo'].includes('platform-admin'));
  assert.ok(profiles['enterprise-demo'].includes('core-aurabot'));
});

test('Enterprise root detection accepts canonical repo markers without old plugin directories', () => {
  const source = readFileSync('scripts/env/reset-and-init.sh', 'utf8');
  const resolver = source.match(/resolve_enterprise_root\(\) \{[\s\S]*?\n\}/)?.[0];
  assert.ok(resolver);
  const fixture = mkdtempSync(join(tmpdir(), 'aura-ee-retirement-'));
  try {
    mkdirSync(join(fixture, 'scripts'));
    mkdirSync(join(fixture, 'platform/platform-enterprise-core'), { recursive: true });
    writeFileSync(join(fixture, 'scripts/plugins-registry.json'), '{}');
    writeFileSync(join(fixture, 'platform/platform-enterprise-core/build.gradle'), '');
    const run = () => spawnSync('bash', ['-c', `${resolver}\nresolve_enterprise_root`], {
      encoding: 'utf8', env: { ...process.env, AURA_ENTERPRISE_ROOT: fixture },
    });
    const valid = run(); assert.equal(valid.status, 0, valid.stderr); assert.equal(valid.stdout.trim(), fixture);
    rmSync(join(fixture, 'platform/platform-enterprise-core/build.gradle'));
    assert.notEqual(run().status, 0);
  } finally { rmSync(fixture, { recursive: true }); }
});
