package com.auraboot.framework.saas.config.service;

import com.auraboot.framework.saas.constant.SystemMode;

public interface SystemModeService {
    SystemMode getMode();
    boolean isSingleTenant();
    boolean isMultiTenant();
    boolean isSetupComplete();
    Long getDefaultTenantId();
    boolean isRegistrationAllowed();
}
