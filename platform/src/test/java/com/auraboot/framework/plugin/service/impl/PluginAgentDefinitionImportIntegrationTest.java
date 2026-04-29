package com.auraboot.framework.plugin.service.impl;

import com.auraboot.framework.integration.BaseIntegrationTest;
import com.auraboot.framework.plugin.dto.imports.AgentDefinitionDTO;
import com.auraboot.framework.plugin.dto.imports.ImportExecuteResult;
import com.auraboot.framework.plugin.dto.imports.ImportRequest;
import com.auraboot.framework.plugin.dto.imports.PluginManifestExtended;
import com.auraboot.framework.plugin.dto.imports.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Plugin agent definition import integration tests")
class PluginAgentDefinitionImportIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private PluginImportServiceImpl importService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("agentDefinitions import creates then updates ab_agent_definition idempotently")
    void importsAgentDefinitionsIdempotently() {
        Long tenantId = getTestTenant().getId();
        String agentCode = "it_agent_def_" + System.nanoTime();
        String pluginId = "com.test.agent-definition-" + System.nanoTime();

        PluginManifestExtended initial = manifest(pluginId, "1.0.0",
                agent(agentCode, "Initial Agent", "Initial prompt"));
        ImportExecuteResult first = importService.executeFromManifest(initial, overwriteRequest());

        assertThat(first.isSuccess()).isTrue();
        assertThat(first.getResourceCounts())
                .containsKey(ResourceType.AGENT_DEFINITION.name());
        assertThat(activeAgentCount(tenantId, agentCode)).isEqualTo(1);
        assertThat(agentName(tenantId, agentCode)).isEqualTo("Initial Agent");

        PluginManifestExtended updated = manifest(pluginId, "1.0.1",
                agent(agentCode, "Updated Agent", "Updated prompt"));
        ImportExecuteResult second = importService.executeFromManifest(updated, overwriteRequest());

        assertThat(second.isSuccess()).isTrue();
        assertThat(activeAgentCount(tenantId, agentCode)).isEqualTo(1);
        assertThat(agentName(tenantId, agentCode)).isEqualTo("Updated Agent");
        assertThat(systemPrompt(tenantId, agentCode)).isEqualTo("Updated prompt");
        assertThat(pluginResourceCount(tenantId, agentCode)).isEqualTo(1);
    }

    private PluginManifestExtended manifest(String pluginId, String version, AgentDefinitionDTO agent) {
        return PluginManifestExtended.builder()
                .pluginId(pluginId)
                .namespace("it-agent-definition")
                .version(version)
                .agentDefinitions(new ArrayList<>(List.of(agent)))
                .build();
    }

    private AgentDefinitionDTO agent(String agentCode, String name, String prompt) {
        return AgentDefinitionDTO.builder()
                .agentCode(agentCode)
                .name(name)
                .description("Integration test agent")
                .agentType("reactive")
                .model("MiniMax-M2.5")
                .systemPrompt(prompt)
                .tools(List.of("named_query:it_agent_quote_summary"))
                .skills(List.of("quote_comparison"))
                .guardrails(Map.of("writePolicy", "approval_required"))
                .allowedModels(List.of("it_agent_quote"))
                .allowedOperations(List.of("query"))
                .maxTools(8)
                .maxConcurrentRuns(2)
                .executionTimeoutSeconds(120)
                .status("active")
                .visibility("tenant")
                .autoReplyMode("mention")
                .soulProfile(Map.of("persona", "Procurement analyst"))
                .build();
    }

    private ImportRequest overwriteRequest() {
        ImportRequest request = new ImportRequest();
        request.setConflictStrategy(ImportRequest.ConflictStrategy.OVERWRITE);
        return request;
    }

    private Integer activeAgentCount(Long tenantId, String agentCode) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ab_agent_definition
                WHERE tenant_id = ?
                  AND agent_code = ?
                  AND deleted_flag = FALSE
                """, Integer.class, tenantId, agentCode);
    }

    private String agentName(Long tenantId, String agentCode) {
        return jdbcTemplate.queryForObject("""
                SELECT name
                FROM ab_agent_definition
                WHERE tenant_id = ?
                  AND agent_code = ?
                  AND deleted_flag = FALSE
                """, String.class, tenantId, agentCode);
    }

    private String systemPrompt(Long tenantId, String agentCode) {
        return jdbcTemplate.queryForObject("""
                SELECT system_prompt
                FROM ab_agent_definition
                WHERE tenant_id = ?
                  AND agent_code = ?
                  AND deleted_flag = FALSE
                """, String.class, tenantId, agentCode);
    }

    private Integer pluginResourceCount(Long tenantId, String agentCode) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM ab_plugin_resource
                WHERE tenant_id = ?
                  AND resource_type = 'agent_definition'
                  AND resource_code = ?
                """, Integer.class, tenantId, agentCode);
    }
}
