#!/usr/bin/env node
/**
 * check-dsl-command-convention.mjs — regression gate for convention CRUD routing.
 *
 * Standard create/edit/delete buttons on DSL pages should NOT hard-code the
 * model's CRUD command: the platform resolves it by convention (page-schema
 * `commands` map + FormPageContent/useActionHandler), so configuring it is
 * redundant and (for navigate buttons) re-introduces the ugly
 * `/p/<model>/new?commandCode=...` URL.
 *
 * This gate flags a button whose `action.command` EXACTLY equals the model's
 * convention command for that button's role — those should be dropped. It is
 * deliberately conservative:
 *   - convention is derived from plugin commands.json (top-level `type`), and
 *     only for UNAMBIGUOUS (model, type) pairs (exactly one command). Models
 *     with multiple create/update/delete commands (e.g. wd_leave_request) are
 *     skipped — their explicit config is correct.
 *   - cross-model sub-resource actions (a crm_account page invoking a
 *     crm_contact command), state_transition / custom commands, and variants
 *     never match the page model's convention command, so they are never flagged.
 *
 * Usage: node scripts/check-dsl-command-convention.mjs
 * Exit 0 = clean, 1 = redundant command(s) found.
 */
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { resolve, dirname, basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const PLUGINS = resolve(REPO, 'plugins');
const CRUD = new Set(['create', 'update', 'delete']);

function readJson(file) {
  try { return JSON.parse(readFileSync(file, 'utf8')); } catch { return null; }
}
function listFiles(sub) {
  const out = [];
  if (!existsSync(PLUGINS)) return out;
  for (const plugin of readdirSync(PLUGINS)) {
    const dir = resolve(PLUGINS, plugin, 'config', sub);
    if (!existsSync(dir)) continue;
    for (const f of readdirSync(dir)) if (f.endsWith('.json')) out.push(resolve(dir, f));
  }
  return out;
}

// 1. Build convention map from commands.json: model -> type -> code (unambiguous only).
const byModelType = new Map(); // `${model}::${type}` -> Set<code>
for (const file of listFiles('commands')) {
  const doc = readJson(file);
  if (!doc) continue;
  const items = Array.isArray(doc) ? doc : Array.isArray(doc.commands) ? doc.commands : [doc];
  for (const c of items) {
    const model = c?.modelCode;
    const type = c?.type;
    const code = c?.code;
    if (!model || !code || !CRUD.has(type)) continue;
    const key = `${model}::${type}`;
    if (!byModelType.has(key)) byModelType.set(key, new Set());
    byModelType.get(key).add(code);
  }
}
const convention = new Map(); // model -> {create?,update?,delete?} (unambiguous)
for (const [key, codes] of byModelType) {
  if (codes.size !== 1) continue; // ambiguous → skip
  const [model, type] = key.split('::');
  if (!convention.has(model)) convention.set(model, {});
  convention.get(model)[type] = [...codes][0];
}

// Expected convention command(s) for a button code.
function expectedFor(code, conv) {
  switch (code) {
    case 'create': return conv.create ? [conv.create] : [];
    case 'edit': return conv.update ? [conv.update] : [];
    case 'submit':
    case 'save': return [conv.create, conv.update].filter(Boolean);
    case 'delete': return conv.delete ? [conv.delete] : [];
    default: return [];
  }
}

// 2. Scan pages for redundant commands.
const violations = [];
for (const file of listFiles('pages')) {
  const doc = readJson(file);
  if (!doc?.modelCode) continue;
  const conv = convention.get(doc.modelCode);
  if (!conv) continue;
  const visit = (node) => {
    if (Array.isArray(node)) { node.forEach(visit); return; }
    if (!node || typeof node !== 'object') return;
    const a = node.action;
    if (a && typeof a === 'object' && typeof a.command === 'string') {
      const expected = expectedFor(node.code, conv);
      if (expected.includes(a.command)) {
        violations.push({ file: basename(file), code: node.code, command: a.command });
      }
    }
    for (const v of Object.values(node)) if (v && typeof v === 'object') visit(v);
  };
  visit(doc);
}

if (violations.length === 0) {
  console.log('✅ DSL command convention check passed (no redundant CRUD commands on standard buttons).');
  process.exit(0);
}

console.error(`❌ DSL command convention check: ${violations.length} redundant command(s) found.\n`);
const roleLabel = (code) =>
  code === 'create' ? 'create' :
  code === 'delete' ? 'delete' :
  code === 'edit' ? 'update' : 'create/update';
for (const v of violations) {
  console.error(
    `  ${v.file}: button "${v.code}" hard-codes "${v.command}", which is the model's ` +
      `convention ${roleLabel(v.code)} command.`,
  );
}
console.error(
  '\nDrop the `command` from these buttons — the platform resolves the CRUD command by ' +
    'convention (page-schema `commands` map). Keep an explicit command only for ' +
    'variants, state-transition, cross-model, or ambiguous-model actions.',
);
process.exit(1);
