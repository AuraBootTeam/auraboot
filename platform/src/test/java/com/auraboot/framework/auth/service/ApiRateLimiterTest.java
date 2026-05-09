package com.auraboot.framework.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiRateLimiterTest {

    private ApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new ApiRateLimiter();
    }

    @Test
    void isAllowed_withinLimit_returnsTrue() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.isAllowed("login:ip:1.2.3.4", 5)).isTrue();
        }
    }

    @Test
    void isAllowed_exceedsLimit_returnsFalse() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.isAllowed("op:ip:5.5.5.5", 3)).isTrue();
        }
        assertThat(limiter.isAllowed("op:ip:5.5.5.5", 3)).isFalse();
    }

    @Test
    void isAllowed_distinctKeysIndependent() {
        assertThat(limiter.isAllowed("a", 1)).isTrue();
        assertThat(limiter.isAllowed("a", 1)).isFalse();
        assertThat(limiter.isAllowed("b", 1)).isTrue();
    }

    @Test
    void evictStaleEntries_doesNotThrow() {
        limiter.isAllowed("evict:test", 5);
        limiter.evictStaleEntries(); // package-private
    }
}
