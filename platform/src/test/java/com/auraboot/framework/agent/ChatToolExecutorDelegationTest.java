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
 */
class ChatToolExecutorDelegationTest extends BaseIntegrationTest {

    @Autowired
    private ChatToolExecutor chatToolExecutor;

    @Test
    void execute_builtinListModels_succeeds() {
        Map<String, Object> result = chatToolExecutor.execute("builtin__list_models", Map.of(), null);
        assertThat(result.get("success")).isEqualTo(true);
    }

    @Test
    void execute_builtinListModels_withKeyword_succeeds() {
        Map<String, Object> result = chatToolExecutor.execute("builtin__list_models",
                Map.of("keyword", "crm"), null);
        assertThat(result.get("success")).isEqualTo(true);
    }

    @Test
    void execute_builtinGetRecord_missingPid_returnsError() {
        Map<String, Object> result = chatToolExecutor.execute("builtin__get_record", Map.of(), "crm_account");
        assertThat(result.get("success")).isEqualTo(false);
    }

    @Test
    void execute_builtinExecuteQuery_rejectsDml() {
        Map<String, Object> result = chatToolExecutor.execute("builtin__execute_query",
                Map.of("sql", "DELETE FROM ab_tenant"), null);
        assertThat(result.get("success")).isEqualTo(false);
    }

    @Test
    void execute_builtinExecuteQuery_acceptsSelect() {
        Map<String, Object> result = chatToolExecutor.execute("builtin__execute_query",
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
