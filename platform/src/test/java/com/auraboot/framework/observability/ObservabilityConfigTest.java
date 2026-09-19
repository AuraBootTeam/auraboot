package com.auraboot.framework.observability;

import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.aop.ObservedAspect;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ObservabilityConfigTest {

    @Test
    void registersAspectThatActivatesObservedAnnotations() {
        ObservabilityConfig config = new ObservabilityConfig(mock(ApiMetricsInterceptor.class));

        ObservedAspect aspect = config.observedAspect(ObservationRegistry.create());

        assertThat(aspect).isNotNull();
    }
}
