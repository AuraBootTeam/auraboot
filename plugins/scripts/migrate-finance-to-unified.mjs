#!/usr/bin/env node

/**
 * Migration script: pcba-finance + finance-accounting -> finance (unified)
 *
 * 1. Copy pcba-finance -> finance
 * 2. Exclude 7 PCBA-specific models (ECN, ECO, cost estimate/detail, compliance)
 * 3. Add 3 unique models from finance-accounting (voucher_template, voucher_template_line, payment)
 * 4. Rename all pe_/fac_ prefixes to fin_
 * 5. Update plugin.json, menus.json, default-bootstrap.json
 *
 * Idempotent: removes existing finance/ directory before copying.
 */

import { existsSync, cpSync, rmSync, readFileSync, writeFileSync, renameSync, mkdirSync, unlinkSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';
import { readdirSync } from 'node:fs';

const PLUGINS_DIR = new URL('..', import.meta.url).pathname;
const SRC_DIR = join(PLUGINS_DIR, 'pcba-finance');
const FAC_DIR = join(PLUGINS_DIR, 'finance-accounting');
const DST_DIR = join(PLUGINS_DIR, 'finance');

// ── Summary counters ─────────────────────────────────────────────────
const summary = {
  filesTransformed: 0,
  filesRenamed: 0,
  filesDeleted: 0,
  filesAdded: 0,
  contentReplacements: 0,
  errors: [],
};

// ── Models to EXCLUDE (PCBA-specific, stay in pcba-finance) ──────────
const EXCLUDED_MODELS = [
  'pe_ecn',
  'pe_eco',
  'pe_eco_affected_item',
  'pe_cost_estimate',
  'pe_cost_detail',
  'pe_compliance_doc',
  'pe_compliance_checklist',
];

// Field prefixes to delete for excluded models
const EXCLUDED_FIELD_PREFIXES = [
  'pe_ecn_',
  'pe_eco_',
  'pe_eai_',
  'pe_ce_',
  'pe_cd_',
  'pe_cpd_',
  'pe_ccl_',
];

// Dict codes to exclude (PCBA-specific)
const EXCLUDED_DICT_CODES = [
  'pe_ecn_reason',
  'pe_ecn_priority',
  'pe_ecn_status',
  'pe_eco_status',
  'pe_eco_type',
  'pe_eai_type',
  'pe_eai_status',
  'pe_ce_status',
  'pe_cd_cost_type',
  'pe_cd_source_type',
  'pe_cpd_doc_type',
  'pe_cpd_status',
  'pe_ccl_reference_type',
  'pe_ccl_status',
  'pe_compliance_type',
];

// Menu codes to exclude
const EXCLUDED_MENU_CODES = [
  'pe_ecm_dir',
  'pe_ecn_menu',
  'pe_eco_menu',
  'pe_compliance_docs',
  'pe_cost_estimates',
];

// Permission codes to exclude
const EXCLUDED_PERMISSION_CODES = [
  'PE.ecm.manage',
  'PE.ecm.read',
  'PE.cost.manage',
  'PE.cost.read',
  'PE.compliance.manage',
  'PE.compliance.read',
];

// i18n keys to exclude (prefixes)
const EXCLUDED_I18N_KEY_PREFIXES = [
  'model.pe_ecn',
  'model.pe_eco',
  'model.pe_cost_estimate',
  'model.pe_cost_detail',
  'model.pe_compliance_doc',
  'model.pe_compliance_checklist',
  'field.pe_ecn_',
  'field.pe_eco_',
  'field.pe_eai_',
  'field.pe_ce_',
  'field.pe_cd_',
  'field.pe_cpd_',
  'field.pe_ccl_',
  'menu.pe_ecm_',
  'menu.pe_ecn_',
  'menu.pe_eco_',
  'menu.pe_compliance_',
  'menu.pe_cost_estimates',
];

// ── Models to ADD from finance-accounting ────────────────────────────
const FAC_MODELS_TO_ADD = [
  'fac_voucher_template',
  'fac_voucher_template_line',
  'fac_payment',
];

// ── Content replacement rules (ORDER MATTERS — longer strings first) ──

const CONTENT_RULES = [
  // ─── Model codes (longer first) ───
  ['pe_three_way_match', 'fin_three_way_match'],
  ['pe_fiscal_period', 'fin_fiscal_period'],
  ['pe_journal_entry', 'fin_journal_entry'],
  ['pe_journal_line', 'fin_journal_line'],
  ['pe_gl_balance', 'fin_gl_balance'],
  ['pe_ar_transaction', 'fin_ar_transaction'],
  ['pe_ap_transaction', 'fin_ap_transaction'],
  ['pe_financial_report', 'fin_financial_report'],
  ['pe_financial_dashboard', 'fin_financial_dashboard'],
  ['pe_restatement', 'fin_restatement'],
  ['pe_cost_center', 'fin_cost_center'],
  ['pe_expense_claim', 'fin_expense_claim'],
  ['pe_kpi_definition', 'fin_kpi_definition'],
  ['pe_kpi_snapshot', 'fin_kpi_snapshot'],
  ['pe_account', 'fin_account'],
  ['fac_journal_entry_line', 'fin_journal_entry_line'],
  ['fac_journal_entry', 'fin_journal_entry'],
  ['fac_voucher_template_line', 'fin_voucher_template_line'],
  ['fac_voucher_template', 'fin_voucher_template'],
  ['fac_receivable', 'fin_ar_transaction'],
  ['fac_payable', 'fin_ap_transaction'],
  ['fac_payment', 'fin_payment'],
  ['fac_fiscal_period', 'fin_fiscal_period'],
  ['fac_account_balance', 'fin_account_balance'],
  ['fac_account', 'fin_account'],

  // ─── Field code prefixes (longer first to avoid partial matches) ───
  ['pe_acc_', 'fin_acc_'],
  ['pe_fp_', 'fin_fp_'],
  ['pe_je_', 'fin_je_'],
  ['pe_jl_', 'fin_jl_'],
  ['pe_glb_', 'fin_glb_'],
  ['pe_art_', 'fin_art_'],
  ['pe_apt_', 'fin_apt_'],
  ['pe_fr_', 'fin_fr_'],
  ['pe_rs_', 'fin_rs_'],
  ['pe_exp_', 'fin_exp_'],
  ['pe_kpi_', 'fin_kpi_'],
  ['pe_ks_', 'fin_ks_'],
  ['pe_cct_', 'fin_cct_'],
  ['pe_twm_', 'fin_twm_'],
  ['fac_vt_', 'fin_vt_'],
  ['fac_vtl_', 'fin_vtl_'],
  ['fac_pay_', 'fin_pay_'],
  ['fac_acc_', 'fin_acc_'],
  ['fac_je_', 'fin_je_'],
  ['fac_jl_', 'fin_jl_'],
  ['fac_ar_', 'fin_ar_'],
  ['fac_ap_', 'fin_ap_'],
  ['fac_fp_', 'fin_fp_'],

  // ─── Dict codes (longer first) ───
  ['pe_balance_direction', 'fin_balance_direction'],
  ['pe_account_type', 'fin_account_type'],
  ['pe_account_status', 'fin_account_status'],
  ['pe_journal_status', 'fin_journal_status'],
  ['pe_journal_source', 'fin_journal_source'],
  ['pe_journal_type', 'fin_journal_type'],
  ['pe_kpi_formula_type', 'fin_kpi_formula_type'],
  ['pe_kpi_category', 'fin_kpi_category'],
  ['pe_kpi_status', 'fin_kpi_status'],
  ['pe_kpi_trend', 'fin_kpi_trend'],
  ['pe_kpi_type', 'fin_kpi_type'],
  ['pe_transaction_type', 'fin_transaction_type'],
  ['pe_restatement_status', 'fin_restatement_status'],
  ['pe_exp_status', 'fin_exp_status'],
  ['pe_twm_status', 'fin_twm_status'],
  ['pe_ar_ap_status', 'fin_ar_ap_status'],
  ['pe_ar_source', 'fin_ar_source'],
  ['pe_ap_source', 'fin_ap_source'],
  ['pe_period_status', 'fin_period_status'],
  ['pe_report_status', 'fin_report_status'],
  ['pe_report_type', 'fin_report_type'],
  ['pe_expense_type', 'fin_expense_type'],
  ['pe_cc_type', 'fin_cc_type'],
  ['pe_aux_type', 'fin_aux_type'],
  ['pe_adjustment_type', 'fin_adjustment_type'],
  ['pe_match_result', 'fin_match_result'],
  ['fac_account_type', 'fin_account_type'],
  ['fac_balance_direction', 'fin_balance_direction'],
  ['fac_status', 'fin_fac_status'],
  ['fac_voucher_status', 'fin_voucher_status'],
  ['fac_ar_ap_status', 'fin_ar_ap_status'],
  ['fac_payment_type', 'fin_payment_type'],
  ['fac_payment_method', 'fin_payment_method'],
  ['fac_period_status', 'fin_period_status'],

  // ─── Command namespace ───
  ['"pe:', '"fin:'],
  ['"fac:', '"fin:'],

  // ─── Permissions ───
  ['PE.financial', 'FIN.financial'],
  ['PE.dashboard.financial', 'FIN.dashboard.financial'],
  ['PE.expense', 'FIN.expense'],
  ['PE.bi', 'FIN.bi'],
  ['PE.cost', 'FIN.cost'],
  ['FAC.account', 'FIN.account'],
  ['FAC.voucher', 'FIN.voucher'],
  ['FAC.template', 'FIN.template'],
  ['FAC.ar_ap', 'FIN.ar_ap'],
  ['FAC.payment', 'FIN.payment'],
  ['FAC.period', 'FIN.period'],

  // ─── Role codes ───
  ['PE_ADMIN', 'FIN_ADMIN'],
  ['PE_FINANCE', 'FIN_FINANCE'],
  ['PE_QUALITY_ENGINEER', 'FIN_QUALITY_ENGINEER'],

  // ─── Menu codes ───
  ['pe_finance_dir', 'fin_finance_dir'],
  ['pe_financial_dashboard_menu', 'fin_financial_dashboard_menu'],
  ['pe_account_menu', 'fin_account_menu'],
  ['pe_journal_entry_menu', 'fin_journal_entry_menu'],
  ['pe_gl_balance_menu', 'fin_gl_balance_menu'],
  ['pe_fiscal_period_menu', 'fin_fiscal_period_menu'],
  ['pe_ar_menu', 'fin_ar_menu'],
  ['pe_ap_menu', 'fin_ap_menu'],
  ['pe_report_menu', 'fin_report_menu'],
  ['pe_restatement_menu', 'fin_restatement_menu'],
  ['pe_expense_claims', 'fin_expense_claims'],
  ['pe_three_way_matches', 'fin_three_way_matches'],
  ['pe_cost_centers', 'fin_cost_centers'],
  ['pe_executive_dashboard_menu', 'fin_executive_dashboard_menu'],
  ['pe_wms_dashboard_menu', 'fin_wms_dashboard_menu'],
  ['pe_pmo_dashboard_menu', 'fin_pmo_dashboard_menu'],
  ['pe_kpi_definitions_menu', 'fin_kpi_definitions_menu'],
  ['pe_kpi_snapshots_menu', 'fin_kpi_snapshots_menu'],
  ['pe_bi_root', 'fin_bi_root'],
  ['pe_root', 'fin_root'],
  ['fac_root', 'fin_root'],

  // ─── Schema/dashboard IDs ───
  ['dashboard.pe_financial', 'dashboard.fin_financial'],

  // ─── Cross-plugin page keys that are now owned by finance ───
  ['pe_executive_dashboard', 'fin_executive_dashboard'],
  ['pe_wms_dashboard', 'fin_wms_dashboard'],
  ['pe_pmo_dashboard', 'fin_pmo_dashboard'],

  // ─── Module/category references ───
  ['"pcba-erp"', '"finance"'],
  ['"pcba-finance"', '"finance"'],
  ['"finance-accounting"', '"finance"'],
  ['PCBA ERP', 'Finance'],
  ['PCBA 财务管理', '财务管理'],
  ['PCBA Finance', 'Finance Management'],
  ['/pcba-erp/', '/finance/'],
  ['/pcba-erp"', '/finance"'],
];

// ─── File rename rules ───
const FILE_RENAME_RULES = [
  // Field file prefixes (longer first)
  ['pe_acc_', 'fin_acc_'],
  ['pe_fp_', 'fin_fp_'],
  ['pe_je_', 'fin_je_'],
  ['pe_jl_', 'fin_jl_'],
  ['pe_glb_', 'fin_glb_'],
  ['pe_art_', 'fin_art_'],
  ['pe_apt_', 'fin_apt_'],
  ['pe_fr_', 'fin_fr_'],
  ['pe_rs_', 'fin_rs_'],
  ['pe_exp_', 'fin_exp_'],
  ['pe_kpi_', 'fin_kpi_'],
  ['pe_ks_', 'fin_ks_'],
  ['pe_cct_', 'fin_cct_'],
  ['pe_twm_', 'fin_twm_'],
  ['fac_vt_', 'fin_vt_'],
  ['fac_vtl_', 'fin_vtl_'],
  ['fac_pay_', 'fin_pay_'],

  // Command/page/binding files (model-based, longer first)
  ['pe_three_way_match', 'fin_three_way_match'],
  ['pe_fiscal_period', 'fin_fiscal_period'],
  ['pe_journal_entry', 'fin_journal_entry'],
  ['pe_journal_line', 'fin_journal_line'],
  ['pe_gl_balance', 'fin_gl_balance'],
  ['pe_ar_transaction', 'fin_ar_transaction'],
  ['pe_ap_transaction', 'fin_ap_transaction'],
  ['pe_financial_report', 'fin_financial_report'],
  ['pe_financial_dashboard', 'fin_financial_dashboard'],
  ['pe_restatement', 'fin_restatement'],
  ['pe_cost_center', 'fin_cost_center'],
  ['pe_expense_claim', 'fin_expense_claim'],
  ['pe_kpi_definition', 'fin_kpi_definition'],
  ['pe_kpi_snapshot', 'fin_kpi_snapshot'],
  ['pe_account', 'fin_account'],
  ['fac_voucher_template_line', 'fin_voucher_template_line'],
  ['fac_voucher_template', 'fin_voucher_template'],
  ['fac_payment', 'fin_payment'],

  // Command files with action prefixes
  ['pe_activate_', 'fin_activate_'],
  ['pe_add_', 'fin_add_'],
  ['pe_apply_', 'fin_apply_'],
  ['pe_approve_', 'fin_approve_'],
  ['pe_calculate_', 'fin_calculate_'],
  ['pe_cancel_', 'fin_cancel_'],
  ['pe_close_', 'fin_close_'],
  ['pe_create_', 'fin_create_'],
  ['pe_deactivate_', 'fin_deactivate_'],
  ['pe_delete_', 'fin_delete_'],
  ['pe_generate_', 'fin_generate_'],
  ['pe_init_', 'fin_init_'],
  ['pe_open_', 'fin_open_'],
  ['pe_pay_', 'fin_pay_'],
  ['pe_post_', 'fin_post_'],
  ['pe_recalculate_', 'fin_recalculate_'],
  ['pe_reject_', 'fin_reject_'],
  ['pe_remove_', 'fin_remove_'],
  ['pe_resolve_', 'fin_resolve_'],
  ['pe_submit_', 'fin_submit_'],
  ['pe_update_', 'fin_update_'],
  ['pe_void_', 'fin_void_'],
  ['pe_write_off_', 'fin_write_off_'],
];

// ── Plugin.json override ─────────────────────────────────────────────
const PLUGIN_JSON = {
  pluginId: 'com.auraboot.finance',
  namespace: 'fin',
  version: '1.0.0',
  dslVersion: 1,
  pluginType: 'config',
  'displayName:zh-CN': '财务管理',
  'displayName:en': 'Finance Management',
  description: 'Generic finance: chart of accounts, journal entries, AR/AP, fiscal periods, expense claims, cost centers, financial reports',
  author: 'AuraBoot Team',
  homepage: 'https://auraboot.com/plugins/finance',
  minPlatformVersion: '1.0.0',
  dependencies: [
    'com.auraboot.crm',
    'com.auraboot.org-management',
  ],
  resourceDirs: {
    models: 'config/models.json',
    fields: 'config/fields',
    modelFieldBindings: 'config/bindings',
    commands: 'config/commands',
    pages: 'config/pages',
    dicts: 'config/dicts.json',
    permissions: 'config/permissions.json',
    roles: 'config/roles.json',
    menus: 'config/menus.json',
    i18n: 'config/i18n.json',
  },
  importOptions: {
    conflictStrategy: 'OVERWRITE',
    validateReferences: true,
    autoDeployProcesses: false,
    createResourcePermissions: false,
    autoPublishPages: false,
  },
};

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

function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function applyContentReplacements(content) {
  let result = content;
  let count = 0;

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

function renameFile(filePath) {
  const name = basename(filePath);
  let newName = name;

  for (const [from, to] of FILE_RENAME_RULES) {
    if (newName.startsWith(from)) {
      newName = to + newName.slice(from.length);
      break; // only apply first match
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

// ── Step 2: Remove excluded model artifacts ──────────────────────────

function removeExcludedModels() {
  console.log('Removing excluded PCBA-specific models ...\n');

  // 2a. Remove from models.json
  const modelsPath = join(DST_DIR, 'config', 'models.json');
  const models = JSON.parse(readFileSync(modelsPath, 'utf-8'));
  const filteredModels = models.filter(m => !EXCLUDED_MODELS.includes(m.code));
  const removedModelCount = models.length - filteredModels.length;
  writeFileSync(modelsPath, JSON.stringify(filteredModels, null, 2) + '\n');
  console.log(`  models.json: removed ${removedModelCount} models`);

  // 2b. Delete excluded field files
  const fieldsDir = join(DST_DIR, 'config', 'fields');
  let fieldFilesDeleted = 0;
  if (existsSync(fieldsDir)) {
    for (const file of readdirSync(fieldsDir)) {
      if (EXCLUDED_FIELD_PREFIXES.some(prefix => file.startsWith(prefix))) {
        unlinkSync(join(fieldsDir, file));
        fieldFilesDeleted++;
        summary.filesDeleted++;
      }
    }
  }
  console.log(`  fields/: deleted ${fieldFilesDeleted} field files`);

  // 2c. Delete excluded command files
  const commandsDir = join(DST_DIR, 'config', 'commands');
  let cmdFilesDeleted = 0;
  if (existsSync(commandsDir)) {
    for (const file of readdirSync(commandsDir)) {
      const matchesExcluded = EXCLUDED_MODELS.some(model => {
        // Command files can be pe_create_ecn.json, pe_approve_ecn.json, etc.
        // or pe_add_eco_affected_item.json etc.
        return file.includes(model.replace('pe_', ''));
      });
      if (matchesExcluded) {
        unlinkSync(join(commandsDir, file));
        cmdFilesDeleted++;
        summary.filesDeleted++;
      }
    }
  }
  console.log(`  commands/: deleted ${cmdFilesDeleted} command files`);

  // 2d. Delete excluded page files
  const pagesDir = join(DST_DIR, 'config', 'pages');
  let pageFilesDeleted = 0;
  if (existsSync(pagesDir)) {
    for (const file of readdirSync(pagesDir)) {
      const matchesExcluded = EXCLUDED_MODELS.some(model => file.startsWith(model));
      if (matchesExcluded) {
        unlinkSync(join(pagesDir, file));
        pageFilesDeleted++;
        summary.filesDeleted++;
      }
    }
  }
  console.log(`  pages/: deleted ${pageFilesDeleted} page files`);

  // 2e. Delete excluded binding files
  const bindingsDir = join(DST_DIR, 'config', 'bindings');
  let bindingFilesDeleted = 0;
  if (existsSync(bindingsDir)) {
    for (const file of readdirSync(bindingsDir)) {
      const matchesExcluded = EXCLUDED_MODELS.some(model => file.startsWith(model));
      if (matchesExcluded) {
        unlinkSync(join(bindingsDir, file));
        bindingFilesDeleted++;
        summary.filesDeleted++;
      }
    }
  }
  console.log(`  bindings/: deleted ${bindingFilesDeleted} binding files`);

  // 2f. Filter dicts.json
  const dictsPath = join(DST_DIR, 'config', 'dicts.json');
  const dicts = JSON.parse(readFileSync(dictsPath, 'utf-8'));
  const filteredDicts = dicts.filter(d => !EXCLUDED_DICT_CODES.includes(d.code));
  const removedDictCount = dicts.length - filteredDicts.length;
  writeFileSync(dictsPath, JSON.stringify(filteredDicts, null, 2) + '\n');
  console.log(`  dicts.json: removed ${removedDictCount} dict entries`);

  // 2g. Filter menus.json
  const menusPath = join(DST_DIR, 'config', 'menus.json');
  const menus = JSON.parse(readFileSync(menusPath, 'utf-8'));
  const filteredMenus = menus.filter(m => !EXCLUDED_MENU_CODES.includes(m.code));
  const removedMenuCount = menus.length - filteredMenus.length;
  writeFileSync(menusPath, JSON.stringify(filteredMenus, null, 2) + '\n');
  console.log(`  menus.json: removed ${removedMenuCount} menu entries`);

  // 2h. Filter permissions.json
  const permsPath = join(DST_DIR, 'config', 'permissions.json');
  const perms = JSON.parse(readFileSync(permsPath, 'utf-8'));
  const filteredPerms = perms.filter(p => !EXCLUDED_PERMISSION_CODES.includes(p.code));
  const removedPermCount = perms.length - filteredPerms.length;
  writeFileSync(permsPath, JSON.stringify(filteredPerms, null, 2) + '\n');
  console.log(`  permissions.json: removed ${removedPermCount} permission entries`);

  // 2i. Filter i18n.json
  const i18nPath = join(DST_DIR, 'config', 'i18n.json');
  const i18n = JSON.parse(readFileSync(i18nPath, 'utf-8'));
  const filteredI18n = i18n.filter(entry => {
    return !EXCLUDED_I18N_KEY_PREFIXES.some(prefix => entry.key.startsWith(prefix));
  });
  const removedI18nCount = i18n.length - filteredI18n.length;
  writeFileSync(i18nPath, JSON.stringify(filteredI18n, null, 2) + '\n');
  console.log(`  i18n.json: removed ${removedI18nCount} i18n entries`);

  // 2j. Filter roles.json (remove compliance-related permissions from roles)
  const rolesPath = join(DST_DIR, 'config', 'roles.json');
  const roles = JSON.parse(readFileSync(rolesPath, 'utf-8'));
  for (const role of roles) {
    if (role.permissions) {
      role.permissions = role.permissions.filter(p => !EXCLUDED_PERMISSION_CODES.includes(p));
    }
  }
  writeFileSync(rolesPath, JSON.stringify(roles, null, 2) + '\n');
  console.log(`  roles.json: cleaned excluded permissions from roles`);
}

// ── Step 3: Add models from finance-accounting ───────────────────────

function addFacModels() {
  console.log('\nAdding models from finance-accounting ...\n');

  // Read finance-accounting consolidated files
  const facModels = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'models.json'), 'utf-8'));
  const facFields = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'fields.json'), 'utf-8'));
  const facCommands = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'commands.json'), 'utf-8'));
  const facPages = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'pages.json'), 'utf-8'));
  const facBindings = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'bindings.json'), 'utf-8'));
  const facDicts = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'dicts.json'), 'utf-8'));
  const facPerms = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'permissions.json'), 'utf-8'));
  const facI18n = JSON.parse(readFileSync(join(FAC_DIR, 'config', 'i18n.json'), 'utf-8'));

  // 3a. Add model entries
  const modelsPath = join(DST_DIR, 'config', 'models.json');
  const models = JSON.parse(readFileSync(modelsPath, 'utf-8'));
  const modelsToAdd = facModels.filter(m => FAC_MODELS_TO_ADD.includes(m.code));
  models.push(...modelsToAdd);
  writeFileSync(modelsPath, JSON.stringify(models, null, 2) + '\n');
  console.log(`  models.json: added ${modelsToAdd.length} models (${modelsToAdd.map(m => m.code).join(', ')})`);

  // 3b. Create individual field files from consolidated fields.json
  const fieldsDir = join(DST_DIR, 'config', 'fields');
  let fieldFilesCreated = 0;
  for (const field of facFields) {
    if (FAC_MODELS_TO_ADD.some(model => {
      const binding = facBindings.find(b => b.modelCode === model && b.fieldCode === field.code);
      return !!binding;
    })) {
      writeFileSync(join(fieldsDir, field.code + '.json'), JSON.stringify(field, null, 2) + '\n');
      fieldFilesCreated++;
      summary.filesAdded++;
    }
  }
  console.log(`  fields/: created ${fieldFilesCreated} field files`);

  // 3c. Create individual command files
  const commandsDir = join(DST_DIR, 'config', 'commands');
  let cmdFilesCreated = 0;
  for (const cmd of facCommands) {
    if (FAC_MODELS_TO_ADD.includes(cmd.modelCode)) {
      // Use command code (namespace:action) → filename (namespace_action.json)
      const fileName = cmd.code.replace(':', '_') + '.json';
      writeFileSync(join(commandsDir, fileName), JSON.stringify(cmd, null, 2) + '\n');
      cmdFilesCreated++;
      summary.filesAdded++;
    }
  }
  console.log(`  commands/: created ${cmdFilesCreated} command files`);

  // 3d. Create individual page files
  const pagesDir = join(DST_DIR, 'config', 'pages');
  let pageFilesCreated = 0;
  for (const page of facPages) {
    if (FAC_MODELS_TO_ADD.includes(page.modelCode)) {
      writeFileSync(join(pagesDir, page.pageKey + '.json'), JSON.stringify(page, null, 2) + '\n');
      pageFilesCreated++;
      summary.filesAdded++;
    }
  }
  console.log(`  pages/: created ${pageFilesCreated} page files`);

  // 3e. Create individual binding files
  const bindingsDir = join(DST_DIR, 'config', 'bindings');
  let bindingFilesCreated = 0;
  for (const model of FAC_MODELS_TO_ADD) {
    const modelBindings = facBindings.filter(b => b.modelCode === model);
    if (modelBindings.length > 0) {
      writeFileSync(join(bindingsDir, model + '.json'), JSON.stringify(modelBindings, null, 2) + '\n');
      bindingFilesCreated++;
      summary.filesAdded++;
    }
  }
  console.log(`  bindings/: created ${bindingFilesCreated} binding files`);

  // 3f. Add dicts from finance-accounting that are relevant to added models
  // Add payment-specific dicts
  const dictsPath = join(DST_DIR, 'config', 'dicts.json');
  const dicts = JSON.parse(readFileSync(dictsPath, 'utf-8'));
  const facDictCodesToAdd = [
    'fac_payment_type',
    'fac_payment_method',
    'fac_voucher_status',
    'fac_ar_ap_status',
    'fac_balance_direction',
    'fac_period_status',
  ];
  const newDicts = facDicts.filter(d => facDictCodesToAdd.includes(d.code));
  // Only add dicts not already present (by code)
  const existingDictCodes = new Set(dicts.map(d => d.code));
  for (const d of newDicts) {
    if (!existingDictCodes.has(d.code)) {
      dicts.push(d);
    }
  }
  writeFileSync(dictsPath, JSON.stringify(dicts, null, 2) + '\n');
  console.log(`  dicts.json: added ${newDicts.length} dict entries`);

  // 3g. Add permissions from finance-accounting for added models
  const permsPath = join(DST_DIR, 'config', 'permissions.json');
  const perms = JSON.parse(readFileSync(permsPath, 'utf-8'));
  const facPermCodesToAdd = ['FAC.template.manage', 'FAC.template.read', 'FAC.payment.manage', 'FAC.payment.read'];
  const existingPermCodes = new Set(perms.map(p => p.code));
  const newPerms = facPerms.filter(p => facPermCodesToAdd.includes(p.code) && !existingPermCodes.has(p.code));
  perms.push(...newPerms);
  writeFileSync(permsPath, JSON.stringify(perms, null, 2) + '\n');
  console.log(`  permissions.json: added ${newPerms.length} permission entries`);

  // 3h. Add menu entries for voucher templates and payments
  const menusPath = join(DST_DIR, 'config', 'menus.json');
  const menus = JSON.parse(readFileSync(menusPath, 'utf-8'));
  const newMenus = [
    {
      code: 'fin_voucher_templates',
      'name:zh-CN': '凭证模板',
      'name:en': 'Voucher Templates',
      path: '/finance/voucher-templates',
      icon: 'FileCode',
      type: 1,
      parentCode: 'pe_finance_dir',
      orderNo: 14,
      permissionCode: 'FAC.template.read',
      pageKey: 'fac_voucher_template_list',
    },
    {
      code: 'fin_payments',
      'name:zh-CN': '收付款',
      'name:en': 'Payments',
      path: '/finance/payments',
      icon: 'CreditCard',
      type: 1,
      parentCode: 'pe_finance_dir',
      orderNo: 15,
      permissionCode: 'FAC.payment.read',
      pageKey: 'fac_payment_list',
    },
  ];
  menus.push(...newMenus);
  writeFileSync(menusPath, JSON.stringify(menus, null, 2) + '\n');
  console.log(`  menus.json: added ${newMenus.length} menu entries`);

  // 3i. Add i18n entries for added models
  const i18nPath = join(DST_DIR, 'config', 'i18n.json');
  const i18n = JSON.parse(readFileSync(i18nPath, 'utf-8'));
  const facI18nToAdd = facI18n.filter(entry => {
    return FAC_MODELS_TO_ADD.some(model => entry.key.includes(model));
  });
  i18n.push(...facI18nToAdd);
  writeFileSync(i18nPath, JSON.stringify(i18n, null, 2) + '\n');
  console.log(`  i18n.json: added ${facI18nToAdd.length} i18n entries`);
}

// ── Main ─────────────────────────────────────────────────────────────

function main() {
  console.log('=== pcba-finance + finance-accounting → finance (unified) Migration ===\n');

  // 1. Validate sources
  if (!existsSync(SRC_DIR)) {
    console.error(`ERROR: Source directory not found: ${SRC_DIR}`);
    process.exit(1);
  }
  if (!existsSync(FAC_DIR)) {
    console.error(`ERROR: Source directory not found: ${FAC_DIR}`);
    process.exit(1);
  }

  // 2. Remove existing destination (idempotent)
  if (existsSync(DST_DIR)) {
    console.log(`Removing existing ${DST_DIR} ...`);
    rmSync(DST_DIR, { recursive: true, force: true });
  }

  // 3. Copy pcba-finance as base
  console.log(`Copying ${SRC_DIR} → ${DST_DIR} ...`);
  cpSync(SRC_DIR, DST_DIR, { recursive: true });

  // 4. Write plugin.json
  console.log('Writing plugin.json ...\n');
  writeFileSync(join(DST_DIR, 'plugin.json'), JSON.stringify(PLUGIN_JSON, null, 2) + '\n');

  // 5. Remove excluded PCBA-specific models
  removeExcludedModels();

  // 6. Add models from finance-accounting
  addFacModels();

  // 7. Transform file contents (apply prefix renames)
  console.log('\nTransforming file contents ...\n');
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

  // 8. Rename files
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

  // 9. Final validation: count models, check for remaining pe_/fac_ references
  console.log('\n=== Post-Migration Validation ===\n');

  // Count files
  const finalFiles = walkDir(DST_DIR);
  console.log(`  Total files: ${finalFiles.length}`);

  // Count models
  const finalModels = JSON.parse(readFileSync(join(DST_DIR, 'config', 'models.json'), 'utf-8'));
  console.log(`  Model count: ${finalModels.length}`);
  console.log(`  Models: ${finalModels.map(m => m.code).join(', ')}`);

  // Check for remaining pe_ or fac_ references
  let remainingRefs = [];
  for (const filePath of finalFiles) {
    if (filePath === join(DST_DIR, 'plugin.json')) continue;
    try {
      const content = readFileSync(filePath, 'utf-8');
      // Look for pe_ or fac_ references
      const peMatches = content.match(/\bpe_[a-z_]+/g) || [];
      const facMatches = content.match(/\bfac_[a-z_]+/g) || [];
      const allMatches = [...peMatches, ...facMatches];
      if (allMatches.length > 0) {
        const relativePath = filePath.replace(DST_DIR + '/', '');
        const unique = [...new Set(allMatches)];
        remainingRefs.push({ file: relativePath, refs: unique });
      }
    } catch (err) {
      // skip binary files
    }
  }

  if (remainingRefs.length > 0) {
    console.log(`\n  ⚠ Remaining pe_/fac_ references (${remainingRefs.length} files):`);
    for (const { file, refs } of remainingRefs) {
      console.log(`    ${file}:`);
      for (const ref of refs.slice(0, 10)) {
        console.log(`      - ${ref}`);
      }
      if (refs.length > 10) {
        console.log(`      ... and ${refs.length - 10} more`);
      }
    }
  } else {
    console.log(`\n  No remaining pe_/fac_ references found.`);
  }

  // 10. Print summary
  console.log('\n=== Migration Summary ===\n');
  console.log(`  Files with content changes: ${summary.filesTransformed}`);
  console.log(`  Total content replacements: ${summary.contentReplacements}`);
  console.log(`  Files renamed:              ${summary.filesRenamed}`);
  console.log(`  Files deleted (excluded):   ${summary.filesDeleted}`);
  console.log(`  Files added (from fac):     ${summary.filesAdded}`);

  if (renames.length > 0) {
    console.log(`\n--- File Renames (${renames.length} total) ---`);
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
