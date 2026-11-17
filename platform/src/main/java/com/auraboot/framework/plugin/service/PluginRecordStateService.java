package com.auraboot.framework.plugin.service;

import com.auraboot.framework.plugin.entity.PluginRecord;

/**
 * Record-level enable/disable state transitions for installed plugins.
 * <p>
 * These are pure DB flag switches on the plugin record — distinct from
 * {@link PluginManagerService} enable/disable, which drives the full PF4J
 * runtime lifecycle. Controllers must not touch the mapper layer directly
 * (ArchUnit rule 2); this service carries those record writes.
 */
public interface PluginRecordStateService {

    /**
     * Loads the record, enforces the current tenant boundary, flips the
     * enabled flag, and returns the refreshed record.
     *
     * @throws IllegalArgumentException when the pid is unknown or belongs to
     *                                  another tenant ("Plugin not found")
     */
    PluginRecord setEnabled(String pid, boolean enabled);

    /** Record by exact pid (or null) — same-tenant enforcement left to the caller. */
    PluginRecord findByPid(String pid);

    /**
     * Catalog-visible plugin records for the tenant under the current
     * ApplicationCatalogPolicy (moved from PluginController).
     */
    java.util.List<PluginRecord> listCatalog(Long tenantId);
}
