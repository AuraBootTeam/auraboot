#!/usr/bin/env node
/**
 * Gate: the committed coverage manifest must still describe reality.
 *
 * A matrix that drifts is worse than no matrix, because people read it and stop
 * looking. The quote/bom quality matrix ships its own re-verification commands
 * and was still 36–40% out of date after eight days — nobody ran them, and
 * nothing made not running them visible.
 *
 * Freshness is checked by regenerating and diffing, not by comparing file
 * timestamps: mtimes are meaningless after a clone or a checkout, so a
 * timestamp check passes in exactly the situation where it matters least.
 *
 * Two things fail the gate:
 *   - a command exists that the manifest has no row for (the denominator shrank
 *     silently, which is how an uncovered action becomes invisible);
 *   - the untested count went up (coverage regressed).
 *
 * Untested going DOWN is fine and does not require regenerating — you are
 * allowed to be ahead of the file.
 *
 *   node scripts/check-coverage-manifest-freshness.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildManifest } from './gen-coverage-manifest.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
export const DEFAULT_CONFIG = 'scripts/coverage-manifest.json';

export function compareManifests(committed, fresh) {
  const findings = [];
  const rowsOf = (m) => new Map((m.groups ?? []).flatMap((g) => (g.rows ?? []).map((r) => [`${g.id}::${r.id}`, r])));
  const before = rowsOf(committed);
  const after = rowsOf(fresh);

  for (const key of after.keys()) {
    if (!before.has(key)) {
      findings.push({ level: 'error', kind: 'missing-row', key,
        message: `${key} is declared but has no row in the committed manifest — regenerate it` });
    }
  }
  for (const key of before.keys()) {
    if (!after.has(key)) {
      findings.push({ level: 'warn', kind: 'stale-row', key,
        message: `${key} has a row but is no longer declared; regenerate to drop it` });
    }
  }

  const wasUntested = committed.stats?.untested ?? 0;
  const nowUntested = fresh.stats?.untested ?? 0;
  if (nowUntested > wasUntested) {
    findings.push({ level: 'error', kind: 'coverage-regressed',
      message: `untested rows went from ${wasUntested} to ${nowUntested} — `
        + 'something lost its coverage, or a new command arrived without any' });
  }

  return { findings, wasUntested, nowUntested };
}

function main() {
  const repoRoot = path.resolve(HERE, '..');
  const cfgAbs = path.join(repoRoot, DEFAULT_CONFIG);
  if (!fs.existsSync(cfgAbs)) {
    console.log(`[manifest-freshness] no ${DEFAULT_CONFIG}; nothing to check`);
    return 0;
  }
  const cfg = JSON.parse(fs.readFileSync(cfgAbs, 'utf8'));
  let status = 0;

  for (const t of cfg.targets ?? []) {
    const committedAbs = path.resolve(repoRoot, t.manifest);
    if (!fs.existsSync(committedAbs)) {
      console.error(`[manifest-freshness] ERROR missing manifest: ${t.manifest}`);
      status = 1;
      continue;
    }
    const committed = JSON.parse(fs.readFileSync(committedAbs, 'utf8'));
    const fresh = buildManifest({
      repoRoot,
      pluginRoot: path.resolve(repoRoot, t.pluginRoot),
      only: t.plugin ?? null,
      specRoots: t.specRoots ?? ['web-admin/tests/e2e'],
      runId: committed.run?.id ?? 'regen',
      target: t.pluginRoot,
    });
    const { findings, wasUntested, nowUntested } = compareManifests(committed, fresh);
    console.log(`[manifest-freshness] ${t.manifest}: untested ${wasUntested} → ${nowUntested}`);
    for (const f of findings) {
      console.log(`  ${f.level === 'error' ? 'ERROR' : 'WARN '} ${f.kind}: ${f.message}`);
      if (f.level === 'error') status = 1;
    }
  }

  if (status !== 0) {
    console.error('\n[manifest-freshness] FAIL — regenerate with scripts/gen-coverage-manifest.mjs');
    console.error('A matrix that drifts is worse than none: people read it and stop looking.');
    return 1;
  }
  console.log('[manifest-freshness] PASS');
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main());
}
