package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuraBotChatService resume snapshot execution")
class AuraBotChatServiceResumeSnapshotTest {

    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private ChatToolResolver chatToolResolver;
    @Mock private ChatToolExecutor chatToolExecutor;
    @Mock private ChatSessionStore chatSessionStore;
    @Mock private AiTraceService aiTraceService;
    @Mock private MetaModelService metaModelService;
    @Mock private ToolLoopService toolLoopService;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("resume executes the stored AgentToolDefinition snapshot instead of re-inferring DSL command names")
    void resumeUsesStoredAgentToolDefinitionSnapshot() throws Exception {
        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                chatSessionStore,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "toolLoopService", toolLoopService);
        MetaContext.setContext(1L, 100L, null, "tester");

        Map<String, Object> input = Map.of("pe_pc_code", "E2E-PCBA-CMP-1");
        AgentToolDefinition toolDef = AgentToolDefinition.builder()
                .name("cmd_pe_create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .requiresConfirmation(true)
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .build();
        ChatSessionStore.PendingTool pending = ChatSessionStore.PendingTool.builder()
                .turnId("turn-1")
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(input)
                .runPid("turn-1")
                .taskPid("task-1")
                .agentToolDefinitions(List.of(toolDef))
                .build();
        when(toolLoopService.executeToolCall(
                eq(1L),
                eq("turn-1"),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");

        Method method = AuraBotChatService.class.getDeclaredMethod(
                "executeResumeTool", ChatSessionStore.PendingTool.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(service, pending);

        assertThat(result)
                .containsEntry("success", true)
                .containsEntry("data", Map.of("pid", "draft-1"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> defsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(
                eq(1L),
                eq("turn-1"),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                defsCaptor.capture(),
                isNull());
        assertThat(defsCaptor.getValue()).hasSize(1);
        assertThat(defsCaptor.getValue().get(0).getName())
                .isEqualTo("cmd_pe_create_procurement_comparison_draft");
        assertThat(defsCaptor.getValue().get(0).getSourceCode())
                .isEqualTo("pe:create_procurement_comparison_draft");
        assertThat(defsCaptor.getValue().get(0).isRequiresConfirmation()).isFalse();
        verify(chatToolExecutor, never()).executeConfirmed(
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                isNull(),
                eq("turn-1"),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"));
    }
}
