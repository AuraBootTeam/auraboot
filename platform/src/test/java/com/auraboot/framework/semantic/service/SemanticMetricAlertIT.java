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
 * <p>The semantic model is published over {@code ab_object_alias} — a
 * migration-owned table populated with three owned rows, so the golden
 * runs on a fresh-seed CI database with a controlled positive metric value.
 */
@Slf4j
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Semantic metric alert golden IT — threshold/silence/boundary on the governed pipeline")
class SemanticMetricAlertIT {

    private long tenantId;
    private long userId;
    private SemanticAcceptanceIdentity identity;
    @Autowired private com.auraboot.framework.user.service.UserService fixtureUsers;
    @Autowired private com.auraboot.framework.tenant.service.TenantService fixtureTenants;
    @Autowired private com.auraboot.framework.tenant.service.TenantMemberService fixtureMembers;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService fixtureSources;
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
                description: Tenant-scoped count of object aliases (three controlled rows on a fresh seed).
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

    private void ensureIdentity() {
        if (identity == null) {
            identity = SemanticAcceptanceIdentity.create(fixtureUsers, fixtureTenants, fixtureMembers, "alert");
            tenantId = identity.tenantId(); userId = identity.userId();
        }
    }

    @BeforeEach
    void bindTenantContext() {
        ensureIdentity();
        identity.bind();
        MetaContext.setMemberId(identity.memberId());
        MetaContext.setEnvironmentId(992_180_004L);
        MetaContext.setOtelTraceId("alert-fixture-trace");
    }

    @AfterAll
    void retainFixturesAndPauseBackgroundEvaluation() {
        bindTenantContext();
        for (SemanticMetricAlertDTO existing : alertService.list()) {
            SemanticMetricAlertRequest paused = request(existing.getName(), existing.getComparator(),
                    existing.getThreshold().toPlainString(), existing.getSilenceMinutes());
            paused.setAlertStatus("paused");
            alertService.update(existing.getPid(), paused);
        }
        // Keep definitions, query logs and notifications; stop scheduled writes to this fixture.
        MetaContext.clear();
    }

    @BeforeEach
    void publishModelOnce() {
        ensureIdentity();
        identity.bind();
        if (modelPid != null) return;
        // The semantic layer resolves model_ref through the meta-model catalog
        // (governance: YAML cannot target arbitrary physical tables). Register
        // the migration-owned ab_object_alias table as this tenant's meta model first.
        identity.registerSource(fixtureSources);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_object_alias WHERE tenant_id = ?",
                Long.class, tenantId)).as("fresh alert fixture namespace").isZero();
        for (int n = 1; n <= 3; n++) {
            jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                            + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                            + "VALUES (?, ?, 'alert_golden', ?, 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                    com.auraboot.framework.common.util.UniqueIdGenerator.generate(), tenantId,
                    "Alert fixture " + n, userId, userId);
        }
        modelPid = publishService.publishFromYaml(
                MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", tenantId, userId);
        AbSemanticMetric metric = metricMapper.listActiveByModel(tenantId, modelPid).get(0);
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
        bindTenantContext();
        MetaContext.Snapshot caller = MetaContext.snapshot();
        // Unknown metric fails validation (fail-closed authoring).
        SemanticMetricAlertRequest bogus = request("bad-alert", "gt", "0", 60);
        bogus.setMetricPid("no-such-metric-pid");
        assertThrows(SemanticValidationException.class, () -> alertService.create(bogus));

        // Three owned fixture rows make the non-breach and exact positive boundary falsifiable.
        SemanticMetricAlertDTO calm = alertService.create(request("never-fires", "gt", "999999", 0));
        Map<String, Object> calmResult = alertService.evaluateNow(calm.getPid());
        assertThat(calmResult.get("triggered")).isEqualTo(false);
        assertThat(calmResult.get("notified")).isEqualTo(false);
        assertThat(notificationCount(calm.getPid())).isZero();

        // gte 3 is boundary-exact for the controlled positive count.
        SemanticMetricAlertDTO firing = alertService.create(request("fires-once", "gte", "3", 120));
        String alertPid = firing.getPid();
        Map<String, Object> first = alertService.evaluateNow(alertPid);
        assertThat(first.get("triggered")).isEqualTo(true);
        assertThat(first.get("notified")).isEqualTo(true);
        BigDecimal value = (BigDecimal) first.get("value");
        assertThat(value).isNotNull();
        assertThat(value).isEqualByComparingTo("3");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
        assertThat(notificationCount(alertPid)).isEqualTo(1);

        // Silence window: the breach persists, the notification does not duplicate.
        Map<String, Object> second = alertService.evaluateNow(alertPid);
        assertThat(second.get("triggered")).isEqualTo(true);
        assertThat(second.get("notified")).isEqualTo(false);
        assertThat(second.get("silenced")).isEqualTo(true);
        assertThat(notificationCount(alertPid)).isEqualTo(1);

        // Boundary exactness: lte on the observed value still triggers (zero silence).
        SemanticMetricAlertRequest boundary = request("boundary-gte", "lte", value.toPlainString(), 0);
        boundary.setMetricPid(metricPid);
        SemanticMetricAlertDTO boundaryAlert = alertService.create(boundary);
        Map<String, Object> boundaryResult = alertService.evaluateNow(boundaryAlert.getPid());
        assertThat(boundaryResult.get("triggered")).isEqualTo(true);
        assertThat(notificationCount(boundaryAlert.getPid())).isEqualTo(1);
    }
    @Test
    @DisplayName("CRUD, pause/resume, scheduled evaluation and tenant isolation retain exact state")
    void lifecyclePauseResumeAndScheduledEvaluation() {
        bindTenantContext();
        MetaContext.Snapshot caller = MetaContext.snapshot();
        SemanticMetricAlertRequest paused = request("lifecycle-paused", "gte", "3", 120);
        paused.setAlertStatus("paused");
        String pid = alertService.create(paused).getPid();
        assertThat(alertService.list()).extracting(SemanticMetricAlertDTO::getPid).contains(pid);
        assertThat(alertMapper.findByPid(tenantId, pid).getAlertStatus()).isEqualTo("paused");
        assertThat(alertMapper.findByPid(tenantId, pid).getLastEvaluatedAt()).isNull();

        Map<String, Object> beforeInvalid = jdbc.queryForMap(
                "SELECT * FROM ab_semantic_metric_alert WHERE tenant_id = ? AND pid = ?", tenantId, pid);
        assertThrows(SemanticValidationException.class,
                () -> alertService.update(pid, request("invalid", "between", "3", 120)));
        assertThat(jdbc.queryForMap("SELECT * FROM ab_semantic_metric_alert WHERE tenant_id = ? AND pid = ?",
                tenantId, pid)).isEqualTo(beforeInvalid);

        try {
            MetaContext.setContext(992_180_099L, 992_180_098L, "other-tenant", "other-user");
            assertThat(alertService.list()).extracting(SemanticMetricAlertDTO::getPid).doesNotContain(pid);
            assertThrows(SemanticValidationException.class, () -> alertService.update(pid, paused));
            assertThrows(SemanticValidationException.class, () -> alertService.delete(pid));
            assertThrows(SemanticValidationException.class, () -> alertService.evaluateNow(pid));
        } finally {
            MetaContext.clear();
            MetaContext.restore(caller);
        }
        assertThat(jdbc.queryForMap("SELECT * FROM ab_semantic_metric_alert WHERE tenant_id = ? AND pid = ?",
                tenantId, pid)).isEqualTo(beforeInvalid);

        // Calling the real scheduler entry proves paused rows are excluded, and
        // then that the same owned row is evaluated after authoring resumes it.
        alertService.evaluateAll();
        assertThat(alertMapper.findByPid(tenantId, pid).getLastEvaluatedAt()).isNull();
        assertThat(notificationCount(pid)).isZero();
        assertThat(MetaContext.snapshot()).isEqualTo(caller);

        SemanticMetricAlertRequest active = request("lifecycle-active", "gte", "3", 120);
        active.setAlertStatus("active");
        SemanticMetricAlertDTO resumed = alertService.update(pid, active);
        assertThat(resumed.getName()).isEqualTo("lifecycle-active");
        assertThat(resumed.getThreshold()).isEqualByComparingTo("3");
        assertThat(resumed.getAlertStatus()).isEqualTo("active");
        alertService.evaluateAll();
        assertThat(alertMapper.findByPid(tenantId, pid).getLastEvaluatedAt()).isNotNull();
        assertThat(alertMapper.findByPid(tenantId, pid).getLastTriggeredAt()).isNotNull();
        assertThat(notificationCount(pid)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT content FROM ab_notification WHERE tenant_id = ? "
                        + "AND user_id = ? AND source_type = 'semantic_metric_alert' AND source_id = ?",
                String.class, tenantId, userId, pid)).contains("当前值 3", "阈值 3");
        assertThat(MetaContext.snapshot()).isEqualTo(caller);

        SemanticMetricAlertRequest calm = request("lifecycle-no-breach", "gt", "999999", 120);
        alertService.update(pid, calm);
        assertThat(alertService.evaluateNow(pid)).containsEntry("triggered", false).containsEntry("notified", false);
        assertThat(notificationCount(pid)).isEqualTo(1);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);

        calm.setAlertStatus("paused");
        alertService.update(pid, calm);
        var pausedAt = alertMapper.findByPid(tenantId, pid).getLastEvaluatedAt();
        alertService.evaluateAll();
        assertThat(alertMapper.findByPid(tenantId, pid).getLastEvaluatedAt()).isEqualTo(pausedAt);
        assertThat(notificationCount(pid)).isEqualTo(1);
        alertService.delete(pid);
        assertThat(alertService.list()).extracting(SemanticMetricAlertDTO::getPid).doesNotContain(pid);
        assertThat(jdbc.queryForObject("SELECT deleted_flag FROM ab_semantic_metric_alert WHERE tenant_id = ? AND pid = ?",
                Boolean.class, tenantId, pid)).isTrue();
        assertThrows(SemanticValidationException.class, () -> alertService.evaluateNow(pid));
        assertThat(notificationCount(pid)).isEqualTo(1);
        assertThat(MetaContext.snapshot()).isEqualTo(caller);
    }
}
