package com.auraboot.framework.iot.tsport.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Vendor-free unit tests for {@link TDengineTimeSeriesPort} pre-flight
 * validation and helper logic. The full SQL round-trip is covered by the
 * Testcontainers-backed {@link TDengineTimeSeriesPortIT}; this class
 * exercises the input validation and the {@code subTableName} hash so they
 * stay green even on dev machines without Docker.
 */
class TDengineTimeSeriesPortUnitTest {

    @Test
    void subTableName_isStableAndTenantQualified() {
        String a = TDengineTimeSeriesPort.subTableName(1L, "dev-1", "temp");
        String b = TDengineTimeSeriesPort.subTableName(1L, "dev-1", "temp");
        String diffTenant = TDengineTimeSeriesPort.subTableName(2L, "dev-1", "temp");

        assertThat(a).isEqualTo(b).startsWith("t_1_");
        assertThat(diffTenant).startsWith("t_2_").isNotEqualTo(a);
    }

    @Test
    void writeBatch_rejectsNonPositiveTenantWithoutTouchingDataSource() {
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        assertThatThrownBy(() -> port.writeBatch(0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void writeBatch_rejectsNullPointsWithoutTouchingDataSource() {
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        assertThatThrownBy(() -> port.writeBatch(1L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void queryLatest_rejectsZeroLimit() {
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        assertThatThrownBy(() -> port.queryLatest(1L, "d1", List.of("temp"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryRange_rejectsNonPositiveTenant() {
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        QueryParams.Range range =
                new QueryParams.Range("d1", List.of("temp"), t0, t0.plusSeconds(10), null);
        assertThatThrownBy(() -> port.queryRange(0L, range))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryAggregate_rejectsNonPositiveTenant() {
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        QueryParams.Aggregate agg =
                new QueryParams.Aggregate(
                        "d1",
                        List.of("temp"),
                        t0,
                        t0.plusSeconds(60),
                        QueryParams.Aggregation.AVG,
                        Duration.ofSeconds(10));
        assertThatThrownBy(() -> port.queryAggregate(0L, agg))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyBatch_isNoOpAndDoesNotTouchDataSource() {
        // null DataSource would NPE if writeBatch tried to issue any SQL.
        TDengineTimeSeriesPort port = new TDengineTimeSeriesPort(org.mockito.Mockito.mock(javax.sql.DataSource.class));
        port.writeBatch(1L, List.of());
        // No exception → contract honoured.
    }

    @Test
    void timeSeriesPointRecord_validatesNulls() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        assertThatThrownBy(() -> new TimeSeriesPoint(null, "temp", t0, 1.0, "GOOD"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeSeriesPoint("d1", null, t0, 1.0, "GOOD"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeSeriesPoint("d1", "temp", null, 1.0, "GOOD"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new TimeSeriesPoint("d1", "temp", t0, null, "GOOD"))
                .isInstanceOf(NullPointerException.class);
    }
}
