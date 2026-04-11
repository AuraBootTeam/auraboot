package com.auraboot.framework.entitlement.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default no-op implementation of {@link LicenseQuotaProvider}.
 * All quotas are unlimited and all features are enabled.
 * Active when no real license module is present (dev / open-source mode).
 *
 * @since 7.1.0
 */
@Component
@ConditionalOnMissingBean(value = LicenseQuotaProvider.class, ignored = NoOpLicenseQuotaProvider.class)
public class NoOpLicenseQuotaProvider implements LicenseQuotaProvider {

    @Override
    public int getMaxTenants() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMaxUsersPerTenant() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getMaxStorageGb() {
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean hasFeature(String featureKey) {
        return true;
    }

    @Override
    public String getCurrentEdition() {
        return "enterprise";
    }
}
