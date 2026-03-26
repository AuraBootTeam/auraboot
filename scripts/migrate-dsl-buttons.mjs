#!/usr/bin/env node
/**
 * Migration script: batch-convert legacy button configs to unified action format.
 *
 * Transforms:
 *   1. "action": "edit" (string)          → "label": "edit"
 *   2. "commandCode": "x"                 → "action": { "type": "command", "command": "x" }
 *   3. "navigateTo": "page"               → "action": { "type": "navigate", "to": "page" }
 *   4. commandCode + navigateTo           → "action": { "type": "navigate", "to": "page", "command": "x" }
 *   5. "apiAction": {...}                 → "action": { "type": "flow", "steps": [...] }
 *   6. "events": { onClick: { handler } } → "action": { "type": "flow", "handler" } or { "type": "builtin", "name": "back" }
 *   7. "confirmMessageKey": "key"         → "confirm": "key"
 *   8. "defaultFilter": {...}             → "defaultFilters": [...]
 *   9. "rowClickNavigateTo": "..."        → deleted
 *
 * Usage:
 *   node scripts/migrate-dsl-buttons.mjs --dry-run
 *   node scripts/migrate-dsl-buttons.mjs
 */
import { readFileSync, writeFileSync, readdirSync, statSync } from 'fs';
import { join, relative } from 'path';

const DRY_RUN = process.argv.includes('--dry-run');
const PLUGINS_DIR = join(import.meta.dirname, '..', 'plugins');

const SKIP_FILENAMES = new Set([
  'plugin.json',
  'permissions.json',
  'roles.json',
  'menus.json',
  'commands.json',
  'fields.json',
  'models.json',
  'named-queries.json',
  'default-bootstrap.json',
  'bindings.json',
  'bindingRules.json',
  'dicts.json',
  'i18n.json',
  'processes.json',
  'reports.json',
  'routes.json',
  'saved-views.json',
]);

// Builtin button codes that should NOT get an explicit action object
// when they only have a code + optional action string label
const BUILTIN_CODES = new Set([
  'search', 'reset', 'export', 'import', 'cancel', 'back',
]);

const stats = {
  filesScanned: 0,
  filesModified: 0,
  actionStringRenamed: 0,
  commandCodeMigrated: 0,
  navigateToMigrated: 0,
  commandPlusNavigate: 0,
  apiActionMigrated: 0,
  eventsMigrated: 0,
  confirmMigrated: 0,
  defaultFilterMigrated: 0,
  rowClickRemoved: 0,
};

// ---------------------------------------------------------------------------
// File discovery
// ---------------------------------------------------------------------------
function findJsonFiles(dir) {
  const results = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const st = statSync(full);
    if (st.isDirectory()) {
      results.push(...findJsonFiles(full));
    } else if (entry.endsWith('.json') && !SKIP_FILENAMES.has(entry)) {
      results.push(full);
    }
  }
  return results;
}

// ---------------------------------------------------------------------------
// Button migration
// ---------------------------------------------------------------------------
function migrateButton(btn) {
  let changed = false;

  // 1. "action": "string" → "label": "string"
  if (typeof btn.action === 'string') {
    btn.label = btn.action;
    delete btn.action;
    stats.actionStringRenamed++;
    changed = true;
  }

  // 7. confirmMessageKey → confirm (do before action build so we can delete)
  if (btn.confirmMessageKey) {
    btn.confirm = btn.confirmMessageKey;
    delete btn.confirmMessageKey;
    stats.confirmMigrated++;
    changed = true;
  }

  // 6. events.onClick.handler
  if (btn.events?.onClick?.handler) {
    const handler = btn.events.onClick.handler;
    if (handler === 'navigateBack') {
      btn.action = { type: 'builtin', name: 'back' };
    } else {
      btn.action = { type: 'flow', handler };
    }
    delete btn.events;
    stats.eventsMigrated++;
    changed = true;
  }

  // 5. apiAction → action: { type: "flow", steps: [...] }
  if (btn.apiAction) {
    const step = { ...btn.apiAction };
    btn.action = { type: 'flow', steps: [step] };
    delete btn.apiAction;
    stats.apiActionMigrated++;
    changed = true;
  }

  // 2/3/4. commandCode and/or navigateTo
  const hasCmd = 'commandCode' in btn;
  const hasNav = 'navigateTo' in btn;

  if (hasCmd && hasNav) {
    // 4. both
    btn.action = { type: 'navigate', to: btn.navigateTo, command: btn.commandCode };
    delete btn.commandCode;
    delete btn.navigateTo;
    stats.commandPlusNavigate++;
    changed = true;
  } else if (hasCmd) {
    // 2. command only
    btn.action = { type: 'command', command: btn.commandCode };
    delete btn.commandCode;
    stats.commandCodeMigrated++;
    changed = true;
  } else if (hasNav) {
    // 3. navigate only
    btn.action = { type: 'navigate', to: btn.navigateTo };
    delete btn.navigateTo;
    stats.navigateToMigrated++;
    changed = true;
  }

  return changed;
}

// ---------------------------------------------------------------------------
// defaultFilter → defaultFilters normalization
// ---------------------------------------------------------------------------
function normalizeDefaultFilter(filter) {
  // Already structured: { fieldName, operator, value }
  if (filter.fieldName && filter.operator) {
    return [filter];
  }
  // Alternate structured: { field, operator, value }
  if (filter.field && filter.operator) {
    return [{ fieldName: filter.field, operator: filter.operator, value: filter.value }];
  }
  // Bare KV format: { status: "ACTIVE", category: "SALES" }
  const filters = [];
  for (const [key, value] of Object.entries(filter)) {
    filters.push({ fieldName: key, operator: 'EQ', value });
  }
  return filters;
}

// ---------------------------------------------------------------------------
// Recursive tree walker
// ---------------------------------------------------------------------------
function walkAndMigrate(obj) {
  if (Array.isArray(obj)) {
    let changed = false;
    for (const item of obj) {
      if (walkAndMigrate(item)) changed = true;
    }
    return changed;
  }

  if (obj === null || typeof obj !== 'object') return false;

  let changed = false;

  // Migrate buttons, rowActions, and actions (sub-table) arrays
  for (const arrayKey of ['buttons', 'rowActions', 'actions']) {
    if (Array.isArray(obj[arrayKey])) {
      for (const btn of obj[arrayKey]) {
        if (migrateButton(btn)) changed = true;
      }
    }
  }

  // Migrate single button objects (e.g. subTable.addButton, blocks[].addButton)
  if (obj.addButton && typeof obj.addButton === 'object' && !Array.isArray(obj.addButton)) {
    if (migrateButton(obj.addButton)) changed = true;
  }

  // defaultFilter → defaultFilters (block-level property)
  if (obj.defaultFilter && !obj.defaultFilters) {
    obj.defaultFilters = normalizeDefaultFilter(obj.defaultFilter);
    delete obj.defaultFilter;
    stats.defaultFilterMigrated++;
    changed = true;
  } else if (obj.defaultFilter && obj.defaultFilters) {
    // Already has defaultFilters — just remove the old one
    delete obj.defaultFilter;
    changed = true;
  }

  // rowClickNavigateTo → remove
  if ('rowClickNavigateTo' in obj) {
    delete obj.rowClickNavigateTo;
    stats.rowClickRemoved++;
    changed = true;
  }

  // Recurse into all child objects
  for (const key of Object.keys(obj)) {
    const val = obj[key];
    if (val && typeof val === 'object') {
      if (walkAndMigrate(val)) changed = true;
    }
  }

  return changed;
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
function main() {
  console.log(`${DRY_RUN ? '[DRY RUN] ' : ''}Migrating button configs in: ${PLUGINS_DIR}\n`);

  const pluginDirs = readdirSync(PLUGINS_DIR).filter(d => {
    const configDir = join(PLUGINS_DIR, d, 'config');
    try { return statSync(configDir).isDirectory(); } catch { return false; }
  });

  const modifiedFiles = [];

  for (const plugin of pluginDirs) {
    const configDir = join(PLUGINS_DIR, plugin, 'config');
    const jsonFiles = findJsonFiles(configDir);

    for (const file of jsonFiles) {
      stats.filesScanned++;
      try {
        const raw = readFileSync(file, 'utf-8');
        const data = JSON.parse(raw);

        if (walkAndMigrate(data)) {
          stats.filesModified++;
          const rel = relative(PLUGINS_DIR, file);
          modifiedFiles.push(rel);

          if (!DRY_RUN) {
            writeFileSync(file, JSON.stringify(data, null, 2) + '\n', 'utf-8');
          }
        }
      } catch (err) {
        console.error(`  ERROR parsing ${file}: ${err.message}`);
      }
    }
  }

  // Summary
  console.log('=== Migration Summary ===');
  console.log(`Files scanned:           ${stats.filesScanned}`);
  console.log(`Files modified:          ${stats.filesModified}`);
  console.log(`---`);
  console.log(`action string → label:   ${stats.actionStringRenamed}`);
  console.log(`commandCode → action:    ${stats.commandCodeMigrated}`);
  console.log(`navigateTo → action:     ${stats.navigateToMigrated}`);
  console.log(`cmd+nav → action:        ${stats.commandPlusNavigate}`);
  console.log(`apiAction → flow:        ${stats.apiActionMigrated}`);
  console.log(`events → action:         ${stats.eventsMigrated}`);
  console.log(`confirmMessageKey:       ${stats.confirmMigrated}`);
  console.log(`defaultFilter:           ${stats.defaultFilterMigrated}`);
  console.log(`rowClickNavigateTo:      ${stats.rowClickRemoved}`);
  const total = stats.actionStringRenamed + stats.commandCodeMigrated +
    stats.navigateToMigrated + stats.commandPlusNavigate + stats.apiActionMigrated +
    stats.eventsMigrated + stats.confirmMigrated + stats.defaultFilterMigrated +
    stats.rowClickRemoved;
  console.log(`---`);
  console.log(`Total transformations:   ${total}`);

  if (DRY_RUN) {
    console.log(`\nFiles that would be modified:`);
    for (const f of modifiedFiles) {
      console.log(`  ${f}`);
    }
    console.log(`\nRe-run without --dry-run to apply changes.`);
  } else {
    console.log(`\nDone. ${stats.filesModified} files updated.`);
  }
}

main();
