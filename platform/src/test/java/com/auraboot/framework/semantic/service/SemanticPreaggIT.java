package com.auraboot.framework.semantic.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticPreagg;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricMapper;
import com.auraboot.framework.semantic.mapper.AbSemanticPreaggMapper;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.TaskExecutionOutcome;
import org.slf4j.LoggerFactory;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    private AbSemanticPreaggMapper preaggMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ScheduledAnnotationBeanPostProcessor scheduler;

    private String modelPid;
    private String metricPid;

    @PostConstruct
    void bindTenantContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
    }

    @BeforeEach
    void publishModelOnce() {
        // Reused development DBs can retain this fixture after a failed older teardown.
        if (modelPid == null) cleanup();
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
        if (modelPid != null) return;
        jdbc.update("DELETE FROM ab_meta_model WHERE id = 991950010 OR pid = ?", META_MODEL_PID);
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
    void cleanup() {
        MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
        try {
            jdbc.execute("DROP MATERIALIZED VIEW IF EXISTS mv_semantic_preagg_it_golden");
            for (Object[] stmt : new Object[][]{
                    {"DELETE FROM ab_object_alias WHERE tenant_id = ? AND pid LIKE 'pg-golden-%'", TENANT_ID},
                    {"DELETE FROM ab_semantic_preagg WHERE tenant_id = ?", TENANT_ID},
                    {"DELETE FROM ab_semantic_metric WHERE semantic_model_pid = ?", modelPid},
                    {"DELETE FROM ab_semantic_dimension WHERE semantic_model_pid = ?", modelPid},
                    {"DELETE FROM ab_semantic_model WHERE pid = ?", modelPid},
                    {"DELETE FROM ab_meta_model WHERE pid = ? OR id = 991950010", META_MODEL_PID}}) {
                jdbc.update((String) stmt[0], stmt[1]);
            }
        } finally {
            MetaContext.clear();
        }
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

    private long insertAliasRow(String alias) {
        jdbc.update("DELETE FROM ab_object_alias WHERE pid = ?", "pg-" + alias);
        jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, "
                        + "acp_priority, created_at, updated_at, created_by, updated_by, deleted_flag) "
                        + "VALUES (?, ?, 'preagg_golden', ?, 'zh-CN', 0, NOW(), NOW(), ?, ?, FALSE)",
                "pg-" + alias, TENANT_ID, alias, USER_ID, USER_ID);
        long count = jdbc.queryForObject(
                "SELECT count(*) FROM ab_object_alias WHERE tenant_id = ? AND pid LIKE 'pg-golden-%'",
                Long.class, TENANT_ID);
        return count;
    }

    @Test
    @DisplayName("MV is consistent at refresh, goes stale on data change, converges on refresh")
    void goldenPreaggLifecycle() {
        // Cross-run leftovers break the deterministic counts: purge every
        // golden row before the lifecycle starts.
        jdbc.update("DELETE FROM ab_object_alias WHERE tenant_id = ? AND pid LIKE 'pg-golden-%'", TENANT_ID);
        long afterFirst = insertAliasRow("golden-one");

        AbSemanticPreagg preagg = preaggService.create(
                "preagg-golden", modelPid, "alias_count_metric", List.of(), 60);
        String mvName = preagg.getMvName();

        String metricColumn = "preagg_golden_alias." + preagg.getMetricCode();

        // Consistency at creation: the MV's metric value equals the live governed value.
        assertThat(mvMetricValue(mvName, metricColumn)).isEqualTo(liveValue());
        assertThat(preagg.getLastRefreshedAt()).isNotNull();

        // Data moves: the live value moves, the MV keeps the refresh snapshot (stale).
        long afterSecond = insertAliasRow("golden-two");
        assertThat(liveValue()).isEqualTo(afterSecond);
        assertThat(mvMetricValue(mvName, metricColumn)).isNotEqualTo(liveValue());

        // Refresh converges the MV's metric value to the live value.
        preaggService.refreshNow(preagg.getPid());
        assertThat(mvMetricValue(mvName, metricColumn)).isEqualTo(liveValue()).isEqualTo(afterSecond);

        // Delete drops the MV together with the definition.
        preaggService.delete(preagg.getPid());
        assertThat(preaggMapper.findByPid(TENANT_ID, preagg.getPid())).isNull();
        assertThat(preaggService.list()).extracting(AbSemanticPreagg::getPid)
                .doesNotContain(preagg.getPid());
        assertThat(MetaContext.runWithoutTenantFilter(preaggMapper::listAllAcrossTenants))
                .extracting(AbSemanticPreagg::getPid).doesNotContain(preagg.getPid());
        Integer mvLeft = jdbc.queryForObject(
                "SELECT count(*) FROM pg_matviews WHERE matviewname = ?",
                Integer.class, mvName.replace("\"", ""));
        assertThat(mvLeft).isZero();
    }

    @Test
    @DisplayName("Due sweep refreshes the governed metric without caller context")
    void dueSweepWithoutCallerContext() {
        // ab_object_alias.pid is varchar(26); the helper also adds a "pg-" prefix.
        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertAliasRow("golden-" + run + "-1");
        AbSemanticPreagg preagg = preaggService.create(
                "preagg-sweep-" + run, modelPid, "alias_count_metric", List.of(), 1);
        try {
            String metricColumn = "preagg_golden_alias." + preagg.getMetricCode();
            long before = mvMetricValue(preagg.getMvName(), metricColumn);
            insertAliasRow("golden-" + run + "-2");
            long expected = liveValue();
            assertThat(expected).isGreaterThan(before);
            OffsetDateTime stale = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2);
            preagg.setLastRefreshedAt(stale);
            preaggMapper.updateById(preagg);

            MetaContext.clear();
            preaggService.refreshAllDue();
            // Check the scheduler boundary before any helper rebinds the tenant.
            assertThat(MetaContext.exists()).isFalse();
            assertThat(mvMetricValue(preagg.getMvName(), metricColumn)).isEqualTo(expected);
            MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
            assertThat(preaggMapper.findByPid(TENANT_ID, preagg.getPid()).getLastRefreshedAt())
                    .isAfter(stale);
        } finally {
            MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
            preaggService.delete(preagg.getPid());
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("Real scheduled sweep refreshes due data during a two-minute observation")
    void scheduledSweepDuringTwoMinuteObservation() throws Exception {
        // Spring 6.2 wraps the registered runnable to track its execution outcome.
        var registered = scheduler.getScheduledTasks().stream()
                .filter(task -> task.toString().equals(SemanticPreaggService.class.getName() + ".refreshAllDue"))
                .toList();
        assertThat(registered).hasSize(1);
        var scheduledTask = registered.get(0).getTask();
        Logger serviceLogger = (Logger) LoggerFactory.getLogger(SemanticPreaggService.class);
        ListAppender<ILoggingEvent> observationLog = new ListAppender<>();
        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        insertAliasRow("golden-" + run + "-1");
        AbSemanticPreagg preagg = preaggService.create(
                "preagg-timer-" + run, modelPid, "alias_count_metric", List.of(), 1);
        observationLog.start();
        serviceLogger.addAppender(observationLog);
        try {
            String column = "preagg_golden_alias." + preagg.getMetricCode();
            long before = mvMetricValue(preagg.getMvName(), column);
            insertAliasRow("golden-" + run + "-2");
            long expected = liveValue();
            assertThat(expected).isGreaterThan(before);
            preagg.setLastRefreshedAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2));
            preaggMapper.updateById(preagg);
            OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
            long started = System.nanoTime();
            MetaContext.clear();
            while (System.nanoTime() - started < Duration.ofMinutes(2).toNanos()) {
                Thread.sleep(1_000);
                assertThat(MetaContext.exists()).isFalse();
            }
            long actual = mvMetricValue(preagg.getMvName(), column);
            MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
            OffsetDateTime refreshedAt = preaggMapper.findByPid(TENANT_ID, preagg.getPid())
                    .getLastRefreshedAt();
            long outcomeDeadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (scheduledTask.getLastExecutionOutcome().status() == TaskExecutionOutcome.Status.STARTED
                    && System.nanoTime() < outcomeDeadline) {
                Thread.sleep(100);
            }
            var outcome = scheduledTask.getLastExecutionOutcome();
            var warnings = observationLog.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage).toList();
            long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("runtime", System.getenv("AURA_RUNTIME_NAME"));
            evidence.put("sourceSha", System.getenv("AURA_RUNTIME_SOURCE_OSS_COMMIT"));
            evidence.put("tenantId", TENANT_ID);
            evidence.put("preaggPid", preagg.getPid());
            evidence.put("mvName", preagg.getMvName());
            evidence.put("startedAt", startedAt.toString());
            evidence.put("elapsedMs", elapsedMs);
            evidence.put("before", before);
            evidence.put("expected", expected);
            evidence.put("actual", actual);
            evidence.put("refreshedAt", refreshedAt.toString());
            evidence.put("scheduledExecutionAt", outcome.executionTime() == null ? null : outcome.executionTime().toString());
            evidence.put("scheduledStatus", outcome.status().name());
            evidence.put("warnings", warnings);
            String ownedEvidence = System.getenv("AURA_EVIDENCE_ROOT");
            Path evidenceDir = ownedEvidence == null ? Path.of("build/reports/preagg") : Path.of(ownedEvidence);
            Files.createDirectories(evidenceDir);
            Files.writeString(evidenceDir.resolve("preagg-scheduled-" + run + ".json"),
                    new ObjectMapper().writeValueAsString(evidence));
            log.info("Preagg scheduled observation runtime={} pid={} start={} elapsedMs={} "
                            + "before={} expected={} actual={} refreshedAt={}",
                    System.getenv("AURA_RUNTIME_NAME"), preagg.getPid(), startedAt,
                    Duration.ofNanos(System.nanoTime() - started).toMillis(),
                    before, expected, actual, refreshedAt);
            assertThat(elapsedMs).isGreaterThanOrEqualTo(120_000L);
            assertThat(actual).isEqualTo(expected);
            assertThat(refreshedAt).isAfter(startedAt);
            assertThat(outcome.status()).isEqualTo(TaskExecutionOutcome.Status.SUCCESS);
            assertThat(outcome.executionTime()).isAfter(startedAt.toInstant());
            assertThat(warnings).isEmpty();
        } finally {
            serviceLogger.detachAppender(observationLog);
            observationLog.stop();
            MetaContext.setContext(TENANT_ID, USER_ID, "preagg-golden-pid", "preagg-golden-user");
            preaggService.delete(preagg.getPid());
        }
    }
}
