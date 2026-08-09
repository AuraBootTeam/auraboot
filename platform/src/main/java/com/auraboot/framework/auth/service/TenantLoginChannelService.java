package com.auraboot.framework.auth.service;

import com.auraboot.framework.auth.dto.ChannelUpdateRequest;
import com.auraboot.framework.auth.dto.LoginChannelOption;
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

    /** Resolve auth methods from the application/channel registry, with legacy tenant toggles as fallback. */
    default List<String> getEnabledChannels(Long tenantId, String applicationCode, String channelCode) {
        return getEnabledChannels(tenantId);
    }

    /**
     * Resolve enabled methods together with the minimum routing metadata required by login clients.
     * Implementations must never include provider configuration or secret references.
     */
    default List<LoginChannelOption> getEnabledChannelOptions(
            Long tenantId, String applicationCode, String channelCode) {
        return getEnabledChannels(tenantId, applicationCode, channelCode).stream()
                .map(TenantLoginChannelService::legacyOption)
                .toList();
    }

    private static LoginChannelOption legacyOption(String rawCode) {
        String code = rawCode == null ? "" : rawCode.toLowerCase();
        String kind = switch (code) {
            case "email_password", "password" -> "password";
            case "sms", "email_code" -> "otp";
            case "ldap" -> "ldap";
            default -> "oauth";
        };
        return new LoginChannelOption(code, kind, rawCode, null);
    }

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
