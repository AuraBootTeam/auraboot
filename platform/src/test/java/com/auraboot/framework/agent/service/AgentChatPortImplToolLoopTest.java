package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AgentChatPortImpl#runAgentTurn} — the named-agent
 * tool-loop continuation re-introduced under the Phase B.0 sink-based SPI.
 *
 * <p>The legacy version of this test exercised
 * {@code streamAgentChat(tenantId, agentCode, request, emitter)} which was
 * removed during the B.0/B.6 → main merge resolution. This rewrite asserts the
 * same behaviors against the canonical
 * {@code runAgentTurn(TurnContext, ChatRequest, ResponseSink): TurnOutcome}
 * surface — text streaming, tool_use auto-execution, confirmation suspension,
 * max-rounds cap, LLM failure surfacing, and persisted-tape rehydration.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChatPortImpl runAgentTurn tool loop")
class AgentChatPortImplToolLoopTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private GroundingService groundingService;
    @Mock private AgentSkillService skillService;
    @Mock private ChatSessionStore chatSessionStore;
    @Mock private LlmProvider provider;
    @Mock private ResponseSink sink;

    private AgentChatPortImpl service;

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 100L;
    private static final String AGENT_CODE = "pcba_procurement_comparison_agent";
    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        service = new AgentChatPortImpl(
                dynamicDataMapper,
                providerFactory,
                toolProviderRegistry,
                groundingService,
                skillService,
                new ObjectMapper(),
                chatSessionStore);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private TurnContext newTurnContext() {
        return TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID);
    }

    private ChatRequest newRequest(String message) {
        ChatRequest request = new ChatRequest();
        request.setSessionId(SESSION_ID);
        request.setMessage(message);
        request.setAgentCode(AGENT_CODE);
        return request;
    }

    private void stubAgentDefinition() {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", AGENT_CODE,
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}")));
    }

    private void stubProvider() {
        when(providerFactory.resolveConfig(eq(TENANT_ID), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://example.invalid")
                        .defaultModel("test-model")
                        .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
    }

    private void stubGrounding() {
        when(groundingService.ground(eq(TENANT_ID), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("compare")
                .object("pe_procurement_comparison")
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
    }

    private ToolDefinition readOnlyTool() {
        return ToolDefinition.builder()
                .toolCode("nq_pe_procurement_comparison_supplier_options")
                .description("Supplier options")
                .toolType("dsl_query")
                .sourceCode("pe_procurement_comparison_supplier_options")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
    }

    private ToolDefinition writeTool() {
        return ToolDefinition.builder()
                .toolCode("cmd_pe_create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();
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

    // =========================================================================
    // tests
    // =========================================================================

    @Test
    @DisplayName("end_turn yields TurnOutcome.Success and streams the assistant text through the sink")
    void endTurnYieldsSuccessAndStreamsText() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(endTurnResponse("Acme PCB is available."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.finalResponse()).isEqualTo("Acme PCB is available.");
        verify(sink).onTextChunk("Acme PCB is available.");
        verify(sink).onDone("Acme PCB is available.", null);
        // Final-turn message tape persisted so the next turn can rehydrate.
        verify(chatSessionStore).storeConversationMessages(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("tool_use round emits onToolStart/onToolResult and feeds the tool result back to the LLM")
    void toolUseRoundExecutesAndFeedsResultBack() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-1",
                        "nq_pe_procurement_comparison_supplier_options",
                        Map.of("productId", "P-100")))
                .thenReturn(endTurnResponse("Acme PCB is available."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        // Read-only tool was auto-executed and signaled to the sink.
        verify(sink).onToolStart(eq("toolu-1"),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(Map.of("productId", "P-100")));
        verify(sink).onToolResult(eq("toolu-1"), anyMap(), eq(true));
        // Two LLM round trips: tool_use then end_turn.
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        LlmChatRequest secondRound = requestCaptor.getAllValues().get(1);
        // The second round must carry the assistant tool_use + user tool_result blocks.
        assertThat(secondRound.getMessages()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(String.valueOf(secondRound.getMessages())).contains("tool_result");
        // No confirm_required on a read-only tool.
        verify(sink, never()).onConfirmRequired(any(), any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("requiresConfirmation tool suspends turn with PendingConfirmation and stores PendingTool keyed by turnId")
    void confirmationToolSuspendsTurn() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(writeTool()));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-write",
                        "cmd_pe_create_procurement_comparison_draft",
                        Map.of("productId", "P-100")));

        TurnContext ctx = newTurnContext();
        TurnOutcome outcome = service.runAgentTurn(ctx, newRequest("Create draft for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        TurnOutcome.PendingConfirmation pending = (TurnOutcome.PendingConfirmation) outcome;
        assertThat(pending.pendingTurnId()).isEqualTo(ctx.turnId());
        assertThat(pending.pendingToolId()).isEqualTo("toolu-write");

        // SSE confirmation event with the turnId echoed for the frontend.
        verify(sink).onConfirmRequired(
                eq("toolu-write"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                any(String.class),
                eq(Map.of("productId", "P-100")),
                eq(ctx.turnId()));
        verify(sink).onDone("", null);

        // Pending entry persisted keyed by turnId; tape persisted by sessionId.
        ArgumentCaptor<ChatSessionStore.PendingTool> pendingCaptor =
                ArgumentCaptor.forClass(ChatSessionStore.PendingTool.class);
        verify(chatSessionStore).storePending(eq(ctx.turnId()), pendingCaptor.capture());
        ChatSessionStore.PendingTool stored = pendingCaptor.getValue();
        assertThat(stored.getTurnId()).isEqualTo(ctx.turnId());
        assertThat(stored.getToolId()).isEqualTo("toolu-write");
        assertThat(stored.getToolName()).isEqualTo("cmd_pe_create_procurement_comparison_draft");
        assertThat(stored.getAgentCode()).isEqualTo(AGENT_CODE);
        assertThat(stored.getProviderCode()).isEqualTo("openai");
        assertThat(stored.getModel()).isEqualTo("test-model");
        assertThat(stored.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(stored.getMessages()).isNotEmpty();
        verify(chatSessionStore).storeConversationMessages(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("LLM call failure surfaces TurnOutcome.Failed and emits sink.onError")
    void llmFailureYieldsFailedOutcome() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenThrow(new RuntimeException("boom"));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Hello"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        TurnOutcome.Failed failed = (TurnOutcome.Failed) outcome;
        assertThat(failed.errorMessage()).contains("boom");
        verify(sink).onError(any(String.class), eq(null));
        // No sink.onDone on failure.
        verify(sink, never()).onDone(any(String.class), any());
    }

    @Test
    @DisplayName("loop exceeding MAX_TOOL_ROUNDS yields TurnOutcome.Failed with the cap message")
    void loopExceedsMaxRoundsYieldsFailed() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        // Return tool_use forever — loop must abort at MAX_TOOL_ROUNDS (5).
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-loop",
                        "nq_pe_procurement_comparison_supplier_options",
                        Map.of("productId", "P-100")));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(),
                newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        TurnOutcome.Failed failed = (TurnOutcome.Failed) outcome;
        assertThat(failed.errorMessage()).contains("maximum rounds");
        // Provider was called exactly MAX_TOOL_ROUNDS times before the cap kicked in.
        verify(provider, times(5)).chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid"));
        verify(sink).onError(any(String.class), eq(null));
    }

    @Test
    @DisplayName("server-side message tape rehydrates across turns instead of trusting frontend history")
    void serverSideTapeRehydratesAcrossTurns() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());

        // Prior tape: assistant tool_use + user tool_result (cannot be expressed
        // by frontend ChatMessage history at all).
        List<Map<String, Object>> storedTape = new ArrayList<>();
        Map<String, Object> assistantMsg = new java.util.LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", List.of(Map.of(
                "type", "tool_use",
                "id", "toolu-1",
                "name", "nq_pe_procurement_comparison_supplier_options",
                "input", Map.of("productId", "P-100"))));
        storedTape.add(assistantMsg);
        Map<String, Object> userToolResult = new java.util.LinkedHashMap<>();
        userToolResult.put("role", "user");
        userToolResult.put("content", List.of(Map.of(
                "type", "tool_result",
                "toolUseId", "toolu-1",
                "result", "{\"records\":[{\"supplier\":\"Acme PCB\"}]}")));
        storedTape.add(userToolResult);
        when(chatSessionStore.loadConversationMessages(SESSION_ID)).thenReturn(storedTape);

        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(endTurnResponse("Ready to create the draft."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(),
                newRequest("Confirm draft creation"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        String messagesText = String.valueOf(requestCaptor.getValue().getMessages());
        // Persisted tape (Acme PCB tool_result) survived; new user message appended.
        assertThat(messagesText).contains("Acme PCB", "tool_result", "Confirm draft creation");
    }
}
