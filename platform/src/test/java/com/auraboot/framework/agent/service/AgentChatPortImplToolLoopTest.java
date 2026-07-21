package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.agent.runtime.AgentExecutionState;
import com.auraboot.framework.agent.runtime.AgentRuntimeEvent;
import com.auraboot.framework.agent.runtime.AgentRuntimeStateFactory;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.DefaultAgentReducer;
import com.auraboot.framework.agent.runtime.ChatMessageTapeStore;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.runtime.PendingToolExecutionClaim;
import com.auraboot.framework.agent.runtime.PendingToolExecutionRecord;
import com.auraboot.framework.agent.dto.ChatRequest;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
    @Mock private ChatMessageTapeStore chatMessageTapeStore;
    @Mock private PendingToolStore pendingToolStore;
    @Mock private LlmProvider provider;
    @Mock private ResponseSink sink;
    @Mock private ToolLoopService toolLoopService;
    @Mock private ToolAclChecker toolAclChecker;

    private AgentChatPortImpl service;

    private static final long TENANT_ID = 1L;
    private static final long USER_ID = 100L;
    private static final String AGENT_CODE = "pcba_procurement_comparison_agent";
    private static final String SESSION_ID = "session-1";

    @BeforeEach
    void setUp() {
        service = newService(new DefaultAgentReducer());
    }

    private AgentChatPortImpl newService(DefaultAgentReducer reducer) {
        return new AgentChatPortImpl(
                dynamicDataMapper,
                providerFactory,
                toolProviderRegistry,
                groundingService,
                skillService,
                new ObjectMapper(),
                chatMessageTapeStore,
                pendingToolStore,
                toolLoopService,
                new AgentRuntimeStateFactory(),
                reducer,
                new ChatTurnRuntime(),
                new PendingToolSnapshotFactory(new AgentRuntimeStateFactory()));
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private TurnContext newTurnContext() {
        return TurnContext.legacyDefault(TENANT_ID, USER_ID, USER_ID);
    }

    private TurnContext newTurnContext(String channel) {
        return newTurnContext(channel, null);
    }

    private TurnContext newTurnContext(String channel, String profileId) {
        return new TurnContext(
                "turn-" + channel,
                TENANT_ID,
                USER_ID,
                USER_ID,
                null,
                AGENT_CODE,
                channel,
                profileId,
                null,
                null,
                null,
                null,
                java.util.Set.of(),
                null,
                null,
                java.time.Instant.now());
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
                "guardrails", "{\"provider\":\"openai\",\"evidenceFirst\":true}")));
    }

    private void stubAgentDefinitionWithTools(String toolsJson) {
        when(dynamicDataMapper.selectByQuery(contains("ab_agent_definition"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "agent_code", AGENT_CODE,
                        "name", "PCBA Procurement Advisor",
                        "status", "active",
                        "model", "test-model",
                        "system_prompt", "Compare suppliers.",
                        "tools", toolsJson,
                        "guardrails", "{\"provider\":\"openai\"}")));
        when(dynamicDataMapper.selectByQuery(contains("ab_command_definition"), anyMap()))
                .thenReturn(List.of(Map.of("model_code", "pe_procurement_comparison")));
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

    private void stubGrounding(String intent, String object) {
        when(groundingService.ground(eq(TENANT_ID), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent(intent)
                .object(object)
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
    }

    private ToolDefinition readOnlyTool() {
        return ToolDefinition.builder()
                .toolCode("nq:pe_procurement_comparison_supplier_options")
                .description("Supplier options")
                .toolType("dsl_query")
                .sourceCode("pe_procurement_comparison_supplier_options")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
    }

    private ToolDefinition writeTool() {
        return ToolDefinition.builder()
                .toolCode("cmd:pe:create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();
    }

    private ToolDefinition approvalRequiredPlatformTool() {
        return ToolDefinition.builder()
                .toolCode("platform.create_model")
                .description("Create data model")
                .toolType("platform")
                .sourceCode("platform.create_model")
                .riskLevel("L3")
                .confirmationPolicy("approval_required")
                .requiresApproval(true)
                .build();
    }

    private ToolDefinition aurabotModelCreateSkillTool() {
        return ToolDefinition.builder()
                .toolCode("aurabot:model:create")
                .toolName("model:create")
                .description("Create model through AuraBot skill")
                .toolType("AURABOT_SKILL")
                .sourceCode("model:create")
                .riskLevel("high")
                .confirmationPolicy("confirm")
                .requiresApproval(true)
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

    @SuppressWarnings("unchecked")
    private void assertRuntimeStateExtension(PendingToolSnapshot stored) {
        assertThat(stored.getExtension()).containsKey("_runtime_state");
        Object runtimeState = stored.getExtension().get("_runtime_state");
        assertThat(runtimeState).isInstanceOf(Map.class);
        Map<String, Object> snapshot = (Map<String, Object>) runtimeState;
        assertThat(snapshot)
                .containsEntry("schemaVersion", "agent-runtime-state/v1")
                .containsEntry("executionKind", "chat_turn")
                .containsEntry("agentCode", AGENT_CODE)
                .containsEntry("providerCode", "openai")
                .containsEntry("model", "test-model")
                .containsEntry("toolChoice", "required");
        assertThat((String) snapshot.get("stateHash")).hasSize(64);
        assertThat(snapshot.get("context")).isInstanceOf(Map.class);
        Map<String, Object> context = (Map<String, Object>) snapshot.get("context");
        assertThat(context)
                .containsKeys("systemPromptHash", "messagesHash", "toolsHash", "contextHash");
        assertThat(((Number) context.get("systemPromptChars")).intValue())
                .isGreaterThan("Compare suppliers.".length());
        assertThat(String.valueOf(snapshot))
                .doesNotContain("test-key")
                .doesNotContain("https://example.invalid")
                .doesNotContain("Compare suppliers.");
    }

    private void assertProviderSecretNotPersisted(PendingToolSnapshot stored) {
        assertThat(stored.getProviderCode()).isEqualTo("openai");
        assertThat(stored.getModel()).isEqualTo("test-model");
        assertThat(stored.getApiKey()).isNull();
        assertThat(stored.getBaseUrl()).isNull();
        assertThat(String.valueOf(stored))
                .doesNotContain("test-key")
                .doesNotContain("https://example.invalid");
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorFrame(Map<String, Object> result) {
        Object frame = result.get("errorFrame");
        assertThat(frame).isInstanceOf(Map.class);
        return (Map<String, Object>) frame;
    }

    private String firstToolResultPayload(LlmChatRequest request) {
        for (LlmChatRequest.Message message : request.getMessages()) {
            Object content = message.getContent();
            if (!(content instanceof List<?> blocks)) {
                continue;
            }
            for (Object block : blocks) {
                if (block instanceof LlmChatRequest.ContentBlock contentBlock
                        && "tool_result".equals(contentBlock.getType())) {
                    return String.valueOf(contentBlock.getResult());
                }
                if (block instanceof Map<?, ?> raw && "tool_result".equals(String.valueOf(raw.get("type")))) {
                    return String.valueOf(raw.get("result"));
                }
            }
        }
        return "";
    }

    // =========================================================================
    // tests
    // =========================================================================

    @Test
    @DisplayName("agent definition query failure surfaces lookup failure instead of inactive-agent result")
    void agentDefinitionQueryFailureSurfacesLookupFailure() throws Exception {
        when(dynamicDataMapper.selectByQuery(contains("ab_agent_definition"), anyMap()))
                .thenThrow(new RuntimeException("database unavailable"));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("Agent definition lookup failed")
                .contains(AGENT_CODE);
        verify(sink).onError(contains("Agent definition lookup failed"), isNull());
        verify(providerFactory, never()).resolveConfig(any(), any());
        verify(provider, never()).chat(any(), any(), any());
    }

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
        verify(chatMessageTapeStore).storeConversationMessages(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("stub-routed provider config -> named-agent turn uses stub provider bean")
    void stubRoutedConfigUsesStubProviderBean() throws Exception {
        stubAgentDefinition();
        stubGrounding();
        when(providerFactory.resolveConfig(eq(TENANT_ID), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("stub")
                        .apiKey("stub_key_for_no_llm_paths")
                        .baseUrl("stub://local")
                        .defaultModel("stub-model")
                        .maxTokens(4096)
                        .build());
        when(providerFactory.getProvider("stub")).thenReturn(provider);
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());
        when(provider.chat(any(LlmChatRequest.class), eq("stub_key_for_no_llm_paths"), eq("stub://local")))
                .thenReturn(endTurnResponse("[stub response]"));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(providerFactory, times(1)).getProvider("stub");
        verify(providerFactory, never()).getProvider("openai");
        verify(sink).onDone("[stub response]", null);
    }

    @Test
    @DisplayName("tool_use round emits onToolStart/onToolResult and feeds the tool result back to the LLM")
    void toolUseRoundExecutesAndFeedsResultBack() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        Map<String, Object> toolInput = Map.of("productId", "P-100");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(toolInput),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"records\":[{\"supplier\":\"Acme PCB\"}]},\"durationMs\":12}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-1",
                        "nq_pe_procurement_comparison_supplier_options",
                        toolInput))
                .thenReturn(endTurnResponse("Acme PCB is available."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        // Read-only tool was auto-executed and signaled to the sink.
        verify(sink).onToolStart(eq("toolu-1"),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(toolInput));
        verify(toolLoopService).executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(toolInput),
                anyList(),
                isNull());
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sink).onToolResult(eq("toolu-1"), resultCaptor.capture(), eq(true));
        assertThat(resultCaptor.getValue())
                .containsEntry("success", true)
                .containsEntry("data", Map.of("records", List.of(Map.of("supplier", "Acme PCB"))));
        assertThat(((Number) resultCaptor.getValue().get("durationMs")).longValue()).isEqualTo(12L);
        // Two LLM round trips: tool_use then end_turn.
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getAllValues().get(0).getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_pe_procurement_comparison_supplier_options");
        assertThat(requestCaptor.getAllValues().get(0).getToolChoice()).isEqualTo("required");
        LlmChatRequest secondRound = requestCaptor.getAllValues().get(1);
        assertThat(secondRound.getToolChoice()).isNull();
        // The second round must carry the assistant tool_use + user tool_result blocks.
        assertThat(secondRound.getMessages()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(String.valueOf(secondRound.getMessages()))
                .contains("tool_result")
                .contains("Acme PCB")
                .doesNotContain("Tool executed");
        // No confirm_required on a read-only tool.
        verify(sink, never()).onConfirmRequired(any(), any(), any(), anyMap(), any());
    }

    @Test
    @DisplayName("tool catalog applies channel ACL before provider sees tools")
    void toolCatalogAppliesChannelAclBeforeProviderSeesTools() throws Exception {
        when(dynamicDataMapper.selectByQuery(contains("ab_agent_definition"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "agent_code", AGENT_CODE,
                        "name", "PCBA Procurement Advisor",
                        "status", "active",
                        "model", "test-model",
                        "system_prompt", "Compare suppliers.",
                        "guardrails", "{\"provider\":\"openai\"}")));
        stubProvider();
        stubGrounding();
        ReflectionTestUtils.setField(service, "toolAclChecker", toolAclChecker);
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool(), writeTool()));
        when(toolAclChecker.check(eq(TENANT_ID), eq(AGENT_CODE), isNull(), eq("interactive"),
                eq("nq_pe_procurement_comparison_supplier_options")))
                .thenReturn(ToolAclChecker.Decision.builder()
                        .allowed(true)
                        .reason("allow_read")
                        .build());
        when(toolAclChecker.check(eq(TENANT_ID), eq(AGENT_CODE), isNull(), eq("interactive"),
                eq("cmd_pe_create_procurement_comparison_draft")))
                .thenReturn(ToolAclChecker.Decision.builder()
                        .allowed(false)
                        .reason("channel_write_denied")
                        .build());
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(endTurnResponse("Only read tools are available."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_pe_procurement_comparison_supplier_options");
        verify(toolLoopService, never()).executeToolCall(any(), any(), any(), any(), any(), anyMap(), anyList(), any());
    }

    @Test
    @DisplayName("tool catalog ACL uses TurnContext channel instead of a null fallback")
    void toolCatalogAclUsesTurnContextChannel() throws Exception {
        when(dynamicDataMapper.selectByQuery(contains("ab_agent_definition"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "agent_code", AGENT_CODE,
                        "name", "PCBA Procurement Advisor",
                        "status", "active",
                        "model", "test-model",
                        "system_prompt", "Compare suppliers.",
                        "guardrails", "{\"provider\":\"openai\"}")));
        stubProvider();
        stubGrounding();
        ReflectionTestUtils.setField(service, "toolAclChecker", toolAclChecker);
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolAclChecker.check(eq(TENANT_ID), eq(AGENT_CODE), eq("im_group"), eq("interactive"),
                eq("nq_pe_procurement_comparison_supplier_options")))
                .thenReturn(ToolAclChecker.Decision.builder()
                        .allowed(true)
                        .reason("im_group_read")
                        .build());
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(endTurnResponse("Only group-safe read tools are available."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext("im_group"), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_pe_procurement_comparison_supplier_options");
        verify(toolAclChecker).check(eq(TENANT_ID), eq(AGENT_CODE), eq("im_group"), eq("interactive"),
                eq("nq_pe_procurement_comparison_supplier_options"));
    }

    @Test
    @DisplayName("tool catalog ACL uses TurnContext profile id before agent-code fallback")
    void toolCatalogAclUsesTurnContextProfileId() throws Exception {
        when(dynamicDataMapper.selectByQuery(contains("ab_agent_definition"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "agent_code", AGENT_CODE,
                        "name", "PCBA Procurement Advisor",
                        "status", "active",
                        "model", "test-model",
                        "system_prompt", "Compare suppliers.",
                        "guardrails", "{\"provider\":\"openai\"}")));
        stubProvider();
        stubGrounding();
        ReflectionTestUtils.setField(service, "toolAclChecker", toolAclChecker);
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolAclChecker.check(eq(TENANT_ID), eq("profile-abc"), eq("im_group"), eq("interactive"),
                eq("nq_pe_procurement_comparison_supplier_options")))
                .thenReturn(ToolAclChecker.Decision.builder()
                        .allowed(true)
                        .reason("profile_read")
                        .build());
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(endTurnResponse("Only profile-safe read tools are available."));

        TurnOutcome outcome = service.runAgentTurn(
                newTurnContext("im_group", "profile-abc"), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_pe_procurement_comparison_supplier_options");
        verify(toolAclChecker).check(eq(TENANT_ID), eq("profile-abc"), eq("im_group"), eq("interactive"),
                eq("nq_pe_procurement_comparison_supplier_options"));
    }

    @Test
    @DisplayName("OpenAI-compatible first tool round fails with diagnostic when provider ignores required tool_choice")
    void requiredToolChoiceIgnoredByProviderFailsDiagnostic() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(endTurnResponse("I can compare suppliers without data."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        TurnOutcome.Failed failed = (TurnOutcome.Failed) outcome;
        assertThat(failed.errorMessage())
                .contains("required tool call")
                .contains("openai")
                .contains("nq_pe_procurement_comparison_supplier_options");
        verify(sink).onError(contains("required tool call"), eq(null));
        verify(sink, never()).onTextChunk(anyString());
        verify(sink, never()).onDone(anyString(), any());
        verify(toolLoopService, never()).executeToolCall(any(), any(), any(), any(), any(), anyMap(), anyList(), any());

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getValue().getToolChoice()).isEqualTo("required");
    }

    @Test
    @DisplayName("explicit named query tool resolves by exact query code when it does not share a model-code prefix")
    void explicitNamedQueryToolResolvesByExactCode() throws Exception {
        when(dynamicDataMapper.selectByQuery(contains("ab_agent_definition"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "agent_code", AGENT_CODE,
                        "name", "PCBA Quality Analyst",
                        "status", "active",
                        "model", "test-model",
                        "system_prompt", "Analyze quality defects.",
                        "tools", "[\"nq:qc_quality_capa_context\"]",
                        "guardrails", "{\"provider\":\"openai\"}")));
        when(dynamicDataMapper.selectByQuery(contains("ab_meta_model"), anyMap()))
                .thenReturn(List.of());
        when(dynamicDataMapper.selectByQuery(contains("ab_named_query"), anyMap()))
                .thenReturn(List.of(Map.of(
                        "code", "qc_quality_capa_context",
                        "title", "PCBA CAPA Context",
                        "purpose", "Read-only defect context query for CAPA draft preparation.",
                        "from_sql", "SELECT * FROM mt_qc_defect_record WHERE tenant_id = #{params.tenantId} "
                                + "AND pid = #{params.sourceRecordPid}")));
        stubProvider();
        stubGrounding("query", "webhook");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());
        Map<String, Object> input = Map.of("sourceRecordPid", "DEFECT-1");
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_qc_quality_capa_context"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"records\":[{\"source_record_pid\":\"DEFECT-1\"}]},\"durationMs\":7}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse("toolu-nq", "nq_qc_quality_capa_context", input))
                .thenReturn(endTurnResponse("CAPA context loaded."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Get CAPA context"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(toolLoopService).executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_qc_quality_capa_context"),
                eq(input),
                anyList(),
                isNull());
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getAllValues().get(0).getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .contains("nq_qc_quality_capa_context");
        LlmChatRequest.Tool namedQueryTool = requestCaptor.getAllValues().get(0).getTools().stream()
                .filter(tool -> "nq_qc_quality_capa_context".equals(tool.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(namedQueryTool.getInputSchema())).contains("sourceRecordPid");
    }

    @Test
    @DisplayName("unknown tool_use fails closed and is fed back without executing any tool")
    void unknownToolUseFailsClosedWithoutExecuting() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-unknown",
                        "platform.create_model",
                        Map.of("description", "Create a Customer table")))
                .thenReturn(endTurnResponse("I cannot execute that tool."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Create a customer model"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(toolLoopService, never()).executeToolCall(any(), any(), any(), any(), any(), anyMap(), anyList(), any());
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sink).onToolResult(eq("toolu-unknown"), resultCaptor.capture(), eq(false));
        assertThat(resultCaptor.getValue())
                .containsEntry("success", false)
                .containsKey("errorFrame");
        Map<String, Object> errorFrame = errorFrame(resultCaptor.getValue());
        assertThat(errorFrame)
                .containsEntry("category", "validation")
                .containsEntry("toolName", "platform.create_model")
                .containsEntry("errorClass", "UnknownTool")
                .containsEntry("retryable", true)
                .containsEntry("userSafeMessage", "The model requested an unavailable tool.");
        assertThat((String) errorFrame.get("argsHash")).hasSize(64);
        assertThat((String) errorFrame.get("modelRecoveryHint"))
                .contains("nq_pe_procurement_comparison_supplier_options");
        assertThat(String.valueOf(resultCaptor.getValue()))
                .doesNotContain("Create a Customer table");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(String.valueOf(requestCaptor.getAllValues().get(1).getMessages()))
                .contains("tool_result")
                .contains("errorFrame")
                .contains("validation");
    }

    @Test
    @DisplayName("tool execution failure feeds a retryable compact error frame back to the model")
    void toolExecutionFailureFeedsRetryableErrorFrameBackToModel() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        Map<String, Object> input = Map.of("productId", "P-100", "note", "secret-note");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(input),
                anyList(),
                isNull()))
                .thenThrow(new IllegalStateException("database password=secret-db exploded"));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-fail",
                        "nq_pe_procurement_comparison_supplier_options",
                        input))
                .thenReturn(endTurnResponse("I cannot load supplier options right now."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sink).onToolResult(eq("toolu-fail"), resultCaptor.capture(), eq(false));
        Map<String, Object> result = resultCaptor.getValue();
        assertThat(result)
                .containsEntry("success", false)
                .containsKey("errorFrame");
        Map<String, Object> errorFrame = errorFrame(result);
        assertThat(errorFrame)
                .containsEntry("category", "tool")
                .containsEntry("toolName", "nq_pe_procurement_comparison_supplier_options")
                .containsEntry("errorClass", "IllegalStateException")
                .containsEntry("retryable", true)
                .containsEntry("userSafeMessage", "Tool execution failed.");
        assertThat((String) errorFrame.get("argsHash")).hasSize(64);
        assertThat(String.valueOf(result))
                .doesNotContain("secret-db")
                .doesNotContain("secret-note");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        String toolResultPayload = firstToolResultPayload(requestCaptor.getAllValues().get(1));
        assertThat(toolResultPayload)
                .contains("errorFrame")
                .contains("Tool execution failed.")
                .doesNotContain("secret-db")
                .doesNotContain("secret-note");
    }

    @Test
    @DisplayName("tool returned Error string is compacted before sink and model retry")
    void returnedErrorStringFeedsCompactErrorFrameBackToModel() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        Map<String, Object> input = Map.of("productId", "P-100", "note", "secret-note");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("Error: database password=secret-db exploded apiKey=sk-secret");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-returned-error",
                        "nq_pe_procurement_comparison_supplier_options",
                        input))
                .thenReturn(endTurnResponse("I cannot load supplier options right now."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sink).onToolResult(eq("toolu-returned-error"), resultCaptor.capture(), eq(false));
        Map<String, Object> result = resultCaptor.getValue();
        assertThat(result)
                .containsEntry("success", false)
                .containsEntry("error", "Tool execution failed.")
                .containsKey("errorFrame");
        Map<String, Object> errorFrame = errorFrame(result);
        assertThat(errorFrame)
                .containsEntry("category", "tool")
                .containsEntry("toolName", "nq_pe_procurement_comparison_supplier_options")
                .containsEntry("errorClass", "ToolReturnedError")
                .containsEntry("retryable", true)
                .containsEntry("userSafeMessage", "Tool execution failed.");
        assertThat(String.valueOf(result))
                .doesNotContain("secret-db")
                .doesNotContain("sk-secret")
                .doesNotContain("secret-note");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        String toolResultPayload = firstToolResultPayload(requestCaptor.getAllValues().get(1));
        assertThat(toolResultPayload)
                .contains("errorFrame")
                .contains("Tool execution failed.")
                .doesNotContain("secret-db")
                .doesNotContain("sk-secret")
                .doesNotContain("secret-note")
                .doesNotContain("database password");
    }

    @Test
    @DisplayName("tool returned JSON error is compacted before sink and model retry")
    void returnedJsonErrorFeedsCompactErrorFrameBackToModel() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        Map<String, Object> input = Map.of("productId", "P-100", "note", "secret-note");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":false,\"error\":\"database password=secret-db exploded\",\"durationMs\":12}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-json-error",
                        "nq_pe_procurement_comparison_supplier_options",
                        input))
                .thenReturn(endTurnResponse("I cannot load supplier options right now."));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers for P-100"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> resultCaptor = ArgumentCaptor.forClass(Map.class);
        verify(sink).onToolResult(eq("toolu-json-error"), resultCaptor.capture(), eq(false));
        Map<String, Object> result = resultCaptor.getValue();
        assertThat(result)
                .containsEntry("success", false)
                .containsEntry("error", "Tool execution failed.")
                .containsKey("errorFrame");
        assertThat(((Number) result.get("durationMs")).longValue()).isEqualTo(12L);
        assertThat(errorFrame(result))
                .containsEntry("errorClass", "ToolReturnedError")
                .containsEntry("userSafeMessage", "Tool execution failed.");
        assertThat(String.valueOf(result))
                .doesNotContain("secret-db")
                .doesNotContain("secret-note")
                .doesNotContain("database password");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(firstToolResultPayload(requestCaptor.getAllValues().get(1)))
                .contains("errorFrame")
                .doesNotContain("secret-db")
                .doesNotContain("secret-note")
                .doesNotContain("database password");
    }

    @Test
    @DisplayName("approval_required tool suspends with approval pid and never executes registry directly")
    void approvalRequiredToolSuspendsWithApprovalPid() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        TurnContext ctx = newTurnContext();
        Map<String, Object> input = Map.of("description", "Create a Customer table");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(approvalRequiredPlatformTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("platform_create_model"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"approvalPid\":\"approval-1\",\"message\":\"Approval required\"}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse("toolu-approval", "platform_create_model", input));

        TurnOutcome outcome = service.runAgentTurn(ctx, newRequest("Create a customer model"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        TurnOutcome.PendingConfirmation pending = (TurnOutcome.PendingConfirmation) outcome;
        assertThat(pending.pendingTurnId()).isEqualTo("approval-1");
        assertThat(pending.pendingToolId()).isEqualTo("approval-1");
        verify(sink).onToolResult(eq("toolu-approval"), anyMap(), eq(false));
        verify(sink).onConfirmRequired(
                eq("approval-1"),
                eq("agent_approval_gate"),
                any(String.class),
                eq(Map.of("toolName", "platform_create_model", "input", input)),
                eq("approval-1"));
        ArgumentCaptor<PendingToolSnapshot> pendingCaptor =
                ArgumentCaptor.forClass(PendingToolSnapshot.class);
        verify(pendingToolStore).storePending(eq("approval-1"), pendingCaptor.capture());
        PendingToolSnapshot pendingTool = pendingCaptor.getValue();
        assertThat(pendingTool.getTurnId()).isEqualTo(ctx.turnId());
        assertThat(pendingTool.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(pendingTool.getUserId()).isEqualTo(USER_ID);
        assertThat(pendingTool.getAgentCode()).isEqualTo(AGENT_CODE);
        assertThat(pendingTool.getToolName()).isEqualTo("platform_create_model");
        assertThat(pendingTool.getInput()).isEqualTo(input);
        assertThat(pendingTool.getRunPid()).isEqualTo(ctx.turnId());
        assertThat(pendingTool.getTaskPid()).isEqualTo(ctx.taskPid());
        assertThat(pendingTool.getAgentToolDefinitions()).hasSize(1);
        assertThat(pendingTool.getAgentToolDefinitions().get(0).isRequiresApproval()).isTrue();
        assertThat(pendingTool.getAgentToolDefinitions().get(0).getSourceCode()).isEqualTo("platform.create_model");
        assertProviderSecretNotPersisted(pendingTool);
        assertRuntimeStateExtension(pendingTool);
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("approved pending chat tool executes once through ToolLoopService with approval flag cleared")
    void executeApprovedPendingToolRunsThroughToolLoopWithoutReapproval() throws Exception {
        AgentToolDefinition toolDef = AgentToolDefinition.builder()
                .name("platform.create_model")
                .description("Create data model")
                .toolType("platform")
                .sourceCode("platform.create_model")
                .riskLevel("L3")
                .confirmationPolicy("approval_required")
                .requiresApproval(true)
                .build();
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId("turn-1")
                .tenantId(TENANT_ID)
                .userId(USER_ID)
                .agentCode(AGENT_CODE)
                .runPid("turn-1")
                .taskPid("task-1")
                .toolId("toolu-approval")
                .toolName("platform.create_model")
                .input(Map.of("description", "Create a Customer table"))
                .agentToolDefinitions(List.of(toolDef))
                .build();
        when(pendingToolStore.consumePendingForOwner("approval-1", TENANT_ID, null)).thenReturn(pending);
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                eq("turn-1"),
                eq("task-1"),
                eq(AGENT_CODE),
                eq("platform.create_model"),
                eq(pending.getInput()),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"model-1\"},\"durationMs\":5}");

        Map<String, Object> result = service.executeApprovedPendingTool(TENANT_ID, "approval-1");

        assertThat(result)
                .containsEntry("handled", true)
                .containsEntry("success", true)
                .containsEntry("approvalPid", "approval-1")
                .containsEntry("toolName", "platform.create_model");
        assertThat(result.get("result")).isEqualTo(Map.of(
                "success", true,
                "data", Map.of("pid", "model-1"),
                "durationMs", 5));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> defsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(
                eq(TENANT_ID),
                eq("turn-1"),
                eq("task-1"),
                eq(AGENT_CODE),
                eq("platform.create_model"),
                eq(pending.getInput()),
                defsCaptor.capture(),
                isNull());
        assertThat(defsCaptor.getValue()).hasSize(1);
        assertThat(defsCaptor.getValue().get(0).isRequiresApproval()).isFalse();
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
    }

    @Test
    @DisplayName("approved pending chat tool replays completed execution record without re-executing")
    void executeApprovedPendingToolReplaysCompletedExecutionRecord() throws Exception {
        AgentToolDefinition toolDef = AgentToolDefinition.builder()
                .name("platform.create_model")
                .description("Create data model")
                .toolType("platform")
                .sourceCode("platform.create_model")
                .riskLevel("L3")
                .confirmationPolicy("approval_required")
                .requiresApproval(true)
                .build();
        PendingToolSnapshot pending = PendingToolSnapshot.builder()
                .turnId("turn-1")
                .tenantId(TENANT_ID)
                .userId(USER_ID)
                .agentCode(AGENT_CODE)
                .runPid("turn-1")
                .taskPid("task-1")
                .toolId("toolu-approval")
                .toolName("platform.create_model")
                .input(Map.of("description", "Create a Customer table"))
                .idempotencyKey("idem-approval-1")
                .agentToolDefinitions(List.of(toolDef))
                .build();
        Map<String, Object> completedResult = Map.of(
                "success", true,
                "data", Map.of("pid", "model-1"));
        when(pendingToolStore.consumePendingForOwner("approval-1", TENANT_ID, null)).thenReturn(pending);
        when(pendingToolStore.claimExecution(pending)).thenReturn(PendingToolExecutionClaim.replay(
                PendingToolExecutionRecord.succeeded("idem-approval-1", completedResult)));

        Map<String, Object> result = service.executeApprovedPendingTool(TENANT_ID, "approval-1");

        assertThat(result)
                .containsEntry("handled", true)
                .containsEntry("success", true)
                .containsEntry("replayed", true)
                .containsEntry("approvalPid", "approval-1")
                .containsEntry("toolName", "platform.create_model");
        assertThat(result.get("result")).isEqualTo(completedResult);
        verify(toolLoopService, never()).executeToolCall(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("approval_required without approval pid fails the turn instead of continuing")
    void approvalRequiredWithoutPidFailsTurn() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        Map<String, Object> input = Map.of("description", "Create a Customer table");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(approvalRequiredPlatformTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("platform_create_model"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"error\":\"No matching approval policy\"}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse("toolu-approval", "platform_create_model", input));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Create a customer model"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        TurnOutcome.Failed failed = (TurnOutcome.Failed) outcome;
        assertThat(failed.errorMessage()).contains("No matching approval policy");
        verify(sink).onError(contains("No matching approval policy"), eq(null));
        verify(provider, times(1)).chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid"));
        verify(toolProviderRegistry, never()).execute(any(), anyString(), anyMap());
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
        ArgumentCaptor<PendingToolSnapshot> pendingCaptor =
                ArgumentCaptor.forClass(PendingToolSnapshot.class);
        verify(pendingToolStore).storePending(eq(ctx.turnId()), pendingCaptor.capture());
        PendingToolSnapshot stored = pendingCaptor.getValue();
        assertThat(stored.getTurnId()).isEqualTo(ctx.turnId());
        assertThat(stored.getToolId()).isEqualTo("toolu-write");
        assertThat(stored.getToolName()).isEqualTo("cmd_pe_create_procurement_comparison_draft");
        assertThat(stored.getAgentCode()).isEqualTo(AGENT_CODE);
        assertThat(stored.getProviderCode()).isEqualTo("openai");
        assertThat(stored.getModel()).isEqualTo("test-model");
        assertThat(stored.getSessionId()).isEqualTo(SESSION_ID);
        assertThat(stored.getMessages()).isNotEmpty();
        assertThat(stored.getToolVersion()).isEqualTo("v1");
        assertThat(stored.getArgsHash()).hasSize(64);
        assertThat(stored.getIdempotencyKey()).isEqualTo(
                "cmd_pe_create_procurement_comparison_draft:v1:" + stored.getArgsHash());
        assertThat(stored.getPolicyDecisionReason()).isEqualTo("user_confirmation_required");
        // #1386 made the approval sentence show the command as the product names it
        // (namespace + colon) instead of the LLM-safe alias; this expectation was left
        // on the pre-#1386 alias text and has been red on main since.
        assertThat(stored.getPreview()).isEqualTo(
                "Execute pe:create_procurement_comparison_draft with 1 argument(s).");
        assertProviderSecretNotPersisted(stored);
        assertRuntimeStateExtension(stored);
        verify(chatMessageTapeStore).storeConversationMessages(eq(SESSION_ID), any());
    }

    @Test
    @DisplayName("requiresConfirmation path records reducer events for model response, tool use, and suspension")
    void confirmationToolRecordsReducerEvents() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        DefaultAgentReducer reducer = spy(new DefaultAgentReducer());
        service = newService(reducer);
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(writeTool()));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse(
                        "toolu-write",
                        "cmd_pe_create_procurement_comparison_draft",
                        Map.of("productId", "P-100")));

        service.runAgentTurn(newTurnContext(), newRequest("Create draft for P-100"), sink);

        ArgumentCaptor<AgentRuntimeEvent> eventCaptor = ArgumentCaptor.forClass(AgentRuntimeEvent.class);
        verify(reducer, times(3)).reduce(any(AgentExecutionState.class), eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
                .extracting(AgentRuntimeEvent::type)
                .containsExactly(
                        AgentRuntimeEvent.MODEL_RESPONSE_RECEIVED,
                        AgentRuntimeEvent.TOOL_USE_REQUESTED,
                        AgentRuntimeEvent.CONFIRMATION_REQUIRED);
    }

    @Test
    @DisplayName("explicit agent tools remain available when grounding points at the wrong model")
    void explicitAgentToolsSurviveWrongGrounding() throws Exception {
        stubAgentDefinitionWithTools("[\"cmd:pe:create_procurement_comparison_draft\"]");
        stubProvider();
        stubGrounding("create", "inv_lot_transaction");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenAnswer(invocation -> {
            ToolDiscoveryContext ctx = invocation.getArgument(0);
            if ("pe_procurement_comparison".equals(ctx.getModelHint())) {
                return List.of(writeTool());
            }
            return List.of();
        });
        Map<String, Object> input = Map.of("productId", "P-100");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse("toolu-write", "cmd_pe_create_procurement_comparison_draft", input));

        TurnContext ctx = newTurnContext();
        TurnOutcome outcome = service.runAgentTurn(ctx, newRequest("Create a draft for the selected supplier"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        verify(sink).onConfirmRequired(
                eq("toolu-write"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                any(String.class),
                eq(input),
                eq(ctx.turnId()));

        ArgumentCaptor<ToolDiscoveryContext> discoveryCaptor =
                ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        verify(toolProviderRegistry, times(2)).discoverAll(discoveryCaptor.capture());
        assertThat(discoveryCaptor.getAllValues())
                .extracting(ToolDiscoveryContext::getModelHint)
                .containsExactly("pe_procurement_comparison", "inv_lot_transaction");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .contains("cmd_pe_create_procurement_comparison_draft");
    }

    @Test
    @DisplayName("AuraBot skill preview creates pending entry with preview token through ToolLoopService")
    void aurabotSkillPreviewCreatesPendingWithPreviewToken() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        Map<String, Object> input = Map.of("code", "crm_customer");
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(aurabotModelCreateSkillTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("aurabot_model_create"),
                eq(input),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":false,\"approvalRequired\":true,\"skillName\":\"model:create\","
                        + "\"riskLevel\":\"high\",\"preview\":{\"modelCode\":\"crm_customer\"},"
                        + "\"previewToken\":\"preview-1\"}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(toolUseResponse("toolu-skill", "aurabot_model_create", input));

        TurnContext ctx = newTurnContext();
        TurnOutcome outcome = service.runAgentTurn(ctx, newRequest("Create the customer model"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        TurnOutcome.PendingConfirmation pending = (TurnOutcome.PendingConfirmation) outcome;
        assertThat(pending.pendingTurnId()).isEqualTo(ctx.turnId());
        assertThat(pending.pendingToolId()).isEqualTo("toolu-skill");
        verify(toolLoopService).executeToolCall(
                eq(TENANT_ID),
                eq(ctx.turnId()),
                eq(ctx.taskPid()),
                eq(AGENT_CODE),
                eq("aurabot_model_create"),
                eq(input),
                anyList(),
                isNull());
        verify(sink).onToolStart("toolu-skill", "aurabot_model_create", input);
        verify(sink).onConfirmRequired(
                eq("toolu-skill"),
                eq("aurabot_model_create"),
                any(String.class),
                eq(input),
                eq(ctx.turnId()));
        verify(sink).onDone("", null);

        ArgumentCaptor<ToolDiscoveryContext> discoveryCaptor =
                ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        verify(toolProviderRegistry).discoverAll(discoveryCaptor.capture());
        assertThat(discoveryCaptor.getValue().getUserId()).isEqualTo(USER_ID);

        ArgumentCaptor<PendingToolSnapshot> pendingCaptor =
                ArgumentCaptor.forClass(PendingToolSnapshot.class);
        verify(pendingToolStore).storePending(eq(ctx.turnId()), pendingCaptor.capture());
        PendingToolSnapshot stored = pendingCaptor.getValue();
        assertThat(stored.getToolName()).isEqualTo("aurabot_model_create");
        assertThat(stored.getAgentToolDefinitions()).hasSize(1);
        assertThat(stored.getAgentToolDefinitions().get(0).getToolType()).isEqualTo("AURABOT_SKILL");
        assertThat(stored.getAgentToolDefinitions().get(0).getSourceCode()).isEqualTo("model:create");
        assertThat(stored.getExtension())
                .containsEntry("_aurabot_skill", true)
                .containsEntry("previewToken", "preview-1")
                .containsEntry("riskLevel", "high");
        assertThat(stored.getExtension().get("preview")).isEqualTo(Map.of("modelCode", "crm_customer"));
        assertProviderSecretNotPersisted(stored);
        assertRuntimeStateExtension(stored);
    }

    @Test
    @DisplayName("LLM call failure surfaces TurnOutcome.Failed and emits sink.onError")
    void llmFailureYieldsFailedOutcome() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        DefaultAgentReducer reducer = spy(new DefaultAgentReducer());
        service = newService(reducer);
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenThrow(new IllegalArgumentException("Invalid scheme [stub] apiKey=sk-secret"));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Hello"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        TurnOutcome.Failed failed = (TurnOutcome.Failed) outcome;
        assertThat(failed.errorMessage())
                .contains("LLM provider request failed.")
                .doesNotContain("sk-secret")
                .doesNotContain("Invalid scheme [stub]");
        verify(sink).onError(contains("LLM provider request failed."), eq(null));
        // No sink.onDone on failure.
        verify(sink, never()).onDone(any(String.class), any());
        ArgumentCaptor<AgentRuntimeEvent> eventCaptor = ArgumentCaptor.forClass(AgentRuntimeEvent.class);
        verify(reducer).reduce(any(AgentExecutionState.class), eventCaptor.capture());
        AgentRuntimeEvent failedEvent = eventCaptor.getValue();
        assertThat(failedEvent.type()).isEqualTo(AgentRuntimeEvent.TURN_FAILED);
        assertThat(String.valueOf(failedEvent.payload()))
                .contains("provider")
                .contains("IllegalArgumentException")
                .doesNotContain("sk-secret")
                .doesNotContain("Invalid scheme [stub]");
    }

    @Test
    @DisplayName("tool discovery failure fails closed before calling the LLM")
    void toolDiscoveryFailureFailsClosedBeforeLlmCall() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenThrow(new IllegalStateException("registry unavailable"));

        TurnOutcome outcome = service.runAgentTurn(newTurnContext(), newRequest("Compare suppliers"), sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("Tool discovery failed")
                .contains("registry unavailable");
        verify(provider, never()).chat(any(LlmChatRequest.class), anyString(), anyString());
        verify(sink).onError(contains("Tool discovery failed"), eq(null));
    }

    @Test
    @DisplayName("loop exceeding MAX_TOOL_ROUNDS yields TurnOutcome.Failed with the cap message")
    void loopExceedsMaxRoundsYieldsFailed() throws Exception {
        stubAgentDefinition();
        stubProvider();
        stubGrounding();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class)))
                .thenReturn(List.of(readOnlyTool()));
        when(toolLoopService.executeToolCall(
                eq(TENANT_ID),
                anyString(),
                isNull(),
                eq(AGENT_CODE),
                eq("nq_pe_procurement_comparison_supplier_options"),
                anyMap(),
                anyList(),
                isNull()))
                .thenReturn("{\"success\":true,\"data\":{\"records\":[]},\"durationMs\":1}");
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
        when(chatMessageTapeStore.loadConversationMessages(SESSION_ID)).thenReturn(storedTape);

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
