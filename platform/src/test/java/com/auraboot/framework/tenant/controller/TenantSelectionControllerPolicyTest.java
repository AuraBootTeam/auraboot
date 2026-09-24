package com.auraboot.framework.tenant.controller;

import com.auraboot.framework.auth.service.SessionManagementService;
import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.auth.dto.SessionTokenContext;
import com.auraboot.framework.exception.RootUnCheckedException;
import com.auraboot.framework.i18n.service.I18nService;
import com.auraboot.framework.i18n.util.I18nLocaleResolver;
import com.auraboot.framework.rbac.service.RoleService;
import com.auraboot.framework.rbac.service.UserRoleService;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.tenant.dto.TenantSelectionRequest;
import com.auraboot.framework.tenant.dto.TenantSelectionResponse;
import com.auraboot.framework.tenant.dao.entity.Tenant;
import com.auraboot.framework.tenant.dao.entity.TenantMember;
import com.auraboot.framework.tenant.service.TenantApplicationService;
import com.auraboot.framework.tenant.service.TenantMemberService;
import com.auraboot.framework.tenant.service.TenantService;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import com.auraboot.framework.user.service.UserApplicationPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
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
    @Mock private UserApplicationPreferenceService userApplicationPreferenceService;

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

    @Test
    void selectingSchoolPreservesApplicationAndRotatesOnlyCurrentSession() {
        User user = new User();
        user.setId(10L);
        user.setPid("user-pid");
        user.setEmail("teacher@example.com");
        user.setSecurityVersion(4);
        when(userService.findByUserId(10L)).thenReturn(user);
        TenantMember member = new TenantMember();
        member.setId(33L);
        member.setStatus("active");
        when(tenantMemberService.findByTenantIdAndUserId(22L, 10L)).thenReturn(member);
        Tenant tenant = new Tenant();
        tenant.setId(22L);
        tenant.setPid("tenant-public-pid");
        tenant.setName("school-22");
        tenant.setDisplayName("向阳小学");
        when(tenantService.getById(22L)).thenReturn(tenant);
        when(jwtUtil.extractApplicationId("old-token")).thenReturn(5L);
        when(jwtUtil.extractLoginChannelId("old-token")).thenReturn(6L);
        when(jwtUtil.generateTokenWithContext(any(), eq("user-pid"), any())).thenReturn("new-token");
        when(jwtUtil.inheritSessionLifetime("new-token", "old-token")).thenReturn("rotated-token");
        when(i18nLocaleResolver.resolveLocale(any())).thenReturn("zh-CN");

        TenantSelectionRequest request = request("select");
        request.setTenantId(22L);
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("Authorization", "Bearer old-token");
        http.setRemoteAddr("127.0.0.1");

        var result = controller.processTenantSelection(request, 10L, http);

        assertThat(result.getData().getJwt()).isEqualTo("rotated-token");
        var context = org.mockito.ArgumentCaptor.forClass(SessionTokenContext.class);
        verify(jwtUtil).generateTokenWithContext(any(), eq("user-pid"), context.capture());
        assertThat(context.getValue().applicationId()).isEqualTo(5L);
        assertThat(context.getValue().loginChannelId()).isEqualTo(6L);
        verify(userApplicationPreferenceService).setLastTenant(10L, 5L, "tenant-public-pid");
        verify(sessionManagementService).createSession(10L, "rotated-token", "127.0.0.1", "space-switch");
        verify(sessionManagementService).revokeSessionByToken("old-token");
    }

    private TenantSelectionRequest request(String action) {
        TenantSelectionRequest request = new TenantSelectionRequest();
        request.setAction(action);
        return request;
    }
}
