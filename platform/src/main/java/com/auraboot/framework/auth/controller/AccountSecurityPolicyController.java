package com.auraboot.framework.auth.controller;

import com.auraboot.framework.common.dto.ApiResponse;
import com.auraboot.framework.permission.annotation.RequirePermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Builder;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/account-security-policy")
@Tag(name = "Account Security Policy", description = "Read-only account and password policy summary")
public class AccountSecurityPolicyController {

    @Value("${security.password.min-length:8}")
    private int minLength;

    @Value("${security.password.max-length:128}")
    private int maxLength;

    @Value("${security.password.require-uppercase:false}")
    private boolean requireUppercase;

    @Value("${security.password.require-lowercase:true}")
    private boolean requireLowercase;

    @Value("${security.password.require-digit:true}")
    private boolean requireDigit;

    @Value("${security.password.require-special:false}")
    private boolean requireSpecial;

    @Value("${security.password.history-count:5}")
    private int historyCount;

    @Value("${security.password.expiry-days:90}")
    private int expiryDays;

    @Value("${security.password.reset-token-expiry-minutes:30}")
    private int resetTokenExpiryMinutes;

    @Value("${security.password.self-service-enabled:false}")
    private boolean selfServiceEnabled;

    @Value("${security.lockout.max-attempts:5}")
    private int lockoutMaxAttempts;

    @Value("${security.lockout.duration-minutes:30}")
    private int lockoutDurationMinutes;

    @GetMapping
    @RequirePermission("system_management")
    public ApiResponse<AccountSecurityPolicyResponse> getPolicy() {
        String mode = selfServiceEnabled ? "self_service" : "admin_managed";
        String[] notes = new String[] {
                "密码复杂度、历史密码、过期时间、登录锁定和重置令牌有效期由部署级配置统一控制。",
                "默认关闭公开注册，成员开通使用管理员受控流程。",
                "当前页面为只读交付视图，不提供租户级策略编辑。"
        };
        return ApiResponse.success(AccountSecurityPolicyResponse.builder()
                .mode(mode)
                .modeDisplay("self_service".equals(mode) ? "允许自助找回" : "管理员托管")
                .publicRegistrationEnabled(false)
                .publicRegistrationDisplay(enabledText(false))
                .selfServicePasswordEnabled(selfServiceEnabled)
                .selfServicePasswordDisplay(enabledText(selfServiceEnabled))
                .adminManagedPasswordEnabled(true)
                .adminManagedPasswordDisplay(enabledText(true))
                .mustChangePasswordAfterAdminReset(false)
                .mustChangePasswordAfterAdminResetDisplay(enabledText(false))
                .password(PasswordPolicySummary.builder()
                        .minLength(minLength)
                        .maxLength(maxLength)
                        .lengthDisplay(minLength + "-" + maxLength + " 个字符")
                        .requireUppercase(requireUppercase)
                        .requireUppercaseDisplay(enabledText(requireUppercase))
                        .requireLowercase(requireLowercase)
                        .requireLowercaseDisplay(enabledText(requireLowercase))
                        .requireDigit(requireDigit)
                        .requireDigitDisplay(enabledText(requireDigit))
                        .requireSpecial(requireSpecial)
                        .requireSpecialDisplay(enabledText(requireSpecial))
                        .historyCount(historyCount)
                        .historyCountDisplay("最近 " + historyCount + " 次不可复用")
                        .expiryDays(expiryDays)
                        .expiryDaysDisplay(expiryDays + " 天")
                        .resetTokenExpiryMinutes(resetTokenExpiryMinutes)
                        .resetTokenExpiryDisplay(resetTokenExpiryMinutes + " 分钟")
                        .recoveryModeDisplay(selfServiceEnabled ? "支持令牌重置" : "联系管理员")
                        .build())
                .lockout(LockoutPolicySummary.builder()
                        .maxAttempts(lockoutMaxAttempts)
                        .maxAttemptsDisplay(lockoutMaxAttempts + " 次失败")
                        .durationMinutes(lockoutDurationMinutes)
                        .durationDisplay(lockoutDurationMinutes + " 分钟")
                        .build())
                .notes(notes)
                .notesText(String.join("\n", notes))
                .build());
    }

    private static String enabledText(boolean enabled) {
        return enabled ? "已启用" : "已停用";
    }

    @Data
    @Builder
    public static class AccountSecurityPolicyResponse {
        private String mode;
        private String modeDisplay;
        private boolean publicRegistrationEnabled;
        private String publicRegistrationDisplay;
        private boolean selfServicePasswordEnabled;
        private String selfServicePasswordDisplay;
        private boolean adminManagedPasswordEnabled;
        private String adminManagedPasswordDisplay;
        private boolean mustChangePasswordAfterAdminReset;
        private String mustChangePasswordAfterAdminResetDisplay;
        private PasswordPolicySummary password;
        private LockoutPolicySummary lockout;
        private String[] notes;
        private String notesText;
    }

    @Data
    @Builder
    public static class PasswordPolicySummary {
        private int minLength;
        private int maxLength;
        private String lengthDisplay;
        private boolean requireUppercase;
        private String requireUppercaseDisplay;
        private boolean requireLowercase;
        private String requireLowercaseDisplay;
        private boolean requireDigit;
        private String requireDigitDisplay;
        private boolean requireSpecial;
        private String requireSpecialDisplay;
        private int historyCount;
        private String historyCountDisplay;
        private int expiryDays;
        private String expiryDaysDisplay;
        private int resetTokenExpiryMinutes;
        private String resetTokenExpiryDisplay;
        private String recoveryModeDisplay;
    }

    @Data
    @Builder
    public static class LockoutPolicySummary {
        private int maxAttempts;
        private String maxAttemptsDisplay;
        private int durationMinutes;
        private String durationDisplay;
    }
}
