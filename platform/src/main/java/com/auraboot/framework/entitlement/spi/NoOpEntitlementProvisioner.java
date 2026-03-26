package com.auraboot.framework.entitlement.spi;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default no-op implementation of {@link EntitlementProvisioner}.
 * All operations are logged but have no effect when no real entitlement module is present.
 */
@Slf4j
@Component
@ConditionalOnMissingBean(value = EntitlementProvisioner.class, ignored = NoOpEntitlementProvisioner.class)
public class NoOpEntitlementProvisioner implements EntitlementProvisioner {

    @Override
    public void createFreeEntitlement(Long tenantId, String pluginId) {
        log.debug("NoOp: createFreeEntitlement tenantId={} pluginId={} (entitlement system not active)", tenantId, pluginId);
    }

    @Override
    public void grantTrial(Long tenantId, String pluginId) {
        log.debug("NoOp: grantTrial tenantId={} pluginId={} (entitlement system not active)", tenantId, pluginId);
    }

    @Override
    public void disableEntitlement(Long tenantId, String pluginId, String reason) {
        log.debug("NoOp: disableEntitlement tenantId={} pluginId={} reason={} (entitlement system not active)", tenantId, pluginId, reason);
    }

    @Override
    public boolean isEligibleForTrial(Long tenantId, String pluginId) {
        return true; // always eligible when entitlement system is not active
    }
}
