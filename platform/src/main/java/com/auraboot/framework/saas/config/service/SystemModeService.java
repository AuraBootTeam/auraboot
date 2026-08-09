package com.auraboot.framework.saas.config.service;

import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.saas.constant.PartyCreationPolicy;
import com.auraboot.framework.saas.constant.TenantProvisioningPolicy;
import com.auraboot.framework.saas.constant.UserRegistrationPolicy;

public interface SystemModeService {
    SystemMode getMode();
    boolean isSingleTenant();
    boolean isMultiTenant();
    boolean isSetupComplete();
    Long getDefaultTenantId();
    UserRegistrationPolicy getUserRegistrationPolicy();
    TenantProvisioningPolicy getTenantProvisioningPolicy();
    PartyCreationPolicy getPartyCreationPolicy();
    boolean isRegistrationAllowed();
    boolean isTenantSelfProvisioningAllowed();
    boolean isPartyInvitationEnabled();
    boolean isActorSwitchEnabled();
}
