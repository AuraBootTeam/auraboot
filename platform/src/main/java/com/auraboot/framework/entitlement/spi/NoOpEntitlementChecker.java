package com.auraboot.framework.entitlement.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default no-op implementation of {@link EntitlementChecker}.
 * All checks pass when no real entitlement module is present (open-source / dev mode).
 */
@Component
@ConditionalOnMissingBean(value = EntitlementChecker.class, ignored = NoOpEntitlementChecker.class)
public class NoOpEntitlementChecker implements EntitlementChecker {
    @Override public boolean isEnabled() { return false; }
    @Override public boolean isPluginActive(String pluginId) { return true; }
    @Override public boolean isPluginActive(Long tenantId, String pluginId) { return true; }
    @Override public boolean hasFeature(String pluginId, String featureKey) { return true; }
    @Override public boolean hasFeature(Long tenantId, String pluginId, String featureKey) { return true; }
}
