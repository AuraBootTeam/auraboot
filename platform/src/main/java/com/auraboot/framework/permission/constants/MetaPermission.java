package com.auraboot.framework.permission.constants;

/**
 * Meta Permission Constants
 *
 * Defines standard permission codes for Meta platform resources.
 * These constants replace the old MetaPermissions constants.
 *
 * Permission Code Format: {module}.{resource}.{action}
 *   module ∈ { meta, bpm, org, sys, data, audit, dashboard,
 *              automation, notification, aurabot, admin, acp, iot }
 *
 * Actions:
 * - read:   Read-only access (query, list, view)
 * - create: Create new resources
 * - update: Modify existing resources (formerly "manage")
 * - delete: Remove resources
 * - execute: Execute operations (commands, decisions, workflows)
 * - admin:  Administrative operations (cleanup, batch, system config)
 * - generate: Generate output (reports, PDFs)
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public final class MetaPermission {

    // ==================== MODEL permissions ====================

    /**
     * Model management permission (create, update, delete)
     */
    public static final String MODEL_MANAGE = "meta.model.update";

    /**
     * Model read permission (query, list, view)
     */
    public static final String MODEL_READ = "meta.model.read";

    // ==================== PAGE permissions ====================

    /**
     * Page management permission (create, update, delete, publish)
     */
    public static final String PAGE_MANAGE = "meta.page.update";

    /**
     * Page read permission (query, list, view)
     */
    public static final String PAGE_READ = "meta.page.read";

    /**
     * Legacy page schema read permission used by PageSchema APIs.
     *
     * <p>Keep this separate from {@link #PAGE_READ}: existing tenants and
     * plugin roles grant {@code page.page.read}, while {@code meta.page.read}
     * is the newer meta-module permission code.
     */
    public static final String PAGE_SCHEMA_READ = "page.page.read";

    /**
     * Page designer management permission
     */
    public static final String PAGE_DESIGNER_MANAGE = "meta.designer.update";

    /**
     * Page designer read permission
     */
    public static final String PAGE_DESIGNER_READ = "meta.designer.read";

    /**
     * Page designer admin permission
     */
    public static final String PAGE_DESIGNER_ADMIN = "meta.designer.admin";

    /**
     * Page publish management permission
     */
    public static final String PAGE_PUBLISH_MANAGE = "meta.publish.update";

    /**
     * Page publish read permission
     */
    public static final String PAGE_PUBLISH_READ = "meta.publish.read";

    /**
     * Page publish admin permission
     */
    public static final String PAGE_PUBLISH_ADMIN = "meta.publish.admin";

    // ==================== FIELD permissions ====================

    /**
     * Field management permission (create, update, delete)
     */
    public static final String FIELD_MANAGE = "meta.field.update";

    /**
     * Field read permission (query, list, view)
     */
    public static final String FIELD_READ = "meta.field.read";

    // ==================== DICT permissions ====================

    /**
     * Dictionary management permission (create, update, delete)
     */
    public static final String DICT_MANAGE = "meta.dict.update";

    /**
     * Dictionary read permission (query, list, view)
     */
    public static final String DICT_READ = "meta.dict.read";

    // ==================== QUERY permissions ====================

    /**
     * Named Query management permission (create, update, delete)
     */
    public static final String QUERY_MANAGE = "meta.query.update";

    /**
     * Named Query read permission (query, list, view, execute)
     */
    public static final String QUERY_READ = "meta.query.read";

    // ==================== COMMAND permissions ====================

    /**
     * Command management permission (create, update, delete, publish)
     */
    public static final String COMMAND_MANAGE = "meta.command.update";

    /**
     * Command read permission (query, list, view)
     */
    public static final String COMMAND_READ = "meta.command.read";

    /**
     * Command execute permission
     */
    public static final String COMMAND_EXECUTE = "meta.command.execute";

    // ==================== EVENT_STORE permissions ====================

    /**
     * Event Store read permission (view event streams)
     */
    public static final String EVENT_STORE_READ = "meta.event_store.read";

    /**
     * Event Store admin permission (replay, create snapshots)
     */
    public static final String EVENT_STORE_ADMIN = "meta.event_store.admin";

    // ==================== STATE_GRAPH permissions ====================

    /**
     * State Graph management permission (create, update, delete, publish)
     */
    public static final String STATE_GRAPH_MANAGE = "meta.state_graph.update";

    /**
     * State Graph read permission (query, list, view)
     */
    public static final String STATE_GRAPH_READ = "meta.state_graph.read";

    // ==================== DECISION permissions ====================

    /**
     * Decision management permission (create, update, delete, publish)
     */
    public static final String DECISION_MANAGE = "meta.decision.update";

    /**
     * Decision read permission (query, list, view)
     */
    public static final String DECISION_READ = "meta.decision.read";

    /**
     * Decision execute permission (submit evidence, adjudicate)
     */
    public static final String DECISION_EXECUTE = "meta.decision.execute";

    // ==================== INVARIANT permissions ====================

    /**
     * Invariant management permission (create, update, delete, publish)
     */
    public static final String INVARIANT_MANAGE = "meta.invariant.update";

    /**
     * Invariant read permission (query, list, view, monitoring)
     */
    public static final String INVARIANT_READ = "meta.invariant.read";

    // ==================== DATASOURCE permissions ====================

    /**
     * DataSource management permission (create, update, delete)
     */
    public static final String DATASOURCE_MANAGE = "data.datasource.update";

    /**
     * DataSource read permission (query, list, view)
     */
    public static final String DATASOURCE_READ = "data.datasource.read";

    // ==================== COMPONENT permissions ====================

    /**
     * Component management permission (create, update, delete)
     */
    public static final String COMPONENT_MANAGE = "meta.component.update";

    /**
     * Component read permission (query, list, view)
     */
    public static final String COMPONENT_READ = "meta.component.read";

    // ==================== RBAC permissions ====================

    /**
     * Role management permission (create, update, delete, assign permissions)
     */
    public static final String ROLE_MANAGE = "org.role.update";

    /**
     * Role read permission (query, list, view)
     */
    public static final String ROLE_READ = "org.role.read";

    /**
     * User-Role binding management permission
     */
    public static final String USER_ROLE_MANAGE = "org.user_role.update";

    /**
     * User-Role binding read permission
     */
    public static final String USER_ROLE_READ = "org.user_role.read";

    // ==================== PERMISSION permissions ====================

    /**
     * Permission management permission (create, update, delete, bind)
     */
    public static final String PERMISSION_MANAGE = "meta.permission.update";

    /**
     * Permission read permission (query, list, view)
     */
    public static final String PERMISSION_READ = "meta.permission.read";

    // ==================== MENU permissions ====================

    /**
     * Menu management permission (create, update, delete)
     */
    public static final String MENU_MANAGE = "meta.menu.update";

    /**
     * Menu read permission (query, list, view)
     */
    public static final String MENU_READ = "meta.menu.read";

    // ==================== CATEGORY permissions ====================

    /**
     * Category management permission (create, update, delete)
     */
    public static final String CATEGORY_MANAGE = "meta.category.update";

    /**
     * Category read permission (query, list, view)
     */
    public static final String CATEGORY_READ = "meta.category.read";

    // ==================== TENANT permissions ====================

    /**
     * Tenant management permission (create, update, delete)
     */
    public static final String TENANT_MANAGE = "org.tenant.update";

    /**
     * Tenant read permission (query, list, view)
     */
    public static final String TENANT_READ = "org.tenant.read";

    // ==================== VIEW permissions ====================

    /**
     * Saved view management permission (create, update, delete)
     */
    public static final String VIEW_MANAGE = "dashboard.saved_view.update";

    /**
     * Saved view TEAM-scope management permission.
     */
    public static final String VIEW_TEAM_MANAGE = "dashboard.saved_view.team.update";

    /**
     * Saved view read permission (query, list, view)
     */
    public static final String VIEW_READ = "dashboard.saved_view.read";

    // ==================== AUTOMATION permissions ====================

    /**
     * Automation management permission (create, update, delete, enable/disable)
     */
    public static final String AUTOMATION_MANAGE = "automation.update";

    /**
     * Automation read permission (query, list, view logs)
     */
    public static final String AUTOMATION_READ = "automation.read";

    /**
     * Automation admin permission (manual trigger, cleanup logs)
     */
    public static final String AUTOMATION_ADMIN = "automation.admin";

    // ==================== REPORT permissions ====================

    /**
     * Report template management permission (create, update, delete, publish)
     */
    public static final String REPORT_MANAGE = "meta.template.update";

    /**
     * Report template read permission (query, list, view)
     */
    public static final String REPORT_READ = "meta.template.read";

    /**
     * Report generate permission (generate reports)
     */
    public static final String REPORT_GENERATE = "meta.report.generate";

    // ==================== PRINT permissions ====================

    /**
     * Print/PDF generation permission (generate PDFs from HTML templates for business documents)
     */
    public static final String PRINT_GENERATE = "meta.print.generate";

    // ==================== QR (AuraQR live code) permissions ====================

    /**
     * Manage AuraQR live codes: create / list / bind-to-model / publish / set-status.
     * Gates the enterprise {@code /api/admin/qr} endpoints.
     */
    public static final String QR_MANAGE = "meta.qr.manage";

    // ==================== DASHBOARD permissions ====================

    /**
     * Dashboard management permission (create, update, delete, publish)
     */
    public static final String DASHBOARD_MANAGE = "dashboard.update";

    /**
     * Dashboard TEAM-scope management permission.
     */
    public static final String DASHBOARD_TEAM_MANAGE = "dashboard.team.update";

    /**
     * Dashboard read permission (query, list, view)
     */
    public static final String DASHBOARD_READ = "dashboard.read";

    // ==================== WORKFLOW permissions ====================

    /**
     * Workflow management permission (create, update, delete, deploy process definitions)
     */
    public static final String WORKFLOW_MANAGE = "bpm.process.update";

    /**
     * Workflow read permission (query, list, view process definitions and instances)
     */
    public static final String WORKFLOW_READ = "bpm.process.read";

    /**
     * Workflow execute permission (start process instances, complete tasks)
     */
    public static final String WORKFLOW_EXECUTE = "bpm.process.execute";

    /**
     * Workflow admin permission (suspend, resume, terminate instances, monitor)
     */
    public static final String WORKFLOW_ADMIN = "bpm.process.admin";

    // ==================== BPM permissions ====================

    /**
     * BPM form management permission
     */
    public static final String BPM_FORM_MANAGE = "bpm.form.update";

    /**
     * BPM monitor read permission (view status, audit, SLA)
     */
    public static final String BPM_MONITOR_READ = "bpm.monitor.read";

    /**
     * BPM monitor manage permission (suspend, terminate, resume, jump)
     */
    public static final String BPM_MONITOR_MANAGE = "bpm.monitor.update";

    /**
     * BPM signature management permission
     */
    public static final String BPM_SIGNATURE_MANAGE = "bpm.signature.update";

    /**
     * BPM definition export/import management permission
     */
    public static final String BPM_DEFINITION_MANAGE = "bpm.definition.update";

    /**
     * BPM rule management permission
     */
    public static final String BPM_RULE_MANAGE = "bpm.rule.update";

    /**
     * BPM node hook management permission
     */
    public static final String BPM_HOOK_MANAGE = "bpm.hook.update";

    /**
     * BPM SLA configuration management permission
     */
    public static final String BPM_SLA_MANAGE = "bpm.sla.update";

    /**
     * BPM domain configuration management permission
     */
    public static final String BPM_CONFIG_MANAGE = "bpm.config.update";

    /**
     * BPM task read permission (workbench list/get)
     */
    public static final String BPM_TASK_READ = "bpm.task.read";

    /**
     * BPM task manage permission (workbench operations)
     */
    public static final String BPM_TASK_MANAGE = "bpm.task.update";

    /**
     * BPM report read permission
     */
    public static final String BPM_REPORT_READ = "bpm.report.read";

    // ==================== PLUGIN permissions ====================

    /**
     * Plugin read permission (list, view details)
     */
    public static final String PLUGIN_READ = "sys.plugin.read";

    /**
     * Plugin management permission (enable, disable, install, uninstall, hotload)
     */
    public static final String PLUGIN_MANAGE = "sys.plugin.update";

    // ==================== META CONFIG permissions ====================

    /**
     * Change log read permission
     */
    public static final String META_CHANGELOG_READ = "meta.changelog.read";

    /**
     * Audit trail read permission
     */
    public static final String META_AUDIT_TRAIL_READ = "audit.trail.read";

    /**
     * Audit trail verify (admin) permission
     */
    public static final String META_AUDIT_TRAIL_ADMIN = "audit.trail.admin";

    /**
     * Field change audit read permission
     */
    public static final String META_FIELD_AUDIT_READ = "audit.field_audit.read";

    /**
     * Field change audit config management permission
     */
    public static final String META_FIELD_AUDIT_MANAGE = "audit.field_audit.update";

    /**
     * Filter preset management permission
     */
    public static final String META_FILTER_MANAGE = "meta.filter.update";

    /**
     * ChatBI v2 conversational analytics — ask questions, manage conversations,
     * disambiguate. PRD 17.
     */
    public static final String META_CHATBI_USE = "meta.chatbi.use";

    // ==================== SYS permissions ====================

    /**
     * File upload permission
     */
    public static final String SYS_FILE_UPLOAD = "sys.file.upload";

    /**
     * Scheduled task management permission
     */
    public static final String SYS_SCHEDULER_MANAGE = "sys.scheduler.update";

    /**
     * Webhook management permission
     */
    public static final String SYS_WEBHOOK_MANAGE = "sys.webhook.update";

    /**
     * API connector management permission
     */
    public static final String SYS_CONNECTOR_MANAGE = "sys.connector.update";

    // ==================== SOD permissions ====================

    /**
     * SoD rule management permission (create, update, delete)
     */
    public static final String META_SOD_MANAGE = "data.sod.update";

    /**
     * SoD rule read permission (list rules, view violations)
     */
    public static final String META_SOD_READ = "data.sod.read";

    // ==================== ASYNC_TASK permissions ====================

    /**
     * Async task read permission (list, view status and progress)
     */
    public static final String ASYNC_TASK_READ = "sys.async_task.read";

    /**
     * Async task manage permission (submit, cancel, delete)
     */
    public static final String ASYNC_TASK_MANAGE = "sys.async_task.update";

    // ==================== GIT permissions ====================

    /**
     * Git repository management permission
     */
    public static final String GIT_REPO_MANAGE = "sys.repo.update";

    /**
     * Git repository read permission
     */
    public static final String GIT_REPO_READ = "sys.repo.read";

    /**
     * Git release management permission
     */
    public static final String GIT_RELEASE_MANAGE = "sys.release.update";

    /**
     * Git release read permission
     */
    public static final String GIT_RELEASE_READ = "sys.release.read";

    // ==================== Field Masking & Data Domain permissions ====================

    /**
     * Field mask configuration management permission
     */
    public static final String META_FIELD_MASK_MANAGE = "meta.field_mask.update";

    /**
     * Data domain management permission
     */
    public static final String META_DATA_DOMAIN_MANAGE = "data.data_domain.update";

    // ==================== EDI permissions ====================

    /**
     * EDI partner and message type management permission
     */
    public static final String EDI_MANAGE = "data.edi.update";

    /**
     * EDI transaction read permission (view transactions, history)
     */
    public static final String EDI_READ = "data.edi.read";

    // ==================== OT Device permissions ====================

    /**
     * OT device management permission (register, update, delete)
     */
    public static final String OT_DEVICE_MANAGE = "data.ot_device.update";

    /**
     * OT device read permission (view status, data logs)
     */
    public static final String OT_DEVICE_READ = "data.ot_device.read";

    /**
     * OT device data ingestion permission (push data, heartbeat)
     */
    public static final String OT_DEVICE_DATA = "data.ot_device.data";

    // ==================== RECONCILIATION permissions ====================

    /**
     * Reconciliation management permission (create/update/delete profiles, start runs)
     */
    public static final String RECON_MANAGE = "data.reconciliation.update";

    /**
     * Reconciliation read permission (view profiles, runs, items, reports)
     */
    public static final String RECON_READ = "data.reconciliation.read";

    // ==================== CLOUD CONFIG permissions ====================

    /**
     * Cloud config management permission (create, update, delete, view)
     */
    public static final String CLOUD_CONFIG_MANAGE = "sys.cloud_config.update";

    // ==================== ACP (Agent Control Plane) permissions ====================

    /**
     * ACP agent approval permission (review and act on agent approval requests).
     */
    public static final String ACP_AGENT_APPROVAL = "acp.agent.approval";

    /**
     * ACP runtime operations: dispatch tasks to agents, scaffolding, schedule
     * reload/trigger, tool contract derivation, dry-run/sandbox execution,
     * collaboration (delegate/broadcast/pipeline), BPM bridging, skill sync.
     */
    public static final String ACP_RUNTIME_MANAGE = "acp.runtime.manage";

    /**
     * Admin views over agent runs and shadow runs ({@code /api/admin/agent-runs},
     * {@code /api/admin/shadow-runs}).
     */
    public static final String ACP_AGENT_RUN_ADMIN = "acp.agent_run.admin";

    /**
     * Agent memory governance: promotion review/retract/batch-approve and
     * tier admin (promote-now).
     */
    public static final String ACP_MEMORY_ADMIN = "acp.memory.admin";

    /**
     * User-soul-profile administration (cross-user views, forget).
     * User self-service profile edits are NOT gated by this code.
     */
    public static final String ACP_PROFILE_ADMIN = "acp.profile.admin";

    /**
     * Learning-loop draft governance (review / auto-rename / evaluate-promotion).
     */
    public static final String ACP_LEARNING_REVIEW = "acp.learning.review";

    // ==================== IoT permissions ====================

    /**
     * Time-series read permission for the platform-side
     * {@code TimeSeriesQueryController} (latest / range / aggregate /
     * batchQuery). Registered as a minimal IoT capability in the OSS
     * default bootstrap so OSS-only deploys can issue the time-series
     * REST contract; the enterprise {@code ent-iot-control} plugin
     * registers the full IoT permission catalogue (write, alarm, command,
     * ...) on top of this baseline.
     *
     * <p><b>Permission-code reconciliation note:</b> the enterprise plugin
     * registers the SAME literal {@code "iot.data_point.read"} so the two
     * sources of truth converge. {@code module=iot} is officially added to
     * the platform module enum in this drop (see MetaPermission javadoc).
     */
    public static final String IOT_DATA_POINT_READ = "iot.data_point.read";

    // ==================== Manufacturing permissions ====================

    /**
     * OEE (Overall Equipment Effectiveness) read permission for the platform-side
     * {@code OeeController}. The OEE calculation engine reads the PCBA manufacturing
     * dynamic tables ({@code mt_pe_*}) to compute availability / performance / quality
     * / OEE / TEEP per equipment, so the read contract is registered as a minimal
     * manufacturing capability in the OSS default bootstrap (module {@code meta}).
     */
    public static final String MANUFACTURING_OEE = "meta.manufacturing.oee";

    // ==================== DECISION RUNTIME permissions ====================
    // NOTE: These are for the Decision Runtime module (ab_drt_* tables, /api/decision).
    // They are DISTINCT from the meta.decision.* permissions above which belong to the
    // meta-adjudication concept (ab_decision_definition / ab_decision_record).

    /**
     * Decision Runtime definition read permission (list, view, query versions/logs).
     */
    public static final String DRT_DEFINITION_READ = "decision.definition.read";

    /**
     * Decision Runtime definition manage permission (create, update, create draft versions).
     */
    public static final String DRT_DEFINITION_MANAGE = "decision.definition.manage";

    /**
     * Decision Runtime definition publish permission (validate → publish a version).
     */
    public static final String DRT_DEFINITION_PUBLISH = "decision.definition.publish";

    /**
     * Decision Runtime version approval permission (M7 4-eyes governance: approve/reject a version
     * submitted for approval). Distinct from publish so authoring and approval can be separated.
     */
    public static final String DRT_DEFINITION_APPROVE = "decision.definition.approve";

    /**
     * Decision Runtime evaluate permission (call /evaluate and /test-run endpoints).
     */
    public static final String DRT_RUNTIME_EVALUATE = "decision.runtime.evaluate";

    // ==================== EVENT POLICY permissions ====================
    // NOTE: These are for the EventPolicy module (ab_drt_policy_* tables, /api/event-policy).
    // They are DISTINCT from the decision.definition.* permissions above which belong to the
    // Decision Runtime module (ab_drt_definition / ab_drt_version).

    /**
     * Event Policy definition read permission (list, view definitions and versions).
     */
    public static final String POLICY_DEFINITION_READ = "decision.policy.read";

    /**
     * Event Policy definition manage permission (create definitions and draft versions).
     */
    public static final String POLICY_DEFINITION_MANAGE = "decision.policy.manage";

    /**
     * Event Policy definition publish permission (validate → publish a version).
     */
    public static final String POLICY_DEFINITION_PUBLISH = "decision.policy.publish";

    /**
     * Event Policy run permission (call /run endpoint to evaluate a published policy).
     */
    public static final String POLICY_RUNTIME_RUN = "decision.policy.run";

    // ==================== BILLING permissions ====================

    /**
     * Billing resource catalog read permission (list all active catalog entries).
     * Used by {@code ResourceCatalogController} GET /api/billing/resource-catalog.
     */
    public static final String BILLING_CATALOG_READ = "billing.catalog.read";

    /**
     * Quota bucket balance read permission.
     * Used by {@code QuotaBucketController} GET /api/billing/quota/buckets.
     */
    public static final String BILLING_QUOTA_READ = "billing.quota.read";

    /**
     * Usage event read permission.
     * Used by {@code UsageEventController} GET /api/billing/usage/events.
     */
    public static final String BILLING_USAGE_READ = "billing.usage.read";

    /**
     * Billing plan catalog read permission (list plans, plan versions, price components, quota templates).
     * Used by {@code BillingPlanConsoleController} GET /api/billing/plans.
     */
    public static final String BILLING_PLAN_READ = "billing.plan.read";

    /**
     * Subscription read permission (list and view subscriptions and subscription items).
     * Used by {@code BillingSubscriptionConsoleController} GET /api/billing/subscriptions.
     */
    public static final String BILLING_SUBSCRIPTION_READ = "billing.subscription.read";

    /**
     * Invoice read permission (list and view invoices and invoice line items).
     * Used by {@code BillingInvoiceConsoleController} GET /api/billing/invoices.
     */
    public static final String BILLING_INVOICE_READ = "billing.invoice.read";

    /**
     * License key read permission (list and view license keys and heartbeats).
     * Used by {@code BillingLicenseConsoleController} GET /api/billing/licenses.
     */
    public static final String BILLING_LICENSE_READ = "billing.license.read";

    // ==================== AI / RAG Knowledge Base permissions ====================

    /**
     * Knowledge base read permission (list KBs, view documents and chunks).
     */
    public static final String AI_KNOWLEDGE_READ = "ai.knowledge.read";

    /**
     * Knowledge base management permission (create/update/delete KBs, upload and
     * delete documents, reindex, import internal docs, generate docs).
     */
    public static final String AI_KNOWLEDGE_MANAGE = "ai.knowledge.manage";

    /**
     * Knowledge retrieval permission (run retrieval queries / playground).
     */
    public static final String AI_KNOWLEDGE_RETRIEVE = "ai.knowledge.retrieve";

    // ==================== Private Constructor ====================

    /**
     * Private constructor to prevent instantiation
     */
    private MetaPermission() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
