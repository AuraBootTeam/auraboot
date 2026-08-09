package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantSelectionControllerPolicyTest {

    @Mock private UserService userService;
    @Mock private TenantApplicationService tenantApplicationService;
    @Mock private TenantMemberService tenantMemberService;
    @Mock private TenantService tenantService;
    @Mock private RoleService roleService;
    @Mock private UserRoleService userRoleService;
    @Mock private JwtUtil jwtUtil;
    @Mock private SessionManagementService sessionManagementService;
    @Mock private I18nService i18nService;
    @Mock private I18nLocaleResolver i18nLocaleResolver;
    @Mock private SystemModeService systemModeService;

    @InjectMocks
    private TenantSelectionController controller;

    @Test
    void singleModeRejectsTenantCreationEvenWhenEndpointIsCalledDirectly() {
        User user = new User();
        user.setId(7L);
        when(userService.findByUserId(7L)).thenReturn(user);
        when(systemModeService.isTenantSelfProvisioningAllowed()).thenReturn(false);
        TenantSelectionRequest request = request("create");

        assertThatThrownBy(() -> controller.processTenantSelection(
                request, 7L, new MockHttpServletRequest()))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("Tenant self-provisioning is disabled");

        verify(tenantApplicationService, never()).createTenantForUser(request, user);
    }

    @Test
    void singleModeRejectsTenantJoinEvenWhenInviteCodeIsValid() {
        User user = new User();
        user.setId(8L);
        when(userService.findByUserId(8L)).thenReturn(user);
        when(systemModeService.isSingleTenant()).thenReturn(true);
        TenantSelectionRequest request = request("join");

        assertThatThrownBy(() -> controller.processTenantSelection(
                request, 8L, new MockHttpServletRequest()))
                .isInstanceOf(RootUnCheckedException.class)
                .hasMessageContaining("Tenant joining is disabled");

        verify(tenantApplicationService, never()).joinTenantByInviteCode(request, user);
    }

    @Test
    void multiModeWithSelfServiceStillDelegatesTenantCreation() {
        User user = new User();
        user.setId(9L);
        when(userService.findByUserId(9L)).thenReturn(user);
        when(systemModeService.isTenantSelfProvisioningAllowed()).thenReturn(true);
        TenantSelectionRequest request = request("create");
        TenantSelectionResponse response = new TenantSelectionResponse();
        response.setStatus("success");
        when(tenantApplicationService.createTenantForUser(request, user)).thenReturn(response);
        when(i18nLocaleResolver.resolveLocale(org.mockito.ArgumentMatchers.any())).thenReturn("en-US");

        controller.processTenantSelection(request, 9L, new MockHttpServletRequest());

        verify(tenantApplicationService).createTenantForUser(request, user);
    }

    private TenantSelectionRequest request(String action) {
        TenantSelectionRequest request = new TenantSelectionRequest();
        request.setAction(action);
        return request;
    }
}
