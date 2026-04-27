package com.auraboot.framework.agent.provider;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            assertThat(tool.getSourceCode()).isNotBlank();
            assertThat(tool.getRiskLevel()).isIn("L0", "L1", "L2", "L3", "L4");
            assertThat(tool.getConfirmationPolicy()).isNotBlank();
        }
    }

    @Test
    void discover_buildsCommandParameterSchemaAndRiskLevelFromDslMetadata() {
        Long tenantId = getTestTenant().getId();
        String suffix = "agent_schema_" + System.nanoTime();
        String modelCode = suffix + "_model";
        String codeField = suffix + "_code";
        String qtyField = suffix + "_qty";
        String dateField = suffix + "_need_date";
        String commandCode = suffix + ":create";

        dynamicDataMapper.insertWithJsonb("ab_meta_model", Map.of(
                "pid", suffix + "_model_pid",
                "tenant_id", tenantId,
                "code", modelCode,
                "table_name", "mt_" + modelCode,
                "extension", "{}",
                "capabilities", "{}",
                "version", 1,
                "is_current", true,
                "status", "published",
                "deleted_flag", false
        ), Set.of("extension", "capabilities"));
        Long modelId = ((Number) dynamicDataMapper.selectByQuery(
                "SELECT id FROM ab_meta_model WHERE tenant_id = #{params.tenantId} AND code = #{params.code}",
                Map.of("tenantId", tenantId, "code", modelCode)).get(0).get("id")).longValue();

        insertField(tenantId, suffix + "_field_pid_1", codeField, "text");
        insertField(tenantId, suffix + "_field_pid_2", qtyField, "decimal");
        insertField(tenantId, suffix + "_field_pid_3", dateField, "date");
        bindField(tenantId, modelId, codeField, 1, true);
        bindField(tenantId, modelId, qtyField, 2, true);
        bindField(tenantId, modelId, dateField, 3, false);

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("pid", suffix + "_cmd_pid");
        command.put("tenant_id", tenantId);
        command.put("code", commandCode);
        command.put("display_name", "Create schema fixture");
        command.put("description", "Create a schema fixture record");
        command.put("model_code", modelCode);
        command.put("input_schema", "{}");
        command.put("target_models", "[]");
        command.put("execution_config", "{\"type\":\"create\",\"inputFields\":[\"" + codeField + "\",\"" + qtyField + "\",\"" + dateField + "\"]}");
        command.put("extension", "{}");
        command.put("cmd_risk_level", "L2");
        command.put("version", 1);
        command.put("is_current", true);
        command.put("status", "published");
        command.put("deleted_flag", false);
        dynamicDataMapper.insertWithJsonb("ab_command_definition", command,
                Set.of("input_schema", "target_models", "execution_config", "extension"));

        var tools = dslToolProvider.discover(ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint(modelCode)
                .maxResults(20)
                .build());

        ToolDefinition commandTool = tools.stream()
                .filter(tool -> ("cmd:" + commandCode).equals(tool.getToolCode()))
                .findFirst()
                .orElseThrow();
        assertThat(commandTool.getRiskLevel()).isEqualTo("L2");
        assertThat(commandTool.isRequiresConfirmation()).isTrue();

        Map<String, Object> schema = commandTool.getParameterSchema();
        assertThat(schema).containsEntry("type", "object");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys(codeField, qtyField, dateField);
        assertThat(properties.get(codeField).toString()).contains("minLength=1");
        assertThat(properties.get(qtyField).toString()).contains("number");
        assertThat(properties.get(dateField).toString()).contains("date");
        List<String> required = ((List<?>) schema.get("required")).stream()
                .map(String::valueOf)
                .toList();
        assertThat(required).containsExactly(codeField, qtyField);
    }

    @Test
    void discover_buildsNamedQueryParameterSchemaFromSqlParams() {
        Long tenantId = getTestTenant().getId();
        String suffix = "agent_nq_" + System.nanoTime();
        String queryCode = suffix + "_supplier_options";

        dynamicDataMapper.insertWithJsonb("ab_named_query", Map.of(
                "pid", suffix + "_nq_pid",
                "tenant_id", tenantId,
                "code", queryCode,
                "title", "Supplier options",
                "description", "Supplier options by product",
                "from_sql", "SELECT 1 WHERE tenant_id = #{params.tenantId} AND product_id = #{params.productId} AND status = #{params.status}",
                "base_where", "[]",
                "policy", "{}",
                "status", "published"
        ), Set.of("base_where", "policy"));

        var tools = dslToolProvider.discover(ToolDiscoveryContext.builder()
                .tenantId(tenantId)
                .modelHint(suffix)
                .maxResults(20)
                .build());

        ToolDefinition queryTool = tools.stream()
                .filter(tool -> ("nq:" + queryCode).equals(tool.getToolCode()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> schema = queryTool.getParameterSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertThat(properties).containsKeys("productId", "status");
        assertThat(properties).doesNotContainKey("tenantId");
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

        // Query commands are exposed as dsl_query so read-only tool filtering can keep them.
        tools.stream()
                .filter(t -> t.getToolCode().startsWith("cmd:"))
                .forEach(t -> assertThat(t.getToolType()).isIn("dsl_command", "dsl_query"));
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

    @Test
    void execute_queryCommandWithRealModel_returnsListShape() {
        List<Map<String, Object>> rows = dynamicDataMapper.selectByQuery(
                "SELECT tenant_id, code, model_code FROM ab_command_definition " +
                "WHERE execution_config->>'type' = 'query' " +
                "AND model_code IS NOT NULL " +
                "AND (deleted_flag = FALSE OR deleted_flag IS NULL) LIMIT 1", Map.of());

        if (rows.isEmpty()) {
            return;
        }

        Long tenantId = ((Number) rows.get(0).get("tenant_id")).longValue();
        String commandCode = (String) rows.get(0).get("code");

        var result = dslToolProvider.execute(tenantId, "cmd:" + commandCode,
                Map.of("pageNum", 1, "pageSize", 5));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsKey("records");
        assertThat(result.getData()).containsKey("total");
    }

    private void insertField(Long tenantId, String pid, String code, String dataType) {
        Map<String, Object> field = new LinkedHashMap<>();
        field.put("pid", pid);
        field.put("tenant_id", tenantId);
        field.put("version", 1);
        field.put("is_current", true);
        field.put("status", "published");
        field.put("deleted_flag", false);
        field.put("code", code);
        field.put("data_type", dataType);
        field.put("extension", "{}");
        field.put("index_hint", "{}");
        field.put("ui_schema", "{}");
        field.put("query_schema", "{}");
        dynamicDataMapper.insertWithJsonb("ab_meta_field", field,
                Set.of("extension", "index_hint", "ui_schema", "query_schema"));
    }

    private void bindField(Long tenantId, Long modelId, String fieldCode, int order, boolean required) {
        Long fieldId = ((Number) dynamicDataMapper.selectByQuery(
                "SELECT id FROM ab_meta_field WHERE tenant_id = #{params.tenantId} AND code = #{params.code}",
                Map.of("tenantId", tenantId, "code", fieldCode)).get(0).get("id")).longValue();
        dynamicDataMapper.insert("ab_meta_model_field_binding", Map.of(
                "pid", fieldCode + "_binding",
                "tenant_id", tenantId,
                "model_id", modelId,
                "field_id", fieldId,
                "field_order", order,
                "required", required,
                "visible", true,
                "editable", true,
                "deleted_flag", false
        ));
    }
}
