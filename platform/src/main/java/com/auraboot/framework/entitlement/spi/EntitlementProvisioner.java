package com.auraboot.framework.entitlement.spi;

/**
 * SPI interface for entitlement provisioning (issuance / lifecycle management).
 * Core provides NoOp default; website provides real implementation via EntitlementService.
 *
 * <p>Used by marketplace install service to create/revoke entitlements when plugins
 * are installed or uninstalled.</p>
 *
 * @author AuraBoot Team
 * @since 3.0.0
 */
public interface EntitlementProvisioner {

    /**
     * Create a permanent free-tier entitlement for the given tenant + plugin.
     */
    void createFreeEntitlement(Long tenantId, String pluginId);

    /**
     * Grant a free trial for the given tenant + plugin.
     */
    void grantTrial(Long tenantId, String pluginId);

    /**
     * Disable all active entitlements for the given tenant + plugin.
     */
    void disableEntitlement(Long tenantId, String pluginId, String reason);

    /**
     * Check whether the given tenant is eligible for a trial of the given plugin.
     */
    boolean isEligibleForTrial(Long tenantId, String pluginId);
}
