package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.MetricCompileException;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.entity.AbSemanticMetric;
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

    private static final long TENANT_ID = 991_960_001L;
    private static final long USER_ID = 991_960_002L;

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

    @PostConstruct
    void bindTenantContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "chatbi-eval-pid", "chatbi-eval-user");
    }

    @BeforeEach
    void publishModels() {
        MetaContext.setContext(TENANT_ID, USER_ID, "chatbi-eval-pid", "chatbi-eval-user");
        jdbc.update("DELETE FROM ab_meta_model WHERE id IN (991960010, 991960011)");
        jdbc.update("INSERT INTO ab_meta_model (id, pid, tenant_id, code, table_name, "
                + "source_type, is_current, status, version, created_at, updated_at, deleted_flag) "
                + "VALUES (991960010, 'chatbi-eval-meta-model', ?, 'ab_object_alias', 'ab_object_alias', "
                + "'physical', TRUE, 'published', 1, NOW(), NOW(), FALSE)", TENANT_ID);
        if (modelPid == null) {
            modelPid = publishService.publishFromYaml(
                    MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", TENANT_ID, USER_ID);
            publishService.publishFromYaml(
                    FORBIDDEN_MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", TENANT_ID, USER_ID);
        }
    }

    @AfterAll
    void cleanup() {
        for (String pid : List.of("chatbi-eval-meta-model", "chatbi-eval-secret-meta-model")) {
            try {
                jdbc.update("DELETE FROM ab_semantic_metric WHERE semantic_model_pid = "
                        + "(SELECT pid FROM ab_semantic_model WHERE pid = ?)", pid);
                jdbc.update("DELETE FROM ab_semantic_dimension WHERE semantic_model_pid = "
                        + "(SELECT pid FROM ab_semantic_model WHERE pid = ?)", pid);
                jdbc.update("DELETE FROM ab_semantic_model WHERE pid = ?", pid);
                jdbc.update("DELETE FROM ab_meta_model WHERE pid = ?", pid);
            } catch (Exception ignored) {
                // cleanup is best-effort; the fresh-seed DB is rebuilt per run
            }
        }
        MetaContext.clear();
    }

    private UserContext user() {
        return new UserContext(USER_ID, TENANT_ID, Map.of());
    }

    @Test
    @DisplayName("every catalog question resolves to a governed metric with rows")
    void catalogQuestionsAnswered() {
        for (var entry : QUESTIONS) {
            SemanticQueryRequest req = new SemanticQueryRequest();
            req.setMetrics(List.of(entry.getValue()));
            var response = queryService.executeQuery(req, user());
            assertThat(response.getRows()).as("question: " + entry.getKey()).isNotEmpty();
        }
    }

    @Test
    @DisplayName("restricted metric is denied before execution (permission negative)")
    void restrictedMetricDenied() {
        AbSemanticMetric secret = metricMapper.findByCode(TENANT_ID, "secret_count_metric", "0.1");
        assertThat(secret).as("restricted metric fixture must be published").isNotNull();
        SemanticQueryRequest req = new SemanticQueryRequest();
        req.setMetrics(List.of("alert_eval_secret.secret_count_metric"));
        // The governed pipeline denies via Spring Security BEFORE any SQL runs.
        assertThatThrownBy(() -> queryService.executeQuery(req, user()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
