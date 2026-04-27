package com.auraboot.framework.agent.service;

import com.auraboot.framework.agent.dto.BusinessIntentFrame;
import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.ConfidenceScore;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.ToolDefinition;
import com.auraboot.framework.agent.provider.ToolDiscoveryContext;
import com.auraboot.framework.agent.provider.ToolProviderRegistry;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.meta.mapper.DynamicDataMapper;
import com.auraboot.framework.agent.trace.TraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.auraboot.framework.aurabot.service.ChatSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentChatPortImpl tool loop")
class AgentChatPortImplToolLoopTest {

    @Mock private DynamicDataMapper dynamicDataMapper;
    @Mock private LlmProviderFactory providerFactory;
    @Mock private ToolProviderRegistry toolProviderRegistry;
    @Mock private GroundingService groundingService;
    @Mock private AgentSkillService skillService;
    @Mock private ToolLoopService toolLoopService;
    @Mock private ChatSessionStore chatSessionStore;
    @Mock private LlmProvider provider;

    private AgentChatPortImpl service;

    @BeforeEach
    void setup() {
        service = new AgentChatPortImpl(
                dynamicDataMapper,
                providerFactory,
                toolProviderRegistry,
                groundingService,
                skillService,
                new ObjectMapper(),
                toolLoopService,
                chatSessionStore);
    }

    @Test
    @DisplayName("custom agent tool_use executes through ToolLoopService and feeds result back to the LLM")
    void customAgentToolUseExecutesThroughToolLoopService() throws Exception {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", "pcba_procurement_comparison_agent",
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}")));
        when(providerFactory.resolveConfig(1L, "openai")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("test-model")
                .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(groundingService.ground(eq(1L), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("compare")
                .object("pe_procurement_comparison")
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        ToolDefinition queryTool = ToolDefinition.builder()
                .toolCode("nq:pe_procurement_comparison_supplier_options")
                .description("Supplier options")
                .toolType("dsl_query")
                .sourceCode("pe_procurement_comparison_supplier_options")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of(queryTool));
        when(toolLoopService.executeToolCall(
                eq(1L),
                any(),
                any(),
                eq("pcba_procurement_comparison_agent"),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(Map.of("productId", "P-100")),
                any(),
                isNull(TraceContext.class)))
                .thenReturn("{\"total\":1,\"records\":[{\"supplier\":\"Acme PCB\"}]}");

        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu-1")
                                .name("nq_pe_procurement_comparison_supplier_options")
                                .input(Map.of("productId", "P-100"))
                                .build()))
                        .build())
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Acme PCB is available.")
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();
        ChatRequest request = new ChatRequest();
        request.setMessage("Compare suppliers for P-100");
        request.setSessionId("session-1");
        request.setAgentCode("pcba_procurement_comparison_agent");

        service.streamAgentChat(1L, "pcba_procurement_comparison_agent", request, emitter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolDefinitionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(
                eq(1L),
                any(),
                any(),
                eq("pcba_procurement_comparison_agent"),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(Map.of("productId", "P-100")),
                toolDefinitionsCaptor.capture(),
                isNull(TraceContext.class));
        assertThat(toolDefinitionsCaptor.getValue()).singleElement().satisfies(tool -> {
            assertThat(tool.getName()).isEqualTo("nq_pe_procurement_comparison_supplier_options");
            assertThat(tool.getSourceCode()).isEqualTo("pe_procurement_comparison_supplier_options");
            assertThat(tool.getToolType()).isEqualTo("dsl_query");
        });

        ArgumentCaptor<LlmChatRequest> llmRequestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider, times(2)).chat(llmRequestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        LlmChatRequest firstRound = llmRequestCaptor.getAllValues().get(0);
        assertThat(firstRound.getTools()).singleElement().satisfies(tool -> {
            assertThat(tool.getName()).isEqualTo("nq_pe_procurement_comparison_supplier_options");
            assertThat(tool.getName()).doesNotContain(":");
        });
        LlmChatRequest secondRound = llmRequestCaptor.getAllValues().get(1);
        Object toolResultContent = secondRound.getMessages().get(2).getContent();
        assertThat(String.valueOf(toolResultContent)).contains("Acme PCB");
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("tool_start");
            assertThat(event.payload).contains("toolu-1", "nq_pe_procurement_comparison_supplier_options");
        });
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("tool_result");
            assertThat(event.payload).contains("Acme PCB");
        });
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("done");
            assertThat(event.payload).contains("Acme PCB is available.");
        });
    }

    @Test
    @DisplayName("duplicate read tool calls are skipped instead of re-executed until max rounds")
    void duplicateReadToolCallIsSkipped() throws Exception {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", "pcba_procurement_comparison_agent",
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}")));
        when(providerFactory.resolveConfig(1L, "openai")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("test-model")
                .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(groundingService.ground(eq(1L), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("compare")
                .object("pe_procurement_comparison")
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        ToolDefinition queryTool = ToolDefinition.builder()
                .toolCode("nq:pe_procurement_comparison_supplier_options")
                .description("Supplier options")
                .toolType("dsl_query")
                .sourceCode("pe_procurement_comparison_supplier_options")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of(queryTool));
        when(toolLoopService.executeToolCall(
                eq(1L),
                any(),
                any(),
                eq("pcba_procurement_comparison_agent"),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(Map.of("productId", "P-100")),
                any(),
                isNull(TraceContext.class)))
                .thenReturn("{\"total\":1,\"records\":[{\"supplier\":\"Acme PCB\"}]}");

        LlmChatResponse duplicateQuery = LlmChatResponse.builder()
                .stopReason("tool_use")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("toolu-1")
                        .name("nq_pe_procurement_comparison_supplier_options")
                        .input(Map.of("productId", "P-100"))
                        .build()))
                .build();
        LlmChatResponse repeatedDuplicateQuery = LlmChatResponse.builder()
                .stopReason("tool_use")
                .content(List.of(LlmChatResponse.ContentBlock.builder()
                        .type("tool_use")
                        .id("toolu-2")
                        .name("nq_pe_procurement_comparison_supplier_options")
                        .input(Map.of("productId", "P-100"))
                        .build()))
                .build();
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(duplicateQuery)
                .thenReturn(repeatedDuplicateQuery)
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Use the first supplier result.")
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();
        ChatRequest request = new ChatRequest();
        request.setMessage("Compare suppliers for P-100");
        request.setSessionId("session-1");
        request.setAgentCode("pcba_procurement_comparison_agent");

        service.streamAgentChat(1L, "pcba_procurement_comparison_agent", request, emitter);

        verify(toolLoopService, times(1)).executeToolCall(
                eq(1L),
                any(),
                any(),
                eq("pcba_procurement_comparison_agent"),
                eq("nq_pe_procurement_comparison_supplier_options"),
                eq(Map.of("productId", "P-100")),
                any(),
                isNull(TraceContext.class));
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("tool_result");
            assertThat(event.payload).contains("Duplicate tool call skipped");
        });
        assertThat(emitter.events).noneSatisfy(event -> {
            assertThat(event.name).isEqualTo("error");
            assertThat(event.payload).contains("maximum rounds");
        });
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("done");
            assertThat(event.payload).contains("Use the first supplier result.");
        });
    }

    @Test
    @DisplayName("stored structured tool history is reused across chat turns")
    void storedStructuredToolHistoryIsReusedAcrossTurns() throws Exception {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", "pcba_procurement_comparison_agent",
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}")));
        when(providerFactory.resolveConfig(1L, "openai")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("test-model")
                .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(groundingService.ground(eq(1L), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("create_draft")
                .object("pe_procurement_comparison")
                .riskLevel("L2")
                .actionability("propose")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of());

        ObjectMapper mapper = new ObjectMapper();
        List<LlmChatRequest.Message> previousMessages = List.of(
                LlmChatRequest.Message.builder()
                        .role("user")
                        .content("Compare suppliers for P-100")
                        .build(),
                LlmChatRequest.Message.builder()
                        .role("assistant")
                        .content(List.of(LlmChatRequest.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu-1")
                                .name("nq_pe_procurement_comparison_supplier_options")
                                .input(Map.of("productId", "P-100"))
                                .build()))
                        .build(),
                LlmChatRequest.Message.builder()
                        .role("user")
                        .content(List.of(LlmChatRequest.ContentBlock.builder()
                                .type("tool_result")
                                .toolUseId("toolu-1")
                                .result("{\"records\":[{\"supplier\":\"Acme PCB\",\"supplier_id\":\"S-1\"}]}")
                                .build()))
                        .build(),
                LlmChatRequest.Message.builder()
                        .role("assistant")
                        .content(List.of(LlmChatRequest.ContentBlock.builder()
                                .type("text")
                                .text("Acme PCB is recommended.")
                                .build()))
                        .build());
        List<Map<String, Object>> storedMessages = mapper.convertValue(
                previousMessages,
                new com.fasterxml.jackson.core.type.TypeReference<>() {});
        when(chatSessionStore.loadConversationMessages("session-1")).thenReturn(storedMessages);
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Ready to create the draft.")
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();
        ChatRequest request = new ChatRequest();
        request.setMessage("Confirm draft creation");
        request.setSessionId("session-1");
        request.setAgentCode("pcba_procurement_comparison_agent");
        request.setHistory(List.of(new com.auraboot.framework.aurabot.dto.ChatMessage(
                "assistant", "stale frontend history without tool_result")));

        service.streamAgentChat(1L, "pcba_procurement_comparison_agent", request, emitter);

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        String messagesText = String.valueOf(requestCaptor.getValue().getMessages());
        assertThat(messagesText).contains("Acme PCB", "tool_result", "Confirm draft creation");
        assertThat(messagesText).doesNotContain("stale frontend history");
    }

    @Test
    @DisplayName("agent declared tools constrain discovery and page context supplies model fallback")
    void agentDeclaredToolsFilterDiscoveryAndUsePageContextFallback() throws Exception {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", "pcba_procurement_comparison_agent",
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}",
                "tools", "[\"nq:pe_procurement_comparison_supplier_options\",\"cmd:pe:create_procurement_comparison_draft\"]",
                "allowed_models", "[\"pe_procurement_comparison\"]")));
        when(providerFactory.resolveConfig(1L, "openai")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("test-model")
                .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(groundingService.ground(eq(1L), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("compare")
                .object(null)
                .riskLevel("L0")
                .actionability("read_only")
                .confidence(ConfidenceScore.of(0.9, 0.1))
                .build());
        ToolDefinition queryTool = ToolDefinition.builder()
                .toolCode("nq:pe_procurement_comparison_supplier_options")
                .description("Supplier options")
                .toolType("dsl_query")
                .sourceCode("pe_procurement_comparison_supplier_options")
                .riskLevel("L0")
                .confirmationPolicy("none")
                .build();
        ToolDefinition commandTool = ToolDefinition.builder()
                .toolCode("cmd:pe:create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();
        ToolDefinition platformTool = ToolDefinition.builder()
                .toolCode("platform.execute_sql")
                .description("Generic SQL")
                .toolType("platform")
                .sourceCode("platform.execute_sql")
                .riskLevel("L1")
                .build();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(
                List.of(queryTool, commandTool, platformTool));
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Ready.")
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();
        ChatRequest request = new ChatRequest();
        request.setMessage("Compare suppliers for P-100");
        request.setSessionId("session-1");
        request.setAgentCode("pcba_procurement_comparison_agent");
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setModelCode("pe_procurement_comparison");
        request.setPageContext(pageContext);

        service.streamAgentChat(1L, "pcba_procurement_comparison_agent", request, emitter);

        ArgumentCaptor<ToolDiscoveryContext> ctxCaptor = ArgumentCaptor.forClass(ToolDiscoveryContext.class);
        verify(toolProviderRegistry).discoverAll(ctxCaptor.capture());
        assertThat(ctxCaptor.getValue().getModelHint()).isEqualTo("pe_procurement_comparison");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(provider).chat(requestCaptor.capture(), eq("test-key"), eq("https://example.invalid"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly(
                        "nq_pe_procurement_comparison_supplier_options",
                        "cmd_pe_create_procurement_comparison_draft");
    }

    @Test
    @DisplayName("L2 command tool emits confirmation event and stores pending context before execution")
    void l2CommandStoresPendingConfirmationBeforeExecution() throws Exception {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", "pcba_procurement_comparison_agent",
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}")));
        when(providerFactory.resolveConfig(1L, "openai")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("test-model")
                .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(groundingService.ground(eq(1L), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("create_draft")
                .object("pe_procurement_comparison")
                .riskLevel("L2")
                .actionability("propose")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        ToolDefinition createDraftTool = ToolDefinition.builder()
                .toolCode("cmd:pe:create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of(createDraftTool));

        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu-create-draft")
                                .name("cmd_pe_create_procurement_comparison_draft")
                                .input(Map.of("productId", "P-100"))
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();
        ChatRequest request = new ChatRequest();
        request.setMessage("Create a draft for P-100");
        request.setSessionId("session-1");
        request.setAgentCode("pcba_procurement_comparison_agent");

        service.streamAgentChat(1L, "pcba_procurement_comparison_agent", request, emitter);

        verify(toolLoopService, never()).executeToolCall(anyLong(), any(), any(), any(), any(), any(), any(), any());
        verify(chatSessionStore).storePending(eq("session-1"), any(ChatSessionStore.PendingTool.class));
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("confirm_required");
            assertThat(event.payload).contains("toolu-create-draft");
            assertThat(event.payload).contains("cmd_pe_create_procurement_comparison_draft");
        });
        assertThat(emitter.events).anySatisfy(event -> assertThat(event.name).isEqualTo("done"));
    }

    @Test
    @DisplayName("L2 command with blank required input is corrected before confirmation")
    void l2CommandBlankRequiredInputIsCorrectedBeforeConfirmation() throws Exception {
        when(dynamicDataMapper.selectByQuery(any(), anyMap())).thenReturn(List.of(Map.of(
                "agent_code", "pcba_procurement_comparison_agent",
                "name", "PCBA Procurement Advisor",
                "status", "active",
                "model", "test-model",
                "system_prompt", "Compare suppliers.",
                "guardrails", "{\"provider\":\"openai\"}")));
        when(providerFactory.resolveConfig(1L, "openai")).thenReturn(LlmProviderFactory.ProviderConfig.builder()
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .defaultModel("test-model")
                .build());
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(groundingService.ground(eq(1L), any(), any())).thenReturn(BusinessIntentFrame.builder()
                .intent("create_draft")
                .object("pe_procurement_comparison")
                .riskLevel("L2")
                .actionability("propose")
                .confidence(ConfidenceScore.of(0.9, 0.9))
                .build());
        ToolDefinition createDraftTool = ToolDefinition.builder()
                .toolCode("cmd:pe:create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .parameterSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "pe_pc_code", Map.of("type", "string"),
                                "pe_pc_product_id", Map.of("type", "string")),
                        "required", List.of("pe_pc_code", "pe_pc_product_id")))
                .build();
        when(toolProviderRegistry.discoverAll(any(ToolDiscoveryContext.class))).thenReturn(List.of(createDraftTool));

        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu-invalid")
                                .name("cmd_pe_create_procurement_comparison_draft")
                                .input(Map.of("pe_pc_code", "", "pe_pc_product_id", "P-100"))
                                .build()))
                        .build())
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu-valid")
                                .name("cmd_pe_create_procurement_comparison_draft")
                                .input(Map.of("pe_pc_code", "PCBA-CMP-001", "pe_pc_product_id", "P-100"))
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();
        ChatRequest request = new ChatRequest();
        request.setMessage("Create a draft for P-100");
        request.setSessionId("session-1");
        request.setAgentCode("pcba_procurement_comparison_agent");

        service.streamAgentChat(1L, "pcba_procurement_comparison_agent", request, emitter);

        verify(toolLoopService, never()).executeToolCall(anyLong(), any(), any(), any(), any(), any(), any(), any());
        ArgumentCaptor<ChatSessionStore.PendingTool> pendingCaptor =
                ArgumentCaptor.forClass(ChatSessionStore.PendingTool.class);
        verify(chatSessionStore).storePending(eq("session-1"), pendingCaptor.capture());
        assertThat(pendingCaptor.getValue().getToolId()).isEqualTo("toolu-valid");
        assertThat(pendingCaptor.getValue().getInput()).containsEntry("pe_pc_code", "PCBA-CMP-001");
        verify(provider, times(2)).chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid"));
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("tool_result");
            assertThat(event.payload).contains("Tool input validation failed before confirmation", "pe_pc_code");
        });
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("confirm_required");
            assertThat(event.payload).contains("toolu-valid", "PCBA-CMP-001");
        });
    }

    @Test
    @DisplayName("confirmed custom-agent pending tool resumes through ToolLoopService and completes LLM response")
    void confirmedPendingToolResumesThroughToolLoopService() throws Exception {
        AgentToolDefinition createDraftTool = AgentToolDefinition.builder()
                .name("cmd_pe_create_procurement_comparison_draft")
                .description("Create comparison draft")
                .toolType("dsl_command")
                .sourceCode("pe:create_procurement_comparison_draft")
                .riskLevel("L2")
                .confirmationPolicy("confirm")
                .requiresConfirmation(true)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        List<LlmChatRequest.Message> messages = List.of(
                LlmChatRequest.Message.builder()
                        .role("user")
                        .content("Create a draft for P-100")
                        .build(),
                LlmChatRequest.Message.builder()
                        .role("assistant")
                        .content(List.of(LlmChatRequest.ContentBlock.builder()
                                .type("tool_use")
                                .id("toolu-create-draft")
                                .name("cmd_pe_create_procurement_comparison_draft")
                                .input(Map.of("productId", "P-100"))
                                .build()))
                        .build());
        ChatSessionStore.PendingTool pending = ChatSessionStore.PendingTool.builder()
                .toolId("toolu-create-draft")
                .toolName("cmd_pe_create_procurement_comparison_draft")
                .input(Map.of("productId", "P-100"))
                .description("Create comparison draft")
                .agentCode("pcba_procurement_comparison_agent")
                .runPid("chat-run-1")
                .taskPid("session-1")
                .agentToolDefinitions(List.of(createDraftTool))
                .messages(mapper.convertValue(messages, new com.fasterxml.jackson.core.type.TypeReference<>() {}))
                .providerCode("openai")
                .apiKey("test-key")
                .baseUrl("https://example.invalid")
                .model("test-model")
                .systemPrompt("Compare suppliers.")
                .maxTokens(4096)
                .currentLoop(0)
                .build();
        when(providerFactory.getProvider("openai")).thenReturn(provider);
        when(toolLoopService.executeToolCall(
                eq(1L),
                eq("chat-run-1"),
                eq("session-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(Map.of("productId", "P-100")),
                any(),
                isNull(TraceContext.class)))
                .thenReturn("{\"success\":true,\"data\":{\"pid\":\"PC-1\"},\"message\":\"Draft created\"}");
        when(provider.chat(any(LlmChatRequest.class), eq("test-key"), eq("https://example.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("Draft PC-1 is ready.")
                                .build()))
                        .build());

        CapturingEmitter emitter = new CapturingEmitter();

        boolean handled = service.resumeAgentToolAfterConfirmation(1L, pending, true, emitter);

        assertThat(handled).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AgentToolDefinition>> toolDefinitionsCaptor = ArgumentCaptor.forClass(List.class);
        verify(toolLoopService).executeToolCall(
                eq(1L),
                eq("chat-run-1"),
                eq("session-1"),
                eq("pcba_procurement_comparison_agent"),
                eq("cmd_pe_create_procurement_comparison_draft"),
                eq(Map.of("productId", "P-100")),
                toolDefinitionsCaptor.capture(),
                isNull(TraceContext.class));
        assertThat(toolDefinitionsCaptor.getValue()).singleElement().satisfies(tool -> {
            assertThat(tool.getName()).isEqualTo("cmd_pe_create_procurement_comparison_draft");
            assertThat(tool.isRequiresConfirmation()).isFalse();
            assertThat(tool.getRiskLevel()).isEqualTo("L2");
        });
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("tool_result");
            assertThat(event.payload).contains("PC-1");
        });
        assertThat(emitter.events).anySatisfy(event -> {
            assertThat(event.name).isEqualTo("done");
            assertThat(event.payload).contains("Draft PC-1 is ready.");
        });
    }

    private static class CapturingEmitter extends SseEmitter {
        final List<CapturedEvent> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) {
            String name = "message";
            Object payload = null;
            for (var entry : builder.build()) {
                Object data = entry.getData();
                if (data instanceof String text) {
                    int eventIndex = text.indexOf("event:");
                    if (eventIndex >= 0) {
                        String tail = text.substring(eventIndex + 6);
                        int newline = tail.indexOf('\n');
                        name = (newline >= 0 ? tail.substring(0, newline) : tail).trim();
                    } else if (!text.startsWith("data:") && !text.isBlank()
                            && !"\n".equals(text) && !"\n\n".equals(text) && !":".equals(text)) {
                        payload = text;
                    }
                } else if (data != null) {
                    payload = data;
                }
            }
            events.add(new CapturedEvent(name, payload == null ? "" : payload.toString()));
        }
    }

    private record CapturedEvent(String name, String payload) {}
}
