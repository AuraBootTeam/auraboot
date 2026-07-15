package com.auraboot.framework.agent.provider;

import com.auraboot.framework.application.TestApplication;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for PlatformToolProvider.
 *
 * Covers:
 * - handles() routing logic
 * - discover() returns all 3 platform tools
 * - execute() for platform.list_models returns a valid model list
 * - execute() for platform.execute_sql rejects invalid SQL (DML guard)
 * - execute() for unknown tool code returns error without throwing
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class PlatformToolProviderTest extends BaseIntegrationTest {

    @Autowired
    private PlatformToolProvider provider;

    // ========== handles() ==========

    @Test
    void handles_platformPrefix_returnsTrue() {
        assertThat(provider.handles("platform.execute_sql")).isTrue();
        assertThat(provider.handles("platform.list_models")).isTrue();
        assertThat(provider.handles("platform.model_suggest")).isTrue();
    }

    @Test
    void handles_nonPlatformPrefix_returnsFalse() {
        assertThat(provider.handles("cmd:something")).isFalse();
        assertThat(provider.handles("dsl.command")).isFalse();
        assertThat(provider.handles("builtin__execute_query")).isFalse();
        assertThat(provider.handles(null)).isFalse();
    }

    // ========== providerCode() ==========

    @Test
    void providerCode_returnsPlatform() {
        assertThat(provider.providerCode()).isEqualTo("platform");
    }

    // ========== discover() ==========

    @Test
    void discover_returnsAllThreePlatformTools() {
        var ctx = ToolDiscoveryContext.builder().tenantId(testTenant.getId()).build();
        var tools = provider.discover(ctx);

        assertThat(tools).hasSize(5);
        assertThat(tools.stream().map(ToolDefinition::getToolCode))
                .containsExactlyInAnyOrder(
                        "platform.execute_sql",
                        "platform.list_models",
                        "platform.model_suggest",
                        "platform.create_model",
                        "platform.delegate_task");
    }

    @Test
    void discover_allToolsHavePlatformProviderCode() {
        var ctx = ToolDiscoveryContext.builder().tenantId(testTenant.getId()).build();
        var tools = provider.discover(ctx);

        assertThat(tools).allSatisfy(t -> {
            assertThat(t.getProviderCode()).isEqualTo("platform");
            assertThat(t.getToolType()).isEqualTo("platform");
            assertThat(t.getToolCode()).isNotBlank();
            assertThat(t.getToolName()).isNotBlank();
            assertThat(t.getDescription()).isNotBlank();
        });
    }

    // ========== execute() - platform.list_models ==========

    @Test
    void execute_listModels_succeeds_andReturnsModelsKey() {
        var result = provider.execute(testTenant.getId(), "platform.list_models", Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsKey("models");
        assertThat(result.getData()).containsKey("total");
        assertThat(result.getData()).containsKey("hint");
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_listModels_withKeyword_doesNotThrow() {
        var result = provider.execute(testTenant.getId(), "platform.list_models",
                Map.of("keyword", "nonexistent_model_xyz_12345"));

        // May return empty result or error, but must not throw
        assertThat(result).isNotNull();
    }

    // ========== execute() - platform.execute_sql ==========

    @Test
    void execute_executeSql_rejectsDmlSql() {
        var result = provider.execute(testTenant.getId(), "platform.execute_sql",
                Map.of("sql", "DELETE FROM ab_user WHERE 1=1"));

        assertThat(result.isSuccess()).isFalse();
        // Either data.error or errorMessage must contain a rejection message
        boolean hasError = (result.getData() != null && result.getData().containsKey("error"))
                || (result.getErrorMessage() != null && !result.getErrorMessage().isBlank());
        assertThat(hasError).isTrue();
    }

    @Test
    void execute_executeSql_rejectsBlankSql() {
        var result = provider.execute(testTenant.getId(), "platform.execute_sql", Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsKey("error");
    }

    @Test
    void execute_executeSql_validSelect_returnsResultStructure() {
        var result = provider.execute(testTenant.getId(), "platform.execute_sql",
                Map.of("sql", "SELECT 1 AS test_col"));

        // Even if rows are empty, the structure must be present on success
        assertThat(result).isNotNull();
        if (result.isSuccess()) {
            assertThat(result.getData()).containsKeys("success", "columns", "records", "total");
        }
    }

    @Test
    void execute_executeSql_autoInjectsTenantFilterForSimpleSelect() {
        var result = provider.execute(testTenant.getId(), "platform.execute_sql",
                Map.of("sql", "SELECT code FROM ab_meta_model WHERE code IS NOT NULL"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsKeys("success", "columns", "records", "total", "sql");
        assertThat(result.getData().get("sql").toString())
                .contains("tenant_id = #{params.tenantId}")
                .contains("code IS NOT NULL");
    }

    /**
     * SEC-001: the SQL is authored by the LLM from a tool-call param, so a
     * caller-declared {@code tenant_id = <foreign>} must never stand alone. The
     * server-side current-tenant predicate is injected unconditionally and AND-ed
     * onto the caller's condition, intersecting the result down to the current
     * tenant. Falsifiable against the old "already mentions tenant_id → skip
     * injection" behaviour: that path would leave the foreign literal unqualified.
     */
    @Test
    void execute_executeSql_injectsServerTenantEvenWhenSqlDeclaresForeignTenant() {
        long foreignTenant = 9_999_999L;
        var result = provider.execute(testTenant.getId(), "platform.execute_sql",
                Map.of("sql", "SELECT code FROM ab_meta_model WHERE tenant_id = " + foreignTenant));

        assertThat(result.isSuccess()).isTrue();
        String rewritten = result.getData().get("sql").toString();
        // Server-side current-tenant predicate is ALWAYS present...
        assertThat(rewritten).contains("tenant_id = #{params.tenantId}");
        // ...AND-ed with the caller-declared literal, so it cannot stand alone.
        assertThat(rewritten).contains(String.valueOf(foreignTenant));
        assertThat(rewritten.toLowerCase()).contains(" and ");
        // The foreign tenant is intersected with the current tenant → no rows leak.
        assertThat(result.getData().get("records")).isInstanceOf(java.util.List.class);
        assertThat((java.util.List<?>) result.getData().get("records")).isEmpty();
    }

    @Test
    void execute_executeSql_injectsTenantFilterWhenSqlHasNoTenantPredicate() {
        var result = provider.execute(testTenant.getId(), "platform.execute_sql",
                Map.of("sql", "SELECT code FROM ab_meta_model"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData().get("sql").toString())
                .contains("tenant_id = #{params.tenantId}");
    }

    // ========== execute() - unknown tool code ==========

    @Test
    void execute_unknownToolCode_returnsErrorResult() {
        var result = provider.execute(testTenant.getId(), "platform.does_not_exist", Map.of());

        assertThat(result).isNotNull();
        // Should return a result indicating failure, not throw
        boolean indicatesError = !result.isSuccess()
                || (result.getData() != null && result.getData().containsKey("error"));
        assertThat(indicatesError).isTrue();
    }

    // ========== execute() - durationMs tracking ==========

    @Test
    void execute_alwaysPopulatesDurationMs() {
        var result = provider.execute(testTenant.getId(), "platform.list_models", Map.of());

        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }
}
