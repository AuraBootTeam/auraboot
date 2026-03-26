#!/usr/bin/env node
/**
 * Split PCBA ERP monolithic plugin into 8 sub-plugins.
 * Usage: node scripts/split-pcba-plugin.mjs
 */

import fs from 'fs';
import path from 'path';

const SRC = 'plugins/pcba-erp';
const DEST = 'plugins';

// ============================================================
// Model assignments per sub-plugin
// ============================================================
const PLUGIN_MODELS = {
  'pcba-base': [
    'pe_brand','pe_category','pe_product','pe_bom','pe_bom_line',
    'pe_alternative_material','pe_customer','pe_supplier','pe_warehouse',
    'pe_warehouse_location','pe_inventory','pe_department','pe_position',
    'pe_employee','pe_production_version','pe_contract',
  ],
  'pcba-crm': [
    'pe_lead','pe_opportunity','pe_rfq','pe_customer_contact',
    'pe_customer_followup','pe_customer_complaint',
  ],
  'pcba-srm': [
    'pe_supplier_contact','pe_supplier_eval','pe_supplier_qualification',
    'pe_supplier_price','pe_order_confirmation','pe_asn',
  ],
  'pcba-sales': [
    'pe_sales_quotation','pe_sales_quotation_line','pe_sales_order',
    'pe_sales_order_line','pe_shipment','pe_shipment_line',
    'pe_sales_collection','pe_sales_return','pe_sales_return_line',
    'pe_order_change','pe_rma','pe_credit_memo','pe_packing','pe_packing_line',
  ],
  'pcba-procurement': [
    'pe_purchase_request','pe_purchase_order','pe_purchase_order_line',
    'pe_purchase_receipt','pe_purchase_receipt_line','pe_purchase_payment',
    'pe_purchase_return','pe_purchase_return_line','pe_outsource_order',
    'pe_outsource_order_line','pe_outsource_receipt','pe_outsource_receipt_line',
  ],
  'pcba-wms': [
    'pe_warehouse_in','pe_warehouse_in_line','pe_warehouse_out',
    'pe_warehouse_out_line','pe_stock_transfer','pe_stock_transfer_line',
    'pe_stock_check','pe_stock_check_line','pe_inventory_hold',
    'pe_lot','pe_lot_transaction','pe_wave','pe_pick_order',
    'pe_pick_order_line','pe_msd_record',
  ],
  'pcba-manufacturing': [
    'pe_production_plan','pe_production_line','pe_material_requirement',
    'pe_routing','pe_operation','pe_work_order_op','pe_work_report',
    'pe_workstation','pe_workstation_assignment','pe_operation_exception',
    'pe_resource','pe_resource_calendar','pe_schedule_result',
    'pe_mrp_run','pe_planned_order','pe_mrp_exception',
    'pe_equipment','pe_equipment_maintenance','pe_spare_part','pe_eq_downtime',
    'pe_iqc_order','pe_pqc_record','pe_fqc_order','pe_defect_record',
    'pe_nonconformance','pe_batch_trace','pe_spc_chart','pe_spc_data_point',
    'pe_capa','pe_quality_cost','pe_rework_order',
    'pe_test_program','pe_test_result','pe_test_defect',
    'pe_trace_template','pe_trace_node',
  ],
  'pcba-finance': [
    'pe_account','pe_fiscal_period','pe_journal_entry','pe_journal_line',
    'pe_gl_balance','pe_ar_transaction','pe_ap_transaction',
    'pe_financial_report','pe_restatement','pe_three_way_match',
    'pe_cost_center','pe_cost_estimate','pe_cost_detail','pe_expense_claim',
    'pe_ecn','pe_eco','pe_eco_affected_item',
    'pe_kpi_definition','pe_kpi_snapshot',
    'pe_compliance_doc','pe_compliance_checklist',
  ],
};

// Reverse map: model_code -> plugin_name
const modelToPlugin = {};
for (const [plugin, models] of Object.entries(PLUGIN_MODELS)) {
  for (const m of models) modelToPlugin[m] = plugin;
}

// ============================================================
// Plugin metadata
// ============================================================
const PLUGIN_META = {
  'pcba-base': {
    pluginId: 'com.auraboot.pcba-base',
    displayNameZh: 'PCBA 基础主数据',
    displayNameEn: 'PCBA Base Master Data',
    description: 'Base master data: products, BOM, customers, suppliers, warehouses, HR, contracts',
    dependencies: [],
  },
  'pcba-crm': {
    pluginId: 'com.auraboot.pcba-crm',
    displayNameZh: 'PCBA CRM',
    displayNameEn: 'PCBA CRM',
    description: 'Customer relationship management: leads, opportunities, RFQ, contacts, followups, complaints',
    dependencies: ['com.auraboot.pcba-base'],
  },
  'pcba-srm': {
    pluginId: 'com.auraboot.pcba-srm',
    displayNameZh: 'PCBA SRM',
    displayNameEn: 'PCBA SRM',
    description: 'Supplier relationship management: contacts, evaluations, qualifications, pricing, ASN',
    dependencies: ['com.auraboot.pcba-base'],
  },
  'pcba-sales': {
    pluginId: 'com.auraboot.pcba-sales',
    displayNameZh: 'PCBA 销售管理',
    displayNameEn: 'PCBA Sales',
    description: 'Sales management: quotations, orders, shipments, returns, RMA, credit memos, packing',
    dependencies: ['com.auraboot.pcba-base', 'com.auraboot.pcba-crm'],
  },
  'pcba-procurement': {
    pluginId: 'com.auraboot.pcba-procurement',
    displayNameZh: 'PCBA 采购管理',
    displayNameEn: 'PCBA Procurement',
    description: 'Procurement management: purchase requests, orders, receipts, returns, outsourcing',
    dependencies: ['com.auraboot.pcba-base', 'com.auraboot.pcba-srm'],
  },
  'pcba-wms': {
    pluginId: 'com.auraboot.pcba-wms',
    displayNameZh: 'PCBA 仓储管理',
    displayNameEn: 'PCBA WMS',
    description: 'Warehouse management: inbound, outbound, transfers, stock checks, lots, waves, picking, MSD',
    dependencies: ['com.auraboot.pcba-base'],
  },
  'pcba-manufacturing': {
    pluginId: 'com.auraboot.pcba-manufacturing',
    displayNameZh: 'PCBA 生产制造',
    displayNameEn: 'PCBA Manufacturing',
    description: 'Manufacturing: production, MES, MRP, APS, quality, equipment, test, traceability',
    dependencies: ['com.auraboot.pcba-base', 'com.auraboot.pcba-wms'],
  },
  'pcba-finance': {
    pluginId: 'com.auraboot.pcba-finance',
    displayNameZh: 'PCBA 财务管理',
    displayNameEn: 'PCBA Finance',
    description: 'Finance: GL, AR/AP, reports, cost, expense, ECM, KPI, compliance',
    dependencies: ['com.auraboot.pcba-base'],
  },
};

// ============================================================
// Permission prefix -> plugin mapping
// ============================================================
const PERMISSION_PREFIXES = {
  'pcba-base': ['PE.product.','PE.company.','PE.hr.','PE.contract.','PE.dashboard.read',
                 'PE.production.version'],
  'pcba-crm': ['PE.crm.'],
  'pcba-srm': ['PE.srm.'],
  'pcba-sales': ['PE.sales.','PE.rma.','PE.wms.packing','PE.financial.credit_memo'],
  'pcba-procurement': ['PE.purchase.','PE.outsource.'],
  'pcba-wms': ['PE.warehouse.','PE.inventory_hold.','PE.wms.pick.','PE.wms.lot.','PE.quality.msd'],
  'pcba-manufacturing': [
    'PE.production.manage','PE.production.read',
    'PE.mrp.','PE.resource.','PE.aps.','PE.mes.',
    'PE.quality.manage','PE.quality.read','PE.quality.spc','PE.quality.capa',
    'PE.quality.cost','PE.quality.rework','PE.dashboard.quality',
    'PE.equipment.','PE.spare_part.','PE.test.',
    'PE.workstation.','PE.workstation_assignment.','PE.operation_exception.',
    'PE.shop_floor.','PE.planned_order.','PE.mrp_exception.','PE.mrp_dashboard.',
  ],
  'pcba-finance': [
    'PE.financial.read','PE.financial.manage','PE.financial.admin',
    'PE.financial.report','PE.financial.restate','PE.financial.three_way_match',
    'PE.expense.','PE.ecm.','PE.bi.','PE.cost.',
    'PE.three_way_match.','PE.compliance.','PE.dashboard.financial',
  ],
};

function findPermissionPlugin(code) {
  for (const [plugin, prefixes] of Object.entries(PERMISSION_PREFIXES)) {
    for (const prefix of prefixes) {
      if (code === prefix || code.startsWith(prefix)) return plugin;
    }
  }
  return 'pcba-base'; // fallback
}

// ============================================================
// Command name -> model mapping (for assigning commands to plugins)
// ============================================================
// Build a sorted list of model codes (longest first) for prefix matching
const allModelCodes = Object.values(PLUGIN_MODELS).flat().sort((a, b) => b.length - a.length);

function findCommandPlugin(cmdFileName) {
  // cmdFileName like "pe_create_brand.json" -> strip .json -> "pe_create_brand"
  const name = cmdFileName.replace('.json', '');

  // IMPORTANT: Check exact overrides FIRST, before model-code matching.
  // Some commands (e.g. pe_allocate_inventory) match model suffixes incorrectly
  // if we let the model loop run first.
  const COMMAND_EXACT = {
    'pe_advance_opp_to_negotiation': 'pcba-crm',
    'pe_advance_opp_to_proposal': 'pcba-crm',
    'pe_advance_stage': 'pcba-crm',
    'pe_auto_putaway': 'pcba-wms',
    'pe_build_trace_tree': 'pcba-manufacturing',
    'pe_calculate_atp': 'pcba-sales',
    'pe_close_period': 'pcba-finance',
    'pe_complete_approval': 'pcba-base',
    'pe_complete_eq_maintenance': 'pcba-manufacturing',
    'pe_convert_request_to_po': 'pcba-procurement',
    'pe_create_ap_from_receipt': 'pcba-finance',
    'pe_create_ar_from_shipment': 'pcba-finance',
    'pe_create_eq_maintenance': 'pcba-manufacturing',
    'pe_delete_eq_maintenance': 'pcba-manufacturing',
    'pe_delete_match': 'pcba-finance',
    'pe_fail_batch': 'pcba-manufacturing',
    'pe_fail_retest': 'pcba-manufacturing',
    'pe_generate_balance_sheet': 'pcba-finance',
    'pe_generate_calendar': 'pcba-manufacturing',
    'pe_generate_executive_summary': 'pcba-finance',
    'pe_generate_income_statement': 'pcba-finance',
    'pe_generate_journal_from_purchase': 'pcba-finance',
    'pe_generate_journal_from_sales': 'pcba-finance',
    'pe_generate_trial_balance': 'pcba-finance',
    'pe_open_period': 'pcba-finance',
    'pe_pass_retest': 'pcba-manufacturing',
    'pe_recalculate_gl': 'pcba-finance',
    'pe_release_batch': 'pcba-manufacturing',
    'pe_report_exception': 'pcba-manufacturing',
    'pe_resolve_exception': 'pcba-manufacturing',
    'pe_send_materials': 'pcba-procurement',
    'pe_start_eq_maintenance': 'pcba-manufacturing',
    'pe_submit_retest': 'pcba-manufacturing',
    'pe_update_eq_maintenance': 'pcba-manufacturing',
    'pe_validate_material_binding': 'pcba-manufacturing',
    'pe_allocate_inventory': 'pcba-wms',
    'pe_hold_inventory': 'pcba-wms',
    'pe_cancel_production': 'pcba-manufacturing',
    'pe_complete_production': 'pcba-manufacturing',
    'pe_confirm_production': 'pcba-manufacturing',
    'pe_start_production': 'pcba-manufacturing',
    'pe_complete_approval': 'pcba-procurement',
    'pe_create_quotation_from_rfq': 'pcba-sales',
    'pe_create_ar_from_shipment': 'pcba-finance',
    'pe_resolve_match_hold': 'pcba-finance',
    'pe_check_compliance': 'pcba-sales',
    'pe_write_off_ap': 'pcba-finance',
    'pe_write_off_ar': 'pcba-finance',
    'pe_init_chart_of_accounts': 'pcba-finance',
    'pe_post_journal_entry': 'pcba-finance',
    'pe_void_journal_entry': 'pcba-finance',
    'pe_generate_pick_order': 'pcba-wms',
  };
  // Check exact match first (already at top of function)
  if (COMMAND_EXACT[name]) return COMMAND_EXACT[name];

  // Try to match against known model codes or their abbreviations
  // Strategy: check if command name contains a model-related keyword
  for (const model of allModelCodes) {
    const suffix = model.replace('pe_', '');
    // Match patterns like pe_create_brand, pe_update_brand, pe_delete_brand
    // Also pe_add_bom_line, pe_activate_bom, etc.
    if (name.includes(`_${suffix}`) || name.endsWith(`_${suffix}`)) {
      return modelToPlugin[model];
    }
  }

  // Special abbreviation mappings for commands that use short names
  const COMMAND_ABBREV = {
    'sq_line': 'pcba-sales', 'so_line': 'pcba-sales', 'ship_line': 'pcba-sales',
    'sr_line': 'pcba-sales', 'quotation': 'pcba-sales', 'sales_quotation': 'pcba-sales',
    'sales_order': 'pcba-sales', 'sales_collection': 'pcba-sales', 'sales_return': 'pcba-sales',
    'shipment': 'pcba-sales', 'credit_memo': 'pcba-sales', 'rma': 'pcba-sales',
    'order_change': 'pcba-sales', 'packing': 'pcba-sales',
    'po_line': 'pcba-procurement', 'pr_line': 'pcba-procurement', 'rcpt_line': 'pcba-procurement',
    'purchase_order': 'pcba-procurement', 'purchase_request': 'pcba-procurement',
    'purchase_receipt': 'pcba-procurement', 'purchase_payment': 'pcba-procurement',
    'purchase_return': 'pcba-procurement', 'outsource': 'pcba-procurement',
    'wh_in_line': 'pcba-wms', 'wh_out_line': 'pcba-wms', 'sc_line': 'pcba-wms',
    'st_line': 'pcba-wms', 'warehouse_in': 'pcba-wms', 'warehouse_out': 'pcba-wms',
    'stock_transfer': 'pcba-wms', 'stock_check': 'pcba-wms', 'wave': 'pcba-wms',
    'pick': 'pcba-wms', 'lot': 'pcba-wms', 'msd': 'pcba-wms',
    'inventory': 'pcba-wms', 'hold': 'pcba-wms', 'allocation': 'pcba-wms',
    'production_plan': 'pcba-manufacturing', 'production_line': 'pcba-manufacturing',
    'material_requirement': 'pcba-manufacturing', 'routing': 'pcba-manufacturing',
    'operation': 'pcba-manufacturing', 'work_order': 'pcba-manufacturing',
    'work_report': 'pcba-manufacturing', 'workstation': 'pcba-manufacturing',
    'resource': 'pcba-manufacturing', 'schedule': 'pcba-manufacturing',
    'mrp': 'pcba-manufacturing', 'planned_order': 'pcba-manufacturing',
    'equipment': 'pcba-manufacturing', 'spare_part': 'pcba-manufacturing',
    'iqc': 'pcba-manufacturing', 'pqc': 'pcba-manufacturing', 'fqc': 'pcba-manufacturing',
    'defect': 'pcba-manufacturing', 'nonconformance': 'pcba-manufacturing',
    'batch_trace': 'pcba-manufacturing', 'spc': 'pcba-manufacturing',
    'capa': 'pcba-manufacturing', 'quality_cost': 'pcba-manufacturing',
    'rework': 'pcba-manufacturing', 'test_program': 'pcba-manufacturing',
    'test_result': 'pcba-manufacturing', 'test_defect': 'pcba-manufacturing',
    'trace_template': 'pcba-manufacturing', 'trace_node': 'pcba-manufacturing',
    'account': 'pcba-finance', 'fiscal_period': 'pcba-finance',
    'journal': 'pcba-finance', 'gl_balance': 'pcba-finance',
    'ar_transaction': 'pcba-finance', 'ap_transaction': 'pcba-finance',
    'financial_report': 'pcba-finance', 'restatement': 'pcba-finance',
    'three_way_match': 'pcba-finance', 'cost_center': 'pcba-finance',
    'cost_estimate': 'pcba-finance', 'cost_detail': 'pcba-finance',
    'expense_claim': 'pcba-finance', 'ecn': 'pcba-finance', 'eco': 'pcba-finance',
    'kpi': 'pcba-finance', 'compliance': 'pcba-finance',
    'lead': 'pcba-crm', 'opportunity': 'pcba-crm', 'rfq': 'pcba-crm',
    'customer_contact': 'pcba-crm', 'customer_followup': 'pcba-crm',
    'complaint': 'pcba-crm',
    'supplier_contact': 'pcba-srm', 'supplier_eval': 'pcba-srm',
    'supplier_qualification': 'pcba-srm', 'supplier_price': 'pcba-srm',
    'order_confirmation': 'pcba-srm', 'asn': 'pcba-srm', 'oc': 'pcba-srm',
    'brand': 'pcba-base', 'category': 'pcba-base', 'product': 'pcba-base',
    'bom': 'pcba-base', 'alt_material': 'pcba-base', 'customer': 'pcba-base',
    'supplier': 'pcba-base', 'warehouse': 'pcba-base', 'department': 'pcba-base',
    'position': 'pcba-base', 'employee': 'pcba-base', 'contract': 'pcba-base',
    'production_version': 'pcba-base',
  };
  // Try abbreviation matching (longest key first)
  const sortedAbbrevs = Object.keys(COMMAND_ABBREV).sort((a, b) => b.length - a.length);
  for (const abbrev of sortedAbbrevs) {
    if (name.includes(abbrev)) return COMMAND_ABBREV[abbrev];
  }
  console.warn(`  [WARN] Cannot assign command "${cmdFileName}" to any plugin, defaulting to pcba-base`);
  return 'pcba-base';
}

// ============================================================
// Page file -> plugin mapping
// ============================================================
// Dashboard pages have special names
const DASHBOARD_PAGES = {
  'pe_sales_dashboard.json': 'pcba-sales',
  'pe_inventory_dashboard.json': 'pcba-base',
  'pe_production_dashboard.json': 'pcba-manufacturing',
  'pe_executive_dashboard.json': 'pcba-base',
  'pe_financial_dashboard.json': 'pcba-finance',
  'pe_quality_dashboard.json': 'pcba-manufacturing',
  'pe_mrp_dashboard.json': 'pcba-manufacturing',
  'pe_wms_dashboard.json': 'pcba-wms',
  'pe_shop_floor_dashboard.json': 'pcba-manufacturing',
  'pe_pmo_dashboard.json': 'pcba-manufacturing',
};

function findPagePlugin(pageFileName) {
  if (DASHBOARD_PAGES[pageFileName]) return DASHBOARD_PAGES[pageFileName];
  // Strip suffix like _list, _form, _detail and .json
  const base = pageFileName.replace('.json', '').replace(/_(list|form|detail)$/, '');
  if (modelToPlugin[base]) return modelToPlugin[base];
  // Fallback: try matching against model codes
  for (const model of allModelCodes) {
    if (base.startsWith(model)) return modelToPlugin[model];
  }
  console.warn(`  [WARN] Cannot assign page "${pageFileName}" to any plugin, defaulting to pcba-base`);
  return 'pcba-base';
}

// ============================================================
// Menu -> plugin mapping (by permissionCode or pageKey)
// ============================================================
function findMenuPlugin(menu) {
  // Root menu goes to base
  if (menu.code === 'pe_root') return 'pcba-base';
  // By permissionCode
  if (menu.permissionCode) {
    return findPermissionPlugin(menu.permissionCode);
  }
  // By pageKey
  if (menu.pageKey) {
    return findPagePlugin(menu.pageKey + '.json');
  }
  return 'pcba-base';
}

// ============================================================
// i18n key -> plugin mapping
// ============================================================
function findI18nPlugin(entry) {
  const key = entry.key;
  // model.pe_brand._meta.label -> extract pe_brand
  // field.pe_brand_code.label -> extract pe_brand from pe_brand_code
  // command.pe_create_brand.label -> use command mapping
  // dict.pe_enable_status.label -> use dict mapping (handled separately)
  const parts = key.split('.');
  if (parts[0] === 'model' && parts[1]) {
    const modelCode = parts[1];
    if (modelToPlugin[modelCode]) return modelToPlugin[modelCode];
  }
  if (parts[0] === 'field' && parts[1]) {
    const fieldCode = parts[1];
    // Match field prefix to model: pe_brand_code -> pe_brand
    for (const model of allModelCodes) {
      const prefix = model.replace('pe_', 'pe_');
      if (fieldCode.startsWith(prefix.replace('pe_', ''))) {
        // More precise: check binding files
      }
    }
    // Simpler: match by longest model code prefix
    for (const model of allModelCodes) {
      // field code like pe_brand_code -> starts with brand prefix
      // model is pe_brand -> field prefix pattern varies
      // Use binding-based approach below
    }
  }
  // Fallback: try matching key against model codes
  for (const model of allModelCodes) {
    if (key.includes(model)) return modelToPlugin[model];
  }
  // For dict entries, they go to base by default
  if (parts[0] === 'dict') return null; // handled by dict splitting
  return null; // unassigned
}

// ============================================================
// Dict -> plugin mapping
// ============================================================
// Common dicts used across multiple plugins go to base
const COMMON_DICTS = [
  'pe_enable_status', 'pe_unit', 'pe_priority', 'pe_currency',
  'pe_payment_method', 'pe_payment_terms',
];

// Dict prefix -> plugin mapping
const DICT_PREFIXES = {
  'pe_lead_': 'pcba-crm', 'pe_opp_': 'pcba-crm', 'pe_rfq_': 'pcba-crm',
  'pe_complaint_': 'pcba-crm', 'pe_followup_': 'pcba-crm',
  'pe_se_': 'pcba-srm', 'pe_sq_': 'pcba-srm', 'pe_asn_': 'pcba-srm',
  'pe_oc_': 'pcba-srm', 'pe_supplier_level': 'pcba-srm',
  'pe_so_': 'pcba-sales', 'pe_ship_': 'pcba-sales', 'pe_sr_': 'pcba-sales',
  'pe_rma_': 'pcba-sales', 'pe_cm_': 'pcba-sales', 'pe_pack_': 'pcba-sales',
  'pe_order_change_': 'pcba-sales', 'pe_sales_': 'pcba-sales',
  'pe_po_': 'pcba-procurement', 'pe_pr_': 'pcba-procurement',
  'pe_preq_': 'pcba-procurement', 'pe_rcpt_': 'pcba-procurement',
  'pe_outsource_': 'pcba-procurement', 'pe_pay_': 'pcba-procurement',
  'pe_wh_': 'pcba-wms', 'pe_st_': 'pcba-wms', 'pe_sc_': 'pcba-wms',
  'pe_wave_': 'pcba-wms', 'pe_pick_': 'pcba-wms', 'pe_lot_': 'pcba-wms',
  'pe_msd_': 'pcba-wms', 'pe_ih_': 'pcba-wms', 'pe_inventory_': 'pcba-wms',
  'pe_pp_': 'pcba-manufacturing', 'pe_pl_': 'pcba-manufacturing',
  'pe_mr_': 'pcba-manufacturing', 'pe_rt_': 'pcba-manufacturing',
  'pe_op_': 'pcba-manufacturing', 'pe_woo_': 'pcba-manufacturing',
  'pe_wr_': 'pcba-manufacturing', 'pe_ws_': 'pcba-manufacturing',
  'pe_res_': 'pcba-manufacturing', 'pe_sched_': 'pcba-manufacturing',
  'pe_mrp_': 'pcba-manufacturing', 'pe_plo_': 'pcba-manufacturing',
  'pe_eq_': 'pcba-manufacturing', 'pe_em_': 'pcba-manufacturing',
  'pe_spr_': 'pcba-manufacturing', 'pe_dt_': 'pcba-manufacturing',
  'pe_iqc_': 'pcba-manufacturing', 'pe_pqc_': 'pcba-manufacturing',
  'pe_fqc_': 'pcba-manufacturing', 'pe_dr_': 'pcba-manufacturing',
  'pe_nc_': 'pcba-manufacturing', 'pe_bt_': 'pcba-manufacturing',
  'pe_spc_': 'pcba-manufacturing', 'pe_capa_': 'pcba-manufacturing',
  'pe_qc_': 'pcba-manufacturing', 'pe_rw_': 'pcba-manufacturing',
  'pe_tp_': 'pcba-manufacturing', 'pe_tr_': 'pcba-manufacturing',
  'pe_td_': 'pcba-manufacturing', 'pe_tt_': 'pcba-manufacturing',
  'pe_tn_': 'pcba-manufacturing', 'pe_production_': 'pcba-manufacturing',
  'pe_quality_': 'pcba-manufacturing', 'pe_defect_': 'pcba-manufacturing',
  'pe_test_': 'pcba-manufacturing', 'pe_trace_': 'pcba-manufacturing',
  'pe_acc_': 'pcba-finance', 'pe_fp_': 'pcba-finance',
  'pe_je_': 'pcba-finance', 'pe_jl_': 'pcba-finance',
  'pe_glb_': 'pcba-finance', 'pe_art_': 'pcba-finance',
  'pe_apt_': 'pcba-finance', 'pe_fr_': 'pcba-finance',
  'pe_rs_': 'pcba-finance', 'pe_twm_': 'pcba-finance',
  'pe_cct_': 'pcba-finance', 'pe_ce_': 'pcba-finance',
  'pe_cd_': 'pcba-finance', 'pe_exp_': 'pcba-finance',
  'pe_ecn_': 'pcba-finance', 'pe_eco_': 'pcba-finance',
  'pe_eai_': 'pcba-finance', 'pe_kpi_': 'pcba-finance',
  'pe_ks_': 'pcba-finance', 'pe_cpd_': 'pcba-finance',
  'pe_ccl_': 'pcba-finance', 'pe_financial_': 'pcba-finance',
  'pe_cost_': 'pcba-finance', 'pe_compliance_': 'pcba-finance',
  'pe_journal_': 'pcba-finance', 'pe_restatement_': 'pcba-finance',
  'pe_account_': 'pcba-finance', 'pe_fiscal_': 'pcba-finance',
  'pe_three_way_': 'pcba-finance',
};

function findDictPlugin(dictCode) {
  if (COMMON_DICTS.includes(dictCode)) return 'pcba-base';
  const sortedPrefixes = Object.keys(DICT_PREFIXES).sort((a, b) => b.length - a.length);
  for (const prefix of sortedPrefixes) {
    if (dictCode.startsWith(prefix)) return DICT_PREFIXES[prefix];
  }
  // Fallback: try model code matching
  for (const model of allModelCodes) {
    const suffix = model.replace('pe_', '');
    if (dictCode.includes(suffix)) return modelToPlugin[model];
  }
  return 'pcba-base'; // common/shared dict
}

// ============================================================
// Field file -> plugin mapping (by binding reference)
// ============================================================
// Read all binding files to build field -> model mapping
// A field may be referenced by multiple models across plugins,
// so we track ALL models that reference each field.
function buildFieldToModelMap() {
  const bindingsDir = path.join(SRC, 'config/bindings');
  const map = {};       // fieldCode -> first modelCode (for primary assignment)
  const multiMap = {};  // fieldCode -> Set of modelCodes (for cross-plugin duplication)
  if (!fs.existsSync(bindingsDir)) return { map, multiMap };
  for (const file of fs.readdirSync(bindingsDir)) {
    if (!file.endsWith('.json')) continue;
    const modelCode = file.replace('.json', '');
    const binding = JSON.parse(fs.readFileSync(path.join(bindingsDir, file), 'utf8'));
    const fields = Array.isArray(binding) ? binding : (binding.fields || []);
    for (const f of fields) {
      const fieldCode = f.fieldCode || f.code;
      if (fieldCode) {
        if (!map[fieldCode]) map[fieldCode] = modelCode;
        if (!multiMap[fieldCode]) multiMap[fieldCode] = new Set();
        multiMap[fieldCode].add(modelCode);
      }
    }
  }
  return { map, multiMap };
}

// ============================================================
// Helpers
// ============================================================
function readJSON(filePath) {
  return JSON.parse(fs.readFileSync(filePath, 'utf8'));
}

function writeJSON(filePath, data) {
  fs.mkdirSync(path.dirname(filePath), { recursive: true });
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + '\n', 'utf8');
}

function copyFile(src, dest) {
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  fs.copyFileSync(src, dest);
}

function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

// ============================================================
// MAIN
// ============================================================
function main() {
  console.log('=== Splitting PCBA ERP plugin into 8 sub-plugins ===\n');

  // Initialize plugin buckets
  const plugins = {};
  for (const name of Object.keys(PLUGIN_MODELS)) {
    plugins[name] = {
      models: [], dicts: [], menus: [], permissions: [], i18n: [],
      commands: [], fields: [], bindings: [], pages: [],
    };
  }

  // 1. Split models.json
  console.log('1. Splitting models.json...');
  const models = readJSON(path.join(SRC, 'config/models.json'));
  let modelAssigned = 0;
  for (const model of models) {
    const code = model.code;
    const plugin = modelToPlugin[code];
    if (plugin) {
      plugins[plugin].models.push(model);
      modelAssigned++;
    } else {
      console.warn(`  [WARN] Model "${code}" not assigned, putting in pcba-base`);
      plugins['pcba-base'].models.push(model);
      modelAssigned++;
    }
  }
  console.log(`  Assigned ${modelAssigned}/${models.length} models`);

  // 2. Split permissions.json
  console.log('2. Splitting permissions.json...');
  const permissions = readJSON(path.join(SRC, 'config/permissions.json'));
  const permToPlugin = {}; // track which plugin owns each permission
  for (const perm of permissions) {
    const plugin = findPermissionPlugin(perm.code);
    plugins[plugin].permissions.push(perm);
    permToPlugin[perm.code] = plugin;
  }
  for (const [name, bucket] of Object.entries(plugins)) {
    if (bucket.permissions.length > 0) {
      console.log(`  ${name}: ${bucket.permissions.length} permissions`);
    }
  }

  // 3. Split roles.json - each plugin gets roles with only its own permissions
  console.log('3. Splitting roles.json...');
  const roles = readJSON(path.join(SRC, 'config/roles.json'));
  // For each role, split its permissions across plugins
  // Each plugin that has at least one permission from a role gets a copy of that role
  for (const role of roles) {
    const permsByPlugin = {};
    for (const permCode of role.permissions) {
      const plugin = permToPlugin[permCode] || findPermissionPlugin(permCode);
      if (!permsByPlugin[plugin]) permsByPlugin[plugin] = [];
      permsByPlugin[plugin].push(permCode);
    }
    // Create role entry in each plugin that has permissions
    for (const [plugin, perms] of Object.entries(permsByPlugin)) {
      plugins[plugin].roles = plugins[plugin].roles || [];
      // Check if this role already exists in this plugin
      let existing = plugins[plugin].roles.find(r => r.code === role.code);
      if (!existing) {
        existing = { ...role, permissions: [] };
        plugins[plugin].roles.push(existing);
      }
      existing.permissions.push(...perms);
    }
  }

  // 4. Split menus.json
  console.log('4. Splitting menus.json...');
  const menus = readJSON(path.join(SRC, 'config/menus.json'));
  // Root menu goes to all plugins that have menus
  const rootMenu = menus.find(m => m.code === 'pe_root');
  const childMenus = menus.filter(m => m.code !== 'pe_root');
  const pluginsWithMenus = new Set();
  for (const menu of childMenus) {
    const plugin = findMenuPlugin(menu);
    plugins[plugin].menus.push(menu);
    pluginsWithMenus.add(plugin);
  }
  // Add root menu to every plugin that has child menus
  for (const plugin of pluginsWithMenus) {
    if (rootMenu) {
      plugins[plugin].menus.unshift({ ...rootMenu });
    }
  }
  for (const [name, bucket] of Object.entries(plugins)) {
    if (bucket.menus.length > 0) {
      console.log(`  ${name}: ${bucket.menus.length} menus`);
    }
  }

  // 5. Split dicts.json
  console.log('5. Splitting dicts.json...');
  const dicts = readJSON(path.join(SRC, 'config/dicts.json'));
  for (const dict of dicts) {
    const plugin = findDictPlugin(dict.code);
    plugins[plugin].dicts.push(dict);
  }
  for (const [name, bucket] of Object.entries(plugins)) {
    if (bucket.dicts.length > 0) {
      console.log(`  ${name}: ${bucket.dicts.length} dicts`);
    }
  }

  // 6. Split i18n.json
  console.log('6. Splitting i18n.json...');
  const i18nEntries = readJSON(path.join(SRC, 'config/i18n.json'));
  let i18nAssigned = 0, i18nFallback = 0;
  for (const entry of i18nEntries) {
    const plugin = findI18nPlugin(entry);
    if (plugin) {
      plugins[plugin].i18n.push(entry);
      i18nAssigned++;
    } else {
      // Try to match by dict code for dict entries
      const parts = entry.key.split('.');
      if (parts[0] === 'dict' && parts[1]) {
        const dictPlugin = findDictPlugin(parts[1]);
        plugins[dictPlugin].i18n.push(entry);
      } else {
        plugins['pcba-base'].i18n.push(entry);
        i18nFallback++;
      }
      i18nAssigned++;
    }
  }
  console.log(`  Assigned ${i18nAssigned}/${i18nEntries.length} i18n entries (${i18nFallback} fallback to base)`);

  // 7. Copy command files
  console.log('7. Assigning command files...');
  const cmdDir = path.join(SRC, 'config/commands');
  if (fs.existsSync(cmdDir)) {
    for (const file of fs.readdirSync(cmdDir)) {
      if (!file.endsWith('.json')) continue;
      const plugin = findCommandPlugin(file);
      plugins[plugin].commands.push(file);
    }
  }
  for (const [name, bucket] of Object.entries(plugins)) {
    if (bucket.commands.length > 0) {
      console.log(`  ${name}: ${bucket.commands.length} commands`);
    }
  }

  // 8. Copy binding files (1:1 with model codes)
  console.log('8. Assigning binding files...');
  const bindDir = path.join(SRC, 'config/bindings');
  if (fs.existsSync(bindDir)) {
    for (const file of fs.readdirSync(bindDir)) {
      if (!file.endsWith('.json')) continue;
      const modelCode = file.replace('.json', '');
      const plugin = modelToPlugin[modelCode] || 'pcba-base';
      plugins[plugin].bindings.push(file);
    }
  }

  // 9. Copy field files (based on binding references)
  console.log('9. Assigning field files...');
  const { map: fieldToModel, multiMap: fieldToModels } = buildFieldToModelMap();
  const fieldDir = path.join(SRC, 'config/fields');
  if (fs.existsSync(fieldDir)) {
    for (const file of fs.readdirSync(fieldDir)) {
      if (!file.endsWith('.json')) continue;
      const fieldCode = file.replace('.json', '');
      const models = fieldToModels[fieldCode];
      if (models && models.size > 0) {
        // Assign field to ALL plugins that reference it via bindings
        const assignedPlugins = new Set();
        for (const modelCode of models) {
          const plugin = modelToPlugin[modelCode] || 'pcba-base';
          assignedPlugins.add(plugin);
        }
        for (const plugin of assignedPlugins) {
          plugins[plugin].fields.push(file);
        }
      } else {
        // Orphan field — fallback to pcba-base
        plugins['pcba-base'].fields.push(file);
      }
    }
  }
  for (const [name, bucket] of Object.entries(plugins)) {
    if (bucket.fields.length > 0) {
      console.log(`  ${name}: ${bucket.fields.length} fields`);
    }
  }

  // 10. Copy page files
  console.log('10. Assigning page files...');
  const pageDir = path.join(SRC, 'config/pages');
  if (fs.existsSync(pageDir)) {
    for (const file of fs.readdirSync(pageDir)) {
      if (!file.endsWith('.json')) continue;
      const plugin = findPagePlugin(file);
      plugins[plugin].pages.push(file);
    }
  }
  for (const [name, bucket] of Object.entries(plugins)) {
    if (bucket.pages.length > 0) {
      console.log(`  ${name}: ${bucket.pages.length} pages`);
    }
  }

  // ============================================================
  // Write output
  // ============================================================
  console.log('\n=== Writing sub-plugins ===\n');

  for (const [name, bucket] of Object.entries(plugins)) {
    const meta = PLUGIN_META[name];
    const destDir = path.join(DEST, name);
    console.log(`Writing ${name} -> ${destDir}/`);

    // Clean existing
    if (fs.existsSync(destDir)) {
      fs.rmSync(destDir, { recursive: true });
    }

    // plugin.json
    const pluginJson = {
      pluginId: meta.pluginId,
      namespace: 'pe',
      version: '1.0.0',
      'displayName:zh-CN': meta.displayNameZh,
      'displayName:en': meta.displayNameEn,
      description: meta.description,
      author: 'AuraBoot Team',
      homepage: `https://auraboot.com/plugins/${name}`,
      minPlatformVersion: '1.0.0',
      dependencies: meta.dependencies,
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
    writeJSON(path.join(destDir, 'plugin.json'), pluginJson);

    // config/models.json
    writeJSON(path.join(destDir, 'config/models.json'), bucket.models);

    // config/dicts.json
    writeJSON(path.join(destDir, 'config/dicts.json'), bucket.dicts);

    // config/permissions.json
    writeJSON(path.join(destDir, 'config/permissions.json'), bucket.permissions);

    // config/roles.json
    writeJSON(path.join(destDir, 'config/roles.json'), bucket.roles || []);

    // config/menus.json
    writeJSON(path.join(destDir, 'config/menus.json'), bucket.menus);

    // config/i18n.json
    writeJSON(path.join(destDir, 'config/i18n.json'), bucket.i18n);

    // Copy command files
    ensureDir(path.join(destDir, 'config/commands'));
    for (const file of bucket.commands) {
      copyFile(path.join(SRC, 'config/commands', file), path.join(destDir, 'config/commands', file));
    }

    // Copy binding files
    ensureDir(path.join(destDir, 'config/bindings'));
    for (const file of bucket.bindings) {
      copyFile(path.join(SRC, 'config/bindings', file), path.join(destDir, 'config/bindings', file));
    }

    // Copy field files
    ensureDir(path.join(destDir, 'config/fields'));
    for (const file of bucket.fields) {
      copyFile(path.join(SRC, 'config/fields', file), path.join(destDir, 'config/fields', file));
    }

    // Copy page files
    ensureDir(path.join(destDir, 'config/pages'));
    for (const file of bucket.pages) {
      copyFile(path.join(SRC, 'config/pages', file), path.join(destDir, 'config/pages', file));
    }

    // Summary
    console.log(`  models=${bucket.models.length} dicts=${bucket.dicts.length} perms=${bucket.permissions.length} ` +
      `roles=${(bucket.roles||[]).length} menus=${bucket.menus.length} i18n=${bucket.i18n.length} ` +
      `cmds=${bucket.commands.length} fields=${bucket.fields.length} bindings=${bucket.bindings.length} pages=${bucket.pages.length}`);
  }

  console.log('\n=== Done! ===');
  console.log('Import order: pcba-base -> pcba-crm, pcba-srm, pcba-wms, pcba-finance -> pcba-sales, pcba-procurement, pcba-manufacturing');
}

main();
