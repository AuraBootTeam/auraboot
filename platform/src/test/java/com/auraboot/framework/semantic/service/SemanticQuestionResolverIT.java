package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
import jakarta.annotation.PostConstruct;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden IT for the offline question resolver (R5 eval harness, offline tier):
 * catalog-anchored label matching resolves natural-language questions to the
 * governed metric, unanswered questions resolve to empty (never a guess), and
 * every resolved question executes through the governed pipeline with rows.
 *
 * <p>The model label is authored so the questions carry it verbatim — that is
 * the offline authoring contract. The LLM tier (provider-gated) slots in
 * front of this resolver for fuzzy questions; the permission negative is
 * covered by {@code SemanticChatBiEvalIT}.
 */
@SpringBootTest(classes = com.auraboot.framework.application.TestApplication.class)
@ActiveProfiles("integration-test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Semantic question resolver golden IT — offline catalog-anchored resolution")
class SemanticQuestionResolverIT {

    private static final long TENANT_ID = 991_970_001L;
    private static final long USER_ID = 991_970_002L;
    private static final String META_MODEL_PID = "resolver-golden-meta-model";
    private static final String MODEL_YAML = """
            version: "0.1"

            semantic_model:
              code: resolver_golden_alias
              label:
                zh-CN: 解析金样对象别名
                en-US: Resolver Golden Object Alias
              description: Migration-owned alias table backing the question resolver golden IT.
              model_ref: ab_object_alias
              primary_entity: pid

            entities:
              - name: pid
                type: primary
                field_ref: pid

            dimensions:
              - code: alias_language
                label:
                  zh-CN: 别名语言
                  en-US: Alias Language
                field_ref: language
                type: categorical

            measures:
              - code: alias_count
                label:
                  zh-CN: 别名数
                agg: COUNT
                field_ref: pid

            metrics:
              - code: alias_total
                label:
                  zh-CN: 对象别名总数
                  en-US: Alias Total
                description: Total count of object aliases.
                type: simple
                type_params:
                  measure: alias_count
            """;

    @Autowired
    private SemanticYamlParser parser;
    @Autowired
    private SemanticPublishService publishService;
    @Autowired
    private SemanticQuestionResolver questionResolver;
    @Autowired
    private SemanticQueryService queryService;
    @Autowired
    private JdbcTemplate jdbc;

    private String modelPid;

    @PostConstruct
    void bindTenantContext() {
        MetaContext.setContext(TENANT_ID, USER_ID, "resolver-golden-pid", "resolver-golden-user");
    }

    @BeforeEach
    void publishModelOnce() {
        MetaContext.setContext(TENANT_ID, USER_ID, "resolver-golden-pid", "resolver-golden-user");
        if (modelPid != null) return;
        jdbc.update("INSERT INTO ab_meta_model (id, pid, tenant_id, code, table_name, "
                + "source_type, is_current, status, version, created_at, updated_at, deleted_flag) "
                + "VALUES (991970010, ?, ?, 'ab_object_alias', 'ab_object_alias', "
                + "'physical', TRUE, 'published', 1, NOW(), NOW(), FALSE)",
                META_MODEL_PID, TENANT_ID);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_object_alias WHERE tenant_id = ?",
                Long.class, TENANT_ID)).as("fresh resolver fixture namespace").isZero();
        for (int n = 1; n <= 2; n++) {
            jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                            + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                            + "VALUES (?, ?, 'resolver_golden', ?, 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                    com.auraboot.framework.common.util.UniqueIdGenerator.generate(), TENANT_ID,
                    "Resolver fixture " + n, USER_ID, USER_ID);
        }
        modelPid = publishService.publishFromYaml(
                MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", TENANT_ID, USER_ID);
    }

    @AfterAll
    void retainFixturesAndClearContext() {
        // The isolated CI database is retained for owner inspection.
        MetaContext.clear();
    }

    private record Eval(String question, String expectMetricCode) {}

    private static final List<Eval> QUESTION_SET = List.of(
            new Eval("对象别名总数是多少?", "alias_total"),
            new Eval("查一下对象别名总数", "alias_total"),
            new Eval("count the alias_total", "alias_total"));

    @Test
    @DisplayName("offline resolver maps the question set to the governed metric; unmatched resolves empty")
    void goldenResolverAccuracy() {
        UserContext user = new UserContext(USER_ID, TENANT_ID, Map.of());
        for (Eval eval : QUESTION_SET) {
            Optional<SemanticQueryRequest> resolved = questionResolver.resolve(eval.question(), user);
            assertThat(resolved).as("question must resolve: " + eval.question()).isPresent();
            assertThat(resolved.get().getMetrics())
                    .containsExactly("resolver_golden_alias." + eval.expectMetricCode());

            // The resolved request must execute through the governed pipeline.
            var response = queryService.executeQuery(resolved.get(), user);
            assertThat(response.getRows()).as("question: " + eval.question()).hasSize(1);
            assertThat(response.getRows().get(0)).hasSize(1);
            Object count = response.getRows().get(0).values().iterator().next();
            assertThat(((Number) count).longValue()).isEqualTo(2L);
        }

        // Unmatched question: empty, never a guess.
        assertThat(questionResolver.resolve("今天天气怎么样", user)).isEmpty();
    }
}
