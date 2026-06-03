#!/usr/bin/env node
/**
 * Guard against datetime-display antipatterns that leak UTC to users.
 *
 * Backend serializes all datetimes as UTC (spring.jackson.time-zone: UTC).
 * The frontend MUST convert to the effective display timezone via the canonical
 * formatter `formatInTimezone(value, format, timezone)` (see
 * app/shared/services/dateTimeFormatService.ts and
 * docs/standards/core/data-and-api.md §Datetime 时区显示规范).
 *
 * This check flags a high-signal antipattern in display code:
 *   Raw ISO string slicing — e.g. `x.replace("T", " ").slice(0, 16)`.
 *   It always shows the UTC wall-clock instead of the configured timezone.
 *
 * Usage:
 *   node scripts/check-datetime-display.mjs                 # scans OSS app/
 *   node scripts/check-datetime-display.mjs <dir|file> ...  # extra roots
 *
 * Exit code 1 on any violation. Reference incident: 2026-06-03 BomConvert
 * summary showed UTC 03:08 instead of Beijing 11:08.
 */
import { readFileSync, readdirSync, statSync } from 'fs';
import { resolve, dirname, relative, extname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB_ADMIN = resolve(__dirname, '..');

const roots = process.argv.slice(2).filter((a) => !a.startsWith('--'));
const scanRoots = (roots.length > 0 ? roots : ['app']).map((r) => resolve(WEB_ADMIN, r));

const EXT = new Set(['.ts', '.tsx']);
const IGNORE_DIR = new Set(['node_modules', 'build', 'dist', '.git']);
const isTestFile = (p) => /\.test\.|__tests__/.test(p);

// Antipattern: ISO string slicing. Matches `.replace("T"...` followed shortly
// by `.slice(` — the canonical "format a datetime by hand" smell.
const ISO_SLICE_RE = /\.replace\(\s*['"]T['"][\s\S]{0,60}?\.slice\(/;

function walk(p, out) {
  let st;
  try {
    st = statSync(p);
  } catch {
    return;
  }
  if (st.isDirectory()) {
    for (const name of readdirSync(p)) {
      if (IGNORE_DIR.has(name)) continue;
      walk(resolve(p, name), out);
    }
  } else if (st.isFile() && EXT.has(extname(p)) && !isTestFile(p)) {
    out.push(p);
  }
}

const files = [];
for (const root of scanRoots) walk(root, files);

const violations = [];
for (const file of files) {
  let text;
  try {
    text = readFileSync(file, 'utf-8');
  } catch {
    continue;
  }
  const lines = text.split('\n');
  for (let i = 0; i < lines.length; i++) {
    if (ISO_SLICE_RE.test(lines[i])) {
      violations.push({
        file: relative(WEB_ADMIN, file),
        line: i + 1,
        rule: 'ISO-SLICE',
        snippet: lines[i].trim().slice(0, 100),
      });
    }
  }
}

if (violations.length > 0) {
  console.error(`\n✗ datetime-display check FAILED — ${violations.length} violation(s):\n`);
  for (const v of violations) {
    console.error(`  [${v.rule}] ${v.file}:${v.line}`);
    console.error(`      ${v.snippet}`);
  }
  console.error(
    '\nFix: use formatInTimezone(value, format, timezone) from ' +
      'app/shared/services/dateTimeFormatService.ts (convert UTC → effective timezone).',
  );
  console.error('See docs/standards/core/data-and-api.md §Datetime 时区显示规范.\n');
  process.exit(1);
}

console.log(`✓ datetime-display check passed (${files.length} files scanned, 0 violations)`);
