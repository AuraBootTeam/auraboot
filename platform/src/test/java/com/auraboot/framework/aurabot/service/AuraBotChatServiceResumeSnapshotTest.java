package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.service.ToolLoopService;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuraBotPendingContinuationService resume snapshot execution")
class AuraBotChatServiceResumeSnapshotTest {

    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private ChatToolResolver chatToolResolver;
    @Mock private ChatToolExecutor chatToolExecutor;
    @Mock private ChatSessionStore chatSessionStore;
    @Mock private AiTraceService aiTraceService;
    @Mock private ToolLoopService toolLoopService;
    @Mock private LlmProvider provider;
    @Mock private ResponseSink sink;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("resume executes the stored AgentToolDefinition snapshot instead of re-inferring DSL command names")
    void resumeUsesStoredAgentToolDefinitionSnapshot() throws Exception {
        AuraBotPendingContinuationService service = newService();
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
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
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

        Method method = AuraBotPendingContinuationService.class.getDeclaredMethod(
                "executeResumeTool", PendingToolSnapshot.class);
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

    @Test
    @DisplayName("resume fails closed when pending payload lacks AgentToolDefinition snapshot")
    void resumeWithoutAgentToolDefinitionSnapshot_failsClosed() {
        AuraBotPendingContinuationService service = newService();
        MetaContext.setContext(1L, 100L, null, "tester");
        TurnContext ctx = TurnContext.legacyDefault(1L, 100L, 100L);
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(ctx.turnId())
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .sessionId("session-1")
                .toolId("toolu-write")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(Map.of("pe_pc_code", "E2E-PCBA-CMP-1"))
                .runPid(ctx.turnId())
                .taskPid("task-1")
                .modelCode("pe_procurement_comparison")
                .providerCode("openai")
                .model("test-model")
                .systemPrompt("system prompt")
                .maxTokens(4096)
                .currentLoop(0)
                .messages(List.of(Map.of("role", "user", "content", "Create draft")))
                .build();

        TurnOutcome outcome = service.resumeApprovedChatTool(ctx, pending, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("Pending tool snapshot is missing agent tool definitions")
                .contains("cmd_pe_create_procurement_comparison_draft");
        verify(chatToolExecutor, never()).executeConfirmed(any(), any(), any(), any(), any(), any());
        verify(toolLoopService, never()).executeToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("resume resolves provider config when pending payload has no stored secret")
    void resumeResolvesProviderConfigWhenPendingHasNoStoredSecret() throws Exception {
        AuraBotPendingContinuationService service = newService();
        MetaContext.setContext(1L, 100L, null, "tester");
        TurnContext ctx = TurnContext.legacyDefault(1L, 100L, 100L);
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
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(ctx.turnId())
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .sessionId("session-1")
                .toolId("toolu-write")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(input)
                .runPid(ctx.turnId())
                .taskPid("task-1")
                .modelCode("pe_procurement_comparison")
                .providerCode("openai")
                .model("test-model")
                .systemPrompt("system prompt")
                .maxTokens(4096)
                .currentLoop(0)
                .messages(List.of(Map.of("role", "user", "content", "Create draft")))
                .agentToolDefinitions(List.of(toolDef))
                .build();
        assertThat(pending.getApiKey()).isNull();
        assertThat(pending.getBaseUrl()).isNull();

        when(toolLoopService.executeToolCall(
                eq(1L),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("fresh-key")
                        .baseUrl("https://fresh.example")
                        .defaultModel("test-model")
                        .maxTokens(4096)
                        .build());
        when(llmProviderFactory.getProvider("openai")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("fresh-key"), eq("https://fresh.example")))
                .thenReturn(endTurnResponse("Draft created."));

        TurnOutcome outcome = service.resumeApprovedChatTool(ctx, pending, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("fresh-key"), eq("https://fresh.example"));
        assertThat(requestCaptor.getValue().getModel()).isEqualTo("test-model");
        verify(sink).onDone("Draft created.", null);
    }

    @Test
    @DisplayName("resume offers the stored tool snapshot to the follow-up LLM call")
    void resumeOffersStoredToolSnapshotToFollowUpLlmCall() throws Exception {
        AuraBotPendingContinuationService service = newService();
        MetaContext.setContext(1L, 100L, null, "tester");
        TurnContext ctx = TurnContext.legacyDefault(1L, 100L, 100L);
        Map<String, Object> input = Map.of("pe_pc_code", "E2E-PCBA-CMP-1");
        AgentToolDefinition toolDef = AgentToolDefinition.builder()
                .name("cmd_pe_create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .requiresConfirmation(true)
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .build();
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(ctx.turnId())
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .sessionId("session-1")
                .toolId("toolu-write")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(input)
                .runPid(ctx.turnId())
                .taskPid("task-1")
                .modelCode("pe_procurement_comparison")
                .providerCode("openai")
                .model("test-model")
                .systemPrompt("system prompt")
                .maxTokens(4096)
                .currentLoop(0)
                .messages(List.of(Map.of("role", "user", "content", "Create draft")))
                .agentToolDefinitions(List.of(toolDef))
                .build();

        when(toolLoopService.executeToolCall(
                eq(1L),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("fresh-key")
                        .baseUrl("https://fresh.example")
                        .defaultModel("test-model")
                        .maxTokens(4096)
                        .build());
        when(llmProviderFactory.getProvider("openai")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("fresh-key"), eq("https://fresh.example")))
                .thenReturn(endTurnResponse("Draft created."));

        TurnOutcome outcome = service.resumeApprovedChatTool(ctx, pending, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("fresh-key"), eq("https://fresh.example"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("cmd_pe_create_procurement_comparison_draft");
        verify(chatToolResolver, never()).resolveTools(any(), any(), any());
    }

    @Test
    @DisplayName("resume delegates follow-up tool_use rounds through shared ChatTurnRuntime loop")
    void resumeFollowUpToolUseRunsThroughSharedChatRuntimeLoop() throws Exception {
        AuraBotPendingContinuationService service = newService();
        MetaContext.setContext(1L, 100L, null, "tester");
        TurnContext ctx = TurnContext.legacyDefault(1L, 100L, 100L);
        Map<String, Object> input = Map.of("pe_pc_code", "E2E-PCBA-CMP-1");
        AgentToolDefinition writeTool = AgentToolDefinition.builder()
                .name("cmd_pe_create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .requiresConfirmation(true)
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .build();
        AgentToolDefinition readTool = AgentToolDefinition.builder()
                .name("nq_lookup_vendor_score")
                .description("Lookup vendor score")
                .toolType("dsl_query")
                .sourceCode("vendor:score")
                .inputSchema(Map.of("type", "object", "properties", Map.of()))
                .requiresConfirmation(false)
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(ctx.turnId())
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .sessionId("session-1")
                .toolId("toolu-write")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(input)
                .runPid(ctx.turnId())
                .taskPid("task-1")
                .modelCode("pe_procurement_comparison")
                .providerCode("openai")
                .model("test-model")
                .systemPrompt("system prompt")
                .maxTokens(4096)
                .currentLoop(0)
                .messages(List.of(Map.of("role", "user", "content", "Create draft")))
                .agentToolDefinitions(List.of(writeTool, readTool))
                .build();

        when(toolLoopService.executeToolCall(
                eq(1L),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");
        when(chatToolResolver.isReadOnly("nq_lookup_vendor_score")).thenReturn(true);
        when(chatToolExecutor.execute(
                eq("nq_lookup_vendor_score"),
                eq(Map.of("vendor", "A")),
                eq("pe_procurement_comparison"),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent")))
                .thenReturn(Map.of("success", true, "data", List.of(Map.of("score", 98))));
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("fresh-key")
                        .baseUrl("https://fresh.example")
                        .defaultModel("test-model")
                        .maxTokens(4096)
                        .build());
        when(llmProviderFactory.getProvider("openai")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("fresh-key"), eq("https://fresh.example")))
                .thenReturn(toolUseResponse("toolu-read", "nq_lookup_vendor_score", Map.of("vendor", "A")),
                        endTurnResponse("Draft created with vendor score."));

        TurnOutcome outcome = service.resumeApprovedChatTool(ctx, pending, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(provider, times(2)).chat(any(LlmChatRequest.class), eq("fresh-key"), eq("https://fresh.example"));
        verify(chatToolExecutor).execute(
                eq("nq_lookup_vendor_score"),
                eq(Map.of("vendor", "A")),
                eq("pe_procurement_comparison"),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"));
        verify(sink).onToolStart("toolu-read", "nq_lookup_vendor_score", Map.of("vendor", "A"));
        verify(sink).onToolResult("toolu-read", Map.of("success", true, "data", List.of(Map.of("score", 98))), true);
        verify(sink).onDone("Draft created with vendor score.", null);
    }

    @Test
    @DisplayName("resume treats a null LLM response as an empty-response failure")
    void resumeTreatsNullLlmResponseAsEmptyResponseFailure() throws Exception {
        AuraBotPendingContinuationService service = newService();
        MetaContext.setContext(1L, 100L, null, "tester");
        TurnContext ctx = TurnContext.legacyDefault(1L, 100L, 100L);
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
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(ctx.turnId())
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .sessionId("session-1")
                .toolId("toolu-write")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(input)
                .runPid(ctx.turnId())
                .taskPid("task-1")
                .modelCode("pe_procurement_comparison")
                .providerCode("openai")
                .model("test-model")
                .systemPrompt("system prompt")
                .maxTokens(4096)
                .currentLoop(0)
                .messages(List.of(Map.of("role", "user", "content", "Create draft")))
                .agentToolDefinitions(List.of(toolDef))
                .build();

        when(toolLoopService.executeToolCall(
                eq(1L),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("fresh-key")
                        .baseUrl("https://fresh.example")
                        .defaultModel("test-model")
                        .maxTokens(4096)
                        .build());
        when(llmProviderFactory.getProvider("openai")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("fresh-key"), eq("https://fresh.example")))
                .thenReturn(null);

        TurnOutcome outcome = service.resumeApprovedChatTool(ctx, pending, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage()).isEqualTo("Empty response from LLM");
        verify(sink).onError("Empty response from LLM", null);
    }

    @Test
    @DisplayName("resume sanitizes LLM exception messages before returning them")
    void resumeSanitizesLlmExceptionMessages() throws Exception {
        AuraBotPendingContinuationService service = newService();
        MetaContext.setContext(1L, 100L, null, "tester");
        TurnContext ctx = TurnContext.legacyDefault(1L, 100L, 100L);
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
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId(ctx.turnId())
                .tenantId(1L)
                .userId(100L)
                .agentCode("pcba_procurement_comparison_agent")
                .sessionId("session-1")
                .toolId("toolu-write")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(input)
                .runPid(ctx.turnId())
                .taskPid("task-1")
                .modelCode("pe_procurement_comparison")
                .providerCode("openai")
                .model("test-model")
                .systemPrompt("system prompt")
                .maxTokens(4096)
                .currentLoop(0)
                .messages(List.of(Map.of("role", "user", "content", "Create draft")))
                .agentToolDefinitions(List.of(toolDef))
                .build();

        when(toolLoopService.executeToolCall(
                eq(1L),
                eq(ctx.turnId()),
                eq("task-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"draft-1\"}}");
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("fresh-key")
                        .baseUrl("https://fresh.example")
                        .defaultModel("test-model")
                        .maxTokens(4096)
                        .build());
        when(llmProviderFactory.getProvider("openai")).thenReturn(provider);
        when(provider.chat(any(LlmChatRequest.class), eq("fresh-key"), eq("https://fresh.example")))
                .thenThrow(new RuntimeException("apiKey=sk-live-secret\nAuthorization: Bearer bearer-secret"));

        TurnOutcome outcome = service.resumeApprovedChatTool(ctx, pending, sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        String errorMessage = ((TurnOutcome.Failed) outcome).errorMessage();
        assertThat(errorMessage)
                .contains("apiKey=[REDACTED]")
                .contains("Authorization: Bearer [REDACTED]")
                .doesNotContain("sk-live-secret", "bearer-secret", "\n");
        verify(sink).onError(errorMessage, null);
    }

    private AuraBotPendingContinuationService newService() {
        AuraBotPendingContinuationService service = new AuraBotPendingContinuationService(
                llmProviderFactory,
                chatToolResolver,
                chatToolExecutor,
                chatSessionStore,
                objectMapper,
                aiTraceService,
                toolLoopService,
                new ChatTurnRuntime(),
                new PendingToolSnapshotFactory(new AgentRuntimeStateFactory()));
        ReflectionTestUtils.setField(service, "maxToolRounds", 5);
        return service;
    }

    private LlmChatResponse endTurnResponse(String text) {
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text")
                        .text(text)
                        .build()))
                .build();
    }

    private LlmChatResponse toolUseResponse(String toolId, String toolName, Map<String, Object> input) {
        return LlmChatResponse.builder()
                .stopReason("tool_use")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id(toolId)
                        .name(toolName)
                        .input(input)
                        .build()))
                .build();
    }
}
