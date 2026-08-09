package com.auraboot.framework.saas.config.service.impl;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.saas.constant.PartyCreationPolicy;
import com.auraboot.framework.saas.constant.TenantProvisioningPolicy;
import com.auraboot.framework.saas.constant.UserRegistrationPolicy;
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
    public UserRegistrationPolicy getUserRegistrationPolicy() {
        return systemConfigService.get(SystemConfigKeys.SYSTEM_USER_REGISTRATION_POLICY)
                .map(UserRegistrationPolicy::fromConfig)
                .orElseGet(() -> systemConfigService.getBoolean(
                        SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION, false)
                        ? UserRegistrationPolicy.OPEN
                        : UserRegistrationPolicy.CLOSED);
    }

    @Override
    public TenantProvisioningPolicy getTenantProvisioningPolicy() {
        if (isSingleTenant()) {
            return TenantProvisioningPolicy.DISABLED;
        }
        return TenantProvisioningPolicy.fromConfig(systemConfigService.getOrDefault(
                SystemConfigKeys.SYSTEM_TENANT_PROVISIONING_POLICY,
                TenantProvisioningPolicy.SELF_SERVICE.getCode()));
    }

    @Override
    public PartyCreationPolicy getPartyCreationPolicy() {
        return PartyCreationPolicy.fromConfig(systemConfigService.getOrDefault(
                SystemConfigKeys.SYSTEM_PARTY_CREATION_POLICY,
                PartyCreationPolicy.APPROVAL_REQUIRED.getCode()));
    }

    @Override
    public boolean isRegistrationAllowed() {
        return getUserRegistrationPolicy() == UserRegistrationPolicy.OPEN;
    }

    @Override
    public boolean isTenantSelfProvisioningAllowed() {
        return getTenantProvisioningPolicy() == TenantProvisioningPolicy.SELF_SERVICE;
    }

    @Override
    public boolean isPartyInvitationEnabled() {
        return systemConfigService.getBoolean(SystemConfigKeys.SYSTEM_PARTY_INVITATION_ENABLED, true);
    }

    @Override
    public boolean isActorSwitchEnabled() {
        return systemConfigService.getBoolean(SystemConfigKeys.SYSTEM_ACTOR_SWITCH_ENABLED, true);
    }
}
