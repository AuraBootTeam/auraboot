package com.auraboot.framework.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    private LoginRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginRateLimiter();
    }

    @Test
    void isAllowed_nullIpAndEmail_returnsTrue() {
        assertThat(limiter.isAllowed(null, null)).isTrue();
    }

    @Test
    void isAllowed_freshIpAndEmail_returnsTrue() {
        assertThat(limiter.isAllowed("9.9.9.9", "User@Example.com")).isTrue();
    }

    @Test
    void isAllowed_emailIsLowercased() {
        assertThat(limiter.isAllowed("1.1.1.1", "Foo@Bar.com")).isTrue();
        // both should now share the same email bucket regardless of case
        for (int i = 0; i < 5; i++) {
            limiter.isAllowed(null, "FOO@BAR.COM");
        }
        assertThat(limiter.isAllowed(null, "foo@bar.com")).isTrue();
    }

    @Test
    void isAllowed_returnsFalseAfterManyAttempts() {
        // Default cap is 3,000/min — high enough for quickstart and smoke tests.
        boolean lastResult = true;
        for (int i = 0; i < 3_005 && lastResult; i++) {
            lastResult = limiter.isAllowed("burst-ip", null);
        }
        assertThat(lastResult).isFalse();
    }

    @Test
    void isAllowed_returnsFalseAfterManyEmailAttempts() {
        boolean lastResult = true;
        for (int i = 0; i < 3_005 && lastResult; i++) {
            lastResult = limiter.isAllowed(null, "burst@example.com");
        }
        assertThat(lastResult).isFalse();
    }

    @Test
    void evictStaleEntries_doesNotThrow() {
        limiter.isAllowed("2.2.2.2", "x@y.com");
        limiter.evictStaleEntries();
    }
}
