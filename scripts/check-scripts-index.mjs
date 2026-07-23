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

// `--repo <path>` lets this single OSS-resident gate check any product repo's
// scripts/README.md (the workspace fan-out pattern). Default: this script's own repo.
const argv = process.argv.slice(2);
let repoArg = null;
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--repo') repoArg = argv[++i];
  else if (argv[i].startsWith('--repo=')) repoArg = argv[i].slice(7);
}
const SELF_SCRIPTS = path.dirname(fileURLToPath(import.meta.url));
const REPO = repoArg ? path.resolve(repoArg) : path.dirname(SELF_SCRIPTS);
const SCRIPTS_DIR = path.join(REPO, 'scripts');
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
  // Not onboarded to the scripts index — skip with a visible note, never a silent
  // pass (mirrors test-system-gate-run.sh's skip-if-no-manifest philosophy).
  console.log(`[scripts-index] SKIP ${path.basename(REPO)} — no scripts/README.md (repo not onboarded to the scripts index).`);
  process.exit(0);
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
