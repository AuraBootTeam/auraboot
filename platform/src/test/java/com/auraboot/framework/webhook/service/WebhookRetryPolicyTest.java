package com.auraboot.framework.webhook.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WebhookRetryPolicyTest {
    @Test
    void samplesEachExponentialWindowAndCapsWithoutOverflow() {
        List<List<Long>> windows = new ArrayList<>();
        var policy = new WebhookRetryPolicy((floor, ceiling) -> {
            windows.add(List.of(floor, ceiling));
            return floor;
        });
        assertThat(policy.delayForRetry(0)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.delayForRetry(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayForRetry(10)).isEqualTo(Duration.ofSeconds(512));
        assertThat(policy.delayForRetry(12)).isEqualTo(Duration.ofMinutes(30));
        assertThat(policy.delayForRetry(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(30));
        assertThat(windows).containsExactly(List.of(500L, 1000L), List.of(1000L, 2000L),
                List.of(512000L, 1024000L), List.of(1800000L, 3600000L), List.of(1800000L, 3600000L));
    }

    @Test
    void acceptsLastMillisecondBeforeCeiling() {
        var policy = new WebhookRetryPolicy((floor, ceiling) -> ceiling - 1);
        assertThat(policy.delayForRetry(0)).isEqualTo(Duration.ofMillis(999));
        assertThat(policy.delayForRetry(Integer.MAX_VALUE)).isEqualTo(Duration.ofMillis(3599999));
    }

    @Test
    void rejectsNegativeIndexBeforeSampling() {
        var policy = new WebhookRetryPolicy((floor, ceiling) -> {
            throw new AssertionError("An invalid retry index must not sample");
        });
        assertThatThrownBy(() -> policy.delayForRetry(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsSamplesOutsideDeclaredWindow() {
        assertThatThrownBy(() -> new WebhookRetryPolicy((floor, ceiling) -> floor - 1).delayForRetry(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebhookRetryPolicy((floor, ceiling) -> ceiling).delayForRetry(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
