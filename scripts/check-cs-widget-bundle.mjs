#!/usr/bin/env node
/**
 * The widget bundle served to customers is a build artifact that lives on the Java classpath, so
 * it can drift from its source without anything failing: the TypeScript changes, the tests pass,
 * and the browsers keep getting the old file. This rebuilds it and fails if the committed copy is
 * not what the current source produces.
 *
 *   node scripts/check-cs-widget-bundle.mjs
 */
import { execSync } from 'node:child_process';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const repo = dirname(dirname(fileURLToPath(import.meta.url)));
const pkgDir = join(repo, 'web-admin', 'packages', 'cs-widget');
const built = join(pkgDir, 'dist', 'aura-cs.global.js');
const served = join(repo, 'platform', 'src', 'main', 'resources', 'static', 'cs', 'aura-cs.global.js');

const fail = (m) => {
  console.error('check-cs-widget-bundle FAIL:', m);
  process.exit(1);
};

if (!existsSync(served)) fail(`served bundle missing: ${served}`);

// This package is a pnpm workspace member, and a fresh checkout (or a fresh worktree) has no
// node_modules under it — `npm run build` there exits 127 and the gate never gets to say anything
// about the bundle. Install what the build needs, so that a gate nobody has installed for still
// gives a verdict rather than an excuse. Takes about a second when it is already there.
if (!existsSync(join(pkgDir, 'node_modules'))) {
  execSync('pnpm install --filter @auraboot/cs-widget', { cwd: repo, stdio: 'inherit' });
}

execSync('pnpm run build', { cwd: pkgDir, stdio: 'inherit' });
if (!existsSync(built)) fail('build produced no bundle');

const sha = (p) => createHash('sha256').update(readFileSync(p)).digest('hex');
const builtSha = sha(built);
const servedSha = sha(served);

if (builtSha !== servedSha) {
  fail(
    'the served widget bundle is stale — customers would keep loading the old widget.\n' +
      `  built:  ${builtSha}\n  served: ${servedSha}\n` +
      '  fix: cp web-admin/packages/cs-widget/dist/aura-cs.global.js platform/src/main/resources/static/cs/aura-cs.global.js',
  );
}

console.log(`OK: served widget bundle matches its source (${builtSha.slice(0, 12)})`);
