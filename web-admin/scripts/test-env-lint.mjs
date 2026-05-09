#!/usr/bin/env node
/**
 * test-env-lint — guard against env-drift in tests/.
 *
 * Background:
 *   `pnpm lint` only covers `app/**` (see package.json). Test code is not
 *   linted, so hard-coded ports / direct process.env reads sneak in and
 *   silently break against isolated docker stacks. This script enforces
 *   the central env contract from tests/helpers/playwright-env.ts +
 *   tests/helpers/pg-env.ts.
 *
 * Strategy:
 *   - Re-runs the same two greps captured in tests/.env-drift-baseline.json
 *     under tests/ + the seven playwright*.ts configs.
 *   - Compares the current hit set against the baseline. Anything in the
 *     baseline is grandfathered (will be cleaned up by Phase 1.5 / 3
 *     migrations). Anything NEW fails the script with exit 1.
 *
 * Usage:
 *   node scripts/test-env-lint.mjs            # check vs baseline
 *   node scripts/test-env-lint.mjs --update   # rewrite baseline (use sparingly,
 *                                             #  e.g. after a wave of migrations
 *                                             #  shrunk the baseline set)
 *
 * CI integration: `pnpm test:env-lint` (added to package.json), invoked
 * by .github/workflows/frontend.yml after `pnpm lint`.
 */

import { readFileSync, writeFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { resolve, relative, join } from 'node:path';

function walk(dir, extensions = ['.ts', '.tsx']) {
  const out = [];
  if (!existsSync(dir)) return out;
  const stat = statSync(dir);
  if (stat.isFile()) {
    if (extensions.some((e) => dir.endsWith(e))) out.push(dir);
    return out;
  }
  for (const entry of readdirSync(dir)) {
    if (entry === 'node_modules' || entry === 'build' || entry === 'test-results') continue;
    out.push(...walk(join(dir, entry), extensions));
  }
  return out;
}

const ROOT = resolve(import.meta.dirname, '..');
const BASELINE_PATH = resolve(ROOT, 'tests/.env-drift-baseline.json');

const PORT_RE = /['"]http:\/\/localhost:(5173|6443|3500|5174|6444|3501|6478|5208|3535)['"]|localhost:(5173|6443|3500|5432)/g;
const ENV_RE = /process\.env\.(BACKEND_URL|BASE_URL|BFF_URL|BE_PORT|VITE_PORT|BFF_PORT|PG_HOST|PG_PORT|PG_USER|PG_DB|PGPASSWORD|PLAYWRIGHT_BASE_URL)/g;

const ROOTS = [
  'tests',
  'playwright.config.ts',
  'playwright.oss.config.ts',
  'playwright.seed.config.ts',
  'playwright.bpm-regression.config.ts',
  'playwright.init.config.ts',
  'playwright.noweb.config.ts',
  'playwright.quick.config.ts',
  'playwright.team-test.config.ts',
];

function collect(pattern, kind) {
  const hits = [];
  for (const root of ROOTS) {
    const abs = resolve(ROOT, root);
    const files = walk(abs);
    for (const abs of files) {
      const text = readFileSync(abs, 'utf-8');
      const lines = text.split('\n');
      lines.forEach((line, idx) => {
        const re = new RegExp(pattern.source, 'g'); // fresh regex each line
        let m;
        while ((m = re.exec(line)) !== null) {
          hits.push({
            file: relative(ROOT, abs),
            line: idx + 1,
            match: m[0],
            kind,
          });
        }
      });
    }
  }
  return hits;
}

function asKey(h) {
  return `${h.file}:${h.line}:${h.kind}:${h.match}`;
}

function loadBaseline() {
  try {
    const raw = readFileSync(BASELINE_PATH, 'utf-8');
    const obj = JSON.parse(raw);
    const set = new Set();
    for (const [_file, hits] of Object.entries(obj.files ?? {})) {
      for (const h of hits) set.add(asKey(h));
    }
    return { obj, set };
  } catch (e) {
    console.error(`[test-env-lint] cannot read ${BASELINE_PATH}: ${e.message}`);
    process.exit(2);
  }
}

function writeBaseline(hits) {
  const byFile = {};
  for (const h of hits) {
    if (!byFile[h.file]) byFile[h.file] = [];
    byFile[h.file].push(h);
  }
  const sorted = Object.fromEntries(
    Object.entries(byFile)
      .sort(([a], [b]) => a.localeCompare(b))
      .map(([f, hs]) => [f, hs.sort((a, b) => a.line - b.line)])
  );
  const baseline = {
    generated: new Date().toISOString().slice(0, 10),
    scope: ROOTS,
    regex_port: PORT_RE.source,
    regex_env: ENV_RE.source,
    totals: {
      port_literals: hits.filter((h) => h.kind === 'port-literal').length,
      process_env: hits.filter((h) => h.kind === 'process.env').length,
      files: Object.keys(sorted).length,
    },
    files: sorted,
  };
  writeFileSync(BASELINE_PATH, JSON.stringify(baseline, null, 2) + '\n');
  return baseline;
}

const args = process.argv.slice(2);
const update = args.includes('--update');

const ports = collect(PORT_RE, 'port-literal');
const envs = collect(ENV_RE, 'process.env');
const allHits = [...ports, ...envs];

if (update) {
  const baseline = writeBaseline(allHits);
  console.log(
    `[test-env-lint] baseline updated: ${baseline.totals.port_literals} port literals + ${baseline.totals.process_env} process.env reads across ${baseline.totals.files} files.`
  );
  process.exit(0);
}

const { set: baselineSet } = loadBaseline();
const newHits = allHits.filter((h) => !baselineSet.has(asKey(h)));

if (newHits.length === 0) {
  console.log(
    `[test-env-lint] OK: ${allHits.length} hit(s) all in baseline (${ports.length} port literals + ${envs.length} process.env reads).`
  );
  process.exit(0);
}

console.error(
  `[test-env-lint] FAIL: ${newHits.length} new env-drift violation(s) not in baseline:\n`
);
for (const h of newHits) {
  console.error(`  ${h.file}:${h.line}  [${h.kind}]  ${h.match}`);
}
console.error(
  `\nPlease use the central env contract:\n` +
  `  - import { BACKEND_URL, BASE_URL, BFF_URL } from 'tests/helpers/playwright-env'\n` +
  `  - import { PSQL_BASE } from 'tests/helpers/pg-env'\n` +
  `\nIf this hit is justified (e.g. a deliberate fixture file), regenerate the\n` +
  `baseline with \`pnpm test:env-lint -- --update\` and commit the new\n` +
  `tests/.env-drift-baseline.json — but every refresh should shrink, not grow,\n` +
  `the totals.\n` +
  `\nv3 plan: docs/plans/2026-05/2026-05-09-env-scripts-testing-systematic-design.md\n`
);
process.exit(1);
