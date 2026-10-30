package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginRecordMapper;

/**
 * Interface for importing specific resource types.
 */
public interface PluginResourceImporter {

    /** Defer published-model DDL until every field definition in this batch is current. */
    default void beginFieldImportBatch() { }

    /** Discard deferred work on failure; the enclosing import transaction owns rollback. */
    default void endFieldImportBatch(boolean completed) { }

    // ==================== Existence Checks ====================

    boolean checkModelExists(Long tenantId, String code);
    boolean checkFieldExists(Long tenantId, String code);
    boolean checkCommandExists(Long tenantId, String code);
    boolean checkPermissionExists(Long tenantId, String code);
    boolean checkRoleExists(Long tenantId, String code);
    boolean checkMenuExists(Long tenantId, String code);
    boolean checkPageExists(Long tenantId, String pageKey);
    boolean checkDashboardExists(Long tenantId, String code);
    boolean checkDictExists(Long tenantId, String code);
    boolean checkNamedQueryExists(Long tenantId, String code);
    boolean checkAgentDefinitionExists(Long tenantId, String agentCode);

    // ==================== Import Operations ====================

    PluginResource importModel(ModelDefinitionDTO dto, String pluginPid, String importId,
                               Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                               Boolean autoPublish);

    PluginResource importField(FieldDefinitionDTO dto, String pluginPid, String importId,
                               Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                               Boolean autoPublish);

    PluginResource importModelFieldBinding(ModelFieldBindingDTO dto, String pluginPid, String importId,
                                           Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importCommand(CommandDefinitionDTO dto, String pluginPid, String importId,
                                 Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                 Boolean autoPublish);

    PluginResource importBindingRule(BindingRuleDTO dto, String pluginPid, String importId,
                                     Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importPermission(PermissionDefinitionDTO dto, String pluginPid, String importId,
                                    Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importRole(RoleDefinitionDTO dto, String pluginPid, String importId,
                              Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    /**
     * Re-resolve a role's declared permission codes and bind any that resolve now but
     * did not at the ROLE import stage (generated model actions are created after that
     * stage). Idempotent: existing bindings are left untouched.
     *
     * @return true if at least one new binding was created
     */
    boolean reconcileRolePermissions(RoleDefinitionDTO dto, Long tenantId);

    /**
     * Reconcile declared role permissions for an installed plugin directory: validate
     * the directory and installed version, then strictly check every declared role and
     * permission resolves and is bound for the current tenant.
     *
     * @return number of role declarations checked
     */
    int reconcileDirectoryRolePermissions(String directoryPath,
                                          PluginDirectoryLoader directoryLoader,
                                          PluginRecordMapper pluginRecordMapper);

    PluginResource importMenu(MenuDefinitionDTO dto, String pluginPid, String importId,
                              Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importPage(PageSchemaDTO dto, String pluginPid, String importId,
                              Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                              Boolean autoPublish);

    PluginResource importDict(DictDefinitionDTO dto, String pluginPid, String importId,
                              Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importNamedQuery(NamedQueryDefinitionDTO dto, String pluginPid, String importId,
                                    Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                         Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    /**
     * Import a dashboard definition from {@code config/dashboards/*.json}.
     * This is the first-class contract (Plan #8) — no conversion needed.
     */
    PluginResource importDashboard(DashboardDefinitionDTO dto, String pluginPid, String importId,
                                   Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    // ==================== Rollback Operations ====================

    void rollbackResource(PluginResource resource);

    void restoreResource(PluginResource resource);

    // ==================== Stale Menu Reconciliation ====================

    /**
     * Soft-deletes menu rows this plugin family previously imported (tracked in
     * ab_plugin_resource for the same plugin_id) whose codes are absent from
     * {@code configuredMenuCodes}. Renamed or slimmed menu declarations must not
     * leave dead sidebar entries behind. Authoring-managed rows are never touched.
     *
     * @return number of pruned menu rows
     */
    int pruneStaleMenus(String pluginPid, String importId, Long tenantId,
                        java.util.Collection<String> configuredMenuCodes);

    /**
     * Uninstall counterpart of {@link #pruneStaleMenus}: soft-deletes every menu row
     * this plugin family imported, so a plugin leaving the composition cannot leave
     * sidebar entries pointing at pages that no longer resolve.
     *
     * @return number of deactivated menu rows
     */
    int deactivateAllPluginMenus(String pluginPid, Long tenantId);
}
