package com.auraboot.framework.integration;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationBackoffPolicyTest {

    private final IntegrationBackoffPolicy policy =
            new IntegrationBackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(15));

    @Test
    void growsExponentiallyAndCapsWithoutOverflow() {
        assertThat(policy.delayForRetry(0)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.delayForRetry(1)).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.delayForRetry(9)).isEqualTo(Duration.ofSeconds(512));
        assertThat(policy.delayForRetry(10)).isEqualTo(Duration.ofMinutes(15));
        assertThat(policy.delayForRetry(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void rejectsInvalidConfigurationAndRetryIndex() {
        assertThatThrownBy(() -> new IntegrationBackoffPolicy(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IntegrationBackoffPolicy(
                Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.delayForRetry(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
