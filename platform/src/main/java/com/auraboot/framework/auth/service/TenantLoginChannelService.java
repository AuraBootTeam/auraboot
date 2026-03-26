package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.ChannelUpdateRequest;
import com.auraboot.framework.auth.entity.TenantLoginChannel;

import java.util.List;

/**
 * Service for managing per-tenant login channel configuration.
 * <p>
 * Controls which authentication methods (email+password, SMS, email code, etc.)
 * are available for each tenant's login page.
 *
 * @since 7.0.0
 */
public interface TenantLoginChannelService {

    /**
     * Get the sorted list of enabled channel codes for a tenant.
     * If the tenant has no channel configuration, returns default channels.
     *
     * @param tenantId tenant ID (may be null for default channels)
     * @return sorted list of enabled channel codes
     */
    List<String> getEnabledChannels(Long tenantId);

    /**
     * List all channel configurations for a tenant (enabled and disabled).
     *
     * @param tenantId tenant ID
     * @return all channel records for the tenant
     */
    List<TenantLoginChannel> listChannels(Long tenantId);

    /**
     * Batch update channel enabled state and sort order.
     *
     * @param tenantId tenant ID
     * @param updates  list of channel updates
     */
    void updateChannels(Long tenantId, List<ChannelUpdateRequest> updates);

    /**
     * Initialize default login channels for a newly created tenant.
     * By default, only EMAIL_PASSWORD is enabled.
     *
     * @param tenantId the new tenant's ID
     */
    void initDefaultChannels(Long tenantId);
}
