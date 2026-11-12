import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cpSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

for (const kind of ['valid', 'symlink', 'tampered', 'embedded-script']) {
  test(`asset contract checks the opened bytes: ${kind}`, t => {
    const root = mkdtempSync(join(tmpdir(), 'asset-descriptor-security-'));
    t.after(() => rmSync(root, { recursive: true, force: true }));
    const scripts = join(root, 'scripts');
    const assets = join(root, 'public/static/xiaoya');
    mkdirSync(scripts, { recursive: true });
    mkdirSync(assets, { recursive: true });
    const gate = join(scripts, 'check.mjs');
    cpSync(new URL('./check-xiaoya-assets.mjs', import.meta.url), gate);
    const svg = `<svg viewBox="0 0 512 512">${kind === 'embedded-script' ? '<script>alert(1)</script>' : ''}</svg>`;
    writeFileSync(join(assets, 'asset-manifest.json'), JSON.stringify({ assets: [{ files: [{
      path: 'runtime/static/xiaoya/test.svg', bytes: Buffer.byteLength(svg),
      sha256: createHash('sha256').update(svg).digest('hex'),
    }] }] }));
    const target = join(assets, 'test.svg');
    if (kind === 'symlink') {
      const foreign = join(root, 'foreign.svg');
      writeFileSync(foreign, svg);
      symlinkSync(foreign, target);
    } else writeFileSync(target, kind === 'tampered' ? svg + 'changed' : svg);
    const result = spawnSync(process.execPath, [gate], { encoding: 'utf8' });
    if (kind === 'valid') assert.equal(result.status, 0, result.stderr);
    else assert.notEqual(result.status, 0);
    if (kind === 'symlink') assert.match(result.stderr, /ELOOP/);
    if (kind === 'tampered') assert.match(result.stderr, /sha256/);
    if (kind === 'embedded-script') assert.match(result.stderr, /forbidden embedded content/);
  });
}
