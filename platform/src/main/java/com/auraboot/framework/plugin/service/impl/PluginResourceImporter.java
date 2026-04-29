package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginResource;

/**
 * Interface for importing specific resource types.
 */
public interface PluginResourceImporter {

    // ==================== Existence Checks ====================

    boolean checkModelExists(Long tenantId, String code);
    boolean checkFieldExists(Long tenantId, String code);
    boolean checkCommandExists(Long tenantId, String code);
    boolean checkPermissionExists(Long tenantId, String code);
    boolean checkRoleExists(Long tenantId, String code);
    boolean checkMenuExists(Long tenantId, String code);
    boolean checkProcessExists(Long tenantId, String key);
    boolean checkPageExists(Long tenantId, String pageKey);
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

    PluginResource importMenu(MenuDefinitionDTO dto, String pluginPid, String importId,
                              Long tenantId, ImportRequest.ConflictStrategy conflictStrategy);

    PluginResource importProcess(ProcessDefinitionDTO dto, String pluginPid, String importId,
                                 Long tenantId, ImportRequest.ConflictStrategy conflictStrategy,
                                 Boolean autoDeploy);

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
}
