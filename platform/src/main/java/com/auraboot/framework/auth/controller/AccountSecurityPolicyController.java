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
        return ApiResponse.success(AccountSecurityPolicyResponse.builder()
                .mode(selfServiceEnabled ? "self_service" : "admin_managed")
                .publicRegistrationEnabled(false)
                .selfServicePasswordEnabled(selfServiceEnabled)
                .adminManagedPasswordEnabled(true)
                .mustChangePasswordAfterAdminReset(false)
                .password(PasswordPolicySummary.builder()
                        .minLength(minLength)
                        .maxLength(maxLength)
                        .requireUppercase(requireUppercase)
                        .requireLowercase(requireLowercase)
                        .requireDigit(requireDigit)
                        .requireSpecial(requireSpecial)
                        .historyCount(historyCount)
                        .expiryDays(expiryDays)
                        .resetTokenExpiryMinutes(resetTokenExpiryMinutes)
                        .build())
                .lockout(LockoutPolicySummary.builder()
                        .maxAttempts(lockoutMaxAttempts)
                        .durationMinutes(lockoutDurationMinutes)
                        .build())
                .notes(new String[] {
                        "Password complexity, history, expiry, lockout, and reset-token lifetime are deployment-level rules.",
                        "Public registration is disabled by default. Member onboarding uses administrator-controlled paths.",
                        "Tenant-scoped behavior switches are not editable in this read-only delivery view."
                })
                .build());
    }

    @Data
    @Builder
    public static class AccountSecurityPolicyResponse {
        private String mode;
        private boolean publicRegistrationEnabled;
        private boolean selfServicePasswordEnabled;
        private boolean adminManagedPasswordEnabled;
        private boolean mustChangePasswordAfterAdminReset;
        private PasswordPolicySummary password;
        private LockoutPolicySummary lockout;
        private String[] notes;
    }

    @Data
    @Builder
    public static class PasswordPolicySummary {
        private int minLength;
        private int maxLength;
        private boolean requireUppercase;
        private boolean requireLowercase;
        private boolean requireDigit;
        private boolean requireSpecial;
        private int historyCount;
        private int expiryDays;
        private int resetTokenExpiryMinutes;
    }

    @Data
    @Builder
    public static class LockoutPolicySummary {
        private int maxAttempts;
        private int durationMinutes;
    }
}
