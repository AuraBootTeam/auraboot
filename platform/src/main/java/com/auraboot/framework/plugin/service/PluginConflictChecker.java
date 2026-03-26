package com.auraboot.framework.plugin.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.plugin.dao.entity.PluginImportLog;
import com.auraboot.framework.plugin.dao.mapper.PluginImportLogMapper;
import com.auraboot.framework.plugin.dto.ConflictItem;
import com.auraboot.framework.plugin.dto.PluginImportRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Pre-import conflict checker for plugin resources.
 * Detects code/key collisions in schemas, permissions, and menus
 * before the actual import begins.
 */
@Service
public class PluginConflictChecker {

    private static final Logger log = LoggerFactory.getLogger(PluginConflictChecker.class);

    @Autowired
    private PageSchemaMapper schemaMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private MenuMapper menuMapper;

    @Autowired
    private PluginImportLogMapper importLogMapper;

    /**
     * Check all resources in the import request for conflicts with existing data.
     * For reimport scenarios, resources owned by the same plugin are excluded from conflicts.
     *
     * @param request the plugin import request
     * @return list of conflict items (empty if no conflicts)
     */
    public List<ConflictItem> checkConflicts(PluginImportRequest request) {
        List<ConflictItem> conflicts = new ArrayList<>();

        // Determine which resources belong to a previous import of the same plugin
        // (reimport scenario — these are not real conflicts)
        List<Long> ownedSchemaIds = getOwnedResourceIds(request.getPluginCode(), "schema");
        List<Long> ownedPermissionIds = getOwnedResourceIds(request.getPluginCode(), "permission");
        List<Long> ownedMenuIds = getOwnedResourceIds(request.getPluginCode(), "menu");

        // Check schema conflicts by page_key (unique business key)
        if (request.getSchemas() != null) {
            for (Map<String, Object> schemaEntry : request.getSchemas()) {
                String pageKey = (String) schemaEntry.get("pageKey");
                if (pageKey == null) continue;

                QueryWrapper<PageSchema> qw = new QueryWrapper<>();
                qw.eq("page_key", pageKey);
                qw.eq("deleted_flag", false);
                List<PageSchema> existing = schemaMapper.selectList(qw);

                for (PageSchema schema : existing) {
                    if (!ownedSchemaIds.contains(schema.getId())) {
                        conflicts.add(new ConflictItem("schema", pageKey, findOwnerPlugin(schema.getId(), "schema")));
                    }
                }
            }
        }

        // Check permission conflicts by code (unique)
        if (request.getPermissions() != null) {
            for (Map<String, Object> permEntry : request.getPermissions()) {
                String code = (String) permEntry.get("code");
                if (code == null) continue;

                Permission existing = permissionMapper.findByCode(code);
                if (existing != null && !ownedPermissionIds.contains(existing.getId())) {
                    conflicts.add(new ConflictItem("permission", code, findOwnerPlugin(existing.getId(), "permission")));
                }
            }
        }

        // Check menu conflicts by path
        if (request.getMenus() != null) {
            for (Map<String, Object> menuEntry : request.getMenus()) {
                String path = (String) menuEntry.get("path");
                if (path == null) continue;

                QueryWrapper<Menu> qw = new QueryWrapper<>();
                qw.eq("path", path);
                List<Menu> existing = menuMapper.selectList(qw);

                for (Menu menu : existing) {
                    if (!ownedMenuIds.contains(menu.getId())) {
                        conflicts.add(new ConflictItem("menu", path, findOwnerPlugin(menu.getId(), "menu")));
                    }
                }
            }
        }

        log.info("Conflict check for plugin [{}]: {} conflicts found", request.getPluginCode(), conflicts.size());
        return conflicts;
    }

    /**
     * Get resource IDs that were imported by a previous successful import of the same plugin.
     */
    private List<Long> getOwnedResourceIds(String pluginCode, String resourceType) {
        List<Long> ids = new ArrayList<>();

        QueryWrapper<PluginImportLog> qw = new QueryWrapper<>();
        qw.eq("plugin_code", pluginCode);
        qw.eq("status", StatusConstants.SUCCESS);
        qw.orderByDesc("completed_at");
        qw.last("LIMIT 1");

        PluginImportLog lastImport = importLogMapper.selectOne(qw);
        if (lastImport == null || lastImport.getImportedResources() == null) {
            return ids;
        }

        for (Map<String, Object> resource : lastImport.getImportedResources()) {
            String type = (String) resource.get("type");
            if (resourceType.equals(type) && resource.get("id") != null) {
                ids.add(((Number) resource.get("id")).longValue());
            }
        }
        return ids;
    }

    /**
     * Find which plugin originally imported a given resource.
     * Returns the plugin code, or "system" if not imported by any plugin.
     */
    private String findOwnerPlugin(Long resourceId, String resourceType) {
        QueryWrapper<PluginImportLog> qw = new QueryWrapper<>();
        qw.eq("status", StatusConstants.SUCCESS);
        qw.orderByDesc("completed_at");

        List<PluginImportLog> logs = importLogMapper.selectList(qw);
        for (PluginImportLog importLog : logs) {
            if (importLog.getImportedResources() == null) continue;
            for (Map<String, Object> resource : importLog.getImportedResources()) {
                String type = (String) resource.get("type");
                Object id = resource.get("id");
                if (resourceType.equals(type) && id != null && ((Number) id).longValue() == resourceId) {
                    return importLog.getPluginCode();
                }
            }
        }
        return "system";
    }
}
