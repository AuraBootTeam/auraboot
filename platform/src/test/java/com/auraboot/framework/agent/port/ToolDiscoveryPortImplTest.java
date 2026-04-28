package com.auraboot.framework.agent.port;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.service.AgentSkillService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolDiscoveryPortImplTest {

    @Test
    void discoverTools_keepsModelHintProviderToolsWhenSkillToolsExist() {
        AgentSkillService agentSkillService = mock(AgentSkillService.class);
        ToolProviderRegistry toolProviderRegistry = mock(ToolProviderRegistry.class);
        ToolDiscoveryPortImpl port = new ToolDiscoveryPortImpl(agentSkillService, toolProviderRegistry);

        when(agentSkillService.resolveSkillTools(1L, "crm_contact_query"))
                .thenReturn(List.of(AgentToolDefinition.builder()
                        .name("list_crm_contact")
                        .description("List CRM contacts")
                        .inputSchema(Map.of("type", "object"))
                        .toolType("dsl_query")
                        .build()));
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(
                        ToolDefinition.builder()
                                .toolCode("list:crm_lead")
                                .toolName("List CRM leads")
                                .description("Paginated list of crm_lead records")
                                .toolType("dsl_query")
                                .build(),
                        ToolDefinition.builder()
                                .toolCode("get:crm_lead")
                                .toolName("Get CRM lead")
                                .description("Get one crm_lead record")
                                .toolType("dsl_query")
                                .build()));

        List<ToolDiscoveryPort.ToolDef> tools = port.discoverTools(
                1L,
                List.of("crm_contact_query"),
                "crm_lead",
                "query",
                5);

        assertThat(tools).extracting(ToolDiscoveryPort.ToolDef::code)
                .contains("list:crm_lead", "get:crm_lead", "list_crm_contact");
        org.mockito.Mockito.verify(toolProviderRegistry).discoverAll(any(ToolDiscoveryContext.class));
        org.mockito.Mockito.verify(agentSkillService).resolveSkillTools(eq(1L), eq("crm_contact_query"));
    }
}
