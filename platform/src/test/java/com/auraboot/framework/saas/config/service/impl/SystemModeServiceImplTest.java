package com.auraboot.framework.saas.config.service.impl;

import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.PartyCreationPolicy;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.TenantProvisioningPolicy;
import com.auraboot.framework.saas.constant.UserRegistrationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SystemModeServiceImplTest {

    @Mock
    private SystemConfigService config;

    private SystemModeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SystemModeServiceImpl(config);
    }

    @Test
    void legacyRegistrationBooleanRemainsCompatible() {
        when(config.get(SystemConfigKeys.SYSTEM_USER_REGISTRATION_POLICY)).thenReturn(Optional.empty());
        when(config.getBoolean(SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION, false)).thenReturn(true);

        assertThat(service.getUserRegistrationPolicy()).isEqualTo(UserRegistrationPolicy.OPEN);
        assertThat(service.isRegistrationAllowed()).isTrue();
    }

    @Test
    void explicitRegistrationPolicyOverridesLegacyBoolean() {
        when(config.get(SystemConfigKeys.SYSTEM_USER_REGISTRATION_POLICY))
                .thenReturn(Optional.of("invite-only"));

        assertThat(service.getUserRegistrationPolicy()).isEqualTo(UserRegistrationPolicy.INVITE_ONLY);
        assertThat(service.isRegistrationAllowed()).isFalse();
    }

    @Test
    void singleModeForcesTenantProvisioningDisabled() {
        when(config.getOrDefault(SystemConfigKeys.SYSTEM_MODE, "single")).thenReturn("single");

        assertThat(service.getTenantProvisioningPolicy()).isEqualTo(TenantProvisioningPolicy.DISABLED);
        assertThat(service.isTenantSelfProvisioningAllowed()).isFalse();
    }

    @Test
    void multiModeKeepsSelfServiceProvisioningByDefault() {
        when(config.getOrDefault(SystemConfigKeys.SYSTEM_MODE, "single")).thenReturn("multi");
        when(config.getOrDefault(SystemConfigKeys.SYSTEM_TENANT_PROVISIONING_POLICY, "self_service"))
                .thenReturn("self_service");

        assertThat(service.getTenantProvisioningPolicy()).isEqualTo(TenantProvisioningPolicy.SELF_SERVICE);
        assertThat(service.isTenantSelfProvisioningAllowed()).isTrue();
    }

    @Test
    void partyCreationDefaultsToApprovalRequired() {
        when(config.getOrDefault(SystemConfigKeys.SYSTEM_PARTY_CREATION_POLICY, "approval_required"))
                .thenReturn("approval_required");

        assertThat(service.getPartyCreationPolicy()).isEqualTo(PartyCreationPolicy.APPROVAL_REQUIRED);
    }
}
