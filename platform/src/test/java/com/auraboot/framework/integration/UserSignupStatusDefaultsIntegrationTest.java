package com.auraboot.framework.integration;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.auth.service.UnifiedUserDetailsService;
import com.auraboot.framework.auth.dto.CustomUserDetails;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback
class UserSignupStatusDefaultsIntegrationTest {

    @Autowired
    private UserService userService;
    @Autowired
    private UnifiedUserDetailsService userDetailsService;

    @Test
    @DisplayName("signUp should create an enabled and non-locked account by default")
    void signUpShouldUseEnabledAccountDefaults() {
        String email = "signup-defaults-" + System.currentTimeMillis() + "@test.local";
        userService.signUp(email, "TestPass123!");

        User created = userService.findByEmail(email);
        assertThat(created).isNotNull();
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.isAccountNonExpired()).isTrue();
        assertThat(created.isAccountNonLocked()).isTrue();
        assertThat(created.isCredentialsNonExpired()).isTrue();
    }

    @Test
    @DisplayName("UserDetails should reflect persisted account status flags")
    void userDetailsShouldReflectPersistedStatusFlags() {
        String email = "signup-status-flags-" + System.currentTimeMillis() + "@test.local";
        userService.signUp(email, "TestPass123!");

        User created = userService.findByEmail(email);
        assertThat(created).isNotNull();

        created.setEnabled(false);
        created.setAccountNonExpired(false);
        created.setAccountNonLocked(false);
        created.setCredentialsNonExpired(false);
        userService.update(created);

        CustomUserDetails userDetails = (CustomUserDetails) userDetailsService.loadUserByUsername(email);
        assertThat(userDetails.isEnabled()).isFalse();
        assertThat(userDetails.isAccountNonExpired()).isFalse();
        assertThat(userDetails.isAccountNonLocked()).isFalse();
        assertThat(userDetails.isCredentialsNonExpired()).isFalse();
    }
}
