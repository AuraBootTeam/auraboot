package com.auraboot.framework.agent;

import com.auraboot.framework.aurabot.service.ChatToolExecutor;
import com.auraboot.framework.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ChatToolExecutor provider delegation.
 * All tool calls are routed through ToolDiscoveryPort → ToolProviderRegistry.
 * Uses provider-style sanitized tool names (underscores, as sent by LLM).
 */
class ChatToolExecutorDelegationTest extends BaseIntegrationTest {

    @Autowired
    private ChatToolExecutor chatToolExecutor;

    @Test
    void execute_platformListModels_succeeds() {
        Map<String, Object> result = chatToolExecutor.execute("platform_list_models", Map.of(), null);
        assertThat(result.get("success")).isEqualTo(true);
    }

    @Test
    void execute_platformListModels_withKeyword_succeeds() {
        Map<String, Object> result = chatToolExecutor.execute("platform_list_models",
                Map.of("keyword", "crm"), null);
        assertThat(result.get("success")).isEqualTo(true);
    }

    @Test
    void execute_getRecord_missingPid_returnsError() {
        Map<String, Object> result = chatToolExecutor.execute("get_crm_account", Map.of(), "crm_account");
        assertThat(result.get("success")).isEqualTo(false);
    }

    @Test
    void execute_platformExecuteSql_rejectsDml() {
        Map<String, Object> result = chatToolExecutor.execute("platform_execute_sql",
                Map.of("sql", "DELETE FROM ab_tenant"), null);
        assertThat(result.get("success")).isEqualTo(false);
    }

    @Test
    void execute_platformExecuteSql_acceptsSelect() {
        Map<String, Object> result = chatToolExecutor.execute("platform_execute_sql",
                Map.of("sql", "SELECT COUNT(*) AS cnt FROM ab_meta_model WHERE tenant_id = #{params.tenantId}"), null);
        assertThat(result.get("success")).isEqualTo(true);
    }

    @Test
    void execute_nullToolName_returnsError() {
        Map<String, Object> result = chatToolExecutor.execute(null, Map.of(), null);
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("error")).isNotNull();
    }

    @Test
    void execute_unknownTool_returnsError() {
        Map<String, Object> result = chatToolExecutor.execute("unknown_tool_xyz", Map.of(), null);
        assertThat(result.get("success")).isEqualTo(false);
    }
}
