#!/usr/bin/env node

/**
 * Migration script: pcba-wms -> inventory
 *
 * 1. Copies plugins/pcba-wms/ to plugins/inventory/ (removes existing first)
 * 2. Excludes PCBA-specific models: pe_wave, pe_msd_record
 * 3. Adds 3 models from pcba-base: pe_warehouse, pe_warehouse_location, pe_inventory
 * 4. Renames all pe_ prefixes to inv_ with ordered replacements
 * 5. Updates plugin.json, menus.json, default-bootstrap.json
 *
 * Idempotent: removes existing inventory/ directory before copying.
 */

import { existsSync, cpSync, rmSync, readFileSync, writeFileSync, renameSync, statSync, readdirSync, mkdirSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';

const PLUGINS_DIR = new URL('..', import.meta.url).pathname;
const SRC_DIR = join(PLUGINS_DIR, 'pcba-wms');
const DST_DIR = join(PLUGINS_DIR, 'inventory');
const PCBA_BASE_DIR = join(PLUGINS_DIR, 'pcba-base');

// ── Summary counters ─────────────────────────────────────────────────
const summary = {
  filesTransformed: 0,
  filesRenamed: 0,
  contentReplacements: 0,
  filesCopiedFromBase: 0,
  filesDeleted: 0,
  errors: [],
};

// ── Excluded models (PCBA-specific) ─────────────────────────────────
const EXCLUDED_MODELS = ['pe_wave', 'pe_msd_record'];
const EXCLUDED_FIELD_PREFIXES = ['pe_wave_', 'pe_msd_'];
const EXCLUDED_COMMAND_PATTERNS = ['wave', 'msd'];

// ── Files to copy from pcba-base ────────────────────────────────────
const BASE_FIELD_FILES = [
  'pe_warehouse_name.json', 'pe_warehouse_code.json', 'pe_warehouse_type.json',
  'pe_warehouse_address.json', 'pe_warehouse_status.json',
  // pe_inv_* fields
  'pe_inv_amount.json', 'pe_inv_available_qty.json', 'pe_inv_avg_cost.json',
  'pe_inv_location_id.json', 'pe_inv_lot_id.json', 'pe_inv_product_id.json',
  'pe_inv_product_name.json', 'pe_inv_qty.json', 'pe_inv_reserved_qty.json',
  'pe_inv_safety_stock.json', 'pe_inv_spec.json', 'pe_inv_unit.json',
  'pe_inv_warehouse_id.json',
  // pe_wl_* fields
  'pe_wl_code.json', 'pe_wl_name.json', 'pe_wl_status.json', 'pe_wl_warehouse_id.json',
  // pe_loc_* fields
  'pe_loc_address.json', 'pe_loc_capacity.json', 'pe_loc_parent_id.json',
  'pe_loc_priority.json', 'pe_loc_status.json', 'pe_loc_type.json', 'pe_loc_zone_type.json',
];

const BASE_COMMAND_FILES = [
  'pe_create_warehouse.json', 'pe_update_warehouse.json', 'pe_delete_warehouse.json',
  'pe_create_warehouse_location.json', 'pe_update_warehouse_location.json', 'pe_delete_warehouse_location.json',
];

const BASE_PAGE_FILES = [
  'pe_warehouse_list.json', 'pe_warehouse_form.json',
  'pe_warehouse_location_list.json', 'pe_warehouse_location_form.json',
  'pe_inventory_list.json', 'pe_inventory_dashboard.json',
];

const BASE_BINDING_FILES = [
  'pe_warehouse.json', 'pe_warehouse_location.json', 'pe_inventory.json',
];

// ── Content replacement rules (ORDER MATTERS — longer strings first) ──
const CONTENT_RULES = [
  // ─── Model codes (longer first to avoid partial matches) ───
  ['pe_warehouse_in_line', 'inv_inbound_line'],
  ['pe_warehouse_in', 'inv_inbound'],
  ['pe_warehouse_out_line', 'inv_outbound_line'],
  ['pe_warehouse_out', 'inv_outbound'],
  ['pe_warehouse_location', 'inv_warehouse_location'],
  ['pe_warehouse', 'inv_warehouse'],
  ['pe_stock_transfer_line', 'inv_transfer_line'],
  ['pe_stock_transfer', 'inv_transfer'],
  ['pe_stock_check_line', 'inv_stock_check_line'],
  ['pe_stock_check', 'inv_stock_check'],
  ['pe_lot_transaction', 'inv_lot_transaction'],
  ['pe_lot', 'inv_lot'],
  ['pe_pick_order_line', 'inv_pick_order_line'],
  ['pe_pick_order', 'inv_pick_order'],
  ['pe_inventory_hold', 'inv_inventory_hold'],
  ['pe_inventory_dashboard', 'inv_inventory_dashboard'],
  ['pe_inventory_list', 'inv_inventory_list'],
  ['pe_inventory', 'inv_balance'],

  // ─── Field code prefixes (longer first) ───
  ['pe_wh_in_line_', 'inv_in_line_'],
  ['pe_wh_in_', 'inv_in_'],
  ['pe_wh_out_line_', 'inv_out_line_'],
  ['pe_wh_out_', 'inv_out_'],
  ['pe_wh_', 'inv_wh_'],
  ['pe_st_line_', 'inv_st_line_'],
  ['pe_st_', 'inv_st_'],
  ['pe_sc_line_', 'inv_sc_line_'],
  ['pe_sc_', 'inv_sc_'],
  ['pe_lt_', 'inv_lt_'],
  ['pe_lot_', 'inv_lot_'],
  ['pe_pkl_', 'inv_pkl_'],
  ['pe_pick_', 'inv_pick_'],
  ['pe_ih_', 'inv_ih_'],
  ['pe_inv_', 'inv_bal_'],
  ['pe_wl_', 'inv_wl_'],
  ['pe_loc_', 'inv_loc_'],
  ['pe_warehouse_', 'inv_warehouse_'],
  ['pe_wi_', 'inv_wi_'],
  ['pe_wout_', 'inv_wout_'],
  ['pe_pk_', 'inv_pk_'],
  ['pe_wms_dashboard', 'inv_wms_dashboard'],

  // ─── Dict codes ───
  ['pe_warehouse_type', 'inv_warehouse_type'],
  ['pe_warehouse_status', 'inv_warehouse_status'],
  ['pe_lot_policy', 'inv_lot_policy'],
  ['pe_lot_type', 'inv_lot_type'],
  ['pe_wh_in_type', 'inv_in_type'],
  ['pe_wh_out_type', 'inv_out_type'],

  // ─── Command names (pe_create/update/delete -> inv_create/update/delete) ───
  ['pe_create_', 'inv_create_'],
  ['pe_update_', 'inv_update_'],
  ['pe_delete_', 'inv_delete_'],
  ['pe_confirm_', 'inv_confirm_'],
  ['pe_cancel_', 'inv_cancel_'],
  ['pe_submit_', 'inv_submit_'],
  ['pe_approve_', 'inv_approve_'],
  ['pe_allocate_', 'inv_allocate_'],
  ['pe_auto_', 'inv_auto_'],
  ['pe_complete_', 'inv_complete_'],
  ['pe_generate_', 'inv_generate_'],
  ['pe_hold_', 'inv_hold_'],
  ['pe_release_', 'inv_release_'],
  ['pe_quarantine_', 'inv_quarantine_'],
  ['pe_scrap_', 'inv_scrap_'],
  ['pe_start_', 'inv_start_'],
  ['pe_trace_', 'inv_trace_'],
  ['pe_transfer_', 'inv_transfer_'],
  ['pe_add_', 'inv_add_'],
  ['pe_expire_', 'inv_expire_'],

  // ─── Permissions ───
  ['PE.warehouse', 'INV.warehouse'],
  ['PE.inventory_hold', 'INV.inventory_hold'],
  ['PE.wms.pick', 'INV.pick'],
  ['PE.wms.lot', 'INV.lot'],
  ['PE.quality.msd', 'INV.msd'],

  // ─── Role codes ───
  ['PE_ADMIN', 'INV_ADMIN'],
  ['PE_WAREHOUSE', 'INV_WAREHOUSE'],
  ['PE_SALES', 'INV_SALES'],
  ['PE_PURCHASER', 'INV_PURCHASER'],
  ['PE_PRODUCTION', 'INV_PRODUCTION'],
  ['PE_QUALITY_ENGINEER', 'INV_QUALITY_ENGINEER'],

  // ─── Menu codes ───
  ['pe_wms_dir', 'inv_wms_dir'],
  ['pe_warehouses', 'inv_warehouses'],
  ['pe_warehouse_locations', 'inv_warehouse_locations'],
  ['pe_stock_transfers', 'inv_stock_transfers'],
  ['pe_stock_checks', 'inv_stock_checks'],
  ['pe_inventory_holds', 'inv_inventory_holds'],
  ['pe_pick_orders', 'inv_pick_orders'],
  ['pe_lots', 'inv_lots'],
  ['pe_waves', 'inv_waves'],
  ['pe_msd_records', 'inv_msd_records'],
  ['pe_root', 'inv_root'],

  // ─── Module/category references ───
  ['pcba-erp', 'inventory'],
  ['pcba-wms', 'inventory'],
  ['PCBA ERP', 'Inventory'],
  ['/pcba-erp/', '/inventory/'],
  ['/pcba-erp"', '/inventory"'],
];

// ── FILE NAME replacement rules (same order logic) ──────────────────
const FILENAME_RULES = [
  // Model-level file renames
  ['pe_warehouse_in_line', 'inv_inbound_line'],
  ['pe_warehouse_in', 'inv_inbound'],
  ['pe_warehouse_out_line', 'inv_outbound_line'],
  ['pe_warehouse_out', 'inv_outbound'],
  ['pe_warehouse_location', 'inv_warehouse_location'],
  ['pe_warehouse', 'inv_warehouse'],
  ['pe_stock_transfer_line', 'inv_transfer_line'],
  ['pe_stock_transfer', 'inv_transfer'],
  ['pe_stock_check_line', 'inv_stock_check_line'],
  ['pe_stock_check', 'inv_stock_check'],
  ['pe_lot_transaction', 'inv_lot_transaction'],
  ['pe_lot', 'inv_lot'],
  ['pe_pick_order_line', 'inv_pick_order_line'],
  ['pe_pick_order', 'inv_pick_order'],
  ['pe_inventory_hold', 'inv_inventory_hold'],
  ['pe_inventory_dashboard', 'inv_inventory_dashboard'],
  ['pe_inventory_list', 'inv_inventory_list'],
  ['pe_inventory', 'inv_balance'],

  // Field prefixes in filenames
  ['pe_wh_in_line_', 'inv_in_line_'],
  ['pe_wh_in_', 'inv_in_'],
  ['pe_wh_out_line_', 'inv_out_line_'],
  ['pe_wh_out_', 'inv_out_'],
  ['pe_wh_', 'inv_wh_'],
  ['pe_st_line_', 'inv_st_line_'],
  ['pe_st_', 'inv_st_'],
  ['pe_sc_line_', 'inv_sc_line_'],
  ['pe_sc_', 'inv_sc_'],
  ['pe_lt_', 'inv_lt_'],
  ['pe_lot_', 'inv_lot_'],
  ['pe_pkl_', 'inv_pkl_'],
  ['pe_pick_', 'inv_pick_'],
  ['pe_ih_', 'inv_ih_'],
  ['pe_inv_', 'inv_bal_'],
  ['pe_wl_', 'inv_wl_'],
  ['pe_loc_', 'inv_loc_'],
  ['pe_warehouse_', 'inv_warehouse_'],

  // Command prefixes
  ['pe_create_', 'inv_create_'],
  ['pe_update_', 'inv_update_'],
  ['pe_delete_', 'inv_delete_'],
  ['pe_confirm_', 'inv_confirm_'],
  ['pe_cancel_', 'inv_cancel_'],
  ['pe_submit_', 'inv_submit_'],
  ['pe_approve_', 'inv_approve_'],
  ['pe_allocate_', 'inv_allocate_'],
  ['pe_auto_', 'inv_auto_'],
  ['pe_complete_', 'inv_complete_'],
  ['pe_generate_', 'inv_generate_'],
  ['pe_hold_', 'inv_hold_'],
  ['pe_release_', 'inv_release_'],
  ['pe_quarantine_', 'inv_quarantine_'],
  ['pe_scrap_', 'inv_scrap_'],
  ['pe_start_', 'inv_start_'],
  ['pe_trace_', 'inv_trace_'],
  ['pe_transfer_', 'inv_transfer_'],
  ['pe_add_', 'inv_add_'],
  ['pe_expire_', 'inv_expire_'],
  ['pe_wms_', 'inv_wms_'],
];

// ═══════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════

function applyReplacements(text, rules) {
  let count = 0;
  for (const [from, to] of rules) {
    const regex = new RegExp(escapeRegex(from), 'g');
    const matches = text.match(regex);
    if (matches) {
      count += matches.length;
      text = text.replace(regex, to);
    }
  }
  summary.contentReplacements += count;
  return text;
}

function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function applyFilenameReplacements(name) {
  for (const [from, to] of FILENAME_RULES) {
    if (name.includes(from)) {
      name = name.replace(from, to);
      break; // only apply first match for filenames
    }
  }
  return name;
}

function walkDir(dir) {
  const results = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      results.push(...walkDir(full));
    } else {
      results.push(full);
    }
  }
  return results;
}

function isExcludedFile(filePath) {
  const name = basename(filePath, '.json');
  // Check field files with excluded prefixes
  for (const prefix of EXCLUDED_FIELD_PREFIXES) {
    if (name.startsWith(prefix)) return true;
  }
  // Check command files
  for (const pattern of EXCLUDED_COMMAND_PATTERNS) {
    if (name.includes(pattern)) return true;
  }
  // Check page files
  if (name.startsWith('pe_wave') || name.startsWith('pe_msd')) return true;
  // Check binding files
  for (const model of EXCLUDED_MODELS) {
    if (name === model) return true;
  }
  return false;
}

function copyFileFromBase(subdir, fileName) {
  const src = join(PCBA_BASE_DIR, 'config', subdir, fileName);
  const dst = join(DST_DIR, 'config', subdir, fileName);
  if (existsSync(src)) {
    const dirPath = dirname(dst);
    if (!existsSync(dirPath)) mkdirSync(dirPath, { recursive: true });
    cpSync(src, dst);
    summary.filesCopiedFromBase++;
    return true;
  } else {
    summary.errors.push(`Base file not found: ${src}`);
    return false;
  }
}

// ═══════════════════════════════════════════════════════════════════════
// Main
// ═══════════════════════════════════════════════════════════════════════

console.log('=== Migration: pcba-wms -> inventory ===\n');

// Step 0: Validate source
if (!existsSync(SRC_DIR)) {
  console.error(`Source directory not found: ${SRC_DIR}`);
  process.exit(1);
}

// Step 1: Remove existing inventory/ and copy pcba-wms/
if (existsSync(DST_DIR)) {
  console.log('Removing existing inventory/ directory...');
  rmSync(DST_DIR, { recursive: true, force: true });
}
console.log('Copying pcba-wms/ -> inventory/...');
cpSync(SRC_DIR, DST_DIR, { recursive: true });

// Step 2: Delete excluded model files (wave, msd)
console.log('\nDeleting PCBA-specific files (wave, msd)...');
const configDir = join(DST_DIR, 'config');

// Delete excluded field files
for (const f of readdirSync(join(configDir, 'fields'))) {
  if (isExcludedFile(join(configDir, 'fields', f))) {
    rmSync(join(configDir, 'fields', f));
    summary.filesDeleted++;
    console.log(`  Deleted field: ${f}`);
  }
}

// Delete excluded command files
for (const f of readdirSync(join(configDir, 'commands'))) {
  if (isExcludedFile(join(configDir, 'commands', f))) {
    rmSync(join(configDir, 'commands', f));
    summary.filesDeleted++;
    console.log(`  Deleted command: ${f}`);
  }
}

// Delete excluded page files
for (const f of readdirSync(join(configDir, 'pages'))) {
  if (isExcludedFile(join(configDir, 'pages', f))) {
    rmSync(join(configDir, 'pages', f));
    summary.filesDeleted++;
    console.log(`  Deleted page: ${f}`);
  }
}

// Delete excluded binding files
for (const f of readdirSync(join(configDir, 'bindings'))) {
  if (isExcludedFile(join(configDir, 'bindings', f))) {
    rmSync(join(configDir, 'bindings', f));
    summary.filesDeleted++;
    console.log(`  Deleted binding: ${f}`);
  }
}

// Clean models.json - remove excluded models
const modelsPath = join(configDir, 'models.json');
let models = JSON.parse(readFileSync(modelsPath, 'utf8'));
const beforeCount = models.length;
models = models.filter(m => !EXCLUDED_MODELS.includes(m.code));
console.log(`  Removed ${beforeCount - models.length} excluded models from models.json`);

// Clean i18n.json - remove wave/msd entries
const i18nPath = join(configDir, 'i18n.json');
let i18nEntries = JSON.parse(readFileSync(i18nPath, 'utf8'));
const i18nBefore = i18nEntries.length;
i18nEntries = i18nEntries.filter(e => {
  const key = e.key || '';
  return !key.includes('wave') && !key.includes('msd') && !key.includes('pe_wave') && !key.includes('pe_msd');
});
console.log(`  Removed ${i18nBefore - i18nEntries.length} wave/msd i18n entries`);

// Clean dicts.json - remove wave/msd dicts
const dictsPath = join(configDir, 'dicts.json');
let dicts = JSON.parse(readFileSync(dictsPath, 'utf8'));
const dictsBefore = dicts.length;
dicts = dicts.filter(d => !d.code.includes('wave') && !d.code.includes('msd'));
console.log(`  Removed ${dictsBefore - dicts.length} wave/msd dicts`);

// Clean permissions.json - remove msd permissions
const permsPath = join(configDir, 'permissions.json');
let perms = JSON.parse(readFileSync(permsPath, 'utf8'));
const permsBefore = perms.length;
perms = perms.filter(p => !p.code.includes('msd'));
console.log(`  Removed ${permsBefore - perms.length} msd permissions`);

// Clean roles.json - remove msd permission references
const rolesPath = join(configDir, 'roles.json');
let roles = JSON.parse(readFileSync(rolesPath, 'utf8'));
for (const role of roles) {
  if (role.permissions) {
    role.permissions = role.permissions.filter(p => !p.includes('msd'));
  }
}

// Clean menus.json - remove wave and msd menu entries
const menusPath = join(configDir, 'menus.json');
let menus = JSON.parse(readFileSync(menusPath, 'utf8'));
const menusBefore = menus.length;
menus = menus.filter(m => {
  const code = m.code || '';
  return !code.includes('wave') && !code.includes('msd');
});
console.log(`  Removed ${menusBefore - menus.length} wave/msd menu entries`);

// Write back cleaned JSON files before adding base files
writeFileSync(modelsPath, JSON.stringify(models, null, 2) + '\n');
writeFileSync(i18nPath, JSON.stringify(i18nEntries, null, 2) + '\n');
writeFileSync(dictsPath, JSON.stringify(dicts, null, 2) + '\n');
writeFileSync(permsPath, JSON.stringify(perms, null, 2) + '\n');
writeFileSync(rolesPath, JSON.stringify(roles, null, 2) + '\n');
writeFileSync(menusPath, JSON.stringify(menus, null, 2) + '\n');

// Step 3: Add files from pcba-base
console.log('\nCopying files from pcba-base...');

for (const f of BASE_FIELD_FILES) {
  copyFileFromBase('fields', f);
}
for (const f of BASE_COMMAND_FILES) {
  copyFileFromBase('commands', f);
}
for (const f of BASE_PAGE_FILES) {
  copyFileFromBase('pages', f);
}
for (const f of BASE_BINDING_FILES) {
  copyFileFromBase('bindings', f);
}
console.log(`  Copied ${summary.filesCopiedFromBase} files from pcba-base`);

// Add model entries for warehouse, warehouse_location, inventory
const baseModels = JSON.parse(readFileSync(join(PCBA_BASE_DIR, 'config', 'models.json'), 'utf8'));
const modelsToAdd = ['pe_warehouse', 'pe_warehouse_location', 'pe_inventory'];
for (const code of modelsToAdd) {
  const model = baseModels.find(m => m.code === code);
  if (model) {
    models.push(model);
    console.log(`  Added model: ${code}`);
  } else {
    summary.errors.push(`Model not found in pcba-base: ${code}`);
  }
}
writeFileSync(modelsPath, JSON.stringify(models, null, 2) + '\n');

// Add i18n entries from pcba-base for warehouse/inventory models
const baseI18n = JSON.parse(readFileSync(join(PCBA_BASE_DIR, 'config', 'i18n.json'), 'utf8'));
const relevantI18n = baseI18n.filter(e => {
  const key = e.key || '';
  return key.includes('warehouse') || key.includes('inventory') ||
         key.includes('pe_inv') || key.includes('pe_wl') || key.includes('pe_loc');
});
i18nEntries.push(...relevantI18n);
console.log(`  Added ${relevantI18n.length} i18n entries from pcba-base`);
writeFileSync(i18nPath, JSON.stringify(i18nEntries, null, 2) + '\n');

// Add warehouse dicts from pcba-base
const baseDicts = JSON.parse(readFileSync(join(PCBA_BASE_DIR, 'config', 'dicts.json'), 'utf8'));
const warehouseDicts = baseDicts.filter(d => d.code.includes('warehouse'));
dicts.push(...warehouseDicts);
console.log(`  Added ${warehouseDicts.length} warehouse dicts from pcba-base`);
writeFileSync(dictsPath, JSON.stringify(dicts, null, 2) + '\n');

// Step 4: Apply content replacements to ALL JSON files
console.log('\nApplying content replacements...');
const allJsonFiles = walkDir(join(DST_DIR, 'config')).filter(f => f.endsWith('.json'));

for (const filePath of allJsonFiles) {
  try {
    const content = readFileSync(filePath, 'utf8');
    const transformed = applyReplacements(content, CONTENT_RULES);
    if (transformed !== content) {
      writeFileSync(filePath, transformed);
      summary.filesTransformed++;
    }
  } catch (err) {
    summary.errors.push(`Error transforming ${filePath}: ${err.message}`);
  }
}

// Step 4b: Post-process dashboard pages — remove blocks referencing excluded models
console.log('Cleaning dashboard pages (removing excluded model blocks)...');
const dashboardFiles = walkDir(join(configDir, 'pages')).filter(f => {
  const content = readFileSync(f, 'utf8');
  return content.includes('"DASHBOARD"') || content.includes('"Dashboard"');
});
for (const dashFile of dashboardFiles) {
  try {
    const dashData = JSON.parse(readFileSync(dashFile, 'utf8'));
    if (dashData.dslSchema?.areas) {
      for (const [areaName, area] of Object.entries(dashData.dslSchema.areas)) {
        if (area.blocks) {
          const before = area.blocks.length;
          area.blocks = area.blocks.filter(block => {
            // Remove blocks referencing excluded models
            if (block.modelCode && (block.modelCode === 'pe_wave' || block.modelCode.includes('msd'))) {
              return false;
            }
            return true;
          });
          if (area.blocks.length < before) {
            console.log(`  Removed ${before - area.blocks.length} excluded-model blocks from ${basename(dashFile)}`);
          }
        }
      }
    }
    // Fix stale pe_wms id
    if (dashData.dslSchema?.id === 'dashboard.pe_wms') {
      dashData.dslSchema.id = 'dashboard.inv_wms';
    }
    writeFileSync(dashFile, JSON.stringify(dashData, null, 2) + '\n');
  } catch (err) {
    summary.errors.push(`Error cleaning dashboard ${dashFile}: ${err.message}`);
  }
}

// Step 5: Rename files
console.log('Renaming files...');
// Process each subdirectory
for (const subdir of ['fields', 'commands', 'pages', 'bindings']) {
  const dirPath = join(configDir, subdir);
  if (!existsSync(dirPath)) continue;

  const files = readdirSync(dirPath).sort().reverse(); // longer names first naturally
  for (const f of files) {
    const newName = applyFilenameReplacements(f);
    if (newName !== f) {
      const oldPath = join(dirPath, f);
      const newPath = join(dirPath, newName);
      renameSync(oldPath, newPath);
      summary.filesRenamed++;
    }
  }
}

// Step 6: Write plugin.json
console.log('\nWriting plugin.json...');
const pluginJson = {
  "pluginId": "com.auraboot.inventory",
  "namespace": "inv",
  "version": "1.0.0",
  "dslVersion": 1,
  "pluginType": "config",
  "displayName:zh-CN": "库存管理",
  "displayName:en": "Inventory Management",
  "description": "Generic inventory management: warehouse, inbound, outbound, transfers, stock checks, lots, picking",
  "author": "AuraBoot Team",
  "homepage": "https://auraboot.com/plugins/inventory",
  "minPlatformVersion": "1.0.0",
  "dependencies": [
    "com.auraboot.org-management"
  ],
  "resourceDirs": {
    "models": "config/models.json",
    "fields": "config/fields",
    "modelFieldBindings": "config/bindings",
    "commands": "config/commands",
    "pages": "config/pages",
    "dicts": "config/dicts.json",
    "permissions": "config/permissions.json",
    "roles": "config/roles.json",
    "menus": "config/menus.json",
    "i18n": "config/i18n.json"
  },
  "importOptions": {
    "conflictStrategy": "OVERWRITE",
    "validateReferences": true,
    "autoDeployProcesses": false,
    "createResourcePermissions": false,
    "autoPublishPages": false
  }
};
writeFileSync(join(DST_DIR, 'plugin.json'), JSON.stringify(pluginJson, null, 2) + '\n');

// ═══════════════════════════════════════════════════════════════════════
// Summary
// ═══════════════════════════════════════════════════════════════════════

console.log('\n=== Migration Complete ===');
console.log(`  Files copied from pcba-base: ${summary.filesCopiedFromBase}`);
console.log(`  Files deleted (wave/msd):    ${summary.filesDeleted}`);
console.log(`  Files with content changes:  ${summary.filesTransformed}`);
console.log(`  Files renamed:               ${summary.filesRenamed}`);
console.log(`  Total content replacements:  ${summary.contentReplacements}`);

if (summary.errors.length > 0) {
  console.log(`\n  ERRORS (${summary.errors.length}):`);
  for (const e of summary.errors) {
    console.log(`    - ${e}`);
  }
}

// Final verification
console.log('\n=== Verification ===');
const finalFiles = walkDir(DST_DIR);
const peRemaining = finalFiles.filter(f => {
  const name = basename(f);
  return name.startsWith('pe_') && name.endsWith('.json');
});
if (peRemaining.length > 0) {
  console.log(`WARNING: ${peRemaining.length} files still have pe_ prefix:`);
  for (const f of peRemaining) {
    console.log(`  ${f}`);
  }
} else {
  console.log('  All files renamed successfully (no pe_ prefixes remaining)');
}

// Check for pe_ references in content
let peContentCount = 0;
for (const f of finalFiles.filter(f => f.endsWith('.json'))) {
  const content = readFileSync(f, 'utf8');
  // Look for pe_ that's not part of "type" or other safe patterns
  const matches = content.match(/\bpe_[a-z]/g);
  if (matches) {
    peContentCount += matches.length;
    if (peContentCount <= 10) {
      console.log(`  pe_ reference in ${basename(f)}: ${matches.slice(0, 3).join(', ')}${matches.length > 3 ? '...' : ''}`);
    }
  }
}
if (peContentCount > 0) {
  console.log(`  WARNING: ${peContentCount} pe_ references remaining in file contents`);
} else {
  console.log('  All pe_ content references replaced');
}

console.log('\nDone!');
