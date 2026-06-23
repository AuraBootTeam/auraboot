package com.auraboot.framework.iot.tsport.impl;

import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Platform-side fallback configuration that registers a no-op {@link TimeSeriesPort}
 * bean when no real implementation is present.
 *
 * <p><b>Problem solved:</b> the real TDengine implementation ({@link TDengineTimeSeriesPort})
 * lives inside {@link TimeSeriesPortConfig}, which is gated by
 * {@code @ConditionalOnProperty(iot.tdengine.enabled=true)}. When the IoT plugin is
 * absent or TDengine is not configured, no {@link TimeSeriesPort} bean exists in the
 * Spring context. Spring Boot 3 / Spring 6 then fails at startup with
 * {@code UnsatisfiedDependencyException: No qualifying bean of type 'TimeSeriesPort'}
 * even for consumers that use {@link java.util.Optional} or
 * {@link org.springframework.beans.factory.ObjectProvider} wrappers, because the
 * single non-default constructor is treated as required by the container
 * (issue #339 / backlog 2026-05-29-oss-isolated-stack-timeseriesport-bean-missing).
 *
 * <p><b>Resolution:</b> this class registers {@link NoopTimeSeriesPort} as the
 * fallback bean. The {@code @ConditionalOnMissingBean} guard ensures:
 * <ul>
 *   <li>When the IoT plugin is absent: this no-op bean is registered and Spring
 *       context starts cleanly. TSDB calls are rejected at invocation time with
 *       a stable HTTP 503 / {@code iot.tsport.unavailable} error.</li>
 *   <li>When the IoT plugin is present and TDengine is enabled: the real
 *       {@link TDengineTimeSeriesPort} from {@link TimeSeriesPortConfig} is
 *       registered first; the {@code @ConditionalOnMissingBean} guard suppresses
 *       this fallback — no change to production behavior.</li>
 * </ul>
 *
 * <p>This class is NOT {@code @ConditionalOnProperty} — it must always be active
 * so the fallback fires for any deploy where TDengine is absent.
 *
 * @since 2.6.2
 * @see NoopTimeSeriesPort
 * @see TimeSeriesPortConfig
 */
@Configuration
public class TimeSeriesPortFallbackConfig {

    private static final Logger log = LoggerFactory.getLogger(TimeSeriesPortFallbackConfig.class);

    /**
     * Registers the no-op fallback only when no real {@link TimeSeriesPort} bean is
     * present (e.g. {@link TDengineTimeSeriesPort} from the IoT plugin).
     */
    @Bean
    @ConditionalOnMissingBean(TimeSeriesPort.class)
    public TimeSeriesPort noopTimeSeriesPortBean() {
        log.info("[iot-tsport] No TimeSeriesPort implementation found "
                + "(iot.tdengine.enabled=false or IoT plugin absent). "
                + "Registering no-op fallback — TSDB calls will return HTTP 503.");
        return new NoopTimeSeriesPort();
    }
}
