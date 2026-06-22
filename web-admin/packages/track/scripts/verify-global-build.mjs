#!/usr/bin/env node
/**
 * Builds the embeddable public-mode SDK bundle and asserts it exposes a working
 * `window.AuraTrack.init` IIFE wired to the keyed ingestion contract.
 *
 * Reproducible gate for CI / SP4 golden setup (kept OUT of the jsdom vitest suite
 * so a vite build doesn't run on every unit-test pass). Real-browser behavior is
 * proven by the SP4 anonymous-keyed golden; this asserts the artifact shape.
 *
 *   node packages/track/scripts/verify-global-build.mjs
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';

const pkgDir = dirname(dirname(fileURLToPath(import.meta.url)));
const dist = join(pkgDir, 'dist', 'aura-track.global.js');

const fail = (m) => {
  console.error('verify-global-build FAIL:', m);
  process.exit(1);
};

execSync('npm run build', { cwd: pkgDir, stdio: 'inherit' });

if (!existsSync(dist)) fail('dist/aura-track.global.js not produced');
const code = readFileSync(dist, 'utf8');
if (code.length < 500) fail(`bundle suspiciously small (${code.length} bytes)`);
for (const s of ['X-Site-Key', 'collect/keyed']) {
  if (!code.includes(s)) fail(`bundle missing keyed-contract string "${s}"`);
}

// Evaluate the IIFE in a sandbox; --format iife + name AuraTrack assigns the
// module exports to a context global. init() must be callable from a <script> tag.
const ctx = { window: {}, self: {} };
vm.createContext(ctx);
vm.runInContext(code, ctx);
const A = ctx.AuraTrack || ctx.window.AuraTrack;
if (typeof A !== 'object' || A === null) fail('AuraTrack global not exposed');
if (typeof A.init !== 'function') fail('AuraTrack.init is not a function');

console.log(
  `OK: aura-track.global.js (${code.length} B) exposes AuraTrack.init wired to /api/collect/keyed (X-Site-Key)`,
);
