package com.auraboot.framework.semantic.service;

import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.semantic.compiler.SemanticQueryRequest;
import com.auraboot.framework.semantic.compiler.UserContext;
import com.auraboot.framework.semantic.parser.SemanticYamlParser;
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

    private void ensureIdentity() {
        if (identity == null) {
            identity = SemanticAcceptanceIdentity.create(fixtureUsers, fixtureTenants, fixtureMembers, "resolver");
            tenantId = identity.tenantId(); userId = identity.userId();
        }
    }

    void bindTenantContext() {
        ensureIdentity();
        identity.bind();
    }

    @BeforeEach
    void publishModelOnce() {
        ensureIdentity();
        identity.bind();
        if (modelPid != null) return;
        identity.registerSource(fixtureSources);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ab_object_alias WHERE tenant_id = ?",
                Long.class, tenantId)).as("fresh resolver fixture namespace").isZero();
        for (int n = 1; n <= 2; n++) {
            jdbc.update("INSERT INTO ab_object_alias (pid, tenant_id, model_code, alias, language, acp_priority, "
                            + "created_at, updated_at, created_by, updated_by, deleted_flag) "
                            + "VALUES (?, ?, 'resolver_golden', ?, 'en-US', 0, NOW(), NOW(), ?, ?, FALSE)",
                    com.auraboot.framework.common.util.UniqueIdGenerator.generate(), tenantId,
                    "Resolver fixture " + n, userId, userId);
        }
        modelPid = publishService.publishFromYaml(
                MODEL_YAML.getBytes(StandardCharsets.UTF_8), "test-fixtures", tenantId, userId);
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
        UserContext user = new UserContext(userId, tenantId, Map.of());
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
