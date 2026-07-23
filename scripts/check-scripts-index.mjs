#!/usr/bin/env node
/**
 * Falsifiable freshness gate for scripts/README.md (the scripts index).
 *
 * Every non-test script under scripts/ MUST have a row in README.md, and every
 * script row in README.md MUST point to a file that still exists. Compares the
 * *set of script paths* only — NOT the refs/updated columns, which are a snapshot
 * and are allowed to lag between refreshes.
 *
 * Fix on failure:
 *   - MISSING (file exists, no row): add a `| `<path>` | … |` row under the right section.
 *   - STALE   (row exists, file gone): delete that row.
 *
 * Self-contained (no deps). Exit 1 on drift. Run: `node scripts/check-scripts-index.mjs`
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SCRIPTS_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)));
const README = path.join(SCRIPTS_DIR, 'README.md');
const SCRIPT_EXT = new Set(['.mjs', '.sh', '.cjs', '.js']);
// relative to SCRIPTS_DIR
const EXCLUDE = /(^|\/)(node_modules|build|dist|\.git|\.worktrees|\.workspace|\.gradle|\.venv|site-packages|__pycache__|\.next|coverage)(\/|$)|\.stage\//;
const isTest = (f) => /\.test\.(mjs|js|cjs)$/.test(f);

function walk(dir, acc = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (EXCLUDE.test(path.relative(SCRIPTS_DIR, p))) continue;
    if (e.isDirectory()) walk(p, acc);
    else if (SCRIPT_EXT.has(path.extname(p))) acc.push(p);
  }
  return acc;
}

if (!fs.existsSync(README)) {
  console.error(`[scripts-index] FAIL: ${path.relative(process.cwd(), README)} is missing.`);
  process.exit(1);
}

const actual = new Set(
  walk(SCRIPTS_DIR)
    .map((p) => path.relative(SCRIPTS_DIR, p))
    .filter((rel) => !isTest(path.basename(rel))),
);

// README rows look like: | `dev/foo.sh` | 3 | 2026-07-23 | purpose |
// Only match a first-cell backtick path that ends in a script extension (skips
// the Counts table and the `check-*` / `git log …` code spans in prose).
const readme = fs.readFileSync(README, 'utf8');
const listed = new Set(
  [...readme.matchAll(/^\|\s*`([^`]+\.(?:mjs|sh|cjs|js))`\s*\|/gm)].map((m) => m[1]),
);

const missing = [...actual].filter((p) => !listed.has(p)).sort();
const stale = [...listed].filter((p) => !actual.has(p)).sort();

if (missing.length || stale.length) {
  console.error(`[scripts-index] FAIL: scripts/README.md is out of sync with scripts/.`);
  if (missing.length) {
    console.error(`\n  ${missing.length} script(s) with NO index row — add a row for each:`);
    for (const p of missing) console.error(`    + ${p}`);
  }
  if (stale.length) {
    console.error(`\n  ${stale.length} index row(s) pointing at a MISSING file — delete each:`);
    for (const p of stale) console.error(`    - ${p}`);
  }
  console.error(`\n  (Only the script-path set is checked; the refs/updated columns may lag.)`);
  process.exit(1);
}

console.log(`[scripts-index] OK: ${actual.size} non-test scripts, all indexed in scripts/README.md.`);
