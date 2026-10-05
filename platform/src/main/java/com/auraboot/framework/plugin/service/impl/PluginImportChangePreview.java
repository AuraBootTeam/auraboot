package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.notification.entity.NotificationTemplate;
import com.auraboot.framework.notification.mapper.NotificationTemplateMapper;
import com.auraboot.framework.view.entity.SavedView;
import com.auraboot.framework.view.mapper.SavedViewMapper;
import com.auraboot.framework.plugin.dto.imports.*;
import com.auraboot.framework.plugin.entity.PluginRecord;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.common.util.LogSanitizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.*;
import java.util.*;

/** Builds import change previews while preserving user-modified resource identity. */
@Slf4j
@RequiredArgsConstructor
final class PluginImportChangePreview {
    private final PluginResourceMapper pluginResourceMapper;

    private final PluginResourceImporter resourceImporter;

    private final SavedViewMapper savedViewMapper;

    private final NotificationTemplateMapper notificationTemplateMapper;

    private final MatchesPluginSavedViewOperation matchesPluginSavedViewOperation;

    @FunctionalInterface
    interface MatchesPluginSavedViewOperation { boolean execute(SavedViewDefinitionDTO dto, SavedView savedView); }

    private boolean matchesPluginSavedView(SavedViewDefinitionDTO dto, SavedView savedView) { return matchesPluginSavedViewOperation.execute(dto,savedView); }

    void generateChangePreview(PluginManifestExtended manifest, ImportPreviewResult result, PluginRecord existing){
        Long tenantId = MetaContext.getCurrentTenantId();

        // Preview models
        if (manifest.getModels() != null) {
            for (ModelDefinitionDTO model : manifest.getModels()) {
                ResourceAction action = resourceImporter.checkModelExists(tenantId, model.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.MODEL, enrichWithUserModified(tenantId, ResourceType.MODEL, model.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.MODEL)
                        .resourceCode(model.getCode())
                        .resourceName(model.getEffectiveDisplayName())
                        .action(action)
                        .build()));
            }
        }

        // Preview fields
        if (manifest.getFields() != null) {
            for (FieldDefinitionDTO field : manifest.getFields()) {
                ResourceAction action = resourceImporter.checkFieldExists(tenantId, field.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.FIELD, enrichWithUserModified(tenantId, ResourceType.FIELD, field.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.FIELD)
                        .resourceCode(field.getCode())
                        .resourceName(field.getEffectiveDisplayName())
                        .action(action)
                        .build()));
            }
        }

        // Preview commands
        if (manifest.getCommands() != null) {
            for (CommandDefinitionDTO command : manifest.getCommands()) {
                ResourceAction action = resourceImporter.checkCommandExists(tenantId, command.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.COMMAND, enrichWithUserModified(tenantId, ResourceType.COMMAND, command.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.COMMAND)
                        .resourceCode(command.getCode())
                        .resourceName(command.getEffectiveDisplayName())
                        .action(action)
                        .build()));
            }
        }

        // Preview permissions
        if (manifest.getPermissions() != null) {
            for (PermissionDefinitionDTO permission : manifest.getPermissions()) {
                ResourceAction action = resourceImporter.checkPermissionExists(tenantId, permission.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.PERMISSION, enrichWithUserModified(tenantId, ResourceType.PERMISSION, permission.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.PERMISSION)
                        .resourceCode(permission.getCode())
                        .resourceName(permission.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview roles
        if (manifest.getRoles() != null) {
            for (RoleDefinitionDTO role : manifest.getRoles()) {
                ResourceAction action = resourceImporter.checkRoleExists(tenantId, role.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.ROLE, enrichWithUserModified(tenantId, ResourceType.ROLE, role.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.ROLE)
                        .resourceCode(role.getCode())
                        .resourceName(role.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview menus
        if (manifest.getMenus() != null) {
            for (MenuDefinitionDTO menu : manifest.getMenus()) {
                ResourceAction action = resourceImporter.checkMenuExists(tenantId, menu.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.MENU, enrichWithUserModified(tenantId, ResourceType.MENU, menu.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.MENU)
                        .resourceCode(menu.getCode())
                        .resourceName(menu.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview pages
        if (manifest.getPages() != null) {
            for (PageSchemaDTO page : manifest.getPages()) {
                ResourceAction action = resourceImporter.checkPageExists(tenantId, page.getPageKey())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.PAGE, enrichWithUserModified(tenantId, ResourceType.PAGE, page.getPageKey(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.PAGE)
                        .resourceCode(page.getPageKey())
                        .resourceName(page.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview dicts
        if (manifest.getDicts() != null) {
            for (DictDefinitionDTO dict : manifest.getDicts()) {
                ResourceAction action = resourceImporter.checkDictExists(tenantId, dict.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.DICT, enrichWithUserModified(tenantId, ResourceType.DICT, dict.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.DICT)
                        .resourceCode(dict.getCode())
                        .resourceName(dict.getEffectiveName())
                        .action(action)
                        .build()));
            }
        }

        // Preview named queries
        if (manifest.getNamedQueries() != null) {
            for (NamedQueryDefinitionDTO namedQuery : manifest.getNamedQueries()) {
                ResourceAction action = resourceImporter.checkNamedQueryExists(tenantId, namedQuery.getCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.NAMED_QUERY, enrichWithUserModified(
                        tenantId, ResourceType.NAMED_QUERY, namedQuery.getCode(),
                        ImportPreviewResult.ResourceChange.builder()
                                .resourceType(ResourceType.NAMED_QUERY)
                                .resourceCode(namedQuery.getCode())
                                .resourceName(namedQuery.getEffectiveTitle())
                                .action(action)
                                .build()));
            }
        }

        // Preview agent definitions
        if (manifest.getAgentDefinitions() != null) {
            for (AgentDefinitionDTO agentDefinition : manifest.getAgentDefinitions()) {
                ResourceAction action = resourceImporter.checkAgentDefinitionExists(tenantId, agentDefinition.getAgentCode())
                        ? ResourceAction.UPDATE : ResourceAction.CREATE;
                result.addChange(ResourceType.AGENT_DEFINITION, enrichWithUserModified(
                        tenantId, ResourceType.AGENT_DEFINITION, agentDefinition.getAgentCode(),
                        ImportPreviewResult.ResourceChange.builder()
                                .resourceType(ResourceType.AGENT_DEFINITION)
                                .resourceCode(agentDefinition.getAgentCode())
                                .resourceName(agentDefinition.getEffectiveName())
                                .action(action)
                                .build()));
            }
        }

        // Preview saved views
        if (manifest.getSavedViews() != null) {
            for (SavedViewDefinitionDTO savedView : manifest.getSavedViews()) {
                List<SavedView> existingViews = savedViewMapper.findGlobalViews(savedView.getModelCode(), savedView.getPageKey());
                boolean exists = existingViews.stream()
                        .anyMatch(v -> matchesPluginSavedView(savedView, v));
                result.addChange(ResourceType.SAVED_VIEW, ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.SAVED_VIEW)
                        .resourceCode(savedView.getUniqueKey())
                        .resourceName(savedView.getName() + " (" + savedView.getViewType() + ")")
                        .action(exists ? ResourceAction.UPDATE : ResourceAction.CREATE)
                        .build());
            }
        }

        // Preview notification templates
        if (manifest.getNotificationTemplates() != null) {
            for (NotificationTemplateDefinitionDTO template : manifest.getNotificationTemplates()) {
                NotificationTemplate existingTemplate =
                        notificationTemplateMapper.findByCodeForUpsert(tenantId, template.getCode());
                result.addChange(ResourceType.NOTIFICATION_TEMPLATE, ImportPreviewResult.ResourceChange.builder()
                        .resourceType(ResourceType.NOTIFICATION_TEMPLATE)
                        .resourceCode(template.getCode())
                        .resourceName(template.getName())
                        .action(existingTemplate != null ? ResourceAction.UPDATE : ResourceAction.CREATE)
                        .build());
            }
        }
    }

    ImportPreviewResult.ResourceChange enrichWithUserModified(
            Long tenantId, ResourceType type, String resourceCode,
            ImportPreviewResult.ResourceChange change){
        if (change.getAction() == ResourceAction.UPDATE) {
            try {
                PluginResource pr = pluginResourceMapper.findByTypeAndCode(
                        tenantId, type.name(), resourceCode);
                if (pr != null && Boolean.TRUE.equals(pr.getUserModified())) {
                    change.setUserModified(true);
                    change.setUserModifiedAt(pr.getUserModifiedAt());
                }
            } catch (Exception e) {
                log.debug("Failed to check user-modified status for {} {}: {}",
                        type, logSafe(resourceCode), logSafe(e.getMessage()));
            }
        }
        return change;
    }

    void summarizeUserModifiedConflicts(ImportPreviewResult result){
        List<String> modifiedResources = new ArrayList<>();
        if (result.getChanges() != null) {
            for (List<ImportPreviewResult.ResourceChange> changes : result.getChanges().values()) {
                for (ImportPreviewResult.ResourceChange change : changes) {
                    if (change.isUserModified() && change.getAction() == ResourceAction.UPDATE) {
                        modifiedResources.add(change.getResourceType() + " " + change.getResourceCode());
                    }
                }
            }
        }
        if (!modifiedResources.isEmpty()) {
            result.addWarning("The following " + modifiedResources.size() +
                    " resource(s) have been manually modified and will be overwritten: " +
                    String.join(", ", modifiedResources));
        }
    }

    private static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }
}
