package com.auraboot.framework.permission.constants;

/**
 * Meta Permission Constants
 *
 * Defines standard permission codes for Meta platform resources.
 * These constants replace the old MetaPermissions constants.
 *
 * Permission Code Format: {resource_type}.{resource_code}.{action}[.{scope}]
 *
 * Actions:
 * - manage: Full CRUD access (create, update, delete)
 * - read: Read-only access (query, list, view)
 * - admin: Administrative operations (cleanup, batch, system config)
 *
 * @author AuraBoot Team
 * @since 2.2.0
 */
public final class MetaPermission {

    // ==================== MODEL permissions ====================

    /**
     * Model management permission (create, update, delete)
     */
    public static final String MODEL_MANAGE = "model.model.manage";

    /**
     * Model read permission (query, list, view)
     */
    public static final String MODEL_READ = "model.model.read";

    // ==================== PAGE permissions ====================

    /**
     * Page management permission (create, update, delete, publish)
     */
    public static final String PAGE_MANAGE = "page.page.manage";

    /**
     * Page read permission (query, list, view)
     */
    public static final String PAGE_READ = "page.page.read";

    /**
     * Page designer management permission
     */
    public static final String PAGE_DESIGNER_MANAGE = "page.designer.manage";

    /**
     * Page designer read permission
     */
    public static final String PAGE_DESIGNER_READ = "page.designer.read";

    /**
     * Page designer admin permission
     */
    public static final String PAGE_DESIGNER_ADMIN = "page.designer.admin";

    /**
     * Page publish management permission
     */
    public static final String PAGE_PUBLISH_MANAGE = "page.publish.manage";

    /**
     * Page publish read permission
     */
    public static final String PAGE_PUBLISH_READ = "page.publish.read";

    /**
     * Page publish admin permission
     */
    public static final String PAGE_PUBLISH_ADMIN = "page.publish.admin";

    // ==================== FIELD permissions ====================

    /**
     * Field management permission (create, update, delete)
     */
    public static final String FIELD_MANAGE = "field.field.manage";

    /**
     * Field read permission (query, list, view)
     */
    public static final String FIELD_READ = "field.field.read";

    // ==================== DICT permissions ====================

    /**
     * Dictionary management permission (create, update, delete)
     */
    public static final String DICT_MANAGE = "dict.dict.manage";

    /**
     * Dictionary read permission (query, list, view)
     */
    public static final String DICT_READ = "dict.dict.read";

    // ==================== QUERY permissions ====================

    /**
     * Named Query management permission (create, update, delete)
     */
    public static final String QUERY_MANAGE = "query.query.manage";

    /**
     * Named Query read permission (query, list, view, execute)
     */
    public static final String QUERY_READ = "query.query.read";

    // ==================== COMMAND permissions ====================

    /**
     * Command management permission (create, update, delete, publish)
     */
    public static final String COMMAND_MANAGE = "command.command.manage";

    /**
     * Command read permission (query, list, view)
     */
    public static final String COMMAND_READ = "command.command.read";

    /**
     * Command execute permission
     */
    public static final String COMMAND_EXECUTE = "command.command.execute";

    // ==================== EVENT_STORE permissions ====================

    /**
     * Event Store read permission (view event streams)
     */
    public static final String EVENT_STORE_READ = "event_store.event_store.read";

    /**
     * Event Store admin permission (replay, create snapshots)
     */
    public static final String EVENT_STORE_ADMIN = "event_store.event_store.admin";

    // ==================== STATE_GRAPH permissions ====================

    /**
     * State Graph management permission (create, update, delete, publish)
     */
    public static final String STATE_GRAPH_MANAGE = "state_graph.state_graph.manage";

    /**
     * State Graph read permission (query, list, view)
     */
    public static final String STATE_GRAPH_READ = "state_graph.state_graph.read";

    // ==================== DECISION permissions ====================

    /**
     * Decision management permission (create, update, delete, publish)
     */
    public static final String DECISION_MANAGE = "decision.decision.manage";

    /**
     * Decision read permission (query, list, view)
     */
    public static final String DECISION_READ = "decision.decision.read";

    /**
     * Decision execute permission (submit evidence, adjudicate)
     */
    public static final String DECISION_EXECUTE = "decision.decision.execute";

    // ==================== INVARIANT permissions ====================

    /**
     * Invariant management permission (create, update, delete, publish)
     */
    public static final String INVARIANT_MANAGE = "invariant.invariant.manage";

    /**
     * Invariant read permission (query, list, view, monitoring)
     */
    public static final String INVARIANT_READ = "invariant.invariant.read";

    // ==================== DATASOURCE permissions ====================

    /**
     * DataSource management permission (create, update, delete)
     */
    public static final String DATASOURCE_MANAGE = "datasource.datasource.manage";

    /**
     * DataSource read permission (query, list, view)
     */
    public static final String DATASOURCE_READ = "datasource.datasource.read";

    // ==================== COMPONENT permissions ====================

    /**
     * Component management permission (create, update, delete)
     */
    public static final String COMPONENT_MANAGE = "component.component.manage";

    /**
     * Component read permission (query, list, view)
     */
    public static final String COMPONENT_READ = "component.component.read";

    // ==================== RBAC permissions ====================

    /**
     * Role management permission (create, update, delete, assign permissions)
     */
    public static final String ROLE_MANAGE = "rbac.role.manage";

    /**
     * Role read permission (query, list, view)
     */
    public static final String ROLE_READ = "rbac.role.read";

    /**
     * User-Role binding management permission
     */
    public static final String USER_ROLE_MANAGE = "rbac.user_role.manage";

    /**
     * User-Role binding read permission
     */
    public static final String USER_ROLE_READ = "rbac.user_role.read";

    /**
     * Permission calculation read permission
     */
    public static final String Permission_READ = "rbac.permission.read";

    // ==================== PERMISSION permissions ====================

    /**
     * Permission management permission (create, update, delete, bind)
     */
    public static final String PERMISSION_MANAGE = "permission.permission.manage";

    /**
     * Permission read permission (query, list, view)
     */
    public static final String PERMISSION_READ = "permission.permission.read";

    // ==================== MENU permissions ====================

    /**
     * Menu management permission (create, update, delete)
     */
    public static final String MENU_MANAGE = "menu.menu.manage";

    /**
     * Menu read permission (query, list, view)
     */
    public static final String MENU_READ = "menu.menu.read";

    // ==================== CATEGORY permissions ====================

    /**
     * Category management permission (create, update, delete)
     */
    public static final String CATEGORY_MANAGE = "category.category.manage";

    /**
     * Category read permission (query, list, view)
     */
    public static final String CATEGORY_READ = "category.category.read";

    // ==================== TENANT permissions ====================

    /**
     * Tenant management permission (create, update, delete)
     */
    public static final String TENANT_MANAGE = "tenant.tenant.manage";

    /**
     * Tenant read permission (query, list, view)
     */
    public static final String TENANT_READ = "tenant.tenant.read";

    // ==================== VIEW permissions ====================

    /**
     * Saved view management permission (create, update, delete)
     */
    public static final String VIEW_MANAGE = "view.saved_view.manage";

    /**
     * Saved view TEAM-scope management permission.
     */
    public static final String VIEW_TEAM_MANAGE = "view.saved_view.team.manage";

    /**
     * Saved view read permission (query, list, view)
     */
    public static final String VIEW_READ = "view.saved_view.read";

    // ==================== AUTOMATION permissions ====================

    /**
     * Automation management permission (create, update, delete, enable/disable)
     */
    public static final String AUTOMATION_MANAGE = "automation.automation.manage";

    /**
     * Automation read permission (query, list, view logs)
     */
    public static final String AUTOMATION_READ = "automation.automation.read";

    /**
     * Automation admin permission (manual trigger, cleanup logs)
     */
    public static final String AUTOMATION_ADMIN = "automation.automation.admin";

    // ==================== REPORT permissions ====================

    /**
     * Report template management permission (create, update, delete, publish)
     */
    public static final String REPORT_MANAGE = "report.template.manage";

    /**
     * Report template read permission (query, list, view)
     */
    public static final String REPORT_READ = "report.template.read";

    /**
     * Report generate permission (generate reports)
     */
    public static final String REPORT_GENERATE = "report.report.generate";

    // ==================== PRINT permissions ====================

    /**
     * Print/PDF generation permission (generate PDFs from HTML templates for business documents)
     */
    public static final String PRINT_GENERATE = "print.print.generate";

    // ==================== DASHBOARD permissions ====================

    /**
     * Dashboard management permission (create, update, delete, publish)
     */
    public static final String DASHBOARD_MANAGE = "dashboard.dashboard.manage";

    /**
     * Dashboard TEAM-scope management permission.
     */
    public static final String DASHBOARD_TEAM_MANAGE = "dashboard.dashboard.team.manage";

    /**
     * Dashboard read permission (query, list, view)
     */
    public static final String DASHBOARD_READ = "dashboard.dashboard.read";

    // ==================== WORKFLOW permissions ====================

    /**
     * Workflow management permission (create, update, delete, deploy process definitions)
     */
    public static final String WORKFLOW_MANAGE = "workflow.process.manage";

    /**
     * Workflow read permission (query, list, view process definitions and instances)
     */
    public static final String WORKFLOW_READ = "workflow.process.read";

    /**
     * Workflow execute permission (start process instances, complete tasks)
     */
    public static final String WORKFLOW_EXECUTE = "workflow.process.execute";

    /**
     * Workflow admin permission (suspend, resume, terminate instances, monitor)
     */
    public static final String WORKFLOW_ADMIN = "workflow.process.admin";

    // ==================== BPM permissions ====================

    /**
     * BPM form management permission
     */
    public static final String BPM_FORM_MANAGE = "bpm.form.manage";

    /**
     * BPM monitor read permission (view status, audit, SLA)
     */
    public static final String BPM_MONITOR_READ = "bpm.monitor.read";

    /**
     * BPM monitor manage permission (suspend, terminate, resume, jump)
     */
    public static final String BPM_MONITOR_MANAGE = "bpm.monitor.manage";

    /**
     * BPM signature management permission
     */
    public static final String BPM_SIGNATURE_MANAGE = "bpm.signature.manage";

    /**
     * BPM definition export/import management permission
     */
    public static final String BPM_DEFINITION_MANAGE = "bpm.definition.manage";

    /**
     * BPM rule management permission
     */
    public static final String BPM_RULE_MANAGE = "bpm.rule.manage";

    /**
     * BPM node hook management permission
     */
    public static final String BPM_HOOK_MANAGE = "bpm.hook.manage";

    /**
     * BPM SLA configuration management permission
     */
    public static final String BPM_SLA_MANAGE = "bpm.sla.manage";

    /**
     * BPM domain configuration management permission
     */
    public static final String BPM_CONFIG_MANAGE = "bpm.config.manage";

    /**
     * BPM task read permission (workbench list/get)
     */
    public static final String BPM_TASK_READ = "bpm.task.read";

    /**
     * BPM task manage permission (workbench operations)
     */
    public static final String BPM_TASK_MANAGE = "bpm.task.manage";

    /**
     * BPM report read permission
     */
    public static final String BPM_REPORT_READ = "bpm.report.read";

    // ==================== PLUGIN permissions ====================

    /**
     * Plugin read permission (list, view details)
     */
    public static final String PLUGIN_READ = "plugin.plugin.read";

    /**
     * Plugin management permission (enable, disable, install, uninstall, hotload)
     */
    public static final String PLUGIN_MANAGE = "plugin.plugin.manage";

    // ==================== META CONFIG permissions ====================

    /**
     * Data permission policy management permission
     */
    public static final String META_PERMISSION_MANAGE = "meta.permission.manage";

    /**
     * ViewModel read permission
     */
    public static final String META_MODEL_READ = "meta.model.read";

    /**
     * ViewModel/model field binding management permission
     */
    public static final String META_MODEL_MANAGE = "meta.model.manage";

    /**
     * Change log read permission
     */
    public static final String META_CHANGELOG_READ = "meta.changelog.read";

    /**
     * Audit trail read permission
     */
    public static final String META_AUDIT_TRAIL_READ = "meta.audit_trail.read";

    /**
     * Audit trail verify (admin) permission
     */
    public static final String META_AUDIT_TRAIL_ADMIN = "meta.audit_trail.admin";

    /**
     * Field change audit read permission
     */
    public static final String META_FIELD_AUDIT_READ = "meta.field_audit.read";

    /**
     * Field change audit config management permission
     */
    public static final String META_FIELD_AUDIT_MANAGE = "meta.field_audit.manage";

    /**
     * Filter preset management permission
     */
    public static final String META_FILTER_MANAGE = "meta.filter.manage";

    // ==================== SYS permissions ====================

    /**
     * File upload permission
     */
    public static final String SYS_FILE_UPLOAD = "sys.file.upload";

    /**
     * Scheduled task management permission
     */
    public static final String SYS_SCHEDULER_MANAGE = "sys.scheduler.manage";

    /**
     * Webhook management permission
     */
    public static final String SYS_WEBHOOK_MANAGE = "sys.webhook.manage";

    /**
     * API connector management permission
     */
    public static final String SYS_CONNECTOR_MANAGE = "sys.connector.manage";

    // ==================== SOD permissions ====================

    /**
     * SoD rule management permission (create, update, delete)
     */
    public static final String META_SOD_MANAGE = "meta.sod.manage";

    /**
     * SoD rule read permission (list rules, view violations)
     */
    public static final String META_SOD_READ = "meta.sod.read";

    // ==================== ASYNC_TASK permissions ====================

    /**
     * Async task read permission (list, view status and progress)
     */
    public static final String ASYNC_TASK_READ = "meta.async_task.read";

    /**
     * Async task manage permission (submit, cancel, delete)
     */
    public static final String ASYNC_TASK_MANAGE = "meta.async_task.manage";

    // ==================== GIT permissions ====================

    /**
     * Git repository management permission
     */
    public static final String GIT_REPO_MANAGE = "git.repo.manage";

    /**
     * Git repository read permission
     */
    public static final String GIT_REPO_READ = "git.repo.read";

    /**
     * Git release management permission
     */
    public static final String GIT_RELEASE_MANAGE = "git.release.manage";

    /**
     * Git release read permission
     */
    public static final String GIT_RELEASE_READ = "git.release.read";

    // ==================== Field Masking & Data Domain permissions ====================

    /**
     * Field mask configuration management permission
     */
    public static final String META_FIELD_MASK_MANAGE = "meta.field_mask.manage";

    /**
     * Data domain management permission
     */
    public static final String META_DATA_DOMAIN_MANAGE = "meta.data_domain.manage";

    // ==================== EDI permissions ====================

    /**
     * EDI partner and message type management permission
     */
    public static final String EDI_MANAGE = "edi.edi.manage";

    /**
     * EDI transaction read permission (view transactions, history)
     */
    public static final String EDI_READ = "edi.edi.read";

    // ==================== OT Device permissions ====================

    /**
     * OT device management permission (register, update, delete)
     */
    public static final String OT_DEVICE_MANAGE = "ot.device.manage";

    /**
     * OT device read permission (view status, data logs)
     */
    public static final String OT_DEVICE_READ = "ot.device.read";

    /**
     * OT device data ingestion permission (push data, heartbeat)
     */
    public static final String OT_DEVICE_DATA = "ot.device.data";

    // ==================== RECONCILIATION permissions ====================

    /**
     * Reconciliation management permission (create/update/delete profiles, start runs)
     */
    public static final String RECON_MANAGE = "recon.reconciliation.manage";

    /**
     * Reconciliation read permission (view profiles, runs, items, reports)
     */
    public static final String RECON_READ = "recon.reconciliation.read";

    // ==================== CLOUD CONFIG permissions ====================

    /**
     * Cloud config management permission (create, update, delete, view)
     */
    public static final String CLOUD_CONFIG_MANAGE = "cloud_config_manage";

    // ==================== Private Constructor ====================

    /**
     * Private constructor to prevent instantiation
     */
    private MetaPermission() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
