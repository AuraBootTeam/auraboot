package com.auraboot.framework.plugin.service.impl;

import static com.auraboot.framework.plugin.service.PluginResourceRecords.createResourceRecord;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.menu.service.MenuService;
import com.auraboot.framework.meta.mapper.CommandDefinitionMapper;
import com.auraboot.framework.meta.mapper.DictMapper;
import com.auraboot.framework.meta.mapper.MetaFieldMapper;
import com.auraboot.framework.meta.mapper.MetaModelMapper;
import com.auraboot.framework.meta.mapper.NamedQueryMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.permission.service.PermissionService;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ResourceAction;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.exception.PluginException;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.auraboot.framework.rbac.mapper.RoleMapper;
import com.auraboot.framework.menu.mapper.MenuMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

/** Tracks, rolls back and restores imported resources. */
final class PluginResourceLifecycleImporter {
    // Preserve virtual calls on the original facade receiver.
    private final PluginResourceImporterImpl facadeReceiver;
    private final MetaModelService metaModelService;
    private final MetaFieldService metaFieldService;
    private final DictService dictService;
    private final CommandService commandService;
    private final PermissionService permissionService;
    private final MenuService menuService;
    private final PageSchemaService pageSchemaService;
    private final NamedQueryService namedQueryService;
    private final JdbcTemplate jdbcTemplate;
    private final RoleMapper roleMapper;
    private final MenuMapper menuMapper;
    private final MetaModelMapper metaModelMapper;
    private final CommandDefinitionMapper commandDefinitionMapper;
    private final PermissionMapper permissionMapper;
    private final PageSchemaMapper pageSchemaMapper;
    private final DictMapper dictMapper;
    private final NamedQueryMapper namedQueryMapper;
    private final com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle agentDefinitionResources;
    private final MetaFieldMapper metaFieldMapper;
    private final PluginResourceMapper pluginResourceMapper;
    private final PluginPublicPublicationLifecycle publicPublicationLifecycle;
    private final Logger log;
    private final PluginImportSupport support;

    PluginResourceLifecycleImporter(
            PluginResourceImporterImpl facadeReceiver,
            MetaModelService metaModelService,
            MetaFieldService metaFieldService,
            DictService dictService,
            CommandService commandService,
            PermissionService permissionService,
            MenuService menuService,
            PageSchemaService pageSchemaService,
            NamedQueryService namedQueryService,
            JdbcTemplate jdbcTemplate,
            RoleMapper roleMapper,
            MenuMapper menuMapper,
            MetaModelMapper metaModelMapper,
            CommandDefinitionMapper commandDefinitionMapper,
            PermissionMapper permissionMapper,
            PageSchemaMapper pageSchemaMapper,
            DictMapper dictMapper,
            NamedQueryMapper namedQueryMapper,
            @org.springframework.lang.Nullable com.auraboot.framework.plugin.service.AgentDefinitionResourceLifecycle agentDefinitionResources,
            MetaFieldMapper metaFieldMapper,
            PluginResourceMapper pluginResourceMapper,
            PluginPublicPublicationLifecycle publicPublicationLifecycle,
            Logger log,
            PluginImportSupport support) {
        this.facadeReceiver = facadeReceiver;
        this.metaModelService = metaModelService;
        this.metaFieldService = metaFieldService;
        this.dictService = dictService;
        this.commandService = commandService;
        this.permissionService = permissionService;
        this.menuService = menuService;
        this.pageSchemaService = pageSchemaService;
        this.namedQueryService = namedQueryService;
        this.jdbcTemplate = jdbcTemplate;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.metaModelMapper = metaModelMapper;
        this.commandDefinitionMapper = commandDefinitionMapper;
        this.permissionMapper = permissionMapper;
        this.pageSchemaMapper = pageSchemaMapper;
        this.dictMapper = dictMapper;
        this.namedQueryMapper = namedQueryMapper;
        this.agentDefinitionResources = agentDefinitionResources;
        this.metaFieldMapper = metaFieldMapper;
        this.pluginResourceMapper = pluginResourceMapper;
        this.publicPublicationLifecycle = publicPublicationLifecycle;
        this.log = log;
        this.support = support;
    }

    public boolean checkAgentDefinitionExists(Long tenantId, String agentCode) {
        PluginAgentResourceGuard.requireInstalled(agentDefinitionResources);
        return agentDefinitionResources.exists(tenantId, agentCode);
    }

    public PluginResource importAgentDefinition(AgentDefinitionDTO dto, String pluginPid, String importId,
                                                Long tenantId, ImportRequest.ConflictStrategy conflictStrategy) {
        PluginAgentResourceGuard.requireInstalled(agentDefinitionResources);
        return agentDefinitionResources.importAgentDefinition(dto, pluginPid, importId, tenantId, conflictStrategy);
    }

    /**
     * Rollback handlers below run during plugin un-install or import-failure compensation.
     *
     * The {@code catch (Exception)} branches here are intentional:
     *   1. They are best-effort cleanups; a single failed delete must not block
     *      subsequent rollback steps for other resource types.
     *   2. They fall back to a direct mapper/jdbc archive call when the service
     *      method fails — the resource still ends up in a tombstoned state.
     *   3. {@code log.warn} carries the message + cause via SLF4J's {@code {}}
     *      placeholder, so trace context is preserved for incident analysis.
     *
     * Per docs/agent-rules/review-baseline.md §1, this is the "non-transactional
     * cleanup" exception to the no-catch-Exception rule.
     */
    public void rollbackResource(PluginResource resource) {
        ResourceType type = resource.getResourceTypeEnum();
        PluginAgentResourceGuard.requireType(type, agentDefinitionResources);

        switch (type) {
            case PUBLIC_RESOURCE, PUBLIC_QUERY -> publicPublicationLifecycle.restore(resource);
            case MODEL -> {
                if (resource.getResourcePid() != null) {
                    try {
                        metaModelService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for model {}: {}",
                                PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                        metaModelMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case FIELD -> {
                if (resource.getResourcePid() != null) {
                    try {
                        metaFieldService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for field {}: {}",
                                PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                        metaFieldMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case COMMAND -> {
                if (resource.getResourcePid() != null) {
                    try {
                        commandService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for command {}: {}",
                                PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                        commandDefinitionMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case PERMISSION -> {
                if (resource.getResourceId() != null) {
                    try {
                        permissionService.delete(resource.getResourceId());
                    } catch (Exception e) {
                        log.warn("Service delete failed for permission {}: {}",
                                PluginImportSupport.logSafe(resource.getResourceId()), PluginImportSupport.logSafe(e.getMessage()));
                        permissionMapper.softDelete(resource.getResourceId());
                    }
                }
            }
            case ROLE -> {
                try {
                    roleMapper.softDeleteByPid(resource.getResourcePid());
                } catch (Exception e) {
                    log.warn("Failed to delete role {}: {}",
                            PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                }
            }
            case MENU -> {
                if (resource.getResourceId() != null) {
                    try {
                        menuService.deleteMenu(resource.getResourceId());
                    } catch (Exception e) {
                        log.warn("Service delete failed for menu {}: {}",
                                PluginImportSupport.logSafe(resource.getResourceId()), PluginImportSupport.logSafe(e.getMessage()));
                        menuMapper.softDeleteById(resource.getResourceId());
                    }
                }
            }
            case PAGE -> {
                if (resource.getResourcePid() != null) {
                    try {
                        pageSchemaService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for page {}: {}",
                                PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                        pageSchemaMapper.archiveByPid(resource.getResourcePid());
                    }
                }
            }
            case DICT -> {
                if (resource.getResourcePid() != null) {
                    try {
                        dictService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Service delete failed for dict {}, using mapper fallback: {}",
                                PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                        dictMapper.softDeleteByPid(resource.getResourcePid());
                    }
                }
            }
            case NAMED_QUERY -> {
                if (resource.getResourcePid() != null) {
                    try {
                        namedQueryService.delete(resource.getResourcePid());
                    } catch (Exception e) {
                        log.warn("Failed to delete named query {}: {}",
                                PluginImportSupport.logSafe(resource.getResourcePid()), PluginImportSupport.logSafe(e.getMessage()));
                        namedQueryMapper.updateStatusByPid(resource.getResourcePid(), "archived");
                    }
                }
            }
            case AGENT_DEFINITION -> agentDefinitionResources.rollback(resource);
            default -> log.warn("Rollback not implemented for resource type: {}", type);
        }
    }

    public void restoreResource(PluginResource resource) {
        PluginAgentResourceGuard.requireType(resource.getResourceTypeEnum(), agentDefinitionResources);
        log.info("Restoring resource: {} ({})", PluginImportSupport.logSafe(resource.getResourceCode()), resource.getResourceType());
        ResourceType type = resource.getResourceTypeEnum();
        if (type.isPublicPublication()) {
            publicPublicationLifecycle.restore(resource);
            return;
        }
        if (type == ResourceType.AGENT_DEFINITION) {
            agentDefinitionResources.restore(resource);
            return;
        }
        if (resource.getPreviousState() == null) {
            return;
        }
    }

    public int pruneStaleMenus(String pluginPid, String importId, Long tenantId,
                               java.util.Collection<String> configuredMenuCodes) {
        java.util.Set<String> configured = configuredMenuCodes == null
                ? java.util.Set.of()
                : new java.util.HashSet<>(configuredMenuCodes);
        List<String> staleCodes = trackedMenuCodes(pluginPid, tenantId).stream()
                .filter(code -> !configured.contains(code))
                .toList();
        if (staleCodes.isEmpty()) {
            return 0;
        }
        int pruned = deleteTrackedMenus(pluginPid, importId, tenantId, staleCodes);
        log.info("Pruned {} stale menu(s) dropped from plugin {} declaration: {}",
                pruned, PluginImportSupport.logSafe(pluginPid), staleCodes);
        return pruned;
    }

    public int deactivateAllPluginMenus(String pluginPid, Long tenantId) {
        List<String> codes = trackedMenuCodes(pluginPid, tenantId);
        if (codes.isEmpty()) {
            return 0;
        }
        int deactivated = deleteTrackedMenus(pluginPid, null, tenantId, codes);
        log.info("Deactivated {} imported menu(s) of uninstalled plugin {}: {}",
                deactivated, PluginImportSupport.logSafe(pluginPid), codes);
        return deactivated;
    }

    /**
     * Menu codes this plugin family (same plugin_id across installs) previously imported,
     * per tenant. Scoped to ab_plugin_resource tracking and explicitly excludes
     * authoring-managed rows, so tenant-owned and dashboard-created menus are never touched.
     */
    private List<String> trackedMenuCodes(String pluginPid, Long tenantId) {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT r.resource_code
                FROM ab_plugin_resource r
                JOIN ab_plugin p ON p.pid = r.plugin_pid
                JOIN ab_menu m ON m.tenant_id = r.tenant_id AND m.code = r.resource_code
                               AND m.deleted_flag = FALSE
                WHERE p.plugin_id = (SELECT plugin_id FROM ab_plugin WHERE pid = ?)
                  AND r.tenant_id = ?
                  AND r.resource_type = ?
                  AND COALESCE(m.extension ->> 'authoringManaged', 'false') <> 'true'
                """, String.class, pluginPid, tenantId, ResourceType.MENU.code()).stream()
                .filter(Objects::nonNull)
                .filter(code -> !code.isBlank())
                .toList();
    }

    /**
     * Validate the complete deletion boundary, then remove leaves before parents.
     * Import runs in a transaction: invoking a service that throws and catching it in
     * rollbackResource would still poison that transaction. Retained children and cycles
     * must fail before any deletion; they must never become orphaned through a fallback.
     */
    private int deleteTrackedMenus(String pluginPid, String importId, Long tenantId, List<String> codes) {
        Map<Long, PluginResource> pending = new java.util.LinkedHashMap<>();
        for (String code : codes) {
            Long menuId = menuMapper.findIdByCode(tenantId, code);
            if (menuId == null) {
                continue;
            }
            PluginResource record = new PluginResource();
            record.setResourceType(ResourceType.MENU.code());
            record.setResourceCode(code);
            record.setResourceId(menuId);
            record.setResourcePid(menuMapper.findPidByCode(tenantId, code));
            pending.put(menuId, record);
        }
        if (pending.isEmpty()) {
            return 0;
        }
        List<Menu> activeMenus = menuMapper.selectList(new QueryWrapper<Menu>()
                .eq("tenant_id", tenantId).eq("deleted_flag", false));
        Map<Long, java.util.Set<Long>> children = new HashMap<>();
        for (Menu menu : activeMenus) {
            if (!pending.containsKey(menu.getParentId())) {
                continue;
            }
            if (!pending.containsKey(menu.getId())) {
                throw new PluginException("Cannot prune menu with retained child: " + menu.getCode());
            }
            children.computeIfAbsent(menu.getParentId(), ignored -> new java.util.HashSet<>())
                    .add(menu.getId());
        }
        List<PluginResource> ordered = new ArrayList<>();
        java.util.Set<Long> remaining = new java.util.LinkedHashSet<>(pending.keySet());
        while (!remaining.isEmpty()) {
            List<Long> leaves = remaining.stream().filter(id -> children.getOrDefault(id,
                    java.util.Set.of()).stream().noneMatch(remaining::contains)).toList();
            if (leaves.isEmpty()) {
                throw new PluginException("Cannot prune cyclic menu hierarchy");
            }
            for (Long id : leaves) {
                ordered.add(pending.get(id));
            }
            remaining.removeAll(leaves);
        }
        for (PluginResource record : ordered) {
            if (!menuService.deleteMenu(record.getResourceId())) {
                throw new PluginException("Tracked menu was not deleted: " + record.getResourceCode());
            }
            if (pluginPid != null) {
                upsertResourceRecord(createResourceRecord(pluginPid, importId, tenantId,
                        ResourceType.MENU, record.getResourcePid(), record.getResourceId(),
                        record.getResourceCode(), null,
                        ResourceAction.DELETE, null, null));
            }
        }
        return ordered.size();
    }

    /**
     * ab_plugin_resource enforces one tracking row per (tenant, plugin, type, code), so
     * prune audits follow the import chain's persistence contract: update the existing
     * row in place (re-homing it to the current plugin install), insert only when the
     * code was never tracked.
     */
    private void upsertResourceRecord(PluginResource record) {
        PluginResource existing = pluginResourceMapper.findByTenantPluginAndResource(
                record.getTenantId(), record.getPluginPid(), record.getResourceType(), record.getResourceCode());
        if (existing == null) {
            existing = pluginResourceMapper.findByTypeAndCode(
                    record.getTenantId(), record.getResourceType(), record.getResourceCode());
        }
        if (existing == null) {
            pluginResourceMapper.insert(record);
            return;
        }
        existing.setPluginPid(record.getPluginPid());
        existing.setImportId(record.getImportId());
        existing.setResourcePid(record.getResourcePid());
        existing.setResourceId(record.getResourceId());
        existing.setResourceName(record.getResourceName());
        existing.setAction(record.getAction());
        existing.setPreviousState(record.getPreviousState());
        existing.setCurrentState(record.getCurrentState());
        existing.setImportSnapshot(record.getImportSnapshot());
        existing.setUserModified(false);
        existing.setUserModifiedAt(null);
        existing.setUpdatedAt(record.getUpdatedAt());
        pluginResourceMapper.updateById(existing);
    }

}
