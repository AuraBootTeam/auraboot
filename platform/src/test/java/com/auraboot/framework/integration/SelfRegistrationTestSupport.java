package com.auraboot.framework.integration;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;

/** Test-only fixture for suites that exercise public user registration. */
public final class SelfRegistrationTestSupport {

    private SelfRegistrationTestSupport() {
    }

    public static void setAllowed(SystemConfigService systemConfigService, boolean allowed) {
        // The modern policy is authoritative when present in a fresh Flyway/seed database. Keep
        // the legacy boolean aligned because upgrade-path tests can still exercise that fallback.
        systemConfigService.initialize(
                SystemConfigKeys.SYSTEM_USER_REGISTRATION_POLICY,
                allowed ? "open" : "closed",
                "system",
                "string",
                "User registration policy",
                false);
        systemConfigService.initialize(
                SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION,
                Boolean.toString(allowed),
                "system",
                "boolean",
                "Allow self-registration",
                false);
        systemConfigService.evictCache();
    }

    public static void configureSingleTenantAdmission(
            SystemConfigService systemConfigService,
            long defaultTenantId
    ) {
        if (defaultTenantId <= 0) {
            throw new IllegalArgumentException("A persisted business tenant is required");
        }
        systemConfigService.initialize(
                SystemConfigKeys.SYSTEM_MODE,
                SystemMode.SINGLE.getCode(),
                "system",
                "string",
                "System mode (single/multi/hybrid)",
                true);
        systemConfigService.initialize(
                SystemConfigKeys.SYSTEM_DEFAULT_TENANT_ID,
                Long.toString(defaultTenantId),
                "system",
                "string",
                "Default tenant ID",
                true);
        systemConfigService.evictCache();
    }
}
