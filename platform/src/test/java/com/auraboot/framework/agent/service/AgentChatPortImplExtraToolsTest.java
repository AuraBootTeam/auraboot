package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
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

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DC.1 (Q-DC.1=β) unit tests for the {@code extraTools} parameter on
 * {@link AgentChatPortImpl#runAgentTurn(TurnContext, ChatRequest, ResponseSink, List)}.
 *
 * <p>Asserts:
 * <ol>
 *   <li>The 3-arg overload (default method on the SPI) calls the 4-arg overload
 *       with empty extraTools — aurabot main path behavior unchanged.</li>
 *   <li>extraTools-supplied tool surfaces in the LlmChatRequest sent to the
 *       provider, alongside registry-discovered tools.</li>
 *   <li>Name collision: extraTools entry wins; registry tool with the same
 *       toolCode is dropped (with a WARN log we don't assert on directly).</li>
 *   <li>Empty / null extraTools list both no-op (registry tools pass through
 *       unmodified).</li>
 * </ol>
 *
 * <p>Other AgentChatPortImpl behaviors (tool loop, confirmation suspension,
 * persistence tape) are covered by AgentChatPortImplToolLoopTest. This test
 * focuses on the new merge surface only.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentChatPortImpl — DC.1 extraTools merge")
class AgentChatPortImplExtraToolsTest {

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
    private static final String AGENT_CODE = "test-agent";
    private static final String SESSION_ID = "sess-1";

    @BeforeEach
    void setUp() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        service = new AgentChatPortImpl(dynamicDataMapper, providerFactory, toolProviderRegistry,
                groundingService, skillService, mapper, chatSessionStore);

        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", AGENT_CODE,
                "name", "Test Agent",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Helpful.",
                "guardrails", "{\"provider\":\"openai\"}")));

        when(providerFactory.resolveConfig(eq(TENANT_ID), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://example.invalid")
                        .defaultModel("test-model")
                        .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);

        when(groundingService.ground(eq(TENANT_ID), any(), any()))
                .thenReturn(BusinessIntentFrame.builder()
                        .intent("query")
                        .object("test")
                        .riskLevel("L0")
                        .actionability("read_only")
                        .confidence(ConfidenceScore.of(0.9, 0.9))
                        .build());

        when(provider.chat(any(), anyString(), anyString())).thenReturn(LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("text").text("done").build()))
                .build());

        when(chatSessionStore.loadConversationMessages(anyString())).thenReturn(List.of());
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

    private ToolDefinition tool(String toolCode, String description) {
        return ToolDefinition.builder()
                .toolCode(toolCode)
                .description(description)
                .toolType("dsl_query")
                .sourceCode(toolCode)
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<LlmChatRequest.Tool> capturedToolsFromProviderCall() throws Exception {
        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(1)).chat(captor.capture(), anyString(), anyString());
        return captor.getValue().getTools();
    }

    // =========================================================================
    // Tests
    // =========================================================================

    @Test
    @DisplayName("3-arg overload defaults to empty extraTools — registry tools pass through unchanged")
    void threeArgOverload_defaultsToEmptyExtraTools() throws Exception {
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("nq_demo_query", "demo query")));

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        List<LlmChatRequest.Tool> tools = capturedToolsFromProviderCall();
        assertThat(tools).extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_demo_query");
    }

    @Test
    @DisplayName("extraTools entry surfaces in LlmChatRequest alongside registry tools")
    void extraTools_appendedToRegistryList() throws Exception {
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("nq_demo_query", "registry tool")));
        ToolDefinition handoff = tool("transfer_to_agent", "handoff");

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, List.of(handoff));

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        List<LlmChatRequest.Tool> tools = capturedToolsFromProviderCall();
        assertThat(tools).extracting(LlmChatRequest.Tool::getName)
                .containsExactlyInAnyOrder("nq_demo_query", "transfer_to_agent");
    }

    @Test
    @DisplayName("Name collision: extraTools wins, registry tool with same toolCode dropped")
    void extraTools_nameCollision_extraWins() throws Exception {
        ToolDefinition registryHandoff = ToolDefinition.builder()
                .toolCode("transfer_to_agent")
                .description("registry-defined transfer")
                .toolType("dsl_command")
                .sourceCode("registry_transfer")
                .build();
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("nq_demo_query", "demo"), registryHandoff));
        ToolDefinition callerHandoff = ToolDefinition.builder()
                .toolCode("transfer_to_agent")
                .description("CALLER-supplied handoff")
                .toolType("custom")
                .sourceCode("agentchat_handoff")
                .build();

        TurnOutcome outcome = service.runAgentTurn(newCtx(), newRequest(), sink, List.of(callerHandoff));

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        List<LlmChatRequest.Tool> tools = capturedToolsFromProviderCall();
        assertThat(tools).extracting(LlmChatRequest.Tool::getName)
                .containsExactlyInAnyOrder("nq_demo_query", "transfer_to_agent");

        LlmChatRequest.Tool transferTool = tools.stream()
                .filter(t -> "transfer_to_agent".equals(t.getName()))
                .findFirst().orElseThrow();
        // Caller's description wins (no easy tool-type assertion since LlmChatRequest.Tool
        // strips that, but the description field is preserved by toLlmTools)
        assertThat(transferTool.getDescription()).isEqualTo("CALLER-supplied handoff");
        assertThat(tools).hasSize(2); // registry version of transfer_to_agent NOT also present
    }

    @Test
    @DisplayName("null extraTools and emptyList extraTools both behave as no-extras")
    void extraTools_nullAndEmpty_sameAsNoExtras() throws Exception {
        when(toolProviderRegistry.discoverAll(any()))
                .thenReturn(List.of(tool("nq_demo_query", "demo")));

        TurnOutcome outcomeNull = service.runAgentTurn(newCtx(), newRequest(), sink, null);
        assertThat(outcomeNull).isInstanceOf(TurnOutcome.Success.class);

        // reset for second invocation: provider.chat will be called twice total.
        // Re-verify via getAllValues.
        TurnOutcome outcomeEmpty = service.runAgentTurn(newCtx(), newRequest(), sink, Collections.emptyList());
        assertThat(outcomeEmpty).isInstanceOf(TurnOutcome.Success.class);

        ArgumentCaptor<LlmChatRequest> captor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(captor.capture(), anyString(), anyString());
        for (LlmChatRequest captured : captor.getAllValues()) {
            assertThat(captured.getTools()).extracting(LlmChatRequest.Tool::getName)
                    .containsExactly("nq_demo_query");
        }
    }
}
