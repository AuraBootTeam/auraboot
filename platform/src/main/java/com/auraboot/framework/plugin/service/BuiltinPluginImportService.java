package com.auraboot.framework.plugin.service;

/**
 * Service for importing built-in plugins during tenant bootstrap.
 * Built-in plugins are shipped with the platform and automatically
 * imported when a new tenant is created.
 */
public interface BuiltinPluginImportService {

    /**
     * Import all built-in plugins for a tenant.
     * This should be called after tenant bootstrap (roles, permissions, menus) completes.
     *
     * @param tenantId tenant ID
     * @param userId   creator user ID
     */
    void importForTenant(Long tenantId, Long userId);
}
