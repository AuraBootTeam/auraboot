package com.auraboot.framework.auth.controller;

import com.auraboot.framework.auth.dto.RegisterRequest;
import com.auraboot.framework.auth.dto.ForgotPasswordRequest;
import com.auraboot.framework.auth.dto.ResetPasswordRequest;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.LoginRateLimiter;
import com.auraboot.framework.auth.service.PasswordManagementService;
import com.auraboot.framework.auth.service.SessionRenewalService;
import com.auraboot.framework.auth.service.UserInfoService;
import com.auraboot.framework.common.constant.ResponseCode;
import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.saas.config.service.SystemModeService;
import com.auraboot.framework.saas.constant.PartyCreationPolicy;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.saas.constant.TenantProvisioningPolicy;
import com.auraboot.framework.saas.constant.UserRegistrationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthControllerSelfServicePasswordTest {

    @Mock
    private AuthService authService;
    @Mock
    private UserInfoService userInfoService;
    @Mock
    private PasswordManagementService passwordManagementService;
    @Mock
    private LoginRateLimiter loginRateLimiter;
    @Mock
    private SessionRenewalService sessionRenewalService;
    @Mock
    private SystemModeService systemModeService;

    @Test
    void forgotPassword_returnsForbiddenWhenSelfServiceDisabled() {
        AuthController controller = controller(false);
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("user@example.com");

        ApiResponse<Void> response = controller.forgotPassword(request, new MockHttpServletRequest());

        assertThat(response.getCode()).isEqualTo(ResponseCode.FORBIDDEN.getCode());
        verify(passwordManagementService, never()).initiatePasswordReset("user@example.com");
    }

    @Test
    void resetPassword_returnsForbiddenWhenSelfServiceDisabled() {
        AuthController controller = controller(false);
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setToken("token");
        request.setNewPassword("jjzz@1234");

        ApiResponse<Void> response = controller.resetPassword(request, new MockHttpServletRequest());

        assertThat(response.getCode()).isEqualTo(ResponseCode.FORBIDDEN.getCode());
        verify(passwordManagementService, never()).resetPasswordWithToken("token", "jjzz@1234");
    }

    @Test
    void register_returnsErrorWhenPublicRegistrationDisabled() {
        AuthController controller = controller(false);
        ReflectionTestUtils.setField(controller, "systemModeService", systemModeService);
        when(systemModeService.isRegistrationAllowed()).thenReturn(false);
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new-user@example.com");
        request.setPassword("StrongPass1!");
        request.setDisplayName("New User");

        ApiResponse<?> response = controller.register(request);

        assertThat(response.getCode()).isEqualTo(ResponseCode.FORBIDDEN.getCode());
        assertThat(response.getMessage()).contains("Self-registration is disabled");
        verify(authService, never()).register(request);
    }

    @Test
    void accessPolicy_exposesServerOwnedProductEntryPolicies() {
        AuthController controller = controller(false);
        ReflectionTestUtils.setField(controller, "systemModeService", systemModeService);
        when(systemModeService.getMode()).thenReturn(SystemMode.SINGLE);
        when(systemModeService.getUserRegistrationPolicy()).thenReturn(UserRegistrationPolicy.INVITE_ONLY);
        when(systemModeService.getTenantProvisioningPolicy()).thenReturn(TenantProvisioningPolicy.DISABLED);
        when(systemModeService.getPartyCreationPolicy()).thenReturn(PartyCreationPolicy.APPROVAL_REQUIRED);
        when(systemModeService.isPartyInvitationEnabled()).thenReturn(true);
        when(systemModeService.isActorSwitchEnabled()).thenReturn(true);

        ApiResponse<?> response = controller.getAccessPolicy();

        assertThat(response.getData()).hasFieldOrPropertyWithValue("deploymentMode", "single");
        assertThat(response.getData()).hasFieldOrPropertyWithValue("userRegistrationPolicy", "invite_only");
        assertThat(response.getData()).hasFieldOrPropertyWithValue("tenantProvisioningPolicy", "disabled");
        assertThat(response.getData()).hasFieldOrPropertyWithValue("partyCreationPolicy", "approval_required");
        assertThat(response.getData()).hasFieldOrPropertyWithValue("partyInvitationEnabled", true);
        assertThat(response.getData()).hasFieldOrPropertyWithValue("actorSwitchEnabled", true);
    }

    private AuthController controller(boolean selfServiceEnabled) {
        AuthController controller = new AuthController(
                authService,
                userInfoService,
                passwordManagementService,
                loginRateLimiter,
                sessionRenewalService);
        ReflectionTestUtils.setField(controller, "passwordSelfServiceEnabled", selfServiceEnabled);
        return controller;
    }
}
