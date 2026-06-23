package com.auraboot.framework.auth.strategy;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.auth.dto.AuthStrategyRequest;
import com.auraboot.framework.auth.dto.AuthenticationResponse;
import com.auraboot.framework.auth.entity.VerificationCode;
import com.auraboot.framework.auth.mapper.VerificationCodeMapper;
import com.auraboot.framework.auth.service.AuthService;
import com.auraboot.framework.auth.service.VerificationCodeService;
import com.auraboot.framework.exception.BusinessException;
import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.saas.config.service.SystemConfigService;
import com.auraboot.framework.saas.constant.SystemConfigKeys;
import com.auraboot.framework.saas.constant.SystemMode;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmailOtpRegistrationPolicyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private VerificationCodeService verificationCodeService;

    @Autowired
    private VerificationCodeMapper verificationCodeMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private UserService userService;

    @AfterEach
    void evictSystemConfigCache() {
        systemConfigService.evictCache();
        MetaContext.clear();
    }

    @Test
    void singleTenantDefaultDisabled_rejectsNewUserEmailOtpAutoRegistration() {
        configureRegistration(SystemMode.SINGLE, false);
        MetaContext.clear();
        String email = uniqueEmail("otp-single-disabled");

        sendLoginCode(email);
        String code = latestCode(email);

        assertThatThrownBy(() -> authenticateByEmailCode(email, code))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Self-registration is disabled");

        assertThat(userService.findByEmail(email)).isNull();
    }

    @Test
    void multiTenantExplicitAllowed_allowsNewUserEmailOtpAutoRegistration() {
        configureRegistration(SystemMode.MULTI, true);
        MetaContext.clear();
        String email = uniqueEmail("otp-multi-enabled");

        sendLoginCode(email);
        String code = latestCode(email);

        AuthenticationResponse response = authenticateByEmailCode(email, code);

        User created = userService.findByEmail(email);
        assertThat(created).isNotNull();
        assertThat(response.getUserId()).isEqualTo(created.getId());
        assertThat(response.getJwt()).isNotBlank();
    }

    private void configureRegistration(SystemMode mode, boolean allowSelfRegistration) {
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_MODE, mode.getCode(),
                "system", "string", "System mode (single/multi/hybrid)", true);
        systemConfigService.initialize(SystemConfigKeys.SYSTEM_ALLOW_SELF_REGISTRATION,
                Boolean.toString(allowSelfRegistration),
                "system", "boolean", "Allow self-registration", false);
        systemConfigService.evictCache();
    }

    private void sendLoginCode(String email) {
        verificationCodeService.sendCode(email, "login", "127.0.0.1");
    }

    private String latestCode(String email) {
        VerificationCode verificationCode = verificationCodeMapper.findLatestByTarget(email);
        assertThat(verificationCode).isNotNull();
        return verificationCode.getCode();
    }

    private AuthenticationResponse authenticateByEmailCode(String email, String code) {
        AuthStrategyRequest request = new AuthStrategyRequest();
        request.setChannelCode("email_code");
        request.setEmail(email);
        request.setCode(code);
        request.setIpAddress("127.0.0.1");
        request.setUserAgent("integration-test");
        return authService.authenticateByChannel(request);
    }

    private String uniqueEmail(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10)
                .toLowerCase(Locale.ROOT);
        return prefix + "-" + suffix + "@example.test";
    }
}
