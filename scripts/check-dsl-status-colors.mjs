#!/usr/bin/env node
/**
 * check-dsl-status-colors.mjs — DSL dict status color semantics gate.
 *
 * Standard `docs/standards/core/ux-design-system.md` §1.3: a business dict
 * item's `extension.color` (used to color status/tag dots and badges on
 * list/detail pages) must resolve to one of the 5 canonical semantic tones:
 *
 *   gray  (neutral)  — draft / not started / closed
 *   blue  (info)      — in progress / processing
 *   amber (warning)   — pending review / awaiting action / warning
 *   green (success)   — completed / passed / normal
 *   red   (danger)    — abnormal / rejected / failed / overdue
 *
 * Rendering source of truth is `resolveStatusTone()` in
 * web-admin/app/framework/meta/runtime/renderers/statusTone.tsx: it looks
 * the raw color up in an alias table (`TONE_BY_NAME`), then tries to map a
 * hex color to a tone by hue, and — if BOTH fail — silently falls back to
 * "gray". That fallback is the actual bug this gate exists to catch: a dict
 * author picks a color name that isn't a canonical tone and isn't in the
 * alias table (e.g. "purple", "cyan"), the platform never errors, and the
 * status dot just quietly renders gray forever.
 *
 * This gate flags any `extension.color` value that is NOT:
 *   - one of the 5 canonical tone names (case-insensitive), OR
 *   - a syntactically valid `#RGB` / `#RRGGBB` hex color (accepted as-is;
 *     hue-to-tone mapping happens at render time and is out of scope here).
 *
 * For every violation it prints a suggested replacement mirrored from
 * `TONE_BY_NAME` in statusTone.tsx (e.g. "orange" -> "amber", "default" ->
 * "gray"). Values with no alias (e.g. "purple", "cyan") are flagged with an
 * explicit warning that they silently degrade to gray at runtime and must
 * be assigned a semantic tone by a human — this gate will not guess.
 *
 * NOTE: `TONE_BY_NAME` below is a hand-mirrored copy of the alias table in
 * statusTone.tsx (kept as plain data so this Node script doesn't need a
 * TSX-capable loader). If that table changes, update this one too.
 *
 * Scope: every `plugins/*\/config/**\/*.json` file (dicts.json is the
 * primary offender, but any config JSON with an `extension.color` dict-item
 * shape is covered).
 *
 * Usage: node scripts/check-dsl-status-colors.mjs
 * Exit 0 = all extension.color values are in-bounds, 1 = violation(s) found.
 */
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { resolve, dirname, relative, extname } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS = resolve(REPO, 'plugins');

const ALLOWED_TONES = new Set(['gray', 'blue', 'amber', 'green', 'red']);
const HEX_RE = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i;

// Mirror of TONE_BY_NAME in
// web-admin/app/framework/meta/runtime/renderers/statusTone.tsx.
const TONE_BY_NAME = {
  green: [
    'success', 'green', 'done', 'completed', 'complete', 'normal', 'pass',
    'passed', 'ok', 'approved', 'valid', 'enabled', 'online',
  ],
  red: [
    'error', 'red', 'danger', 'failed', 'fail', 'rejected', 'overdue',
    'invalid', 'expired', 'offline',
  ],
  amber: ['warning', 'warn', 'amber', 'yellow', 'orange', 'pending', 'waiting', 'review'],
  blue: [
    'info', 'blue', 'processing', 'in_progress', 'inprogress', 'active',
    'running', 'open', 'primary',
  ],
  gray: [
    'gray', 'grey', 'default', 'neutral', 'draft', 'closed', 'inactive',
    'none', 'disabled',
  ],
};
const ALIAS_TO_TONE = new Map();
for (const [tone, names] of Object.entries(TONE_BY_NAME)) {
  for (const name of names) ALIAS_TO_TONE.set(name, tone);
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

function listJsonFilesRecursive(dir) {
  const out = [];
  if (!existsSync(dir)) return out;
  for (const entry of readdirSync(dir)) {
    const full = resolve(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) out.push(...listJsonFilesRecursive(full));
    else if (extname(entry) === '.json') out.push(full);
  }
  return out;
}

function configFiles() {
  const out = [];
  if (!existsSync(PLUGINS)) return out;
  for (const plugin of readdirSync(PLUGINS)) {
    const cfgDir = resolve(PLUGINS, plugin, 'config');
    out.push(...listJsonFilesRecursive(cfgDir));
  }
  return out;
}

function suggestionFor(rawValue) {
  const key = rawValue.trim().toLowerCase();
  const tone = ALIAS_TO_TONE.get(key);
  if (tone) return `"${tone}" (per statusTone.tsx TONE_BY_NAME alias)`;
  return 'NO ALIAS in statusTone.tsx -- silently resolves to "gray" at runtime; a human must pick one of gray/blue/amber/green/red';
}

// Walk a parsed JSON doc for `{ extension: { color: "<value>" } }` nodes
// (dict-item shape). `onColor` is called for every string color value found.
function visitColors(node, onColor) {
  if (Array.isArray(node)) {
    for (const item of node) visitColors(item, onColor);
    return;
  }
  if (!node || typeof node !== 'object') return;
  const ext = node.extension;
  if (ext && typeof ext === 'object' && typeof ext.color === 'string') {
    const label = node.label ?? node['label:zh-CN'] ?? node.value ?? node.code ?? node.name ?? '(unknown)';
    onColor(ext.color, label);
  }
  for (const value of Object.values(node)) {
    if (value && typeof value === 'object') visitColors(value, onColor);
  }
}

// 1. Scan.
let filesScanned = 0;
let colorsScanned = 0;
const violations = [];
for (const file of configFiles()) {
  const doc = readJson(file);
  if (!doc) continue;
  filesScanned += 1;
  visitColors(doc, (value, label) => {
    colorsScanned += 1;
    const trimmed = value.trim();
    if (!ALLOWED_TONES.has(trimmed.toLowerCase()) && !HEX_RE.test(trimmed)) {
      violations.push({ file: relative(REPO, file), label, value });
    }
  });
}

// 2. Report.
if (violations.length === 0) {
  console.log(
    `✅ DSL status color semantics check passed ` +
      `(scanned ${filesScanned} config file(s), ${colorsScanned} extension.color value(s)).`,
  );
  process.exit(0);
}

console.error(
  `❌ DSL status color semantics check: ${violations.length} out-of-palette color(s) found.\n`,
);
for (const v of violations) {
  console.error(`  ${v.file}: dict item "${v.label}" has extension.color="${v.value}"`);
  console.error(`    suggested replacement: ${suggestionFor(v.value)}`);
}
console.error(
  '\nStandard `docs/standards/core/ux-design-system.md` §1.3: business dict ' +
    '`extension.color` must be one of the 5 canonical semantic tones ' +
    '(gray/blue/amber/green/red) or a valid #RGB/#RRGGBB hex color. Any other ' +
    'name that has no alias in statusTone.tsx TONE_BY_NAME silently renders ' +
    'gray at runtime -- fix the dict value, don\'t rely on the fallback.',
);
process.exit(1);
