package com.auraboot.framework.auth.dto;

import com.auraboot.framework.application.security.CustomPasswordEncoder;
import com.auraboot.framework.application.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Actual production authentication factory and BCrypt; the account loader is an in-memory fixture. */
class CustomUserDetailsAuthenticationContractTest {
    private static final String USERNAME = "operator@example.invalid";
    private static final String PASSWORD = "Fixture-login-password-47";
    private final PasswordEncoder encoder = new CustomPasswordEncoder().passwordEncoder();

    @Test
    void realPasswordAuthenticationSucceedsWithoutInventingSpringRoles() {
        var user = user(true, true);
        var result = manager(user).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(USERNAME, PASSWORD));
        assertThat(result.isAuthenticated()).isTrue();
        assertThat(result.getPrincipal()).isSameAs(user);
        assertThat(result.getCredentials()).isNull();
        assertThat(user.getAuthorities()).isEmpty();
        assertThat(result.getAuthorities()).allSatisfy(authority ->
                assertThat(authority.getAuthority()).doesNotStartWith("ROLE_").isNotEqualTo("*"));
    }

    @Test
    void realProviderStillRejectsWrongPasswordsAndUnknownUsers() {
        var manager = manager(user(true, true));
        assertThatThrownBy(() -> manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(USERNAME, "incorrect")))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> manager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("unknown@example.invalid", PASSWORD)))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void accountRestrictionsStillPreventSuccessfulAuthentication() {
        assertThatThrownBy(() -> manager(user(false, true)).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(USERNAME, PASSWORD)))
                .isInstanceOf(LockedException.class);
        assertThatThrownBy(() -> manager(user(true, false)).authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(USERNAME, PASSWORD)))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void diagnosticRepresentationNeverIncludesTheStoredCredential() {
        var user = user(true, true);
        assertThat(user.toString()).doesNotContain(user.getPassword(), PASSWORD)
                .contains("CustomUserDetails", "31", "fixture-user");
    }

    private CustomUserDetails user(boolean unlocked, boolean enabled) {
        // This is the production loader's intentionally empty Spring-authority shape.
        return new CustomUserDetails(USERNAME, encoder.encode(PASSWORD), 31L, "fixture-user", null,
                true, unlocked, true, enabled);
    }

    private AuthenticationManager manager(CustomUserDetails user) {
        UserDetailsService loader = name -> {
            if (!USERNAME.equals(name)) {
                throw new UsernameNotFoundException("Unknown fixture account");
            }
            return user;
        };
        var configuration = new SecurityConfig(null, loader);
        ReflectionTestUtils.setField(configuration, "passwordEncoder", encoder);
        return configuration.authenticationManager();
    }
}
