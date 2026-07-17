#!/usr/bin/env node
/**
 * Builds the embeddable customer-service widget and asserts the artifact is actually usable from
 * a <script> tag: the IIFE must expose window.AuraCS.init, and the bundle must still be wired to
 * the endpoints and the site-key header it is supposed to use.
 *
 * A bundle that builds is not a bundle that works — this executes it. Kept out of the jsdom unit
 * suite so a vite build does not run on every test pass.
 *
 *   node packages/cs-widget/scripts/verify-global-build.mjs
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import vm from 'node:vm';

const pkgDir = dirname(dirname(fileURLToPath(import.meta.url)));
const dist = join(pkgDir, 'dist', 'aura-cs.global.js');

const fail = (m) => {
  console.error('verify-global-build FAIL:', m);
  process.exit(1);
};

execSync('npm run build', { cwd: pkgDir, stdio: 'inherit' });

if (!existsSync(dist)) fail('dist/aura-cs.global.js not produced');
const code = readFileSync(dist, 'utf8');
if (code.length < 1000) fail(`bundle suspiciously small (${code.length} bytes)`);

for (const s of ['X-Site-Key', '/api/public/cs/session', '/api/public/cs/message']) {
  if (!code.includes(s)) fail(`bundle missing contract string "${s}"`);
}
// The secret must never be in a browser bundle. userHash is computed on the host site's server;
// if the word "identitySecret" ever appears here, someone has leaked the HMAC key into the client.
if (code.includes('identitySecret')) fail('bundle references identitySecret — the HMAC key must never ship to a browser');

// Execute the IIFE the way a <script> tag would, and prove the global is real.
const ctx = {
  window: { location: { origin: 'https://example.test' } },
  self: {},
  document: { currentScript: null, querySelector: () => null, readyState: 'complete' },
  console: { warn: () => {} },
};
ctx.window.window = ctx.window;
vm.createContext(ctx);
vm.runInContext(code, ctx);

const A = ctx.AuraCS || ctx.window.AuraCS;
if (typeof A !== 'object' || A === null) fail('AuraCS global not exposed');
if (typeof A.init !== 'function') fail('AuraCS.init is not a function');

// With no site key anywhere, init must decline rather than throw or half-start.
if (A.init({}) !== null) fail('init() without a site key should return null');

console.log(`OK: aura-cs.global.js (${code.length} B) exposes AuraCS.init wired to /api/public/cs/** (X-Site-Key)`);
