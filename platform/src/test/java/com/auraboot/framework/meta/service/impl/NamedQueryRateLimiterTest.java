package com.auraboot.framework.meta.service.impl;

import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.assertThat;

class NamedQueryRateLimiterTest {
    @Test void concurrentAdmissionCannotExceedTheTenantQueryBudget() throws Exception {
        var limiter = new NamedQueryRateLimiter(() -> 100_000L);
        try (var executor = Executors.newFixedThreadPool(32)) {
            var ready = new CountDownLatch(32);
            var start = new CountDownLatch(1);
            var futures = new java.util.ArrayList<Future<Boolean>>();
            for (int index = 0; index < 32; index++) futures.add(executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("Admission start barrier timed out");
                return limiter.tryAcquire(1L, "bounded-query", 7);
            }));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int admitted = 0;
            for (var future : futures) if (future.get(10, TimeUnit.SECONDS)) admitted++;
            assertThat(admitted).isEqualTo(7);
            assertThat(limiter.getCurrentCount(1L, "bounded-query")).isEqualTo(7);
            assertThat(limiter.tryAcquire(1L, "bounded-query", 7)).isFalse();
        }
    }

    @Test void expiredWindowReopensWithoutCrossTenantOrQueryInterference() {
        var clock = new AtomicLong(100_000L);
        var limiter = new NamedQueryRateLimiter(clock::get);
        assertThat(limiter.tryAcquire(1L, "one", 1)).isTrue();
        assertThat(limiter.tryAcquire(1L, "one", 1)).isFalse();
        assertThat(limiter.tryAcquire(2L, "one", 1)).isTrue();
        assertThat(limiter.tryAcquire(1L, "two", 1)).isTrue();
        clock.addAndGet(60_000L);
        assertThat(limiter.tryAcquire(1L, "one", 1)).isFalse();
        clock.incrementAndGet();
        assertThat(limiter.getCurrentCount(1L, "one")).isZero();
        assertThat(limiter.tryAcquire(1L, "one", 1)).isTrue();
        assertThat(limiter.getCurrentCount(1L, "one")).isEqualTo(1);
    }

    @Test void disabledPolicyDoesNotConsumeAWindowAndTheDefaultRemainsBounded() {
        var limiter = new NamedQueryRateLimiter(() -> 100_000L);
        for (int index = 0; index < 100; index++) assertThat(limiter.tryAcquire(1L, "off", 0)).isTrue();
        assertThat(limiter.getCurrentCount(1L, "off")).isZero();
        assertThat(new com.auraboot.framework.meta.entity.NamedQueryPolicy().getRateLimitPerMinute()).isEqualTo(60);
    }
}
