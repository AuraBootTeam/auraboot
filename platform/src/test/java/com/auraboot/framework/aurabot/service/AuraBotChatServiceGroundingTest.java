package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.AgentToolDefinition;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.StubLlmProvider;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.runtime.PendingToolSnapshot;
import com.auraboot.framework.agent.runtime.PendingToolSnapshotFactory;
import com.auraboot.framework.agent.runtime.PendingToolStore;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.service.MetaModelService;
import com.auraboot.framework.permission.service.UserPermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuraBotChatServiceGroundingTest {

    @Mock private LlmProviderFactory llmProviderFactory;
    @Mock private PromptTemplateService promptTemplateService;
    @Mock private ChatToolResolver chatToolResolver;
    @Mock private ChatToolExecutor chatToolExecutor;
    @Mock private AiTraceService aiTraceService;
    @Mock private MetaModelService metaModelService;
    @Mock private GroundingService groundingService;
    @Mock private LlmProvider stubProvider;
    @Mock private LlmProvider openAiProvider;
    @Mock private ResponseSink sink;
    @Mock private UserPermissionService userPermissionService;
    @Mock private PendingToolStore pendingToolStore;
    @Mock private PendingToolSnapshotFactory pendingToolSnapshotFactory;

    @AfterEach
    void tearDown() {
        MetaContext.clear();
    }

    @Test
    @DisplayName("grounding failure fails closed instead of falling back to tool resolver")
    void groundingFailureFailsClosedInsteadOfFallingBackToToolResolver() {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "groundingService", groundingService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setMessage("list customers");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("anthropic");
        request.setOptions(options);
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setKind("list");
        pageContext.setModelCode("crm_customer");
        request.setPageContext(pageContext);

        when(llmProviderFactory.resolveConfig(eq(1L), eq("anthropic")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("anthropic")
                        .apiKey("test-key")
                        .baseUrl("https://llm.invalid")
                        .defaultModel("claude-test")
                        .maxTokens(4096)
                        .build());
        when(groundingService.ground(anyLong(), eq("list customers"), any()))
                .thenThrow(new IllegalStateException("semantic store unavailable"));

        TurnOutcome outcome = service.executeAuraBotTurn(
                TurnContext.legacyDefault(1L, 100L, 100L),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Failed.class);
        assertThat(((TurnOutcome.Failed) outcome).errorMessage())
                .contains("D1 grounding failed")
                .contains("semantic store unavailable");
        verify(sink).onError(
                eq("D1 grounding failed: semantic store unavailable"),
                eq(null));
        verify(chatToolResolver, never()).resolveTools(any(), any(), any(), any());
    }

    @Test
    @DisplayName("stub provider config streams through provider abstraction instead of raw HTTP")
    void stubProviderConfigStreamsThroughProviderAbstractionInsteadOfRawHttp() {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setMessage("hello");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("anthropic");
        request.setOptions(options);

        when(llmProviderFactory.resolveConfig(eq(1L), eq("anthropic")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode(StubLlmProvider.PROVIDER_CODE)
                        .apiKey(StubLlmProvider.STUB_API_KEY_SENTINEL)
                        .baseUrl("stub://local")
                        .defaultModel("stub-model")
                        .maxTokens(4096)
                        .build());
        when(chatToolResolver.resolveTools(eq("hello"), eq(null), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(List.of(), null, null, true));
        when(llmProviderFactory.getProvider(eq(StubLlmProvider.PROVIDER_CODE)))
                .thenReturn(stubProvider);
        when(stubProvider.streamChat(any(), eq(StubLlmProvider.STUB_API_KEY_SENTINEL), eq("stub://local")))
                .thenReturn(Flux.just(
                        LlmChunk.delta(0, "[stub response]"),
                        LlmChunk.done(1, LlmChatResponse.builder()
                                .stopReason("end_turn")
                                .content(List.of(LlmChatResponse.ContentBlock.builder()
                                        .type("text")
                                        .text("[stub response]")
                                        .build()))
                                .build())));

        TurnOutcome outcome = service.executeAuraBotTurn(
                TurnContext.legacyDefault(1L, 100L, 100L),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        assertThat(((TurnOutcome.Success) outcome).finalResponse()).isEqualTo("[stub response]");
        verify(stubProvider).streamChat(any(), eq(StubLlmProvider.STUB_API_KEY_SENTINEL), eq("stub://local"));
        verify(sink).onTextChunk("[stub response]");
        verify(sink).onDone("[stub response]", null);
        verify(sink, never()).onError(any(), any());
    }

    @Test
    @DisplayName("non-stub provider config streams through provider abstraction instead of raw HTTP")
    void nonStubProviderConfigStreamsThroughProviderAbstractionInsteadOfRawHttp() {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-1");
        request.setMessage("hello from openai");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("openai");
        request.setOptions(options);

        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("stub://local")
                        .defaultModel("gpt-test")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.listAllProviders()).thenReturn(List.of());
        when(chatToolResolver.resolveTools(eq("hello from openai"), eq(null), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(List.of(), null, null, true));
        when(llmProviderFactory.getProvider(eq("openai"))).thenReturn(openAiProvider);
        when(openAiProvider.streamChat(any(), eq("test-key"), eq("stub://local")))
                .thenReturn(Flux.just(
                        LlmChunk.delta(0, "provider-stream response"),
                        LlmChunk.done(1, LlmChatResponse.builder()
                                .stopReason("end_turn")
                                .content(List.of(LlmChatResponse.ContentBlock.builder()
                                        .type("text")
                                        .text("provider-stream response")
                                        .build()))
                                .build())));

        TurnOutcome outcome = service.executeAuraBotTurn(
                TurnContext.legacyDefault(1L, 100L, 100L),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        assertThat(((TurnOutcome.Success) outcome).finalResponse()).isEqualTo("provider-stream response");
        verify(openAiProvider).streamChat(any(), eq("test-key"), eq("stub://local"));
        verify(sink).onTextChunk("provider-stream response");
        verify(sink).onDone("provider-stream response", null);
        verify(sink, never()).onError(any(), any());
    }

    @Test
    @DisplayName("resolved read-only tools run through ChatTurnRuntime tool loop")
    void resolvedReadOnlyToolsUseToolLoopInsteadOfPlainTextStream() throws Exception {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-tools");
        request.setMessage("统计客户信息");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("openai");
        request.setOptions(options);
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setKind("list");
        pageContext.setModelCode("crm_customer");
        request.setPageContext(pageContext);

        LlmChatRequest.Tool statsTool = LlmChatRequest.Tool.builder()
                .name("platform_execute_sql")
                .description("Execute read-only SQL")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://llm.invalid")
                        .defaultModel("gpt-test")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.listAllProviders()).thenReturn(List.of());
        when(chatToolResolver.resolveTools(eq("统计客户信息"), eq("crm_customer"), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(
                        List.of(statsTool),
                        "stats",
                        "crm_customer",
                        true));
        when(chatToolResolver.isReadOnly("platform_execute_sql")).thenReturn(true);
        when(llmProviderFactory.getProvider(eq("openai"))).thenReturn(openAiProvider);
        when(openAiProvider.chat(any(), eq("test-key"), eq("https://llm.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-stats")
                                .name("platform_execute_sql")
                                .input(Map.of("sql", "select count(*) from mt_crm_customer where tenant_id = :tenantId"))
                                .build()))
                        .build())
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("客户总数为 3。")
                                .build()))
                        .build());
        when(chatToolExecutor.execute(
                eq("platform_execute_sql"),
                any(),
                eq("crm_customer"),
                eq("turn-tools"),
                eq(null),
                eq("aurabot")))
                .thenReturn(Map.of("success", true, "rows", List.of(Map.of("count", 3))));

        TurnOutcome outcome = service.executeAuraBotTurn(
                new TurnContext("turn-tools", 1L, 100L, null, null, "aurabot",
                        null, null, null, null, java.util.Set.of(), null, null, java.time.Instant.now()),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        assertThat(((TurnOutcome.Success) outcome).finalResponse()).isEqualTo("客户总数为 3。");

        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(openAiProvider, times(2)).chat(requestCaptor.capture(), eq("test-key"), eq("https://llm.invalid"));
        assertThat(requestCaptor.getAllValues().get(0).getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("platform_execute_sql");
        verify(chatToolExecutor).execute(
                eq("platform_execute_sql"),
                any(),
                eq("crm_customer"),
                eq("turn-tools"),
                eq(null),
                eq("aurabot"));
        verify(sink).onDone("客户总数为 3。", null);
    }

    @Test
    @DisplayName("AuraBot tool loop uses grounded object as model code when page context is absent")
    void resolvedObjectProvidesModelCodeWhenPageContextIsAbsent() throws Exception {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-grounded-object");
        request.setMessage("统计客户信息");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("openai");
        request.setOptions(options);

        LlmChatRequest.Tool statsTool = LlmChatRequest.Tool.builder()
                .name("nq_crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://llm.invalid")
                        .defaultModel("gpt-test")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.listAllProviders()).thenReturn(List.of());
        when(chatToolResolver.resolveTools(eq("统计客户信息"), eq(null), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(
                        List.of(statsTool),
                        "stats",
                        "crm_customer",
                        true));
        when(chatToolResolver.isReadOnly("nq_crm_customer_stats")).thenReturn(true);
        when(llmProviderFactory.getProvider(eq("openai"))).thenReturn(openAiProvider);
        when(openAiProvider.chat(any(), eq("test-key"), eq("https://llm.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-grounded-object")
                                .name("nq_crm_customer_stats")
                                .input(Map.of("groupBy", "industry"))
                                .build()))
                        .build())
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("客户行业统计已完成。")
                                .build()))
                        .build());
        when(chatToolExecutor.execute(
                eq("nq_crm_customer_stats"),
                any(),
                eq("crm_customer"),
                eq("turn-grounded-object"),
                eq(null),
                eq("aurabot")))
                .thenReturn(Map.of("success", true, "rows", List.of()));

        TurnOutcome outcome = service.executeAuraBotTurn(
                new TurnContext("turn-grounded-object", 1L, 100L, null, null, "aurabot",
                        null, null, null, null, java.util.Set.of(), null, null, java.time.Instant.now()),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(chatToolExecutor).execute(
                eq("nq_crm_customer_stats"),
                any(),
                eq("crm_customer"),
                eq("turn-grounded-object"),
                eq(null),
                eq("aurabot"));
    }

    @Test
    @DisplayName("AuraBot discovered provider-code tools remain executable after LLM-name sanitization")
    void discoveredProviderCodeToolsRemainExecutableAfterSanitization() throws Exception {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-sanitized-provider-tool");
        request.setMessage("统计客户信息");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("openai");
        request.setOptions(options);
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setKind("list");
        pageContext.setModelCode("crm_customer");
        request.setPageContext(pageContext);

        LlmChatRequest.Tool statsTool = LlmChatRequest.Tool.builder()
                .name("nq_crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .build();
        AgentToolDefinition discovered = AgentToolDefinition.builder()
                .name("nq:crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .toolType("built_in")
                .sourceCode("nq:crm_customer_stats")
                .riskLevel("L0")
                .build();
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://llm.invalid")
                        .defaultModel("gpt-test")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.listAllProviders()).thenReturn(List.of());
        when(chatToolResolver.resolveTools(eq("统计客户信息"), eq("crm_customer"), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(
                        List.of(statsTool),
                        "stats",
                        "crm_customer",
                        true));
        when(chatToolResolver.getAgentToolDefinition("nq_crm_customer_stats")).thenReturn(discovered);
        when(chatToolResolver.isReadOnly("nq_crm_customer_stats")).thenReturn(true);
        when(llmProviderFactory.getProvider(eq("openai"))).thenReturn(openAiProvider);
        when(openAiProvider.chat(any(), eq("test-key"), eq("https://llm.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-sanitized-provider")
                                .name("nq_crm_customer_stats")
                                .input(Map.of("groupBy", "industry"))
                                .build()))
                        .build())
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("客户行业统计已完成。")
                                .build()))
                        .build());
        when(chatToolExecutor.execute(
                eq("nq_crm_customer_stats"),
                any(),
                eq("crm_customer"),
                eq("turn-sanitized-provider-tool"),
                eq(null),
                eq("aurabot")))
                .thenReturn(Map.of("success", true, "rows", List.of()));

        TurnOutcome outcome = service.executeAuraBotTurn(
                new TurnContext("turn-sanitized-provider-tool", 1L, 100L, null, null, "aurabot",
                        null, null, null, null, java.util.Set.of(), null, null, java.time.Instant.now()),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        verify(chatToolExecutor).execute(
                eq("nq_crm_customer_stats"),
                any(),
                eq("crm_customer"),
                eq("turn-sanitized-provider-tool"),
                eq(null),
                eq("aurabot"));
    }

    @Test
    @DisplayName("AuraBot simple write tool suspends as pending confirmation instead of executing immediately")
    void simpleWriteToolSuspendsAsPendingConfirmation() throws Exception {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "pendingToolStore", pendingToolStore);
        ReflectionTestUtils.setField(service, "pendingToolSnapshotFactory", pendingToolSnapshotFactory);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-create-customer");
        request.setMessage("创建客户");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("openai");
        request.setOptions(options);
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setKind("list");
        pageContext.setModelCode("crm_customer");
        request.setPageContext(pageContext);

        LlmChatRequest.Tool createTool = LlmChatRequest.Tool.builder()
                .name("cmd_crm_customer_create")
                .description("Create customer")
                .inputSchema(Map.of("type", "object"))
                .build();
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://llm.invalid")
                        .defaultModel("gpt-test")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.listAllProviders()).thenReturn(List.of());
        when(chatToolResolver.resolveTools(eq("创建客户"), eq("crm_customer"), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(
                        List.of(createTool),
                        "create",
                        "crm_customer",
                        false));
        when(chatToolResolver.isReadOnly("cmd_crm_customer_create")).thenReturn(false);
        when(llmProviderFactory.getProvider(eq("openai"))).thenReturn(openAiProvider);
        when(openAiProvider.chat(any(), eq("test-key"), eq("https://llm.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("tool_use")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("tool_use")
                                .id("tool-create")
                                .name("cmd_crm_customer_create")
                                .input(Map.of("name", "Acme"))
                                .build()))
                        .build());
        when(pendingToolSnapshotFactory.build(any(PendingToolSnapshotFactory.Snapshot.class)))
                .thenReturn(PendingToolSnapshot.builder()
                        .turnId("turn-create-customer")
                        .toolId("tool-create")
                        .toolName("cmd_crm_customer_create")
                        .build());

        TurnOutcome outcome = service.executeAuraBotTurn(
                new TurnContext("turn-create-customer", 1L, 100L, null, null, "aurabot",
                        null, null, null, null, java.util.Set.of(), null, null, java.time.Instant.now()),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.PendingConfirmation.class);
        TurnOutcome.PendingConfirmation pending = (TurnOutcome.PendingConfirmation) outcome;
        assertThat(pending.pendingTurnId()).isEqualTo("turn-create-customer");
        assertThat(pending.pendingToolId()).isEqualTo("tool-create");
        verify(sink).onConfirmRequired(
                eq("tool-create"),
                eq("cmd_crm_customer_create"),
                contains("Execute cmd_crm_customer_create"),
                eq(Map.of("name", "Acme")),
                eq("turn-create-customer"));
        ArgumentCaptor<PendingToolSnapshotFactory.Snapshot> snapshotCaptor =
                ArgumentCaptor.forClass(PendingToolSnapshotFactory.Snapshot.class);
        verify(pendingToolSnapshotFactory).build(snapshotCaptor.capture());
        PendingToolSnapshotFactory.Snapshot snapshot = snapshotCaptor.getValue();
        assertThat(snapshot.getToolId()).isEqualTo("tool-create");
        assertThat(snapshot.getToolName()).isEqualTo("cmd_crm_customer_create");
        assertThat(snapshot.getInput()).containsEntry("name", "Acme");
        assertThat(snapshot.getModelCode()).isEqualTo("crm_customer");
        assertThat(snapshot.getProviderCode()).isEqualTo("openai");
        assertThat(snapshot.getContextBlocks()).isNotEmpty();
        verify(pendingToolStore).storePending(eq("turn-create-customer"), any(PendingToolSnapshot.class));
        verify(chatToolExecutor, never()).execute(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("AuraBot tool loop passes granted required permissions into policy catalog filtering")
    void resolvedToolsUseGrantedRequiredPermissionsForCatalogFiltering() throws Exception {
        MetaContext.setContext(1L, 100L, null, "tester");

        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);
        ReflectionTestUtils.setField(service, "userPermissionService", userPermissionService);

        ChatRequest request = new ChatRequest();
        request.setSessionId("session-permissioned-tools");
        request.setMessage("查询客户统计");
        ChatRequest.ChatOptions options = new ChatRequest.ChatOptions();
        options.setProvider("openai");
        request.setOptions(options);

        LlmChatRequest.Tool statsTool = LlmChatRequest.Tool.builder()
                .name("nq_crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .build();
        AgentToolDefinition discovered = AgentToolDefinition.builder()
                .name("nq_crm_customer_stats")
                .description("Customer stats")
                .inputSchema(Map.of("type", "object"))
                .toolType("dsl_query")
                .riskLevel("L0")
                .requiredPermissions(java.util.Set.of("crm.customer.read"))
                .build();
        when(llmProviderFactory.resolveConfig(eq(1L), eq("openai")))
                .thenReturn(LlmProviderFactory.ProviderConfig.builder()
                        .providerCode("openai")
                        .apiKey("test-key")
                        .baseUrl("https://llm.invalid")
                        .defaultModel("gpt-test")
                        .maxTokens(4096)
                        .build());
        lenient().when(llmProviderFactory.listAllProviders()).thenReturn(List.of());
        when(chatToolResolver.resolveTools(eq("查询客户统计"), eq(null), eq(null), any()))
                .thenReturn(new ChatToolResolver.ResolvedTools(
                        List.of(statsTool),
                        "query",
                        "crm_customer",
                        true));
        when(chatToolResolver.getAgentToolDefinition("nq_crm_customer_stats")).thenReturn(discovered);
        when(chatToolResolver.isReadOnly("nq_crm_customer_stats")).thenReturn(true);
        when(userPermissionService.hasPermission(eq(100L), eq("crm.customer.read"))).thenReturn(true);
        when(llmProviderFactory.getProvider(eq("openai"))).thenReturn(openAiProvider);
        when(openAiProvider.chat(any(), eq("test-key"), eq("https://llm.invalid")))
                .thenReturn(LlmChatResponse.builder()
                        .stopReason("end_turn")
                        .content(List.of(LlmChatResponse.ContentBlock.builder()
                                .type("text")
                                .text("可以查询客户统计。")
                                .build()))
                        .build());

        TurnOutcome outcome = service.executeAuraBotTurn(
                new TurnContext("turn-permissioned-tools", 1L, 100L, null, null, "aurabot",
                        null, null, null, null, java.util.Set.of(), null, null, java.time.Instant.now()),
                request,
                sink);

        assertThat(outcome).isInstanceOf(TurnOutcome.Success.class);
        ArgumentCaptor<LlmChatRequest> requestCaptor = ArgumentCaptor.forClass(LlmChatRequest.class);
        verify(openAiProvider).chat(requestCaptor.capture(), eq("test-key"), eq("https://llm.invalid"));
        assertThat(requestCaptor.getValue().getTools())
                .extracting(LlmChatRequest.Tool::getName)
                .containsExactly("nq_crm_customer_stats");
        verify(userPermissionService).hasPermission(100L, "crm.customer.read");
    }

    @Test
    @DisplayName("fallback system prompt labels page context with provenance")
    void fallbackSystemPromptLabelsPageContextWithProvenance() {
        AuraBotChatService service = new AuraBotChatService(
                llmProviderFactory,
                promptTemplateService,
                chatToolResolver,
                chatToolExecutor,
                new ObjectMapper(),
                aiTraceService,
                metaModelService,
                new ChatTurnRuntime(),
                (Executor) Runnable::run);
        ChatRequest request = new ChatRequest();
        request.setMessage("summarize current customer");
        ChatRequest.PageContext pageContext = new ChatRequest.PageContext();
        pageContext.setKind("detail");
        pageContext.setPageKey("crm/customer-detail");
        pageContext.setModelCode("crm_customer");
        pageContext.setRecordPid("CUST-1");
        pageContext.setRecordData(Map.of("name", "Acme"));
        request.setPageContext(pageContext);

        when(promptTemplateService.render(eq(42L), eq("aurabot_chat"), any()))
                .thenReturn("");

        String prompt = service.buildSystemPrompt(42L, request);

        assertThat(prompt)
                .contains("context-provenance source=PAGE")
                .contains("context-provenance source=RECORD")
                .contains("tenant=42")
                .contains("recordPids=[CUST-1]")
                .contains("sensitivity=CONFIDENTIAL")
                .contains("<user-data>");
    }
}
