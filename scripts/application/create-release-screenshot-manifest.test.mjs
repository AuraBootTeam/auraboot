import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { resolve } from 'node:path';
import test from 'node:test';

const script = resolve(import.meta.dirname, 'create-release-screenshot-manifest.mjs');

function png(width, height) {
  const bytes = Buffer.alloc(24);
  Buffer.from('89504e470d0a1a0a', 'hex').copy(bytes, 0);
  bytes.writeUInt32BE(13, 8);
  bytes.write('IHDR', 12);
  bytes.writeUInt32BE(width, 16);
  bytes.writeUInt32BE(height, 20);
  return bytes;
}

test('release screenshot manifest requires every stable ID and records immutable image facts', (t) => {
  const root = mkdtempSync(resolve(tmpdir(), 'aura-release-screenshots-'));
  t.after(() => execFileSync('find', [root, '-depth', '-delete']));
  mkdirSync(resolve(root, 'journey'));
  writeFileSync(resolve(root, 'journey', 'BPM-REL-01.png'), png(1440, 900));
  writeFileSync(resolve(root, 'journey', 'BPM-REL-02.png'), png(1440, 1200));
  const output = resolve(root, 'manifest.json');
  execFileSync(process.execPath, [script, '--root', root, '--output', output,
    '--required', 'BPM-REL-01.png,BPM-REL-02.png', '--product', 'aura-bpm',
    '--core-commit', '1'.repeat(40), '--product-commit', '2'.repeat(40),
    '--image-digest', `sha256:${'3'.repeat(64)}`]);
  const manifest = JSON.parse(readFileSync(output, 'utf8'));
  assert.equal(manifest.allRequiredPresent, true);
  assert.equal(manifest.visualReview, 'PENDING');
  assert.deepEqual(manifest.screenshots.map(({ width, height }) => [width, height]), [[1440, 900], [1440, 1200]]);
  assert.match(manifest.screenshots[0].sha256, /^sha256:[0-9a-f]{64}$/);

  const missing = spawnSync(process.execPath, [script, '--root', root, '--output', output,
    '--required', 'BPM-REL-03.png', '--product', 'aura-bpm', '--core-commit', '1'.repeat(40),
    '--product-commit', '2'.repeat(40), '--image-digest', `sha256:${'3'.repeat(64)}`],
  { encoding: 'utf8' });
  assert.equal(missing.status, 1);
  assert.match(missing.stderr, /must exist exactly once; found 0/);
});
