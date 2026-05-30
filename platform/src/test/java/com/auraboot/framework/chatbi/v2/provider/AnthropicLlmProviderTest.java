package com.auraboot.framework.chatbi.v2.provider;

import com.auraboot.framework.agent.dto.LlmChatRequest;
import com.auraboot.framework.agent.dto.LlmChatResponse;
import com.auraboot.framework.agent.provider.LlmProvider;
import com.auraboot.framework.agent.provider.LlmProviderFactory;
import com.auraboot.framework.chatbi.v2.service.LlmAuditService;
import com.auraboot.framework.semantic.dto.SemanticMetaResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnthropicLlmProviderTest {

    private LlmProviderFactory factory;
    private LlmProvider wireProvider;
    private LlmAuditService audit;
    private AnthropicLlmProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        factory = mock(LlmProviderFactory.class);
        wireProvider = mock(LlmProvider.class);
        audit = mock(LlmAuditService.class);

        ObjectProvider<LlmProviderFactory> op = mock(ObjectProvider.class);
        when(op.getIfAvailable()).thenReturn(factory);

        provider = new AnthropicLlmProvider(op, audit);
        AnswerCorrelation.set("ans-pid", "conv-pid");
    }

    @AfterEach
    void teardown() {
        AnswerCorrelation.clear();
    }

    private void stubFactory(String model) {
        LlmProviderFactory.ProviderConfig cfg = LlmProviderFactory.ProviderConfig.builder()
                .providerCode("anthropic")
                .apiKey("sk-test")
                .baseUrl("https://api.anthropic.com")
                .defaultModel(model)
                .maxTokens(4096)
                .build();
        LlmProviderFactory.ProviderResolution res = LlmProviderFactory.ProviderResolution.builder()
                .requestedProviderCode("anthropic")
                .effectiveProviderCode("anthropic")
                .config(cfg)
                .provider(wireProvider)
                .build();
        when(factory.resolveProvider(any(), eq("anthropic"))).thenReturn(res);
    }

    private LlmChatResponse textResponse(String text, int inTokens, int outTokens) {
        LlmChatResponse.ContentBlock block = LlmChatResponse.ContentBlock.builder()
                .type("text")
                .text(text)
                .build();
        return LlmChatResponse.builder()
                .stopReason("end_turn")
                .content(List.of(block))
                .inputTokens(inTokens)
                .outputTokens(outTokens)
                .build();
    }

    @Test
    void translateHappyPathParsesAndAuditsSuccess() throws Exception {
        stubFactory("claude-sonnet-4-7");
        when(wireProvider.estimateCost(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(0.001234);   // dollars
        String llmJson = "{\"tokens\":[{\"type\":\"METRIC\",\"rawText\":\"sales\","
                + "\"resolvedCode\":\"sales.total_sales\",\"position\":0}],"
                + "\"confidence\":0.93,\"needsClarification\":false,\"suggestedFollowUps\":[]}";
        when(wireProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse(llmJson, 1200, 80));

        IntentResult result = provider.translate("sales by month",
                new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(result.confidence()).isEqualTo(0.93);
        assertThat(result.tokens()).hasSize(1);
        assertThat(result.usage().model()).isEqualTo("claude-sonnet-4-7");
        assertThat(result.usage().promptTokens()).isEqualTo(1200);
        assertThat(result.usage().completionTokens()).isEqualTo(80);
        assertThat(result.usage().costCents()).isCloseTo(0.1234,
                org.assertj.core.data.Offset.offset(1e-6)); // 0.001234 USD * 100c
        assertThat(result.usage().latencyMs()).isPositive();

        ArgumentCaptor<LlmUsage> usageCap = ArgumentCaptor.forClass(LlmUsage.class);
        verify(audit).recordSuccess(any(), eq("ans-pid"), eq("conv-pid"), usageCap.capture());
        assertThat(usageCap.getValue().model()).isEqualTo("claude-sonnet-4-7");
        assertThat(usageCap.getValue().totalTokens()).isEqualTo(1280);
        verify(audit, never()).recordFailure(any(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void translateBlankQuestionReturnsEmptyAndSkipsWire() throws Exception {
        IntentResult r = provider.translate("  ", new SemanticMetaResponse(), null);
        assertThat(r.confidence()).isZero();
        verify(wireProvider, never()).chat(any(), anyString(), anyString());
        verify(audit, never()).recordSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    void translateWireFailureReturnsEmptyAndAuditsFailure() throws Exception {
        stubFactory("claude-sonnet-4-7");
        when(wireProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        IntentResult result = provider.translate("sales by month",
                new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(result.confidence()).isZero();
        assertThat(result.tokens()).isEmpty();
        verify(audit).recordFailure(any(), eq("ans-pid"), eq("conv-pid"), any(LlmUsage.class),
                eq("RuntimeException"));
        verify(audit, never()).recordSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    void translateUnresolvableConfigReturnsEmptyWithoutAudit() throws Exception {
        when(factory.resolveProvider(any(), eq("anthropic"))).thenReturn(null);

        IntentResult result = provider.translate("hello",
                new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(result.confidence()).isZero();
        verify(wireProvider, never()).chat(any(), anyString(), anyString());
        verify(audit, never()).recordSuccess(any(), anyString(), anyString(), any());
    }

    @Test
    void translateMalformedLlmResponseDowngradesButStillAuditsSuccess() throws Exception {
        stubFactory("claude-sonnet-4-7");
        when(wireProvider.estimateCost(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(0.0);
        when(wireProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse("I cannot answer that.", 800, 12));

        IntentResult r = provider.translate("???",
                new SemanticMetaResponse(), ConversationContext.empty());

        assertThat(r.confidence()).isZero();
        assertThat(r.tokens()).isEmpty();
        // Wire call succeeded → audit success (tokens billed even on parse miss).
        verify(audit).recordSuccess(any(), eq("ans-pid"), eq("conv-pid"), any(LlmUsage.class));
    }

    @Test
    void translateMissingCorrelationFallsBackToBlank() throws Exception {
        AnswerCorrelation.clear();
        stubFactory("claude-sonnet-4-7");
        when(wireProvider.estimateCost(anyString(), anyInt(), anyInt(), anyInt(), anyInt()))
                .thenReturn(0.0);
        when(wireProvider.chat(any(LlmChatRequest.class), anyString(), anyString()))
                .thenReturn(textResponse("{\"tokens\":[],\"confidence\":0.5,\"suggestedFollowUps\":[]}",
                        50, 10));

        IntentResult r = provider.translate("x", new SemanticMetaResponse(),
                ConversationContext.empty());
        assertThat(r.confidence()).isEqualTo(0.5);
        // Correlation null → audit still called with null pids.
        verify(audit).recordSuccess(any(), eq((String) null), eq((String) null), any());
    }

    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
