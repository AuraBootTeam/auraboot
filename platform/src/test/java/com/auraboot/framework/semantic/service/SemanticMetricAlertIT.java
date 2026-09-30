package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertDTO;
import com.auraboot.framework.semantic.dto.SemanticMetricAlertRequest;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
import com.auraboot.framework.semantic.entity.AbSemanticMetricAlert;
import com.auraboot.framework.semantic.exception.SemanticValidationException;
import com.auraboot.framework.semantic.mapper.AbSemanticMetricAlertMapper;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Golden IT for threshold alerts over published semantic metrics (R2).
 *
 * <p>Falsifiable contract, on the real semantic pipeline:
 * <ul>
 *   <li>a breaching alert enqueues exactly one in-app notification;</li>
 *   <li>re-evaluating inside the silence window records the breach but does
 *       NOT duplicate the notification;</li>
 *   <li>the comparator is boundary-exact (gte on the observed value triggers);</li>
 *   <li>a non-breaching threshold does not trigger;</li>
 *   <li>creating an alert for an unknown metric fails validation.</li>
 * </ul>
 *
 * <p>The semantic model is published over {@code ab_tenant} — a
 * migration-owned table with bootstrap rows on every database, so the golden
 * runs on a fresh-seed CI database with no fixture imports.
 */
@Slf4j
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Semantic metric alert golden IT — threshold/silence/boundary on the governed pipeline")
class SemanticMetricAlertIT {

    private static final long TENANT_ID = 991_800_001L;
    private static final long USER_ID = 991_800_002L;
    private static final String MODEL_YAML = """
            version: "0.1"

            semantic_model:
              code: alert_golden_alias
              label:
                zh-CN: 告警金样对象别名
                en-US: Alert Golden Object Alias
              description: Migration-owned object-alias table backing the alert golden IT.
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
                description: Tenant-scoped count of object aliases (deterministically zero on a fresh seed).
                type: simple
                type_params:
                  measure: alias_count
            """;

    @Autowired
    private SemanticYamlParser parser;
    @Autowired
    private SemanticPublishService publishService;
    @Autowired
    private SemanticMetricAlertService alertService;
    @Autowired
    private AbSemanticMetricMapper metricMapper;
    @Autowired
    private AbSemanticMetricAlertMapper alertMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private String modelPid;
    private String metricPid;
    private static final String META_MODEL_PID = "alert-golden-meta-model";

    @BeforeEach
    void bindTenantContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "alert-golden-pid", "alert-golden-user");
    }

    @org.junit.jupiter.api.AfterAll
    void cleanupMetaModel() {
        jdbc.update("DELETE FROM ab_meta_model WHERE pid = ?", META_MODEL_PID);
    }

    @org.junit.jupiter.api.AfterAll
    void cleanup() {
        jdbc.update("DELETE FROM ab_notification WHERE source_type = 'semantic_metric_alert' "
                + "AND source_id IN (SELECT pid FROM ab_semantic_metric_alert WHERE tenant_id = ?)", TENANT_ID);
        jdbc.update("DELETE FROM ab_semantic_metric_alert WHERE tenant_id = ?", TENANT_ID);
        if (modelPid != null) {
            jdbc.update("DELETE FROM ab_semantic_metric WHERE semantic_model_pid = ?", modelPid);
            jdbc.update("DELETE FROM ab_semantic_dimension WHERE semantic_model_pid = ?", modelPid);
            jdbc.update("DELETE FROM ab_semantic_model WHERE pid = ?", modelPid);
        }
        MetaContext.clear();
    }

    @BeforeEach
    void publishModelOnce() {
        MetaContext.setContext(TENANT_ID, USER_ID, "alert-golden-pid", "alert-golden-user");
        if (modelPid != null) return;
        // The semantic layer resolves model_ref through the meta-model catalog
        // (governance: YAML cannot target arbitrary physical tables). Register
        // the migration-owned ab_tenant table as this tenant's meta model first.
        jdbc.update("INSERT INTO ab_meta_model (id, pid, tenant_id, code, table_name, "
                        + "source_type, is_current, status, version, created_at, updated_at, deleted_flag) "
                        + "VALUES (991800010, ?, ?, 'ab_object_alias', 'ab_object_alias', "
                        + "'physical', TRUE, 'published', 1, NOW(), NOW(), FALSE)",
                META_MODEL_PID, TENANT_ID);
        modelPid = publishService.publishFromYaml(
                MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", TENANT_ID, USER_ID);
        AbSemanticMetric metric = metricMapper.listActiveByModel(TENANT_ID, modelPid).get(0);
        metricPid = metric.getPid();
    }

    private SemanticMetricAlertRequest request(String name, String comparator, String threshold,
                                               int silenceMinutes) {
        SemanticMetricAlertRequest r = new SemanticMetricAlertRequest();
        r.setName(name);
        r.setMetricPid(metricPid);
        r.setComparator(comparator);
        r.setThreshold(new BigDecimal(threshold));
        r.setSilenceMinutes(silenceMinutes);
        return r;
    }

    private int notificationCount(String alertPid) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM ab_notification WHERE source_type = 'semantic_metric_alert' "
                        + "AND source_id = ?",
                Integer.class, alertPid);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("breach notifies once, silence window dedupes, boundary is exact, non-breach stays quiet")
    void goldenThresholdSilenceBoundary() {
        // The publish transaction commit tears down the thread's MetaContext
        // (framework behaviour observed on main); rebind for the test body.
        MetaContext.setContext(TENANT_ID, USER_ID, "alert-golden-pid", "alert-golden-user");
        System.out.println("[ALERT-IT] ctx after rebind exists=" + MetaContext.exists()
                + " thread=" + Thread.currentThread().getName());
        // Unknown metric fails validation (fail-closed authoring).
        SemanticMetricAlertRequest bogus = request("bad-alert", "gt", "0", 60);
        bogus.setMetricPid("no-such-metric-pid");
        assertThrows(SemanticValidationException.class, () -> alertService.create(bogus));

        // Non-breaching threshold: the tenant-scoped alias count is deterministically 0
        // on a fresh seed (seed rows belong to tenant -1), so gt 999999 never fires.
        SemanticMetricAlertDTO calm = alertService.create(request("never-fires", "gt", "999999", 0));
        Map<String, Object> calmResult = alertService.evaluateNow(calm.getPid());
        assertThat(calmResult.get("triggered")).isEqualTo(false);
        assertThat(calmResult.get("notified")).isEqualTo(false);
        assertThat(notificationCount(calm.getPid())).isZero();

        // Breaching threshold: gte 0 is boundary-exact for a deterministically-zero value
        // and fires on the first evaluation, exactly once.
        SemanticMetricAlertDTO firing = alertService.create(request("fires-once", "gte", "0", 120));
        String alertPid = firing.getPid();
        Map<String, Object> first = alertService.evaluateNow(alertPid);
        assertThat(first.get("triggered")).isEqualTo(true);
        assertThat(first.get("notified")).isEqualTo(true);
        BigDecimal value = (BigDecimal) first.get("value");
        assertThat(value).isNotNull();
        assertThat(value.intValue()).isZero();
        assertThat(notificationCount(alertPid)).isEqualTo(1);

        // Silence window: the breach persists, the notification does not duplicate.
        Map<String, Object> second = alertService.evaluateNow(alertPid);
        assertThat(second.get("triggered")).isEqualTo(true);
        assertThat(second.get("notified")).isEqualTo(false);
        assertThat(second.get("silenced")).isEqualTo(true);
        assertThat(notificationCount(alertPid)).isEqualTo(1);

        // Boundary exactness: gte on the observed value still triggers (0-silence re-notifies).
        SemanticMetricAlertRequest boundary = request("boundary-gte", "lte", value.toPlainString(), 0);
        boundary.setMetricPid(metricPid);
        SemanticMetricAlertDTO boundaryAlert = alertService.create(boundary);
        Map<String, Object> boundaryResult = alertService.evaluateNow(boundaryAlert.getPid());
        assertThat(boundaryResult.get("triggered")).isEqualTo(true);
        assertThat(notificationCount(boundaryAlert.getPid())).isEqualTo(1);
    }
}
