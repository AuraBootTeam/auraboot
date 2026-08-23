package com.auraboot.framework.integration;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;

/** Test-only fixture for suites that exercise public user registration. */
public final class SelfRegistrationTestSupport {

    private SelfRegistrationTestSupport() {
    }

    public static void setAllowed(SystemConfigService systemConfigService, boolean allowed) {
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
