package com.auraboot.framework.entitlement.spi;

/**
 * SPI for retrieving license quota and feature information.
 *
 * <p>Enterprise provides a real implementation backed by {@code LicenseVerificationService}.
 * Core and dev environments use the no-op default that allows everything.</p>
 *
 * @since 7.1.0
 */
public interface LicenseQuotaProvider {

    /** Maximum number of tenants allowed by the license. */
    int getMaxTenants();

    /** Maximum number of users per tenant allowed by the license. */
    int getMaxUsersPerTenant();

    /** Maximum storage in GB allowed by the license. */
    int getMaxStorageGb();

    /** Whether a specific feature is enabled in the current edition. */
    boolean hasFeature(String featureKey);

    /** The current edition name (e.g. "community", "pro", "enterprise"). */
    String getCurrentEdition();
}
