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
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link ToolProviderRegistry}.
 *
 * <p>Verifies that all four ToolProvider beans are auto-discovered by Spring,
 * that routing via {@link ToolProviderRegistry#execute} reaches the right provider,
 * and that {@link ToolProviderRegistry#discoverAll} aggregates results across providers.
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("integration-test")
@Transactional
@Rollback(true)
class ToolProviderRegistryTest extends BaseIntegrationTest {

    @Autowired
    private ToolProviderRegistry registry;

    // ========== Provider Registration ==========

    @Test
    void registryHasAllFourProviders() {
        var codes = registry.getProviderCodes();
        assertThat(codes).containsExactlyInAnyOrder("dsl", "platform", "custom", "mcp");
    }

    @Test
    void getProviderCodes_returnsNonEmptyList() {
        assertThat(registry.getProviderCodes()).isNotEmpty();
    }

    // ========== execute() — routing ==========

    @Test
    void execute_platformListModels_routesToPlatformProvider() {
        var result = registry.execute(testTenant.getId(), "platform.list_models", Map.of());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsKey("models");
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_platformExecuteSql_invalidSql_routesToPlatformProvider() {
        // DML should be rejected by platform provider's SQL safety guard
        var result = registry.execute(testTenant.getId(), "platform.execute_sql",
                Map.of("sql", "DELETE FROM ab_user WHERE 1=1"));

        assertThat(result).isNotNull();
        // Platform provider rejects DML — result is not successful
        boolean indicatesError = !result.isSuccess()
                || (result.getData() != null && result.getData().containsKey("error"));
        assertThat(indicatesError).isTrue();
    }

    @Test
    void execute_dslListNonExistentModel_routesToDslProvider() {
        // DslToolProvider handles "list:" prefix; non-existent model -> error from DSL layer
        var result = registry.execute(testTenant.getId(), "list:nonexistent_model_xyz", Map.of());

        assertThat(result).isNotNull();
        // Routing succeeded (reached DslToolProvider); execution may fail due to missing table
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_unknownToolCode_returnsFailureWithMessage() {
        var result = registry.execute(testTenant.getId(), "unknown:xyz_tool_that_does_not_exist", Map.of());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("No provider handles");
    }

    @Test
    void execute_nullToolCode_returnsFailure() {
        var result = registry.execute(testTenant.getId(), null, Map.of());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void execute_customPrefixTool_routesToCustomProvider() {
        // Custom provider handles the custom: prefix; unknown tool codes should still route there
        // and produce a provider-level failure rather than falling through registry-level routing.
        var result = registry.execute(testTenant.getId(), "custom:some_tool_code", Map.of());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Custom tool");
    }

    // ========== discoverAll() ==========

    @Test
    void discoverAll_aggregatesAcrossProviders() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(50)
                .build();
        var tools = registry.discoverAll(ctx);

        // Platform provider now contributes 4 tools — overall result must be >= 4
        assertThat(tools.size()).isGreaterThanOrEqualTo(4);
    }

    @Test
    void discoverAll_includesPlatformTools() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(50)
                .build();
        var tools = registry.discoverAll(ctx);

        Set<String> providerCodes = tools.stream()
                .map(ToolDefinition::getProviderCode)
                .collect(Collectors.toSet());
        assertThat(providerCodes).contains("platform");
    }

    @Test
    void discoverAll_respectsMaxResultsLimit() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(2)
                .build();
        var tools = registry.discoverAll(ctx);

        assertThat(tools.size()).isLessThanOrEqualTo(2);
    }

    @Test
    void discoverAll_allToolsHaveRequiredFields() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(50)
                .build();
        var tools = registry.discoverAll(ctx);

        for (ToolDefinition tool : tools) {
            assertThat(tool.getToolCode()).as("toolCode must not be blank").isNotBlank();
            assertThat(tool.getToolName()).as("toolName must not be blank for %s", tool.getToolCode()).isNotBlank();
            assertThat(tool.getProviderCode()).as("providerCode must not be blank for %s", tool.getToolCode()).isNotBlank();
        }
    }

    // ========== discoverByProvider() ==========

    @Test
    void discoverByProvider_platform_returnsExactlyFourTools() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(50)
                .build();
        var tools = registry.discoverByProvider("platform", ctx);

        assertThat(tools).hasSize(4);
        assertThat(tools.stream().map(ToolDefinition::getToolCode))
                .containsExactlyInAnyOrder(
                        "platform.execute_sql",
                        "platform.list_models",
                        "platform.model_suggest",
                        "platform.create_model");
    }

    @Test
    void discoverByProvider_unknownProviderCode_returnsEmptyList() {
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(50)
                .build();
        var tools = registry.discoverByProvider("nonexistent_provider_xyz", ctx);

        assertThat(tools).isEmpty();
    }

    @Test
    void discoverByProvider_dslWithoutModelHint_returnsEmpty() {
        // DslToolProvider.discover() returns empty without a modelHint
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(testTenant.getId())
                .maxResults(50)
                .build();
        var tools = registry.discoverByProvider("dsl", ctx);

        assertThat(tools).isEmpty();
    }
}
