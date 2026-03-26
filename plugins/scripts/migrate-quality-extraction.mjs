#!/usr/bin/env node

/**
 * Migration script: Extract quality-related models from pcba-manufacturing -> quality
 *
 * 1. Creates plugins/quality/ directory structure
 * 2. Copies ONLY quality-related models and their associated files from pcba-manufacturing
 * 3. Renames pe_ -> qc_ for all quality model/field/command/page codes
 * 4. Creates plugin.json, menus.json, default-bootstrap.json, permissions.json, roles.json
 * 5. Removes extracted models from pcba-manufacturing
 *
 * Quality models extracted (16):
 *   pe_iqc_order, pe_pqc_record, pe_fqc_order, pe_defect_record,
 *   pe_nonconformance, pe_spc_chart, pe_spc_data_point, pe_capa,
 *   pe_quality_cost, pe_batch_trace, pe_test_program, pe_test_result,
 *   pe_test_defect, pe_trace_template, pe_trace_node, pe_rework_order
 *
 * Idempotent: removes existing quality/ directory before creating.
 */

import { existsSync, cpSync, rmSync, readFileSync, writeFileSync, renameSync, statSync, readdirSync, mkdirSync } from 'node:fs';
import { join, basename, dirname } from 'node:path';

const PLUGINS_DIR = new URL('..', import.meta.url).pathname;
const SRC_DIR = join(PLUGINS_DIR, 'pcba-manufacturing');
const DST_DIR = join(PLUGINS_DIR, 'quality');

// ── Summary counters ─────────────────────────────────────────────────
const summary = {
  modelsCopied: 0,
  fieldsCopied: 0,
  commandsCopied: 0,
  pagesCopied: 0,
  bindingsCopied: 0,
  dictsCopied: 0,
  i18nCopied: 0,
  menusCopied: 0,
  filesTransformed: 0,
  filesRenamed: 0,
  contentReplacements: 0,
  errors: [],
};

// ── Quality model codes to extract ───────────────────────────────────
const QUALITY_MODELS = [
  'pe_iqc_order',
  'pe_pqc_record',
  'pe_fqc_order',
  'pe_defect_record',
  'pe_nonconformance',
  'pe_spc_chart',
  'pe_spc_data_point',
  'pe_capa',
  'pe_quality_cost',
  'pe_batch_trace',
  'pe_test_program',
  'pe_test_result',
  'pe_test_defect',
  'pe_trace_template',
  'pe_trace_node',
  'pe_rework_order',
];

// ── Field prefixes belonging to quality models ───────────────────────
const QUALITY_FIELD_PREFIXES = [
  'pe_iqc_',    // IQC order fields
  'pe_pqc_',    // PQC record fields
  'pe_fqc_',    // FQC order fields
  'pe_dr_',     // defect_record fields
  'pe_nc_',     // nonconformance fields
  'pe_spc_',    // spc_chart fields
  'pe_spd_',    // spc_data_point fields
  'pe_capa_',   // CAPA fields
  'pe_qc_',     // quality_cost fields
  'pe_bt_',     // batch_trace fields
  'pe_tp_',     // test_program fields
  'pe_tr_',     // test_result fields
  'pe_td_',     // test_defect fields
  'pe_tt_',     // trace_template fields
  'pe_tn_',     // trace_node fields
  'pe_rw_',     // rework_order fields
];

// ── Command patterns belonging to quality ────────────────────────────
const QUALITY_COMMAND_PATTERNS = [
  'iqc', 'pqc', 'fqc', 'defect', 'nonconformance', 'spc',
  'capa', 'quality_cost', 'batch_trace', 'test_program',
  'test_result', 'test_defect', 'trace_template', 'trace_node',
  'rework', 'batch_record_test', 'build_trace_tree',
  'calculate_quality_cost', 'calculate_spc_limits',
  'record_spc_data', 'record_test_result',
];

// ── Page patterns belonging to quality ───────────────────────────────
const QUALITY_PAGE_PREFIXES = [
  'pe_iqc_', 'pe_pqc_', 'pe_fqc_', 'pe_defect_record',
  'pe_nonconformance', 'pe_spc_chart', 'pe_capa',
  'pe_quality_cost', 'pe_quality_dashboard',
  'pe_batch_trace', 'pe_test_program', 'pe_test_result',
  'pe_test_defect', 'pe_trace_template', 'pe_trace_node',
  'pe_rework_order',
];

// ── Quality dict codes ───────────────────────────────────────────────
const QUALITY_DICT_CODES = [
  'pe_qc_result', 'pe_pqc_type', 'pe_defect_source', 'pe_defect_type',
  'pe_defect_status', 'pe_nc_type', 'pe_nc_source_type', 'pe_nc_status',
  'pe_spc_chart_type', 'pe_spc_status', 'pe_capa_type', 'pe_capa_source_type',
  'pe_capa_status', 'pe_capa_effectiveness', 'pe_quality_cost_type',
  'pe_test_type', 'pe_tp_status', 'pe_test_result_enum', 'pe_test_defect_type',
  'pe_tt_status', 'pe_trace_node_type', 'pe_trace_level',
  'pe_rw_source_type', 'pe_retest_result',
];

// ── Quality permission codes ─────────────────────────────────────────
const QUALITY_PERMISSION_CODES = [
  'PE.quality.manage', 'PE.quality.read', 'PE.quality.spc',
  'PE.quality.capa', 'PE.quality.cost', 'PE.dashboard.quality',
  'PE.test.manage', 'PE.test.read', 'PE.quality.rework', 'PE.quality.rework.read',
];

// ── Quality menu codes ───────────────────────────────────────────────
const QUALITY_MENU_CODES = [
  'pe_quality_dir', 'pe_quality_dashboard_menu', 'pe_iqc_orders',
  'pe_pqc_records', 'pe_fqc_orders', 'pe_defect_records',
  'pe_batch_traces', 'pe_nonconformance_menu', 'pe_spc_menu',
  'pe_capa_menu', 'pe_quality_cost_menu', 'pe_trace_templates',
  'pe_rework_orders', 'pe_test_programs', 'pe_test_results',
];

// ── Content replacement rules (ORDER MATTERS — longer strings first) ──
const CONTENT_RULES = [
  // ─── Model codes (longer first to avoid partial matches) ───
  ['pe_spc_data_point', 'qc_spc_data_point'],
  ['pe_quality_dashboard', 'qc_quality_dashboard'],
  ['pe_quality_cost', 'qc_quality_cost'],
  ['pe_defect_record', 'qc_defect_record'],
  ['pe_batch_trace', 'qc_batch_trace'],
  ['pe_test_program', 'qc_test_program'],
  ['pe_test_result', 'qc_test_result'],
  ['pe_test_defect', 'qc_test_defect'],
  ['pe_trace_template', 'qc_trace_template'],
  ['pe_trace_node', 'qc_trace_node'],
  ['pe_rework_order', 'qc_rework_order'],
  ['pe_nonconformance', 'qc_ncr'],
  ['pe_iqc_order', 'qc_iqc_order'],
  ['pe_pqc_record', 'qc_pqc_record'],
  ['pe_fqc_order', 'qc_fqc_order'],
  ['pe_spc_chart', 'qc_spc_chart'],
  ['pe_capa', 'qc_capa'],

  // ─── Field code prefixes (longer first) ───
  ['pe_spd_', 'qc_spd_'],
  ['pe_iqc_', 'qc_iqc_'],
  ['pe_pqc_', 'qc_pqc_'],
  ['pe_fqc_', 'qc_fqc_'],
  ['pe_dr_', 'qc_dr_'],
  ['pe_nc_', 'qc_nc_'],
  ['pe_spc_', 'qc_spc_'],
  ['pe_capa_', 'qc_capa_'],
  ['pe_qc_', 'qc_qc_'],
  ['pe_bt_', 'qc_bt_'],
  ['pe_tp_', 'qc_tp_'],
  ['pe_tr_', 'qc_tr_'],
  ['pe_td_', 'qc_td_'],
  ['pe_tt_', 'qc_tt_'],
  ['pe_tn_', 'qc_tn_'],
  ['pe_rw_', 'qc_rw_'],

  // ─── Command name prefixes ───
  ['pe_auto_trigger_fqc', 'qc_auto_trigger_fqc'],
  ['pe_auto_trigger_iqc', 'qc_auto_trigger_iqc'],
  ['pe_auto_trigger_pqc', 'qc_auto_trigger_pqc'],
  ['pe_batch_record_test_results', 'qc_batch_record_test_results'],
  ['pe_build_trace_tree', 'qc_build_trace_tree'],
  ['pe_calculate_quality_cost', 'qc_calculate_quality_cost'],
  ['pe_calculate_spc_limits', 'qc_calculate_spc_limits'],
  ['pe_close_capa', 'qc_close_capa'],
  ['pe_close_defect', 'qc_close_defect'],
  ['pe_close_nonconformance', 'qc_close_nonconformance'],
  ['pe_complete_fqc', 'qc_complete_fqc'],
  ['pe_complete_iqc', 'qc_complete_iqc'],
  ['pe_complete_rework', 'qc_complete_rework'],
  ['pe_create_batch_trace', 'qc_create_batch_trace'],
  ['pe_create_capa', 'qc_create_capa'],
  ['pe_create_defect_record', 'qc_create_defect_record'],
  ['pe_create_fqc_order', 'qc_create_fqc_order'],
  ['pe_create_iqc_order', 'qc_create_iqc_order'],
  ['pe_create_nonconformance', 'qc_create_nonconformance'],
  ['pe_create_pqc_record', 'qc_create_pqc_record'],
  ['pe_create_quality_cost', 'qc_create_quality_cost'],
  ['pe_create_rework_order', 'qc_create_rework_order'],
  ['pe_create_spc_chart', 'qc_create_spc_chart'],
  ['pe_create_spc_data_point', 'qc_create_spc_data_point'],
  ['pe_create_test_defect', 'qc_create_test_defect'],
  ['pe_create_test_program', 'qc_create_test_program'],
  ['pe_create_trace_node', 'qc_create_trace_node'],
  ['pe_create_trace_template', 'qc_create_trace_template'],
  ['pe_delete_capa', 'qc_delete_capa'],
  ['pe_delete_nonconformance', 'qc_delete_nonconformance'],
  ['pe_delete_pqc_record', 'qc_delete_pqc_record'],
  ['pe_delete_quality_cost', 'qc_delete_quality_cost'],
  ['pe_delete_rework_order', 'qc_delete_rework_order'],
  ['pe_delete_spc_chart', 'qc_delete_spc_chart'],
  ['pe_delete_spc_data_point', 'qc_delete_spc_data_point'],
  ['pe_deprecate_test_program', 'qc_deprecate_test_program'],
  ['pe_deprecate_trace_template', 'qc_deprecate_trace_template'],
  ['pe_activate_test_program', 'qc_activate_test_program'],
  ['pe_activate_trace_template', 'qc_activate_trace_template'],
  ['pe_fail_batch', 'qc_fail_batch'],
  ['pe_fail_retest', 'qc_fail_retest'],
  ['pe_handle_nonconformance', 'qc_handle_nonconformance'],
  ['pe_pass_retest', 'qc_pass_retest'],
  ['pe_record_spc_data', 'qc_record_spc_data'],
  ['pe_record_test_result', 'qc_record_test_result'],
  ['pe_release_batch', 'qc_release_batch'],
  ['pe_resolve_defect', 'qc_resolve_defect'],
  ['pe_start_capa', 'qc_start_capa'],
  ['pe_start_rework_order', 'qc_start_rework_order'],
  ['pe_start_rework', 'qc_start_rework'],
  ['pe_submit_retest', 'qc_submit_retest'],
  ['pe_update_batch_trace', 'qc_update_batch_trace'],
  ['pe_update_capa', 'qc_update_capa'],
  ['pe_update_defect_record', 'qc_update_defect_record'],
  ['pe_update_fqc_order', 'qc_update_fqc_order'],
  ['pe_update_iqc_order', 'qc_update_iqc_order'],
  ['pe_update_nonconformance', 'qc_update_nonconformance'],
  ['pe_update_pqc_record', 'qc_update_pqc_record'],
  ['pe_update_quality_cost', 'qc_update_quality_cost'],
  ['pe_update_rework_order', 'qc_update_rework_order'],
  ['pe_update_spc_chart', 'qc_update_spc_chart'],
  ['pe_update_spc_data_point', 'qc_update_spc_data_point'],
  ['pe_update_test_defect', 'qc_update_test_defect'],
  ['pe_update_test_program', 'qc_update_test_program'],
  ['pe_update_trace_template', 'qc_update_trace_template'],
  ['pe_validate_material_binding', 'qc_validate_material_binding'],
  ['pe_verify_capa', 'qc_verify_capa'],

  // ─── Dict codes ───
  ['pe_qc_result', 'qc_result'],
  ['pe_pqc_type', 'qc_pqc_type'],
  ['pe_defect_source', 'qc_defect_source'],
  ['pe_defect_type', 'qc_defect_type'],
  ['pe_defect_status', 'qc_defect_status'],
  ['pe_nc_type', 'qc_nc_type'],
  ['pe_nc_source_type', 'qc_nc_source_type'],
  ['pe_nc_status', 'qc_nc_status'],
  ['pe_spc_chart_type', 'qc_spc_chart_type'],
  ['pe_spc_status', 'qc_spc_status'],
  ['pe_capa_type', 'qc_capa_type'],
  ['pe_capa_source_type', 'qc_capa_source_type'],
  ['pe_capa_status', 'qc_capa_status'],
  ['pe_capa_effectiveness', 'qc_capa_effectiveness'],
  ['pe_quality_cost_type', 'qc_quality_cost_type'],
  ['pe_test_type', 'qc_test_type'],
  ['pe_tp_status', 'qc_tp_status'],
  ['pe_test_result_enum', 'qc_test_result_enum'],
  ['pe_test_defect_type', 'qc_test_defect_type'],
  ['pe_tt_status', 'qc_tt_status'],
  ['pe_trace_node_type', 'qc_trace_node_type'],
  ['pe_trace_level', 'qc_trace_level'],
  ['pe_rw_source_type', 'qc_rw_source_type'],
  ['pe_retest_result', 'qc_retest_result'],

  // ─── Permission codes ───
  ['PE.quality.manage', 'QC.quality.manage'],
  ['PE.quality.read', 'QC.quality.read'],
  ['PE.quality.spc', 'QC.quality.spc'],
  ['PE.quality.capa', 'QC.quality.capa'],
  ['PE.quality.cost', 'QC.quality.cost'],
  ['PE.quality.rework.read', 'QC.quality.rework.read'],
  ['PE.quality.rework', 'QC.quality.rework'],
  ['PE.dashboard.quality', 'QC.dashboard.quality'],
  ['PE.test.manage', 'QC.test.manage'],
  ['PE.test.read', 'QC.test.read'],

  // ─── Role codes ───
  ['PE_QUALITY_ENGINEER', 'QC_QUALITY_ENGINEER'],

  // ─── Menu codes ───
  ['pe_quality_dir', 'qc_quality_dir'],
  ['pe_quality_dashboard_menu', 'qc_quality_dashboard_menu'],
  ['pe_iqc_orders', 'qc_iqc_orders'],
  ['pe_pqc_records', 'qc_pqc_records'],
  ['pe_fqc_orders', 'qc_fqc_orders'],
  ['pe_defect_records', 'qc_defect_records'],
  ['pe_batch_traces', 'qc_batch_traces'],
  ['pe_nonconformance_menu', 'qc_nonconformance_menu'],
  ['pe_spc_menu', 'qc_spc_menu'],
  ['pe_capa_menu', 'qc_capa_menu'],
  ['pe_quality_cost_menu', 'qc_quality_cost_menu'],
  ['pe_trace_templates', 'qc_trace_templates'],
  ['pe_rework_orders', 'qc_rework_orders'],
  ['pe_test_programs', 'qc_test_programs'],
  ['pe_test_results', 'qc_test_results'],

  // ─── Command namespace prefix (pe: -> qc:) ───
  ['pe:activate_test_program', 'qc:activate_test_program'],
  ['pe:activate_trace_template', 'qc:activate_trace_template'],
  ['pe:auto_trigger_fqc', 'qc:auto_trigger_fqc'],
  ['pe:auto_trigger_iqc', 'qc:auto_trigger_iqc'],
  ['pe:auto_trigger_pqc', 'qc:auto_trigger_pqc'],
  ['pe:batch_record_test_results', 'qc:batch_record_test_results'],
  ['pe:build_trace_tree', 'qc:build_trace_tree'],
  ['pe:calculate_quality_cost', 'qc:calculate_quality_cost'],
  ['pe:calculate_spc_limits', 'qc:calculate_spc_limits'],
  ['pe:close_capa', 'qc:close_capa'],
  ['pe:close_defect', 'qc:close_defect'],
  ['pe:close_nonconformance', 'qc:close_nonconformance'],
  ['pe:complete_fqc', 'qc:complete_fqc'],
  ['pe:complete_iqc', 'qc:complete_iqc'],
  ['pe:complete_rework', 'qc:complete_rework'],
  ['pe:create_batch_trace', 'qc:create_batch_trace'],
  ['pe:create_capa', 'qc:create_capa'],
  ['pe:create_defect_record', 'qc:create_defect_record'],
  ['pe:create_fqc_order', 'qc:create_fqc_order'],
  ['pe:create_iqc_order', 'qc:create_iqc_order'],
  ['pe:create_nonconformance', 'qc:create_nonconformance'],
  ['pe:create_pqc_record', 'qc:create_pqc_record'],
  ['pe:create_quality_cost', 'qc:create_quality_cost'],
  ['pe:create_rework_order', 'qc:create_rework_order'],
  ['pe:create_spc_chart', 'qc:create_spc_chart'],
  ['pe:create_spc_data_point', 'qc:create_spc_data_point'],
  ['pe:create_test_defect', 'qc:create_test_defect'],
  ['pe:create_test_program', 'qc:create_test_program'],
  ['pe:create_trace_node', 'qc:create_trace_node'],
  ['pe:create_trace_template', 'qc:create_trace_template'],
  ['pe:delete_capa', 'qc:delete_capa'],
  ['pe:delete_nonconformance', 'qc:delete_nonconformance'],
  ['pe:delete_pqc_record', 'qc:delete_pqc_record'],
  ['pe:delete_quality_cost', 'qc:delete_quality_cost'],
  ['pe:delete_rework_order', 'qc:delete_rework_order'],
  ['pe:delete_spc_chart', 'qc:delete_spc_chart'],
  ['pe:delete_spc_data_point', 'qc:delete_spc_data_point'],
  ['pe:deprecate_test_program', 'qc:deprecate_test_program'],
  ['pe:deprecate_trace_template', 'qc:deprecate_trace_template'],
  ['pe:fail_batch', 'qc:fail_batch'],
  ['pe:fail_retest', 'qc:fail_retest'],
  ['pe:handle_nonconformance', 'qc:handle_nonconformance'],
  ['pe:pass_retest', 'qc:pass_retest'],
  ['pe:record_spc_data', 'qc:record_spc_data'],
  ['pe:record_test_result', 'qc:record_test_result'],
  ['pe:release_batch', 'qc:release_batch'],
  ['pe:resolve_defect', 'qc:resolve_defect'],
  ['pe:start_capa', 'qc:start_capa'],
  ['pe:start_rework_order', 'qc:start_rework_order'],
  ['pe:start_rework', 'qc:start_rework'],
  ['pe:submit_retest', 'qc:submit_retest'],
  ['pe:update_batch_trace', 'qc:update_batch_trace'],
  ['pe:update_capa', 'qc:update_capa'],
  ['pe:update_defect_record', 'qc:update_defect_record'],
  ['pe:update_fqc_order', 'qc:update_fqc_order'],
  ['pe:update_iqc_order', 'qc:update_iqc_order'],
  ['pe:update_nonconformance', 'qc:update_nonconformance'],
  ['pe:update_pqc_record', 'qc:update_pqc_record'],
  ['pe:update_quality_cost', 'qc:update_quality_cost'],
  ['pe:update_rework_order', 'qc:update_rework_order'],
  ['pe:update_spc_chart', 'qc:update_spc_chart'],
  ['pe:update_spc_data_point', 'qc:update_spc_data_point'],
  ['pe:update_test_defect', 'qc:update_test_defect'],
  ['pe:update_test_program', 'qc:update_test_program'],
  ['pe:update_trace_template', 'qc:update_trace_template'],
  ['pe:validate_material_binding', 'qc:validate_material_binding'],
  ['pe:verify_capa', 'qc:verify_capa'],

  // ─── Module/category references ───
  ['pcba-erp', 'quality'],
  ['pcba-manufacturing', 'quality'],
  ['/pcba-erp/quality-dashboard', '/quality/dashboard'],
  ['/pcba-erp/iqc', '/quality/iqc'],
  ['/pcba-erp/pqc', '/quality/pqc'],
  ['/pcba-erp/fqc', '/quality/fqc'],
  ['/pcba-erp/defects', '/quality/defects'],
  ['/pcba-erp/batch-trace', '/quality/batch-trace'],
  ['/pcba-erp/nonconformance', '/quality/nonconformance'],
  ['/pcba-erp/spc', '/quality/spc'],
  ['/pcba-erp/capa', '/quality/capa'],
  ['/pcba-erp/quality-cost', '/quality/quality-cost'],
  ['/pcba-erp/trace-templates', '/quality/trace-templates'],
  ['/pcba-erp/rework-orders', '/quality/rework-orders'],
  ['/pcba-erp/test-programs', '/quality/test-programs'],
  ['/pcba-erp/test-results', '/quality/test-results'],
  ['/pcba-erp/quality', '/quality'],
];

// ── FILE NAME replacement rules ──────────────────────────────────────
const FILENAME_RULES = [
  // Model-level file renames (longer first)
  ['pe_spc_data_point', 'qc_spc_data_point'],
  ['pe_quality_dashboard', 'qc_quality_dashboard'],
  ['pe_quality_cost', 'qc_quality_cost'],
  ['pe_defect_record', 'qc_defect_record'],
  ['pe_batch_trace', 'qc_batch_trace'],
  ['pe_test_program', 'qc_test_program'],
  ['pe_test_result', 'qc_test_result'],
  ['pe_test_defect', 'qc_test_defect'],
  ['pe_trace_template', 'qc_trace_template'],
  ['pe_trace_node', 'qc_trace_node'],
  ['pe_rework_order', 'qc_rework_order'],
  ['pe_nonconformance', 'qc_ncr'],
  ['pe_iqc_order', 'qc_iqc_order'],
  ['pe_pqc_record', 'qc_pqc_record'],
  ['pe_fqc_order', 'qc_fqc_order'],
  ['pe_spc_chart', 'qc_spc_chart'],
  ['pe_capa', 'qc_capa'],

  // Field prefixes in filenames
  ['pe_spd_', 'qc_spd_'],
  ['pe_iqc_', 'qc_iqc_'],
  ['pe_pqc_', 'qc_pqc_'],
  ['pe_fqc_', 'qc_fqc_'],
  ['pe_dr_', 'qc_dr_'],
  ['pe_nc_', 'qc_nc_'],
  ['pe_spc_', 'qc_spc_'],
  ['pe_capa_', 'qc_capa_'],
  ['pe_qc_', 'qc_qc_'],
  ['pe_bt_', 'qc_bt_'],
  ['pe_tp_', 'qc_tp_'],
  ['pe_tr_', 'qc_tr_'],
  ['pe_td_', 'qc_td_'],
  ['pe_tt_', 'qc_tt_'],
  ['pe_tn_', 'qc_tn_'],
  ['pe_rw_', 'qc_rw_'],

  // Command prefixes
  ['pe_auto_trigger_', 'qc_auto_trigger_'],
  ['pe_batch_record_', 'qc_batch_record_'],
  ['pe_build_trace_', 'qc_build_trace_'],
  ['pe_calculate_', 'qc_calculate_'],
  ['pe_close_', 'qc_close_'],
  ['pe_complete_', 'qc_complete_'],
  ['pe_create_', 'qc_create_'],
  ['pe_delete_', 'qc_delete_'],
  ['pe_deprecate_', 'qc_deprecate_'],
  ['pe_activate_', 'qc_activate_'],
  ['pe_fail_', 'qc_fail_'],
  ['pe_handle_', 'qc_handle_'],
  ['pe_pass_', 'qc_pass_'],
  ['pe_record_', 'qc_record_'],
  ['pe_release_', 'qc_release_'],
  ['pe_resolve_', 'qc_resolve_'],
  ['pe_start_', 'qc_start_'],
  ['pe_submit_', 'qc_submit_'],
  ['pe_update_', 'qc_update_'],
  ['pe_validate_', 'qc_validate_'],
  ['pe_verify_', 'qc_verify_'],
];

// ═══════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════

function escapeRegex(str) {
  return str.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

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

function isQualityFieldFile(fileName) {
  const name = basename(fileName, '.json');
  return QUALITY_FIELD_PREFIXES.some(prefix => name.startsWith(prefix));
}

function isQualityCommandFile(fileName) {
  const name = basename(fileName, '.json');
  return QUALITY_COMMAND_PATTERNS.some(pattern => name.includes(pattern));
}

function isQualityPageFile(fileName) {
  const name = basename(fileName, '.json');
  return QUALITY_PAGE_PREFIXES.some(prefix => name.startsWith(prefix));
}

function isQualityBindingFile(fileName) {
  const name = basename(fileName, '.json');
  return QUALITY_MODELS.includes(name);
}

function isQualityI18nEntry(entry) {
  const key = entry.key || '';
  // Model-level i18n
  for (const model of QUALITY_MODELS) {
    if (key.includes(model)) return true;
  }
  // Field-level i18n
  for (const prefix of QUALITY_FIELD_PREFIXES) {
    if (key.includes(prefix)) return true;
  }
  // Menu-level i18n
  for (const menuCode of QUALITY_MENU_CODES) {
    if (key.includes(menuCode)) return true;
  }
  // Quality dashboard
  if (key.includes('quality_dashboard') || key.includes('quality_cost')) return true;
  return false;
}

// ═══════════════════════════════════════════════════════════════════════
// Main
// ═══════════════════════════════════════════════════════════════════════

console.log('=== Migration: Extract quality models from pcba-manufacturing -> quality ===\n');

// Step 0: Validate source
if (!existsSync(SRC_DIR)) {
  console.error(`Source directory not found: ${SRC_DIR}`);
  process.exit(1);
}

// Step 1: Create quality/ directory structure
if (existsSync(DST_DIR)) {
  console.log('Removing existing quality/ directory...');
  rmSync(DST_DIR, { recursive: true, force: true });
}
console.log('Creating quality/ directory structure...');
mkdirSync(DST_DIR, { recursive: true });
mkdirSync(join(DST_DIR, 'config', 'fields'), { recursive: true });
mkdirSync(join(DST_DIR, 'config', 'commands'), { recursive: true });
mkdirSync(join(DST_DIR, 'config', 'pages'), { recursive: true });
mkdirSync(join(DST_DIR, 'config', 'bindings'), { recursive: true });

// Step 2: Extract quality models from models.json
console.log('\nExtracting quality models...');
const srcModelsPath = join(SRC_DIR, 'config', 'models.json');
const srcModels = JSON.parse(readFileSync(srcModelsPath, 'utf8'));
const qualityModels = srcModels.filter(m => QUALITY_MODELS.includes(m.code));
summary.modelsCopied = qualityModels.length;
console.log(`  Found ${qualityModels.length} quality models out of ${srcModels.length} total`);

// Write quality models.json
writeFileSync(join(DST_DIR, 'config', 'models.json'), JSON.stringify(qualityModels, null, 2) + '\n');

// Step 3: Copy quality field files
console.log('\nCopying quality field files...');
const srcFieldsDir = join(SRC_DIR, 'config', 'fields');
for (const f of readdirSync(srcFieldsDir)) {
  if (isQualityFieldFile(f)) {
    cpSync(join(srcFieldsDir, f), join(DST_DIR, 'config', 'fields', f));
    summary.fieldsCopied++;
  }
}
console.log(`  Copied ${summary.fieldsCopied} field files`);

// Step 4: Copy quality command files
console.log('\nCopying quality command files...');
const srcCommandsDir = join(SRC_DIR, 'config', 'commands');
for (const f of readdirSync(srcCommandsDir)) {
  if (isQualityCommandFile(f)) {
    cpSync(join(srcCommandsDir, f), join(DST_DIR, 'config', 'commands', f));
    summary.commandsCopied++;
  }
}
console.log(`  Copied ${summary.commandsCopied} command files`);

// Step 5: Copy quality page files
console.log('\nCopying quality page files...');
const srcPagesDir = join(SRC_DIR, 'config', 'pages');
for (const f of readdirSync(srcPagesDir)) {
  if (isQualityPageFile(f)) {
    cpSync(join(srcPagesDir, f), join(DST_DIR, 'config', 'pages', f));
    summary.pagesCopied++;
  }
}
console.log(`  Copied ${summary.pagesCopied} page files`);

// Step 6: Copy quality binding files
console.log('\nCopying quality binding files...');
const srcBindingsDir = join(SRC_DIR, 'config', 'bindings');
for (const f of readdirSync(srcBindingsDir)) {
  if (isQualityBindingFile(f)) {
    cpSync(join(srcBindingsDir, f), join(DST_DIR, 'config', 'bindings', f));
    summary.bindingsCopied++;
  }
}
console.log(`  Copied ${summary.bindingsCopied} binding files`);

// Step 7: Extract quality dicts
console.log('\nExtracting quality dicts...');
const srcDictsPath = join(SRC_DIR, 'config', 'dicts.json');
const srcDicts = JSON.parse(readFileSync(srcDictsPath, 'utf8'));
const qualityDicts = srcDicts.filter(d => QUALITY_DICT_CODES.includes(d.code));
summary.dictsCopied = qualityDicts.length;
console.log(`  Extracted ${qualityDicts.length} quality dicts`);
writeFileSync(join(DST_DIR, 'config', 'dicts.json'), JSON.stringify(qualityDicts, null, 2) + '\n');

// Step 8: Extract quality i18n entries
console.log('\nExtracting quality i18n entries...');
const srcI18nPath = join(SRC_DIR, 'config', 'i18n.json');
const srcI18n = JSON.parse(readFileSync(srcI18nPath, 'utf8'));
const qualityI18n = srcI18n.filter(e => isQualityI18nEntry(e));
summary.i18nCopied = qualityI18n.length;
console.log(`  Extracted ${qualityI18n.length} quality i18n entries`);
writeFileSync(join(DST_DIR, 'config', 'i18n.json'), JSON.stringify(qualityI18n, null, 2) + '\n');

// Step 9: Extract quality menus
console.log('\nExtracting quality menus...');
const srcMenusPath = join(SRC_DIR, 'config', 'menus.json');
const srcMenus = JSON.parse(readFileSync(srcMenusPath, 'utf8'));
const qualityMenus = srcMenus.filter(m => QUALITY_MENU_CODES.includes(m.code));
// Fix root: quality_dir should have no parentCode (will become root)
for (const menu of qualityMenus) {
  if (menu.code === 'pe_quality_dir') {
    delete menu.parentCode;
    menu.orderNo = 400;
  }
  // Rework and test menus are under pe_mfg_dir in source — move to quality
  if (menu.parentCode === 'pe_mfg_dir') {
    menu.parentCode = 'pe_quality_dir';
  }
}
summary.menusCopied = qualityMenus.length;
console.log(`  Extracted ${qualityMenus.length} quality menu entries`);
writeFileSync(join(DST_DIR, 'config', 'menus.json'), JSON.stringify(qualityMenus, null, 2) + '\n');

// Step 10: Extract quality permissions
console.log('\nExtracting quality permissions...');
const srcPermsPath = join(SRC_DIR, 'config', 'permissions.json');
const srcPerms = JSON.parse(readFileSync(srcPermsPath, 'utf8'));
const qualityPerms = srcPerms.filter(p => QUALITY_PERMISSION_CODES.includes(p.code));
console.log(`  Extracted ${qualityPerms.length} quality permissions`);
writeFileSync(join(DST_DIR, 'config', 'permissions.json'), JSON.stringify(qualityPerms, null, 2) + '\n');

// Step 11: Create quality roles
console.log('\nCreating quality roles...');
const qualityRoles = [
  {
    "code": "PE_QUALITY_ENGINEER",
    "name:zh-CN": "质量工程师",
    "name:en": "Quality Engineer",
    "description": "Manage quality inspections, rework orders, NCR, SPC, CAPA",
    "module": "quality",
    "permissions": [
      "PE.quality.manage", "PE.quality.read", "PE.quality.rework",
      "PE.quality.rework.read", "PE.quality.spc", "PE.quality.capa",
      "PE.quality.cost", "PE.dashboard.quality",
      "PE.test.manage", "PE.test.read"
    ]
  }
];
writeFileSync(join(DST_DIR, 'config', 'roles.json'), JSON.stringify(qualityRoles, null, 2) + '\n');

// Step 12: Create default-bootstrap.json
console.log('Creating default-bootstrap.json...');
const bootstrap = {
  "rolePermissionBindings": [
    {
      "roleCode": "TENANT_ADMIN",
      "permissionCodes": ["*"]
    }
  ]
};
writeFileSync(join(DST_DIR, 'config', 'default-bootstrap.json'), JSON.stringify(bootstrap, null, 2) + '\n');

// Step 13: Apply content replacements to ALL quality JSON files
console.log('\nApplying content replacements...');
const allQualityJsonFiles = walkDir(join(DST_DIR, 'config')).filter(f => f.endsWith('.json'));

for (const filePath of allQualityJsonFiles) {
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
console.log(`  Transformed ${summary.filesTransformed} files`);

// Step 14: Rename files
console.log('\nRenaming files...');
for (const subdir of ['fields', 'commands', 'pages', 'bindings']) {
  const dirPath = join(DST_DIR, 'config', subdir);
  if (!existsSync(dirPath)) continue;

  const files = readdirSync(dirPath).sort().reverse(); // longer names first
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
console.log(`  Renamed ${summary.filesRenamed} files`);

// Step 15: Write plugin.json
console.log('\nWriting plugin.json...');
const pluginJson = {
  "pluginId": "com.auraboot.quality",
  "namespace": "qc",
  "version": "1.0.0",
  "dslVersion": 1,
  "pluginType": "config",
  "displayName:zh-CN": "质量管理",
  "displayName:en": "Quality Management",
  "description": "Generic quality management: IQC, PQC, FQC, NCR, CAPA, defect tracking, SPC, rework, traceability",
  "author": "AuraBoot Team",
  "homepage": "https://auraboot.com/plugins/quality",
  "minPlatformVersion": "1.0.0",
  "dependencies": [
    "com.auraboot.inventory",
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

// Step 16: Remove extracted models from pcba-manufacturing
console.log('\n=== Cleaning pcba-manufacturing (removing extracted quality resources) ===');

// Remove quality models from models.json
const remainingModels = srcModels.filter(m => !QUALITY_MODELS.includes(m.code));
console.log(`  models.json: ${srcModels.length} -> ${remainingModels.length} (removed ${srcModels.length - remainingModels.length})`);
writeFileSync(srcModelsPath, JSON.stringify(remainingModels, null, 2) + '\n');

// Remove quality field files from pcba-manufacturing
let srcFieldsRemoved = 0;
for (const f of readdirSync(srcFieldsDir)) {
  if (isQualityFieldFile(f)) {
    rmSync(join(srcFieldsDir, f));
    srcFieldsRemoved++;
  }
}
console.log(`  fields: removed ${srcFieldsRemoved} files`);

// Remove quality command files from pcba-manufacturing
let srcCommandsRemoved = 0;
for (const f of readdirSync(srcCommandsDir)) {
  if (isQualityCommandFile(f)) {
    rmSync(join(srcCommandsDir, f));
    srcCommandsRemoved++;
  }
}
console.log(`  commands: removed ${srcCommandsRemoved} files`);

// Remove quality page files from pcba-manufacturing
let srcPagesRemoved = 0;
for (const f of readdirSync(srcPagesDir)) {
  if (isQualityPageFile(f)) {
    rmSync(join(srcPagesDir, f));
    srcPagesRemoved++;
  }
}
console.log(`  pages: removed ${srcPagesRemoved} files`);

// Remove quality binding files from pcba-manufacturing
let srcBindingsRemoved = 0;
for (const f of readdirSync(srcBindingsDir)) {
  if (isQualityBindingFile(f)) {
    rmSync(join(srcBindingsDir, f));
    srcBindingsRemoved++;
  }
}
console.log(`  bindings: removed ${srcBindingsRemoved} files`);

// Remove quality dicts from pcba-manufacturing
const remainingDicts = srcDicts.filter(d => !QUALITY_DICT_CODES.includes(d.code));
console.log(`  dicts.json: ${srcDicts.length} -> ${remainingDicts.length} (removed ${srcDicts.length - remainingDicts.length})`);
writeFileSync(srcDictsPath, JSON.stringify(remainingDicts, null, 2) + '\n');

// Remove quality i18n entries from pcba-manufacturing
const remainingI18n = srcI18n.filter(e => !isQualityI18nEntry(e));
console.log(`  i18n.json: ${srcI18n.length} -> ${remainingI18n.length} (removed ${srcI18n.length - remainingI18n.length})`);
writeFileSync(srcI18nPath, JSON.stringify(remainingI18n, null, 2) + '\n');

// Remove quality menus from pcba-manufacturing
const remainingMenus = srcMenus.filter(m => !QUALITY_MENU_CODES.includes(m.code));
console.log(`  menus.json: ${srcMenus.length} -> ${remainingMenus.length} (removed ${srcMenus.length - remainingMenus.length})`);
writeFileSync(srcMenusPath, JSON.stringify(remainingMenus, null, 2) + '\n');

// Remove quality permissions from pcba-manufacturing
const remainingPerms = srcPerms.filter(p => !QUALITY_PERMISSION_CODES.includes(p.code));
console.log(`  permissions.json: ${srcPerms.length} -> ${remainingPerms.length} (removed ${srcPerms.length - remainingPerms.length})`);
writeFileSync(srcPermsPath, JSON.stringify(remainingPerms, null, 2) + '\n');

// Remove quality roles from pcba-manufacturing
const srcRolesPath = join(SRC_DIR, 'config', 'roles.json');
const srcRoles = JSON.parse(readFileSync(srcRolesPath, 'utf8'));
const remainingRoles = srcRoles.filter(r => r.code !== 'PE_QUALITY_ENGINEER');
// Also clean quality permission references from remaining roles
for (const role of remainingRoles) {
  if (role.permissions) {
    role.permissions = role.permissions.filter(p => !QUALITY_PERMISSION_CODES.includes(p));
  }
}
console.log(`  roles.json: ${srcRoles.length} -> ${remainingRoles.length} (removed ${srcRoles.length - remainingRoles.length})`);
writeFileSync(srcRolesPath, JSON.stringify(remainingRoles, null, 2) + '\n');

// Update pcba-manufacturing plugin.json to add quality dependency
const srcPluginPath = join(SRC_DIR, 'plugin.json');
const srcPlugin = JSON.parse(readFileSync(srcPluginPath, 'utf8'));
if (!srcPlugin.dependencies.includes('com.auraboot.quality')) {
  srcPlugin.dependencies.push('com.auraboot.quality');
  writeFileSync(srcPluginPath, JSON.stringify(srcPlugin, null, 2) + '\n');
  console.log('  Added com.auraboot.quality to pcba-manufacturing dependencies');
}

// ═══════════════════════════════════════════════════════════════════════
// Summary
// ═══════════════════════════════════════════════════════════════════════

console.log('\n=== Migration Complete ===');
console.log(`  Models extracted:            ${summary.modelsCopied}`);
console.log(`  Field files copied:          ${summary.fieldsCopied}`);
console.log(`  Command files copied:        ${summary.commandsCopied}`);
console.log(`  Page files copied:           ${summary.pagesCopied}`);
console.log(`  Binding files copied:        ${summary.bindingsCopied}`);
console.log(`  Dict entries extracted:       ${summary.dictsCopied}`);
console.log(`  i18n entries extracted:       ${summary.i18nCopied}`);
console.log(`  Menu entries extracted:       ${summary.menusCopied}`);
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
    console.log(`  ${f.replace(DST_DIR, 'quality')}`);
  }
} else {
  console.log('  All files renamed successfully (no pe_ prefixes remaining in filenames)');
}

// Check for pe_ references in content (within quality plugin)
let peContentCount = 0;
const peContentFiles = [];
for (const f of finalFiles.filter(f => f.endsWith('.json'))) {
  const content = readFileSync(f, 'utf8');
  const matches = content.match(/\bpe_[a-z]/g);
  if (matches) {
    peContentCount += matches.length;
    peContentFiles.push({ file: basename(f), matches: [...new Set(matches)].slice(0, 5) });
  }
}
if (peContentCount > 0) {
  console.log(`  INFO: ${peContentCount} pe_ references remaining in content (likely cross-plugin refs to manufacturing/inventory models):`);
  for (const { file, matches } of peContentFiles.slice(0, 10)) {
    console.log(`    ${file}: ${matches.join(', ')}`);
  }
} else {
  console.log('  All pe_ content references replaced');
}

// Count final files
const finalFieldCount = readdirSync(join(DST_DIR, 'config', 'fields')).length;
const finalCommandCount = readdirSync(join(DST_DIR, 'config', 'commands')).length;
const finalPageCount = readdirSync(join(DST_DIR, 'config', 'pages')).length;
const finalBindingCount = readdirSync(join(DST_DIR, 'config', 'bindings')).length;
console.log(`\n  Final file counts:`);
console.log(`    Fields:   ${finalFieldCount}`);
console.log(`    Commands: ${finalCommandCount}`);
console.log(`    Pages:    ${finalPageCount}`);
console.log(`    Bindings: ${finalBindingCount}`);

console.log('\nDone!');
