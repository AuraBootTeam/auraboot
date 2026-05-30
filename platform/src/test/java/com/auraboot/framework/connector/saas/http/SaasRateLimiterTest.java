package com.auraboot.framework.connector.saas.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sliding-window bucket semantics for {@link SaasRateLimiter}. */
class SaasRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<Long> sleeps = new ArrayList<>();
    private final SaasRateLimiter limiter = new SaasRateLimiter(
            now::get,
            ms -> { sleeps.add(ms); now.addAndGet(ms); });

    @Test
    void acquireBelowCapacityIsImmediate() throws Exception {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(1L, "saas-hubspot", 3, 10_000);
        }
        assertThat(sleeps).isEmpty();
        assertThat(limiter.currentSize(1L, "saas-hubspot")).isEqualTo(3);
    }

    @Test
    void acquireAtCapacitySleepsUntilOldestExpires() throws Exception {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(1L, "saas-hubspot", 3, 10_000);
        }
        // 4th acquire — bucket full at now=1_000_000, oldest=1_000_000.
        // Should sleep until 1_010_001.
        limiter.acquire(1L, "saas-hubspot", 3, 10_000);
        assertThat(sleeps).hasSize(1);
        assertThat(sleeps.get(0)).isEqualTo(10_001L);
        assertThat(limiter.currentSize(1L, "saas-hubspot")).isEqualTo(1); // older 3 evicted
    }

    @Test
    void differentTenantsHaveIndependentBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(1L, "saas-hubspot", 3, 10_000);
            limiter.acquire(2L, "saas-hubspot", 3, 10_000);
        }
        assertThat(sleeps).isEmpty();
        assertThat(limiter.currentSize(1L, "saas-hubspot")).isEqualTo(3);
        assertThat(limiter.currentSize(2L, "saas-hubspot")).isEqualTo(3);
    }

    @Test
    void differentVendorsHaveIndependentBuckets() throws Exception {
        for (int i = 0; i < 3; i++) {
            limiter.acquire(1L, "saas-hubspot", 3, 10_000);
            limiter.acquire(1L, "saas-stripe", 3, 10_000);
        }
        assertThat(sleeps).isEmpty();
    }

    @Test
    void nullTenantUsesSystemKey() throws Exception {
        limiter.acquire(null, "saas-hubspot", 1, 10_000);
        // Filling the bucket — next call should sleep.
        limiter.acquire(null, "saas-hubspot", 1, 10_000);
        assertThat(sleeps).hasSize(1);
    }

    @Test
    void resetClearsAllBuckets() throws Exception {
        limiter.acquire(1L, "saas-hubspot", 1, 10_000);
        assertThat(limiter.currentSize(1L, "saas-hubspot")).isEqualTo(1);
        limiter.reset();
        assertThat(limiter.currentSize(1L, "saas-hubspot")).isZero();
    }

    @Test
    void invalidArgsThrow() {
        assertThatThrownBy(() -> limiter.acquire(1L, "x", 0, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.acquire(1L, "x", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
