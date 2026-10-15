import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
const entry = resolve('scripts/dev/start-web-stack.sh');

test('production and retained entry use compiled SSR and never start a dev server; missing build fails closed', t => {
  const root = mkdtempSync(join(tmpdir(), 'oss-production-mode-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  mkdirSync(join(root, 'bin')); mkdirSync(join(root, 'build/server'), { recursive: true });
  writeFileSync(join(root, 'build/server/index.js'), 'compiled SSR');
  writeFileSync(join(root, 'bin/pnpm'), '#!/bin/sh\nprintf "%s\\n" "$NODE_ENV" "$@" > "$PROBE_FILE"\n', { mode: 0o755 });
  const env = { ...process.env, AURA_WEB_MODE: 'production', PATH: `${root}/bin:${process.env.PATH}`, PROBE_FILE: join(root, 'probe') };
  for (const phase of ['fresh', 'retained']) {
    const r = spawnSync('bash', [entry, phase], { cwd: root, env, encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    const args = readFileSync(env.PROBE_FILE, 'utf8');
    assert.match(args, /^production\n/); assert.match(args, /production-entry\.mjs/);
    assert.doesNotMatch(args, /dev:full|dev:web|react-router dev|restart-tries/);
  }
  rmSync(join(root, 'build/server/index.js'));
  const missing = spawnSync('bash', [entry, 'retained'], { cwd: root, env, encoding: 'utf8' });
  assert.notEqual(missing.status, 0); assert.match(missing.stderr, /build missing/);
});
