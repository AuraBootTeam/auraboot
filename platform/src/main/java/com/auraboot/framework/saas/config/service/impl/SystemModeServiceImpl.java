package com.auraboot.framework.saas.config.service.impl;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SystemModeServiceImpl implements SystemModeService {

    private final SystemConfigService systemConfigService;

    @Override
    public SystemMode getMode() {
        String modeStr = systemConfigService.getOrDefault(
            SystemConfigKeys.SYSTEM_MODE, SystemMode.SINGLE.getCode());
        return SystemMode.fromCode(modeStr);
    }

    @Override
    public boolean isSingleTenant() {
        return getMode() == SystemMode.SINGLE;
    }

    @Override
    public boolean isMultiTenant() {
        SystemMode mode = getMode();
        return mode == SystemMode.MULTI || mode == SystemMode.HYBRID;
    }

    @Override
    public boolean isSetupComplete() {
        return systemConfigService.isInitialized();
    }

    @Override
    public Long getDefaultTenantId() {
        return systemConfigService.getLong(SystemConfigKeys.SYSTEM_DEFAULT_TENANT_ID, 0L);
    }

    @Override
    public boolean isRegistrationAllowed() {
        if (isSingleTenant()) {
            return systemConfigService.getBoolean(
                SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION, false);
        }
        return systemConfigService.getBoolean(
            SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION, true);
    }
}
