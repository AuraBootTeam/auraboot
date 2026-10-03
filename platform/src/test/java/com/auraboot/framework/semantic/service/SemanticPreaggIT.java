package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Golden IT for the pre-aggregation engine (R3): the materialized view is
 * created from the governed compiled SQL, is consistent with the live
 * pipeline at refresh time, goes stale when the data moves, and converges on
 * refresh. Runs on a fresh-seed migration-only database.
 */
@Slf4j
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Semantic preagg golden IT — MV lifecycle, staleness, consistency")
class SemanticPreaggIT {

    private static final long TENANT_ID = 991_950_001L;
    private static final long USER_ID = 991_950_002L;
    private static final String META_MODEL_PID = "preagg-golden-meta-model";
    private static final String MODEL_YAML = """
            version: "0.1"

            semantic_model:
              code: preagg_golden_alias
              label:
                zh-CN: 预聚合金样对象别名
                en-US: Preagg Golden Object Alias
              description: Migration-owned object-alias table backing the preagg golden IT.
              model_ref: ab_object_alias
              primary_entity: pid

            entities:
              - name: pid
                type: primary
                field_ref: pid

            dimensions:
              - code: alias_language
                label:
                  zh-CN: 语言
                field_ref: language
                type: categorical

            measures:
              - code: alias_count
                label:
                  zh-CN: 别名数
                agg: COUNT
                field_ref: pid

            metrics:
              - code: alias_count_metric
                label:
                  zh-CN: 别名数指标
                  en-US: Alias Count Metric
                type: simple
                type_params:
                  measure: alias_count
            """;

    @Autowired
    private SemanticYamlParser parser;
    @Autowired
    private SemanticPublishService publishService;
    @Autowired
    private SemanticPreaggService preaggService;
    @Autowired
    private SemanticQueryService queryService;
    @Autowired
    private AbSemanticMetricMapper metricMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private String modelPid;
    private String metricPid;

    @PostConstruct
    void bindTenantContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
    }

    @BeforeEach
    void publishModelOnce() {
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
        if (modelPid != null) return;
        jdbc.update("INSERT INTO ab_meta_model (id, pid, tenant_id, code, table_name, "
                        + "source_type, is_current, status, version, created_at, updated_at, deleted_flag) "
                        + "VALUES (991950010, ?, ?, 'ab_object_alias', 'ab_object_alias', "
                        + "'physical', TRUE, 'published', 1, NOW(), NOW(), FALSE)",
                META_MODEL_PID, TENANT_ID);
        modelPid = publishService.publishFromYaml(
                MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", TENANT_ID, USER_ID);
        AbSemanticMetric metric = metricMapper.listActiveByModel(TENANT_ID, modelPid).get(0);
        metricPid = metric.getPid();
    }

    @AfterAll
    void retainFixturesAndClearContext() {
        // The isolated CI database is retained for owner inspection.
        MetaContext.clear();
    }

    private long liveValue() {
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
        UserContext user = new UserContext(USER_ID, TENANT_ID, java.util.Map.of());
        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("preagg_golden_alias.alias_count_metric"));
        var resp = queryService.executeQuery(req, user);
        Object value = resp.getRows().get(0).values().iterator().next();
        return value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
    }

    /**
     * The metric VALUE inside the MV (a dimension-less preagg holds exactly one
     * aggregate row whose only column is the qualified metric). Row-count of
     * the MV is always 1 and proves nothing about staleness.
     */
    private long mvMetricValue(String mvName, String metricColumn) {
        Long value = jdbc.queryForObject(
                "SELECT \"" + metricColumn + "\" FROM " + mvName, Long.class);
        return value == null ? -1 : value;
    }

    private long aliasCount() {
        return jdbc.queryForObject("SELECT count(*) FROM ab_object_alias WHERE tenant_id = ? "
                + "AND deleted_flag = FALSE", Long.class, TENANT_ID);
    }

    private long insertAliasRow(String alias) {
        jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, "
                        + "acp_priority, created_at, updated_at, created_by, updated_by, deleted_flag) "
                        + "VALUES (?, ?, 'preagg_golden', ?, 'zh-CN', 0, NOW(), NOW(), ?, ?, FALSE)",
                "pg-" + alias, TENANT_ID, alias, USER_ID, USER_ID);
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM ab_object_alias WHERE tenant_id = ? AND deleted_flag = FALSE",
                Long.class, TENANT_ID);
        return count;
    }

    @Test
    @DisplayName("Governed aggregation over 100k isolated rows has P95 below one second")
    void hundredThousandRowAggregationP95() {
        // Migration-owned table; a unique language keeps other IT fixtures out
        // of both the expected value and the measured governed query.
        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String prefix = "pg-golden-p-" + run + "-";
        String language = "p" + run;
        int inserted = jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, "
                        + "acp_priority, created_at, updated_at, created_by, updated_by, deleted_flag) "
                        + "SELECT ? || n::text, ?, 'preagg_golden', ? || n::text, ?, "
                        + "0, NOW(), NOW(), ?, ?, FALSE FROM generate_series(1, 100000) n",
                prefix, TENANT_ID, prefix, language, USER_ID, USER_ID);
        assertThat(inserted).isEqualTo(100_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_object_alias WHERE tenant_id = ? "
                + "AND language = ? AND deleted_flag = FALSE", Long.class, TENANT_ID, language))
                .isEqualTo(100_000L);

        SemanticQueryRequest request = new SemanticQueryRequest();
        request.setMetrics(List.of("preagg_golden_alias.alias_count_metric"));
        request.setFilters(List.of(new SemanticQueryRequest.Filter("alias_language", "eq", language)));
        UserContext user = new UserContext(USER_ID, TENANT_ID, java.util.Map.of());
        int warmups = 5;
        int samples = 40;
        long[] durations = new long[samples];
        long coldNanos = 0;
        for (int i = 0; i < warmups + samples; i++) {
            long started = System.nanoTime();
            var response = queryService.executeQuery(request, user);
            long elapsed = System.nanoTime() - started;
            if (i == 0) coldNanos = elapsed;
            // Verify every result, so a cheap empty or incorrect query cannot
            // satisfy the latency bound. Compilation, DB and audit are timed.
            assertThat(response.getRows()).hasSize(1);
            assertThat(response.getRows().get(0)).hasSize(1);
            Object value = response.getRows().get(0).values().iterator().next();
            assertThat(((Number) value).longValue()).isEqualTo(100_000L);
            if (i >= warmups) durations[i - warmups] = elapsed;
        }
        long[] sorted = durations.clone();
        Arrays.sort(sorted);
        long p95Nanos = sorted[(int) Math.ceil(samples * 0.95) - 1];
        log.info("BI_R3_PERF tenant={} rows={} warmups={} samples={} coldMs={} p95Ms={} samplesNs={}",
                TENANT_ID, inserted, warmups, samples, coldNanos / 1_000_000.0,
                p95Nanos / 1_000_000.0, Arrays.toString(durations));
        assertThat(p95Nanos).as("100k governed aggregation P95, nearest-rank over 40 samples")
                .isLessThan(1_000_000_000L);
    }

    @Test
    @DisplayName("A failed metadata update restores the prior MV snapshot and refresh timestamp")
    void failedRefreshRollsBackViewAndMetadataTogether() {
        insertAliasRow("golden-rollback-one");
        AbSemanticPreagg preagg = preaggService.create(
                "preagg-refresh-rollback", modelPid, "alias_count_metric", List.of(), 60);
        String column = "preagg_golden_alias.alias_count_metric";
        long before = mvMetricValue(preagg.getMvName(), column);
        var timestamp = jdbc.queryForObject("SELECT last_refreshed_at FROM ab_semantic_preagg WHERE pid = ?",
                java.time.OffsetDateTime.class, preagg.getPid());
        insertAliasRow("golden-rollback-two");
        assertThat(liveValue()).isGreaterThan(before);
        String constraint = "preagg_it_" + preagg.getPid().toLowerCase();
        // NOT VALID allows the existing row, but fails its next metadata UPDATE,
        // after DROP/CREATE has already rebuilt the materialized view.
        jdbc.execute("ALTER TABLE ab_semantic_preagg ADD CONSTRAINT " + constraint
                + " CHECK (pid <> '" + preagg.getPid() + "') NOT VALID");
        try {
            assertThatThrownBy(() -> preaggService.refreshNow(preagg.getPid()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(mvMetricValue(preagg.getMvName(), column)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT last_refreshed_at FROM ab_semantic_preagg WHERE pid = ?",
                    java.time.OffsetDateTime.class, preagg.getPid())).isEqualTo(timestamp);
        } finally {
            jdbc.execute("ALTER TABLE ab_semantic_preagg DROP CONSTRAINT " + constraint);
        }
    }

    @Test
    @DisplayName("A dependent view prevents deletion without hiding the active definition")
    void failedDeleteKeepsDefinitionAndMaterializedView() {
        AbSemanticPreagg preagg = preaggService.create(
                "preagg-delete-rollback", modelPid, "alias_count_metric", List.of(), 60);
        String dependent = "preagg_dep_" + preagg.getPid().toLowerCase();
        jdbc.execute("CREATE VIEW " + dependent + " AS SELECT * FROM " + preagg.getMvName());
        try {
            assertThatThrownBy(() -> preaggService.delete(preagg.getPid()))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(preaggService.list()).extracting(AbSemanticPreagg::getPid).contains(preagg.getPid());
            assertThat(jdbc.queryForObject("SELECT deleted_flag FROM ab_semantic_preagg WHERE pid = ?",
                    Boolean.class, preagg.getPid())).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_matviews WHERE matviewname = ?",
                    Integer.class, preagg.getMvName())).isEqualTo(1);
        } finally {
            jdbc.execute("DROP VIEW " + dependent);
        }
    }

    @Test
    @DisplayName("MV is consistent at refresh, goes stale on data change, converges on refresh")
    void goldenPreaggLifecycle() {
        // The independent SQL count includes retained fixtures; each owned
        // insertion must increase the governed value by exactly one.
        long baseline = aliasCount();
        long afterFirst = insertAliasRow("golden-one");
        assertThat(afterFirst).isEqualTo(baseline + 1);

        AbSemanticPreagg preagg = preaggService.create(
                "preagg-golden", modelPid, "alias_count_metric", List.of(), 60);
        String mvName = preagg.getMvName();

        String metricColumn = "preagg_golden_alias." + preagg.getMetricCode();

        // Consistency at creation: the MV's metric value equals the live governed value.
        assertThat(mvMetricValue(mvName, metricColumn)).isEqualTo(liveValue()).isEqualTo(afterFirst);
        assertThat(preagg.getLastRefreshedAt()).isNotNull();

        // Data moves: the live value moves, the MV keeps the refresh snapshot (stale).
        long afterSecond = insertAliasRow("golden-two");
        assertThat(afterSecond).isEqualTo(baseline + 2);
        assertThat(liveValue()).isEqualTo(afterSecond);
        assertThat(mvMetricValue(mvName, metricColumn)).isNotEqualTo(liveValue());

        // Refresh converges the MV's metric value to the live value.
        preaggService.refreshNow(preagg.getPid());
        assertThat(mvMetricValue(mvName, metricColumn)).isEqualTo(liveValue()).isEqualTo(afterSecond);

        // Delete drops the MV together with the definition.
        preaggService.delete(preagg.getPid());
        Integer mvLeft = jdbc.queryForObject(
                "SELECT count(*) FROM pg_matviews WHERE matviewname = ?",
                Integer.class, mvName.replace("\"", ""));
        assertThat(mvLeft).isZero();
    }
    @Test
    @DisplayName("The scheduled sweep refreshes due data and leaves a not-due MV stale")
    void scheduledRefreshHonorsIntervalAndRestoresCaller() {
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
        long baseline = aliasCount();
        AbSemanticPreagg preagg = preaggService.create(
                "preagg-scheduled", modelPid, "alias_count_metric", List.of(), 60);
        String column = "preagg_golden_alias.alias_count_metric";
        assertThat(mvMetricValue(preagg.getMvName(), column)).isEqualTo(baseline);
        // Arrange only this test's persisted timer; no fixed sleep or shared-row repair.
        assertThat(jdbc.update("UPDATE ab_semantic_preagg SET last_refreshed_at = NOW() - INTERVAL '61 minutes' "
                + "WHERE tenant_id = ? AND pid = ?", TENANT_ID, preagg.getPid())).isEqualTo(1);
        var dueTimestamp = jdbc.queryForObject("SELECT last_refreshed_at FROM ab_semantic_preagg WHERE pid = ?",
                java.time.OffsetDateTime.class, preagg.getPid());
        assertThat(insertAliasRow("golden-scheduled-change")).isEqualTo(baseline + 1);

        MetaContext.setMemberId(991_950_003L);
        MetaContext.setEnvironmentId(991_950_004L);
        MetaContext.setOtelTraceId("preagg-scheduled-trace");
        MetaContext.Snapshot caller = MetaContext.snapshot();
        preaggService.refreshAllDue();
        assertThat(mvMetricValue(preagg.getMvName(), column)).isEqualTo(baseline + 1);
        var refreshed = jdbc.queryForObject("SELECT last_refreshed_at FROM ab_semantic_preagg WHERE pid = ?",
                java.time.OffsetDateTime.class, preagg.getPid());
        assertThat(refreshed).isAfter(dueTimestamp);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);

        assertThat(insertAliasRow("golden-not-due-change")).isEqualTo(baseline + 2);
        preaggService.refreshAllDue();
        assertThat(mvMetricValue(preagg.getMvName(), column)).isEqualTo(baseline + 1);
        assertThat(jdbc.queryForObject("SELECT last_refreshed_at FROM ab_semantic_preagg WHERE pid = ?",
                java.time.OffsetDateTime.class, preagg.getPid())).isEqualTo(refreshed);
        assertThat(aliasCount()).isEqualTo(baseline + 2);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }
}
