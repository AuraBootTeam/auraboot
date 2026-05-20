package com.auraboot.framework.aurabot.service;

import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.dto.LlmChunk;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.agent.provider.StubLlmProvider;
import com.auraboot.framework.agent.runtime.ChatTurnRuntime;
import com.auraboot.framework.agent.service.GroundingService;
import com.auraboot.framework.agent.trace.AiTraceService;
import com.auraboot.framework.aurabot.dto.ChatRequest;
import com.auraboot.framework.application.tenant.MetaContext;
import com.auraboot.framework.conversation.ResponseSink;
import com.auraboot.framework.conversation.TurnContext;
import com.auraboot.framework.conversation.TurnOutcome;
import com.auraboot.framework.meta.service.MetaModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
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
        verify(chatToolResolver, never()).resolveTools(any(), any(), any());
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
        when(chatToolResolver.resolveTools(eq("hello"), eq(null), eq(null)))
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
        when(chatToolResolver.resolveTools(eq("hello from openai"), eq(null), eq(null)))
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
                .contains("recordIds=[CUST-1]")
                .contains("sensitivity=CONFIDENTIAL")
                .contains("<user-data>");
    }
}
