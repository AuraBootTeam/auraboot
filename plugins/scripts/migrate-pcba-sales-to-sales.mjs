#!/usr/bin/env node

/**
 * Migration script: pcba-sales -> sales
 *
 * Copies plugins/pcba-sales/ to plugins/sales/ and transforms all
 * model codes, field prefixes, command namespaces, permissions, dict codes,
 * menu codes, and file names from pe_ namespace to sl_ namespace.
 *
 * Idempotent: removes existing sales/ directory before copying.
 */

import { existsSync, cpSync, rmSync, readFileSync, writeFileSync, renameSync, statSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { readdirSync } from 'node:fs';

const PLUGINS_DIR = new URL('..', import.meta.url).pathname;
const SRC_DIR = join(PLUGINS_DIR, 'pcba-sales');
const DST_DIR = join(PLUGINS_DIR, 'sales');

// ── Summary counters ─────────────────────────────────────────────────
const summary = {
  filesTransformed: 0,
  filesRenamed: 0,
  contentReplacements: 0,
  errors: [],
};

// ── Content replacement rules (ORDER MATTERS — longer strings first) ──

const CONTENT_RULES = [
  // ─── Model codes (longer first) ───
  ['pe_sales_order_line', 'sl_sales_order_line'],
  ['pe_sales_quotation_line', 'sl_sales_quotation_line'],
  ['pe_sales_return_line', 'sl_sales_return_line'],
  ['pe_shipment_line', 'sl_shipment_line'],
  ['pe_packing_line', 'sl_packing_line'],
  ['pe_sales_order', 'sl_sales_order'],
  ['pe_sales_quotation', 'sl_sales_quotation'],
  ['pe_sales_return', 'sl_sales_return'],
  ['pe_sales_collection', 'sl_sales_collection'],
  ['pe_credit_memo', 'sl_credit_memo'],
  ['pe_order_change', 'sl_order_change'],
  ['pe_shipment', 'sl_shipment'],
  ['pe_packing', 'sl_packing'],
  ['pe_rma', 'sl_rma'],

  // ─── Field code prefixes (longer first to avoid partial matches) ───
  ['pe_so_line_', 'sl_sol_'],
  ['pe_sq_line_', 'sl_sql_'],
  ['pe_ship_line_', 'sl_shl_'],
  ['pe_sr_line_', 'sl_srl_'],
  ['pe_so_', 'sl_so_'],
  ['pe_sq_', 'sl_sq_'],
  ['pe_ship_', 'sl_sh_'],
  ['pe_sr_', 'sl_sr_'],
  ['pe_cm_', 'sl_cm_'],
  ['pe_oc_', 'sl_oc_'],
  ['pe_rma_', 'sl_rma_'],
  ['pe_col_', 'sl_col_'],
  ['pe_pack_', 'sl_pack_'],
  ['pe_pkln_', 'sl_pkln_'],

  // ─── Command namespace ───
  ['"pe:', '"sl:'],

  // ─── Permissions ───
  ['PE.sales', 'SL.sales'],
  ['PE.rma', 'SL.rma'],
  ['PE.financial', 'SL.financial'],
  ['PE.wms.packing', 'SL.packing'],

  // ─── Role codes ───
  ['PE_ADMIN', 'SL_ADMIN'],
  ['PE_WAREHOUSE', 'SL_WAREHOUSE'],
  ['PE_SALES', 'SL_SALES'],
  ['PE_CRM', 'SL_CRM'],
  ['PE_FINANCE', 'SL_FINANCE'],

  // ─── Menu codes ───
  ['pe_sales_dir', 'sl_sales_dir'],
  ['pe_sales_quotations', 'sl_sales_quotations'],
  ['pe_sales_orders', 'sl_sales_orders'],
  ['pe_sales_collections', 'sl_sales_collections'],
  ['pe_sales_returns', 'sl_sales_returns'],
  ['pe_shipments', 'sl_shipments'],
  ['pe_order_changes', 'sl_order_changes'],
  ['pe_rmas', 'sl_rmas'],
  ['pe_credit_memos', 'sl_credit_memos'],
  ['pe_packings', 'sl_packings'],
  ['pe_root', 'sl_root'],

  // ─── Module/category references ───
  ['pcba-erp', 'sales'],
  ['PCBA ERP', 'Sales'],
  ['PCBA 销售管理', '销售管理'],
  ['PCBA Sales', 'Sales Management'],
  ['/pcba-erp/', '/sales/'],
  ['/pcba-erp"', '/sales"'],
];

// ─── File rename rules (for fields/, commands/, pages/, bindings/) ───
// Order matters: longer prefixes first
const FILE_RENAME_RULES = [
  // Field files
  ['pe_so_line_', 'sl_sol_'],
  ['pe_sq_line_', 'sl_sql_'],
  ['pe_ship_line_', 'sl_shl_'],
  ['pe_sr_line_', 'sl_srl_'],
  ['pe_so_', 'sl_so_'],
  ['pe_sq_', 'sl_sq_'],
  ['pe_ship_', 'sl_sh_'],
  ['pe_sr_', 'sl_sr_'],
  ['pe_cm_', 'sl_cm_'],
  ['pe_oc_', 'sl_oc_'],
  ['pe_rma_', 'sl_rma_'],
  ['pe_col_', 'sl_col_'],
  ['pe_pack_', 'sl_pack_'],
  ['pe_pkln_', 'sl_pkln_'],
  // Command/page/binding files that use model names
  ['pe_sales_order_line', 'sl_sales_order_line'],
  ['pe_sales_quotation_line', 'sl_sales_quotation_line'],
  ['pe_sales_return_line', 'sl_sales_return_line'],
  ['pe_shipment_line', 'sl_shipment_line'],
  ['pe_packing_line', 'sl_packing_line'],
  ['pe_sales_order', 'sl_sales_order'],
  ['pe_sales_quotation', 'sl_sales_quotation'],
  ['pe_sales_return', 'sl_sales_return'],
  ['pe_sales_collection', 'sl_sales_collection'],
  ['pe_sales_dashboard', 'sl_sales_dashboard'],
  ['pe_credit_memo', 'sl_credit_memo'],
  ['pe_order_change', 'sl_order_change'],
  ['pe_shipment', 'sl_shipment'],
  ['pe_packing', 'sl_packing'],
  ['pe_rma', 'sl_rma'],
  // Command files with action prefixes
  ['pe_accept_', 'sl_accept_'],
  ['pe_add_', 'sl_add_'],
  ['pe_apply_', 'sl_apply_'],
  ['pe_approve_', 'sl_approve_'],
  ['pe_calculate_', 'sl_calculate_'],
  ['pe_cancel_', 'sl_cancel_'],
  ['pe_check_', 'sl_check_'],
  ['pe_close_', 'sl_close_'],
  ['pe_complete_', 'sl_complete_'],
  ['pe_confirm_', 'sl_confirm_'],
  ['pe_convert_', 'sl_convert_'],
  ['pe_create_', 'sl_create_'],
  ['pe_decide_', 'sl_decide_'],
  ['pe_delete_', 'sl_delete_'],
  ['pe_deliver_', 'sl_deliver_'],
  ['pe_inspect_', 'sl_inspect_'],
  ['pe_receive_', 'sl_receive_'],
  ['pe_reject_', 'sl_reject_'],
  ['pe_remove_', 'sl_remove_'],
  ['pe_send_', 'sl_send_'],
  ['pe_submit_', 'sl_submit_'],
  ['pe_update_', 'sl_update_'],
];

// ── Plugin.json override ─────────────────────────────────────────────
const PLUGIN_JSON = {
  pluginId: 'com.auraboot.sales',
  namespace: 'sl',
  version: '1.0.0',
  dslVersion: 1,
  pluginType: 'config',
  'displayName:zh-CN': '销售管理',
  'displayName:en': 'Sales Management',
  description: 'Generic sales management: orders, quotations, shipments, returns, credit memos, RMA',
  dependencies: ['com.auraboot.crm'],
};

// ── Special case: pe_so_customer field rename ────────────────────────
// The field code pe_so_customer → sl_so_account_id (better semantic)
// But keep targetModel as crm_account
const SPECIAL_FIELD_RENAMES = [
  ['pe_so_customer', 'sl_so_account_id'],
  ['pe_sq_customer', 'sl_sq_account_id'],
  ['pe_sq_contact', 'sl_sq_contact_id'],
];

// ── Helpers ──────────────────────────────────────────────────────────

function walkDir(dir) {
  const results = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const fullPath = join(dir, entry.name);
    if (entry.isDirectory()) {
      results.push(...walkDir(fullPath));
    } else {
      results.push(fullPath);
    }
  }
  return results;
}

function applyContentReplacements(content) {
  let result = content;
  let count = 0;

  // Apply special field renames first (more specific)
  for (const [from, to] of SPECIAL_FIELD_RENAMES) {
    const regex = new RegExp(escapeRegex(from), 'g');
    const matches = result.match(regex);
    if (matches) {
      count += matches.length;
      result = result.replace(regex, to);
    }
  }

  // Apply general rules
  for (const [from, to] of CONTENT_RULES) {
    const regex = new RegExp(escapeRegex(from), 'g');
    const matches = result.match(regex);
    if (matches) {
      count += matches.length;
      result = result.replace(regex, to);
    }
  }

  return { result, count };
}

function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function renameFile(filePath) {
  const name = basename(filePath);
  let newName = name;

  // Apply special field renames first
  for (const [from, to] of SPECIAL_FIELD_RENAMES) {
    if (newName.startsWith(from + '.') || newName.startsWith(from + '_')) {
      // e.g., pe_so_customer.json → sl_so_account_id.json
      newName = to + newName.slice(from.length);
      break;
    }
  }

  // If special rename didn't apply, try general rules
  if (newName === name) {
    for (const [from, to] of FILE_RENAME_RULES) {
      if (newName.startsWith(from)) {
        newName = to + newName.slice(from.length);
        break; // only apply first match
      }
    }
  }

  if (newName !== name) {
    const newPath = join(dirname(filePath), newName);
    renameSync(filePath, newPath);
    summary.filesRenamed++;
    return { oldName: name, newName, newPath };
  }
  return null;
}

// ── Main ─────────────────────────────────────────────────────────────

function main() {
  console.log('=== pcba-sales → sales Migration Script ===\n');

  // 1. Validate source
  if (!existsSync(SRC_DIR)) {
    console.error(`ERROR: Source directory not found: ${SRC_DIR}`);
    process.exit(1);
  }

  // 2. Remove existing destination (idempotent)
  if (existsSync(DST_DIR)) {
    console.log(`Removing existing ${DST_DIR} ...`);
    rmSync(DST_DIR, { recursive: true, force: true });
  }

  // 3. Copy
  console.log(`Copying ${SRC_DIR} → ${DST_DIR} ...`);
  cpSync(SRC_DIR, DST_DIR, { recursive: true });

  // 4. Write plugin.json
  console.log('Writing plugin.json ...');
  writeFileSync(join(DST_DIR, 'plugin.json'), JSON.stringify(PLUGIN_JSON, null, 2) + '\n');

  // 5. Transform file contents
  console.log('Transforming file contents ...\n');
  const allFiles = walkDir(DST_DIR);

  for (const filePath of allFiles) {
    // Skip plugin.json (already overwritten)
    if (filePath === join(DST_DIR, 'plugin.json')) continue;

    try {
      const content = readFileSync(filePath, 'utf-8');
      const { result, count } = applyContentReplacements(content);

      if (count > 0) {
        writeFileSync(filePath, result);
        summary.filesTransformed++;
        summary.contentReplacements += count;
      }
    } catch (err) {
      summary.errors.push(`Content transform error in ${filePath}: ${err.message}`);
    }
  }

  // 6. Rename files (process directories that have renamable files)
  console.log('Renaming files ...\n');
  const renameDirs = ['config/fields', 'config/commands', 'config/pages', 'config/bindings'];
  const renames = [];

  for (const relDir of renameDirs) {
    const dir = join(DST_DIR, relDir);
    if (!existsSync(dir)) continue;

    // Get files, sort by name length descending to avoid conflicts
    const files = readdirSync(dir)
      .filter(f => f.endsWith('.json'))
      .sort((a, b) => b.length - a.length);

    for (const file of files) {
      const filePath = join(dir, file);
      try {
        const result = renameFile(filePath);
        if (result) {
          renames.push({ dir: relDir, ...result });
        }
      } catch (err) {
        summary.errors.push(`Rename error for ${filePath}: ${err.message}`);
      }
    }
  }

  // 7. Print summary
  console.log('=== Migration Summary ===\n');
  console.log(`  Files with content changes: ${summary.filesTransformed}`);
  console.log(`  Total content replacements: ${summary.contentReplacements}`);
  console.log(`  Files renamed:              ${summary.filesRenamed}`);

  if (renames.length > 0) {
    console.log('\n--- File Renames ---');
    for (const r of renames) {
      console.log(`  ${r.dir}: ${r.oldName} → ${r.newName}`);
    }
  }

  if (summary.errors.length > 0) {
    console.log('\n--- Errors ---');
    for (const err of summary.errors) {
      console.log(`  ERROR: ${err}`);
    }
    process.exit(1);
  }

  console.log('\nDone! New plugin at: ' + DST_DIR);
}

main();
