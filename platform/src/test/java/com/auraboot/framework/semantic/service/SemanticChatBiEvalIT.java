package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.MetricCompileException;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
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

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Evaluation harness for the ChatBI governed answer path (BI rectification
 * R5, offline tier).
 *
 * <p>The live NL→metric mapping is provider-side (real LLM, owner-gated
 * credentials). What CAN be pinned without a provider is the contract every
 * mapped question must go through: the governed semantic pipeline answers
 * each catalog question with rows, and a metric the user is not allowed to
 * see is denied before any SQL runs. When the provider is unlocked, the same
 * harness gains an NL-resolution tier in front of these checks.
 */
@Slf4j
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ChatBI governed answer path eval — catalog questions + permission denial")
class SemanticChatBiEvalIT {

    private long tenantId;
    private long userId;
    private SemanticAcceptanceIdentity identity;
    @Autowired private com.auraboot.framework.user.service.UserService fixtureUsers;
    @Autowired private com.auraboot.framework.tenant.service.TenantService fixtureTenants;
    @Autowired private com.auraboot.framework.tenant.service.TenantMemberService fixtureMembers;
    @Autowired private com.auraboot.framework.meta.service.MetaModelService fixtureSources;

    /**
     * Offline evaluation set: catalog questions mapped to governed metrics.
     * With a live provider the NL→metric step sits in front of these; the
     * governed execution + authorization checks below are provider-independent.
     */
    private static final List<Map.Entry<String, String>> QUESTIONS = List.of(
            Map.entry("现在有多少个对象别名?", "alert_eval_alias.alias_count_metric"),
            Map.entry("对象别名总数是多少?", "alert_eval_alias.alias_count_metric"),
            Map.entry("帮我统计别名数量", "alert_eval_alias.alias_count_metric"),
            Map.entry("count the object aliases", "alert_eval_alias.alias_count_metric"));

    private static final String MODEL_YAML = """
            version: "0.1"

            semantic_model:
              code: alert_eval_alias
              label:
                zh-CN: 问数评测对象别名
                en-US: ChatBI Eval Object Alias
              description: Migration-owned alias table backing the ChatBI eval harness.
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

    /** Same model, but the metric requires a permission the eval user lacks. */
    private static final String FORBIDDEN_MODEL_YAML = """
            version: "0.1"

            semantic_model:
              code: alert_eval_secret
              label:
                zh-CN: 问数评测受限指标
                en-US: ChatBI Eval Restricted Metric
              description: Restricted metric proving the denial path.
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
              - code: secret_count
                label:
                  zh-CN: 受限计数
                agg: COUNT
                field_ref: pid

            metrics:
              - code: secret_count_metric
                label:
                  zh-CN: 受限计数指标
                  en-US: Restricted Count Metric
                type: simple
                type_params:
                  measure: secret_count
                required_permissions: [chatbi.eval.secret]
            """;

    @Autowired
    private SemanticPublishService publishService;
    @Autowired
    private SemanticQueryService queryService;
    @Autowired
    private AbSemanticMetricMapper metricMapper;
    @Autowired
    private JdbcTemplate jdbc;

    private String modelPid;

    private void ensureIdentity() {
        if (identity == null) {
            identity = SemanticAcceptanceIdentity.create(fixtureUsers, fixtureTenants, fixtureMembers, "chatbi");
            tenantId = identity.tenantId(); userId = identity.userId();
        }
    }

    void bindTenantContext() {
        ensureIdentity();
        identity.bind();
    }

    @BeforeEach
    void publishModels() {
        ensureIdentity();
        identity.bind();
        if (modelPid != null) return;
        identity.registerSource(fixtureSources);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_object_alias WHERE tenant_id = ?",
                Long.class, tenantId)).as("fresh governed-eval fixture namespace").isZero();
        for (int n = 1; n <= 3; n++) {
            jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                            + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                            + "VALUES (?, ?, 'chatbi_eval', ?, 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                    com.auraboot.framework.common.util.UniqueIdGenerator.generate(), tenantId,
                    "Governed eval fixture " + n, userId, userId);
        }
        modelPid = publishService.publishFromYaml(
                MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", tenantId, userId);
        publishService.publishFromYaml(
                FORBIDDEN_MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", tenantId, userId);
    }

    @AfterAll
    void retainFixturesAndClearContext() {
        // The isolated CI database is retained for owner inspection.
        MetaContext.clear();
    }

    private UserContext user() {
        return new UserContext(userId, tenantId, Map.of());
    }

    @Test
    @DisplayName("preassigned catalog metrics return the exact positive governed fixture count")
    void catalogQuestionsAnswered() {
        for (var entry : QUESTIONS) {
            SemanticQueryRequest req = new SemanticQueryRequest();
            req.setMetrics(List.of(entry.getValue()));
            var response = queryService.executeQuery(req, user());
            assertThat(response.getRows()).as("preassigned catalog question: " + entry.getKey()).hasSize(1);
            assertThat(response.getRows().get(0)).hasSize(1);
            Object count = response.getRows().get(0).values().iterator().next();
            assertThat(((Number) count).longValue()).isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("restricted metric is denied before execution (permission negative)")
    void restrictedMetricDenied() {
        AbSemanticMetric secret = metricMapper.findByCode(tenantId, "secret_count_metric", "0.1");
        assertThat(secret).as("restricted metric fixture must be published").isNotNull();
        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("alert_eval_secret.secret_count_metric"));
        Long before = jdbc.queryForObject("SELECT count(*) FROM ab_semantic_query_log WHERE tenant_id = ?",
                Long.class, tenantId);
        // The governed pipeline denies before data execution and audit insertion.
        assertThatThrownBy(() -> queryService.executeQuery(req, user()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_semantic_query_log WHERE tenant_id = ?",
                Long.class, tenantId)).isEqualTo(before);
    }
}
