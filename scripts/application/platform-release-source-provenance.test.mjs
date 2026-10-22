import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const dbScripts = fileURLToPath(new URL('../db/', import.meta.url));

function fixture(t) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'release-provenance-'));
  t.after(() => fs.rmSync(root, { recursive: true, force: true }));
  const core = path.join(root, 'core');
  const enterprise = path.join(root, 'enterprise');
  const bin = path.join(root, 'bin');
  fs.mkdirSync(core, { recursive: true });
  fs.mkdirSync(enterprise);
  fs.mkdirSync(bin);
  fs.cpSync(dbScripts, path.join(core, 'scripts/db'), { recursive: true });
  function executable(name, content) {
    fs.writeFileSync(path.join(bin, name), `#!/usr/bin/env bash\nset -euo pipefail\n${content}\n`, { mode: 0o755 });
  }
  executable('flyway', 'exit 0');
  executable('pg_dump', 'echo "CREATE TABLE fixture(id bigint);"');
  executable('psql', 'if [[ "$*" == *"INSERT INTO ab_platform_release"* ]]; then printf "%s\\n" "$*" >> "$CAPTURE_SQL"; else echo 20261020000000; fi');
  const capture = path.join(root, 'sql.txt');
  const env = {
    ...process.env, PATH: `${bin}${path.delimiter}${process.env.PATH}`,
    AURA_CORE_ROOT: core, AURA_ENTERPRISE_ROOT: enterprise,
    PG_HOST: '127.0.0.1', PG_PORT: '5432', PG_USER: 'fixture', PG_DB: 'fixture',
    PG_PASSWORD: 'fixture-only', CAPTURE_SQL: capture,
  };
  delete env.AURA_RELEASE_REPOSITORY_SHAS_JSON;
  delete env.AURA_FLYWAY_CORE_MIGRATION_DIR;
  const sources = { core: 'a'.repeat(40), enterprise: 'b'.repeat(40), crm: 'c'.repeat(40) };
  return {
    root, core, enterprise, env, sources,
    run(overrides = {}, args = []) {
      return spawnSync('bash', [path.join(core, 'scripts/db/write-platform-release.sh'),
        '--edition', 'enterprise', '--enterprise-root', enterprise, '--app-version', 'fixture', ...args],
      { env: { ...env, ...overrides }, encoding: 'utf8' });
    },
    sql() { return fs.existsSync(capture) ? fs.readFileSync(capture, 'utf8') : ''; },
  };
}

test('Git-free deployment records all explicitly verified full repository SHAs', (t) => {
  const f = fixture(t);
  const result = f.run({ AURA_RELEASE_REPOSITORY_SHAS_JSON: JSON.stringify(f.sources) },
    ['--git-sha', f.sources.enterprise]);
  assert.equal(result.status, 0, result.stderr);
  assert.ok(f.sql().includes(`"core": "${f.sources.core}"`));
  assert.ok(f.sql().includes(`"enterprise": "${f.sources.enterprise}"`));
  assert.ok(f.sql().includes(`"crm": "${f.sources.crm}"`));
  assert.ok(!f.sql().includes('unknown'));
});

test('Git-free deployment without verified source identities fails before ledger insertion', (t) => {
  const f = fixture(t);
  const result = f.run();
  assert.notEqual(result.status, 0);
  assert.equal(f.sql(), '');
});

test('Short or incomplete explicit repository identities cannot write deployment metadata', (t) => {
  const f = fixture(t);
  for (const sources of [{ ...f.sources, core: 'abcdef0' }, { core: f.sources.core }]) {
    const result = f.run({ AURA_RELEASE_REPOSITORY_SHAS_JSON: JSON.stringify(sources) });
    assert.notEqual(result.status, 0);
    assert.equal(f.sql(), '');
  }
});

test('Primary git_sha must match the verified primary repository', (t) => {
  const f = fixture(t);
  const result = f.run({ AURA_RELEASE_REPOSITORY_SHAS_JSON: JSON.stringify(f.sources) },
    ['--git-sha', f.sources.core]);
  assert.notEqual(result.status, 0);
  assert.equal(f.sql(), '');
});

test('Explicit source identities cannot contradict an available Git checkout', (t) => {
  const f = fixture(t);
  for (const repo of [f.core, f.enterprise]) {
    assert.equal(spawnSync('git', ['init', '-b', 'main', repo]).status, 0);
    assert.equal(spawnSync('git', ['-C', repo, '-c', 'user.name=fixture',
      '-c', 'user.email=fixture@example.invalid', 'commit', '--allow-empty', '-m', 'fixture']).status, 0);
  }
  const result = f.run({ AURA_RELEASE_REPOSITORY_SHAS_JSON: JSON.stringify(f.sources) });
  assert.notEqual(result.status, 0);
  assert.equal(f.sql(), '');
});
