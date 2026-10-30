package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.meta.service.*;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.exception.PluginException;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.common.util.LogSanitizer;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/** Shared import serialization, overwrite and resurrection operations. */
final class PluginImportSupport {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PluginResourceMapper pluginResourceMapper;
    private final Logger log;

    PluginImportSupport(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PluginResourceMapper pluginResourceMapper,
            Logger log) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.pluginResourceMapper = pluginResourceMapper;
        this.log = log;
    }

    static String logSafe(Object value) {
        return LogSanitizer.safe(value);
    }

    /**
     * Check if a resource has been modified by a user (for OVERWRITE_SAFE strategy).
     * Returns true if the resource is tracked in ab_plugin_resource and marked as user_modified.
     */
    private PluginResourceMapper.ModificationState lockProtectionState(Long tenantId, ResourceType type, String resourceCode) {
        try {
            PluginResourceMapper.ModificationState resource = pluginResourceMapper.lockModificationState(tenantId, type.code(), resourceCode);
            if (resource != null && resource.modified() == null) {
                throw new PluginException("Customization protection state is missing");
            }
            return resource;
        } catch (Exception failure) {
            throw new PluginException("Cannot verify customization protection for "
                    + type.code() + ":" + resourceCode, failure);
        }
    }

    boolean isResourceUserModified(Long tenantId, ResourceType type, String resourceCode) {
        PluginResourceMapper.ModificationState resource = lockProtectionState(tenantId, type, resourceCode);
        return resource != null && Boolean.TRUE.equals(resource.modified());
    }

    boolean shouldSkipForOverwriteSafe(Long tenantId, ImportRequest.ConflictStrategy strategy,
                                       ResourceType type, String resourceCode) {
        return strategy == ImportRequest.ConflictStrategy.OVERWRITE_SAFE
                && isResourceUserModified(tenantId, type, resourceCode);
    }

    /** Existing untracked content is customer-owned until an explicit overwrite adopts it. */
    boolean shouldSkipExistingForOverwriteSafe(Long tenantId, ImportRequest.ConflictStrategy strategy,
                                               ResourceType type, String resourceCode) {
        if (strategy != ImportRequest.ConflictStrategy.OVERWRITE_SAFE) return false;
        PluginResourceMapper.ModificationState resource = lockProtectionState(tenantId, type, resourceCode);
        return resource == null || Boolean.TRUE.equals(resource.modified());
    }

    String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new PluginException("Failed to serialize to JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Check for a soft-deleted record and resurrect it by clearing deleted_flag.
     * Returns the pid of the resurrected record, or null if no soft-deleted record exists.
     */
    String resurrectSoftDeleted(String tableName, String codeColumn, String codeValue,
                                         Long tenantId, String pluginPid, Map<String, Object> extension) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                String.format("SELECT pid FROM %s WHERE %s = ? AND tenant_id = ? AND deleted_flag = TRUE LIMIT 1",
                        tableName, codeColumn),
                codeValue, tenantId);

        if (rows.isEmpty()) {
            return null;
        }

        String pid = (String) rows.get(0).get("pid");

        // Resurrect: clear deleted_flag and update plugin_pid
        if (extension != null && !extension.isEmpty()) {
            Map<String, Object> wrappedExtension = new HashMap<>();
            wrappedExtension.put("extension", extension);
            String extensionJson = toJson(wrappedExtension);
            jdbcTemplate.update(
                    String.format("UPDATE %s SET deleted_flag = FALSE, plugin_pid = ?, extension = ?::jsonb, updated_at = NOW() WHERE pid = ?",
                            tableName),
                    pluginPid, extensionJson, pid);
        } else {
            jdbcTemplate.update(
                    String.format("UPDATE %s SET deleted_flag = FALSE, plugin_pid = ?, updated_at = NOW() WHERE pid = ?",
                            tableName),
                    pluginPid, pid);
        }

        return pid;
    }

}
