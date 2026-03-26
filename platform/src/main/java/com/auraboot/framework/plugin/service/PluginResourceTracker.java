package com.auraboot.framework.plugin.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import com.auraboot.framework.plugin.entity.PluginResource;
import com.auraboot.framework.plugin.mapper.PluginResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tracks user modifications to plugin-managed resources.
 *
 * When a user modifies a resource (Command, Page, Field, etc.) through Studio or API,
 * this service marks the corresponding PluginResource record as user-modified.
 * This information is used during plugin reimport to protect user customizations
 * via the OVERWRITE_SAFE conflict strategy.
 *
 * Note: Plugin imports do NOT go through Controllers, so calling this from
 * Controllers ensures only user-initiated modifications are tracked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PluginResourceTracker {

    private final PluginResourceMapper pluginResourceMapper;

    /**
     * Mark a plugin-managed resource as user-modified.
     * Safe to call for any resource — silently skips if the resource is not plugin-managed.
     *
     * @param resourceType the resource type (e.g., COMMAND, PAGE, FIELD)
     * @param resourceCode the resource code (e.g., command code, page key, field code)
     */
    public void markAsUserModified(ResourceType resourceType, String resourceCode) {
        Long tenantId = MetaContext.getCurrentTenantId();
        if (tenantId == null || resourceCode == null) {
            return;
        }

        try {
            PluginResource pr = pluginResourceMapper.findByTypeAndCode(
                    tenantId, resourceType.name(), resourceCode);

            if (pr != null && !Boolean.TRUE.equals(pr.getUserModified())) {
                pr.markAsUserModified();
                pluginResourceMapper.updateById(pr);
                log.info("Marked plugin resource as user-modified: type={}, code={}",
                        resourceType, resourceCode);
            }
        } catch (Exception e) {
            // Non-blocking: don't fail the user's operation if tracking fails
            log.warn("Failed to mark resource as user-modified: type={}, code={}, error={}",
                    resourceType, resourceCode, e.getMessage());
        }
    }
}
