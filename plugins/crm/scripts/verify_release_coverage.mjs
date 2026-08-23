#!/usr/bin/env node

import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const PLUGIN_ROOT = path.resolve(HERE, '..');
const REPO_ROOT = path.resolve(PLUGIN_ROOT, '../..');
const DEFAULT_MANIFEST = path.join(PLUGIN_ROOT, 'coverage-manifest.json');

const RG1_PAGES = [
  'crm_customer_360_workbench',
  'crm_lead_desk_workbench',
  'crm_opportunity_workspace',
  'crm_forecast_cockpit',
  'crm_activity_service_desk',
];
const RG2_PAGES = [
  'crm_qdp_release_workbench',
  'crm_quote_summary_common_detail',
  'crm_qdp_revision_common_detail',
];
const RG2_COMMANDS = [
  'crm:prepare_qdp_draft',
  'crm:compile_qdp_revision',
  'crm:submit_qdp_review',
  'crm:publish_qdp_revision',
  'crm:record_order_commitment',
];
const RG3_COMMANDS = [
  'crm:create_lead',
  'crm:contact_lead',
  'crm:qualify_lead',
  'crm:convert_lead',
  'crm:create_activity',
];
const AMOS_P0_B01_COMMANDS = ['crm:intake_customer_request'];

// Runtime-generated ledgers can expose list/detail investigation surfaces while
// intentionally denying manual forms. Their service commands are scheduler or
// policy entry points, not proof that a human-facing create/update form is required.
const SYSTEM_MANAGED_NO_FORM_MODELS = new Set(['crm_sla_breach']);

const EVIDENCE = {
  rg1Browser: 'plugins/crm/e2e/core-workbenches.golden.spec.ts',
  rg2QdpStack: 'plugins/crm/scripts/it/qdp_release_center_true_stack.py',
  rg2QdpBrowser: 'plugins/crm/e2e/qdp-release-center.golden.spec.ts',
  rg2OrderStack: 'plugins/crm/scripts/it/order_commitment_true_stack.py',
  rg2OrderBrowser: 'plugins/crm/e2e/order-commitment.golden.spec.ts',
  rg3Journey: 'plugins/crm/scripts/adoption_journey.py',
  rg3Guide: 'plugins/crm/README.md',
  releaseB: 'plugins/crm/e2e/opportunity-efficiency.golden.spec.ts',
  dashboards: 'plugins/crm/e2e/crm-dashboards.golden.spec.ts',
  forecastVariance: 'plugins/crm/e2e/forecast-variance.golden.spec.ts',
  customerPool: 'web-admin/tests/e2e/crm/crm-customer-pool-parity.spec.ts',
  followupComments: 'web-admin/tests/e2e/crm/crm-followup-comments-parity.spec.ts',
  contactFollowupLifecycle:
    'web-admin/tests/e2e/crm/crm-contact-followup-lifecycle-parity.spec.ts',
  leadConversionActivityCarry:
    'web-admin/tests/e2e/crm/crm-lead-conversion-activity-carry-parity.spec.ts',
  accountCollaboration: 'web-admin/tests/e2e/crm/crm-ownership-sharing.spec.ts',
  accountRelationship: 'web-admin/tests/e2e/crm/crm-account-relationship-parity.spec.ts',
  amosB01Stack: 'plugins/crm/scripts/it/customer_request_intake_true_stack.py',
  amosB01Browser: 'plugins/crm/e2e/customer-request-intake.golden.spec.ts',
};

const AMOS_P0_B01_COVERAGE = {
  blocks: ['crm_customer_request_common_detail:crm_cr_source_evidence'],
  fields: [
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_source_channel',
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_source_system',
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_source_message_ref',
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_source_received_at',
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_ingested_at',
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_field_evidence_count',
    'crm_customer_request_common_detail:crm_cr_source_evidence:crm_cr_source_content_hash',
  ],
  uiActions: ['crm_customer_request_common_detail:crm_cr_tabs:source_evidence'],
};

const FOLLOWUP_COMMENT_COVERAGE = {
  pages: ['crm_activity_common_detail'],
  blocks: ['crm_activity_common_detail:activity_followup_comments'],
};
const CONTACT_FOLLOWUP_LIFECYCLE_COVERAGE = {
  pages: [
    'crm_contact_common_detail',
    'crm_activity_common_list',
    'crm_activity_common_detail',
  ],
  blocks: [
    'crm_contact_common_detail:section_basic',
    'crm_contact_common_detail:crm_contact_detail_toolbar',
    'crm_activity_common_list:crm_act_tabs',
    'crm_activity_common_list:crm_act_table',
    'crm_activity_common_detail:activity_summary',
    'crm_activity_common_detail:activity_history',
    'crm_activity_common_detail:crm_activity_detail_toolbar',
  ],
  fields: [
    'crm_contact_common_detail:section_basic:crm_ct_account_id',
    'crm_contact_common_detail:section_basic:crm_ct_name',
    'crm_contact_common_detail:section_basic:crm_ct_is_primary',
    'crm_contact_common_detail:section_basic:crm_ct_status',
    'crm_activity_common_list:crm_act_table:crm_act_type',
    'crm_activity_common_list:crm_act_table:crm_act_subject',
    'crm_activity_common_list:crm_act_table:crm_act_date',
    'crm_activity_common_detail:activity_summary:crm_act_type',
    'crm_activity_common_detail:activity_summary:crm_act_subject',
    'crm_activity_common_detail:activity_summary:crm_act_date',
    'crm_activity_common_detail:activity_summary:crm_act_content',
  ],
  uiActions: [
    'crm_contact_common_detail:crm_contact_detail_toolbar:set_primary',
    'crm_contact_common_detail:crm_contact_detail_toolbar:disable',
    'crm_contact_common_detail:crm_contact_detail_toolbar:enable',
    'crm_activity_common_list:crm_act_tabs:follow_plans',
    'crm_activity_common_list:crm_act_tabs:follow_records',
    'crm_activity_common_detail:crm_activity_detail_toolbar:start_task',
    'crm_activity_common_detail:crm_activity_detail_toolbar:complete_task',
    'crm_activity_common_detail:crm_activity_detail_toolbar:delete_follow_plan',
    'crm_activity_common_detail:crm_activity_detail_toolbar:delete_follow_record',
  ],
  commands: [
    'crm:create_account',
    'crm:create_contact',
    'crm:set_primary_contact',
    'crm:disable_contact',
    'crm:enable_contact',
    'crm:create_activity',
    'crm:start_task',
    'crm:complete_task',
    'crm:delete_follow_plan',
    'crm:delete_follow_record',
  ],
};
const LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE = {
  pages: [
    'crm_lead_common_detail',
    'crm_account_common_detail',
    'crm_contact_common_detail',
    'crm_opportunity_common_detail',
    'crm_activity_common_detail',
  ],
  commands: [
    'crm:create_lead',
    'crm:qualify_lead',
    'crm:create_activity',
    'crm:log_lead_activity',
    'crm:convert_lead',
  ],
  queries: ['crm_activities_by_object', 'crm_account_timeline'],
  blocks: [
    'crm_lead_common_detail:crm_lead_tabs',
    'crm_lead_common_detail:block_activities',
    'crm_lead_common_detail:block_conversion',
    'crm_lead_common_detail:crm_lead_detail_toolbar',
    'crm_account_common_detail:crm_account_tabs',
    'crm_account_common_detail:block_activities',
    'crm_contact_common_detail:crm_contact_tabs',
    'crm_contact_common_detail:block_activities',
    'crm_opportunity_common_detail:crm_opportunity_tabs',
    'crm_opportunity_common_detail:block_activities',
    'crm_activity_common_detail:activity_followup_comments',
  ],
  uiActions: [
    'crm_lead_common_detail:crm_lead_tabs:activities',
    'crm_lead_common_detail:crm_lead_detail_toolbar:convert',
    'crm_lead_common_detail:crm_lead_tabs:conversion',
    'crm_account_common_detail:crm_account_tabs:activities',
    'crm_contact_common_detail:crm_contact_tabs:activities',
    'crm_opportunity_common_detail:crm_opportunity_tabs:activities',
  ],
};
const ACCOUNT_COLLABORATION_COVERAGE = {
  pages: ['crm_account_common_list', 'crm_account_common_detail'],
  commands: ['crm:create_account', 'crm:update_account'],
  permissions: ['crm.account.read', 'crm.account.manage'],
  blocks: [
    'crm_account_common_list:crm_account_tabs',
    'crm_account_common_list:crm_account_table',
    'crm_account_common_detail:crm_account_tabs',
    'crm_account_common_detail:crm_account_detail_toolbar',
  ],
  uiActions: [
    'crm_account_common_list:platform:collaborative_accounts',
    'crm_account_common_detail:platform:share_record',
  ],
};
const ACCOUNT_RELATIONSHIP_COVERAGE = {
  pages: [
    'crm_account_common_detail',
    'crm_account_relation_common_form',
    'crm_account_relation_common_detail',
  ],
  commands: [
    'crm:create_account',
    'crm:create_account_relation',
    'crm:update_account_relation',
    'crm:delete_account_relation',
  ],
  permissions: ['crm.account.read', 'crm.account.manage'],
  blocks: [
    'crm_account_common_detail:crm_account_tabs',
    'crm_account_common_detail:crm_account_relationship_actions',
    'crm_account_common_detail:block_outgoing_relationships',
    'crm_account_common_detail:block_incoming_relationships',
    'crm_account_relation_common_form:relationship_basic',
    'crm_account_relation_common_form:relationship_buttons',
    'crm_account_relation_common_detail:relationship_detail',
    'crm_account_relation_common_detail:relationship_detail_toolbar',
  ],
  fields: [
    'crm_account_relation_common_form:relationship_basic:crm_acr_source_account_id',
    'crm_account_relation_common_form:relationship_basic:crm_acr_target_account_id',
    'crm_account_relation_common_form:relationship_basic:crm_acr_relation_type',
    'crm_account_relation_common_form:relationship_basic:crm_acr_strength',
    'crm_account_relation_common_form:relationship_basic:crm_acr_status',
    'crm_account_relation_common_form:relationship_basic:crm_acr_effective_from',
    'crm_account_relation_common_form:relationship_basic:crm_acr_effective_to',
    'crm_account_relation_common_form:relationship_basic:crm_acr_notes',
  ],
  uiActions: [
    'crm_account_common_detail:crm_account_tabs:relationships',
    'crm_account_common_detail:crm_account_relationship_actions:add',
    'crm_account_common_detail:block_outgoing_relationships:view',
    'crm_account_relation_common_form:relationship_buttons:submit',
    'crm_account_relation_common_detail:relationship_detail_toolbar:edit',
    'crm_account_relation_common_detail:relationship_detail_toolbar:delete',
  ],
};

// Executable denominator owned by the opportunity-efficiency true-stack gate.
// Keys deliberately match the machine receipt emitted by the Playwright spec;
// adding a pass here therefore also requires completed runtime evidence.
const RELEASE_B_COVERAGE = {
  pages: [
    'crm_contact_common_list',
    'crm_forecast_cockpit',
    'crm_opportunity_common_detail',
    'crm_opportunity_common_list',
  ],
  commands: [
    'crm:advance_opp_to_negotiation',
    'crm:advance_opp_to_proposal',
    'crm:create_account',
    'crm:create_contact',
    'crm:create_forecast',
    'crm:create_opp_task',
    'crm:create_opportunity',
    'crm:create_quote_summary',
    'crm:qualify_opportunity',
    'crm:start_task',
    'crm:complete_task',
    'crm:cancel_task',
    'crm:win_opportunity',
  ],
  queries: ['crm_account_stats', 'crm_account_timeline'],
  permissions: ['crm.activity.manage'],
  dashboardTargets: [
    'crm_account_360:recent_activities:recent_activities',
    'crm_account_360:recent_opportunities:recent_opportunities',
    'crm_account_360:stats_contacts:stats_contacts',
  ],
  uiActions: [
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions:create_plan_task',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions:create_quote_summary',
    'crm_opportunity_common_detail:block_opportunity_plan:view_task',
    'crm_opportunity_common_detail:block_opportunity_plan:start_task',
    'crm_opportunity_common_detail:block_opportunity_plan:complete_task',
    'crm_opportunity_common_detail:block_opportunity_plan:cancel_task',
    'crm_opportunity_common_detail:crm_opportunity_tabs:activities',
    'crm_opportunity_common_detail:crm_opportunity_tabs:plan_and_quotes',
    'crm_opportunity_common_list:crm_opp_table:bulk_qualify',
    'crm_opportunity_common_list:platform:add_advanced_filter',
    'crm_opportunity_common_list:platform:configure_view_columns',
    'crm_opportunity_common_list:platform:export_filtered_csv',
    'crm_opportunity_common_list:platform:analyze_current_view',
    'crm_opportunity_common_list:platform:select_preset_view',
    'crm_opportunity_common_list:platform:drill_chart_to_list',
    'crm_opportunity_common_list:platform:save_advanced_filters',
    'crm_opportunity_common_list:crm_opp_tabs:proposal',
  ],
  blocks: [
    'crm_contact_common_list:crm_contact_table',
    'crm_forecast_cockpit:crm_forecast_execution_metrics',
    'crm_forecast_cockpit:crm_forecast_metrics',
    'crm_forecast_cockpit:crm_forecast_owner_queue',
    'crm_forecast_cockpit:crm_forecast_submission_queue',
    'crm_forecast_cockpit:crm_forecast_tabs',
    'crm_opportunity_common_detail:block_activities',
    'crm_opportunity_common_detail:block_opportunity_plan',
    'crm_opportunity_common_detail:block_opportunity_quotes',
    'crm_opportunity_common_detail:crm_opp_plan_quote_actions',
    'crm_opportunity_common_detail:crm_opp_stage_rail',
    'crm_opportunity_common_detail:crm_opportunity_tabs',
    'crm_opportunity_common_list:crm_opp_table',
    'crm_opportunity_common_list:crm_opp_tabs',
  ],
  fields: [
    'crm_contact_common_list:crm_contact_table:crm_ct_name',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_best_case_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_commit_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_period',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_pipeline_amount',
    'crm_opportunity_common_detail:block_activities:crm_act_subject',
    'crm_opportunity_common_detail:block_opportunity_plan:crm_act_subject',
    'crm_opportunity_common_detail:block_opportunity_quotes:crm_qs_quote_amount',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_amount',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_expected_close_date',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_name',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_probability',
    'crm_opportunity_common_list:crm_opp_table:crm_opp_stage',
  ],
};
const CUSTOMER_POOL_COVERAGE = {
  pages: [
    'crm_customer_capacity_detail',
    'crm_customer_capacity_form',
    'crm_customer_capacity_list',
    'crm_customer_owner_history_detail',
    'crm_customer_owner_history_list',
    'crm_customer_pool_batch_list',
    'crm_customer_pool_detail',
    'crm_customer_pool_form',
    'crm_customer_pool_item_detail',
    'crm_customer_pool_item_list',
    'crm_customer_pool_list',
    'crm_customer_pool_strategy',
    'crm_customer_pool_recycle_rule_detail',
    'crm_customer_pool_recycle_rule_form',
    'crm_customer_pool_recycle_rule_list',
  ],
  commands: [
    'crm:assign_pool_customer',
    'crm:claim_pool_customer',
    'crm:create_customer_capacity',
    'crm:create_customer_pool',
    'crm:create_customer_pool_recycle_rule',
    'crm:download_customer_pool_import_template',
    'crm:delete_customer_capacity',
    'crm:delete_customer_pool',
    'crm:delete_customer_pool_recycle_rule',
    'crm:delete_pool_customer',
    'crm:move_customer_to_pool',
    'crm:precheck_customer_pool_import',
    'crm:run_customer_pool_recycle',
    'crm:toggle_customer_pool',
    'crm:import_customer_pool_customers',
    'crm:update_customer_capacity',
    'crm:update_customer_pool',
    'crm:update_customer_pool_recycle_rule',
    'crm:update_pool_customer',
  ],
  queries: [
    'crm_customer_pool_ops_queue',
    'crm_customer_pool_ops_stats',
    'crm_customer_pool_policy_queue',
    'crm_customer_pool_policy_stats',
    'crm_customer_capacity_policy_queue',
    'crm_customer_recycle_policy_queue',
    'crm_pool_customer_owner_history',
    'crm_pool_customer_timeline',
  ],
  permissions: [
    'crm.customer_pool.assign',
    'crm.customer_pool.manage',
    'crm.customer_pool.import',
    'crm.customer_pool.move',
    'crm.customer_pool.pick',
    'crm.customer_pool.read',
    'crm.customer_pool.recycle',
  ],
  blocks: [
    'crm_customer_capacity_detail:actions',
    'crm_customer_capacity_detail:capacity_detail',
    'crm_customer_capacity_form:buttons',
    'crm_customer_capacity_form:capacity',
    'crm_customer_capacity_list:table',
    'crm_customer_capacity_list:toolbar',
    'crm_customer_owner_history_detail:ownership_evidence',
    'crm_customer_owner_history_list:history',
    'crm_customer_pool_batch_list:batch_guidance',
    'crm_customer_pool_batch_list:pool_queue',
    'crm_customer_pool_batch_list:pool_tabs',
    'crm_customer_pool_detail:actions',
    'crm_customer_pool_detail:policy',
    'crm_customer_pool_form:buttons',
    'crm_customer_pool_form:claim_policy',
    'crm_customer_pool_form:identity',
    'crm_customer_pool_form:recycle_policy',
    'crm_customer_pool_item_detail:customer_snapshot',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline',
    'crm_customer_pool_item_detail:pool_customer_owner_history',
    'crm_customer_pool_item_detail:pool_evidence',
    'crm_customer_pool_item_detail:pooled_customer_mobile_tabs',
    'crm_customer_pool_item_list:crm_customer_pool_actions',
    'crm_customer_pool_item_list:crm_customer_pool_evidence',
    'crm_customer_pool_item_list:crm_customer_pool_header_actions',
    'crm_customer_pool_item_list:crm_customer_pool_metrics',
    'crm_customer_pool_item_list:crm_customer_pool_queue',
    'crm_customer_pool_item_list:crm_customer_pool_search',
    'crm_customer_pool_item_list:crm_customer_pool_status',
    'crm_customer_pool_list:crm_cp_table',
    'crm_customer_pool_list:crm_cp_toolbar',
    'crm_customer_pool_strategy:crm_customer_policy_metrics',
    'crm_customer_pool_strategy:crm_customer_policy_search',
    'crm_customer_pool_strategy:crm_customer_policy_header_actions',
    'crm_customer_pool_strategy:crm_customer_policy_tabs',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table',
    'crm_customer_pool_strategy:crm_customer_capacity_actions',
    'crm_customer_pool_strategy:crm_customer_capacity_table',
    'crm_customer_pool_strategy:crm_customer_recycle_actions',
    'crm_customer_pool_strategy:crm_customer_recycle_table',
    'crm_customer_pool_strategy:crm_customer_policy_status',
    'crm_customer_pool_strategy:crm_customer_policy_evidence',
    'crm_customer_pool_recycle_rule_detail:actions',
    'crm_customer_pool_recycle_rule_detail:condition',
    'crm_customer_pool_recycle_rule_form:buttons',
    'crm_customer_pool_recycle_rule_form:identity',
    'crm_customer_pool_recycle_rule_form:time_condition',
    'crm_customer_pool_recycle_rule_list:crm_cprr_guidance',
    'crm_customer_pool_recycle_rule_list:crm_cprr_table',
    'crm_customer_pool_recycle_rule_list:crm_cprr_toolbar',
  ],
  fields: [
    'crm_customer_capacity_form:capacity:crm_ccap_capacity',
    'crm_customer_capacity_form:capacity:crm_ccap_remark',
    'crm_customer_capacity_form:capacity:crm_ccap_status',
    'crm_customer_capacity_form:capacity:crm_ccap_user_id',
    'crm_customer_capacity_list:table:crm_ccap_capacity',
    'crm_customer_capacity_list:table:crm_ccap_remark',
    'crm_customer_owner_history_detail:ownership_evidence:crm_coh_reason',
    'crm_customer_owner_history_list:history:crm_coh_event',
    'crm_customer_owner_history_list:history:crm_coh_reason',
    'crm_customer_pool_batch_list:pool_queue:crm_cpi_account_name',
    'crm_customer_pool_batch_list:pool_queue:crm_cpi_claimed_by',
    'crm_customer_pool_batch_list:pool_queue:crm_cpi_industry',
    'crm_customer_pool_batch_list:pool_queue:crm_cpi_phone',
    'crm_customer_pool_batch_list:pool_queue:crm_cpi_rating',
    'crm_customer_pool_batch_list:pool_tabs:crm_cpi_status',
    'crm_customer_pool_detail:policy:crm_cp_name',
    'crm_customer_pool_form:claim_policy:crm_cp_daily_pick_limit',
    'crm_customer_pool_form:claim_policy:crm_cp_new_cooldown_days',
    'crm_customer_pool_form:claim_policy:crm_cp_previous_owner_cooldown_days',
    'crm_customer_pool_form:identity:crm_cp_admin_user_ids',
    'crm_customer_pool_form:identity:crm_cp_description',
    'crm_customer_pool_form:identity:crm_cp_member_user_ids',
    'crm_customer_pool_form:identity:crm_cp_name',
    'crm_customer_pool_form:recycle_policy:crm_cp_auto_recycle',
    'crm_customer_pool_form:recycle_policy:crm_cp_recycle_after_days',
    'crm_customer_pool_form:recycle_policy:crm_cp_recycle_basis',
    'crm_customer_pool_form:recycle_policy:crm_cp_recycle_match_mode',
    'crm_customer_pool_item_detail:customer_snapshot:crm_cpi_account_name',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline:crm_act_content',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline:crm_act_date',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline:crm_act_subject',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline:crm_act_type',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline:crm_ar_object_type',
    'crm_customer_pool_item_detail:pool_customer_activity_timeline:owner_name',
    'crm_customer_pool_item_detail:pool_customer_owner_history:actor_name',
    'crm_customer_pool_item_detail:pool_customer_owner_history:crm_coh_event',
    'crm_customer_pool_item_detail:pool_customer_owner_history:crm_coh_occurred_at',
    'crm_customer_pool_item_detail:pool_customer_owner_history:crm_coh_reason',
    'crm_customer_pool_item_detail:pool_customer_owner_history:next_owner_name',
    'crm_customer_pool_item_detail:pool_customer_owner_history:previous_owner_name',
    'crm_customer_pool_item_detail:pool_evidence:crm_cpi_reason',
    'crm_customer_pool_item_list:crm_customer_pool_evidence:crm_cpi_account_name',
    'crm_customer_pool_item_list:crm_customer_pool_evidence:owner_name',
    'crm_customer_pool_item_list:crm_customer_pool_queue:crm_cpi_account_name',
    'crm_customer_pool_item_list:crm_customer_pool_queue:crm_cpi_claimed_by',
    'crm_customer_pool_item_list:crm_customer_pool_queue:operational_state',
    'crm_customer_pool_item_list:crm_customer_pool_search:crm_cpi_account_name',
    'crm_customer_pool_item_list:crm_customer_pool_status:owner_name',
    'crm_customer_pool_list:crm_cp_table:crm_cp_name',
    'crm_customer_pool_list:crm_cp_table:crm_cp_daily_pick_limit',
    'crm_customer_pool_list:crm_cp_table:crm_cp_new_cooldown_days',
    'crm_customer_pool_list:crm_cp_table:crm_cp_previous_owner_cooldown_days',
    'crm_customer_pool_list:crm_cp_table:crm_cp_status',
    'crm_customer_pool_list:crm_cp_table:importType',
    'crm_customer_pool_list:crm_cp_table:skipErrors',
    'crm_customer_pool_strategy:crm_customer_policy_search:crm_cp_name',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:crm_cp_name',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:crm_cp_status',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:member_count',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:admin_count',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:crm_cp_daily_pick_limit',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:crm_cp_auto_recycle',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:crm_cp_new_cooldown_days',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:crm_cp_previous_owner_cooldown_days',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:active_rule_count',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:importType',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:importFileId',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:skipErrors',
    'crm_customer_pool_strategy:crm_customer_capacity_table:user_name',
    'crm_customer_pool_strategy:crm_customer_capacity_table:crm_ccap_capacity',
    'crm_customer_pool_strategy:crm_customer_capacity_table:owned_customer_count',
    'crm_customer_pool_strategy:crm_customer_capacity_table:remaining_capacity',
    'crm_customer_pool_strategy:crm_customer_capacity_table:crm_ccap_status',
    'crm_customer_pool_strategy:crm_customer_capacity_table:crm_ccap_remark',
    'crm_customer_pool_strategy:crm_customer_recycle_table:crm_cprr_name',
    'crm_customer_pool_strategy:crm_customer_recycle_table:pool_name',
    'crm_customer_pool_strategy:crm_customer_recycle_table:crm_cprr_time_source',
    'crm_customer_pool_strategy:crm_customer_recycle_table:crm_cprr_operator',
    'crm_customer_pool_strategy:crm_customer_recycle_table:crm_cprr_days',
    'crm_customer_pool_strategy:crm_customer_recycle_table:crm_cprr_status',
    'crm_customer_pool_strategy:crm_customer_recycle_table:crm_cprr_sort_order',
    'crm_customer_pool_strategy:crm_customer_policy_status:member_count',
    'crm_customer_pool_strategy:crm_customer_policy_status:admin_count',
    'crm_customer_pool_strategy:crm_customer_policy_status:crm_cp_daily_pick_limit',
    'crm_customer_pool_strategy:crm_customer_policy_status:crm_cp_new_cooldown_days',
    'crm_customer_pool_strategy:crm_customer_policy_status:active_rule_count',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_name',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_description',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_daily_pick_limit',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_new_cooldown_days',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_previous_owner_cooldown_days',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_recycle_match_mode',
    'crm_customer_pool_strategy:crm_customer_policy_evidence:crm_cp_recycle_after_days',
    'crm_customer_pool_recycle_rule_detail:condition:crm_cprr_code',
    'crm_customer_pool_recycle_rule_detail:condition:crm_cprr_days',
    'crm_customer_pool_recycle_rule_detail:condition:crm_cprr_operator',
    'crm_customer_pool_recycle_rule_form:identity:crm_cprr_code',
    'crm_customer_pool_recycle_rule_form:identity:crm_cprr_name',
    'crm_customer_pool_recycle_rule_form:identity:crm_cprr_pool_id',
    'crm_customer_pool_recycle_rule_form:identity:crm_cprr_sort_order',
    'crm_customer_pool_recycle_rule_form:identity:crm_cprr_status',
    'crm_customer_pool_recycle_rule_form:time_condition:crm_cprr_days',
    'crm_customer_pool_recycle_rule_form:time_condition:crm_cprr_description',
    'crm_customer_pool_recycle_rule_form:time_condition:crm_cprr_end_at',
    'crm_customer_pool_recycle_rule_form:time_condition:crm_cprr_operator',
    'crm_customer_pool_recycle_rule_form:time_condition:crm_cprr_start_at',
    'crm_customer_pool_recycle_rule_form:time_condition:crm_cprr_time_source',
    'crm_customer_pool_recycle_rule_list:crm_cprr_table:crm_cprr_name',
    'crm_customer_pool_recycle_rule_list:crm_cprr_table:crm_cprr_sort_order',
  ],
  uiActions: [
    'crm_customer_capacity_detail:actions:edit',
    'crm_customer_capacity_form:buttons:submit',
    'crm_customer_capacity_list:table:delete',
    'crm_customer_capacity_list:table:view',
    'crm_customer_capacity_list:toolbar:create',
    'crm_customer_owner_history_list:history:view',
    'crm_customer_pool_batch_list:pool_queue:batch_assign',
    'crm_customer_pool_batch_list:pool_queue:batch_claim',
    'crm_customer_pool_batch_list:pool_queue:batch_delete',
    'crm_customer_pool_batch_list:pool_queue:batch_update_industry',
    'crm_customer_pool_batch_list:pool_queue:batch_update_name',
    'crm_customer_pool_batch_list:pool_queue:batch_update_rating',
    'crm_customer_pool_batch_list:pool_queue:delete',
    'crm_customer_pool_batch_list:pool_queue:quick_update',
    'crm_customer_pool_batch_list:platform:analyze_current_view',
    'crm_customer_pool_batch_list:platform:export_filtered_xlsx',
    'crm_customer_pool_batch_list:platform:export_selected_xlsx',
    'crm_customer_pool_batch_list:pool_tabs:assigned',
    'crm_customer_pool_batch_list:pool_tabs:available',
    'crm_customer_pool_batch_list:pool_tabs:claimed',
    'crm_customer_pool_detail:actions:edit',
    'crm_customer_pool_form:buttons:submit',
    'crm_customer_pool_item_list:crm_customer_pool_actions:assign',
    'crm_customer_pool_item_list:crm_customer_pool_actions:claim',
    'crm_customer_pool_item_list:crm_customer_pool_header_actions:batch_operations',
    'crm_customer_pool_item_list:crm_customer_pool_queue:open_customer',
    'crm_customer_pool_item_list:crm_customer_pool_queue:assign',
    'crm_customer_pool_item_list:crm_customer_pool_queue:claim',
    'crm_customer_pool_item_list:crm_customer_pool_queue:view_pool_evidence',
    'crm_customer_pool_item_detail:pooled_customer_mobile_tabs:activity_history',
    'crm_customer_pool_item_detail:pooled_customer_mobile_tabs:customer_info',
    'crm_customer_pool_item_detail:pooled_customer_mobile_tabs:ownership_history',
    'crm_customer_pool_list:crm_cp_table:delete',
    'crm_customer_pool_list:crm_cp_table:download_import_template',
    'crm_customer_pool_list:crm_cp_table:import_customers',
    'crm_customer_pool_list:crm_cp_table:precheck_import',
    'crm_customer_pool_list:crm_cp_table:quick_update',
    'crm_customer_pool_list:crm_cp_table:toggle',
    'crm_customer_pool_list:crm_cp_table:view',
    'crm_customer_pool_list:crm_cp_toolbar:create',
    'crm_customer_pool_list:crm_cp_toolbar:run_recycle',
    'crm_customer_pool_strategy:crm_customer_policy_header_actions:open_pool_operations',
    'crm_customer_pool_strategy:crm_customer_policy_header_actions:open_ownership_history',
    'crm_customer_pool_strategy:crm_customer_policy_header_actions:run_recycle',
    'crm_customer_pool_strategy:crm_customer_policy_header_actions:create_pool',
    'crm_customer_pool_strategy:crm_customer_policy_tabs:pools',
    'crm_customer_pool_strategy:crm_customer_policy_tabs:capacity',
    'crm_customer_pool_strategy:crm_customer_policy_tabs:recycle',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:view',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:edit',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:quick_update',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:download_import_template',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:precheck_import',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:import_customers',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:toggle',
    'crm_customer_pool_strategy:crm_customer_policy_pool_table:delete',
    'crm_customer_pool_strategy:crm_customer_capacity_actions:create_capacity',
    'crm_customer_pool_strategy:crm_customer_capacity_table:view',
    'crm_customer_pool_strategy:crm_customer_capacity_table:edit',
    'crm_customer_pool_strategy:crm_customer_capacity_table:delete',
    'crm_customer_pool_strategy:crm_customer_recycle_actions:create_recycle_rule',
    'crm_customer_pool_strategy:crm_customer_recycle_table:view',
    'crm_customer_pool_strategy:crm_customer_recycle_table:edit',
    'crm_customer_pool_strategy:crm_customer_recycle_table:delete',
    'crm_customer_pool_recycle_rule_detail:actions:edit',
    'crm_customer_pool_recycle_rule_form:buttons:submit',
    'crm_customer_pool_recycle_rule_list:crm_cprr_table:delete',
    'crm_customer_pool_recycle_rule_list:crm_cprr_table:view',
    'crm_customer_pool_recycle_rule_list:crm_cprr_toolbar:create',
  ],
};
const DASHBOARD_COVERAGE = {
  pages: ['crm_dashboard', 'crm_sales_forecast'],
  queries: [
    'crm_dashboard_kpi',
    'crm_dashboard_pending_quotes',
    'crm_dashboard_recent_opportunities',
    'crm_lead_pipeline_stats',
    'crm_lead_source_distribution',
    'crm_open_opportunities_detail',
    'crm_opp_lost_reason_breakdown',
    'crm_opp_stale',
    'crm_opportunity_monthly_trend',
    'crm_opportunity_pipeline_stats',
    'crm_recent_activities',
    'crm_sales_forecast_by_category',
    'crm_sales_forecast_by_close_date',
    'crm_sales_forecast_by_owner',
    'crm_sales_forecast_by_stage',
    'crm_sales_forecast_kpi',
    'crm_win_loss_ratio',
  ],
  dashboardTargets: [
    'crm_dashboard:block_kpi_cards',
    'crm_dashboard:block_lead_pipeline',
    'crm_dashboard:block_pending_quotes',
    'crm_dashboard:block_recent_activities',
    'crm_dashboard:block_recent_leads',
    'crm_dashboard:block_recent_opportunities',
    'crm_dashboard:block_stale_opportunities',
    'crm_dashboard:chart_lead_source',
    'crm_dashboard:chart_lost_reason_breakdown',
    'crm_dashboard:chart_opp_monthly_trend',
    'crm_dashboard:chart_opp_pipeline',
    'crm_dashboard:chart_win_loss',
    'crm_sales_forecast:block_forecast_kpi',
    'crm_sales_forecast:chart_forecast_by_category',
    'crm_sales_forecast:chart_forecast_by_month',
    'crm_sales_forecast:chart_forecast_by_owner',
    'crm_sales_forecast:chart_forecast_by_stage',
    'crm_sales_forecast:table_forecast_by_category_detail',
    'crm_sales_forecast:table_open_opportunities',
    'crm_sales_forecast:table_stage_detail',
  ],
  uiActions: ['crm_dashboard:block_kpi_cards:new_leads_drilldown'],
};
const FORECAST_VARIANCE_COVERAGE = {
  queries: ['crm_forecast_variance_summary', 'crm_forecast_variance_drivers'],
  blocks: [
    'crm_forecast_cockpit:crm_forecast_variance_summary_intro',
    'crm_forecast_cockpit:crm_forecast_variance_summary',
    'crm_forecast_cockpit:crm_forecast_variance_drivers_intro',
    'crm_forecast_cockpit:crm_forecast_variance_drivers',
  ],
  fields: [
    'crm_forecast_cockpit:crm_forecast_variance_summary:measure',
    'crm_forecast_cockpit:crm_forecast_variance_summary:submitted_amount',
    'crm_forecast_cockpit:crm_forecast_variance_summary:current_amount',
    'crm_forecast_cockpit:crm_forecast_variance_summary:variance_amount',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_name',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:account_name',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_stage',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:forecast_category',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_expected_amount',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_probability',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:crm_opp_expected_close_date',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:variance_driver',
  ],
  uiActions: [
    'crm_forecast_cockpit:crm_forecast_tabs:variance',
    'crm_forecast_cockpit:crm_forecast_variance_drivers:open_variance_opportunity',
  ],
};
const CORE_WORKBENCH_COVERAGE = {
  pages: RG1_PAGES,
  commands: [
    'crm:contact_lead',
    'crm:qualify_lead',
    'crm:convert_lead',
    'crm:lose_lead',
    'crm:qualify_opportunity',
    'crm:advance_opp_to_proposal',
    'crm:advance_opp_to_negotiation',
    'crm:win_opportunity',
    'crm:lose_opportunity',
    'crm:submit_forecast',
    'crm:start_task',
    'crm:complete_task',
    'crm:investigate_complaint',
    'crm:resolve_complaint',
    'crm:close_complaint',
    'crm:log_opp_activity',
  ],
  queries: [
    'crm_customer_360_stats',
    'crm_customer_360_queue',
    'crm_lead_desk_stats',
    'crm_lead_desk_queue',
    'crm_opportunity_workspace_stats',
    'crm_opportunity_workspace_queue',
    'crm_forecast_cockpit_stats',
    'crm_sales_forecast_by_owner',
    'crm_activity_service_stats',
    'crm_activity_service_queue',
  ],
  blocks: [
    'crm_customer_360_workbench:crm_customer_metrics',
    'crm_customer_360_workbench:crm_customer_search',
    'crm_customer_360_workbench:crm_customer_actions',
    'crm_customer_360_workbench:crm_customer_queue',
    'crm_customer_360_workbench:crm_customer_attention',
    'crm_customer_360_workbench:crm_customer_evidence',
    'crm_lead_desk_workbench:crm_lead_desk_metrics',
    'crm_lead_desk_workbench:crm_lead_desk_search',
    'crm_lead_desk_workbench:crm_lead_desk_header_actions',
    'crm_lead_desk_workbench:crm_lead_desk_queue',
    'crm_lead_desk_workbench:crm_lead_next_action_banner',
    'crm_lead_desk_workbench:crm_lead_lifecycle_actions',
    'crm_lead_desk_workbench:crm_lead_context',
    'crm_opportunity_workspace:crm_opportunity_metrics',
    'crm_opportunity_workspace:crm_opportunity_search',
    'crm_opportunity_workspace:crm_opportunity_header_actions',
    'crm_opportunity_workspace:crm_opportunity_queue',
    'crm_opportunity_workspace:crm_opportunity_attention',
    'crm_opportunity_workspace:crm_opportunity_stage_actions',
    'crm_opportunity_workspace:crm_opportunity_context',
    'crm_forecast_cockpit:crm_forecast_metrics',
    'crm_forecast_cockpit:crm_forecast_execution_metrics',
    'crm_forecast_cockpit:crm_forecast_search',
    'crm_forecast_cockpit:crm_forecast_header_actions',
    'crm_forecast_cockpit:crm_forecast_tabs',
    'crm_forecast_cockpit:crm_forecast_submission_queue',
    'crm_forecast_cockpit:crm_forecast_owner_queue',
    'crm_forecast_cockpit:crm_forecast_status',
    'crm_forecast_cockpit:crm_forecast_actions',
    'crm_forecast_cockpit:crm_forecast_context',
    'crm_activity_service_desk:crm_activity_service_metrics',
    'crm_activity_service_desk:crm_activity_service_search',
    'crm_activity_service_desk:crm_activity_service_header_actions',
    'crm_activity_service_desk:crm_activity_service_queue',
    'crm_activity_service_desk:crm_activity_service_attention',
    'crm_activity_service_desk:crm_activity_service_actions',
    'crm_activity_service_desk:crm_activity_service_context',
  ],
  fields: [
    'crm_customer_360_workbench:crm_customer_search:crm_acc_name',
    'crm_customer_360_workbench:crm_customer_queue:crm_acc_name',
    'crm_customer_360_workbench:crm_customer_queue:crm_acc_status',
    'crm_customer_360_workbench:crm_customer_queue:pipeline_amount',
    'crm_customer_360_workbench:crm_customer_queue:open_complaints',
    'crm_customer_360_workbench:crm_customer_attention:owner_name',
    'crm_customer_360_workbench:crm_customer_attention:contact_count',
    'crm_customer_360_workbench:crm_customer_attention:pipeline_amount',
    'crm_lead_desk_workbench:crm_lead_desk_search:crm_lead_company',
    'crm_lead_desk_workbench:crm_lead_desk_queue:crm_lead_company',
    'crm_lead_desk_workbench:crm_lead_desk_queue:crm_lead_contact_name',
    'crm_lead_desk_workbench:crm_lead_desk_queue:crm_lead_status',
    'crm_lead_desk_workbench:crm_lead_desk_queue:crm_lead_score',
    'crm_lead_desk_workbench:crm_lead_desk_queue:owner_name',
    'crm_lead_desk_workbench:crm_lead_next_action_banner:crm_lead_status',
    'crm_lead_desk_workbench:crm_lead_next_action_banner:owner_name',
    'crm_opportunity_workspace:crm_opportunity_search:crm_opp_name',
    'crm_opportunity_workspace:crm_opportunity_queue:crm_opp_name',
    'crm_opportunity_workspace:crm_opportunity_queue:account_name',
    'crm_opportunity_workspace:crm_opportunity_queue:owner_name',
    'crm_opportunity_workspace:crm_opportunity_queue:crm_opp_stage',
    'crm_opportunity_workspace:crm_opportunity_queue:crm_opp_expected_amount',
    'crm_opportunity_workspace:crm_opportunity_queue:crm_opp_probability',
    'crm_opportunity_workspace:crm_opportunity_queue:crm_opp_expected_close_date',
    'crm_opportunity_workspace:crm_opportunity_attention:crm_opp_stage',
    'crm_opportunity_workspace:crm_opportunity_attention:owner_name',
    'crm_forecast_cockpit:crm_forecast_search:crm_fcst_period',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_period',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_owner',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_status',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_commit_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_best_case_amount',
    'crm_forecast_cockpit:crm_forecast_submission_queue:crm_fcst_pipeline_amount',
    'crm_forecast_cockpit:crm_forecast_owner_queue:owner_name',
    'crm_forecast_cockpit:crm_forecast_owner_queue:total_amount',
    'crm_forecast_cockpit:crm_forecast_owner_queue:weighted_forecast',
    'crm_forecast_cockpit:crm_forecast_status:crm_fcst_owner_display',
    'crm_activity_service_desk:crm_activity_service_search:item_title',
    'crm_activity_service_desk:crm_activity_service_queue:item_kind',
    'crm_activity_service_desk:crm_activity_service_queue:item_title',
    'crm_activity_service_desk:crm_activity_service_queue:item_status',
    'crm_activity_service_desk:crm_activity_service_queue:item_priority',
    'crm_activity_service_desk:crm_activity_service_queue:due_date',
    'crm_activity_service_desk:crm_activity_service_queue:owner_name',
    'crm_activity_service_desk:crm_activity_service_queue:attention_reason',
    'crm_activity_service_desk:crm_activity_service_attention:item_kind',
    'crm_activity_service_desk:crm_activity_service_attention:item_status',
    'crm_activity_service_desk:crm_activity_service_attention:owner_name',
    'crm_activity_service_desk:crm_activity_service_context:owner_name',
    'crm_activity_service_desk:crm_activity_service_context:related_model',
  ],
};
const coreWorkbenchCoverageSets = Object.fromEntries(
  Object.entries(CORE_WORKBENCH_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);
const releaseBCoverageSets = Object.fromEntries(
  Object.entries(RELEASE_B_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);
const customerPoolCoverageSets = Object.fromEntries(
  Object.entries(CUSTOMER_POOL_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);
const followupCommentCoverageSets = Object.fromEntries(
  Object.entries(FOLLOWUP_COMMENT_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);
const contactFollowupLifecycleCoverageSets = Object.fromEntries(
  Object.entries(CONTACT_FOLLOWUP_LIFECYCLE_COVERAGE).map(([axis, values]) => [
    axis,
    new Set(values),
  ]),
);
const leadConversionActivityCarryCoverageSets = Object.fromEntries(
  Object.entries(LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE).map(([axis, values]) => [
    axis,
    new Set(values),
  ]),
);
const accountCollaborationCoverageSets = Object.fromEntries(
  Object.entries(ACCOUNT_COLLABORATION_COVERAGE).map(([axis, values]) => [
    axis,
    new Set(values),
  ]),
);
const accountRelationshipCoverageSets = Object.fromEntries(
  Object.entries(ACCOUNT_RELATIONSHIP_COVERAGE).map(([axis, values]) => [
    axis,
    new Set(values),
  ]),
);
const dashboardCoverageSets = Object.fromEntries(
  Object.entries(DASHBOARD_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);
const forecastVarianceCoverageSets = Object.fromEntries(
  Object.entries(FORECAST_VARIANCE_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);
const amosB01CoverageSets = Object.fromEntries(
  Object.entries(AMOS_P0_B01_COVERAGE).map(([axis, values]) => [axis, new Set(values)]),
);

function json(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(PLUGIN_ROOT, 'config', relativePath), 'utf8'));
}

function jsonDirectory(relativePath) {
  const directory = path.join(PLUGIN_ROOT, 'config', relativePath);
  return fs
    .readdirSync(directory)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .flatMap((name) => {
      const value = JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
      return Array.isArray(value) ? value : [value];
    });
}

function jsonDirectoryEntries(relativePath) {
  const directory = path.join(PLUGIN_ROOT, 'config', relativePath);
  return fs
    .readdirSync(directory)
    .filter((name) => name.endsWith('.json'))
    .sort()
    .flatMap((name) => {
      const value = JSON.parse(fs.readFileSync(path.join(directory, name), 'utf8'));
      const values = Array.isArray(value) ? value : [value];
      return values.map((item) => ({
        value: item,
        source: `plugins/crm/config/${relativePath}${name}`,
      }));
    });
}

function uniq(values) {
  return [...new Set(values)].sort();
}

function allBlocks(page) {
  const blocks = [];
  const visit = (candidate) => {
    if (!candidate || typeof candidate !== 'object') return;
    if (Array.isArray(candidate)) {
      for (const item of candidate) visit(item);
      return;
    }
    if (candidate.blockType) blocks.push(candidate);
    for (const item of candidate.blocks ?? []) visit(item);
    for (const tab of candidate.tabs ?? []) visit(tab.blocks ?? []);
  };
  visit(page.blocks ?? []);
  return blocks;
}

function actionTarget(action) {
  if (action.onClick?.action === 'command.execute') {
    return {
      targetType: 'command',
      target: action.onClick.args?.command ?? null,
    };
  }
  if (action.onClick?.action === 'navigate') {
    return { targetType: 'route', target: action.onClick.args?.to ?? null };
  }
  return { targetType: action.onClick?.action ?? 'unknown', target: null };
}

function evidenceForCommand(code, rg1Commands) {
  const files = [];
  if (rg1Commands.has(code) || coreWorkbenchCoverageSets.commands.has(code)) {
    files.push(EVIDENCE.rg1Browser);
  }
  if (RG2_COMMANDS.includes(code)) {
    files.push(
      code === 'crm:record_order_commitment' ? EVIDENCE.rg2OrderStack : EVIDENCE.rg2QdpStack,
    );
    files.push(
      code === 'crm:record_order_commitment' ? EVIDENCE.rg2OrderBrowser : EVIDENCE.rg2QdpBrowser,
    );
  }
  if (RG3_COMMANDS.includes(code)) files.push(EVIDENCE.rg3Journey);
  if (AMOS_P0_B01_COMMANDS.includes(code)) {
    files.push(EVIDENCE.amosB01Stack, EVIDENCE.amosB01Browser);
  }
  if (releaseBCoverageSets.commands.has(code)) files.push(EVIDENCE.releaseB);
  if (customerPoolCoverageSets.commands.has(code)) files.push(EVIDENCE.customerPool);
  if (contactFollowupLifecycleCoverageSets.commands.has(code)) {
    files.push(EVIDENCE.contactFollowupLifecycle);
  }
  if (leadConversionActivityCarryCoverageSets.commands.has(code)) {
    files.push(EVIDENCE.leadConversionActivityCarry);
  }
  if (accountCollaborationCoverageSets.commands.has(code)) {
    files.push(EVIDENCE.accountCollaboration);
  }
  if (accountRelationshipCoverageSets.commands.has(code)) {
    files.push(EVIDENCE.accountRelationship);
  }
  return uniq(files);
}

function safeId(value) {
  return String(value).replace(/[^A-Za-z0-9._:-]+/g, '_');
}

function executableRow({
  id,
  action,
  assertion,
  evidence = [],
  verdict = 'untested',
  verdictBy = null,
  surface = 'ui',
  dependencies = 'real-stack',
  authority = 'blocking-release',
  driver = 'browser',
  source,
}) {
  return {
    id,
    action,
    evidence,
    assertion,
    verdict,
    verdict_by: verdictBy,
    surface,
    dependencies,
    authority,
    driver,
    ...(source ? { source } : {}),
  };
}

function walkConfig(candidate, visitor, currentPath = 'root', currentBlockId = null) {
  if (!candidate || typeof candidate !== 'object') return;
  if (Array.isArray(candidate)) {
    candidate.forEach((item, index) =>
      walkConfig(item, visitor, `${currentPath}[${index}]`, currentBlockId),
    );
    return;
  }
  const nextBlockId = candidate.blockType ? (candidate.id ?? currentBlockId) : currentBlockId;
  visitor(candidate, currentPath, nextBlockId);
  for (const [key, value] of Object.entries(candidate)) {
    walkConfig(value, visitor, `${currentPath}.${key}`, nextBlockId);
  }
}

function resolveUiActionTarget(item, modelCode, commandByModel) {
  const action = item?.action;
  if (action && typeof action === 'object') {
    let target = action.command ?? action.to ?? action.name ?? null;
    if (action.type === 'command' && target === null && modelCode && commandByModel) {
      const conventionTypes = {
        create: ['create'],
        edit: ['update'],
        delete: ['delete'],
        save: ['create', 'update'],
        submit: ['create', 'update'],
      };
      const allowedTypes = conventionTypes[item.code] ?? [];
      const candidates = (commandByModel.get(modelCode) ?? []).filter((command) =>
        allowedTypes.includes(command.type),
      );
      if (candidates.length === 1) target = candidates[0].code;
    }
    return {
      targetType: action.type ?? 'unknown',
      target,
    };
  }
  if (item?.onClick?.action === 'command.execute') {
    return {
      targetType: 'command',
      target: item.onClick.args?.command ?? null,
    };
  }
  if (item?.onClick?.action === 'navigate') {
    return { targetType: 'navigate', target: item.onClick.args?.to ?? null };
  }
  return {
    targetType: item?.commandCode ? 'command' : item?.navigateTo ? 'navigate' : 'unknown',
    target: item?.commandCode ?? item?.navigateTo ?? null,
  };
}

function collectPageStructure(page, source, commandByModel = null) {
  const blocks = [];
  const fields = [];
  const uiActions = [];
  const actionKeys = new Map([
    ['actions', 'action'],
    ['buttons', 'button'],
    ['rowActions', 'row-action'],
    ['bulkActions', 'bulk-action'],
  ]);

  walkConfig(page.blocks ?? [], (candidate, candidatePath, blockId) => {
    if (candidate.blockType) {
      blocks.push({
        id: candidate.id ?? candidatePath,
        blockType: candidate.blockType,
        path: candidatePath,
      });
    }
    if (typeof candidate.field === 'string') {
      fields.push({
        field: candidate.field,
        blockId: blockId ?? 'page',
        path: `${candidatePath}.field`,
      });
    }
    for (const [key, actionKind] of actionKeys) {
      const items = candidate[key];
      if (!Array.isArray(items)) continue;
      items.forEach((item, index) => {
        if (!item || typeof item !== 'object') return;
        const code = item.code ?? item.key ?? `${actionKind}-${index}`;
        uiActions.push({
          code,
          actionKind,
          blockId: blockId ?? candidate.id ?? 'page',
          path: `${candidatePath}.${key}[${index}]`,
          permissionCode: item.permissionCode ?? item.permission ?? null,
          ...resolveUiActionTarget(item, page.modelCode, commandByModel),
        });
      });
    }
    if (Array.isArray(candidate.tabs)) {
      candidate.tabs.forEach((tab, index) => {
        if (!tab || typeof tab !== 'object') return;
        uiActions.push({
          code: tab.key ?? `tab-${index}`,
          actionKind: 'tab',
          blockId: blockId ?? candidate.id ?? 'page',
          path: `${candidatePath}.tabs[${index}]`,
          permissionCode: tab.permissionCode ?? null,
          targetType: 'view-state',
          target: tab.key ?? null,
        });
      });
    }
    if (candidate.onRowClick) {
      uiActions.push({
        code: 'row-click',
        actionKind: 'row-click',
        blockId: blockId ?? candidate.id ?? 'page',
        path: `${candidatePath}.onRowClick`,
        permissionCode: null,
        targetType: String(candidate.onRowClick),
        target: candidate.detailUrl ?? null,
      });
    }
  });

  return { source, blocks, fields, uiActions };
}

function pageEvidence(pageKey, releasePageRows) {
  return releasePageRows.find((row) => row.id === pageKey)?.evidence ?? [];
}

function buildProductGroups({
  pageEntries,
  commandEntries,
  models,
  menus,
  permissions,
  queries,
  dashboards,
  releasePageRows,
  releaseCommandRows,
  releasePermissionRows,
  releaseQueryRows,
  releaseSemanticActions,
}) {
  const pages = pageEntries.map((entry) => entry.value);
  const commands = commandEntries.map((entry) => entry.value);
  const pageMap = new Map(pages.map((page) => [page.pageKey, page]));
  const commandByModel = new Map();
  for (const command of commands) {
    if (!command.modelCode) continue;
    const bucket = commandByModel.get(command.modelCode) ?? [];
    bucket.push(command);
    commandByModel.set(command.modelCode, bucket);
  }
  const releaseCommandMap = new Map(releaseCommandRows.map((row) => [row.id, row]));
  const releasePermissionMap = new Map(releasePermissionRows.map((row) => [row.id, row]));
  const releaseQueryMap = new Map(releaseQueryRows.map((row) => [row.id, row]));
  const releaseSemanticMap = new Map(
    releaseSemanticActions.map((row) => [`${row.pageKey}:${row.blockId}:${row.id}`, row]),
  );

  const modelRows = models.map((model) => {
    const modelPages = pages.filter((page) => page.modelCode === model.code);
    const directMenu = menus.some(
      (menu) => menu.pageKey && modelPages.some((page) => page.pageKey === menu.pageKey),
    );
    const directlyExposed = modelPages.length > 0 || directMenu;
    const modelCommands = commandByModel.get(model.code) ?? [];
    const expectedKinds = directlyExposed
      ? uniq([
          'list',
          'detail',
          ...(modelCommands.some((command) => ['create', 'update'].includes(command.type)) &&
          !SYSTEM_MANAGED_NO_FORM_MODELS.has(model.code)
            ? ['form']
            : []),
        ])
      : [];
    const actualKinds = uniq(modelPages.map((page) => page.kind));
    const missingKinds = expectedKinds.filter((kind) => !actualKinds.includes(kind));
    return executableRow({
      id: `model:${model.code}`,
      action: directlyExposed
        ? `Expose ${model.code} through required ${expectedKinds.join('/')} page surfaces`
        : `Keep support model ${model.code} out of the direct-page denominator`,
      assertion:
        missingKinds.length > 0
          ? `Missing required page kinds: ${missingKinds.join(', ')}`
          : `Actual page kinds: ${actualKinds.join(', ') || 'support-only'}; expected: ${expectedKinds.join(', ') || 'none'}`,
      evidence: ['plugins/crm/tests/crm-release-coverage.test.mjs'],
      verdict: missingKinds.length > 0 ? 'gap' : 'pass',
      verdictBy: 'spec-assert',
      surface: 'contract',
      dependencies: 'hermetic',
      authority: 'blocking-commit',
      driver: 'unit',
      source: 'plugins/crm/config/models.json',
    });
  });

  const pageRows = pageEntries.map(({ value: page, source }) => {
    const evidence = pageEvidence(page.pageKey, releasePageRows);
    return executableRow({
      id: `page:${page.pageKey}`,
      action: `Open ${page.pageKey} (${page.kind}) from its supported product entry and exercise its complete page journey`,
      assertion:
        evidence.length > 0
          ? 'Real-stack browser evidence exists for the current release scope.'
          : 'No qualifying browser journey is registered for this page; it remains in the denominator.',
      evidence,
      verdict: evidence.length > 0 ? 'pass' : 'untested',
      verdictBy: evidence.length > 0 ? 'spec-assert' : null,
      source,
    });
  });

  const blockRows = [];
  const fieldRows = [];
  const uiActionRows = [];
  for (const entry of pageEntries) {
    const page = entry.value;
    const structure = collectPageStructure(page, entry.source, commandByModel);
    structure.blocks.forEach((block, index) => {
      const blockKey = `${page.pageKey}:${block.id}`;
      const evidence = uniq([
        ...(coreWorkbenchCoverageSets.blocks.has(blockKey) ? [EVIDENCE.rg1Browser] : []),
        ...(releaseBCoverageSets.blocks.has(blockKey) ? [EVIDENCE.releaseB] : []),
        ...(forecastVarianceCoverageSets.blocks.has(blockKey) ? [EVIDENCE.forecastVariance] : []),
        ...(amosB01CoverageSets.blocks.has(blockKey) ? [EVIDENCE.amosB01Browser] : []),
        ...(customerPoolCoverageSets.blocks.has(blockKey) ? [EVIDENCE.customerPool] : []),
        ...(followupCommentCoverageSets.blocks.has(blockKey)
          ? [EVIDENCE.followupComments]
          : []),
        ...(contactFollowupLifecycleCoverageSets.blocks.has(blockKey)
          ? [EVIDENCE.contactFollowupLifecycle]
          : []),
        ...(leadConversionActivityCarryCoverageSets.blocks.has(blockKey)
          ? [EVIDENCE.leadConversionActivityCarry]
          : []),
        ...(accountCollaborationCoverageSets.blocks.has(blockKey)
          ? [EVIDENCE.accountCollaboration]
          : []),
        ...(accountRelationshipCoverageSets.blocks.has(blockKey)
          ? [EVIDENCE.accountRelationship]
          : []),
      ]);
      blockRows.push(
        executableRow({
          id: `block:${safeId(page.pageKey)}:${safeId(block.id)}:${index}`,
          action: `Render and semantically verify ${block.blockType} block ${block.id} on ${page.pageKey}`,
          assertion:
            evidence.length > 0
              ? 'The registered real-stack journey renders this block and asserts its business state.'
              : 'Block-level browser visibility, content, loading, empty, error, and permission evidence is not yet registered.',
          evidence,
          verdict: evidence.length > 0 ? 'pass' : 'untested',
          verdictBy: evidence.length > 0 ? 'spec-assert' : null,
          source: `${entry.source}#${block.path}`,
        }),
      );
    });
    structure.fields.forEach((field, index) => {
      const fieldKey = `${page.pageKey}:${field.blockId}:${field.field}`;
      const evidence = uniq([
        ...(coreWorkbenchCoverageSets.fields.has(fieldKey) ? [EVIDENCE.rg1Browser] : []),
        ...(releaseBCoverageSets.fields.has(fieldKey) ? [EVIDENCE.releaseB] : []),
        ...(forecastVarianceCoverageSets.fields.has(fieldKey) ? [EVIDENCE.forecastVariance] : []),
        ...(amosB01CoverageSets.fields.has(fieldKey) ? [EVIDENCE.amosB01Browser] : []),
        ...(customerPoolCoverageSets.fields.has(fieldKey) ? [EVIDENCE.customerPool] : []),
        ...(contactFollowupLifecycleCoverageSets.fields.has(fieldKey)
          ? [EVIDENCE.contactFollowupLifecycle]
          : []),
        ...(accountRelationshipCoverageSets.fields.has(fieldKey)
          ? [EVIDENCE.accountRelationship]
          : []),
      ]);
      fieldRows.push(
        executableRow({
          id: `field:${safeId(page.pageKey)}:${safeId(field.blockId)}:${safeId(field.field)}:${index}`,
          action: `Verify field ${field.field} in ${field.blockId} on ${page.pageKey}`,
          assertion:
            evidence.length > 0
              ? 'The registered real-stack journey asserts this field fact in its consuming page.'
              : 'Label, component type, required state, payload, list/detail/form persistence and negative-path evidence are not yet registered.',
          evidence,
          verdict: evidence.length > 0 ? 'pass' : 'untested',
          verdictBy: evidence.length > 0 ? 'spec-assert' : null,
          source: `${entry.source}#${field.path}`,
        }),
      );
    });
    structure.uiActions.forEach((item, index) => {
      const releaseAction = releaseSemanticMap.get(`${page.pageKey}:${item.blockId}:${item.code}`);
      const releaseBAction = releaseBCoverageSets.uiActions.has(
        `${page.pageKey}:${item.blockId}:${item.code}`,
      );
      const forecastVarianceAction = forecastVarianceCoverageSets.uiActions.has(
        `${page.pageKey}:${item.blockId}:${item.code}`,
      );
      const amosB01Action = amosB01CoverageSets.uiActions.has(
        `${page.pageKey}:${item.blockId}:${item.code}`,
      );
      const customerPoolAction = customerPoolCoverageSets.uiActions.has(
        `${page.pageKey}:${item.blockId}:${item.code}`,
      );
      const contactFollowupLifecycleAction =
        contactFollowupLifecycleCoverageSets.uiActions.has(
          `${page.pageKey}:${item.blockId}:${item.code}`,
        );
      const leadConversionActivityCarryAction =
        leadConversionActivityCarryCoverageSets.uiActions.has(
          `${page.pageKey}:${item.blockId}:${item.code}`,
        );
      const accountCollaborationAction = accountCollaborationCoverageSets.uiActions.has(
        `${page.pageKey}:${item.blockId}:${item.code}`,
      );
      const accountRelationshipAction = accountRelationshipCoverageSets.uiActions.has(
        `${page.pageKey}:${item.blockId}:${item.code}`,
      );
      const evidence = uniq([
        ...(releaseAction?.evidence ?? []),
        ...(releaseBAction ? [EVIDENCE.releaseB] : []),
        ...(forecastVarianceAction ? [EVIDENCE.forecastVariance] : []),
        ...(amosB01Action ? [EVIDENCE.amosB01Browser] : []),
        ...(customerPoolAction ? [EVIDENCE.customerPool] : []),
        ...(contactFollowupLifecycleAction ? [EVIDENCE.contactFollowupLifecycle] : []),
        ...(leadConversionActivityCarryAction ? [EVIDENCE.leadConversionActivityCarry] : []),
        ...(accountCollaborationAction ? [EVIDENCE.accountCollaboration] : []),
        ...(accountRelationshipAction ? [EVIDENCE.accountRelationship] : []),
      ]);
      uiActionRows.push(
        executableRow({
          id: `ui-action:${safeId(page.pageKey)}:${safeId(item.blockId)}:${safeId(item.code)}:${index}`,
          action: `${item.actionKind} ${item.code} on ${page.pageKey} -> ${item.targetType}:${item.target ?? 'n/a'}`,
          assertion:
            evidence.length > 0
              ? 'The registered real-stack journey executes this semantic action and asserts its user-visible result.'
              : 'This visible action has no qualifying executable evidence and remains in the denominator.',
          evidence,
          verdict: evidence.length > 0 ? 'pass' : 'untested',
          verdictBy: evidence.length > 0 ? 'spec-assert' : null,
          source: `${entry.source}#${item.path}`,
        }),
      );
    });
  }

  const commandRows = commandEntries.map(({ value: command, source }) => {
    const release = releaseCommandMap.get(command.code);
    return executableRow({
      id: `command:${command.code}`,
      action: `Execute ${command.code} (${command.type}) through its supported user or runtime path`,
      assertion: release
        ? 'The command has registered release evidence.'
        : 'No complete browser/backend evidence pair is registered for this command.',
      evidence: release?.evidence ?? [],
      verdict: release ? 'pass' : 'untested',
      verdictBy: release ? 'spec-assert' : null,
      surface: release ? 'journey' : 'service',
      dependencies: 'real-stack',
      driver: release ? 'browser' : 'it',
      source,
    });
  });

  const permissionRows = permissions.map((permission) => {
    const release = releasePermissionMap.get(permission.code);
    return executableRow({
      id: `permission:${permission.code}`,
      action: `Verify allow, deny, menu, action and direct-API behavior for ${permission.code}`,
      assertion: release
        ? 'The permission participates in registered release evidence.'
        : 'Positive and negative persona evidence is not registered.',
      evidence: release?.evidence ?? [],
      verdict: release ? 'pass' : 'untested',
      verdictBy: release ? 'spec-assert' : null,
      surface: 'journey',
      dependencies: 'real-stack',
      driver: 'browser',
      source: 'plugins/crm/config/permissions.json',
    });
  });

  const queryRows = queries.map((query) => {
    const release = releaseQueryMap.get(query.code);
    return executableRow({
      id: `query:${query.code}`,
      action: `Execute ${query.code} with tenant isolation and assert non-empty business semantics where fixtures require data`,
      assertion: release
        ? 'The named query has registered release evidence.'
        : 'No real-stack response and consuming-page evidence pair is registered.',
      evidence: release?.evidence ?? [],
      verdict: release ? 'pass' : 'untested',
      verdictBy: release ? 'spec-assert' : null,
      surface: 'api',
      dependencies: 'real-stack',
      driver: 'http',
      source: 'plugins/crm/config/named-queries.json',
    });
  });

  const menuRows = menus.map((menu) => {
    const releaseEvidence = uniq([
      ...(menu.pageKey ? pageEvidence(menu.pageKey, releasePageRows) : []),
      ...(dashboardCoverageSets.pages.has(menu.pageKey) ? [EVIDENCE.dashboards] : []),
    ]);
    const isDirectory = menu.type === 0;
    return executableRow({
      id: `menu:${menu.code}`,
      action: isDirectory
        ? `Render CRM menu group ${menu.code}`
        : `Navigate through menu ${menu.code} to ${menu.path ?? menu.pageKey ?? 'n/a'}`,
      assertion: isDirectory
        ? 'Directory structure is freshness-checked by the manifest generator.'
        : releaseEvidence.length > 0
          ? 'The release browser journey enters this product surface through the menu.'
          : 'Menu visibility, navigation, permission and destination state are not registered.',
      evidence: isDirectory ? ['plugins/crm/tests/crm-release-coverage.test.mjs'] : releaseEvidence,
      verdict: isDirectory || releaseEvidence.length > 0 ? 'pass' : 'untested',
      verdictBy: isDirectory || releaseEvidence.length > 0 ? 'spec-assert' : null,
      surface: isDirectory ? 'contract' : 'journey',
      dependencies: isDirectory ? 'hermetic' : 'real-stack',
      authority: isDirectory ? 'blocking-commit' : 'blocking-release',
      driver: isDirectory ? 'unit' : 'browser',
      source: 'plugins/crm/config/menus.json',
    });
  });

  const dashboardRows = [];
  for (const dashboard of dashboards) {
    for (const widget of dashboard.widgets ?? []) {
      const cards = Array.isArray(widget.config?.cards) ? widget.config.cards : null;
      const targets = cards?.length
        ? cards.map((card) => ({
            id: card.field ?? 'card',
            drillDown: card.drillDown ?? widget.config?.drillDown,
          }))
        : [{ id: widget.id, drillDown: widget.config?.drillDown }];
      for (const target of targets) {
        const rowActions = Array.isArray(widget.config?.table?.rowActions)
          ? widget.config.table.rowActions
          : [];
        const rowDetailConfigured = rowActions.some(
          (action) => action?.action?.type === 'navigate' && Boolean(action.action.to),
        );
        const actionableMetric = widget.type !== 'smart-table-chart' || rowActions.length > 0;
        const drillDownConfigured = Boolean(target.drillDown?.enabled) || rowDetailConfigured;
        const targetKey = `${dashboard.code}:${widget.id}:${target.id}`;
        const widgetKey = `${dashboard.code}:${widget.id}`;
        const evidence = uniq([
          ...(releaseBCoverageSets.dashboardTargets.has(targetKey) ? [EVIDENCE.releaseB] : []),
          ...(dashboardCoverageSets.dashboardTargets.has(widgetKey) ? [EVIDENCE.dashboards] : []),
        ]);
        dashboardRows.push(
          executableRow({
            id: `dashboard:${dashboard.code}:${widget.id}:${safeId(target.id)}`,
            action: `Render ${dashboard.code}/${widget.id}/${target.id} with localized business data and a valid configured detail path`,
            assertion:
              evidence.length > 0
                ? 'The registered true-stack journey asserts widget data, record context, layout and technical-leak absence.'
                : actionableMetric && !drillDownConfigured
                  ? 'Actionable metric or chart has no drill-down contract.'
                  : rowDetailConfigured
                    ? 'A row-level detail path exists; value, identity and destination equivalence are not yet registered.'
                    : 'Widget rendering, named-query values, filters and destination equivalence are not yet registered.',
            evidence,
            verdict:
              evidence.length > 0
                ? 'pass'
                : actionableMetric && !drillDownConfigured
                  ? 'gap'
                  : 'untested',
            verdictBy: evidence.length > 0 ? 'spec-assert' : null,
            source: `plugins/crm/config/dashboards/${dashboard.code}.json`,
          }),
        );
      }
    }
  }

  return [
    {
      id: 'model-surfaces',
      title: 'Model and page-surface contracts',
      rows: modelRows,
    },
    { id: 'menus', title: 'Menu entries and navigation', rows: menuRows },
    { id: 'pages', title: 'All CRM pages', rows: pageRows },
    { id: 'page-blocks', title: 'Every page block', rows: blockRows },
    { id: 'page-fields', title: 'Every page field reference', rows: fieldRows },
    {
      id: 'ui-actions',
      title: 'Every visible page action and tab',
      rows: uiActionRows,
    },
    { id: 'commands', title: 'All CRM commands', rows: commandRows },
    { id: 'permissions', title: 'All CRM permissions', rows: permissionRows },
    { id: 'queries', title: 'All CRM named queries', rows: queryRows },
    {
      id: 'dashboards',
      title: 'Dashboard widgets and drill-down facts',
      rows: dashboardRows,
    },
  ];
}

export function buildReleaseManifest() {
  const pageEntries = jsonDirectoryEntries('pages/');
  const commandEntries = jsonDirectoryEntries('commands/');
  const pages = new Map(pageEntries.map(({ value: page }) => [page.pageKey, page]));
  const commands = new Map(commandEntries.map(({ value: command }) => [command.code, command]));
  const permissionDefinitions = json('permissions.json');
  const queryDefinitions = json('named-queries.json');
  const permissions = new Map(
    permissionDefinitions.map((permission) => [permission.code, permission]),
  );
  const queries = new Map(queryDefinitions.map((query) => [query.code, query]));
  const menus = json('menus.json');
  const models = json('models.json');
  const dashboards = jsonDirectory('dashboards/');

  const semanticActions = [];
  for (const pageKey of RG1_PAGES) {
    const page = pages.get(pageKey);
    assert.ok(page, `missing RG-1 page ${pageKey}`);
    for (const block of allBlocks(page)) {
      for (const action of block.actions ?? []) {
        const target = actionTarget(action);
        semanticActions.push({
          id: action.code,
          pageKey,
          blockId: block.id,
          ...target,
          permissionCode: action.permissionCode ?? null,
          evidence: [EVIDENCE.rg1Browser],
          verdict: 'pass',
        });
      }
    }
  }
  assert.equal(semanticActions.length, 26, 'RG-1 semantic-action denominator drifted');
  assert.equal(
    new Set(semanticActions.map((row) => row.id)).size,
    26,
    'RG-1 semantic action codes must be unique',
  );

  const coreWorkbenchRuntimeCoverage = {
    ...CORE_WORKBENCH_COVERAGE,
    uiActions: semanticActions.map((row) => `${row.pageKey}:${row.blockId}:${row.id}`),
  };
  for (const blockKey of CORE_WORKBENCH_COVERAGE.blocks) {
    const separator = blockKey.indexOf(':');
    const pageKey = blockKey.slice(0, separator);
    const blockId = blockKey.slice(separator + 1);
    assert.ok(
      allBlocks(pages.get(pageKey)).some((block) => block.id === blockId),
      `RG-1 covered block no longer exists: ${blockKey}`,
    );
  }
  for (const fieldKey of CORE_WORKBENCH_COVERAGE.fields) {
    const [pageKey, blockId, ...fieldParts] = fieldKey.split(':');
    const field = fieldParts.join(':');
    const page = pages.get(pageKey);
    const structure = collectPageStructure(page, `plugins/crm/config/pages/${pageKey}.json`);
    assert.ok(
      structure.fields.some(
        (candidate) => candidate.blockId === blockId && candidate.field === field,
      ),
      `RG-1 covered field no longer exists: ${fieldKey}`,
    );
  }
  for (const blockKey of FORECAST_VARIANCE_COVERAGE.blocks) {
    const separator = blockKey.indexOf(':');
    const pageKey = blockKey.slice(0, separator);
    const blockId = blockKey.slice(separator + 1);
    assert.ok(
      allBlocks(pages.get(pageKey)).some((block) => block.id === blockId),
      `forecast-variance covered block no longer exists: ${blockKey}`,
    );
  }
  for (const fieldKey of FORECAST_VARIANCE_COVERAGE.fields) {
    const [pageKey, blockId, ...fieldParts] = fieldKey.split(':');
    const field = fieldParts.join(':');
    const page = pages.get(pageKey);
    const structure = collectPageStructure(page, `plugins/crm/config/pages/${pageKey}.json`);
    assert.ok(
      structure.fields.some(
        (candidate) => candidate.blockId === blockId && candidate.field === field,
      ),
      `forecast-variance covered field no longer exists: ${fieldKey}`,
    );
  }
  for (const blockKey of CUSTOMER_POOL_COVERAGE.blocks) {
    const separator = blockKey.indexOf(':');
    const pageKey = blockKey.slice(0, separator);
    const blockId = blockKey.slice(separator + 1);
    assert.ok(
      allBlocks(pages.get(pageKey)).some((block) => block.id === blockId),
      `customer-pool covered block no longer exists: ${blockKey}`,
    );
  }
  for (const fieldKey of CUSTOMER_POOL_COVERAGE.fields) {
    const [pageKey, blockId, ...fieldParts] = fieldKey.split(':');
    const field = fieldParts.join(':');
    const page = pages.get(pageKey);
    const structure = collectPageStructure(page, `plugins/crm/config/pages/${pageKey}.json`);
    assert.ok(
      structure.fields.some(
        (candidate) => candidate.blockId === blockId && candidate.field === field,
      ),
      `customer-pool covered field no longer exists: ${fieldKey}`,
    );
  }
  for (const actionKey of CUSTOMER_POOL_COVERAGE.uiActions) {
    const [pageKey, blockId, ...actionParts] = actionKey.split(':');
    const code = actionParts.join(':');
    if (blockId === 'platform') continue;
    const page = pages.get(pageKey);
    const structure = collectPageStructure(page, `plugins/crm/config/pages/${pageKey}.json`);
    assert.ok(
      structure.uiActions.some(
        (candidate) => candidate.blockId === blockId && candidate.code === code,
      ),
      `customer-pool covered UI action no longer exists: ${actionKey}`,
    );
  }
  for (const blockKey of LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE.blocks) {
    const separator = blockKey.indexOf(':');
    const pageKey = blockKey.slice(0, separator);
    const blockId = blockKey.slice(separator + 1);
    assert.ok(
      allBlocks(pages.get(pageKey)).some((block) => block.id === blockId),
      `lead-conversion covered block no longer exists: ${blockKey}`,
    );
  }
  for (const actionKey of LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE.uiActions) {
    const [pageKey, blockId, ...actionParts] = actionKey.split(':');
    const code = actionParts.join(':');
    const page = pages.get(pageKey);
    const structure = collectPageStructure(page, `plugins/crm/config/pages/${pageKey}.json`);
    assert.ok(
      structure.uiActions.some(
        (candidate) => candidate.blockId === blockId && candidate.code === code,
      ),
      `lead-conversion covered UI action no longer exists: ${actionKey}`,
    );
  }
  for (const blockKey of ACCOUNT_COLLABORATION_COVERAGE.blocks) {
    const separator = blockKey.indexOf(':');
    const pageKey = blockKey.slice(0, separator);
    const blockId = blockKey.slice(separator + 1);
    assert.ok(
      allBlocks(pages.get(pageKey)).some((block) => block.id === blockId),
      `account-collaboration covered block no longer exists: ${blockKey}`,
    );
  }
  for (const actionKey of ACCOUNT_COLLABORATION_COVERAGE.uiActions) {
    const [pageKey, blockId, ...actionParts] = actionKey.split(':');
    if (blockId === 'platform') continue;
    const code = actionParts.join(':');
    const structure = collectPageStructure(
      pages.get(pageKey),
      `plugins/crm/config/pages/${pageKey}.json`,
    );
    assert.ok(
      structure.uiActions.some(
        (candidate) => candidate.blockId === blockId && candidate.code === code,
      ),
      `account-collaboration covered UI action no longer exists: ${actionKey}`,
    );
  }
  for (const blockKey of ACCOUNT_RELATIONSHIP_COVERAGE.blocks) {
    const separator = blockKey.indexOf(':');
    const pageKey = blockKey.slice(0, separator);
    const blockId = blockKey.slice(separator + 1);
    assert.ok(
      allBlocks(pages.get(pageKey)).some((block) => block.id === blockId),
      `account-relationship covered block no longer exists: ${blockKey}`,
    );
  }
  for (const actionKey of ACCOUNT_RELATIONSHIP_COVERAGE.uiActions) {
    const [pageKey, blockId, ...actionParts] = actionKey.split(':');
    const code = actionParts.join(':');
    const structure = collectPageStructure(
      pages.get(pageKey),
      `plugins/crm/config/pages/${pageKey}.json`,
    );
    assert.ok(
      structure.uiActions.some(
        (candidate) => candidate.blockId === blockId && candidate.code === code,
      ),
      `account-relationship covered UI action no longer exists: ${actionKey}`,
    );
  }

  const rg1Commands = new Set(
    semanticActions.filter((row) => row.targetType === 'command').map((row) => row.target),
  );
  const commandCodes = uniq([
    ...rg1Commands,
    ...CORE_WORKBENCH_COVERAGE.commands,
    ...RG2_COMMANDS,
    ...RG3_COMMANDS,
    ...AMOS_P0_B01_COMMANDS,
    ...RELEASE_B_COVERAGE.commands,
    ...CUSTOMER_POOL_COVERAGE.commands,
    ...CONTACT_FOLLOWUP_LIFECYCLE_COVERAGE.commands,
    ...LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE.commands,
    ...ACCOUNT_COLLABORATION_COVERAGE.commands,
    ...ACCOUNT_RELATIONSHIP_COVERAGE.commands,
  ]);
  const commandRows = commandCodes.map((code) => {
    const command = commands.get(code);
    assert.ok(command, `missing release command ${code}`);
    const goals = [];
    if (rg1Commands.has(code) || coreWorkbenchCoverageSets.commands.has(code)) goals.push('RG-1');
    if (RG2_COMMANDS.includes(code)) goals.push('RG-2');
    if (RG3_COMMANDS.includes(code)) goals.push('RG-3');
    if (AMOS_P0_B01_COMMANDS.includes(code)) goals.push('AMOS-P0-B01');
    if (releaseBCoverageSets.commands.has(code)) goals.push('RELEASE-B');
    if (customerPoolCoverageSets.commands.has(code)) goals.push('CORDYS-CUSTOMER-POOL');
    if (contactFollowupLifecycleCoverageSets.commands.has(code)) {
      goals.push('CORDYS-CONTACT-FOLLOWUP-LIFECYCLE');
    }
    if (leadConversionActivityCarryCoverageSets.commands.has(code)) {
      goals.push('CORDYS-LEAD-CONVERSION-ACTIVITY-CARRY');
    }
    if (accountCollaborationCoverageSets.commands.has(code)) {
      goals.push('CORDYS-ACCOUNT-COLLABORATION');
    }
    if (accountRelationshipCoverageSets.commands.has(code)) {
      goals.push('CORDYS-ACCOUNT-RELATIONSHIP');
    }
    return {
      id: code,
      goals,
      permissionCodes: uniq(command.permissions ?? []),
      evidence: evidenceForCommand(code, rg1Commands),
      permissionsVerified: goals.some((goal) =>
        [
          'RG-1',
          'RG-2',
          'RG-3',
          'CORDYS-CUSTOMER-POOL',
          'CORDYS-ACCOUNT-COLLABORATION',
          'CORDYS-ACCOUNT-RELATIONSHIP',
        ].includes(goal),
      ),
      verdict: 'pass',
    };
  });

  const pageRows = uniq([
    ...RG1_PAGES,
    ...RG2_PAGES,
    ...RELEASE_B_COVERAGE.pages,
    ...CUSTOMER_POOL_COVERAGE.pages,
    ...FOLLOWUP_COMMENT_COVERAGE.pages,
    ...CONTACT_FOLLOWUP_LIFECYCLE_COVERAGE.pages,
    ...LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE.pages,
    ...ACCOUNT_COLLABORATION_COVERAGE.pages,
    ...ACCOUNT_RELATIONSHIP_COVERAGE.pages,
  ]).map(
    (pageKey) => {
      const page = pages.get(pageKey);
      assert.ok(page, `missing release page ${pageKey}`);
      const goals = [
        ...(RG1_PAGES.includes(pageKey) ? ['RG-1'] : []),
        ...(pageKey === 'crm_lead_desk_workbench' ? ['RG-3'] : []),
        ...(RG2_PAGES.includes(pageKey) ? ['RG-2'] : []),
        ...(releaseBCoverageSets.pages.has(pageKey) ? ['RELEASE-B'] : []),
        ...(customerPoolCoverageSets.pages.has(pageKey) ? ['CORDYS-CUSTOMER-POOL'] : []),
        ...(followupCommentCoverageSets.pages.has(pageKey)
          ? ['CORDYS-FOLLOWUP-COMMENTS']
          : []),
        ...(contactFollowupLifecycleCoverageSets.pages.has(pageKey)
          ? ['CORDYS-CONTACT-FOLLOWUP-LIFECYCLE']
          : []),
        ...(leadConversionActivityCarryCoverageSets.pages.has(pageKey)
          ? ['CORDYS-LEAD-CONVERSION-ACTIVITY-CARRY']
          : []),
        ...(accountCollaborationCoverageSets.pages.has(pageKey)
          ? ['CORDYS-ACCOUNT-COLLABORATION']
          : []),
        ...(accountRelationshipCoverageSets.pages.has(pageKey)
          ? ['CORDYS-ACCOUNT-RELATIONSHIP']
          : []),
      ];
      const evidence = uniq([
        ...(RG1_PAGES.includes(pageKey) ? [EVIDENCE.rg1Browser] : []),
        ...(pageKey === 'crm_lead_desk_workbench' ? [EVIDENCE.rg3Journey] : []),
        ...(pageKey === 'crm_qdp_release_workbench' ? [EVIDENCE.rg2QdpBrowser] : []),
        ...(RG2_PAGES.includes(pageKey) && pageKey !== 'crm_qdp_release_workbench'
          ? [EVIDENCE.rg2OrderBrowser]
          : []),
        ...(releaseBCoverageSets.pages.has(pageKey) ? [EVIDENCE.releaseB] : []),
        ...(customerPoolCoverageSets.pages.has(pageKey) ? [EVIDENCE.customerPool] : []),
        ...(followupCommentCoverageSets.pages.has(pageKey)
          ? [EVIDENCE.followupComments]
          : []),
        ...(contactFollowupLifecycleCoverageSets.pages.has(pageKey)
          ? [EVIDENCE.contactFollowupLifecycle]
          : []),
        ...(leadConversionActivityCarryCoverageSets.pages.has(pageKey)
          ? [EVIDENCE.leadConversionActivityCarry]
          : []),
        ...(accountCollaborationCoverageSets.pages.has(pageKey)
          ? [EVIDENCE.accountCollaboration]
          : []),
        ...(accountRelationshipCoverageSets.pages.has(pageKey)
          ? [EVIDENCE.accountRelationship]
          : []),
      ]);
      const menuPermissions = menus
        .filter((menu) => menu.pageKey === pageKey && menu.permissionCode)
        .map((menu) => menu.permissionCode);
      return {
        id: pageKey,
        goals,
        kind: page.kind,
        permissionCodes: uniq([page.permissionCode, ...menuPermissions].filter(Boolean)),
        evidence: uniq(evidence),
        permissionsVerified: goals.some((goal) =>
          [
            'RG-1',
            'RG-2',
            'RG-3',
            'CORDYS-CUSTOMER-POOL',
            'CORDYS-ACCOUNT-COLLABORATION',
            'CORDYS-ACCOUNT-RELATIONSHIP',
          ].includes(goal),
        ),
        verdict: 'pass',
      };
    },
  );

  const permissionEvidence = new Map();
  const addPermissionEvidence = (code, files) => {
    if (!code) return;
    assert.ok(permissions.has(code), `missing release permission ${code}`);
    permissionEvidence.set(code, uniq([...(permissionEvidence.get(code) ?? []), ...files]));
  };
  for (const action of semanticActions)
    addPermissionEvidence(action.permissionCode, action.evidence);
  for (const command of commandRows) {
    if (!command.permissionsVerified) continue;
    for (const code of command.permissionCodes) addPermissionEvidence(code, command.evidence);
  }
  for (const page of pageRows) {
    if (!page.permissionsVerified) continue;
    for (const code of page.permissionCodes) addPermissionEvidence(code, page.evidence);
  }
  for (const code of RELEASE_B_COVERAGE.permissions) {
    addPermissionEvidence(code, [EVIDENCE.releaseB]);
  }
  for (const code of CUSTOMER_POOL_COVERAGE.permissions) {
    addPermissionEvidence(code, [EVIDENCE.customerPool]);
  }
  for (const code of ACCOUNT_COLLABORATION_COVERAGE.permissions) {
    addPermissionEvidence(code, [EVIDENCE.accountCollaboration]);
  }
  for (const code of ACCOUNT_RELATIONSHIP_COVERAGE.permissions) {
    addPermissionEvidence(code, [EVIDENCE.accountRelationship]);
  }
  const permissionRows = [...permissionEvidence.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([code, evidence]) => ({ id: code, evidence, verdict: 'pass' }));

  const queryEvidence = new Map();
  for (const pageRow of pageRows) {
    const page = pages.get(pageRow.id);
    const dataSources = Array.isArray(page.dataSources)
      ? page.dataSources
      : Object.values(page.dataSources ?? {});
    for (const dataSource of dataSources) {
      const code = dataSource.queryCode;
      if (!code) continue;
      assert.ok(queries.has(code), `missing release named query ${code}`);
      queryEvidence.set(code, uniq([...(queryEvidence.get(code) ?? []), ...pageRow.evidence]));
    }
  }
  for (const code of RELEASE_B_COVERAGE.queries) {
    assert.ok(queries.has(code), `missing Release B named query ${code}`);
    queryEvidence.set(code, uniq([...(queryEvidence.get(code) ?? []), EVIDENCE.releaseB]));
  }
  for (const code of CUSTOMER_POOL_COVERAGE.queries) {
    assert.ok(queries.has(code), `missing customer-pool named query ${code}`);
    queryEvidence.set(code, uniq([...(queryEvidence.get(code) ?? []), EVIDENCE.customerPool]));
  }
  for (const code of DASHBOARD_COVERAGE.queries) {
    assert.ok(queries.has(code), `missing dashboard named query ${code}`);
    queryEvidence.set(code, uniq([...(queryEvidence.get(code) ?? []), EVIDENCE.dashboards]));
  }
  for (const code of FORECAST_VARIANCE_COVERAGE.queries) {
    assert.ok(queries.has(code), `missing forecast-variance named query ${code}`);
    queryEvidence.set(code, uniq([...(queryEvidence.get(code) ?? []), EVIDENCE.forecastVariance]));
  }
  for (const code of LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE.queries) {
    assert.ok(queries.has(code), `missing lead-conversion named query ${code}`);
    queryEvidence.set(
      code,
      uniq([...(queryEvidence.get(code) ?? []), EVIDENCE.leadConversionActivityCarry]),
    );
  }
  const queryRows = [...queryEvidence.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([code, evidence]) => {
      const query = queries.get(code);
      return {
        id: code,
        resourceCode: query.resourceCode ?? null,
        actionCode: query.actionCode ?? null,
        evidence,
        verdict: 'pass',
      };
    });

  for (const source of Object.values(EVIDENCE)) {
    assert.ok(
      fs.existsSync(path.join(REPO_ROOT, source)),
      `missing executable evidence source ${source}`,
    );
  }

  const groups = buildProductGroups({
    pageEntries,
    commandEntries,
    models,
    menus,
    permissions: permissionDefinitions,
    queries: queryDefinitions,
    dashboards,
    releasePageRows: pageRows,
    releaseCommandRows: commandRows,
    releasePermissionRows: permissionRows,
    releaseQueryRows: queryRows,
    releaseSemanticActions: semanticActions,
  });
  const productDenominator = Object.fromEntries(
    groups.map((group) => [group.id, group.rows.length]),
  );
  const productVerdicts = groups
    .flatMap((group) => group.rows)
    .reduce((counts, row) => {
      counts[row.verdict] = (counts[row.verdict] ?? 0) + 1;
      return counts;
    }, {});

  return {
    schemaVersion: 2,
    release: 'AuraBoot CRM product gate (RG-1 through RG-4 verified subset)',
    generatedBy: 'plugins/crm/scripts/verify_release_coverage.mjs',
    run: {
      id: 'crm-full-product-denominator-v2',
      target: 'All AuraBoot CRM product pages and Cordys-parity action surfaces',
      sot: 'plugins/crm/README.md',
      runtime: 'static denominator; qualifying pass rows point to real-stack evidence sources',
      commit: 'source-tree',
      fix_rounds: 0,
    },
    scope: {
      semanticActionDenominator: 26,
      axes: ['semanticActions', 'commands', 'pages', 'permissions', 'queries'],
      excluded: ['RG-5 licensing and entitlement', 'data migration'],
      productDenominator,
      productVerdicts,
      fullProductNonGoals: ['data migration'],
    },
    goals: [
      {
        id: 'RG-1',
        verdict: 'pass',
        note: '26/26 semantic actions and five workbenches have real-stack browser evidence.',
      },
      {
        id: 'RG-2',
        verdict: 'pass',
        note: 'QDP release and order commitment have true-stack and browser evidence.',
      },
      {
        id: 'RG-3',
        verdict: 'partial',
        note: 'Clean-room automation passes; independent non-developer human sign-off is still pending.',
      },
      {
        id: 'RG-4',
        verdict: 'pass',
        note: 'This committed manifest is freshness-gated and mutation-falsifiable.',
      },
      {
        id: 'CORDYS-FORECAST-VARIANCE',
        verdict: 'pass',
        note: 'Selected forecast submissions are compared with live owner facts and drilled down to exact opportunity drivers.',
      },
      {
        id: 'AMOS-P0-B01',
        verdict: 'pass',
        note: 'The governed external Customer Request intake command and its read-only source-evidence summary have true-stack and browser evidence; actual Email/Portal connectors and Excel UX remain outside this slice.',
      },
      {
        id: 'CORDYS-CUSTOMER-POOL',
        verdict: 'pass',
        note: 'Three real-stack journeys cover member operations, manager batch work, administrator governance, recycle policy, ownership evidence, import/mobile behavior and negative boundaries without development-data migration.',
      },
      {
        id: 'CORDYS-CONTACT-FOLLOWUP-LIFECYCLE',
        verdict: 'pass',
        note: 'A self-seeded real-stack journey verifies contact primary/enable-disable invariants and the Web follow-up plan/record lifecycle without development-data migration.',
      },
      {
        id: 'CORDYS-LEAD-CONVERSION-ACTIVITY-CARRY',
        verdict: 'pass',
        note: 'A self-seeded browser journey converts a qualified lead and proves that direct and relation-anchored follow-up activities, comments and deduplicated customer history remain visible across the resulting account, contact and opportunity graph.',
      },
      {
        id: 'CORDYS-ACCOUNT-COLLABORATION',
        verdict: 'pass',
        note: 'A self-seeded two-person browser journey proves owner isolation, read-only and editable collaboration, expiry, renewal, revoke, notification, collaborative-account filtering and cross-tenant fail-closed behavior.',
      },
      {
        id: 'CORDYS-ACCOUNT-RELATIONSHIP',
        verdict: 'pass',
        note: 'A self-seeded browser journey creates one governed customer relationship fact from account detail, proves outgoing and incoming projections, edit persistence, self/missing/duplicate rollback, uniqueness and confirmed deletion.',
      },
    ],
    axes: {
      semanticActions,
      commands: commandRows,
      pages: pageRows,
      permissions: permissionRows,
      queries: queryRows,
    },
    runtimeEvidenceContracts: [
      {
        id: 'RG1-BROWSER',
        filePrefix: 'crm-core-workbenches-',
        expectedActions: 26,
        expectedScenarios: 5,
        minimumScreenshots: 20,
        expectedCoverage: coreWorkbenchRuntimeCoverage,
      },
      {
        id: 'RG2-QDP-STACK',
        filePrefix: 'qdp-release-center-true-stack-',
        minimumChecks: 20,
      },
      {
        id: 'RG2-QDP-BROWSER',
        filePrefix: 'qdp-release-center-browser-',
        minimumScenarios: 6,
        minimumScreenshots: 1,
      },
      {
        id: 'RG2-ORDER-STACK',
        filePrefix: 'order-commitment-true-stack-',
        minimumChecks: 8,
      },
      {
        id: 'RG2-ORDER-BROWSER',
        filePrefix: 'order-commitment-browser-',
        minimumScenarios: 3,
        minimumScreenshots: 2,
      },
      {
        id: 'RG3-CLEAN-ROOM',
        filePrefix: 'crm-adoption-journey-',
        maximumElapsedSeconds: 1800,
        minimumCheckpoints: 6,
      },
      {
        id: 'RELEASE-B-OPPORTUNITY',
        filePrefix: 'crm-opportunity-efficiency-',
        expectedScenarios: 12,
        minimumScreenshots: 27,
        expectedCoverage: RELEASE_B_COVERAGE,
      },
      {
        id: 'CRM-DASHBOARDS',
        filePrefix: 'crm-dashboard-parity-',
        expectedScenarios: 2,
        minimumScreenshots: 6,
        expectedCoverage: DASHBOARD_COVERAGE,
        expectedTechnicalVerdict: 'pass',
        expectedDataMigration: 'out-of-scope-development-stage',
        requireNoFailedRuntimeRequests: true,
        requireSeedLineage: true,
      },
      {
        id: 'CRM-FORECAST-VARIANCE',
        filePrefix: 'crm-forecast-variance-',
        expectedActions: 8,
        minimumScreenshots: 2,
        expectedCoverage: FORECAST_VARIANCE_COVERAGE,
      },
      {
        id: 'AMOS-P0-B01-STACK',
        filePrefix: 'customer-request-intake-true-stack-',
        minimumChecks: 9,
      },
      {
        id: 'AMOS-P0-B01-BROWSER',
        filePrefix: 'customer-request-intake-browser-',
        minimumScenarios: 2,
        minimumScreenshots: 2,
        expectedCoverage: AMOS_P0_B01_COVERAGE,
      },
      {
        id: 'CRM-CUSTOMER-POOL',
        filePrefix: 'crm-customer-pool-parity',
        expectedScenarios: 6,
        minimumScreenshots: 15,
        expectedCoverage: CUSTOMER_POOL_COVERAGE,
      },
      {
        id: 'CRM-FOLLOWUP-COMMENTS',
        filePrefix: 'crm-followup-comments-parity-',
        expectedScenarios: 12,
        minimumScreenshots: 6,
        expectedTechnicalVerdict: 'pass',
        expectedDataMigration: 'out-of-scope-development-stage',
        requireNoFailedRuntimeRequests: true,
      },
      {
        id: 'CRM-CONTACT-FOLLOWUP-LIFECYCLE',
        filePrefix: 'crm-contact-followup-lifecycle-',
        expectedScenarios: 14,
        minimumScreenshots: 7,
        expectedCoverage: CONTACT_FOLLOWUP_LIFECYCLE_COVERAGE,
        expectedTechnicalVerdict: 'pass',
        expectedDataMigration: 'out-of-scope-development-stage',
        requireNoFailedRuntimeRequests: true,
      },
      {
        id: 'CRM-LEAD-CONVERSION-ACTIVITY-CARRY',
        filePrefix: 'crm-lead-conversion-activity-carry-',
        expectedScenarios: 10,
        minimumScreenshots: 7,
        expectedCoverage: LEAD_CONVERSION_ACTIVITY_CARRY_COVERAGE,
        expectedTechnicalVerdict: 'pass',
        expectedDataMigration: 'out-of-scope-development-stage',
        requireNoFailedRuntimeRequests: true,
        expectedFixtureMode: 'self-seeded',
        requiredRecordIds: [
          'lead',
          'account',
          'contact',
          'opportunity',
          'customerRequest',
          'activities',
        ],
      },
      {
        id: 'CRM-ACCOUNT-COLLABORATION',
        filePrefix: 'crm-account-collaboration-',
        expectedScenarios: 14,
        minimumScreenshots: 7,
        expectedCoverage: ACCOUNT_COLLABORATION_COVERAGE,
        expectedTechnicalVerdict: 'pass',
        expectedDataMigration: 'out-of-scope-development-stage',
        requireNoFailedRuntimeRequests: true,
        expectedFixtureMode: 'self-seeded',
        requiredRecordIds: [
          'owner',
          'recipient',
          'otherTenantMember',
          'ownerAccount',
          'recipientAccount',
          'share',
        ],
      },
      {
        id: 'CRM-ACCOUNT-RELATIONSHIP',
        filePrefix: 'crm-account-relationship-',
        expectedScenarios: 12,
        minimumScreenshots: 7,
        expectedCoverage: ACCOUNT_RELATIONSHIP_COVERAGE,
        expectedTechnicalVerdict: 'pass',
        expectedDataMigration: 'out-of-scope-development-stage',
        requireNoFailedRuntimeRequests: true,
        expectedFixtureMode: 'self-seeded',
        requiredRecordIds: [
          'sourceAccount',
          'targetAccount',
          'thirdAccount',
          'relationship',
        ],
      },
    ],
    untested: [
      {
        id: 'RG3-INDEPENDENT-HUMAN-ADOPTER',
        verdict: 'untested',
        reason:
          'A developer-authored automation run cannot prove that a person who did not participate in development completed the browser journey.',
      },
    ],
    groups,
  };
}

function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, stable(value[key])]),
    );
  }
  return value;
}

export function assertManifestMatches(actual, expected = buildReleaseManifest()) {
  assert.deepEqual(
    stable(actual),
    stable(expected),
    'committed CRM coverage manifest drifted from DSL, executable evidence, or release scope',
  );
}

function walkFiles(root) {
  const files = [];
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    const candidate = path.join(root, entry.name);
    if (entry.isDirectory()) files.push(...walkFiles(candidate));
    else files.push(candidate);
  }
  return files;
}

function assertSameMembers(actual, expected, label) {
  assert.deepEqual(uniq(actual ?? []), uniq(expected ?? []), label);
}

function validateScreenshots(receipt, minimum, label) {
  assert.ok(
    (receipt.screenshots ?? []).length >= minimum,
    `${label} expected at least ${minimum} screenshots`,
  );
  for (const screenshot of receipt.screenshots ?? []) {
    assert.ok(fs.existsSync(screenshot), `${label} screenshot is missing: ${screenshot}`);
    assert.ok(fs.statSync(screenshot).size > 0, `${label} screenshot is empty: ${screenshot}`);
  }
}

export function verifyRuntimeEvidence(
  evidenceRoot,
  manifest = buildReleaseManifest(),
  contractIds = null,
) {
  const files = walkFiles(evidenceRoot).filter((file) => file.endsWith('.json'));
  const results = [];
  const contracts = contractIds?.length
    ? contractIds.map((id) => {
        const contract = manifest.runtimeEvidenceContracts.find((candidate) => candidate.id === id);
        assert.ok(contract, `unknown runtime evidence contract ${id}`);
        return contract;
      })
    : manifest.runtimeEvidenceContracts;
  for (const contract of contracts) {
    const candidates = files
      .filter((file) => path.basename(file).startsWith(contract.filePrefix))
      .map((file) => ({
        file,
        receipt: JSON.parse(fs.readFileSync(file, 'utf8')),
        mtimeMs: fs.statSync(file).mtimeMs,
      }))
      .filter(({ receipt }) => (receipt.verdict ?? receipt.technicalVerdict) === 'pass');
    assert.ok(candidates.length > 0, `${contract.id} has no passing machine receipt`);
    const selected =
      contract.id === 'RG3-CLEAN-ROOM'
        ? candidates.sort(
            (a, b) => (b.receipt.elapsedSeconds ?? 0) - (a.receipt.elapsedSeconds ?? 0),
          )[0]
        : candidates.sort((a, b) => b.mtimeMs - a.mtimeMs)[0];
    const { file, receipt } = selected;

    if (contract.expectedActions) {
      assert.equal(
        receipt.expectedActions?.length,
        contract.expectedActions,
        `${contract.id} denominator`,
      );
      assertSameMembers(
        receipt.completedActions,
        receipt.expectedActions,
        `${contract.id} incomplete action set`,
      );
    }
    if (contract.expectedScenarios) {
      assert.equal(
        receipt.expectedScenarios?.length,
        contract.expectedScenarios,
        `${contract.id} scenario denominator`,
      );
      assertSameMembers(
        receipt.completedScenarios,
        receipt.expectedScenarios,
        `${contract.id} incomplete scenarios`,
      );
    }
    if (contract.minimumScenarios) {
      assert.ok(
        (receipt.expectedScenarios ?? []).length >= contract.minimumScenarios,
        `${contract.id} scenario count`,
      );
      assertSameMembers(
        receipt.completedScenarios,
        receipt.expectedScenarios,
        `${contract.id} incomplete scenarios`,
      );
    }
    if (contract.minimumChecks) {
      assert.ok(
        (receipt.checks ?? []).length >= contract.minimumChecks,
        `${contract.id} check count`,
      );
      assert.ok(
        receipt.checks.every((check) => check.result === 'pass'),
        `${contract.id} contains a failed check`,
      );
    }
    if (contract.minimumCheckpoints) {
      assert.ok(
        (receipt.checkpoints ?? []).length >= contract.minimumCheckpoints,
        `${contract.id} checkpoint count`,
      );
      assert.ok(
        receipt.checkpoints.every((check) => check.result === 'pass'),
        `${contract.id} contains a failed checkpoint`,
      );
    }
    if (contract.maximumElapsedSeconds) {
      assert.ok(
        receipt.elapsedSeconds <= contract.maximumElapsedSeconds,
        `${contract.id} exceeded deadline`,
      );
    }
    if (contract.minimumScreenshots)
      validateScreenshots(receipt, contract.minimumScreenshots, contract.id);
    if (contract.expectedTechnicalVerdict) {
      assert.equal(
        receipt.technicalVerdict,
        contract.expectedTechnicalVerdict,
        `${contract.id} technical verdict`,
      );
    }
    if (contract.expectedDataMigration) {
      assert.equal(
        receipt.dataMigration,
        contract.expectedDataMigration,
        `${contract.id} data-migration scope`,
      );
    }
    if (contract.requireNoFailedRuntimeRequests) {
      assert.deepEqual(
        receipt.failedRuntimeRequests,
        [],
        `${contract.id} runtime request failures`,
      );
    }
    if (contract.expectedFixtureMode) {
      assert.equal(
        receipt.fixtureMode,
        contract.expectedFixtureMode,
        `${contract.id} fixture mode`,
      );
    }
    for (const key of contract.requiredRecordIds ?? []) {
      assert.ok(receipt.recordIds?.[key], `${contract.id} receipt missing recordIds.${key}`);
    }
    if (contract.requireSeedLineage) {
      assert.ok(
        ['self-seeded', 'reuse-clean-seed'].includes(receipt.fixtureMode),
        `${contract.id} fixture mode`,
      );
      let seedReceipt = receipt;
      if (receipt.fixtureMode === 'reuse-clean-seed') {
        assert.ok(
          receipt.seedReceipt,
          `${contract.id} reused fixture must identify its seed receipt`,
        );
        assert.ok(fs.existsSync(receipt.seedReceipt), `${contract.id} seed receipt is missing`);
        seedReceipt = JSON.parse(fs.readFileSync(receipt.seedReceipt, 'utf8'));
      }
      assert.equal(
        seedReceipt.fixtureMode,
        'self-seeded',
        `${contract.id} seed must be self-seeded`,
      );
      assert.equal(seedReceipt.technicalVerdict, 'pass', `${contract.id} seed verdict`);
      for (const key of [
        'account',
        'leads',
        'opportunities',
        'activities',
        'quotes',
        'complaint',
      ]) {
        assert.ok(seedReceipt.recordIds?.[key], `${contract.id} seed receipt missing ${key}`);
      }
    }
    if (contract.expectedCoverage) {
      for (const [axis, expected] of Object.entries(contract.expectedCoverage)) {
        const coverage = receipt.coverage?.[axis];
        assert.ok(coverage, `${contract.id} missing coverage axis ${axis}`);
        assertSameMembers(
          coverage.expected,
          expected,
          `${contract.id} ${axis} declared denominator drift`,
        );
        assertSameMembers(
          coverage.completed,
          expected,
          `${contract.id} ${axis} incomplete runtime coverage`,
        );
      }
    }
    results.push({
      id: contract.id,
      verdict: 'pass',
      receipt: path.relative(evidenceRoot, file),
      runId: receipt.runId,
    });
  }
  return results;
}

export function runMutationProof(manifest) {
  const phases = [];
  assertManifestMatches(manifest);
  phases.push({ phase: 'green-before', result: 'pass' });

  const mutant = structuredClone(manifest);
  mutant.axes.semanticActions.splice(0, 1);
  let rejected = false;
  let rejection = '';
  try {
    assertManifestMatches(mutant);
  } catch (error) {
    rejected = true;
    rejection = error.message.split('\n')[0];
  }
  assert.ok(rejected, 'controlled manifest mutation did not turn the gate red');
  phases.push({ phase: 'red-controlled-mutation', result: 'pass', rejection });

  assertManifestMatches(manifest);
  phases.push({ phase: 'green-restored', result: 'pass' });
  return {
    schemaVersion: 2,
    verdict: 'pass',
    mutation: 'remove one of 26 semantic actions',
    phases,
  };
}

export function assertFullProductReady(manifest) {
  const incomplete = manifest.groups.flatMap((group) =>
    group.rows
      .filter((row) => row.verdict !== 'pass')
      .map((row) => `${group.id}/${row.id}:${row.verdict}`),
  );
  assert.equal(
    incomplete.length,
    0,
    `CRM full-product release gate is NOT MET; ${incomplete.length} rows are not pass. First rows: ${incomplete.slice(0, 10).join(', ')}`,
  );
}

function option(argv, name, fallback = null) {
  const index = argv.indexOf(name);
  return index >= 0 ? argv[index + 1] : fallback;
}

function main(argv) {
  const manifestPath = path.resolve(option(argv, '--manifest', DEFAULT_MANIFEST));
  const expected = buildReleaseManifest();
  if (argv.includes('--write')) {
    fs.writeFileSync(manifestPath, `${JSON.stringify(expected, null, 2)}\n`);
  }
  assert.ok(fs.existsSync(manifestPath), `coverage manifest not found: ${manifestPath}`);
  const actual = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
  assertManifestMatches(actual, expected);
  if (argv.includes('--require-full-product')) assertFullProductReady(actual);

  const evidenceRoot = option(argv, '--evidence-root');
  const runtimeContract = option(argv, '--runtime-contract');
  const runtimeEvidence = evidenceRoot
    ? verifyRuntimeEvidence(
        path.resolve(evidenceRoot),
        actual,
        runtimeContract ? [runtimeContract] : null,
      )
    : [];
  const mutation = argv.includes('--self-test-mutation') ? runMutationProof(actual) : null;
  const mutationEvidencePath = option(argv, '--mutation-evidence');
  if (mutationEvidencePath && mutation) {
    const absolute = path.resolve(mutationEvidencePath);
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    fs.writeFileSync(absolute, `${JSON.stringify(mutation, null, 2)}\n`);
  }
  const report = {
    verdict: 'pass',
    manifest: path.relative(REPO_ROOT, manifestPath),
    counts: Object.fromEntries(
      Object.entries(actual.axes).map(([axis, rows]) => [axis, rows.length]),
    ),
    productDenominator: actual.scope.productDenominator,
    productVerdicts: actual.scope.productVerdicts,
    explicitUntested: actual.untested.length,
    runtimeEvidence,
    mutation,
  };
  const gateEvidencePath = option(argv, '--gate-evidence');
  if (gateEvidencePath) {
    const absolute = path.resolve(gateEvidencePath);
    fs.mkdirSync(path.dirname(absolute), { recursive: true });
    fs.writeFileSync(absolute, `${JSON.stringify(report, null, 2)}\n`);
  }
  console.log(JSON.stringify(report, null, 2));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    main(process.argv.slice(2));
  } catch (error) {
    console.error(`CRM release coverage gate failed: ${error.message}`);
    process.exit(1);
  }
}
