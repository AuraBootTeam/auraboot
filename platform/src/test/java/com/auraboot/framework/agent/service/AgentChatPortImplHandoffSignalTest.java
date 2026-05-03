package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.port.AgentTurnOverrides;
import com.auraboot.framework.agent.provider.ToolDefinition;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DC.2 (Q-DC.2=α) unit tests for the handoff signal in
 * {@link AgentChatPortImpl#runAgentTurn}. Asserts:
 *
 * <ol>
 *   <li>LLM emits {@code transfer_to_agent} tool_use → AgentChatPortImpl
 *       does NOT execute the tool, returns {@link TurnOutcome.Success}
 *       with {@code meta._handoff_to=<agent_code>} and (when present)
 *       {@code meta._handoff_context}.</li>
 *   <li>Other tool calls (read-only / confirmation-required) behave
 *       exactly as before — DC.2 is additive.</li>
 *   <li>{@code transfer_to_agent} without {@code agent_code} input
 *       still surfaces a Success outcome but {@code meta} is empty (caller
 *       handles missing target as a no-op).</li>
 *   <li>Streaming continues: any text the LLM emitted alongside the
 *       transfer_to_agent block is sent through {@code sink.onTextChunk}
 *       + {@code sink.onDone} so the SSE / WS stream terminates cleanly.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentChatPortImpl — DC.2 transfer_to_agent handoff signal")
class AgentChatPortImplHandoffSignalTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private GroundingService groundingService;
    @Mock private AgentSkillService skillService;
    @Mock private LlmProvider provider;
    @Mock private ResponseSink sink;
    @Mock private ChatSessionStore chatSessionStore;

    private AgentChatPortImpl service;

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 100L;
    private static final String AGENT_CODE = "agent_alpha";
    private static final String SESSION_ID = "sess-handoff-1";

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        service = new AgentChatPortImpl(dynamicDataMapper, providerFactory, toolProviderRegistry,
                groundingService, skillService, mapper, chatSessionStore);

        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", AGENT_CODE,
                "name", "Alpha",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Helpful.",
                "guardrails", "{\"provider\":\"openai\"}")));

        when(providerFactory.resolveConfig(eq(TENANT_ID), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai").apiKey("key").baseUrl("https://example.invalid")
                        .defaultModel("test-model").build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);

        when(groundingService.ground(eq(TENANT_ID), any(), any()))
                .thenReturn(BusinessIntentFrame.builder()
                        .intent("query").object("test").riskLevel("L0").actionability("read_only")
                        .confidence(ConfidenceScore.of(0.9, 0.9)).build());

        when(chatSessionStore.loadConversationMessages(anyString())).thenReturn(List.of());
        when(toolProviderRegistry.discoverAll(any())).thenReturn(List.of());
    }

    private TurnContext newCtx() {
        return TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID);
    }

    private ChatRequest newRequest() {
        ChatRequest req = new ChatRequest();
        req.setSessionId(SESSION_ID);
        req.setMessage("hand off to beta please");
        req.setAgentCode(AGENT_CODE);
        return req;
    }

    private ToolDefinition handoffToolDef() {
        return ToolDefinition.builder()
                .toolCode("transfer_to_agent")
                .description("Hand off to another agent")
                .toolType("custom")
                .sourceCode("agentchat_handoff")
                .build();
    }

    private LlmChatResponse handoffToolUseResponse(String targetAgentCode, String context, String prelude) {
        LlmChatResponse resp = new LlmChatResponse();
        resp.setStopReason("tool_use");
        java.util.List<LlmChatResponse.ContentBlock> blocks = new java.util.ArrayList<>();
        if (prelude != null && !prelude.isEmpty()) {
            LlmChatResponse.ContentBlock textBlock = new LlmChatResponse.ContentBlock();
            textBlock.setType("text");
            textBlock.setText(prelude);
            blocks.add(textBlock);
        }
        LlmChatResponse.ContentBlock toolBlock = new LlmChatResponse.ContentBlock();
        toolBlock.setType("tool_use");
        toolBlock.setName("transfer_to_agent");
        toolBlock.setId("tool_call_handoff_1");
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        if (targetAgentCode != null) input.put("agent_code", targetAgentCode);
        if (context != null) input.put("context", context);
        toolBlock.setInput(input);
        blocks.add(toolBlock);
        resp.setContent(blocks);
        return resp;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("transfer_to_agent hit -> Success.meta carries _handoff_to + _handoff_context; tool NOT executed")
    void handoffToolUse_returnsSuccessWithMetaNoExecution() throws Exception {
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(handoffToolUseResponse("agent_beta", "user wants beta", "Handing off to Beta..."));

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, AgentTurnOverrides.builder().extraTools(List.of(handoffToolDef())).build());

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.finalResponse()).isEqualTo("Handing off to Beta...");
        assertThat(success.meta())
                .containsEntry("_handoff_to", "agent_beta")
                .containsEntry("_handoff_context", "user wants beta");
        // The text "Handing off to Beta..." went through the sink
        verify(sink, times(1)).onTextChunk(eq("Handing off to Beta..."));
        verify(sink, times(1)).onDone(eq("Handing off to Beta..."), any());
        // No tool execution happened — onToolStart / onToolResult NOT called
        verify(sink, never()).onToolStart(anyString(), anyString(), any());
        verify(sink, never()).onToolResult(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        // LLM was called exactly once — we returned without continuing the loop
        verify(provider, times(1)).chat(any(), anyString(), anyString());
    }

    @Test
    @DisplayName("transfer_to_agent without prelude text -> Success.finalResponse=\"\"; meta still carries handoff info")
    void handoffToolUse_emptyPrelude_returnsEmptyResponse() throws Exception {
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(handoffToolUseResponse("agent_beta", null, ""));

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, AgentTurnOverrides.builder().extraTools(List.of(handoffToolDef())).build());

        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.finalResponse()).isEmpty();
        assertThat(success.meta())
                .containsEntry("_handoff_to", "agent_beta")
                .doesNotContainKey("_handoff_context");
        // No onTextChunk call when prelude is empty
        verify(sink, never()).onTextChunk(anyString());
        // onDone still fires so the stream terminates
        verify(sink, times(1)).onDone(eq(""), any());
    }

    @Test
    @DisplayName("transfer_to_agent missing agent_code input -> Success with empty meta (caller handles)")
    void handoffToolUse_missingTarget_emptyMeta() throws Exception {
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(handoffToolUseResponse(null, null, "passing through"));

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, AgentTurnOverrides.builder().extraTools(List.of(handoffToolDef())).build());

        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.meta()).isEmpty();
    }

    @Test
    @DisplayName("non-handoff tool call still goes through normal read-only execution path (DC.2 is additive)")
    void nonHandoffToolUse_executesAsBefore() throws Exception {
        // Round 1: LLM calls a regular read-only tool (not transfer_to_agent)
        ToolDefinition regular = ToolDefinition.builder()
                .toolCode("nq_demo_query")
                .description("regular tool")
                .toolType("dsl_query")
                .sourceCode("demo")
                .riskLevel("L0")
                .build();
        when(toolProviderRegistry.discoverAll(any())).thenReturn(List.of(regular));

        LlmChatResponse round1 = new LlmChatResponse();
        round1.setStopReason("tool_use");
        LlmChatResponse.ContentBlock toolBlock = new LlmChatResponse.ContentBlock();
        toolBlock.setType("tool_use");
        toolBlock.setName("nq_demo_query");
        toolBlock.setId("tool_call_1");
        toolBlock.setInput(Map.of("q", "x"));
        round1.setContent(List.of(toolBlock));

        // Round 2: end_turn — LLM returns text after tool result
        LlmChatResponse round2 = new LlmChatResponse();
        round2.setStopReason("end_turn");
        LlmChatResponse.ContentBlock textBlock = new LlmChatResponse.ContentBlock();
        textBlock.setType("text");
        textBlock.setText("done");
        round2.setContent(List.of(textBlock));

        when(provider.chat(any(), anyString(), anyString())).thenReturn(round1).thenReturn(round2);

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, AgentTurnOverrides.builder().extraTools(List.of(handoffToolDef())).build());

        // Final outcome is Success (from end_turn round 2), NOT handoff
        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        assertThat(((TurnOutcome.Success) outcome).meta()).doesNotContainKey("_handoff_to");
        // Regular tool DID execute
        verify(sink, times(1)).onToolStart(eq("tool_call_1"), eq("nq_demo_query"), any());
        verify(sink, times(1)).onToolResult(eq("tool_call_1"), any(),
                org.mockito.ArgumentMatchers.anyBoolean());
        // LLM called twice (tool_use round + end_turn round)
        verify(provider, times(2)).chat(any(), anyString(), anyString());
    }
}
