package com.auraboot.framework.saas.constant;

import java.util.Locale;

/** Controls who may create a business tenant. */
public enum TenantProvisioningPolicy {
    DISABLED,
    PLATFORM_MANAGED,
    SELF_SERVICE;

    public static TenantProvisioningPolicy fromConfig(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Tenant provisioning policy is required");
        }
        return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }

    public String getCode() {
        return name().toLowerCase(Locale.ROOT);
    }
}
