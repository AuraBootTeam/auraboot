package com.auraboot.framework.agent.provider;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for DslToolProvider.
 * Validates provider identity, prefix routing, discover, and execute flows.
 *
 * Note: The integration-test tenant may not have published models or dynamic tables,
 * so execution tests verify both success and graceful error handling paths.
 */
class DslToolProviderTest extends BaseIntegrationTest {

    @Autowired
    private DslToolProvider dslToolProvider;

    @Autowired
    private DynamicDataMapper dynamicDataMapper;

    // ========== Provider Identity ==========

    @Test
    void providerCode_isDsl() {
        assertThat(dslToolProvider.providerCode()).isEqualTo("dsl");
    }

    @Test
    void handles_correctPrefixes() {
        assertThat(dslToolProvider.handles("cmd:crm_account_create")).isTrue();
        assertThat(dslToolProvider.handles("nq:crm_account_all")).isTrue();
        assertThat(dslToolProvider.handles("list:crm_account")).isTrue();
        assertThat(dslToolProvider.handles("get:crm_account")).isTrue();
        assertThat(dslToolProvider.handles("platform.execute_sql")).isFalse();
        assertThat(dslToolProvider.handles(null)).isFalse();
        assertThat(dslToolProvider.handles("")).isFalse();
        assertThat(dslToolProvider.handles("custom:foo")).isFalse();
    }

    // ========== Execute: get: prefix ==========

    @Test
    void execute_getWithoutRecordId_fails() {
        Long tenantId = getTestTenant().getId();
        var result = dslToolProvider.execute(tenantId, "get:crm_account", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("recordId is required");
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    // ========== Execute: unknown prefix ==========

    @Test
    void execute_unknownPrefix_fails() {
        Long tenantId = getTestTenant().getId();
        var result = dslToolProvider.execute(tenantId, "xyz:something", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("Unknown DSL tool code prefix");
    }

    // ========== Execute: error handling ==========

    @Test
    void execute_listNonExistentModel_returnsError() {
        Long tenantId = getTestTenant().getId();
        var result = dslToolProvider.execute(tenantId, "list:nonexistent_model_xyz", Map.of());
        // Model doesn't exist -> caught exception -> success=false
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void execute_cmdNonExistentCommand_returnsError() {
        Long tenantId = getTestTenant().getId();
        var result = dslToolProvider.execute(tenantId, "cmd:nonexistent_cmd_xyz", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    @Test
    void execute_nqNonExistentQuery_returnsError() {
        Long tenantId = getTestTenant().getId();
        var result = dslToolProvider.execute(tenantId, "nq:nonexistent_nq_xyz", Map.of());
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).isNotBlank();
    }

    // ========== Discover ==========

    @Test
    void discover_withoutModelHint_returnsEmpty() {
        Long tenantId = getTestTenant().getId();
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .maxResults(20)
                .build();
        var tools = dslToolProvider.discover(ctx);
        assertThat(tools).isEmpty();
    }

    @Test
    void discover_withBlankModelHint_returnsEmpty() {
        Long tenantId = getTestTenant().getId();
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint("  ")
                .maxResults(20)
                .build();
        var tools = dslToolProvider.discover(ctx);
        assertThat(tools).isEmpty();
    }

    @Test
    void discover_withModelHint_alwaysIncludesGenericTools() {
        // Even for a model with no commands in this tenant, list + get tools are generated
        Long tenantId = getTestTenant().getId();
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint("some_model")
                .maxResults(20)
                .build();
        var tools = dslToolProvider.discover(ctx);
        assertThat(tools).isNotEmpty();
        assertThat(tools).allMatch(t -> "dsl".equals(t.getProviderCode()));
        assertThat(tools).anyMatch(t -> "list:some_model".equals(t.getToolCode()));
        assertThat(tools).anyMatch(t -> "get:some_model".equals(t.getToolCode()));
    }

    @Test
    void discover_toolDefinitions_haveRequiredFields() {
        Long tenantId = getTestTenant().getId();
        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint("test_model")
                .maxResults(20)
                .build();
        var tools = dslToolProvider.discover(ctx);
        for (ToolDefinition tool : tools) {
            assertThat(tool.getToolCode()).isNotBlank();
            assertThat(tool.getToolName()).isNotBlank();
            assertThat(tool.getProviderCode()).isEqualTo("dsl");
            assertThat(tool.getToolType()).isIn("dsl_command", "dsl_query");
            assertThat(tool.getDescription()).isNotBlank();
        }
    }

    // ========== Discover with real tenant data ==========

    @Test
    void discover_withRealModelHint_includesCommands() {
        // Find a tenant + model that actually has commands in the database
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT DISTINCT tenant_id, model_code FROM ab_command_definition " +
                "WHERE (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1", Map.of());

        if (rows.isEmpty()) {
            // No commands in DB — skip this assertion but don't fail
            return;
        }

        Long tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
        String modelCode = (String) rows.get(0).get("model_code");

        var ctx = ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint(modelCode)
                .maxResults(50)
                .build();
        var tools = dslToolProvider.discover(ctx);

        boolean hasCmd = tools.stream().anyMatch(t -> t.getToolCode().startsWith("cmd:"));
        assertThat(hasCmd).as("Model %s should have DSL commands", modelCode).isTrue();

        // All cmd tools should have toolType dsl_command
        tools.stream()
                .filter(t -> t.getToolCode().startsWith("cmd:"))
                .forEach(t -> assertThat(t.getToolType()).isEqualTo("dsl_command"));
    }

    // ========== Execute with real tenant data ==========

    @Test
    void execute_listWithRealModel_returnsRecords() {
        // Find a tenant + model that has a physical table
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT DISTINCT tenant_id, model_code FROM ab_command_definition " +
                "WHERE (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1", Map.of());

        if (rows.isEmpty()) {
            return;
        }

        Long tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
        String modelCode = (String) rows.get(0).get("model_code");

        var result = dslToolProvider.execute(tenantId, "list:" + modelCode,
                Map.of("pageNum", 1, "pageSize", 5));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsKey("records");
        assertThat(result.getData()).containsKey("total");
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
    }
}
