#!/usr/bin/env node

/**
 * Migration script: pcba-procurement -> procurement
 *
 * Copies plugins/pcba-procurement/ to plugins/procurement/ and transforms all
 * model codes, field prefixes, command namespaces, permissions, dict codes,
 * menu codes, and file names from pe_ namespace to pr_ namespace.
 *
 * Idempotent: removes existing procurement/ directory before copying.
 */

import { existsSync, cpSync, rmSync, readFileSync, writeFileSync, renameSync, statSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { readdirSync } from 'node:fs';

const PLUGINS_DIR = new URL('..', import.meta.url).pathname;
const SRC_DIR = join(PLUGINS_DIR, 'pcba-procurement');
const DST_DIR = join(PLUGINS_DIR, 'procurement');

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
  ['pe_purchase_order_line', 'pr_purchase_order_line'],
  ['pe_purchase_receipt_line', 'pr_purchase_receipt_line'],
  ['pe_purchase_return_line', 'pr_purchase_return_line'],
  ['pe_outsource_order_line', 'pr_outsource_order_line'],
  ['pe_outsource_receipt_line', 'pr_outsource_receipt_line'],
  ['pe_purchase_order', 'pr_purchase_order'],
  ['pe_purchase_receipt', 'pr_purchase_receipt'],
  ['pe_purchase_return', 'pr_purchase_return'],
  ['pe_purchase_request', 'pr_purchase_request'],
  ['pe_purchase_payment', 'pr_purchase_payment'],
  ['pe_outsource_order', 'pr_outsource_order'],
  ['pe_outsource_receipt', 'pr_outsource_receipt'],

  // ─── Field code prefixes (longer first to avoid partial matches) ───
  ['pe_po_line_', 'pr_pol_'],
  ['pe_pr_line_', 'pr_prl_'],
  ['pe_rcpt_line_', 'pr_rcptl_'],
  ['pe_po_', 'pr_po_'],
  ['pe_pr_', 'pr_pr_'],
  ['pe_rcpt_', 'pr_rcpt_'],
  ['pe_preq_', 'pr_preq_'],
  ['pe_pay_', 'pr_pay_'],
  ['pe_oso_', 'pr_oso_'],
  ['pe_osl_', 'pr_osl_'],
  ['pe_osr_', 'pr_osr_'],
  ['pe_orl_', 'pr_orl_'],

  // ─── Command namespace ───
  ['"pe:', '"pr:'],

  // ─── Permissions ───
  ['PE.purchase', 'PR.purchase'],
  ['PE.outsource', 'PR.outsource'],

  // ─── Role codes ───
  ['PE_ADMIN', 'PR_ADMIN'],
  ['PE_PURCHASER', 'PR_PURCHASER'],
  ['PE_PRODUCTION', 'PR_PRODUCTION'],
  ['PE_FINANCE', 'PR_FINANCE'],

  // ─── Menu codes ───
  ['pe_purchase_dir', 'pr_purchase_dir'],
  ['pe_purchase_requests', 'pr_purchase_requests'],
  ['pe_purchase_orders', 'pr_purchase_orders'],
  ['pe_purchase_receipts', 'pr_purchase_receipts'],
  ['pe_purchase_payments', 'pr_purchase_payments'],
  ['pe_purchase_returns', 'pr_purchase_returns'],
  ['pe_outsource_orders', 'pr_outsource_orders'],
  ['pe_outsource_receipts', 'pr_outsource_receipts'],
  ['pe_order_confirmations', 'pr_order_confirmations'],
  ['pe_asn', 'pr_asn'],
  ['pe_root', 'pr_root'],

  // ─── Module/category references ───
  ['pcba-erp', 'procurement'],
  ['PCBA ERP', 'Procurement'],
  ['PCBA 采购管理', '采购管理'],
  ['PCBA Procurement', 'Procurement Management'],
  ['/pcba-erp/', '/procurement/'],
  ['/pcba-erp"', '/procurement"'],
];

// ─── File rename rules (for fields/, commands/, pages/, bindings/) ───
// Order matters: longer prefixes first
const FILE_RENAME_RULES = [
  // Field files (longer prefixes first)
  ['pe_po_line_', 'pr_pol_'],
  ['pe_pr_line_', 'pr_prl_'],
  ['pe_rcpt_line_', 'pr_rcptl_'],
  ['pe_po_', 'pr_po_'],
  ['pe_pr_', 'pr_pr_'],
  ['pe_rcpt_', 'pr_rcpt_'],
  ['pe_preq_', 'pr_preq_'],
  ['pe_pay_', 'pr_pay_'],
  ['pe_oso_', 'pr_oso_'],
  ['pe_osl_', 'pr_osl_'],
  ['pe_osr_', 'pr_osr_'],
  ['pe_orl_', 'pr_orl_'],
  // Command/page/binding files that use model names (longer first)
  ['pe_purchase_order_line', 'pr_purchase_order_line'],
  ['pe_purchase_receipt_line', 'pr_purchase_receipt_line'],
  ['pe_purchase_return_line', 'pr_purchase_return_line'],
  ['pe_outsource_order_line', 'pr_outsource_order_line'],
  ['pe_outsource_receipt_line', 'pr_outsource_receipt_line'],
  ['pe_purchase_order', 'pr_purchase_order'],
  ['pe_purchase_receipt', 'pr_purchase_receipt'],
  ['pe_purchase_return', 'pr_purchase_return'],
  ['pe_purchase_request', 'pr_purchase_request'],
  ['pe_purchase_payment', 'pr_purchase_payment'],
  ['pe_outsource_order', 'pr_outsource_order'],
  ['pe_outsource_receipt', 'pr_outsource_receipt'],
  // Command files with action prefixes
  ['pe_add_', 'pr_add_'],
  ['pe_approve_', 'pr_approve_'],
  ['pe_cancel_', 'pr_cancel_'],
  ['pe_complete_', 'pr_complete_'],
  ['pe_confirm_', 'pr_confirm_'],
  ['pe_convert_', 'pr_convert_'],
  ['pe_create_', 'pr_create_'],
  ['pe_delete_', 'pr_delete_'],
  ['pe_process_', 'pr_process_'],
  ['pe_receive_', 'pr_receive_'],
  ['pe_send_', 'pr_send_'],
  ['pe_start_', 'pr_start_'],
  ['pe_submit_', 'pr_submit_'],
  ['pe_update_', 'pr_update_'],
];

// ── Plugin.json override ─────────────────────────────────────────────
const PLUGIN_JSON = {
  pluginId: 'com.auraboot.procurement',
  namespace: 'pr',
  version: '1.0.0',
  dslVersion: 1,
  pluginType: 'config',
  'displayName:zh-CN': '采购管理',
  'displayName:en': 'Procurement Management',
  description: 'Generic procurement: purchase orders, receipts, returns, requests, payments, outsourcing',
  dependencies: ['com.auraboot.crm'],
};

// ── Special case field renames ────────────────────────────────────────
// Fields referencing external models — keep targetModel as-is
const SPECIAL_FIELD_RENAMES = [
  // pe_po_supplier references pe_supplier — rename field code but keep targetModel
  // (no semantic rename needed here, the content rules handle pe_po_ → pr_po_ already)
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
  console.log('=== pcba-procurement → procurement Migration Script ===\n');

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
