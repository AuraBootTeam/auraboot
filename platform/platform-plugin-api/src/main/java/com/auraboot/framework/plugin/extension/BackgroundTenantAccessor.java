package com.auraboot.framework.plugin.extension;

import java.util.List;

/**
 * Tenant-discovery bridge for plugin background components.
 *
 * <p>Plugins that schedule cross-tenant work (lease scanners, watchers,
 * batch jobs) need to know which tenants currently host live data
 * without each plugin coupling to a platform-internal {@code TenantService}
 * class. This interface gives them a stable, narrow surface.
 *
 * <p>Returned ids represent tenants in the platform-defined <em>active</em>
 * state — typically excluding suspended / archived / deleted. Plugins
 * should treat the list as the universe to iterate; per-record tenant
 * isolation still happens via {@link BackgroundDataAccessor}'s
 * tenant-id parameter on each call.
 *
 * @since 2.5.0
 */
public interface BackgroundTenantAccessor {

    /**
     * @return non-null list of active tenant ids; empty when no tenants
     *         are active (e.g. fresh install before bootstrap).
     */
    List<Long> listActiveTenantIds();
}
