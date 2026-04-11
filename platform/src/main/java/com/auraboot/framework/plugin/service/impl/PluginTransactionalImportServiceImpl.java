package com.auraboot.framework.plugin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.auraboot.framework.menu.entity.Menu;
import com.auraboot.framework.permission.entity.Permission;
import com.auraboot.framework.meta.entity.PageSchema;
import com.auraboot.framework.menu.mapper.MenuMapper;
import com.auraboot.framework.permission.mapper.PermissionMapper;
import com.auraboot.framework.meta.mapper.PageSchemaMapper;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.util.UniqueIdGenerator;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.plugin.dao.entity.PluginImportLog;
import com.auraboot.framework.plugin.dao.mapper.PluginImportLogMapper;
import com.auraboot.framework.plugin.dto.ConflictItem;
import com.auraboot.framework.plugin.dto.PluginImportRequest;
import com.auraboot.framework.plugin.dto.PluginImportResult;
import com.auraboot.framework.plugin.service.PluginConflictChecker;
import com.auraboot.framework.plugin.service.PluginImportContext;
import com.auraboot.framework.plugin.service.PluginTransactionalImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import com.auraboot.framework.common.constant.StatusConstants;

/**
 * Transactional plugin import with conflict detection, resource tracking, and rollback.
 *
 * Import flow:
 * 1. checkConflicts() — detect code/key collisions
 * 2. importSchemas() -> importPermissions() -> importMenus() — each step records IDs in ImportContext
 * 3. On success: log SUCCESS with resource manifest
 * 4. On failure: rollback in reverse order (menus -> permissions -> schemas), log ROLLED_BACK
 */
@Service
public class PluginTransactionalImportServiceImpl implements PluginTransactionalImportService {

    private static final Logger log = LoggerFactory.getLogger(PluginTransactionalImportServiceImpl.class);

    @Autowired
    private PluginConflictChecker conflictChecker;

    @Autowired
    private PageSchemaMapper schemaMapper;

    @Autowired
    private PermissionMapper permissionMapper;

    @Autowired
    private MenuMapper menuMapper;

    @Autowired
    private PluginImportLogMapper importLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PluginImportResult importPlugin(PluginImportRequest request, boolean dryRun) {
        PluginImportResult result = new PluginImportResult();
        result.setPluginCode(request.getPluginCode());

        // Step 1: Conflict detection
        List<ConflictItem> conflicts = conflictChecker.checkConflicts(request);
        if (!conflicts.isEmpty()) {
            result.setStatus("conflict");
            result.setConflicts(conflicts);
            result.setErrorMessage("Found " + conflicts.size() + " resource conflict(s)");

            if (!dryRun) {
                // Log the conflict as a FAILED import
                saveImportLog(request, "failed", null,
                        "Blocked by " + conflicts.size() + " conflict(s): " +
                        conflicts.stream().map(c -> c.getType() + ":" + c.getCode()).collect(Collectors.joining(", ")));
            }
            return result;
        }

        if (dryRun) {
            result.setStatus("OK");
            return result;
        }

        // Step 2: Perform import with resource tracking
        PluginImportContext ctx = new PluginImportContext(request.getPluginCode());
        Instant startedAt = Instant.now();

        try {
            importPermissions(request, ctx);
            importMenus(request, ctx);

            // Step 3: Log success
            PluginImportLog importLog = saveImportLog(request, "success", ctx.toResourceList(), null);
            importLog.setStartedAt(startedAt);
            importLog.setCompletedAt(Instant.now());
            importLogMapper.updateById(importLog);

            result.setStatus(StatusConstants.SUCCESS);
            result.setSchemasImported(ctx.getSchemaIds().size());
            result.setPermissionsImported(ctx.getPermissionIds().size());
            result.setMenusImported(ctx.getMenuIds().size());
            result.setImportLogId(importLog.getId());

            log.info("Plugin [{}] v{} imported successfully: {} permissions, {} menus",
                    request.getPluginCode(), request.getPluginVersion(),
                    ctx.getPermissionIds().size(), ctx.getMenuIds().size());

        } catch (Exception e) {
            log.error("Plugin [{}] import failed, rolling back {} resources",
                    request.getPluginCode(), ctx.totalImported(), e);

            // Step 4: Rollback in reverse order
            rollback(ctx);

            saveImportLog(request, "rolled_back", ctx.toResourceList(), e.getMessage());

            result.setStatus(StatusConstants.ROLLED_BACK);
            result.setErrorMessage(e.getMessage());
            throw new RootUnCheckedException(ResponseCode.BUSINESS_ERROR,
                    "Import failed and rolled back: " + e.getMessage());
        }

        return result;
    }

    @Override
    public List<PluginImportResult> getImportHistory(String pluginCode, int pageNum, int pageSize) {
        QueryWrapper<PluginImportLog> qw = new QueryWrapper<>();
        if (pluginCode != null && !pluginCode.isBlank()) {
            qw.eq("plugin_code", pluginCode);
        }
        qw.eq("deleted_flag", false);
        qw.orderByDesc("created_at");

        Page<PluginImportLog> page = importLogMapper.selectPage(
                new Page<>(pageNum, pageSize), qw);

        return page.getRecords().stream().map(this::toResult).collect(Collectors.toList());
    }

    // ---- Internal import steps ----

    private void importPermissions(PluginImportRequest request, PluginImportContext ctx) {
        if (request.getPermissions() == null) return;

        for (Map<String, Object> permEntry : request.getPermissions()) {
            Permission perm = new Permission();
            perm.setPid(UniqueIdGenerator.generate());
            perm.setCode((String) permEntry.get("code"));
            perm.setName((String) permEntry.getOrDefault("name", perm.getCode()));
            perm.setResourceType((String) permEntry.getOrDefault("resourceType", "plugin"));
            perm.setAction((String) permEntry.getOrDefault("action", "execute"));
            perm.setSource("generated");
            perm.setStatus(StatusConstants.ACTIVE);
            perm.setDeletedFlag(false);
            perm.setCreatedAt(Instant.now());
            perm.setUpdatedAt(Instant.now());

            permissionMapper.insert(perm);
            ctx.getPermissionIds().add(perm.getId());
        }
    }

    private void importMenus(PluginImportRequest request, PluginImportContext ctx) {
        if (request.getMenus() == null) return;

        for (Map<String, Object> menuEntry : request.getMenus()) {
            Menu menu = new Menu();
            menu.setPid(UniqueIdGenerator.generate());
            menu.setName((String) menuEntry.get("name"));
            menu.setPath((String) menuEntry.get("path"));
            menu.setIcon((String) menuEntry.get("icon"));
            menu.setComponent((String) menuEntry.get("component"));
            menu.setPermissionCode((String) menuEntry.get("permissionCode"));
            menu.setType(menuEntry.get("type") != null ? ((Number) menuEntry.get("type")).intValue() : 1);
            menu.setOrderNo(menuEntry.get("orderNo") != null ? ((Number) menuEntry.get("orderNo")).intValue() : 0);
            menu.setVisible(true);
            menu.setCreatedAt(Instant.now());
            menu.setUpdatedAt(Instant.now());

            menuMapper.insert(menu);
            ctx.getMenuIds().add(menu.getId());
        }
    }

    // ---- Rollback ----

    /**
     * Rollback imported resources in reverse order: menus -> permissions.
     * Uses hard delete since these are freshly inserted rows.
     */
    private void rollback(PluginImportContext ctx) {
        // Reverse order: menus first (may depend on permissions via FK)
        List<Long> menuIds = new ArrayList<>(ctx.getMenuIds());
        Collections.reverse(menuIds);
        for (Long id : menuIds) {
            try {
                menuMapper.deleteById(id);
            } catch (Exception e) {
                log.warn("Failed to rollback menu id={}: {}", id, e.getMessage());
            }
        }

        List<Long> permIds = new ArrayList<>(ctx.getPermissionIds());
        Collections.reverse(permIds);
        for (Long id : permIds) {
            try {
                permissionMapper.deleteById(id);
            } catch (Exception e) {
                log.warn("Failed to rollback permission id={}: {}", id, e.getMessage());
            }
        }

        log.info("Rolled back {} resources for plugin [{}]",
                ctx.totalImported(), ctx.getPluginCode());
    }

    // ---- Import log persistence ----

    private PluginImportLog saveImportLog(PluginImportRequest request, String status,
                                          List<Map<String, Object>> resources, String errorMessage) {
        PluginImportLog importLog = new PluginImportLog();
        importLog.setPid(UniqueIdGenerator.generate());
        Long tenantId = com.auraboot.framework.application.tenant.MetaContext.getCurrentTenantId();
        importLog.setTenantId(tenantId != null ? tenantId : 0L);
        importLog.setPluginCode(request.getPluginCode());
        importLog.setPluginVersion(request.getPluginVersion());
        importLog.setStatus(status);
        importLog.setImportedResources(resources);
        importLog.setErrorMessage(errorMessage);
        importLog.setStartedAt(Instant.now());
        importLog.setCompletedAt(Instant.now());
        importLog.setDeletedFlag(false);
        importLog.setCreatedAt(Instant.now());
        importLog.setUpdatedAt(Instant.now());

        importLogMapper.insert(importLog);
        return importLog;
    }

    private PluginImportResult toResult(PluginImportLog importLog) {
        PluginImportResult result = new PluginImportResult();
        result.setPluginCode(importLog.getPluginCode());
        result.setStatus(importLog.getStatus());
        result.setErrorMessage(importLog.getErrorMessage());
        result.setImportLogId(importLog.getId());

        if (importLog.getImportedResources() != null) {
            for (Map<String, Object> r : importLog.getImportedResources()) {
                String type = (String) r.get("type");
                if ("schema".equalsIgnoreCase(type)) result.setSchemasImported(result.getSchemasImported() + 1);
                if ("permission".equalsIgnoreCase(type)) result.setPermissionsImported(result.getPermissionsImported() + 1);
                if ("menu".equalsIgnoreCase(type)) result.setMenusImported(result.getMenusImported() + 1);
            }
        }

        return result;
    }
}
