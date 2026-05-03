package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.port.AgentTurnOverrides;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agentchat.handoff.HandoffToolProvider;
import com.auraboot.framework.agentchat.spi.AgentMemberDto;
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
import static org.mockito.Mockito.when;

/**
 * DC.3d (Fix 4) regression test: drives {@link AgentChatPortImpl} with the
 * REAL {@link HandoffToolProvider} schema (not the synthetic
 * {@code targetAgentCode} field that DC.2 unit tests baked in by accident).
 *
 * <p>Why this test exists:
 * <ol>
 *   <li>{@link HandoffToolProvider#getToolDefinition} declares the input
 *       schema with two properties: {@code agent_code} and {@code context}.
 *       That is what the LLM sees and returns in the {@code tool_use} input
 *       map.</li>
 *   <li>Pre-DC.3d, {@link AgentChatPortImpl#buildHandoffOutcome} read
 *       {@code input.get("targetAgentCode")} — a field name that never
 *       appeared in the real schema. The handoff meta was therefore always
 *       empty in production; the bug was masked because DC.2 unit tests used
 *       a synthetic schema that matched the buggy reader.</li>
 *   <li>This test wires the real {@link HandoffToolProvider} as the
 *       extraTools provider AND simulates the LLM emitting the field name
 *       the real schema declares ({@code agent_code}). It would fail before
 *       DC.3d and passes after the {@code agent_code} read fix.</li>
 * </ol>
 *
 * <p>Compared to {@link AgentChatPortImplHandoffSignalTest} which is a
 * targeted unit test with a synthetic ToolDefinition, this test exercises
 * the real provider's schema generation and the chokepoint's handoff
 * outcome together — preventing the schema/reader drift from re-occurring.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentChatPortImpl — DC.3d real HandoffToolProvider schema regression")
class AgentChatPortImplHandoffRealSchemaTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private GroundingService groundingService;
    @Mock private AgentSkillService skillService;
    @Mock private LlmProvider provider;
    @Mock private ResponseSink sink;
    @Mock private ChatSessionStore chatSessionStore;

    private AgentChatPortImpl service;
    private HandoffToolProvider handoffProvider;

    private static final long TENANT_ID = 7L;
    private static final long USER_ID = 100L;
    private static final String AGENT_CODE = "agent_alpha";
    private static final String SESSION_ID = "sess-real-1";

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        service = new AgentChatPortImpl(dynamicDataMapper, providerFactory, toolProviderRegistry,
                groundingService, skillService, mapper, chatSessionStore);
        handoffProvider = new HandoffToolProvider();

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

    /** Build the real handoff tool def for two members + wrap in extraTools-shaped ToolDefinition. */
    private ToolDefinition realHandoffToolDef() {
        AgentMemberDto beta = AgentMemberDto.builder()
                .agentId(2L).agentCode("agent_beta").name("Beta").build();
        AgentMemberDto gamma = AgentMemberDto.builder()
                .agentId(3L).agentCode("agent_gamma").name("Gamma").build();
        LlmChatRequest.Tool tool = handoffProvider.getToolDefinition(List.of(beta, gamma));
        // Sanity-check the real schema (this is the contract DC.3d locks):
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>)
                ((Map<String, Object>) tool.getInputSchema()).get("properties");
        assertThat(properties).containsKey("agent_code").containsKey("context");
        assertThat(properties).doesNotContainKey("targetAgentCode");
        // Wrap it as a ToolDefinition so it goes through the AgentChatPortImpl
        // extraTools merge → toLlmTools conversion → provider.chat tools field.
        return ToolDefinition.builder()
                .toolCode(tool.getName())
                .description(tool.getDescription())
                .parameterSchema(tool.getInputSchema())
                .toolType("custom")
                .sourceCode("agentchat_handoff_real")
                .build();
    }

    private LlmChatResponse handoffToolUseResponse(String agentCodeArg, String contextArg) {
        LlmChatResponse resp = new LlmChatResponse();
        resp.setStopReason("tool_use");
        java.util.List<LlmChatResponse.ContentBlock> blocks = new java.util.ArrayList<>();
        LlmChatResponse.ContentBlock textBlock = new LlmChatResponse.ContentBlock();
        textBlock.setType("text");
        textBlock.setText("Routing to Beta...");
        blocks.add(textBlock);
        LlmChatResponse.ContentBlock toolBlock = new LlmChatResponse.ContentBlock();
        toolBlock.setType("tool_use");
        toolBlock.setName("transfer_to_agent");
        toolBlock.setId("tool_call_real_handoff_1");
        java.util.Map<String, Object> input = new java.util.LinkedHashMap<>();
        if (agentCodeArg != null) input.put("agent_code", agentCodeArg);
        if (contextArg != null) input.put("context", contextArg);
        toolBlock.setInput(input);
        blocks.add(toolBlock);
        resp.setContent(blocks);
        return resp;
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("real HandoffToolProvider schema -> LLM emits agent_code -> meta._handoff_to populated")
    void realSchema_handoffMetaPopulated() throws Exception {
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(handoffToolUseResponse("agent_beta", "user prefers Beta"));

        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .extraTools(List.of(realHandoffToolDef()))
                .build();
        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.meta())
                .as("DC.3d Fix 4: chokepoint reads agent_code (the real schema field), not targetAgentCode")
                .containsEntry("_handoff_to", "agent_beta")
                .containsEntry("_handoff_context", "user prefers Beta");
    }

    @Test
    @DisplayName("real schema with bare agent_code (no context) -> meta._handoff_to set, _handoff_context absent")
    void realSchema_agentCodeOnly_contextOmitted() throws Exception {
        when(provider.chat(any(), anyString(), anyString()))
                .thenReturn(handoffToolUseResponse("agent_gamma", null));

        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .extraTools(List.of(realHandoffToolDef()))
                .build();
        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.meta())
                .containsEntry("_handoff_to", "agent_gamma")
                .doesNotContainKey("_handoff_context");
    }

    @Test
    @DisplayName("regression guard: targetAgentCode-shaped input is NOT recognized (proves the schema fix)")
    void regressionGuard_oldFieldNameIgnored() throws Exception {
        // Simulate an LLM that hallucinates the OLD wrong field name. Real
        // schema rejects this at validation time, but the chokepoint must
        // also not accidentally accept it via the legacy reader path.
        LlmChatResponse resp = new LlmChatResponse();
        resp.setStopReason("tool_use");
        LlmChatResponse.ContentBlock toolBlock = new LlmChatResponse.ContentBlock();
        toolBlock.setType("tool_use");
        toolBlock.setName("transfer_to_agent");
        toolBlock.setId("tool_call_old_field_1");
        toolBlock.setInput(Map.of("targetAgentCode", "agent_beta", "context", "ignored"));
        resp.setContent(List.of(toolBlock));
        when(provider.chat(any(), anyString(), anyString())).thenReturn(resp);

        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .extraTools(List.of(realHandoffToolDef()))
                .build();
        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        TurnOutcome.Success success = (TurnOutcome.Success) outcome;
        assertThat(success.meta())
                .as("DC.3d guard: legacy targetAgentCode key must NOT populate handoff meta")
                .doesNotContainKey("_handoff_to")
                // context field still gets read because the schema declares it,
                // but with no _handoff_to the caller treats this as a no-op handoff.
                .containsEntry("_handoff_context", "ignored");
    }
}
