package com.auraboot.framework.auth.dto;

import com.auraboot.framework.saas.bootstrap.dto.BootstrapRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveRequestToStringTest {

    @Test
    void excludesAuthenticationSecretsFromGeneratedToString() {
        AuthenticationRequest login = new AuthenticationRequest();
        login.setPassword("login-secret");

        AuthStrategyRequest strategy = new AuthStrategyRequest();
        strategy.setPassword("strategy-secret");
        strategy.setCode("otp-secret");

        RegisterRequest register = new RegisterRequest();
        register.setPassword("register-secret");

        ResetPasswordRequest reset = new ResetPasswordRequest();
        reset.setToken("reset-token-secret");
        reset.setNewPassword("reset-password-secret");

        BootstrapRequest bootstrap = new BootstrapRequest();
        bootstrap.setAdminPassword("bootstrap-secret");

        assertThat(login.toString()).doesNotContain("login-secret");
        assertThat(strategy.toString())
                .doesNotContain("strategy-secret", "otp-secret");
        assertThat(register.toString()).doesNotContain("register-secret");
        assertThat(reset.toString())
                .doesNotContain("reset-token-secret", "reset-password-secret");
        assertThat(bootstrap.toString()).doesNotContain("bootstrap-secret");
    }
}
