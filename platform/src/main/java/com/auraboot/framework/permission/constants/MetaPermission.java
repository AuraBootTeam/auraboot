package com.auraboot.framework.permission.constants;

/**
 * Meta Permission Constants
 *
 * Defines standard permission codes for Meta platform resources.
 * These constants replace the old MetaPermissions constants.
 *
 * Permission Code Format: system.{resource_code}.{action}
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
    public static final String MODEL_MANAGE = "system.model.update";

    /**
     * Model read permission (query, list, view)
     */
    public static final String MODEL_READ = "system.model.read";

    // ==================== PAGE permissions ====================

    /**
     * Page management permission (create, update, delete, publish)
     */
    public static final String PAGE_MANAGE = "system.page.update";

    /**
     * Page read permission (query, list, view)
     */
    public static final String PAGE_READ = "system.page.read";

    /**
     * Page designer management permission
     */
    public static final String PAGE_DESIGNER_MANAGE = "system.designer.update";

    /**
     * Page designer read permission
     */
    public static final String PAGE_DESIGNER_READ = "system.designer.read";

    /**
     * Page designer admin permission
     */
    public static final String PAGE_DESIGNER_ADMIN = "system.designer.admin";

    /**
     * Page publish management permission
     */
    public static final String PAGE_PUBLISH_MANAGE = "system.publish.update";

    /**
     * Page publish read permission
     */
    public static final String PAGE_PUBLISH_READ = "system.publish.read";

    /**
     * Page publish admin permission
     */
    public static final String PAGE_PUBLISH_ADMIN = "system.publish.admin";

    // ==================== FIELD permissions ====================

    /**
     * Field management permission (create, update, delete)
     */
    public static final String FIELD_MANAGE = "system.field.update";

    /**
     * Field read permission (query, list, view)
     */
    public static final String FIELD_READ = "system.field.read";

    // ==================== DICT permissions ====================

    /**
     * Dictionary management permission (create, update, delete)
     */
    public static final String DICT_MANAGE = "system.dict.update";

    /**
     * Dictionary read permission (query, list, view)
     */
    public static final String DICT_READ = "system.dict.read";

    // ==================== QUERY permissions ====================

    /**
     * Named Query management permission (create, update, delete)
     */
    public static final String QUERY_MANAGE = "system.query.update";

    /**
     * Named Query read permission (query, list, view, execute)
     */
    public static final String QUERY_READ = "system.query.read";

    // ==================== COMMAND permissions ====================

    /**
     * Command management permission (create, update, delete, publish)
     */
    public static final String COMMAND_MANAGE = "system.command.update";

    /**
     * Command read permission (query, list, view)
     */
    public static final String COMMAND_READ = "system.command.read";

    /**
     * Command execute permission
     */
    public static final String COMMAND_EXECUTE = "system.command.execute";

    // ==================== EVENT_STORE permissions ====================

    /**
     * Event Store read permission (view event streams)
     */
    public static final String EVENT_STORE_READ = "system.event_store.read";

    /**
     * Event Store admin permission (replay, create snapshots)
     */
    public static final String EVENT_STORE_ADMIN = "system.event_store.admin";

    // ==================== STATE_GRAPH permissions ====================

    /**
     * State Graph management permission (create, update, delete, publish)
     */
    public static final String STATE_GRAPH_MANAGE = "system.state_graph.update";

    /**
     * State Graph read permission (query, list, view)
     */
    public static final String STATE_GRAPH_READ = "system.state_graph.read";

    // ==================== DECISION permissions ====================

    /**
     * Decision management permission (create, update, delete, publish)
     */
    public static final String DECISION_MANAGE = "system.decision.update";

    /**
     * Decision read permission (query, list, view)
     */
    public static final String DECISION_READ = "system.decision.read";

    /**
     * Decision execute permission (submit evidence, adjudicate)
     */
    public static final String DECISION_EXECUTE = "system.decision.execute";

    // ==================== INVARIANT permissions ====================

    /**
     * Invariant management permission (create, update, delete, publish)
     */
    public static final String INVARIANT_MANAGE = "system.invariant.update";

    /**
     * Invariant read permission (query, list, view, monitoring)
     */
    public static final String INVARIANT_READ = "system.invariant.read";

    // ==================== DATASOURCE permissions ====================

    /**
     * DataSource management permission (create, update, delete)
     */
    public static final String DATASOURCE_MANAGE = "system.datasource.update";

    /**
     * DataSource read permission (query, list, view)
     */
    public static final String DATASOURCE_READ = "system.datasource.read";

    // ==================== COMPONENT permissions ====================

    /**
     * Component management permission (create, update, delete)
     */
    public static final String COMPONENT_MANAGE = "system.component.update";

    /**
     * Component read permission (query, list, view)
     */
    public static final String COMPONENT_READ = "system.component.read";

    // ==================== RBAC permissions ====================

    /**
     * Role management permission (create, update, delete, assign permissions)
     */
    public static final String ROLE_MANAGE = "system.role.update";

    /**
     * Role read permission (query, list, view)
     */
    public static final String ROLE_READ = "system.role.read";

    /**
     * User-Role binding management permission
     */
    public static final String USER_ROLE_MANAGE = "system.user_role.update";

    /**
     * User-Role binding read permission
     */
    public static final String USER_ROLE_READ = "system.user_role.read";

    /**
     * Permission calculation read permission
     */
    public static final String Permission_READ = "system.permission.read";

    // ==================== PERMISSION permissions ====================

    /**
     * Permission management permission (create, update, delete, bind)
     */
    public static final String PERMISSION_MANAGE = "system.permission.update";

    /**
     * Permission read permission (query, list, view)
     */
    public static final String PERMISSION_READ = "system.permission.read";

    // ==================== MENU permissions ====================

    /**
     * Menu management permission (create, update, delete)
     */
    public static final String MENU_MANAGE = "system.menu.update";

    /**
     * Menu read permission (query, list, view)
     */
    public static final String MENU_READ = "system.menu.read";

    // ==================== CATEGORY permissions ====================

    /**
     * Category management permission (create, update, delete)
     */
    public static final String CATEGORY_MANAGE = "system.category.update";

    /**
     * Category read permission (query, list, view)
     */
    public static final String CATEGORY_READ = "system.category.read";

    // ==================== TENANT permissions ====================

    /**
     * Tenant management permission (create, update, delete)
     */
    public static final String TENANT_MANAGE = "system.tenant.update";

    /**
     * Tenant read permission (query, list, view)
     */
    public static final String TENANT_READ = "system.tenant.read";

    // ==================== VIEW permissions ====================

    /**
     * Saved view management permission (create, update, delete)
     */
    public static final String VIEW_MANAGE = "system.saved_view.update";

    /**
     * Saved view TEAM-scope management permission.
     */
    public static final String VIEW_TEAM_MANAGE = "system.saved_view.team.update";

    /**
     * Saved view read permission (query, list, view)
     */
    public static final String VIEW_READ = "system.saved_view.read";

    // ==================== AUTOMATION permissions ====================

    /**
     * Automation management permission (create, update, delete, enable/disable)
     */
    public static final String AUTOMATION_MANAGE = "system.automation.update";

    /**
     * Automation read permission (query, list, view logs)
     */
    public static final String AUTOMATION_READ = "system.automation.read";

    /**
     * Automation admin permission (manual trigger, cleanup logs)
     */
    public static final String AUTOMATION_ADMIN = "system.automation.admin";

    // ==================== REPORT permissions ====================

    /**
     * Report template management permission (create, update, delete, publish)
     */
    public static final String REPORT_MANAGE = "system.template.update";

    /**
     * Report template read permission (query, list, view)
     */
    public static final String REPORT_READ = "system.template.read";

    /**
     * Report generate permission (generate reports)
     */
    public static final String REPORT_GENERATE = "system.report.generate";

    // ==================== PRINT permissions ====================

    /**
     * Print/PDF generation permission (generate PDFs from HTML templates for business documents)
     */
    public static final String PRINT_GENERATE = "system.print.generate";

    // ==================== DASHBOARD permissions ====================

    /**
     * Dashboard management permission (create, update, delete, publish)
     */
    public static final String DASHBOARD_MANAGE = "system.dashboard.update";

    /**
     * Dashboard TEAM-scope management permission.
     */
    public static final String DASHBOARD_TEAM_MANAGE = "system.dashboard.team.update";

    /**
     * Dashboard read permission (query, list, view)
     */
    public static final String DASHBOARD_READ = "system.dashboard.read";

    // ==================== WORKFLOW permissions ====================

    /**
     * Workflow management permission (create, update, delete, deploy process definitions)
     */
    public static final String WORKFLOW_MANAGE = "system.process.update";

    /**
     * Workflow read permission (query, list, view process definitions and instances)
     */
    public static final String WORKFLOW_READ = "system.process.read";

    /**
     * Workflow execute permission (start process instances, complete tasks)
     */
    public static final String WORKFLOW_EXECUTE = "system.process.execute";

    /**
     * Workflow admin permission (suspend, resume, terminate instances, monitor)
     */
    public static final String WORKFLOW_ADMIN = "system.process.admin";

    // ==================== BPM permissions ====================

    /**
     * BPM form management permission
     */
    public static final String BPM_FORM_MANAGE = "system.bpm_form.update";

    /**
     * BPM monitor read permission (view status, audit, SLA)
     */
    public static final String BPM_MONITOR_READ = "system.bpm_monitor.read";

    /**
     * BPM monitor manage permission (suspend, terminate, resume, jump)
     */
    public static final String BPM_MONITOR_MANAGE = "system.bpm_monitor.update";

    /**
     * BPM signature management permission
     */
    public static final String BPM_SIGNATURE_MANAGE = "system.bpm_signature.update";

    /**
     * BPM definition export/import management permission
     */
    public static final String BPM_DEFINITION_MANAGE = "system.bpm_definition.update";

    /**
     * BPM rule management permission
     */
    public static final String BPM_RULE_MANAGE = "system.bpm_rule.update";

    /**
     * BPM node hook management permission
     */
    public static final String BPM_HOOK_MANAGE = "system.bpm_hook.update";

    /**
     * BPM SLA configuration management permission
     */
    public static final String BPM_SLA_MANAGE = "system.bpm_sla.update";

    /**
     * BPM domain configuration management permission
     */
    public static final String BPM_CONFIG_MANAGE = "system.bpm_config.update";

    /**
     * BPM task read permission (workbench list/get)
     */
    public static final String BPM_TASK_READ = "system.bpm_task.read";

    /**
     * BPM task manage permission (workbench operations)
     */
    public static final String BPM_TASK_MANAGE = "system.bpm_task.update";

    /**
     * BPM report read permission
     */
    public static final String BPM_REPORT_READ = "system.bpm_report.read";

    // ==================== PLUGIN permissions ====================

    /**
     * Plugin read permission (list, view details)
     */
    public static final String PLUGIN_READ = "system.plugin.read";

    /**
     * Plugin management permission (enable, disable, install, uninstall, hotload)
     */
    public static final String PLUGIN_MANAGE = "system.plugin.update";

    // ==================== META CONFIG permissions ====================

    /**
     * Data permission policy management permission
     */
    public static final String META_PERMISSION_MANAGE = "system.meta_permission.update";

    /**
     * ViewModel read permission
     */
    public static final String META_MODEL_READ = "system.meta_model.read";

    /**
     * ViewModel/model field binding management permission
     */
    public static final String META_MODEL_MANAGE = "system.meta_model.update";

    /**
     * Change log read permission
     */
    public static final String META_CHANGELOG_READ = "system.changelog.read";

    /**
     * Audit trail read permission
     */
    public static final String META_AUDIT_TRAIL_READ = "system.audit_trail.read";

    /**
     * Audit trail verify (admin) permission
     */
    public static final String META_AUDIT_TRAIL_ADMIN = "system.audit_trail.admin";

    /**
     * Field change audit read permission
     */
    public static final String META_FIELD_AUDIT_READ = "system.field_audit.read";

    /**
     * Field change audit config management permission
     */
    public static final String META_FIELD_AUDIT_MANAGE = "system.field_audit.update";

    /**
     * Filter preset management permission
     */
    public static final String META_FILTER_MANAGE = "system.filter.update";

    // ==================== SYS permissions ====================

    /**
     * File upload permission
     */
    public static final String SYS_FILE_UPLOAD = "system.file.upload";

    /**
     * Scheduled task management permission
     */
    public static final String SYS_SCHEDULER_MANAGE = "system.scheduler.update";

    /**
     * Webhook management permission
     */
    public static final String SYS_WEBHOOK_MANAGE = "system.webhook.update";

    /**
     * API connector management permission
     */
    public static final String SYS_CONNECTOR_MANAGE = "system.connector.update";

    // ==================== SOD permissions ====================

    /**
     * SoD rule management permission (create, update, delete)
     */
    public static final String META_SOD_MANAGE = "system.sod.update";

    /**
     * SoD rule read permission (list rules, view violations)
     */
    public static final String META_SOD_READ = "system.sod.read";

    // ==================== ASYNC_TASK permissions ====================

    /**
     * Async task read permission (list, view status and progress)
     */
    public static final String ASYNC_TASK_READ = "system.async_task.read";

    /**
     * Async task manage permission (submit, cancel, delete)
     */
    public static final String ASYNC_TASK_MANAGE = "system.async_task.update";

    // ==================== GIT permissions ====================

    /**
     * Git repository management permission
     */
    public static final String GIT_REPO_MANAGE = "system.repo.update";

    /**
     * Git repository read permission
     */
    public static final String GIT_REPO_READ = "system.repo.read";

    /**
     * Git release management permission
     */
    public static final String GIT_RELEASE_MANAGE = "system.release.update";

    /**
     * Git release read permission
     */
    public static final String GIT_RELEASE_READ = "system.release.read";

    // ==================== Field Masking & Data Domain permissions ====================

    /**
     * Field mask configuration management permission
     */
    public static final String META_FIELD_MASK_MANAGE = "system.field_mask.update";

    /**
     * Data domain management permission
     */
    public static final String META_DATA_DOMAIN_MANAGE = "system.data_domain.update";

    // ==================== EDI permissions ====================

    /**
     * EDI partner and message type management permission
     */
    public static final String EDI_MANAGE = "system.edi.update";

    /**
     * EDI transaction read permission (view transactions, history)
     */
    public static final String EDI_READ = "system.edi.read";

    // ==================== OT Device permissions ====================

    /**
     * OT device management permission (register, update, delete)
     */
    public static final String OT_DEVICE_MANAGE = "system.ot_device.update";

    /**
     * OT device read permission (view status, data logs)
     */
    public static final String OT_DEVICE_READ = "system.ot_device.read";

    /**
     * OT device data ingestion permission (push data, heartbeat)
     */
    public static final String OT_DEVICE_DATA = "system.ot_device.data";

    // ==================== RECONCILIATION permissions ====================

    /**
     * Reconciliation management permission (create/update/delete profiles, start runs)
     */
    public static final String RECON_MANAGE = "system.reconciliation.update";

    /**
     * Reconciliation read permission (view profiles, runs, items, reports)
     */
    public static final String RECON_READ = "system.reconciliation.read";

    // ==================== CLOUD CONFIG permissions ====================

    /**
     * Cloud config management permission (create, update, delete, view)
     */
    public static final String CLOUD_CONFIG_MANAGE = "system.cloud_config.update";

    // ==================== Private Constructor ====================

    /**
     * Private constructor to prevent instantiation
     */
    private MetaPermission() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
