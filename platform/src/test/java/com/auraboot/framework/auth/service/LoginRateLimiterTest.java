package com.auraboot.framework.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimiterTest {

    @Test
    void identifierBudgetExpiresAtTheWindowBoundaryAndIgnoresCaseOrSurroundingWhitespace() {
        AtomicLong time = new AtomicLong(1_000_000);
        var bounded = new LoginRateLimiter(time::get);
        for (int i = 0; i < 10; i++) assertThat(bounded.isAllowed(null, " USER@example.com ")).isTrue();
        assertThat(bounded.isAllowed(null, "user@example.com")).isFalse();
        time.addAndGet(59_999);
        assertThat(bounded.isAllowed(null, "user@example.com")).isFalse();
        time.incrementAndGet();
        assertThat(bounded.isAllowed(null, "user@example.com")).isTrue();
    }

    @Test
    void concurrentAttemptsCannotExceedIdentifierOrClientBudgets() throws Exception {
        assertThat(concurrentBudget(true)).isEqualTo(10);
        assertThat(concurrentBudget(false)).isEqualTo(60);
    }

    private long concurrentBudget(boolean sameIdentifier) throws Exception {
        var bounded = new LoginRateLimiter();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(16)) {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 100; i++) {
                final int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return bounded.isAllowed(sameIdentifier ? "client-" + index : "same-client",
                            sameIdentifier ? "same-user@example.com" : "user-" + index);
                }));
            }
            start.countDown();
            long allowed = 0;
            for (var result : results) if (result.get()) allowed++;
            return allowed;
        }
    }

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
        // A sustained burst must be rejected; exact default and parallel budgets are tested above.
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
