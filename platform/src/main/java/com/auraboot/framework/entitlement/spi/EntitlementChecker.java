package com.auraboot.framework.entitlement.spi;

import java.util.Optional;

/**
 * SPI interface for entitlement verification.
 * Core provides NoOp default; enterprise provides real implementation.
 */
public interface EntitlementChecker {
    boolean isEnabled();
    boolean isPluginActive(String pluginId);
    boolean isPluginActive(Long tenantId, String pluginId);
    boolean hasFeature(String pluginId, String featureKey);
    boolean hasFeature(Long tenantId, String pluginId, String featureKey);

    /**
     * Get a warning message for the given tenant + plugin entitlement.
     * Returns a "code: message" string if a warning is active (e.g. expiring soon, grace period),
     * or empty if no warning is needed.
     *
     * <p>Used by the interceptor to set the X-Entitlement-Warning response header.</p>
     */
    default Optional<String> getWarningHeader(Long tenantId, String pluginId) {
        return Optional.empty();
    }
}
