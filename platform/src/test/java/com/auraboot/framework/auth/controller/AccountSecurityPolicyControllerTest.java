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
        assertThat(response.getData().getModeDisplay()).isEqualTo("管理员托管");
        assertThat(response.getData().getPublicRegistrationDisplay()).isEqualTo("已停用");
        assertThat(response.getData().getSelfServicePasswordDisplay()).isEqualTo("已停用");
        assertThat(response.getData().getAdminManagedPasswordDisplay()).isEqualTo("已启用");
        assertThat(response.getData().isPublicRegistrationEnabled()).isFalse();
        assertThat(response.getData().isSelfServicePasswordEnabled()).isFalse();
        assertThat(response.getData().isAdminManagedPasswordEnabled()).isTrue();
        assertThat(response.getData().getPassword().getMinLength()).isEqualTo(8);
        assertThat(response.getData().getPassword().getMaxLength()).isEqualTo(128);
        assertThat(response.getData().getPassword().getLengthDisplay()).isEqualTo("8-128 个字符");
        assertThat(response.getData().getPassword().getRequireLowercaseDisplay()).isEqualTo("已启用");
        assertThat(response.getData().getPassword().getRequireSpecialDisplay()).isEqualTo("已停用");
        assertThat(response.getData().getPassword().getHistoryCountDisplay()).isEqualTo("最近 5 次不可复用");
        assertThat(response.getData().getPassword().getExpiryDaysDisplay()).isEqualTo("90 天");
        assertThat(response.getData().getPassword().getResetTokenExpiryDisplay()).isEqualTo("30 分钟");
        assertThat(response.getData().getPassword().getRecoveryModeDisplay()).isEqualTo("联系管理员");
        assertThat(response.getData().getPassword().isRequireLowercase()).isTrue();
        assertThat(response.getData().getPassword().isRequireDigit()).isTrue();
        assertThat(response.getData().getPassword().getHistoryCount()).isEqualTo(5);
        assertThat(response.getData().getLockout().getMaxAttempts()).isEqualTo(5);
        assertThat(response.getData().getLockout().getDurationMinutes()).isEqualTo(30);
        assertThat(response.getData().getLockout().getMaxAttemptsDisplay()).isEqualTo("5 次失败");
        assertThat(response.getData().getLockout().getDurationDisplay()).isEqualTo("30 分钟");
        assertThat(response.getData().getNotesText()).contains("部署级配置");
    }
}
