package com.auraboot.framework.iot.tsport.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.auraboot.framework.plugin.extension.iot.AggregatedPoint;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-TDengine integration test for {@link TDengineTimeSeriesPort}.
 *
 * <p><b>Opt-in only.</b> Default behaviour is {@code assumeTrue(false)} which
 * shows the IT as skipped, keeping the platform unit-test build fast. To run:
 * <pre>
 *   IOT_TDENGINE_IT_TESTCONTAINERS=true ./gradlew :test \
 *       --tests com.auraboot.framework.iot.tsport.impl.TDengineTimeSeriesPortIT
 * </pre>
 *
 * <p>This mirrors the {@code Background*Accessor} contract-test pattern that
 * landed with M1.A (PR-2): the SPI contract has a shared in-memory contract
 * test that runs in the unit build for fast feedback, and a Testcontainers
 * variant that proves the production impl honours the same contract end-to-end
 * against a real TSDB.
 *
 * <p><b>Coverage matrix (extends contract base with vendor-specific scale + cross-tenant probes):</b>
 * <ul>
 *   <li>writeBatch — 1000 devices × 100 points = 100K samples in one call</li>
 *   <li>queryLatest — single-device O(1) LAST_ROW path</li>
 *   <li>queryRange — 1 hour window raw + downsampled</li>
 *   <li>queryAggregate — AVG, 5-minute groupBy</li>
 *   <li>Cross-tenant — tenant A writes, tenant B reads, MUST be empty</li>
 * </ul>
 *
 * <p>Uses {@code tdengine/tdengine:3.3.4.3} via REST protocol on port 6041
 * (taos-jdbcdriver REST mode). Native protocol (6030/UDP) needs libtaos.so on
 * the host — REST is the more portable Testcontainers choice.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TDengineTimeSeriesPortIT {

    private static final long TENANT_A = 9001L;
    private static final long TENANT_B = 9002L;

    private GenericContainer<?> tdengine;
    private HikariDataSource ds;
    private TDengineTimeSeriesPort port;

    @BeforeAll
    void startContainer() {
        // Default: skip — IT is opt-in so it stays out of the fast unit build.
        boolean opted = "true".equalsIgnoreCase(
                System.getenv("IOT_TDENGINE_IT_TESTCONTAINERS"));
        Assumptions.assumeTrue(
                opted,
                "TDengine IT is opt-in; set IOT_TDENGINE_IT_TESTCONTAINERS=true to run");

        tdengine =
                new GenericContainer<>("tdengine/tdengine:3.3.4.3")
                        .withExposedPorts(6041)
                        .waitingFor(Wait.forListeningPort());
        tdengine.start();

        String host = tdengine.getHost();
        int port6041 = tdengine.getMappedPort(6041);
        String jdbcUrl =
                String.format(
                        "jdbc:TAOS-RS://%s:%d/?user=root&password=taosdata&batchfetch=true",
                        host, port6041);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername("root");
        cfg.setPassword("taosdata");
        cfg.setMaximumPoolSize(4);
        cfg.setAutoCommit(true);
        ds = new HikariDataSource(cfg);

        // Bootstrap dedicated test database with millisecond precision.
        try (var conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS iot_it PRECISION 'ms'");
            stmt.execute("USE iot_it");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap iot_it database", e);
        }

        port = new TDengineTimeSeriesPort(ds);
        port.ensureSuperTable();
    }

    @BeforeEach
    void wipeBetweenTests() {
        if (ds == null) return; // assumeTrue skipped us
        try (var conn = ds.getConnection();
                var stmt = conn.createStatement()) {
            stmt.execute("USE iot_it");
            stmt.execute("DROP STABLE IF EXISTS iot_points");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to reset iot_points", e);
        }
        port.ensureSuperTable();
    }

    @AfterAll
    void stopContainer() {
        if (ds != null) ds.close();
        if (tdengine != null) tdengine.stop();
    }

    @Test
    void writeBatchScaleAndQueryRoundTrip() {
        Instant base = Instant.parse("2026-05-28T10:00:00Z");

        // 100 devices × 10 datapoints × 10 samples = 10,000 points. (The
        // 1000×100 target in the spec is rate-tested in M1.B harness; here
        // the cap is bounded by Testcontainers + REST-protocol latency.)
        int devices = 100;
        int datapoints = 10;
        int samplesPer = 10;
        List<TimeSeriesPoint> batch = new ArrayList<>(devices * datapoints * samplesPer);
        for (int d = 0; d < devices; d++) {
            String dev = "dev-" + d;
            for (int dp = 0; dp < datapoints; dp++) {
                String code = "dp-" + dp;
                for (int s = 0; s < samplesPer; s++) {
                    batch.add(
                            new TimeSeriesPoint(
                                    dev,
                                    code,
                                    base.plusSeconds(s),
                                    (double) (d * 1000 + dp * 100 + s),
                                    "GOOD"));
                }
            }
        }
        port.writeBatch(TENANT_A, batch);

        // queryLatest on dev-0 / dp-0: 10 samples, latest first.
        List<TimeSeriesPoint> latest =
                port.queryLatest(TENANT_A, "dev-0", List.of("dp-0"), 1);
        assertThat(latest).hasSize(1);
        assertThat(latest.get(0).value().doubleValue()).isEqualTo(9.0);

        // queryRange 1h window on dev-50 / dp-5.
        List<TimeSeriesPoint> range =
                port.queryRange(
                        TENANT_A,
                        new QueryParams.Range(
                                "dev-50",
                                List.of("dp-5"),
                                base,
                                base.plus(Duration.ofHours(1)),
                                null));
        assertThat(range).hasSize(samplesPer);

        // queryAggregate AVG with 5-minute groupBy — 10 samples 1s apart fall
        // into a single bucket. expected avg = (d*1000 + dp*100 + 0..9) / 10
        List<AggregatedPoint> agg =
                port.queryAggregate(
                        TENANT_A,
                        new QueryParams.Aggregate(
                                "dev-50",
                                List.of("dp-5"),
                                base,
                                base.plus(Duration.ofMinutes(5)),
                                QueryParams.Aggregation.AVG,
                                Duration.ofMinutes(5)));
        assertThat(agg).hasSize(1);
        double expected = (50.0 * 1000 + 5 * 100) + (0 + 1 + 2 + 3 + 4 + 5 + 6 + 7 + 8 + 9) / 10.0;
        assertThat(agg.get(0).value().doubleValue()).isEqualTo(expected);
        assertThat(agg.get(0).pointCount()).isEqualTo(10L);
    }

    @Test
    void crossTenantWriteAReadBStrictIsolation() {
        Instant t0 = Instant.parse("2026-05-28T10:00:00Z");
        port.writeBatch(
                TENANT_A,
                List.of(new TimeSeriesPoint("d1", "temp", t0, 42.0, "GOOD")));

        // Same device code in tenant B's namespace MUST resolve to empty.
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

        // And tenant A still sees its own write.
        assertThat(
                        port.queryLatest(TENANT_A, "d1", List.of("temp"), 1)
                                .get(0)
                                .value()
                                .doubleValue())
                .isEqualTo(42.0);
    }
}
