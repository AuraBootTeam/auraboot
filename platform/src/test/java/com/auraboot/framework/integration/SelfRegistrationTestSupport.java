package com.auraboot.framework.integration;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;

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
}
