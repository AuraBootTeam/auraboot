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
 * DC.3a (Q-DC.1=A') unit tests for {@link AgentTurnOverrides} consumption in
 * {@link AgentChatPortImpl#runAgentTurn}.
 *
 * <p>{@code AgentChatPortImplExtraToolsTest} (DC.1) and {@code AgentChatPortImplHandoffSignalTest}
 * (DC.2) cover the {@code extraTools} merge path under the new
 * AgentTurnOverrides shape. This class adds the cases unique to DC.3a:
 *
 * <ol>
 *   <li>{@code overrides.systemPromptOverride} non-null → AgentChatPortImpl uses it
 *       in the LlmChatRequest, NOT the agent definition's system_prompt.</li>
 *   <li>{@code overrides.messagesOverride} non-null → AgentChatPortImpl uses
 *       caller's message list, NOT the session-tape restored / ChatRequest.history
 *       built default.</li>
 *   <li>{@code overrides.toolDefsOverride} non-null → tools list comes from
 *       the override (REPLACING ToolProviderRegistry.discoverAll), then extraTools
 *       still merges on top per DC.1 contract.</li>
 *   <li>{@code overrides.persistSessionTape == false} → AgentChatPortImpl skips
 *       writes to ChatSessionStore.storeConversationMessages.</li>
 *   <li>Default: when overrides is null, behavior is identical to the 3-arg
 *       overload (regression guard for aurabot main path).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentChatPortImpl — DC.3a AgentTurnOverrides consumption")
class AgentChatPortImplOverridesTest {

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
    private static final String SESSION_ID = "sess-overrides-1";

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
                "system_prompt", "DEFAULT system prompt from agent definition.",
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
        when(toolProviderRegistry.discoverAll(any())).thenReturn(List.of(
                ToolDefinition.builder().toolCode("nq_registry_default").description("registry tool")
                        .toolType("dsl_query").sourceCode("registry").riskLevel("L0").build()));

        // Default LLM response: end_turn with one text block
        when(provider.chat(any(), anyString(), anyString())).thenReturn(LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("done").build()))
                .build());
    }

    private TurnContext newCtx() {
        return TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID);
    }

    private ChatRequest newRequest() {
        ChatRequest req = new ChatRequest();
        req.setSessionId(SESSION_ID);
        req.setMessage("hi");
        req.setAgentCode(AGENT_CODE);
        return req;
    }

    private LlmChatRequest captureProviderRequest() throws Exception {
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(1)).chat(captor.capture(), anyString(), anyString());
        return captor.getValue();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("systemPromptOverride non-null -> used in LlmChatRequest, not agent_definition.system_prompt")
    void systemPromptOverride_replacesDefault() throws Exception {
        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .systemPromptOverride("CALLER-supplied group-chat prompt with multi-agent awareness.")
                .build();

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        LlmChatRequest sent = captureProviderRequest();
        assertThat(sent.getSystemPrompt())
                .isEqualTo("CALLER-supplied group-chat prompt with multi-agent awareness.");
        assertThat(sent.getSystemPrompt())
                .doesNotContain("DEFAULT system prompt from agent definition.");
    }

    @Test
    @DisplayName("messagesOverride non-null -> used as LlmChatRequest.messages, NOT session-tape/history default")
    void messagesOverride_replacesDefault() throws Exception {
        List<LlmChatRequest.Message> override = List.of(
                LlmChatRequest.Message.builder().role("user").content("group chat msg 1").build(),
                LlmChatRequest.Message.builder().role("assistant").content("agent beta said x").build(),
                LlmChatRequest.Message.builder().role("user").content("group chat msg 2 - to alpha").build());
        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .messagesOverride(override)
                .build();

        service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        LlmChatRequest sent = captureProviderRequest();
        assertThat(sent.getMessages())
                .hasSize(3)
                .extracting(LlmChatRequest.Message::getContent)
                .containsExactly("group chat msg 1", "agent beta said x", "group chat msg 2 - to alpha");
        // session tape NOT loaded when messages override present
        verify(chatSessionStore, never()).loadConversationMessages(anyString());
    }

    @Test
    @DisplayName("toolDefsOverride non-null -> REPLACES registry discovery; extraTools still merges on top")
    void toolDefsOverride_replacesRegistryAndExtraToolsMerges() throws Exception {
        ToolDefinition agentAttachedTool = ToolDefinition.builder()
                .toolCode("nq_alpha_specialist_query")
                .description("agent-attached tool not in registry")
                .toolType("dsl_query").sourceCode("alpha_specialist").riskLevel("L0").build();
        ToolDefinition handoffTool = ToolDefinition.builder()
                .toolCode("transfer_to_agent")
                .description("handoff")
                .toolType("custom").sourceCode("handoff").build();
        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .toolDefsOverride(List.of(agentAttachedTool))
                .extraTools(List.of(handoffTool))
                .build();

        service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        LlmChatRequest sent = captureProviderRequest();
        assertThat(sent.getTools()).extracting(LlmChatRequest.Tool::getName)
                .containsExactlyInAnyOrder("nq_alpha_specialist_query", "transfer_to_agent")
                .doesNotContain("nq_registry_default");
        // toolProviderRegistry was NOT called when override present
        verify(toolProviderRegistry, never()).discoverAll(any());
    }

    @Test
    @DisplayName("persistSessionTape=false -> AgentChatPortImpl skips ChatSessionStore.storeConversationMessages")
    void persistSessionTapeFalse_skipsTapeWrite() throws Exception {
        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                .persistSessionTape(false)
                .build();

        service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        verify(chatSessionStore, never()).storeConversationMessages(anyString(), any());
    }

    @Test
    @DisplayName("persistSessionTape=true (default) -> tape written as before")
    void persistSessionTapeDefault_writesTape() throws Exception {
        AgentTurnOverrides overrides = AgentTurnOverrides.builder()
                // persistSessionTape unset → default true
                .build();

        service.runAgentTurn(newCtx(), newRequest(), sink, overrides);

        verify(chatSessionStore, times(1)).storeConversationMessages(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("overrides=null -> behavior identical to 3-arg overload (aurabot main path regression guard)")
    void nullOverrides_matchesDefaultBehavior() throws Exception {
        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, (AgentTurnOverrides) null);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        LlmChatRequest sent = captureProviderRequest();
        // Default system prompt from agent_definition is used
        assertThat(sent.getSystemPrompt()).contains("DEFAULT system prompt from agent definition.");
        // Default tools come from registry
        assertThat(sent.getTools()).extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_registry_default");
        // Tape persisted by default
        verify(chatSessionStore, times(1)).storeConversationMessages(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("3-arg default method delegates to 4-arg with null overrides (no behavior change)")
    void threeArgOverload_matchesNullOverrides() throws Exception {
        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        LlmChatRequest sent = captureProviderRequest();
        assertThat(sent.getSystemPrompt()).contains("DEFAULT system prompt from agent definition.");
        assertThat(sent.getTools()).extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_registry_default");
    }
}
