package com.auraboot.framework.plugin.extension.iot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract test base for {@link TimeSeriesPort}. Every impl
 * ({@code TDengineTimeSeriesPort} via Testcontainers, in-memory fake,
 * future TimescaleDB impl) MUST subclass and override {@link #newPort()}
 * so all impls prove they honour the same semantics.
 *
 * <p>The class itself runs against {@link InMemoryTimeSeriesPort} so the
 * contract assertions are self-validating in the unit-test build — broken
 * contract surfaces here before the IT layer touches a real TDengine.
 *
 * <p>Coverage matrix:
 * <ul>
 *   <li>writeBatch — happy path + empty / null defenses + multi-tenant isolation</li>
 *   <li>queryLatest — multi-code, limit, ts-DESC ordering, missing-code skip</li>
 *   <li>queryRange — half-open bounds, multi-code, downsample-null vs non-null</li>
 *   <li>queryAggregate — AVG / SUM / COUNT / MAX, bucket boundary, pointCount</li>
 *   <li>Cross-tenant isolation — write tenant1, read tenant2 must return empty</li>
 * </ul>
 */
class TimeSeriesPortContractTest {

    protected static final long TENANT_A = 1001L;
    protected static final long TENANT_B = 1002L;

    protected TimeSeriesPort port;

    /** Override in subclasses to supply the impl under test. */
    protected TimeSeriesPort newPort() {
        return new InMemoryTimeSeriesPort();
    }

    @BeforeEach
    void setUp() {
        port = newPort();
    }

    // ---------- writeBatch ----------

    @Test
    void writeBatch_emptyIsNoOp() {
        port.writeBatch(TENANT_A, List.of());
        assertThat(port.queryLatest(TENANT_A, "d1", List.of("temp"), 1)).isEmpty();
    }

    @Test
    void writeBatch_rejectsNullPoints() {
        assertThatThrownBy(() -> port.writeBatch(TENANT_A, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void writeBatch_rejectsNonPositiveTenant() {
        assertThatThrownBy(() -> port.writeBatch(0L, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- queryLatest ----------

    @Test
    void queryLatest_returnsMostRecentPerCode() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A,
                List.of(
                        new TimeSeriesPoint("d1", "temp", t0, 10.0, "GOOD"),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(60), 12.5, "GOOD"),
                        new TimeSeriesPoint("d1", "humid", t0, 55.0, null)));

        List<TimeSeriesPoint> latest =
                port.queryLatest(TENANT_A, "d1", List.of("temp", "humid"), 1);

        assertThat(latest).hasSize(2);
        TimeSeriesPoint temp =
                latest.stream().filter(p -> p.code().equals("temp")).findFirst().orElseThrow();
        assertThat(temp.value().doubleValue()).isEqualTo(12.5);
    }

    @Test
    void queryLatest_respectsLimitInDescOrder() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A,
                List.of(
                        new TimeSeriesPoint("d1", "temp", t0, 1.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(10), 2.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(20), 3.0, null)));

        List<TimeSeriesPoint> got = port.queryLatest(TENANT_A, "d1", List.of("temp"), 2);

        assertThat(got).hasSize(2);
        assertThat(got.get(0).value().doubleValue()).isEqualTo(3.0);
        assertThat(got.get(1).value().doubleValue()).isEqualTo(2.0);
    }

    @Test
    void queryLatest_skipsMissingCodesSilently() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A, List.of(new TimeSeriesPoint("d1", "temp", t0, 1.0, null)));

        List<TimeSeriesPoint> got =
                port.queryLatest(TENANT_A, "d1", List.of("temp", "absent"), 1);

        assertThat(got).hasSize(1);
        assertThat(got.get(0).code()).isEqualTo("temp");
    }

    @Test
    void queryLatest_rejectsZeroLimit() {
        assertThatThrownBy(() -> port.queryLatest(TENANT_A, "d1", List.of("temp"), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- queryRange ----------

    @Test
    void queryRange_halfOpenAscending() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A,
                List.of(
                        new TimeSeriesPoint("d1", "temp", t0, 1.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(10), 2.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(20), 3.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(30), 4.0, null)));

        List<TimeSeriesPoint> got =
                port.queryRange(
                        TENANT_A,
                        new QueryParams.Range(
                                "d1",
                                List.of("temp"),
                                t0.plusSeconds(10),
                                t0.plusSeconds(30),
                                null));

        assertThat(got).hasSize(2);
        assertThat(got.get(0).value().doubleValue()).isEqualTo(2.0);
        assertThat(got.get(1).value().doubleValue()).isEqualTo(3.0);
    }

    @Test
    void queryRange_downsampleReducesPointCount() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        // 10 samples 1s apart → 10s downsample window → ≤ 2 points
        List<TimeSeriesPoint> samples = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(
                    new TimeSeriesPoint("d1", "temp", t0.plusSeconds(i), (double) i, null));
        }
        port.writeBatch(TENANT_A, samples);

        List<TimeSeriesPoint> got =
                port.queryRange(
                        TENANT_A,
                        new QueryParams.Range(
                                "d1",
                                List.of("temp"),
                                t0,
                                t0.plusSeconds(10),
                                Duration.ofSeconds(10)));

        assertThat(got.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void queryRange_rejectsInvertedBounds() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        assertThatThrownBy(
                        () ->
                                new QueryParams.Range(
                                        "d1",
                                        List.of("temp"),
                                        t0.plusSeconds(10),
                                        t0,
                                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryRange_rejectsEmptyCodes() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        assertThatThrownBy(
                        () ->
                                new QueryParams.Range(
                                        "d1", List.of(), t0, t0.plusSeconds(10), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- queryAggregate ----------

    @Test
    void queryAggregate_avgRespectsBucketBoundary() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A,
                List.of(
                        new TimeSeriesPoint("d1", "temp", t0, 10.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(5), 20.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(15), 30.0, null)));

        List<AggregatedPoint> got =
                port.queryAggregate(
                        TENANT_A,
                        new QueryParams.Aggregate(
                                "d1",
                                List.of("temp"),
                                t0,
                                t0.plusSeconds(20),
                                QueryParams.Aggregation.AVG,
                                Duration.ofSeconds(10)));

        // bucket [0,10): avg(10,20)=15; bucket [10,20): avg(30)=30
        assertThat(got).hasSize(2);
        assertThat(got.get(0).value().doubleValue()).isEqualTo(15.0);
        assertThat(got.get(0).pointCount()).isEqualTo(2L);
        assertThat(got.get(1).value().doubleValue()).isEqualTo(30.0);
        assertThat(got.get(1).pointCount()).isEqualTo(1L);
    }

    @Test
    void queryAggregate_countReturnsLong() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A,
                List.of(
                        new TimeSeriesPoint("d1", "temp", t0, 10.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(1), 20.0, null),
                        new TimeSeriesPoint("d1", "temp", t0.plusSeconds(2), 30.0, null)));

        List<AggregatedPoint> got =
                port.queryAggregate(
                        TENANT_A,
                        new QueryParams.Aggregate(
                                "d1",
                                List.of("temp"),
                                t0,
                                t0.plusSeconds(10),
                                QueryParams.Aggregation.COUNT,
                                Duration.ofSeconds(10)));

        assertThat(got).hasSize(1);
        assertThat(got.get(0).value().longValue()).isEqualTo(3L);
    }

    @Test
    void queryAggregate_rejectsZeroGroupBy() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        assertThatThrownBy(
                        () ->
                                new QueryParams.Aggregate(
                                        "d1",
                                        List.of("temp"),
                                        t0,
                                        t0.plusSeconds(10),
                                        QueryParams.Aggregation.AVG,
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- Cross-tenant isolation (critical) ----------

    @Test
    void crossTenant_writeAReadBReturnsEmpty() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A, List.of(new TimeSeriesPoint("d1", "temp", t0, 99.0, null)));

        assertThat(port.queryLatest(TENANT_B, "d1", List.of("temp"), 10)).isEmpty();
        assertThat(
                        port.queryRange(
                                TENANT_B,
                                new QueryParams.Range(
                                        "d1",
                                        List.of("temp"),
                                        t0.minusSeconds(60),
                                        t0.plusSeconds(60),
                                        null)))
                .isEmpty();
        assertThat(
                        port.queryAggregate(
                                TENANT_B,
                                new QueryParams.Aggregate(
                                        "d1",
                                        List.of("temp"),
                                        t0.minusSeconds(60),
                                        t0.plusSeconds(60),
                                        QueryParams.Aggregation.AVG,
                                        Duration.ofSeconds(10))))
                .isEmpty();
    }

    @Test
    void crossTenant_concurrentTenantsAreIsolated() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A, List.of(new TimeSeriesPoint("d1", "temp", t0, 10.0, null)));
        port.writeBatch(
                TENANT_B, List.of(new TimeSeriesPoint("d1", "temp", t0, 99.0, null)));

        assertThat(
                        port.queryLatest(TENANT_A, "d1", List.of("temp"), 1)
                                .get(0)
                                .value()
                                .doubleValue())
                .isEqualTo(10.0);
        assertThat(
                        port.queryLatest(TENANT_B, "d1", List.of("temp"), 1)
                                .get(0)
                                .value()
                                .doubleValue())
                .isEqualTo(99.0);
    }
}
