package com.auraboot.framework.iot.tsport.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link NoopTimeSeriesPort} / {@link TimeSeriesPortFallbackConfig}
 * keep the platform context alive when no real {@link TimeSeriesPort} bean is registered
 * (i.e. the IoT plugin is absent and {@code iot.tdengine.enabled=false}).
 *
 * <p>Uses a minimal slice (no web server, no DB, no Kafka) so Docker is not required
 * and the test runs in a few seconds.
 *
 * <p>Regression gate for issue #339 (backlog 2026-05-29-oss-isolated-stack-
 * timeseriesport-bean-missing): previously the context crashed at startup with
 * {@code UnsatisfiedDependencyException: No qualifying bean of type 'TimeSeriesPort'}.
 */
@SpringBootTest(
        classes = {TimeSeriesPortFallbackConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@TestPropertySource(properties = "iot.tdengine.enabled=false")
class NoopTimeSeriesPortTest {

    @Autowired
    private ApplicationContext ctx;

    @Autowired
    private TimeSeriesPort timeSeriesPort;

    // ------------------------------------------------------------------ context health

    @Test
    void contextLoads_withoutIotPlugin() {
        // Reaching this point proves the context started without crashing —
        // the P1 regression from backlog 2026-05-29 is fixed.
        assertThat(ctx).isNotNull();
    }

    @Test
    void timeSeriesPort_bean_is_present() {
        assertThat(timeSeriesPort).isNotNull();
    }

    @Test
    void timeSeriesPort_bean_is_noop_instance() {
        assertThat(timeSeriesPort).isInstanceOf(NoopTimeSeriesPort.class);
    }

    // ------------------------------------------------------------------ call-time behaviour (fail-closed)

    @Test
    void writeBatch_throws_unavailable() {
        assertThatThrownBy(() -> timeSeriesPort.writeBatch(1L, List.of()))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("iot.tsport.unavailable");
    }

    @Test
    void queryLatest_throws_unavailable() {
        assertThatThrownBy(() ->
                timeSeriesPort.queryLatest(1L, "dev-1", List.of("temp"), 1))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("iot.tsport.unavailable");
    }

    @Test
    void queryRange_throws_unavailable() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        assertThatThrownBy(() ->
                timeSeriesPort.queryRange(1L,
                        new QueryParams.Range("dev-1", List.of("temp"), from, to, null)))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("iot.tsport.unavailable");
    }

    @Test
    void queryAggregate_throws_unavailable() {
        Instant from = Instant.parse("2026-05-28T00:00:00Z");
        Instant to = Instant.parse("2026-05-28T01:00:00Z");
        assertThatThrownBy(() ->
                timeSeriesPort.queryAggregate(1L,
                        new QueryParams.Aggregate("dev-1", List.of("temp"), from, to,
                                QueryParams.Aggregation.AVG, Duration.ofMinutes(5))))
                .isInstanceOf(MetaServiceException.class)
                .hasMessageContaining("iot.tsport.unavailable");
    }

    // ------------------------------------------------------------------ guard: must not override real impl

    @Test
    void fallbackConfig_factory_carries_ConditionalOnMissingBean() {
        // If someone removes @ConditionalOnMissingBean the no-op would suppress
        // the real TDengine impl on IoT-enabled deploys.
        boolean annotationPresent = false;
        try {
            java.lang.reflect.Method m =
                    TimeSeriesPortFallbackConfig.class
                            .getDeclaredMethod("noopTimeSeriesPortBean");
            annotationPresent = m.isAnnotationPresent(ConditionalOnMissingBean.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "TimeSeriesPortFallbackConfig.noopTimeSeriesPortBean() method missing", e);
        }
        assertThat(annotationPresent)
                .as("noopTimeSeriesPortBean() must carry @ConditionalOnMissingBean "
                        + "so the real TDengine impl wins when the IoT plugin is loaded")
                .isTrue();
    }
}
