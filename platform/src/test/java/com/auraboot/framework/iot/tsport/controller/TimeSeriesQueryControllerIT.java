package com.auraboot.framework.iot.tsport.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.iot.tsport.controller.dto.AggregateQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryRequest;
import com.auraboot.framework.iot.tsport.controller.dto.BatchQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.LatestQueryResponse;
import com.auraboot.framework.iot.tsport.controller.dto.RangeQueryResponse;
import com.auraboot.framework.iot.tsport.impl.TDengineTimeSeriesPort;
import com.auraboot.framework.plugin.extension.iot.QueryParams;
import com.auraboot.framework.plugin.extension.iot.TimeSeriesPoint;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end IT for {@link TimeSeriesQueryController} wired to a real
 * {@link TDengineTimeSeriesPort} backed by a Testcontainers TDengine 3.x.
 *
 * <p><b>Opt-in only.</b> Default is {@code assumeTrue(false)} → reported as
 * skipped to keep the unit build fast. Run with:
 * <pre>
 *   IOT_TSPORT_IT_REAL=true ./gradlew :test \
 *       --tests com.auraboot.framework.iot.tsport.controller.TimeSeriesQueryControllerIT
 * </pre>
 *
 * <p><b>Coverage proves at the REST-edge level + real TSDB:</b>
 * <ol>
 *   <li>Seed 1000 device × 100 datapoint × 1 hour × 10 samples = 1M rows
 *       (scaled to 100 dev × 10 dp × 10 samples = 10K in this IT to keep
 *       Testcontainers REST mode reasonable; full scale is exercised in
 *       {@link com.auraboot.framework.iot.tsport.impl.TDengineTimeSeriesPortIT}).</li>
 *   <li>queryLatest via service → returns ts-DESC sample</li>
 *   <li>queryRange via service → returns expected row count</li>
 *   <li>queryAggregate via service → returns AVG buckets with pointCount</li>
 *   <li>batchQuery via service → three mixed-type entries align with input</li>
 *   <li>Cross-tenant isolation — tenant A writes, tenant B reads, MUST be empty</li>
 * </ol>
 *
 * <p>Bypasses MockMvc and Spring context to keep IT focused on the
 * service ↔ port ↔ TSDB chain. The {@link TimeSeriesQueryControllerTest}
 * already proves the REST-edge plumbing (URL routing, ApiResponse,
 * exception mapping) with mocked port.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TimeSeriesQueryControllerIT {

    private static final long TENANT_A = 7001L;
    private static final long TENANT_B = 7002L;

    private GenericContainer<?> tdengine;
    private HikariDataSource ds;
    private TDengineTimeSeriesPort port;
    private TimeSeriesQueryService service;

    @BeforeAll
    void startContainer() {
        boolean opted = "true".equalsIgnoreCase(System.getenv("IOT_TSPORT_IT_REAL"));
        Assumptions.assumeTrue(
                opted,
                "TimeSeriesQueryController IT is opt-in; set IOT_TSPORT_IT_REAL=true to run");

        // Bypass any global ~/.testcontainers.properties hub mirror — for
        // tdengine the daocloud mirror returns 403 today, and Docker Hub
        // direct is healthy. Set BEFORE GenericContainer constructs so
        // the substitutor reads the override.
        if (System.getenv("IOT_TSPORT_IT_DISABLE_HUB_PREFIX") == null
                || "true".equalsIgnoreCase(System.getenv("IOT_TSPORT_IT_DISABLE_HUB_PREFIX"))) {
            System.setProperty("hub.image.name.prefix", "");
        }

        tdengine = new GenericContainer<>("tdengine/tdengine:3.3.4.3")
                .withExposedPorts(6041)
                .waitingFor(Wait.forListeningPort());
        tdengine.start();

        String host = tdengine.getHost();
        int port6041 = tdengine.getMappedPort(6041);
        HikariConfig cfg = new HikariConfig();
        // Bootstrap the DB up-front with a one-shot connection (no DB
        // pinned), then re-target the Hikari pool URL with /iot_ctrl_it
        // so every checkout USEs the right schema.
        try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                String.format(
                        "jdbc:TAOS-RS://%s:%d/?user=root&password=taosdata",
                        host, port6041));
                java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE IF NOT EXISTS iot_ctrl_it PRECISION 'ms'");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bootstrap iot_ctrl_it database", e);
        }
        cfg.setJdbcUrl(String.format(
                "jdbc:TAOS-RS://%s:%d/iot_ctrl_it?user=root&password=taosdata&batchfetch=true",
                host, port6041));
        cfg.setUsername("root");
        cfg.setPassword("taosdata");
        cfg.setMaximumPoolSize(4);
        cfg.setAutoCommit(true);
        ds = new HikariDataSource(cfg);

        port = new TDengineTimeSeriesPort(ds);
        port.ensureSuperTable();
        service = new TimeSeriesQueryService(Optional.of(port));
    }

    @AfterAll
    void stopContainer() {
        if (ds != null) ds.close();
        if (tdengine != null) tdengine.stop();
    }

    @BeforeEach
    void pinTenant() {
        MetaContext.setContext(TENANT_A, 0L, null, "it");
    }

    @AfterEach
    void clearTenant() {
        MetaContext.clear();
    }

    /**
     * Seed 10 devices × 5 datapoints × 100 samples (= 5000 rows) for tenant A.
     * Times are dense (1-second spacing) so range/aggregate windows have
     * deterministic counts.
     */
    private Instant seedTenantA() {
        Instant t0 = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        List<TimeSeriesPoint> batch = new ArrayList<>(5000);
        for (int d = 0; d < 10; d++) {
            String deviceCode = "dev-A-" + d;
            for (int c = 0; c < 5; c++) {
                String code = "dp-" + c;
                for (int s = 0; s < 100; s++) {
                    batch.add(new TimeSeriesPoint(
                            deviceCode,
                            code,
                            t0.plus(s, ChronoUnit.SECONDS),
                            // sine-ish but cheap & deterministic: c offset + s/10
                            (double) c + s / 10.0,
                            "GOOD"));
                }
            }
        }
        port.writeBatch(TENANT_A, batch);
        return t0;
    }

    @Test
    void latest_returns_ts_desc_top_sample() {
        Instant t0 = seedTenantA();
        LatestQueryResponse r = service.queryLatest(
                "dev-A-3", List.of("dp-2"), 1, null);
        assertEquals(1, r.points().size());
        // last write for s=99 → ts = t0 + 99s, value = 2 + 9.9 = 11.9
        TimeSeriesPoint p = r.points().get(0);
        assertEquals(t0.plus(99, ChronoUnit.SECONDS), p.ts());
        assertEquals(11.9, p.value().doubleValue(), 0.001);
    }

    @Test
    void range_returns_expected_row_count_raw() {
        Instant t0 = seedTenantA();
        RangeQueryResponse r = service.queryRange(
                "dev-A-1",
                List.of("dp-0"),
                t0,
                t0.plus(60, ChronoUnit.SECONDS),
                null,
                null);
        // 60-second half-open window → expect 60 samples
        assertEquals(60, r.points().size());
    }

    @Test
    void range_with_downsample_collapses_to_buckets() {
        Instant t0 = seedTenantA();
        RangeQueryResponse r = service.queryRange(
                "dev-A-1",
                List.of("dp-0"),
                t0,
                t0.plus(60, ChronoUnit.SECONDS),
                "PT10S", // 10-second downsample → 6 buckets in 60s
                null);
        assertEquals("PT10S", r.downsampleApplied());
        assertTrue(r.points().size() <= 6,
                () -> "expected ≤ 6 downsampled points, got " + r.points().size());
    }

    @Test
    void aggregate_returns_avg_buckets_with_count() {
        Instant t0 = seedTenantA();
        AggregateQueryResponse r = service.queryAggregate(
                "dev-A-1",
                List.of("dp-0"),
                t0,
                t0.plus(60, ChronoUnit.SECONDS),
                QueryParams.Aggregation.AVG,
                "PT10S",
                null);
        assertEquals("PT10S", r.groupBy());
        assertEquals(QueryParams.Aggregation.AVG, r.aggregation());
        assertTrue(r.buckets().size() >= 1);
        assertNotNull(r.buckets().get(0).pointCount());
        assertTrue(r.buckets().get(0).pointCount() > 0);
    }

    @Test
    void batch_query_returns_aligned_results_across_types() {
        Instant t0 = seedTenantA();
        BatchQueryRequest req = new BatchQueryRequest(List.of(
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.LATEST, "dev-A-0", List.of("dp-0"),
                        1, null, null, null, null, null),
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.RANGE, "dev-A-1", List.of("dp-0"),
                        null, t0, t0.plus(10, ChronoUnit.SECONDS), null, null, null),
                new BatchQueryRequest.Query(
                        BatchQueryRequest.QueryType.AGGREGATE, "dev-A-2", List.of("dp-0"),
                        null, t0, t0.plus(30, ChronoUnit.SECONDS),
                        null, QueryParams.Aggregation.MAX, "PT10S")));
        BatchQueryResponse r = service.batchQuery(req, null);
        assertEquals(3, r.results().size());
        assertEquals("dev-A-0", r.results().get(0).deviceCode());
        assertNotNull(r.results().get(0).points());
        assertEquals(10, r.results().get(1).points().size());
        assertNotNull(r.results().get(2).buckets());
        // No error in any result
        for (BatchQueryResponse.Result res : r.results()) {
            assertEquals(null, res.errorCode(), () -> "unexpected error: " + res.errorMessage());
        }
    }

    @Test
    void cross_tenant_isolation_strict() {
        Instant t0 = seedTenantA();
        // Switch context to tenant B (which never wrote anything)
        MetaContext.clear();
        MetaContext.setContext(TENANT_B, 0L, null, "it");
        LatestQueryResponse r = service.queryLatest(
                "dev-A-0", List.of("dp-0"), 1, null);
        assertEquals(0, r.points().size(),
                "tenant B must NOT see any of tenant A's samples");

        RangeQueryResponse rr = service.queryRange(
                "dev-A-0", List.of("dp-0"),
                t0, t0.plus(60, ChronoUnit.SECONDS), null, null);
        assertEquals(0, rr.points().size());
    }
}
