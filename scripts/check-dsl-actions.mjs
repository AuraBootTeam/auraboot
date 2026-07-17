#!/usr/bin/env node
/**
 * check-dsl-actions.mjs — a workbench button may only ask for an action the runtime runs.
 *
 * Why this gate exists (2026-07-15, conversation → CS 坐席台 / ENT#784):
 *
 * `onClick: { action: { type: "api", endpoint, body } }` was written on a workbench button.
 * The workbench executor recognizes only the STRING verbs state.set / dataSource.reload /
 * navigate / command.execute, and silently no-op'd the unknown shape: DSL validation green,
 * page rendered, import validator success, zero runtime signal, dead button. Then the fix used
 * `args.refresh` for the reload key — but the runtime reads `args.reload`; `refresh` was silently
 * ignored. Both guesses came from the OTHER two action dialects (table rowActions / ActionRegistry),
 * where `api.request` and `refresh` are real. Prose could not stop it; a script that fails does.
 *
 * The check is deliberately narrow so it cannot cry wolf:
 *   - it only gates the workbench-action-bar dialect (the one that shipped the bug),
 *   - the legal verb set and the legal arg-key set are derived from the runtime source itself
 *     (via sync-dsl-action-catalog.mjs), so the gate cannot drift from what actually runs, and
 *   - the arg-key set IS exactly the set the executor reads, so an "unknown arg" is a true
 *     positive (the runtime would silently ignore it), never a stylistic nit.
 *
 * It also fails if the committed dsl-action-catalog.json (the surface agents read) is stale.
 *
 * Usage: node scripts/check-dsl-actions.mjs   (exit 0 = clean, 1 = problems, 2 = parser broke)
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildCatalog } from './sync-dsl-action-catalog.mjs';

const REPO_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const PLUGIN_ROOT = path.join(REPO_ROOT, 'plugins');
const CATALOG_FILE = path.join(PLUGIN_ROOT, 'schemas/dsl-action-catalog.json');
const WORKBENCH_BLOCK_TYPE = 'workbench-action-bar';
// Keys that betray the wrong dialect (ActionRegistry / api shape) pasted into a workbench onClick.
const FOREIGN_SHAPE_KEYS = ['type', 'endpoint', 'body', 'method'];

function rel(file) {
  return path.relative(REPO_ROOT, file);
}

/** Walk every JSON file under plugins/, skipping generated/tooling dirs. */
function jsonFiles(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'schemas' || entry.name === 'cli') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) jsonFiles(full, out);
    else if (entry.isFile() && entry.name.endsWith('.json')) out.push(full);
  }
  return out;
}

/** Visit every object node in a parsed JSON tree. */
function visit(node, fn) {
  if (Array.isArray(node)) {
    for (const item of node) visit(item, fn);
  } else if (node && typeof node === 'object') {
    fn(node);
    for (const value of Object.values(node)) visit(value, fn);
  }
}

function checkWorkbenchAction(action, legalVerbs, legalArgKeys, where, errors) {
  const oc = action?.onClick;
  const label = action?.code || action?.id || action?.label || '(unnamed action)';
  const at = `${where} action "${typeof label === 'object' ? JSON.stringify(label) : label}"`;
  if (oc === undefined || oc === null) return; // display-only entry — not our concern

  // Wrong shape: object-valued action, or an api/ActionRegistry shape with no string verb.
  if (oc.action !== undefined && typeof oc.action !== 'string') {
    errors.push(
      `${at}: onClick.action must be a STRING verb, got ${typeof oc.action}. ` +
        `A workbench action is { action: "command.execute", args: {…} }, not the object ` +
        `{ type, … } shape used by table rowActions / ActionRegistry.`,
    );
    return;
  }
  if (oc.action === undefined) {
    const foreign = FOREIGN_SHAPE_KEYS.filter((k) => k in oc);
    if (foreign.length > 0) {
      errors.push(
        `${at}: onClick has no string "action" verb but carries ${foreign.join('/')} — that is an ` +
          `api/ActionRegistry shape. There is no "api" workbench action; model a write as ` +
          `{ action: "command.execute", args: { command, targetRecordPid, reload } }.`,
      );
    }
    return;
  }

  // Unknown verb (catches a bare "api").
  if (!legalVerbs.has(oc.action)) {
    errors.push(
      `${at}: unknown workbench action "${oc.action}". Legal: ${[...legalVerbs].join(' | ')}. ` +
        `(There is no "api" action — a backend write is command.execute.)`,
    );
    return;
  }

  const args = oc.args;

  if (oc.action === 'command.execute') {
    const hasCommand = args && (args.command || args.commandCode);
    if (!hasCommand) {
      errors.push(`${at}: command.execute requires args.command (or args.commandCode).`);
    }
  }

  // Unknown top-level arg keys are silently ignored by the runtime — the exact `refresh` footgun.
  // state.set writes arbitrary state keys, so its args are free-form and NOT validated.
  if (oc.action !== 'state.set' && args && typeof args === 'object' && !Array.isArray(args)) {
    for (const key of Object.keys(args)) {
      if (!legalArgKeys.has(key)) {
        const hint = key === 'refresh' ? ` Did you mean "reload"? (the workbench reload key)` : '';
        errors.push(
          `${at}: unknown arg "${key}" on ${oc.action} — the runtime reads none of it and ` +
            `silently ignores it.${hint}`,
        );
      }
    }
  }
}

function main() {
  const errors = [];

  // 1. The catalog agents read must match the runtime source.
  const catalog = buildCatalog();
  const serialized = `${JSON.stringify(catalog, null, 2)}\n`;
  if (!fs.existsSync(CATALOG_FILE) || fs.readFileSync(CATALOG_FILE, 'utf8') !== serialized) {
    errors.push(
      `${rel(CATALOG_FILE)} is stale or missing — the query surface agents read no longer matches ` +
        `the runtime source. Regenerate: node scripts/sync-dsl-action-catalog.mjs`,
    );
  }

  const wb = catalog.surfaces[WORKBENCH_BLOCK_TYPE];
  const legalVerbs = new Set(wb.verbs);
  const legalArgKeys = new Set(wb.recognizedArgKeys);

  // 2. Every workbench-action-bar block in every plugin page.
  for (const file of jsonFiles(PLUGIN_ROOT)) {
    let doc;
    try {
      doc = JSON.parse(fs.readFileSync(file, 'utf8'));
    } catch {
      continue; // not a JSON document we can read — nothing to check
    }
    visit(doc, (node) => {
      const blockType = node.blockType || node.type;
      if (blockType !== WORKBENCH_BLOCK_TYPE || !Array.isArray(node.actions)) return;
      for (const action of node.actions) {
        checkWorkbenchAction(action, legalVerbs, legalArgKeys, rel(file), errors);
      }
    });
  }

  if (errors.length > 0) {
    console.error(`✗ check-dsl-actions: ${errors.length} problem(s)\n`);
    for (const e of errors) console.error(`  - ${e}`);
    console.error(
      `\nWorkbench action contract: plugins/schemas/dsl-action-catalog.json ` +
        `(regenerate with scripts/sync-dsl-action-catalog.mjs).`,
    );
    process.exit(1);
  }
  console.log('✓ check-dsl-actions: all workbench actions are legal, catalog fresh.');
}

main();
