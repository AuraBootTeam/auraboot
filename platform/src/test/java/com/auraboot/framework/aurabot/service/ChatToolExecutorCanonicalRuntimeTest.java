package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChatToolExecutor is a thin chat adapter. Execution must enter the canonical
 * ACP ToolLoopService; ToolDiscoveryPort and SkillToolExecutor are not chat
 * execution runtimes.
 */
@ExtendWith(MockitoExtension.class)
class ChatToolExecutorCanonicalRuntimeTest {

    @Mock
    private ToolLoopService toolLoopService;

    @Mock
    private ChatToolResolver chatToolResolver;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    void platformTool_routesThroughToolLoopWithCanonicalToolDefinition() {
        Map<String, Object> input = Map.of("keyword", "crm");
        when(toolLoopService.executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("platform.list_models"), eq(input), any(), isNull()))
                .thenReturn("{\"success\":true,\"models\":[]}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, null, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.execute("platform_list_models", input, null);

        assertThat(result).containsEntry("success", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("platform.list_models"), eq(input), toolsCaptor.capture(), isNull());
        AgentToolDefinition toolDef = toolsCaptor.getValue().get(0);
        assertThat(toolDef.getName()).isEqualTo("platform.list_models");
        assertThat(toolDef.getSourceCode()).isEqualTo("platform.list_models");
        assertThat(toolDef.getToolType()).isEqualTo("platform");
    }

    @Test
    void discoveredTool_usesExactCanonicalDefinitionFromResolver() {
        AgentToolDefinition discovered = AgentToolDefinition.builder()
                .name("cmd:crm:list_leads")
                .description("List leads")
                .toolType("built_in")
                .sourceCode("cmd:crm:list_leads")
                .requiresApproval(false)
                .inputSchema(Map.of("type", "object"))
                .build();
        when(chatToolResolver.getAgentToolDefinition("cmd_crm_list_leads")).thenReturn(discovered);
        when(toolLoopService.executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("cmd:crm:list_leads"), eq(Map.of()), any(), isNull()))
                .thenReturn("{\"success\":true}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, chatToolResolver, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.execute("cmd_crm_list_leads", Map.of(), "crm_lead");

        assertThat(result).containsEntry("success", true);
        verify(toolLoopService).executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("cmd:crm:list_leads"), eq(Map.of()), eq(List.of(discovered)), isNull());
    }

    @Test
    void llmSafeDslCommandFallbackRestoresNamespaceSeparator() {
        Map<String, Object> input = Map.of("pe_pc_code", "E2E-PCBA-CMP-1");
        when(toolLoopService.executeToolCall(eq(7L), eq("run-1"), isNull(),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd:pe:create_procurement_comparison_draft"),
                eq(input), any(), isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, null, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.executeConfirmed(
                "cmd_pe_create_procurement_comparison_draft",
                input,
                "pe_procurement_comparison",
                "run-1",
                null,
                "pcba_procurement_comparison_agent");

        assertThat(result).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(eq(7L), eq("run-1"), isNull(),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd:pe:create_procurement_comparison_draft"),
                eq(input), toolsCaptor.capture(), isNull());
        AgentToolDefinition toolDef = toolsCaptor.getValue().get(0);
        assertThat(toolDef.getName()).isEqualTo("cmd:pe:create_procurement_comparison_draft");
        assertThat(toolDef.getSourceCode()).isEqualTo("cmd:pe:create_procurement_comparison_draft");
        assertThat(toolDef.getToolType()).isEqualTo("built_in");
    }

    @Test
    void aurabotSkill_routesThroughToolLoopWithSkillToolDefinition() {
        Map<String, Object> input = Map.of("name", "Acme");
        when(toolLoopService.executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("aurabot:model:query"), eq(input), any(), isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"ok\":true}}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, null, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.execute("aurabot:model:query", input, "crm");

        assertThat(result).containsEntry("success", true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("aurabot:model:query"), eq(input), toolsCaptor.capture(), isNull());
        AgentToolDefinition toolDef = toolsCaptor.getValue().get(0);
        assertThat(toolDef.getName()).isEqualTo("aurabot:model:query");
        assertThat(toolDef.getSourceCode()).isEqualTo("model:query");
        assertThat(toolDef.getToolType()).isEqualTo("AURABOT_SKILL");
    }

    @Test
    void llmSafeAurabotSkillFallbackRestoresSkillSeparator() {
        Map<String, Object> input = Map.of("code", "crm_customer");
        when(toolLoopService.executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("aurabot:model:create"), eq(input), any(), isNull()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"previewToken\":\"preview-1\"}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, null, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.execute("aurabot_model_create", input, "crm");

        assertThat(result).containsEntry("approvalRequired", true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(eq(7L), eq("aurabot_chat"), isNull(), eq("aurabot"),
                eq("aurabot:model:create"), eq(input), toolsCaptor.capture(), isNull());
        AgentToolDefinition toolDef = toolsCaptor.getValue().get(0);
        assertThat(toolDef.getName()).isEqualTo("aurabot:model:create");
        assertThat(toolDef.getSourceCode()).isEqualTo("model:create");
        assertThat(toolDef.getToolType()).isEqualTo("AURABOT_SKILL");
    }

    @Test
    void aurabotSkillConfirm_routesThroughToolLoopConfirm() {
        Map<String, Object> input = Map.of("name", "Acme");
        when(toolLoopService.confirmAuraBotSkill(eq(7L), eq("run-1"), isNull(), eq("aurabot"),
                eq("aurabot:model:create"), eq(input), any(), eq("preview-1"), isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"created\":true}}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, null, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.confirmAuraBotSkill(
                "aurabot:model:create", input, "crm", "preview-1", "run-1", null, "aurabot");

        assertThat(result).containsEntry("success", true);
        verify(toolLoopService).confirmAuraBotSkill(eq(7L), eq("run-1"), isNull(), eq("aurabot"),
                eq("aurabot:model:create"), eq(input), any(), eq("preview-1"), isNull());
    }

    @Test
    void aurabotSkillConfirm_acceptsBareSkillNameFromPersistedPendingTool() {
        Map<String, Object> input = Map.of("name", "Acme");
        when(toolLoopService.confirmAuraBotSkill(eq(7L), eq("run-1"), isNull(), eq("aurabot"),
                eq("aurabot:model:create"), eq(input), any(), eq("preview-1"), isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"created\":true}}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, null, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.confirmAuraBotSkill(
                "model:create", input, "crm", "preview-1", "run-1", null, "aurabot");

        assertThat(result).containsEntry("success", true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).confirmAuraBotSkill(eq(7L), eq("run-1"), isNull(), eq("aurabot"),
                eq("aurabot:model:create"), eq(input), toolsCaptor.capture(), eq("preview-1"), isNull());
        AgentToolDefinition toolDef = toolsCaptor.getValue().get(0);
        assertThat(toolDef.getName()).isEqualTo("aurabot:model:create");
        assertThat(toolDef.getSourceCode()).isEqualTo("model:create");
        assertThat(toolDef.getToolType()).isEqualTo("AURABOT_SKILL");
    }

    @Test
    void executeConfirmed_clearsChatConfirmationButKeepsApprovalGateMetadata() {
        AgentToolDefinition discovered = AgentToolDefinition.builder()
                .name("cmd:crm:update_lead")
                .description("Update lead")
                .toolType("built_in")
                .sourceCode("cmd:crm:update_lead")
                .requiresConfirmation(true)
                .requiresApproval(true)
                .inputSchema(Map.of("type", "object"))
                .build();
        Map<String, Object> input = Map.of("recordId", "lead-1");
        when(chatToolResolver.getAgentToolDefinition("cmd_crm_update_lead")).thenReturn(discovered);
        when(toolLoopService.executeToolCall(eq(7L), eq("run-2"), isNull(), eq("aurabot"),
                eq("cmd:crm:update_lead"), eq(input), any(), isNull()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"approvalPid\":\"ap-1\"}");

        ChatToolExecutor executor = new ChatToolExecutor(toolLoopService, chatToolResolver, objectMapper);

        MetaContext.setSystemTenantContext(7L);
        Map<String, Object> result = executor.executeConfirmed(
                "cmd_crm_update_lead", input, "crm_lead", "run-2", null, "aurabot");

        assertThat(result).containsEntry("approvalRequired", true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(eq(7L), eq("run-2"), isNull(), eq("aurabot"),
                eq("cmd:crm:update_lead"), eq(input), toolsCaptor.capture(), isNull());
        AgentToolDefinition toolDef = toolsCaptor.getValue().get(0);
        assertThat(toolDef.isRequiresConfirmation()).isFalse();
        assertThat(toolDef.isRequiresApproval()).isTrue();
    }
}
