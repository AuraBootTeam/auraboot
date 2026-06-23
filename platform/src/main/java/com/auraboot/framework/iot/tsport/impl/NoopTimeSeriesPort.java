package com.auraboot.framework.iot.tsport.impl;

import com.auraboot.framework.meta.exception.MetaServiceException;
import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPort;
import java.util.List;

/**
 * Platform-side no-op fallback implementation of {@link TimeSeriesPort}.
 *
 * <p><b>Why this exists:</b> the real {@link TDengineTimeSeriesPort} lives inside
 * {@link TimeSeriesPortConfig}, gated by
 * {@code @ConditionalOnProperty(iot.tdengine.enabled=true)}. When the IoT plugin is
 * absent or TDengine is disabled, no {@link TimeSeriesPort} bean exists, and Spring
 * Boot 3 / Spring 6 fails to start with
 * {@code UnsatisfiedDependencyException: No qualifying bean of type 'TimeSeriesPort'}
 * even when consumers use {@link java.util.Optional} or
 * {@link org.springframework.beans.factory.ObjectProvider} wrappers, because a single
 * non-default constructor is treated as required (issue #339 / backlog 2026-05-29).
 *
 * <p>This class is a plain Java class — it is not a Spring component itself.
 * It is instantiated by {@link TimeSeriesPortFallbackConfig#noopTimeSeriesPortBean()}
 * which carries the {@code @ConditionalOnMissingBean} guard.
 *
 * <p><b>Design contract:</b>
 * <ul>
 *   <li>All methods throw {@link MetaServiceException} with i18n code
 *       {@code iot.tsport.unavailable} — same code the service layer maps to HTTP 503.
 *       The platform context starts cleanly; callers get a clear error only when they
 *       actually invoke a TSDB operation, never at boot time.</li>
 *   <li>Does NOT swallow or silently degrade — red line §8.</li>
 * </ul>
 *
 * @since 2.6.2
 * @see TimeSeriesPortFallbackConfig
 */
public final class NoopTimeSeriesPort implements TimeSeriesPort {

    static final String MSG =
            "iot.tsport.unavailable:TimeSeriesPort is not configured "
                    + "(iot.tdengine.enabled=false — set it to true and supply iot.tdengine.url)";

    @Override
    public void writeBatch(long tenantId, List<TimeSeriesPoint> points) {
        throw new MetaServiceException(MSG);
    }

    @Override
    public List<TimeSeriesPoint> queryLatest(
            long tenantId, String deviceCode, List<String> codes, int limit) {
        throw new MetaServiceException(MSG);
    }

    @Override
    public List<TimeSeriesPoint> queryRange(long tenantId, QueryParams.Range params) {
        throw new MetaServiceException(MSG);
    }

    @Override
    public List<AggregatedPoint> queryAggregate(long tenantId, QueryParams.Aggregate params) {
        throw new MetaServiceException(MSG);
    }
}
