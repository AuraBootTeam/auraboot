package com.auraboot.framework.auth.service.impl;

import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdmissionServiceImplTest {

    @Mock
    private SystemModeService systemModeService;
    @Mock
    private TenantMemberService tenantMemberService;
    @Mock
    private TenantService tenantService;

    private UserAdmissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new UserAdmissionServiceImpl(systemModeService, tenantMemberService, tenantService);
    }

    @Test
    void closedRegistrationIsRejectedBeforeIdentityCreation() {
        when(systemModeService.isRegistrationAllowed()).thenReturn(false);

        assertThatThrownBy(service::assertSelfRegistrationAllowed)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Self-registration is disabled");
    }

    @Test
    void singleModeAdmitsNewIdentityToServerOwnedBusinessTenant() {
        User user = user(7L);
        Tenant tenant = new Tenant();
        tenant.setId(2L);
        tenant.setName("Default Business");
        TenantMember created = new TenantMember();
        created.setId(17L);

        when(systemModeService.isSingleTenant()).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(2L);
        when(tenantService.getById(2L)).thenReturn(tenant);
        when(tenantMemberService.findByTenantIdAndUserId(2L, 7L)).thenReturn(null);
        when(tenantMemberService.addMember(7L, 2L, "active")).thenReturn(created);

        assertThat(service.admitSelfRegisteredUser(user)).isSameAs(created);
    }

    @Test
    void singleModeReusesExistingMembership() {
        User user = user(8L);
        Tenant tenant = new Tenant();
        tenant.setId(2L);
        tenant.setName("Default Business");
        TenantMember existing = new TenantMember();
        existing.setId(18L);

        when(systemModeService.isSingleTenant()).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(2L);
        when(tenantService.getById(2L)).thenReturn(tenant);
        when(tenantMemberService.findByTenantIdAndUserId(2L, 8L)).thenReturn(existing);

        assertThat(service.admitSelfRegisteredUser(user)).isSameAs(existing);
        verify(tenantMemberService, never()).addMember(8L, 2L, "active");
    }

    @Test
    void multiModeLeavesIdentityTenantlessForExplicitSelectionFlow() {
        User user = user(9L);
        when(systemModeService.isSingleTenant()).thenReturn(false);

        assertThat(service.admitSelfRegisteredUser(user)).isNull();
        verify(tenantMemberService, never()).addMember(9L, 2L, "active");
    }

    @Test
    void systemTenantCanNeverBeTheSingleBusinessDefault() {
        User user = user(10L);
        Tenant system = new Tenant();
        system.setId(1L);
        system.setName("System");

        when(systemModeService.isSingleTenant()).thenReturn(true);
        when(systemModeService.getDefaultTenantId()).thenReturn(1L);
        when(tenantService.getById(1L)).thenReturn(system);

        assertThatThrownBy(() -> service.admitSelfRegisteredUser(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default business tenant");
    }

    private User user(Long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
