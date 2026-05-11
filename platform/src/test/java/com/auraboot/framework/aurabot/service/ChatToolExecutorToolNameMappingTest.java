package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.port.GroundingPort;
import com.auraboot.framework.agent.port.ToolDiscoveryPort;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.application.tenant.MetaContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatToolExecutorToolNameMappingTest {

    @Test
    void execute_usesExactDiscoveredProviderToolCode() {
        GroundingPort groundingPort = (tenantId, userMessage, pageModel, recordId) ->
                new GroundingPort.GroundingResult("create", "crm_lead", 0.9, List.of(), false);
        AtomicReference<String> executedToolCode = new AtomicReference<>();
        ToolDiscoveryPort toolDiscoveryPort = new ToolDiscoveryPort() {
            @Override
            public List<ToolDef> discoverTools(Long tenantId, List<String> candidateSkills,
                                               String modelHint, String intentHint, int maxTools) {
                return List.of(new ToolDef(
                        "cmd:crm:list_leads",
                        "List Leads",
                        "Query CRM leads",
                        Map.of("type", "object"),
                        true
                ));
            }
        };
        ChatToolResolver resolver = new ChatToolResolver(groundingPort, toolDiscoveryPort, null);
        MetaContext.setSystemTenantContext(1L);
        try {
            resolver.resolveTools("list high-score leads", "crm_lead", null);
        } finally {
            MetaContext.clear();
        }
        ToolLoopService toolLoopService = mock(ToolLoopService.class);
        when(toolLoopService.executeToolCall(eq(1L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                any(), eq(Map.of()), anyList(), isNull()))
                .thenAnswer(invocation -> {
                    executedToolCode.set(invocation.getArgument(4, String.class));
                    @SuppressWarnings("unchecked")
                    List<AgentToolDefinition> tools = invocation.getArgument(6, List.class);
                    assertThat(tools).hasSize(1);
                    assertThat(tools.get(0).getName()).isEqualTo("cmd:crm:list_leads");
                    return "{\"success\":true}";
                });
        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, resolver,
                new com.fasterxml.jackson.databind.ObjectMapper());

        MetaContext.setSystemTenantContext(1L);
        Map<String, Object> result;
        try {
            result = executor.execute("cmd_crm_list_leads", Map.of(), "crm_lead");
        } finally {
            MetaContext.clear();
        }

        assertThat(result.get("success")).isEqualTo(true);
        assertThat(executedToolCode).hasValue("cmd:crm:list_leads");
    }
}
