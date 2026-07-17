#!/usr/bin/env node
/**
 * check-dsl-render-types.mjs — a DSL page may only ask for a renderer that exists.
 *
 * Why this gate exists (2026-07-14, conversation → FAQ loop):
 *
 * A confidence column was configured `renderType: "percent"`. There is no `percent` renderer —
 * it was invented. Nothing said so. The page rendered, the import validator returned
 * `success: true`, the static page audit was 0/0, and the browser golden went green, because
 * every assertion asked "is the number on screen" and none asked "what does it look like".
 * The column silently printed a bare `0.95`.
 *
 * That is the shape of the whole class: **config written, nobody reads it, nothing warns.**
 * The same session found `format: "percent"` ignored by one branch of the evidence panel, a
 * `renderType` ignored outright by the table renderer, and a `review-drawer` rendering a panel
 * nobody configured. Prose in AGENTS.md did not stop any of them. A script that fails does.
 *
 * The check is deliberately narrow, so it cannot cry wolf: the set of legal renderers is read
 * from the registry source itself (the single place they are registered), so the gate cannot
 * drift away from the runtime. If a renderer is added, this passes with no edit here.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const REGISTRY = path.join(
  REPO_ROOT,
  'web-admin/app/framework/meta/runtime/renderers/CellRendererRegistry.tsx',
);
const PLUGIN_ROOT = path.join(REPO_ROOT, 'plugins');

/** The legal set, read from the one place renderers actually register themselves. */
function registeredRenderTypes() {
  const src = fs.readFileSync(REGISTRY, 'utf8');
  const found = [...src.matchAll(/cellRendererRegistry\.register\(\s*'([A-Za-z-]+)'/g)].map(
    (m) => m[1],
  );
  if (found.length === 0) {
    console.error(
      `FATAL: parsed 0 renderers out of ${path.relative(REPO_ROOT, REGISTRY)}.\n` +
        'The registration shape changed — fix this gate rather than deleting it, or it will ' +
        'pass vacuously forever.',
    );
    process.exit(2);
  }
  return new Set(found);
}

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, out);
    else if (entry.isFile() && entry.name.endsWith('.json')) out.push(full);
  }
  return out;
}

/** Every renderType in the tree, with the path that leads to it, so the error is actionable. */
function collectRenderTypes(node, trail, hits) {
  if (Array.isArray(node)) {
    node.forEach((child, i) => collectRenderTypes(child, `${trail}[${i}]`, hits));
    return;
  }
  if (!node || typeof node !== 'object') return;
  for (const [key, value] of Object.entries(node)) {
    if (key === 'renderType' && typeof value === 'string') {
      hits.push({ value, at: `${trail}.renderType` });
    } else {
      collectRenderTypes(value, `${trail}.${key}`, hits);
    }
  }
}

const legal = registeredRenderTypes();
const failures = [];
let checked = 0;

for (const file of walk(PLUGIN_ROOT)) {
  // DR-20260715-C-003: scan BOTH page layouts. The split layout lives under
  // `config/pages/<file>.json`; the single-file layout is `config/pages.json` (no
  // trailing separator). The old `includes('/config/pages/')` filter silently skipped
  // every single-file plugin, leaving its renderTypes ungated.
  const inSplitPages = file.includes(`${path.sep}config${path.sep}pages${path.sep}`);
  const isSingleFilePages = file.endsWith(`${path.sep}config${path.sep}pages.json`);
  if (!inSplitPages && !isSingleFilePages) continue;
  let schema;
  try {
    schema = JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    continue; // not our gate's job; the import validator owns malformed JSON
  }
  const hits = [];
  collectRenderTypes(schema, '$', hits);
  for (const hit of hits) {
    checked += 1;
    if (!legal.has(hit.value)) {
      failures.push({ file: path.relative(REPO_ROOT, file), ...hit });
    }
  }
}

console.log(`=== DSL renderType gate ===`);
console.log(`registered renderers: ${[...legal].sort().join(', ')}`);
console.log(`renderType usages checked: ${checked}`);

if (failures.length > 0) {
  console.error(`\n${failures.length} page(s) ask for a renderer that does not exist:\n`);
  for (const f of failures) {
    console.error(`  ${f.file}`);
    console.error(`      ${f.at} = "${f.value}"  ← not registered`);
  }
  console.error(
    `\nA renderType the registry does not know is not a styling miss — the value is printed raw ` +
      `(a 0-1 ratio, an enum code, an ISO timestamp) straight at the user.\n` +
      `Pick one of: ${[...legal].sort().join(', ')}\n`,
  );
  process.exit(1);
}

console.log('PASSED.');
