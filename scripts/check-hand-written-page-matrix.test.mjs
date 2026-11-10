import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { DEFAULT_BASELINE } from './check-hand-written-page-matrix.mjs';

const gate = fileURLToPath(new URL('./check-hand-written-page-matrix.mjs', import.meta.url));
for (const kind of ['new', 'existing', 'symlink']) {
  test(`baseline update handles ${kind} file without touching another target`, t => {
    const root = mkdtempSync(join(tmpdir(), 'page-baseline-security-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    mkdirSync(join(root, 'scripts'));
    const baseline = join(root, DEFAULT_BASELINE);
    const foreign = join(root, 'preserved.json');
    writeFileSync(foreign, '["preserved"]');
    if (kind === 'existing') writeFileSync(baseline, '["old"]', { mode: 0o600 });
    if (kind === 'symlink') symlinkSync(foreign, baseline);
    const result = spawnSync(process.execPath, [gate, '--repo', root, '--update-baseline'], { encoding: 'utf8' });
    assert.equal(readFileSync(foreign, 'utf8'), '["preserved"]');
    if (kind === 'symlink') {
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, /ELOOP/);
    } else {
      assert.equal(result.status, 0, result.stderr);
      assert.deepEqual(JSON.parse(readFileSync(baseline, 'utf8')), []);
      assert.equal(statSync(baseline).mode & 0o777, 0o600);
    }
  });
}
