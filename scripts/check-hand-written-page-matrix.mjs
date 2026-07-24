#!/usr/bin/env node
/**
 * No new hand-written page-coverage matrices.
 *
 * There are 29 GOLDEN-UI-COVERAGE-MATRIX.md files across the workspace. None has a
 * generator. None has a gate. One still cites a Docker image retired months ago and
 * carries the status "Blocked on local Docker image freshness". They are read as
 * coverage evidence.
 *
 * The problem is not that they are markdown, it is that they are a *denominator*
 * maintained by hand. A page someone forgot to list reads as "not applicable" rather
 * than as work, so the file gets more reassuring exactly as coverage gets worse. The
 * quote/bom matrix shipped its own re-verification commands and was still 36–40% out
 * of date after eight days.
 *
 * Page coverage now comes out of gen-coverage-manifest.mjs: every declared page gets a
 * row, including the ones no spec reaches (`verdict: untested`, `evidence: []`), so
 * uncovered pages are in the denominator instead of missing from the numerator.
 *
 * Existing files are recorded as debt in the baseline and warn. New ones fail. This is
 * adoption, not amnesty: the baseline is a list to shrink, and shrinking it means
 * deleting a file whose content is now generated, not deleting a file that still says
 * something the manifest cannot.
 *
 *   node scripts/check-hand-written-page-matrix.mjs [--update-baseline]
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveRepoRoot } from './lib/repo-root.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const NAME = 'GOLDEN-UI-COVERAGE-MATRIX.md';

function walk(dir, out = []) {
  let entries;
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return out; }
  for (const e of entries) {
    if (e.name === 'node_modules' || e.name === '.git' || e.name === 'build' || e.name === 'dist') continue;
    const abs = path.join(dir, e.name);
    if (e.isDirectory()) walk(abs, out);
    else if (e.name === NAME) out.push(abs);
  }
  return out;
}

export function audit({ repoRoot, roots, baseline }) {
  const found = roots
    .flatMap((r) => walk(path.join(repoRoot, r)))
    .map((f) => path.relative(repoRoot, f))
    .sort();
  const known = new Set(baseline);
  return {
    found,
    fresh: found.filter((f) => !known.has(f)),
    stale: baseline.filter((f) => !found.includes(f)),
  };
}

function main(argv) {
  const repoRoot = resolveRepoRoot(argv, path.resolve(HERE, '..'));
  const baselineFile = path.join(HERE, 'check-hand-written-page-matrix.baseline.json');
  const baseline = fs.existsSync(baselineFile)
    ? JSON.parse(fs.readFileSync(baselineFile, 'utf8'))
    : [];
  const roots = ['plugins', 'web-admin'];
  const { found, fresh, stale } = audit({ repoRoot, roots, baseline });

  if (argv.includes('--update-baseline')) {
    fs.writeFileSync(baselineFile, `${JSON.stringify(found, null, 2)}\n`);
    console.log(`[page-matrix] baseline updated: ${found.length} file(s) recorded as debt`);
    return 0;
  }

  if (stale.length > 0) {
    console.log(`[page-matrix] ${stale.length} baseline entr(ies) no longer exist — good, rerun with --update-baseline:`);
    for (const f of stale) console.log(`    ${f}`);
  }

  if (fresh.length > 0) {
    console.error(`[page-matrix] FAIL — ${fresh.length} new hand-written page matrix file(s):`);
    for (const f of fresh) console.error(`    ${f}`);
    console.error('');
    console.error('  Page coverage is generated, not written. Run:');
    console.error('    node scripts/gen-coverage-manifest.mjs --plugin-root plugins --out docs/coverage/oss-coverage-manifest.json');
    console.error('  Every declared page gets a row there, including the ones nothing tests.');
    console.error('  A hand-kept denominator drifts, and it drifts in the flattering direction.');
    return 1;
  }

  console.log(`[page-matrix] PASS — no new hand-written matrices (${baseline.length} pre-existing, tracked as debt)`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) process.exit(main(process.argv.slice(2)));
