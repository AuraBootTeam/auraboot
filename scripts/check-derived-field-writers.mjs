#!/usr/bin/env node
/**
 * Gate: a declared derived field must not have a write path that bypasses its
 * deriver.
 *
 * The shape behind two production defects this month: a model has a column that
 * only a backend service is supposed to compute (`bom_mm_norm_text` from
 * spec+name; `bom_mm_attributes_json` from the same), and also carries a
 * declarative `create`/`update` command with no handler, which writes the form
 * fields straight to the row and leaves the derived column null. The matcher
 * then reads that null and throws inside a bare loop, taking out a whole recall
 * query — not just the one bad row.
 *
 * Why this gate is declarative rather than inferred: config cannot tell a
 * must-derive column from a merely-nullable one. `norm_text` (null → crash) and
 * `manual_locked` (null → fine) look identical in fields.json — both just carry
 * a dataType, neither is marked required. The knowledge of which is a hard
 * contract lives only in Java (`SqlV2LibraryPort.toRow` throws on a blank one).
 * A static guesser over that ambiguity produced 63 model hits and thousands of
 * false positives when tried, which is exactly the false-positive rate that
 * gets a gate switched off.
 *
 * So the plugin declares its derived fields, in fields.json:
 *   { "code": "bom_mm_norm_text", "feature": { "derived": true }, ... }
 * and the gate checks the one thing it CAN be sure of: that no handler-less
 * declarative create/update command lists that field in inputFields, and — the
 * subtler leak — that a hand-editable field feeding a deriver still routes
 * through a handler.
 *
 *   node scripts/check-derived-field-writers.mjs
 *   node scripts/check-derived-field-writers.mjs --plugin-root ../plugins --json
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadConfigList } from './lib/plugin-config.mjs';

import { resolveRepoRoot } from './lib/repo-root.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

function readJson(abs) { return JSON.parse(fs.readFileSync(abs, 'utf8')); }

/** `feature.derived === true` on a field marks it deriver-only. */
export function derivedFields(pluginDir) {
  return loadConfigList(pluginDir, 'fields')
    .filter((f) => f?.feature && typeof f.feature === 'object' && f.feature.derived === true)
    .map((f) => f.code)
    .filter(Boolean);
}

/** Which model each field is bound to, from bindings.json (prefix guessing
 *  cross-contaminates models — bom_cl_* fields leak into a bom model that only
 *  shares the `bom` namespace). */
export function fieldModel(pluginDir) {
  const map = new Map();
  for (const b of loadConfigList(pluginDir, 'bindings')) {
    if (b?.fieldCode && b?.modelCode) map.set(b.fieldCode, b.modelCode);
  }
  return map;
}

/** Handler-less declarative create/update commands, by target model. */
function declarativeWriters(pluginDir) {
  const byModel = new Map();
  for (const c of loadConfigList(pluginDir, 'commands')) {
    if (!c?.modelCode) continue;
    if (!['create', 'update'].includes(c.type)) continue;
    if (c.handler) continue; // a handler is the sanctioned place to run the deriver
    if (!byModel.has(c.modelCode)) byModel.set(c.modelCode, []);
    byModel.get(c.modelCode).push(c);
  }
  return byModel;
}

export function auditDerivedWriters({ roots, config = {} }) {
  const findings = [];
  const summary = [];

  for (const root of roots) {
    if (!fs.existsSync(root)) {
      findings.push({ level: 'error', kind: 'missing-root', root, message: `plugin root does not exist: ${root}` });
      continue;
    }
    for (const plugin of fs.readdirSync(root).sort()) {
      const pluginDir = path.join(root, plugin);
      if (!fs.statSync(pluginDir).isDirectory()) continue;
      const derived = derivedFields(pluginDir);
      if (derived.length === 0) continue;

      const model = fieldModel(pluginDir);
      const writers = declarativeWriters(pluginDir);
      const allow = config.allow?.[plugin] ?? {};
      let leaks = 0;

      for (const field of derived) {
        const m = model.get(field);
        if (!m) {
          findings.push({ level: 'warn', kind: 'unbound-derived', plugin, field,
            message: `${field} is marked derived but bound to no model; drop the mark or add a binding` });
          continue;
        }
        for (const cmd of writers.get(m) ?? []) {
          const inputs = cmd.inputFields ?? [];
          if (!inputs.includes(field)) continue;
          const key = `${cmd.code}::${field}`;
          if (Object.prototype.hasOwnProperty.call(allow, key)) {
            if (!String(allow[key] ?? '').trim()) {
              findings.push({ level: 'error', kind: 'allow-without-reason', plugin, field, command: cmd.code,
                message: `${key}: allowlisted with an empty reason` });
            }
            continue;
          }
          leaks += 1;
          findings.push({ level: 'error', kind: 'bypass', plugin, field, command: cmd.code,
            message: `${cmd.code} (declarative ${cmd.type}, no handler) can write the derived field `
              + `${field} on model ${m} — it will land whatever the form sent and skip the deriver, `
              + 'leaving the column in a state its consumer does not expect' });
        }
      }

      // A derived field that no command CAN write is not automatically safe: a
      // handler must still populate it, but that is a runtime fact this static
      // gate cannot see. It only guarantees the declarative bypass is closed.
      summary.push({ plugin, derived: derived.length, leaks });
    }
  }
  return { findings, summary };
}

function main(argv) {
  const repoRoot = resolveRepoRoot(argv, path.resolve(HERE, '..'));
  const asJson = argv.includes('--json');
  const rootFlag = argv.indexOf('--plugin-root');
  const cfgAbs = path.join(repoRoot, 'scripts/derived-field-writers.json');
  const config = fs.existsSync(cfgAbs) ? readJson(cfgAbs) : {};
  const roots = rootFlag >= 0
    ? [path.resolve(repoRoot, argv[rootFlag + 1])]
    : (config.roots ?? ['plugins']).map((r) => path.resolve(repoRoot, r));

  const { findings, summary } = auditDerivedWriters({ roots, config });

  if (asJson) {
    console.log(JSON.stringify({ summary, findings }, null, 2));
  } else {
    console.log(`[derived-field-writers] ${summary.length} plugin(s) declaring derived fields`);
    for (const f of findings) console.log(`  ${f.level === 'error' ? 'ERROR' : 'WARN '} ${f.kind}: ${f.message}`);
  }

  const errors = findings.filter((f) => f.level === 'error');
  if (errors.length > 0) {
    console.error(`\n[derived-field-writers] FAIL — ${errors.length} bypass(es).`);
    console.error('A declarative command writing a derived field skips the code that column depends on.');
    console.error('Give the command a handler that runs the deriver, or record the exception with a reason.');
    return 1;
  }
  console.log('[derived-field-writers] PASS');
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exit(main(process.argv.slice(2)));
}
