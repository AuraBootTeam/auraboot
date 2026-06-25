package com.auraboot.framework.auth.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AccountSecurityPolicyControllerTest {

    @Test
    void getPolicy_returnsReadOnlyAdminManagedDefaults() {
        AccountSecurityPolicyController controller = new AccountSecurityPolicyController();
        ReflectionTestUtils.setField(controller, "minLength", 8);
        ReflectionTestUtils.setField(controller, "maxLength", 128);
        ReflectionTestUtils.setField(controller, "requireUppercase", false);
        ReflectionTestUtils.setField(controller, "requireLowercase", true);
        ReflectionTestUtils.setField(controller, "requireDigit", true);
        ReflectionTestUtils.setField(controller, "requireSpecial", false);
        ReflectionTestUtils.setField(controller, "historyCount", 5);
        ReflectionTestUtils.setField(controller, "expiryDays", 90);
        ReflectionTestUtils.setField(controller, "resetTokenExpiryMinutes", 30);
        ReflectionTestUtils.setField(controller, "selfServiceEnabled", false);
        ReflectionTestUtils.setField(controller, "lockoutMaxAttempts", 5);
        ReflectionTestUtils.setField(controller, "lockoutDurationMinutes", 30);

        ApiResponse<AccountSecurityPolicyController.AccountSecurityPolicyResponse> response =
                controller.getPolicy();

        assertThat(response.getData().getMode()).isEqualTo("admin_managed");
        assertThat(response.getData().isPublicRegistrationEnabled()).isFalse();
        assertThat(response.getData().isSelfServicePasswordEnabled()).isFalse();
        assertThat(response.getData().isAdminManagedPasswordEnabled()).isTrue();
        assertThat(response.getData().getPassword().getMinLength()).isEqualTo(8);
        assertThat(response.getData().getPassword().getMaxLength()).isEqualTo(128);
        assertThat(response.getData().getPassword().isRequireLowercase()).isTrue();
        assertThat(response.getData().getPassword().isRequireDigit()).isTrue();
        assertThat(response.getData().getPassword().getHistoryCount()).isEqualTo(5);
        assertThat(response.getData().getLockout().getMaxAttempts()).isEqualTo(5);
        assertThat(response.getData().getLockout().getDurationMinutes()).isEqualTo(30);
    }
}
